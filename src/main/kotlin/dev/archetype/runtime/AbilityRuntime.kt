package dev.archetype.runtime

import dev.archetype.definitions.*
import java.util.UUID

/** Minecraft objects stay in the adapter. Native calls return actual health deltas. */
interface WorldOps {
    fun validTarget(actor: UUID, target: UUID): Boolean
    fun heal(target: UUID, amount: Double): Double
    fun damage(actor: UUID, target: UUID, amount: Double, damageType: String): Double
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

/** All mutation, including publish and casts, runs on the server tick thread. ASVS 2.3.1, 2.3.4, 15.4.1. */
class AbilityRuntime(private val world: WorldOps) {
    var definitions: DefinitionSet = DefinitionSet(emptyMap(), emptyMap(), emptyMap(), emptyMap(), "")
        private set
    var generation: Long = 0
        private set
    private val players = linkedMapOf<UUID, PlayerRecord>()
    private data class Budget(var remaining: Int = 1024)
    private data class Scope(val id: UUID, val owner: UUID, val classId: String, val grant: String, val ability: AbilityDef, val target: UUID?, val budget: Budget)
    private data class Scheduled(val scope: Scope, val due: Long, val effects: List<Effect>, val bindings: Map<String, Double>, val repeatsLeft: Int = 1, val every: Int = 0)
    private val pending = mutableListOf<Scheduled>()
    private val onlineClock = mutableMapOf<UUID, Long>()
    private val castsThisTick = mutableMapOf<UUID, Int>()
    private var globalCastsThisTick = 0
    private var workThisTick = 4096

    fun record(player: UUID): PlayerRecord = players.getOrPut(player) { PlayerRecord() }

    fun installRecord(player: UUID, record: PlayerRecord) {
        for ((key, amount) in record.resources.toMap()) {
            definitions.resources[key.substringAfterLast('|')]?.let { resource ->
                record.resources[key] = amount.coerceIn(resource.minimum, resource.maximum)
            }
        }
        players[player] = record
    }

    fun onDeath(player: UUID) {
        pending.removeIf { it.scope.owner == player }
        val record = players[player] ?: return
        for ((key, _) in record.resources.toMap()) {
            definitions.resources[key.substringAfterLast('|')]?.let { record.resources[key] = it.initial }
        }
    }

    fun onLogout(player: UUID) { pending.removeIf { it.scope.owner == player } }

    fun publish(candidate: DefinitionSet) {
        if (candidate.fingerprint == definitions.fingerprint) return
        // Reconcile first, then swap the immutable definition set in one server-thread transition.
        for (record in players.values) {
            for ((key, amount) in record.resources.toMap()) {
                val resource = candidate.resources[key.substringAfterLast('|')]
                if (resource != null) record.resources[key] = amount.coerceIn(resource.minimum, resource.maximum)
            }
        }
        pending.removeIf { task -> candidate.classes[task.scope.classId]?.grants?.get(task.scope.grant)?.ability != task.scope.ability }
        definitions = candidate
        generation++
    }

    fun selectClass(player: UUID, classId: String): Boolean {
        if (classId !in definitions.classes) return false
        val record = record(player)
        pending.removeIf { it.scope.owner == player && it.scope.classId != classId }
        record.ownedClasses += classId
        record.activeClasses.clear()
        record.activeClasses += classId
        return true
    }

    fun cast(player: UUID, classId: String, grantName: String, target: UUID?, clientGeneration: Long): CastResult {
        if (clientGeneration != generation) return CastResult.Rejected("stale definitions")
        if ((castsThisTick[player] ?: 0) >= 8 || globalCastsThisTick >= 256) return CastResult.Rejected("input limit reached")
        castsThisTick[player] = (castsThisTick[player] ?: 0) + 1
        globalCastsThisTick++
        val record = record(player)
        if (record.activeClasses.firstOrNull() != classId) return CastResult.Rejected("class is not active")
        val classDef = definitions.classes[classId] ?: return CastResult.Rejected("class is unavailable")
        val grant = classDef.grants[grantName] ?: return CastResult.Rejected("unknown grant")
        val ability = grant.ability
        if (ability.activation != Activation.ACTIVATED) return CastResult.Rejected("ability cannot be cast")
        if (ability.effects.any { it.needsTarget() } && (target == null || !world.validTarget(player, target))) {
            return CastResult.Rejected("target is unavailable")
        }
        val cooldownKey = "$classId|$grantName"
        if ((record.cooldowns[cooldownKey] ?: 0) > 0) return CastResult.Rejected("cooldown is active")
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
        // Costs and cooldown commit once after every precondition passes. World changes below are not rolled back.
        for ((key, amount) in amounts) record.resources[key] = record.resources.getValue(key) - amount
        if (ability.cooldownTicks > 0) record.cooldowns[cooldownKey] = ability.cooldownTicks

        val scope = Scope(UUID.randomUUID(), player, classId, grantName, ability, target, Budget())
        try { execute(scope, ability.effects, linkedMapOf()) }
        catch (failure: Exception) {
            pending.removeIf { it.scope.id == scope.id }
            return CastResult.Interrupted(failure.message ?: "effect failed")
        }
        return CastResult.Applied
    }

    fun tick(onlinePlayers: Collection<UUID>) {
        castsThisTick.clear()
        globalCastsThisTick = 0
        workThisTick = 4096
        val online = onlinePlayers.toSet()
        for (player in onlinePlayers) {
            onlineClock[player] = (onlineClock[player] ?: 0L) + 1
            val record = players[player] ?: continue
            record.cooldowns.replaceAll { _, ticks -> (ticks - 1).coerceAtLeast(0) }
            for (resource in definitions.resources.values) {
                val regen = resource.regeneration ?: continue
                val classes = if (resource.scope == ResourceScope.CLASS) record.activeClasses else setOf("")
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
        val due = mutableListOf<Scheduled>()
        val iterator = pending.iterator()
        while (iterator.hasNext() && due.size < 256) {
            val task = iterator.next()
            if (task.scope.owner in online && task.due <= (onlineClock[task.scope.owner] ?: 0L)) {
                iterator.remove()
                due += task
            }
        }
        for (task in due) {
            val scope = task.scope
            if (record(scope.owner).activeClasses.firstOrNull() != scope.classId) continue
            if (definitions.classes[scope.classId]?.grants?.get(scope.grant)?.ability != scope.ability) continue
            try {
                execute(scope, task.effects, task.bindings.toMutableMap())
                if (task.repeatsLeft > 1) schedule(scope, task.every, task.effects, task.bindings, task.repeatsLeft - 1, task.every)
            } catch (_: Exception) {
                pending.removeIf { it.scope.id == scope.id }
            }
        }
    }

    private fun execute(scope: Scope, effects: List<Effect>, results: MutableMap<String, Double>) {
        val record = record(scope.owner)
        for (effect in effects) {
            require(--scope.budget.remaining >= 0 && --workThisTick >= 0) { "ability work limit exceeded" }
            if (effect is Effect.Delay) {
                schedule(scope, effect.ticks, effect.effects, results)
                continue
            }
            if (effect is Effect.Repeat) {
                schedule(scope, effect.everyTicks, effect.effects, results, effect.count, effect.everyTicks)
                continue
            }
            if (effect is Effect.Branch) {
                val branch = if (condition(scope, effect.condition, results)) effect.onTrue else effect.onFalse
                execute(scope, branch, results)
                continue
            }
            if (effect.targetOrNull() == EffectTarget.TARGET &&
                (scope.target == null || !world.validTarget(scope.owner, scope.target))) continue
            val amount = effect.amountOrNull()?.value(results) ?: error("effect has no amount")
            require(amount.isFinite() && amount in 0.0..1_000_000.0) { "effect amount must be finite and within the work limit" }
            val actual = when (effect) {
                is Effect.Heal -> world.heal(effect.target.resolve(scope.owner, scope.target), amount)
                is Effect.Damage -> world.damage(scope.owner, effect.target.resolve(scope.owner, scope.target), amount, effect.damageType)
                is Effect.GainResource -> changeResource(record, scope.classId, effect.resource, amount)
                is Effect.SpendResource -> changeResource(record, scope.classId, effect.resource, -amount)
            }
            effect.resultName?.let { name ->
                val field = when (effect) { is Effect.Heal -> "health_restored"; is Effect.Damage -> "health_lost"; else -> "amount" }
                results["result.$name.$field"] = actual
            }
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

    private fun schedule(scope: Scope, ticks: Int, effects: List<Effect>, results: Map<String, Double>, repeatsLeft: Int = 1, every: Int = 0) {
        require(pending.size < 2048 && pending.count { it.scope.owner == scope.owner } < 128) { "scheduled work limit exceeded" }
        pending += Scheduled(scope, (onlineClock[scope.owner] ?: 0L) + ticks, effects, results.toMap(), repeatsLeft, every)
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

    private fun balance(record: PlayerRecord, classId: String, resource: ResourceDef): Double =
        record.resources.getOrPut(balanceKey(classId, resource)) { resource.initial }

    private fun balanceKey(classId: String, resource: ResourceDef): String =
        "${if (resource.scope == ResourceScope.PLAYER) "player" else classId}|${resource.id}"
}

private fun Effect.needsTarget(): Boolean = when (this) {
    is Effect.Heal -> target == EffectTarget.TARGET
    is Effect.Damage -> target == EffectTarget.TARGET
    is Effect.Delay -> effects.any(Effect::needsTarget)
    is Effect.Repeat -> effects.any(Effect::needsTarget)
    is Effect.Branch -> onTrue.any(Effect::needsTarget) || onFalse.any(Effect::needsTarget)
    else -> false
}
private fun Effect.targetOrNull(): EffectTarget? = when (this) { is Effect.Heal -> target; is Effect.Damage -> target; else -> null }
private fun Effect.amountOrNull(): Numeric? = when (this) {
    is Effect.Heal -> amount
    is Effect.Damage -> amount
    is Effect.GainResource -> amount
    is Effect.SpendResource -> amount
    else -> null
}
private fun Numeric.value(results: Map<String, Double>): Double = when (this) {
    is Numeric.Constant -> value
    is Numeric.Expression -> Expression.evaluate(source, results)
}
private fun EffectTarget.resolve(actor: UUID, target: UUID?): UUID = when (this) {
    EffectTarget.ACTOR -> actor
    EffectTarget.TARGET -> target ?: error("target is unavailable")
}
