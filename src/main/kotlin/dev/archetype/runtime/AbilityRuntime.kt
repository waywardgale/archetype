package dev.archetype.runtime

import dev.archetype.definitions.*
import java.util.UUID

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
}

data class PlayerRecord(
    val ownedClasses: MutableSet<String> = linkedSetOf(),
    val activeClasses: MutableSet<String> = linkedSetOf(),
    val resources: MutableMap<String, Double> = linkedMapOf(),
    val cooldowns: MutableMap<String, Int> = linkedMapOf(),
    val regenerationTimers: MutableMap<String, Int> = linkedMapOf(),
)

sealed interface CastResult {
    data object Applied : CastResult
    data class Rejected(val reason: String) : CastResult
    data class Interrupted(val reason: String) : CastResult
}

/** All mutation runs on the server tick thread. ASVS 2.3.1, 2.3.4, 15.4.1. */
class AbilityRuntime(private val world: WorldOps, private val catalog: MechanicCatalog = BuiltinEffects.catalog) {
    var definitions = DefinitionSet(emptyMap(), emptyMap(), emptyMap(), emptyMap(), "")
        private set
    var generation: Long = 0
        private set
    private val players = linkedMapOf<UUID, PlayerRecord>()
    private val selection = TargetSelection(world)
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
    )
    private data class TargetGuard(val frame: Frame, val selector: Selector)
    private data class Scheduled(val scope: Scope, val due: Long, val effects: List<Effect>, val bindings: Map<String, Double>, val repeatsLeft: Int = 1, val every: Int = 0)
    private data class ChainTask(val scope: Scope, val due: Long, val effect: Effect.Chain, val origin: Position, val last: UUID, val visited: Set<UUID>, val hits: Int, val bindings: Map<String, Double>)
    private data class Area(
        val scope: Scope, val definition: AreaDef, val lifetime: Lifetime, val fixed: Frame?, val attached: UUID?,
        val expires: Long, var nextSample: Long, var nextPulse: Long,
        val members: MutableMap<UUID, Lifetime> = linkedMapOf(),
    )
    private val scopes = linkedMapOf<UUID, Scope>()
    private val pending = mutableListOf<Scheduled>()
    private val chains = mutableListOf<ChainTask>()
    private val areas = mutableListOf<Area>()
    private val failures = ArrayDeque<String>()
    private val onlineClock = mutableMapOf<UUID, Long>()
    private val castsThisTick = mutableMapOf<UUID, Int>()
    private val ownerWorkThisTick = mutableMapOf<UUID, Int>()
    private var globalCastsThisTick = 0
    private var workThisTick = 4096

    fun record(player: UUID): PlayerRecord = players.getOrPut(player) { PlayerRecord() }
    fun drainFailures(): List<String> = failures.toList().also { failures.clear() }

    fun installRecord(player: UUID, record: PlayerRecord) {
        for ((key, amount) in record.resources.toMap()) definitions.resources[key.substringAfterLast('|')]?.let {
            record.resources[key] = amount.coerceIn(it.minimum, it.maximum)
        }
        players[player] = record
    }

    fun onDeath(player: UUID) {
        cancelWhere { it.owner == player }
        val record = players[player] ?: return
        for ((key, _) in record.resources.toMap()) definitions.resources[key.substringAfterLast('|')]?.let { record.resources[key] = it.initial }
    }

    fun onLogout(player: UUID) { cancelWhere { it.owner == player } }

    fun publish(candidate: DefinitionSet) {
        if (candidate.fingerprint == definitions.fingerprint) return
        cancelWhere { scope ->
            candidate.classes[scope.classId]?.grants?.get(scope.grant)?.ability != scope.ability ||
                scope.dependencies.any { (id, definition) -> candidate.areas[id] != definition }
        }
        for (record in players.values) for ((key, amount) in record.resources.toMap()) candidate.resources[key.substringAfterLast('|')]?.let {
            record.resources[key] = amount.coerceIn(it.minimum, it.maximum)
        }
        definitions = candidate
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
        val requiresTarget = ability.effects.any { needsTarget(it, ability.targeting.type == Targeting.Type.GROUND) }
        val selectedTarget = target ?: if (requiresTarget) world.aim(player, ability.targeting.range) else null
        if (requiresTarget && (selectedTarget == null || !world.validTarget(player, selectedTarget, ability.targeting.range))) return CastResult.Rejected("target is unavailable")
        val ground = if (ability.targeting.type == Targeting.Type.GROUND) world.ground(player, ability.targeting.range) else null
        if ((ability.targeting.type == Targeting.Type.GROUND || ability.effects.any { needsGround(it) }) && (ground == null || !world.loaded(ground))) return CastResult.Rejected("ground target is unavailable")
        val cooldownKey = "$classId|$grantName"
        if ((record.cooldowns[cooldownKey] ?: 0) > 0) return CastResult.Rejected("cooldown is active")
        // Known oversized setup is rejected before payment. Dynamic query work is checked during execution.
        if (estimate(ability.effects) > 1024) return CastResult.Rejected("ability work limit exceeded")
        val dependencies = areaDependencies(ability.effects)
        if (dependencies.values.any { area -> (estimate(area.enter) + estimate(area.exit) + estimate(area.periodic)) * area.selector.limit + 1 > 1024 || estimate(area.expired) > 1024 }) return CastResult.Rejected("area pulse work limit exceeded")
        val created = estimatedAreas(ability.effects)
        if (created + areas.size > 512 || created + areas.count { it.scope.owner == player } > 32) return CastResult.Rejected("area limit reached")
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
        if (ability.cooldownTicks > 0) record.cooldowns[cooldownKey] = ability.cooldownTicks
        val scope = Scope(UUID.randomUUID(), player, classId, grantName, ability, if (ability.targeting.type == Targeting.Type.GROUND) null else selectedTarget, ground, Budget(), Lifetime(), dependencies = dependencies)
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
        cancelWhere { it.owner !in online }
        for (player in online) {
            onlineClock[player] = now(player) + 1
            val record = players[player] ?: continue
            record.cooldowns.replaceAll { _, ticks -> (ticks - 1).coerceAtLeast(0) }
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
        // Membership reconciliation and expiry precede descendants due on the same tick.
        for (area in areas.toList()) if (area.lifetime.active) try { tickArea(area) } catch (failure: Exception) { fail(area.scope, failure) }
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
        override fun resource(id: String, amount: Numeric, spend: Boolean) = changeResource(record(scope.owner), scope.classId, id, amount.amount(results) * if (spend) -1 else 1)
        override fun delay(ticks: Int, effects: List<Effect>, count: Int, every: Int) = schedule(scope, ticks, effects, results, count, every)
        override fun branch(condition: Condition, onTrue: List<Effect>, onFalse: List<Effect>) = execute(scope, if (condition(scope, condition, results)) onTrue else onFalse, results)
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
        val area = Area(scope, definition, Lifetime(scope.lifetime), if (attached == null) Frame(position, world.direction(scope.owner)) else null, attached,
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
        val frame = frame(area) ?: run { area.lifetime.cancelled = true; return }
        val time = now(area.scope.owner)
        if (time >= area.expires) {
            area.lifetime.cancelled = true
            execute(area.scope.copy(target = null, ground = frame.position, targetGuard = null, budget = Budget()), area.definition.expired, linkedMapOf())
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
            for ((target, lifetime) in area.members.toMap()) execute(area.scope.copy(target = target, ground = frame.position, selected = true, budget = budget, lifetime = lifetime, targetGuard = TargetGuard(frame, area.definition.selector)), area.definition.periodic, linkedMapOf())
        }
    }

    private fun reconcileArea(area: Area, frame: Frame, budget: Budget) {
        val pulseScope = area.scope.copy(ground = frame.position, budget = budget, lifetime = area.lifetime, selected = true, targetGuard = null)
        val targets = selection.select(area.scope.owner, frame, area.definition.selector, charge = { charge(pulseScope, it) }).toSet()
        for (old in area.members.keys.toList()) if (old !in targets) {
            area.members.remove(old)?.cancelled = true
            execute(pulseScope.copy(target = old), area.definition.exit, linkedMapOf())
        }
        for (target in targets) if (target !in area.members && area.lifetime.active) {
            val lifetime = Lifetime(area.lifetime)
            area.members[target] = lifetime
            execute(pulseScope.copy(target = target, lifetime = lifetime, targetGuard = TargetGuard(frame, area.definition.selector)), area.definition.enter, linkedMapOf())
        }
    }

    private fun condition(scope: Scope, condition: Condition, results: Map<String, Double>): Boolean = when (condition) {
        is Condition.ResourceAtLeast -> {
            val resource = definitions.resources[condition.resource] ?: error("resource is unavailable")
            balance(record(scope.owner), scope.classId, resource) >= condition.amount.value(results)
        }
        is Condition.Compare -> {
            val left = condition.left.value(results)
            val right = condition.right.value(results)
            when (condition.operator) { "lt" -> left < right; "lte" -> left <= right; "eq" -> left == right; "gte" -> left >= right; "gt" -> left > right; else -> error("invalid comparison") }
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
        pending.removeIf { !it.scope.lifetime.active }
        chains.removeIf { !it.scope.lifetime.active }
        areas.removeIf { !it.lifetime.active }
        val retained = pending.map { it.scope.id }.toSet() + chains.map { it.scope.id } + areas.map { it.scope.id }
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
    private fun estimate(effects: List<Effect>): Long = effects.sumOf { effect ->
        val nested = catalog.mechanic(effect).nested(effect)
        val work = when (effect) {
            is Effect.Repeat -> estimate(effect.effects) * effect.count
            is Effect.ForEach -> estimate(effect.effects) * effect.selector.limit
            is Effect.Chain -> estimate(effect.effects) * effect.maxTargets
            is Effect.Branch -> maxOf(estimate(effect.onTrue), estimate(effect.onFalse))
            is Effect.CreateArea -> definitions.areas[effect.area]?.let { estimate(it.enter) * it.selector.limit }.orEmptyWork()
            else -> nested.sumOf { estimate(it) }
        }
        (1 + work).coerceAtMost(1_000_000_000)
    }.coerceAtMost(1_000_000_000)
    private fun estimatedAreas(effects: List<Effect>): Long = effects.sumOf { effect ->
        when (effect) {
            is Effect.CreateArea -> 1L
            is Effect.Repeat -> effect.count * estimatedAreas(effect.effects)
            is Effect.ForEach -> effect.selector.limit * estimatedAreas(effect.effects)
            is Effect.Chain -> effect.maxTargets * estimatedAreas(effect.effects)
            is Effect.Branch -> maxOf(estimatedAreas(effect.onTrue), estimatedAreas(effect.onFalse))
            else -> catalog.mechanic(effect).nested(effect).sumOf { estimatedAreas(it) }
        }.coerceAtMost(1_000_000_000)
    }.coerceAtMost(1_000_000_000)

    private fun initialSlots(effects: List<Effect>): Long = effects.sumOf { effect ->
        when (effect) {
            is Effect.Delay, is Effect.Repeat -> 1L
            is Effect.Chain -> initialSlots(effect.effects) * (if (effect.delayTicks == 0) effect.maxTargets else 1) + if (effect.delayTicks > 0 && effect.maxTargets > 1) 1 else 0
            is Effect.ForEach -> initialSlots(effect.effects) * effect.selector.limit
            is Effect.Branch -> maxOf(initialSlots(effect.onTrue), initialSlots(effect.onFalse))
            is Effect.CreateArea -> definitions.areas[effect.area]?.let { initialSlots(it.enter) * it.selector.limit }.orEmptyWork()
            else -> catalog.mechanic(effect).nested(effect).sumOf { initialSlots(it) }
        }.coerceAtMost(1_000_000_000)
    }.coerceAtMost(1_000_000_000)

    private fun now(owner: UUID) = onlineClock[owner] ?: 0L
    private fun areaDependencies(effects: List<Effect>): MutableMap<String, AreaDef> {
        val dependencies = linkedMapOf<String, AreaDef>()
        fun collect(body: List<Effect>) {
            for (effect in body.flatMap { catalog.descendants(it).toList() }) for (ref in catalog.mechanic(effect).areas(effect)) {
                val area = definitions.areas[ref] ?: continue
                if (dependencies.putIfAbsent(ref, area) == null) collect(area.enter + area.periodic + area.exit + area.expired)
            }
        }
        collect(effects)
        return dependencies
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
}

private fun Long?.orEmptyWork() = this ?: 0L
private fun Numeric.value(results: Map<String, Double>): Double = when (this) {
    is Numeric.Constant -> value
    is Numeric.Expression -> Expression.evaluate(source, results)
}
private fun Numeric.amount(results: Map<String, Double>): Double = value(results).also { require(it.isFinite() && it in 0.0..1_000_000.0) { "effect amount must be finite and within the work limit" } }
private fun Numeric.unavailable(results: Map<String, Double>): Boolean = this is Numeric.Expression && Expression.variables(source).any { it !in results }
