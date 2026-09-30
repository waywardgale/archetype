package dev.archetype.definitions

data class Diagnostic(val file: String, val field: String, val problem: String) {
    override fun toString(): String = "$file:$field: $problem"
}

data class Pack(val id: String, val name: String, val dependencies: Set<String>)

data class ResourceDef(
    val id: String,
    val scope: ResourceScope,
    val minimum: Double,
    val maximum: Double,
    val initial: Double,
    val regeneration: Regeneration?,
)

enum class ResourceScope { PLAYER, CLASS }
data class Regeneration(val amount: Double, val everyTicks: Int)
data class Cost(val resource: String, val amount: Numeric)

sealed interface Numeric {
    data class Constant(val value: Double) : Numeric
    data class Expression(val source: String) : Numeric
}

interface Effect {
    val resultName: String?
    data class Heal(val target: EffectTarget, val amount: Numeric, override val resultName: String?) : Effect
    data class Damage(val target: EffectTarget, val amount: Numeric, val damageType: String, override val resultName: String?) : Effect
    data class ReadHealth(val target: EffectTarget, override val resultName: String?) : Effect
    data class GainResource(val resource: String, val amount: Numeric, override val resultName: String?) : Effect
    data class SpendResource(val resource: String, val amount: Numeric, override val resultName: String?) : Effect
    data class SetResource(val resource: String, val value: Numeric, override val resultName: String?) : Effect
    data class ResetResource(val resource: String, override val resultName: String?) : Effect
    data class Delay(val ticks: Int, val effects: List<Effect>) : Effect { override val resultName: String? = null }
    data class Sequence(val effects: List<Effect>) : Effect { override val resultName: String? = null }
    data class Repeat(val count: Int, val everyTicks: Int, val effects: List<Effect>) : Effect { override val resultName: String? = null }
    data class Branch(val condition: Condition, val onTrue: List<Effect>, val onFalse: List<Effect>) : Effect { override val resultName: String? = null }
    data class Choose(val options: List<WeightedBranch>, override val resultName: String?) : Effect
    data class ForEach(val origin: SpatialTarget, val selector: Selector, val effects: List<Effect>) : Effect { override val resultName: String? = null }
    data class Chain(val maxTargets: Int, val hopRange: Double, val delayTicks: Int, val revisit: Boolean, val selector: Selector, val effects: List<Effect>) : Effect { override val resultName: String? = null }
    data class CreateArea(val area: String, val anchor: Anchor) : Effect { override val resultName: String? = null }
    data class ApplyStatus(val status: String, val target: EffectTarget, val applicationId: String) : Effect { override val resultName: String? = null }
    data class Dispel(val target: EffectTarget, val filter: StatusFilter, val count: Int, override val resultName: String?) : Effect
    data class ConsumeStatus(val target: EffectTarget, val filter: StatusFilter, val count: Int, override val resultName: String?) : Effect
    data class ReadStatus(val target: EffectTarget, val filter: StatusFilter, override val resultName: String?) : Effect
    data class SetTimer(val name: String, val target: EffectTarget, val ticks: Int, val expired: List<Effect>) : Effect { override val resultName: String? = null }
    data class CancelTimer(val name: String, val target: EffectTarget, override val resultName: String?) : Effect
    data class ReadTimer(val name: String, val target: EffectTarget, override val resultName: String?) : Effect
    data class RestoreCharge(val count: Int, val grant: String, override val resultName: String?) : Effect
    data class ReduceCooldown(val ticks: Int, val grant: String, override val resultName: String?) : Effect
    data class ReduceRecharge(val ticks: Int, val grant: String, val selection: RechargeSelection, override val resultName: String?) : Effect
    data class ReduceGroupCooldown(val group: String, val ticks: Int, override val resultName: String?) : Effect
    data class ReduceGlobalCooldown(val ticks: Int, override val resultName: String?) : Effect
}
data class WeightedBranch(val weight: Int, val effects: List<Effect>)

enum class StatusSource { ANY, ACTOR, GRANT }
data class StatusFilter(val status: String? = null, val tags: Set<String> = emptySet(), val source: StatusSource = StatusSource.ANY)

sealed interface Condition {
    data class ResourceAtLeast(val resource: String, val amount: Numeric) : Condition
    data class Compare(val left: Numeric, val operator: String, val right: Numeric) : Condition
    data class HasStatus(val target: EffectTarget, val filter: StatusFilter) : Condition
    data class All(val conditions: List<Condition>) : Condition
    data class Any(val conditions: List<Condition>) : Condition
    data class Not(val condition: Condition) : Condition
    data class Chance(val probability: Double) : Condition
}

fun Condition.leaves(): Sequence<Condition> = sequence {
    when (this@leaves) {
        is Condition.All -> conditions.forEach { yieldAll(it.leaves()) }
        is Condition.Any -> conditions.forEach { yieldAll(it.leaves()) }
        is Condition.Not -> yieldAll(condition.leaves())
        else -> yield(this@leaves)
    }
}

fun Effect.descendants(): Sequence<Effect> = BuiltinEffects.catalog.descendants(this)

enum class EffectTarget { ACTOR, TARGET }
enum class Activation { ACTIVATED, PASSIVE }
enum class RechargeMode { SEQUENTIAL, PARALLEL }
enum class RechargeSelection { EARLIEST, LATEST, ALL }
data class ChargeDef(val maximum: Int, val rechargeTicks: Int, val mode: RechargeMode)

data class AbilityDef(
    val id: String,
    val name: String,
    val activation: Activation,
    val cooldownTicks: Int,
    val costs: List<Cost>,
    val effects: List<Effect>,
    val targeting: Targeting = Targeting(),
    val charges: ChargeDef? = null,
    val cooldownGroups: Set<String> = emptySet(),
    val globalCooldownTicks: Int = 0,
)

data class AreaDef(
    val id: String,
    val shape: Shape,
    val durationTicks: Int,
    val sampleTicks: Int,
    val selector: Selector,
    val enter: List<Effect>,
    val periodic: List<Effect>,
    val periodicTicks: Int,
    val exit: List<Effect>,
    val expired: List<Effect>,
    val buffs: List<String> = emptyList(),
)

enum class StackDuration { SHARED, PER_STACK }
enum class ActionRestriction { ACTIVATE }
data class StatusStacks(val maximum: Int, val duration: StackDuration)
enum class BonusCombination { STRONGEST, CAPPED_ADD }
data class SpeedBonus(val amount: Double, val combination: BonusCombination, val cap: Double?)
data class StatusDef(
    val id: String, val durationTicks: Int, val stacks: StatusStacks?,
    val periodicTicks: Int, val periodic: List<Effect>,
    val applied: List<Effect>, val refreshed: List<Effect>, val stacksChanged: List<Effect>, val expired: List<Effect>,
    val speed: SpeedBonus?,
    val tags: Set<String> = emptySet(),
    val restrictions: Set<ActionRestriction> = emptySet(),
    val controlCategories: Set<String> = emptySet(),
    val immunities: Set<String> = emptySet(),
) {
    val bodies: List<Effect> get() = applied + refreshed + stacksChanged + periodic + expired
}

data class Grant(val name: String, val slot: String?, val ability: AbilityDef)
data class ClassDef(val id: String, val name: String, val grants: Map<String, Grant>)

data class DefinitionSet(
    val packs: Map<String, Pack>,
    val resources: Map<String, ResourceDef>,
    val abilities: Map<String, AbilityDef>,
    val classes: Map<String, ClassDef>,
    val fingerprint: String,
    val areas: Map<String, AreaDef> = emptyMap(),
    val statuses: Map<String, StatusDef> = emptyMap(),
)

sealed interface CompileResult {
    data class Valid(val definitions: DefinitionSet) : CompileResult
    data class Invalid(val diagnostics: List<Diagnostic>) : CompileResult
}
