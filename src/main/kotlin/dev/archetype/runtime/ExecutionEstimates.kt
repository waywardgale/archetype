package dev.archetype.runtime

import dev.archetype.definitions.*

/** Static reservations for validated acyclic definitions. Runtime still bounds dynamic selection and pulses. */
internal class ExecutionEstimates(private val definitions: DefinitionSet, private val catalog: MechanicCatalog) {
    enum class Kind { WORK, AREAS, STATUSES, SLOTS, TIMERS }
    private val controllers = mutableMapOf<Pair<String, Kind>, Long>()
    fun measure(effects: List<Effect>, kind: Kind): Long = effects.sumOf { effect ->
        val descendants = when (effect) {
            is Effect.CreateArea -> area(effect.area, kind)
            is Effect.ApplyStatus -> status(effect.status, kind) + if (kind == Kind.STATUSES) 1L else 0L
            is Effect.Dispel, is Effect.ConsumeStatus, is Effect.ReadStatus -> if (kind == Kind.WORK) 64L else 0L
            // Expiry starts a separate bounded pulse. Its body is not part of setup reservation.
            is Effect.SetTimer -> if (kind == Kind.TIMERS) 1L else 0L
            is Effect.Delay -> if (kind == Kind.SLOTS) 1L else measure(effect.effects, kind)
            is Effect.Repeat -> if (kind == Kind.SLOTS) 1L else measure(effect.effects, kind) * effect.count
            is Effect.ForEach -> measure(effect.effects, kind) * effect.selector.limit
            is Effect.Chain -> if (kind == Kind.SLOTS) measure(effect.effects, kind) * (if (effect.delayTicks == 0) effect.maxTargets else 1) +
                (if (effect.delayTicks > 0 && effect.maxTargets > 1) 1L else 0L) else measure(effect.effects, kind) * effect.maxTargets
            is Effect.Branch -> maxOf(measure(effect.onTrue, kind), measure(effect.onFalse, kind)) +
                if (kind == Kind.WORK) effect.condition.leaves().count { it is Condition.HasStatus } * 64L else 0L
            is Effect.Choose -> effect.options.maxOf { measure(it.effects, kind) }
            else -> catalog.mechanic(effect).nested(effect).sumOf { measure(it, kind) }
        }
        (descendants + if (kind == Kind.WORK) 1L else 0L).coerceAtMost(LIMIT)
    }.coerceAtMost(LIMIT)

    fun buffs(area: AreaDef, kind: Kind): Long = area.buffs.sumOf { status(it, kind) + if (kind == Kind.WORK || kind == Kind.STATUSES) 1L else 0L }.coerceAtMost(LIMIT)
    // ASVS 2.3.2: memoize reference expansion so a shared dependency graph cannot create exponential preflight work.
    private fun status(id: String, kind: Kind): Long = controllers.getOrPut(id to kind) {
        definitions.statuses[id]?.let { maxOf(measure(it.applied, kind), measure(it.refreshed, kind) + measure(it.stacksChanged, kind)) } ?: 0L
    }
    private fun area(id: String, kind: Kind): Long = controllers.getOrPut(id to kind) {
        definitions.areas[id]?.let { (measure(it.enter, kind) + buffs(it, kind)) * it.selector.limit + if (kind == Kind.AREAS) 1L else 0L } ?: 0L
    }.coerceAtMost(LIMIT)
    private companion object { const val LIMIT = 1_000_000_000L }
}
