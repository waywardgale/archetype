package dev.archetype.runtime

import dev.archetype.definitions.*
import java.util.UUID
import java.util.concurrent.ThreadLocalRandom

/** Minecraft objects stay in the adapter. Native calls return actual health deltas. */
interface WorldOps {
    fun validTarget(actor: UUID, target: UUID): Boolean
    fun heal(target: UUID, amount: Double): Double
    fun damage(actor: UUID, target: UUID, amount: Double, damageType: String): Double
    fun availableTarget(actor: UUID, target: UUID): Boolean = validTarget(actor, target)
    fun validTarget(actor: UUID, target: UUID, range: Double): Boolean = validTarget(actor, target)
    fun position(entity: UUID): Position? = null
    fun direction(entity: UUID): Vec = Vec(0.0, 0.0, 1.0)
    fun aim(actor: UUID, range: Double): UUID? = null
    fun ground(actor: UUID, range: Double): Position? = null
    fun loaded(position: Position): Boolean = false
    fun candidates(actor: UUID, origin: Position, radius: Double, limit: Int): List<EntityView> = emptyList()
    fun view(actor: UUID, target: UUID): EntityView? = null
    fun lineOfSight(origin: Position, target: UUID): Boolean = false
    /** Replaces only Archetype's transient additive movement speed bonus; zero removes it. */
    fun movementSpeedBonus(target: UUID, amount: Double) {}
    /** A server-side sample in [0, 1); tests may provide a fixed sequence. */
    fun roll(actor: UUID): Double = ThreadLocalRandom.current().nextDouble()
}

data class PlayerRecord(
    val ownedClasses: MutableSet<String> = linkedSetOf(),
    val activeClasses: MutableSet<String> = linkedSetOf(),
    val resources: MutableMap<String, Double> = linkedMapOf(),
    val cooldowns: MutableMap<String, Int> = linkedMapOf(),
    val regenerationTimers: MutableMap<String, Int> = linkedMapOf(),
    val charges: MutableMap<String, ChargeState> = linkedMapOf(),
)

data class ChargeState(var capacity: Int, var available: Int, var mode: RechargeMode, val timers: MutableList<Int> = mutableListOf())

sealed interface CastResult {
    data object Applied : CastResult
    data class Rejected(val reason: String) : CastResult
    data class Interrupted(val reason: String) : CastResult
}

data class StatusView(val status: String, val owner: UUID, val sourceClass: String, val grant: String, val application: String, val stacks: Int, val remainingTicks: Int?, val membership: Boolean)
data class TimerView(val name: String, val target: UUID, val sourceClass: String, val grant: String, val remainingTicks: Int)

/** All mutation runs on the server tick thread. ASVS 2.3.1, 2.3.4, 15.4.1. */
class AbilityRuntime(private val world: WorldOps, private val catalog: MechanicCatalog = BuiltinEffects.catalog) {
    var definitions = DefinitionSet(emptyMap(), emptyMap(), emptyMap(), emptyMap(), "")
        private set
    var generation: Long = 0
        private set
    private val players = linkedMapOf<UUID, PlayerRecord>()
    private val selection = TargetSelection(world)
    private var estimates = ExecutionEstimates(definitions, catalog)
    private data class Budget(var remaining: Int = 1024)
    private class Lifetime(val parent: Lifetime? = null) {
        var cancelled = false
        val active: Boolean get() = !cancelled && parent?.active != false
    }
    private data class Scope(
        val id: UUID, val owner: UUID, val classId: String, val grant: String, val ability: AbilityDef,
        val target: UUID?, val ground: Position?, val budget: Budget, val lifetime: Lifetime,
        val selected: Boolean = false, val dependencies: MutableMap<String, AreaDef> = linkedMapOf(),
        val targetGuard: TargetGuard? = null,
        val statusDependencies: MutableMap<String, StatusDef> = linkedMapOf(),
        val originDefinition: String = ability.id, val sourceDimension: String? = null,
        val controllerId: UUID? = null,
    )
    private data class TargetGuard(val frame: Frame, val selector: Selector)
    private data class Scheduled(val scope: Scope, val due: Long, val effects: List<Effect>, val bindings: Map<String, Double>, val repeatsLeft: Int = 1, val every: Int = 0)
    private data class ChainTask(val scope: Scope, val due: Long, val effect: Effect.Chain, val origin: Position, val last: UUID, val visited: Set<UUID>, val hits: Int, val bindings: Map<String, Double>)
    private data class Area(
        val id: UUID, val scope: Scope, val definition: AreaDef, val lifetime: Lifetime, val fixed: Frame?, val attached: UUID?,
        val expires: Long, var nextSample: Long, var nextPulse: Long,
        val members: MutableMap<UUID, Lifetime> = linkedMapOf(),
    )
    private data class StatusKey(val owner: UUID, val classId: String, val grant: String, val application: String, val target: UUID, val membership: Lifetime?)
    private data class Contribution(
        val id: UUID, val key: StatusKey, val scope: Scope, val definition: StatusDef, val lifetime: Lifetime,
        val stackExpiries: MutableList<Long>, var nextPulse: Long,
    )
    private data class TimerKey(val owner: UUID, val classId: String, val grant: String, val source: String, val name: String, val target: UUID)
    private data class Timer(val key: TimerKey, val scope: Scope, val lifetime: Lifetime, val due: Long, val expired: List<Effect>, val bindings: Map<String, Double>)
    private data class ControllerDependencies(
        val areas: MutableMap<String, AreaDef>, val statuses: MutableMap<String, StatusDef>, val createdStatuses: Set<String>,
    )
    private val scopes = linkedMapOf<UUID, Scope>()
    private val pending = mutableListOf<Scheduled>()
    private val chains = mutableListOf<ChainTask>()
    private val areas = mutableListOf<Area>()
    private val statuses = linkedMapOf<StatusKey, Contribution>()
    private val statusesByTarget = mutableMapOf<UUID, MutableList<Contribution>>()
    private val timers = linkedMapOf<TimerKey, Timer>()
    private val failures = ArrayDeque<String>()
    private val onlineClock = mutableMapOf<UUID, Long>()
    private val castsThisTick = mutableMapOf<UUID, Int>()
    private val ownerWorkThisTick = mutableMapOf<UUID, Int>()
    private var globalCastsThisTick = 0
    private var workThisTick = 4096

    fun record(player: UUID): PlayerRecord = players.getOrPut(player) { PlayerRecord() }
    fun drainFailures(): List<String> = failures.toList().also { failures.clear() }
    fun statuses(target: UUID): List<StatusView> = statusesByTarget[target].orEmpty().filter { it.lifetime.active }.map {
        StatusView(it.definition.id, it.key.owner, it.key.classId, it.key.grant, it.key.application, it.stackExpiries.size,
            if (it.key.membership != null) null else (it.stackExpiries.max() - now(it.key.owner)).coerceAtLeast(0).toInt(), it.key.membership != null)
    }
    fun timers(owner: UUID): List<TimerView> = timers.values.filter { it.key.owner == owner && it.lifetime.active }.map {
        TimerView(it.key.name, it.key.target, it.key.classId, it.key.grant, (it.due - now(owner)).coerceAtLeast(0).toInt())
    }
    fun shutdown() { cancelWhere { true } }

    fun installRecord(player: UUID, record: PlayerRecord) {
        for ((key, amount) in record.resources.toMap()) definitions.resources[key.substringAfterLast('|')]?.let {
            record.resources[key] = amount.coerceIn(it.minimum, it.maximum)
        }
        players[player] = record
        reconcileCharges(record)
    }

    fun onDeath(player: UUID) {
        cancelTargetStatuses(player)
        cancelTargetTimers(player)
        cancelWhere { it.owner == player }
        val record = players[player] ?: return
        for ((key, _) in record.resources.toMap()) definitions.resources[key.substringAfterLast('|')]?.let { record.resources[key] = it.initial }
    }

    fun onLogout(player: UUID) { cancelTargetStatuses(player); cancelTargetTimers(player); cancelWhere { it.owner == player } }

    fun publish(candidate: DefinitionSet) {
        if (candidate.fingerprint == definitions.fingerprint) return
        cancelWhere { scope ->
            candidate.classes[scope.classId]?.grants?.get(scope.grant)?.ability != scope.ability ||
                scope.dependencies.any { (id, definition) -> candidate.areas[id] != definition } ||
                scope.statusDependencies.any { (id, definition) -> candidate.statuses[id] != definition }
        }
        for (record in players.values) for ((key, amount) in record.resources.toMap()) candidate.resources[key.substringAfterLast('|')]?.let {
            record.resources[key] = amount.coerceIn(it.minimum, it.maximum)
        }
        definitions = candidate
        estimates = ExecutionEstimates(candidate, catalog)
        players.values.forEach(::reconcileCharges)
        generation++
    }

    fun selectClass(player: UUID, classId: String): Boolean {
        if (classId !in definitions.classes) return false
        cancelWhere { it.owner == player && it.classId != classId }
        record(player).apply {
            ownedClasses += classId
            activeClasses.clear()
            activeClasses += classId
        }
        return true
    }

    fun cast(player: UUID, classId: String, grantName: String, target: UUID?, clientGeneration: Long): CastResult {
        if (clientGeneration != generation) return CastResult.Rejected("stale definitions")
        if ((castsThisTick[player] ?: 0) >= 8 || globalCastsThisTick >= 256) return CastResult.Rejected("input limit reached")
        castsThisTick[player] = (castsThisTick[player] ?: 0) + 1
        globalCastsThisTick++
        val record = record(player)
        if (record.activeClasses.firstOrNull() != classId) return CastResult.Rejected("class is not active")
        val grant = definitions.classes[classId]?.grants?.get(grantName) ?: return CastResult.Rejected("unknown grant")
        val ability = grant.ability
        if (ability.activation != Activation.ACTIVATED) return CastResult.Rejected("ability cannot be cast")
        if (!world.availableTarget(player, player)) return CastResult.Rejected("actor is unavailable")
        // ASVS 2.3.1, 2.3.2: authoritative restrictions are checked before any cost, charge, or cooldown commits.
        if (statusesByTarget[player].orEmpty().any { it.lifetime.active && ActionRestriction.ACTIVATE in it.definition.restrictions })
            return CastResult.Rejected("ability activation is restricted")
        val requiresTarget = ability.effects.any { needsTarget(it, ability.targeting.type == Targeting.Type.GROUND) }
        val selectedTarget = target ?: if (requiresTarget) world.aim(player, ability.targeting.range) else null
        if (requiresTarget && (selectedTarget == null || !world.validTarget(player, selectedTarget, ability.targeting.range))) return CastResult.Rejected("target is unavailable")
        val ground = if (ability.targeting.type == Targeting.Type.GROUND) world.ground(player, ability.targeting.range) else null
        if ((ability.targeting.type == Targeting.Type.GROUND || ability.effects.any { needsGround(it) }) && (ground == null || !world.loaded(ground))) return CastResult.Rejected("ground target is unavailable")
        val cooldownKey = "$classId|$grantName"
        val cooldowns = cooldownKeys(cooldownKey, ability)
        if (cooldowns.any { (key, _) -> (record.cooldowns[key] ?: 0) > 0 }) return CastResult.Rejected("cooldown is active")
        val charges = ability.charges?.let { chargeState(record, cooldownKey, it) }
        if (charges != null && charges.available <= 0) return CastResult.Rejected("no ability charges available")
        // Known oversized setup is rejected before payment. Dynamic query work is checked during execution.
        if (estimate(ability.effects) > 1024) return CastResult.Rejected("ability work limit exceeded")
        val (dependencies, statusDependencies, createdStatuses) = controllerDependencies(ability.effects)
        if (dependencies.values.any { area -> (estimate(area.enter) + estimate(area.exit) + estimate(area.periodic) + buffWork(area)) * area.selector.limit + 1 > 1024 || estimate(area.expired) > 1024 }) return CastResult.Rejected("area pulse work limit exceeded")
        if (createdStatuses.any { estimate(statusDependencies.getValue(it).bodies) > 1024 }) return CastResult.Rejected("status pulse work limit exceeded")
        val allBodies = listOf(ability.effects) + dependencies.values.flatMap { listOf(it.enter, it.periodic, it.exit, it.expired) } + statusDependencies.values.map { it.bodies }
        if (allBodies.any { body -> body.flatMap { catalog.descendants(it).toList() }.filterIsInstance<Effect.SetTimer>().any { estimate(it.expired) > 1024 } })
            return CastResult.Rejected("timer expiry work limit exceeded")
        val knownKeys = initialStatusKeys(player, classId, grantName, ability, selectedTarget)
        val refreshedSlots = knownKeys.count { statuses[it]?.lifetime?.active == true }
        val contributions = (estimatedStatuses(ability.effects) - refreshedSlots).coerceAtLeast(0)
        if (contributions + statuses.size > 4096 || contributions + statuses.values.count { it.key.owner == player } > 256) return CastResult.Rejected("status contribution limit reached")
        if (knownKeys.filter { statuses[it]?.lifetime?.active != true }.groupingBy { it.target }.eachCount().any { (id, count) -> count + statusesByTarget[id].orEmpty().count { it.lifetime.active } > 64 }) return CastResult.Rejected("status contribution limit reached")
        val created = estimatedAreas(ability.effects)
        if (created + areas.size > 512 || created + areas.count { it.scope.owner == player } > 32) return CastResult.Rejected("area limit reached")
        val refreshedTimers = initialTimerKeys(player, classId, grantName, selectedTarget, ability.effects).count { timers[it]?.lifetime?.active == true }
        val createdTimers = (estimatedTimers(ability.effects) - refreshedTimers).coerceAtLeast(0)
        if (createdTimers + timers.size > 1024 || createdTimers + timers.values.count { it.key.owner == player } > 64) return CastResult.Rejected("timer limit reached")
        val slots = initialSlots(ability.effects)
        if (slots + pending.size + chains.size > 2048 || slots + pending.count { it.scope.owner == player } + chains.count { it.scope.owner == player } > 128) return CastResult.Rejected("scheduled work limit exceeded")
        val amounts = mutableListOf<Pair<String, Double>>()
        for (cost in ability.costs) {
            val resource = definitions.resources[cost.resource] ?: return CastResult.Rejected("resource is unavailable")
            val amount = try { cost.amount.value(emptyMap()) } catch (_: Exception) { return CastResult.Rejected("invalid cost") }
            if (!amount.isFinite() || amount !in 0.0..1_000_000.0) return CastResult.Rejected("invalid cost")
            val key = balanceKey(classId, resource)
            val already = amounts.filter { it.first == key }.sumOf { it.second }
            if (balance(record, classId, resource) - already - amount < resource.minimum) return CastResult.Rejected("insufficient ${resource.id}")
            amounts += key to amount
        }
        for ((key, amount) in amounts) record.resources[key] = record.resources.getValue(key) - amount
        if (charges != null) {
            // ASVS 2.3.1, 2.3.4: one validated use is consumed in the same server turn as its cost and cooldown.
            charges.available--
            if (charges.mode == RechargeMode.PARALLEL || charges.timers.isEmpty()) charges.timers += ability.charges.rechargeTicks
        }
        // ASVS 2.3.1, 2.3.4: commit every applicable cooldown in the same server turn as costs and charge consumption.
        for ((key, ticks) in cooldowns) if (ticks > 0) record.cooldowns[key] = ticks
        val scope = Scope(UUID.randomUUID(), player, classId, grantName, ability, if (ability.targeting.type == Targeting.Type.GROUND) null else selectedTarget, ground, Budget(), Lifetime(), dependencies = dependencies,
            statusDependencies = statusDependencies, sourceDimension = world.position(player)?.dimension)
        scopes[scope.id] = scope
        try { execute(scope, ability.effects, linkedMapOf()) }
        catch (failure: Exception) {
            fail(scope, failure)
            return CastResult.Interrupted(failure.message ?: "effect failed")
        } finally { prune(releaseIdle = true) }
        return CastResult.Applied
    }

    fun tick(onlinePlayers: Collection<UUID>) {
        castsThisTick.clear()
        ownerWorkThisTick.clear()
        globalCastsThisTick = 0
        workThisTick = 4096
        val online = onlinePlayers.toSet()
        cancelWhere { it.owner !in online || !world.availableTarget(it.owner, it.owner) || (it.sourceDimension != null && world.position(it.owner)?.dimension != it.sourceDimension) }
        for (player in online) {
            onlineClock[player] = now(player) + 1
            val record = players[player] ?: continue
            record.cooldowns.replaceAll { _, ticks -> (ticks - 1).coerceAtLeast(0) }
            tickCharges(record)
            for (resource in definitions.resources.values) {
                val regen = resource.regeneration ?: continue
                val classes = if (resource.scope == ResourceScope.CLASS) record.activeClasses else if (record.activeClasses.isNotEmpty()) setOf("") else emptySet()
                for (classId in classes) {
                    val key = balanceKey(classId, resource)
                    val timer = (record.regenerationTimers[key] ?: regen.everyTicks) - 1
                    if (timer <= 0) {
                        val current = record.resources.getOrPut(key) { resource.initial }
                        record.resources[key] = (current + regen.amount).coerceAtMost(resource.maximum)
                        record.regenerationTimers[key] = regen.everyTicks
                    } else record.regenerationTimers[key] = timer
                }
            }
        }
        // Timed contribution expiry precedes owned controllers; membership reconciliation precedes status pulses.
        for (status in statuses.values.toList()) if (status.lifetime.active) try { tickStatus(status, pulse = false) } catch (failure: Exception) { fail(status.scope, failure) }
        for (area in areas.toList()) if (area.lifetime.active) try { tickArea(area) } catch (failure: Exception) { fail(area.scope, failure) }
        for (status in statuses.values.toList()) if (status.lifetime.active) try { tickStatus(status, pulse = true) } catch (failure: Exception) { fail(status.scope, failure) }
        for (timer in timers.values.toList()) if (timer.lifetime.active && timer.due <= now(timer.key.owner)) try { expireTimer(timer) } catch (failure: Exception) { fail(timer.scope, failure) }
        val due = pending.filter { it.due <= now(it.scope.owner) }.take(256)
        pending.removeAll(due.toSet())
        for (task in due) if (task.scope.lifetime.active) try {
            execute(task.scope, task.effects, task.bindings.toMutableMap())
            if (task.repeatsLeft > 1) schedule(task.scope, task.every, task.effects, task.bindings, task.repeatsLeft - 1, task.every)
        } catch (failure: Exception) { fail(task.scope, failure) }
        val dueChains = chains.filter { it.due <= now(it.scope.owner) }.take(256)
        chains.removeAll(dueChains.toSet())
        for (task in dueChains) if (task.scope.lifetime.active) try { advanceChain(task) } catch (failure: Exception) { fail(task.scope, failure) }
        prune(releaseIdle = true)
    }

    private fun execute(scope: Scope, effects: List<Effect>, results: MutableMap<String, Double>) {
        val context = Execution(scope, results)
        for (effect in effects) {
            if (!scope.lifetime.active) return
            charge(scope, 1)
            val mechanic = catalog.mechanic(effect)
            effect.resultName?.let { name -> mechanic.resultFields.forEach { results.remove("result.$name.$it") } }
            // Target loss skips dependent work. It never reads an earlier target's result or invents zero.
            if (mechanic.inputs(effect).values.any { it.unavailable(results) }) continue
            val outcome = mechanic.run(effect, context)
            if (outcome != null) {
                require(outcome.keys.all { it in mechanic.resultFields } && outcome.values.all { it.isFinite() }) { "invalid mechanic result" }
                effect.resultName?.let { name -> for ((field, value) in outcome) results["result.$name.$field"] = value }
            }
        }
    }

    private inner class Execution(val scope: Scope, val results: MutableMap<String, Double>) : EffectExecution {
        private fun target(target: EffectTarget): UUID? {
            val id = if (target == EffectTarget.ACTOR) scope.owner else scope.target ?: return null
            if (target == EffectTarget.TARGET) scope.targetGuard?.let { guard ->
                val view = world.view(scope.owner, id) ?: return null
                if (!world.loaded(guard.frame.position) || !selection.matches(scope.owner, guard.frame, guard.selector, view)) return null
            }
            return id.takeIf {
                if (target == EffectTarget.ACTOR || scope.selected) world.availableTarget(scope.owner, id)
                else world.validTarget(scope.owner, id, scope.ability.targeting.range)
            }
        }
        override fun heal(target: EffectTarget, amount: Numeric): Healing? {
            val id = target(target) ?: return null
            val requested = amount.amount(results)
            val before = world.view(scope.owner, id)
            val restored = world.heal(id, requested).coerceIn(0.0, requested)
            val missingHealth = before?.let { (it.maximumHealth - it.health).coerceAtLeast(0.0) }
            return Healing(restored, (requested - (missingHealth ?: restored)).coerceAtLeast(0.0))
        }
        override fun damage(target: EffectTarget, amount: Numeric, damageType: String): Double? {
            val id = target(target) ?: return null
            return world.damage(scope.owner, id, amount.amount(results), damageType)
        }
        override fun readHealth(target: EffectTarget): Map<String, Double>? {
            val id = target(target) ?: return null
            val view = world.view(scope.owner, id) ?: return null
            require(view.health.isFinite() && view.maximumHealth.isFinite() && view.maximumHealth > 0.0 && view.health in 0.0..view.maximumHealth) { "invalid native health view" }
            return mapOf("health" to view.health, "maximum" to view.maximumHealth,
                "missing" to view.maximumHealth - view.health, "fraction" to view.health / view.maximumHealth)
        }
        override fun resource(id: String, amount: Numeric, spend: Boolean) = changeResource(record(scope.owner), scope.classId, id, amount.amount(results) * if (spend) -1 else 1)
        override fun setResource(id: String, value: Numeric?): Map<String, Double> {
            val definition = definitions.resources[id] ?: error("resource is unavailable")
            val record = record(scope.owner)
            val key = balanceKey(scope.classId, definition)
            val requested = value?.value(results) ?: definition.initial
            require(requested.isFinite() && requested in -1_000_000_000.0..1_000_000_000.0) { "resource value is out of range" }
            val previous = balance(record, scope.classId, definition)
            val current = requested.coerceIn(definition.minimum, definition.maximum)
            record.resources[key] = current
            return mapOf("previous" to previous, "current" to current, "changed" to kotlin.math.abs(current - previous))
        }
        override fun delay(ticks: Int, effects: List<Effect>, count: Int, every: Int) = schedule(scope, ticks, effects, results, count, every)
        override fun sequence(effects: List<Effect>) = execute(scope, effects, results)
        override fun branch(condition: Condition, onTrue: List<Effect>, onFalse: List<Effect>) {
            val matches = condition(condition) ?: return
            execute(scope, if (matches) onTrue else onFalse, results)
        }

        override fun choose(effect: Effect.Choose): Map<String, Double> {
            val total = effect.options.sumOf(WeightedBranch::weight)
            val selected = minOf((sample() * total).toInt(), total - 1)
            var cumulative = 0
            val index = effect.options.indexOfFirst { option ->
                cumulative += option.weight
                selected < cumulative
            }
            execute(scope, effect.options[index].effects, results)
            return mapOf("index" to index.toDouble())
        }

        private fun sample() = world.roll(scope.owner).also { require(it.isFinite() && it >= 0.0 && it < 1.0) { "invalid chance sample" } }

        private fun condition(condition: Condition): Boolean? = when (condition) {
            is Condition.HasStatus -> target(condition.target)?.let { matchingStatuses(scope, it, condition.filter).isNotEmpty() }
            is Condition.ResourceAtLeast -> {
                val resource = definitions.resources[condition.resource] ?: error("resource is unavailable")
                balance(record(scope.owner), scope.classId, resource) >= condition.amount.value(results)
            }
            is Condition.Compare -> {
                val left = condition.left.value(results)
                val right = condition.right.value(results)
                when (condition.operator) { "lt" -> left < right; "lte" -> left <= right; "eq" -> left == right; "gte" -> left >= right; "gt" -> left > right; else -> error("invalid comparison") }
            }
            is Condition.All -> condition.conditions.map(::condition).let { values -> if (values.any { it == null }) null else values.all { it == true } }
            is Condition.Any -> condition.conditions.map(::condition).let { values -> if (values.any { it == null }) null else values.any { it == true } }
            is Condition.Not -> condition(condition.condition)?.not()
            is Condition.Chance -> sample() < condition.probability
        }
        override fun forEach(effect: Effect.ForEach) {
            val origin = spatial(scope, effect.origin) ?: return
            val frame = Frame(origin, world.direction(scope.owner))
            val targets = selection.select(scope.owner, frame, effect.selector, charge = { charge(scope, it) })
            for ((index, id) in targets.withIndex()) execute(scope.copy(target = id, selected = true, targetGuard = TargetGuard(frame, effect.selector)), effect.effects, (results + ("selection.index" to index.toDouble())).toMutableMap())
        }
        override fun chain(effect: Effect.Chain) {
            val first = scope.target ?: return
            val position = world.position(first) ?: return
            val actorPosition = world.position(scope.owner) ?: return
            val initial = effect.selector.copy(shape = Shape.Sphere(scope.ability.targeting.range))
            val view = world.view(scope.owner, first) ?: return
            if (!selection.matches(scope.owner, Frame(actorPosition), initial, view)) return
            hitChain(scope.copy(targetGuard = TargetGuard(Frame(actorPosition), initial)), effect, first, position, emptySet(), 0, results.toMap())
        }
        override fun area(effect: Effect.CreateArea) = createArea(scope, effect)
        override fun status(effect: Effect.ApplyStatus) {
            val id = target(effect.target) ?: return
            applyStatus(scope, effect.status, id, "${scope.originDefinition}/${effect.applicationId}")
        }
        override fun dispel(effect: Effect.Dispel): Map<String, Double>? {
            val id = target(effect.target) ?: return null
            val removed = matchingStatuses(scope, id, effect.filter).take(effect.count)
            val stacks = removed.sumOf { it.stackExpiries.size }
            // ASVS 2.3.1, 2.3.4: select and cancel once on the server thread, after charging all query work.
            removed.forEach { it.lifetime.cancelled = true }
            prune()
            return mapOf("contributions_removed" to removed.size.toDouble(), "stacks_removed" to stacks.toDouble())
        }
        override fun consumeStatus(effect: Effect.ConsumeStatus): Map<String, Double>? {
            val id = target(effect.target) ?: return null
            var remaining = effect.count
            val changes = mutableListOf<Pair<Contribution, Int>>()
            for (status in matchingStatuses(scope, id, effect.filter)) {
                if (remaining == 0) break
                val count = minOf(remaining, status.stackExpiries.size)
                changes += status to count
                remaining -= count
            }
            val fullyRemoved = changes.count { (status, count) -> count == status.stackExpiries.size }
            val partials = changes.filter { (status, count) -> count < status.stackExpiries.size }.map { it.first }
            val callbackWork = partials.sumOf { estimate(it.definition.stacksChanged) }
            // ASVS 2.3.2, 2.3.4: reserve all known callback work before consuming a stack.
            require(callbackWork <= scope.budget.remaining && callbackWork <= workThisTick &&
                (ownerWorkThisTick[scope.owner] ?: 0) + callbackWork <= 2048) { "ability work limit exceeded" }
            for ((status, count) in changes) {
                if (count == status.stackExpiries.size) status.lifetime.cancelled = true
                else repeat(count) {
                    val oldest = status.stackExpiries.indices.minBy { status.stackExpiries[it] }
                    status.stackExpiries.removeAt(oldest)
                }
            }
            prune()
            for (status in partials) if (status.lifetime.active) execute(statusScope(status, scope.budget), status.definition.stacksChanged, statusBindings(status))
            return mapOf("stacks_removed" to (effect.count - remaining).toDouble(),
                "contributions_removed" to fullyRemoved.toDouble())
        }
        override fun readStatus(effect: Effect.ReadStatus): Map<String, Double>? {
            val id = target(effect.target) ?: return null
            val matches = matchingStatuses(scope, id, effect.filter)
            return mapOf("contributions" to matches.size.toDouble(), "stacks" to matches.sumOf { it.stackExpiries.size }.toDouble())
        }
        override fun setTimer(effect: Effect.SetTimer) {
            val recipient = target(effect.target) ?: return
            val key = timerKey(scope, effect.name, recipient)
            // ASVS 2.2.3, 2.3.2: check the finite expiry pulse before allocating a controller.
            require(estimate(effect.expired) <= 1024) { "timer expiry work limit exceeded" }
            val existing = timers[key]?.takeIf { it.lifetime.active }
            if (existing == null) require(timers.size < 1024 && timers.values.count { it.key.owner == scope.owner } < 64) { "timer limit reached" }
            if (scopes[scope.id] == null) return
            // ASVS 2.3.1, 15.4.2: replacement and insertion share the server tick turn.
            existing?.lifetime?.cancelled = true
            timers[key] = Timer(key, scope, Lifetime(scope.lifetime), now(scope.owner) + effect.ticks, effect.expired, results.toMap())
            prune()
        }
        override fun cancelTimer(effect: Effect.CancelTimer): Map<String, Double>? {
            val recipient = target(effect.target) ?: return null
            val key = timerKey(scope, effect.name, recipient)
            val timer = timers.remove(key)?.takeIf { it.lifetime.active }
            timer?.lifetime?.cancelled = true
            prune()
            return mapOf("cancelled" to if (timer != null) 1.0 else 0.0)
        }
        override fun readTimer(effect: Effect.ReadTimer): Map<String, Double>? {
            val recipient = target(effect.target) ?: return null
            val key = timerKey(scope, effect.name, recipient)
            val remaining = timers[key]?.takeIf { it.lifetime.active }?.let { (it.due - now(scope.owner)).coerceAtLeast(0) } ?: 0L
            return mapOf("active" to if (remaining > 0) 1.0 else 0.0, "remaining_ticks" to remaining.toDouble())
        }
        override fun restoreCharge(effect: Effect.RestoreCharge): Map<String, Double> {
            val targetGrant = if (effect.grant == "self") scope.grant else effect.grant
            val state = chargeState(scope.owner, scope.classId, targetGrant)
                ?: return mapOf("restored" to 0.0, "available_charges" to 0.0)
            val restored = minOf(effect.count, state.capacity - state.available)
            if (restored > 0) {
                // ASVS 2.3.1, 2.3.4: each restored use consumes a missing recharge obligation once.
                state.available += restored
                if (state.mode == RechargeMode.PARALLEL) repeat(restored) { if (state.timers.isNotEmpty()) state.timers.removeAt(state.timers.lastIndex) }
                if (state.available == state.capacity) state.timers.clear()
            }
            return mapOf("restored" to restored.toDouble(), "available_charges" to state.available.toDouble())
        }
        override fun reduceCooldown(effect: Effect.ReduceCooldown): Map<String, Double> {
            val record = record(scope.owner)
            val targetGrant = if (effect.grant == "self") scope.grant else effect.grant
            val key = "${scope.classId}|$targetGrant"
            val remaining = ((record.cooldowns[key] ?: 0) - effect.ticks).coerceAtLeast(0)
            record.cooldowns[key] = remaining
            return mapOf("remaining_ticks" to remaining.toDouble())
        }
        override fun reduceRecharge(effect: Effect.ReduceRecharge): Map<String, Double> {
            val targetGrant = if (effect.grant == "self") scope.grant else effect.grant
            val definition = definitions.classes[scope.classId]?.grants?.get(targetGrant)?.ability?.charges
            val state = chargeState(scope.owner, scope.classId, targetGrant)
            if (definition == null || state == null) return mapOf("restored" to 0.0, "available_charges" to 0.0, "next_recharge_ticks" to 0.0)
            val indices = when (effect.selection) {
                RechargeSelection.EARLIEST -> state.timers.indices.take(1)
                RechargeSelection.LATEST -> state.timers.indices.reversed().take(1)
                RechargeSelection.ALL -> state.timers.indices.toList()
            }
            // ASVS 2.3.1, 2.3.4: edit each selected timer once, then reconcile restored uses atomically.
            for (index in indices) state.timers[index] = (state.timers[index] - effect.ticks).coerceAtLeast(0)
            val restored = state.timers.count { it == 0 }
            state.timers.removeIf { it == 0 }
            state.available = (state.available + restored).coerceAtMost(state.capacity)
            if (state.mode == RechargeMode.SEQUENTIAL && state.available < state.capacity && state.timers.isEmpty()) state.timers += definition.rechargeTicks
            return mapOf("restored" to restored.toDouble(), "available_charges" to state.available.toDouble(),
                "next_recharge_ticks" to (state.timers.minOrNull() ?: 0).toDouble())
        }
        override fun reduceGroupCooldown(effect: Effect.ReduceGroupCooldown): Map<String, Double> {
            val record = record(scope.owner)
            val key = "group|${effect.group}"
            val remaining = ((record.cooldowns[key] ?: 0) - effect.ticks).coerceAtLeast(0)
            record.cooldowns[key] = remaining
            return mapOf("remaining_ticks" to remaining.toDouble())
        }
        override fun reduceGlobalCooldown(effect: Effect.ReduceGlobalCooldown): Map<String, Double> {
            val record = record(scope.owner)
            val remaining = ((record.cooldowns["global"] ?: 0) - effect.ticks).coerceAtLeast(0)
            record.cooldowns["global"] = remaining
            return mapOf("remaining_ticks" to remaining.toDouble())
        }
    }

    private fun expireTimer(timer: Timer) {
        if (timers[timer.key] !== timer || !timer.lifetime.active) return
        timers.remove(timer.key)
        timer.lifetime.cancelled = true
        // Ordinary expiry gets a new source-owned lifetime. Cancelled timers never run this body.
        val root = scopes[timer.scope.id] ?: return
        val callback = timer.scope.copy(lifetime = Lifetime(root.lifetime), budget = Budget(), target = timer.key.target, selected = true)
        execute(callback, timer.expired, timer.bindings.toMutableMap())
    }

    private fun timerKey(scope: Scope, name: String, target: UUID): TimerKey {
        // A grant timer persists across casts. Controller timers belong to their status or area instance.
        val source = scope.controllerId?.toString() ?: "grant"
        return TimerKey(scope.owner, scope.classId, scope.grant, source, name, target)
    }

    private fun matchingStatuses(scope: Scope, target: UUID, filter: StatusFilter): List<Contribution> {
        val candidates = statusesByTarget[target].orEmpty()
        // ASVS 2.3.2: the recipient index bounds each query to its 64 contribution slots.
        charge(scope, candidates.size)
        return candidates.filter { status ->
            status.lifetime.active && (filter.status == null || status.definition.id == filter.status) &&
                status.definition.tags.containsAll(filter.tags) && when (filter.source) {
                    StatusSource.ANY -> true
                    StatusSource.ACTOR -> status.key.owner == scope.owner
                    StatusSource.GRANT -> status.key.owner == scope.owner && status.key.classId == scope.classId && status.key.grant == scope.grant
                }
        }
    }

    private fun hitChain(scope: Scope, effect: Effect.Chain, target: UUID, position: Position, visited: Set<UUID>, previousHits: Int, bindings: Map<String, Double>) {
        execute(scope.copy(target = target, selected = true), effect.effects, (bindings + mapOf("chain.index" to previousHits.toDouble(), "chain.hit_count" to previousHits.toDouble())).toMutableMap())
        if (!scope.lifetime.active || previousHits + 1 >= effect.maxTargets) return
        val task = ChainTask(scope, now(scope.owner) + effect.delayTicks, effect, position, target, visited + target, previousHits + 1, bindings)
        if (effect.delayTicks > 0) {
            requireCapacity(scope)
            chains += task
        } else advanceChain(task)
    }

    private fun advanceChain(task: ChainTask) {
        if (!world.loaded(task.origin)) return
        val excluded = if (task.effect.revisit) setOf(task.last) else task.visited
        val targets = selection.select(task.scope.owner, Frame(task.origin), task.effect.selector.copy(limit = 1), excluded, charge = { charge(task.scope, it) })
        val target = targets.firstOrNull() ?: return
        val position = world.position(target) ?: return
        hitChain(task.scope.copy(targetGuard = TargetGuard(Frame(task.origin), task.effect.selector)), task.effect, target, position, task.visited, task.hits, task.bindings)
    }

    private fun createArea(scope: Scope, effect: Effect.CreateArea) {
        val definition = definitions.areas[effect.area] ?: error("area is unavailable")
        require(areas.size < 512 && areas.count { it.scope.owner == scope.owner } < 32) { "area limit reached" }
        val attached = (effect.anchor as? Anchor.Attached)?.let { if (it.entity == EffectTarget.ACTOR) scope.owner else scope.target }
        val position = when (val anchor = effect.anchor) {
            is Anchor.Fixed -> spatial(scope, anchor.position)
            is Anchor.Attached -> attached?.let(world::position)
        } ?: return
        if (!world.loaded(position)) return
        val time = now(scope.owner)
        val area = Area(UUID.randomUUID(), scope, definition, Lifetime(scope.lifetime), if (attached == null) Frame(position, world.direction(scope.owner)) else null, attached,
            time + definition.durationTicks, time + definition.sampleTicks, time + definition.periodicTicks)
        scope.dependencies[definition.id] = definition
        areas += area
        reconcileArea(area, Frame(position, world.direction(attached ?: scope.owner)), scope.budget)
    }

    private fun frame(area: Area): Frame? {
        val fixed = area.fixed
        if (fixed != null) return fixed.takeIf { world.loaded(it.position) }
        val anchor = area.attached ?: return null
        if (!world.availableTarget(area.scope.owner, anchor)) return null
        val position = world.position(anchor) ?: return null
        return Frame(position, world.direction(anchor)).takeIf { world.loaded(it.position) }
    }

    private fun tickArea(area: Area) {
        val frame = frame(area) ?: run { area.lifetime.cancelled = true; prune(); return }
        val time = now(area.scope.owner)
        if (time >= area.expires) {
            area.lifetime.cancelled = true
            prune()
            execute(area.scope.copy(target = null, ground = frame.position, targetGuard = null, budget = Budget(), originDefinition = area.definition.id, controllerId = area.id), area.definition.expired, linkedMapOf())
            return
        }
        val budget = Budget()
        // Reconcile before pulses too, so departing members cannot receive a late pulse.
        if (time >= area.nextSample || (area.definition.periodicTicks > 0 && time >= area.nextPulse)) {
            reconcileArea(area, frame, budget)
            area.nextSample = time + area.definition.sampleTicks
        }
        if (area.definition.periodicTicks > 0 && time >= area.nextPulse) {
            area.nextPulse = time + area.definition.periodicTicks
            for ((target, lifetime) in area.members.toMap()) execute(area.scope.copy(target = target, ground = frame.position, selected = true, budget = budget, lifetime = lifetime, originDefinition = area.definition.id, controllerId = area.id, targetGuard = TargetGuard(frame, area.definition.selector)), area.definition.periodic, linkedMapOf())
        }
    }

    private fun reconcileArea(area: Area, frame: Frame, budget: Budget) {
        val pulseScope = area.scope.copy(ground = frame.position, budget = budget, lifetime = area.lifetime, selected = true, targetGuard = null, originDefinition = area.definition.id, controllerId = area.id)
        val targets = selection.select(area.scope.owner, frame, area.definition.selector, charge = { charge(pulseScope, it) }).toSet()
        for (old in area.members.keys.toList()) if (old !in targets) {
            area.members.remove(old)?.cancelled = true
            prune()
            execute(pulseScope.copy(target = old), area.definition.exit, linkedMapOf())
        }
        for (target in targets) if (target !in area.members && area.lifetime.active) {
            val lifetime = Lifetime(area.lifetime)
            area.members[target] = lifetime
            for (status in area.definition.buffs) {
                charge(pulseScope, 1)
                applyStatus(pulseScope.copy(target = target, lifetime = lifetime), status, target, "${area.definition.id}/buffs/$status", lifetime)
            }
            execute(pulseScope.copy(target = target, lifetime = lifetime, targetGuard = TargetGuard(frame, area.definition.selector)), area.definition.enter, linkedMapOf())
        }
    }

    private fun applyStatus(scope: Scope, id: String, target: UUID, application: String, membership: Lifetime? = null) {
        if (!scope.lifetime.active) return
        val definition = definitions.statuses[id] ?: error("status is unavailable")
        // ASVS 2.3.1: immunity is decided from server-owned active contributions before a control source can refresh or attach.
        if (definition.controlCategories.isNotEmpty() && statusesByTarget[target].orEmpty().any { active ->
                active.lifetime.active && active.definition.immunities.any { it in definition.controlCategories }
            }) return
        val key = StatusKey(scope.owner, scope.classId, scope.grant, application, target, membership)
        val time = now(scope.owner)
        val existing = statuses[key]?.takeIf { it.lifetime.active }
        if (existing != null) {
            val before = existing.stackExpiries.size
            val maximum = definition.stacks?.maximum ?: 1
            val expiry = time + definition.durationTicks
            if (definition.stacks?.duration != StackDuration.PER_STACK) {
                if (before < maximum) existing.stackExpiries += expiry
                existing.stackExpiries.replaceAll { expiry }
            } else if (before < maximum) existing.stackExpiries += expiry
            else {
                val oldest = existing.stackExpiries.indices.minBy { existing.stackExpiries[it] }
                existing.stackExpiries[oldest] = expiry
            }
            enforceImmunity(target, definition.immunities)
            val callbackScope = statusScope(existing, scope.budget)
            execute(callbackScope, definition.refreshed, statusBindings(existing))
            if (before != existing.stackExpiries.size) execute(callbackScope, definition.stacksChanged, statusBindings(existing))
            return
        }
        // ASVS 2.3.2, 2.3.4: allocation and insertion occur together on the server tick thread.
        require(statuses.size < 4096 && statuses.values.count { it.key.owner == scope.owner } < 256 && statusesByTarget[target].orEmpty().size < 64) { "status contribution limit reached" }
        val root = scopes[scope.id] ?: return
        val lifetime = Lifetime(membership ?: root.lifetime)
        val contribution = Contribution(UUID.randomUUID(), key, scope, definition, lifetime, mutableListOf(time + definition.durationTicks), time + definition.periodicTicks)
        root.statusDependencies[id] = definition
        statuses[key] = contribution
        statusesByTarget.getOrPut(target) { mutableListOf() } += contribution
        enforceImmunity(target, definition.immunities)
        syncSpeed(target)
        execute(statusScope(contribution, scope.budget), definition.applied, statusBindings(contribution))
    }

    private fun enforceImmunity(target: UUID, categories: Set<String>) {
        if (categories.isEmpty()) return
        val controlled = statusesByTarget[target].orEmpty().filter { contribution ->
            contribution.lifetime.active && contribution.definition.controlCategories.any { it in categories }
        }
        if (controlled.isEmpty()) return
        // This is an explicit cleanse, not natural expiry. Each source and its descendants are cancelled independently.
        controlled.forEach { it.lifetime.cancelled = true }
        prune()
    }

    private fun statusBindings(status: Contribution) = linkedMapOf("status.stacks" to status.stackExpiries.size.toDouble())
    private fun statusScope(status: Contribution, budget: Budget) = status.scope.copy(target = status.key.target, ground = null, selected = true, targetGuard = null,
        lifetime = status.lifetime, budget = budget, originDefinition = status.definition.id, controllerId = status.id)

    private fun tickStatus(status: Contribution, pulse: Boolean) {
        if (statuses[status.key] !== status) return
        val target = status.key.target
        val position = world.position(target)
        if (!world.availableTarget(status.key.owner, target) || (position != null && !world.loaded(position))) {
            status.lifetime.cancelled = true
            prune()
            return
        }
        val time = now(status.key.owner)
        val before = status.stackExpiries.size
        if (status.key.membership == null) status.stackExpiries.removeIf { it <= time }
        val budget = Budget()
        if (status.stackExpiries.isEmpty()) {
            removeStatus(status)
            status.lifetime.cancelled = true
            prune()
            syncSpeed(target)
            // Ordinary expiry gets a new source-owned scope. Cancellation never invokes expiry effects.
            val root = scopes[status.scope.id] ?: return
            execute(statusScope(status, budget).copy(lifetime = Lifetime(root.lifetime)), status.definition.expired, statusBindings(status))
            return
        }
        if (before != status.stackExpiries.size) execute(statusScope(status, budget), status.definition.stacksChanged, statusBindings(status))
        if (pulse && status.definition.periodicTicks > 0 && time >= status.nextPulse && status.lifetime.active) {
            status.nextPulse = time + status.definition.periodicTicks
            execute(statusScope(status, budget), status.definition.periodic, statusBindings(status))
        }
    }

    private fun syncSpeed(target: UUID) {
        val bonuses = statusesByTarget[target].orEmpty().filter { it.lifetime.active }.mapNotNull { it.definition.speed }
        val amount = if (bonuses.firstOrNull()?.combination == BonusCombination.CAPPED_ADD) bonuses.sumOf { it.amount }.coerceAtMost(bonuses.first().cap!!)
            else bonuses.maxOfOrNull { it.amount } ?: 0.0
        world.movementSpeedBonus(target, amount)
    }

    private fun cancelTargetStatuses(target: UUID) {
        statusesByTarget[target].orEmpty().forEach { it.lifetime.cancelled = true }
        prune()
    }
    private fun cancelTargetTimers(target: UUID) {
        timers.values.filter { it.key.target == target }.forEach { it.lifetime.cancelled = true }
        prune()
    }

    private fun removeStatus(status: Contribution) {
        statuses.remove(status.key)
        statusesByTarget[status.key.target]?.let { contributions ->
            contributions.remove(status)
            if (contributions.isEmpty()) statusesByTarget.remove(status.key.target)
        }
    }

    private fun spatial(scope: Scope, target: SpatialTarget): Position? = when (target) {
        SpatialTarget.ACTOR -> world.position(scope.owner)
        SpatialTarget.TARGET -> scope.target?.let(world::position) ?: scope.ground
        SpatialTarget.GROUND -> scope.ground
    }

    private fun charge(scope: Scope, amount: Int) {
        require(amount >= 0 && scope.budget.remaining >= amount && workThisTick >= amount && (ownerWorkThisTick[scope.owner] ?: 0) + amount <= 2048) { "ability work limit exceeded" }
        scope.budget.remaining -= amount
        workThisTick -= amount
        ownerWorkThisTick[scope.owner] = (ownerWorkThisTick[scope.owner] ?: 0) + amount
    }

    private fun requireCapacity(scope: Scope) {
        require(pending.size + chains.size < 2048 && pending.count { it.scope.owner == scope.owner } + chains.count { it.scope.owner == scope.owner } < 128) { "scheduled work limit exceeded" }
    }

    private fun schedule(scope: Scope, ticks: Int, effects: List<Effect>, results: Map<String, Double>, repeatsLeft: Int = 1, every: Int = 0) {
        if (!scope.lifetime.active) return
        require(ticks > 0 && repeatsLeft in 1..64 && (repeatsLeft == 1 || every > 0)) { "scheduled work requires a positive interval and finite count" }
        requireCapacity(scope)
        pending += Scheduled(scope, now(scope.owner) + ticks, effects, results.toMap(), repeatsLeft, every)
    }

    private fun cancelWhere(predicate: (Scope) -> Boolean) {
        scopes.values.filter(predicate).forEach { it.lifetime.cancelled = true }
        prune()
    }

    private fun prune(releaseIdle: Boolean = false) {
        val removed = statuses.values.filter { !it.lifetime.active }
        removed.forEach(::removeStatus)
        removed.map { it.key.target }.toSet().forEach(::syncSpeed)
        timers.entries.removeIf { !it.value.lifetime.active }
        pending.removeIf { !it.scope.lifetime.active }
        chains.removeIf { !it.scope.lifetime.active }
        areas.removeIf { !it.lifetime.active }
        val retained = pending.map { it.scope.id }.toSet() + chains.map { it.scope.id } + areas.map { it.scope.id } + statuses.values.map { it.scope.id } + timers.values.map { it.scope.id }
        scopes.entries.removeIf { !it.value.lifetime.active || (releaseIdle && it.key !in retained) }
    }

    private fun fail(scope: Scope, failure: Exception) {
        scopes[scope.id]?.lifetime?.cancelled = true
        scope.lifetime.cancelled = true
        if (failures.size >= 32) failures.removeFirst()
        failures += "${scope.classId}/${scope.grant}: ${failure.message ?: "effect failed"}"
        prune()
    }

    private fun needsTarget(effect: Effect, groundMode: Boolean = false): Boolean {
        val mechanic = catalog.mechanic(effect)
        return (if (groundMode) mechanic.needsEntityTarget(effect) else mechanic.needsTarget(effect)) ||
            (!mechanic.providesTarget && mechanic.nested(effect).any { body -> body.any { needsTarget(it, groundMode) } })
    }
    private fun needsGround(effect: Effect): Boolean = catalog.mechanic(effect).let { mechanic ->
        mechanic.needsGround(effect) || mechanic.nested(effect).any { body -> body.any { needsGround(it) } }
    }
    private fun estimate(effects: List<Effect>) = estimates.measure(effects, ExecutionEstimates.Kind.WORK)
    private fun estimatedAreas(effects: List<Effect>) = estimates.measure(effects, ExecutionEstimates.Kind.AREAS)
    private fun initialSlots(effects: List<Effect>) = estimates.measure(effects, ExecutionEstimates.Kind.SLOTS)
    private fun estimatedStatuses(effects: List<Effect>) = estimates.measure(effects, ExecutionEstimates.Kind.STATUSES)
    private fun estimatedTimers(effects: List<Effect>) = estimates.measure(effects, ExecutionEstimates.Kind.TIMERS)

    private fun initialTimerKeys(owner: UUID, classId: String, grant: String, target: UUID?, effects: List<Effect>): Set<TimerKey> =
        effects.filterIsInstance<Effect.SetTimer>().mapNotNull { effect ->
            (if (effect.target == EffectTarget.ACTOR) owner else target)?.let { TimerKey(owner, classId, grant, "grant", effect.name, it) }
        }.toSet()

    private fun now(owner: UUID) = onlineClock[owner] ?: 0L
    private fun buffWork(area: AreaDef) = estimates.buffs(area, ExecutionEstimates.Kind.WORK)

    private fun initialStatusKeys(owner: UUID, classId: String, grant: String, ability: AbilityDef, target: UUID?): Set<StatusKey> {
        val keys = linkedSetOf<StatusKey>()
        fun collect(effects: List<Effect>) {
            for (effect in effects) when (effect) {
                is Effect.ApplyStatus -> (if (effect.target == EffectTarget.ACTOR) owner else target)?.let {
                    keys += StatusKey(owner, classId, grant, "${ability.id}/${effect.applicationId}", it, null)
                }
                // Branches, selections, controllers and continuations have dynamic allocation requirements.
                is Effect.Branch, is Effect.ForEach, is Effect.Chain, is Effect.CreateArea, is Effect.Delay, is Effect.Repeat -> Unit
                else -> catalog.mechanic(effect).nested(effect).forEach(::collect)
            }
        }
        collect(ability.effects)
        return keys
    }

    private fun controllerBodies(area: AreaDef) = area.enter + area.periodic + area.exit + area.expired
    private fun controllerDependencies(effects: List<Effect>): ControllerDependencies {
        val areaDefinitions = linkedMapOf<String, AreaDef>()
        val statusDefinitions = linkedMapOf<String, StatusDef>()
        val createdStatuses = mutableSetOf<String>()
        fun collectStatus(id: String, collect: (List<Effect>) -> Unit) {
            val status = definitions.statuses[id] ?: return
            statusDefinitions[id] = status
            if (createdStatuses.add(id)) collect(status.bodies)
        }
        fun collect(body: List<Effect>) {
            for (effect in body.flatMap { catalog.descendants(it).toList() }) {
                val mechanic = catalog.mechanic(effect)
                for (ref in mechanic.statuses(effect)) definitions.statuses[ref]?.let { statusDefinitions[ref] = it }
                for (ref in mechanic.createdStatuses(effect)) collectStatus(ref, ::collect)
                for (ref in mechanic.areas(effect)) {
                    val area = definitions.areas[ref] ?: continue
                    if (areaDefinitions.putIfAbsent(ref, area) == null) {
                        collect(controllerBodies(area))
                        area.buffs.forEach { collectStatus(it, ::collect) }
                    }
                }
            }
        }
        collect(effects)
        return ControllerDependencies(areaDefinitions, statusDefinitions, createdStatuses)
    }
    private fun changeResource(record: PlayerRecord, classId: String, id: String, delta: Double): Double {
        val resource = definitions.resources[id] ?: error("resource is unavailable")
        val key = balanceKey(classId, resource)
        val current = balance(record, classId, resource)
        if (delta < 0 && current + delta < resource.minimum) error("insufficient resource")
        val next = (current + delta).coerceIn(resource.minimum, resource.maximum)
        record.resources[key] = next
        return kotlin.math.abs(next - current)
    }
    private fun balance(record: PlayerRecord, classId: String, resource: ResourceDef): Double = record.resources.getOrPut(balanceKey(classId, resource)) { resource.initial }
    private fun balanceKey(classId: String, resource: ResourceDef): String = "${if (resource.scope == ResourceScope.PLAYER) "player" else classId}|${resource.id}"

    fun chargeState(player: UUID, classId: String, grant: String): ChargeState? {
        val definition = definitions.classes[classId]?.grants?.get(grant)?.ability?.charges ?: return null
        return chargeState(record(player), "$classId|$grant", definition)
    }

    fun cooldownRemaining(player: UUID, classId: String, grant: String): Int {
        val ability = definitions.classes[classId]?.grants?.get(grant)?.ability ?: return 0
        val record = record(player)
        return cooldownKeys("$classId|$grant", ability).maxOfOrNull { (key, _) -> record.cooldowns[key] ?: 0 } ?: 0
    }

    private fun cooldownKeys(grantKey: String, ability: AbilityDef): List<Pair<String, Int>> =
        listOf(grantKey to ability.cooldownTicks) + ability.cooldownGroups.map { "group|$it" to ability.cooldownTicks } +
            if (ability.globalCooldownTicks > 0) listOf("global" to ability.globalCooldownTicks) else emptyList()

    private fun chargeState(record: PlayerRecord, key: String, definition: ChargeDef): ChargeState {
        val state = record.charges.getOrPut(key) { ChargeState(definition.maximum, definition.maximum, definition.mode) }
        val oldTimers = state.timers.filter { it in 1..72_000 }.sorted()
        state.capacity = definition.maximum
        state.available = state.available.coerceIn(0, definition.maximum)
        state.mode = definition.mode
        val missing = definition.maximum - state.available
        state.timers.clear()
        if (missing > 0) {
            if (definition.mode == RechargeMode.SEQUENTIAL) state.timers += oldTimers.firstOrNull() ?: definition.rechargeTicks
            else {
                state.timers += oldTimers.take(missing)
                repeat(missing - state.timers.size) { state.timers += definition.rechargeTicks }
            }
        }
        return state
    }

    private fun reconcileCharges(record: PlayerRecord) {
        for ((classId, classDef) in definitions.classes) for ((grant, definition) in classDef.grants) {
            val key = "$classId|$grant"
            if (key in record.charges && definition.ability.charges != null) chargeState(record, key, definition.ability.charges)
        }
    }

    private fun tickCharges(record: PlayerRecord) {
        for ((key, state) in record.charges) {
            val definition = definitions.classes[key.substringBefore('|')]?.grants?.get(key.substringAfter('|'))?.ability?.charges ?: continue
            chargeState(record, key, definition)
            state.timers.replaceAll { it - 1 }
            val restored = state.timers.count { it <= 0 }
            state.timers.removeIf { it <= 0 }
            state.available = (state.available + restored).coerceAtMost(state.capacity)
            if (state.mode == RechargeMode.SEQUENTIAL && state.available < state.capacity && state.timers.isEmpty()) state.timers += definition.rechargeTicks
        }
    }
}

private fun Numeric.value(results: Map<String, Double>): Double = when (this) {
    is Numeric.Constant -> value
    is Numeric.Expression -> Expression.evaluate(source, results)
}
private fun Numeric.amount(results: Map<String, Double>): Double = value(results).also { require(it.isFinite() && it in 0.0..1_000_000.0) { "effect amount must be finite and within the work limit" } }
private fun Numeric.unavailable(results: Map<String, Double>): Boolean = this is Numeric.Expression && Expression.variables(source).any { it !in results }
