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

enum class StateScope { PLAYER, CLASS, ACTIVATION, STATUS }
enum class StateType { BOOLEAN, ENUM, INTEGER, NUMBER }
data class StateField(
    val type: StateType, val initial: StateValue, val minimum: Double? = null,
    val maximum: Double? = null, val choices: Set<String> = emptySet(),
)
sealed interface StateValue {
    data class Number(val value: Double) : StateValue
    data class Flag(val value: Boolean) : StateValue
    data class Mode(val value: String) : StateValue
}
data class StateDef(val id: String, val scope: StateScope, val persistent: Boolean, val fields: Map<String, StateField>)
sealed interface StateInput {
    data class Number(val value: Numeric) : StateInput
    data class Flag(val value: Boolean) : StateInput
    data class Mode(val value: String) : StateInput
}

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
    data class Dash(val direction: DashDirection, val distance: Numeric, override val resultName: String?) : Effect
    data class Impulse(val target: EffectTarget, val direction: ImpulseDirection, val distance: Numeric,
        override val resultName: String?) : Effect
    data class SafeTeleport(val target: EffectTarget, val destination: SpatialTarget,
        override val resultName: String?) : Effect
    data class PlacePattern(val pattern: String, val at: SpatialTarget, val rotation: Int,
        val mirror: PatternMirror, val durationTicks: Int?, val allowFluid: Boolean,
        val allowGravity: Boolean, override val resultName: String?) : Effect
    data class EditTerrain(val operation: TerrainOperation, val at: SpatialTarget,
        val region: TerrainRegion, val block: String?, val filter: TerrainFilter,
        val durationTicks: Int?, val allowFluid: Boolean, val allowGravity: Boolean,
        val loot: Boolean, override val resultName: String?) : Effect
    data class ReadHealth(val target: EffectTarget, override val resultName: String?) : Effect
    data class Shield(
        val target: EffectTarget, val capacity: Numeric, val durationTicks: Int, val priority: Int,
        val damageType: String?, val depleted: List<Effect>, override val resultName: String?,
    ) : Effect
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
    data class SetState(val state: String, val field: String, val value: StateInput, override val resultName: String?) : Effect
    data class AddState(val state: String, val field: String, val amount: Numeric, override val resultName: String?) : Effect
    data class ResetState(val state: String, val field: String, override val resultName: String?) : Effect
    data class ReadState(val state: String, val field: String, override val resultName: String?) : Effect
    data class ReadStatusState(val state: String, val field: String, val target: EffectTarget, val filter: StatusFilter,
        override val resultName: String?) : Effect
    data class WriteStatusState(val state: String, val field: String, val target: EffectTarget, val filter: StatusFilter,
        val operation: StatusStateOperation, val value: StateInput?, val amount: Numeric?, override val resultName: String?) : Effect
    data class LaunchProjectile(val projectile: String, val direction: ProjectileDirection, override val resultName: String?,
        val arguments: Map<String, Numeric> = emptyMap()) : Effect
    data class WaitFor(val handle: String, val event: ProjectileEvent, val timeoutTicks: Int,
        val matched: List<Effect>, val timedOut: List<Effect>) : Effect { override val resultName: String? = null }
    data class Parallel(val branches: List<ParallelBranch>, val join: ParallelJoin,
        val then: List<Effect>, val failed: List<Effect>) : Effect { override val resultName: String? = null }
}
data class WeightedBranch(val weight: Int, val effects: List<Effect>)
data class ParallelBranch(val afterTicks: Int, val effects: List<Effect>, val successWhen: Condition?)
enum class ParallelJoin { ALL, FIRST_SUCCESS }
enum class StatusStateOperation { SET, ADD, RESET }

enum class StatusSource { ANY, ACTOR, GRANT }
data class StatusFilter(val status: String? = null, val tags: Set<String> = emptySet(), val source: StatusSource = StatusSource.ANY)

sealed interface Condition {
    data class ResourceAtLeast(val resource: String, val amount: Numeric) : Condition
    data class Compare(val left: Numeric, val operator: String, val right: Numeric) : Condition
    data class HasStatus(val target: EffectTarget, val filter: StatusFilter) : Condition
    data class StateIs(val state: String, val field: String, val value: StateValue) : Condition
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
enum class Activation { ACTIVATED, PASSIVE, TOGGLE, CHANNEL, CHARGE, CONFIRM, RECAST }
enum class RechargeMode { SEQUENTIAL, PARALLEL }
enum class RechargeSelection { EARLIEST, LATEST, ALL }
enum class ProjectileDirection { AIM, TARGET, GROUND }
enum class DashDirection { AIM, TARGET, GROUND }
enum class ImpulseDirection { AWAY, TOWARD }
enum class ProjectileEntities { ENEMIES, ALLIES, ANY }
enum class ProjectileEvent { ENTITY_HIT, BLOCK_HIT, EXPIRY }
enum class PatternMirror { NONE, X, Z }
data class PatternCell(val x: Int, val y: Int, val z: Int, val block: String)
data class BlockPatternDef(val id: String, val cells: List<PatternCell>)
enum class TerrainOperation { SET, REPLACE, BREAK }
sealed interface TerrainRegion {
    data object Point : TerrainRegion
    data class Line(val x: Int, val y: Int, val z: Int) : TerrainRegion
    data class Box(val width: Int, val height: Int, val depth: Int) : TerrainRegion
    data class Sphere(val radius: Int) : TerrainRegion
}
data class TerrainFilter(val blocks: Set<String> = emptySet(), val tags: Set<String> = emptySet()) {
    val defined: Boolean get() = blocks.isNotEmpty() || tags.isNotEmpty()
}
data class NumberParameter(val minimum: Double, val maximum: Double, val default: Double?)
data class ProjectileDef(
    val id: String, val speed: Double, val gravity: Double, val lifetimeTicks: Int,
    val entities: ProjectileEntities, val entityHit: List<Effect>, val blockHit: List<Effect>, val expiry: List<Effect>,
    val pierce: Int = 0, val bounces: Int = 0, val repeatHits: Int = 1, val repeatIntervalTicks: Int = 0,
    val homingDegreesPerTick: Double? = null,
    val parameters: Map<String, NumberParameter> = emptyMap(),
) { val bodies: List<Effect> get() = entityHit + blockHit + expiry }
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
    val channelEveryTicks: Int = 0,
    val channelMaxTicks: Int? = null,
    val periodicCosts: List<Cost> = emptyList(),
    val chargeMinTicks: Int = 0,
    val chargeMaxTicks: Int = 0,
    val confirmWindowTicks: Int = 0,
    val recastWindowTicks: Int = 0,
    val recastEffects: List<Effect> = emptyList(),
)

data class AreaDef(
    val id: String,
    val shape: Shape,
    val durationTicks: Int?,
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
enum class ActionRestriction { ACTIVATE, MOVE, JUMP, ATTACK }
data class StatusStacks(val maximum: Int, val duration: StackDuration)
enum class BonusCombination { STRONGEST, CAPPED_ADD }
data class SpeedBonus(val amount: Double, val combination: BonusCombination, val cap: Double?)
data class ReflectPolicy(val fraction: Double, val cap: Double, val damageType: String)
data class AbilityReplacement(val grant: String, val replacement: String, val priority: Int)
data class StatusDef(
    val id: String, val durationTicks: Int, val stacks: StatusStacks?,
    val periodicTicks: Int, val periodic: List<Effect>,
    val applied: List<Effect>, val refreshed: List<Effect>, val stacksChanged: List<Effect>, val expired: List<Effect>,
    val speed: SpeedBonus?,
    val tags: Set<String> = emptySet(),
    val restrictions: Set<ActionRestriction> = emptySet(),
    val controlCategories: Set<String> = emptySet(),
    val immunities: Set<String> = emptySet(),
    val breakOnHealthLoss: Double? = null,
    val broken: List<Effect> = emptyList(),
    val state: String? = null,
    val replacements: List<AbilityReplacement> = emptyList(),
    val reflect: ReflectPolicy? = null,
) {
    val bodies: List<Effect> get() = applied + refreshed + stacksChanged + periodic + expired + broken
}

data class Grant(val name: String, val slot: String?, val ability: AbilityDef)
data class ClassDef(val id: String, val name: String, val grants: Map<String, Grant>)
data class SpecializationDef(val id: String, val name: String, val classId: String, val grants: Map<String, Grant>)

enum class ProgressScope { PLAYER, CLASS }
enum class ProgressEvent { ENTITY_DEATH, VANILLA_XP }
enum class ProgressRecipients { ACTOR, CONTRIBUTORS, NEARBY_ALLIES }
enum class ProgressDistribution { EACH, SPLIT }
data class PointAward(val id: String, val budget: String, val amount: Int)
data class ProgressLevel(val level: Int, val xp: Long, val awards: List<PointAward>)
data class EarningRule(val id: String, val event: ProgressEvent, val xp: Long,
    val recipients: ProgressRecipients = ProgressRecipients.ACTOR, val range: Double = 0.0,
    val distribution: ProgressDistribution = ProgressDistribution.EACH)
data class ProgressionTrackDef(val id: String, val scope: ProgressScope, val levels: List<ProgressLevel>,
    val capLevel: Int?, val bankOverflow: Boolean, val earningRules: List<EarningRule>,
    val classes: Set<String> = emptySet())
data class EmpowermentDef(val id: String, val replacements: List<AbilityReplacement>)
enum class UnlockSelection { AUTOMATIC, TALENT }
data class PointCost(val budget: String, val amount: Int)
data class AbilityUnlock(val grant: String, val ability: String, val slot: String?)
data class UnlockNode(val id: String, val selection: UnlockSelection, val requiredLevel: Int,
    val prerequisites: Set<String>, val ranks: Int, val choiceGroup: String?, val cost: PointCost?,
    val empowerments: List<String>, val abilities: List<AbilityUnlock> = emptyList())
data class UnlockTreeDef(val id: String, val track: String, val nodes: Map<String, UnlockNode>,
    val specializations: Set<String> = emptySet())

data class DefinitionSet(
    val packs: Map<String, Pack>,
    val resources: Map<String, ResourceDef>,
    val abilities: Map<String, AbilityDef>,
    val classes: Map<String, ClassDef>,
    val fingerprint: String,
    val areas: Map<String, AreaDef> = emptyMap(),
    val statuses: Map<String, StatusDef> = emptyMap(),
    val states: Map<String, StateDef> = emptyMap(),
    val projectiles: Map<String, ProjectileDef> = emptyMap(),
    val progressionTracks: Map<String, ProgressionTrackDef> = emptyMap(),
    val empowerments: Map<String, EmpowermentDef> = emptyMap(),
    val unlockTrees: Map<String, UnlockTreeDef> = emptyMap(),
    val specializations: Map<String, SpecializationDef> = emptyMap(),
    val blockPatterns: Map<String, BlockPatternDef> = emptyMap(),
)

sealed interface CompileResult {
    data class Valid(val definitions: DefinitionSet) : CompileResult
    data class Invalid(val diagnostics: List<Diagnostic>) : CompileResult
}
