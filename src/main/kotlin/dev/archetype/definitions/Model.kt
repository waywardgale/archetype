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

sealed interface Effect {
    val resultName: String?
    data class Heal(val target: EffectTarget, val amount: Numeric, override val resultName: String?) : Effect
    data class Damage(val target: EffectTarget, val amount: Numeric, val damageType: String, override val resultName: String?) : Effect
    data class GainResource(val resource: String, val amount: Numeric, override val resultName: String?) : Effect
    data class SpendResource(val resource: String, val amount: Numeric, override val resultName: String?) : Effect
    data class Delay(val ticks: Int, val effects: List<Effect>) : Effect { override val resultName: String? = null }
    data class Repeat(val count: Int, val everyTicks: Int, val effects: List<Effect>) : Effect { override val resultName: String? = null }
    data class Branch(val condition: Condition, val onTrue: List<Effect>, val onFalse: List<Effect>) : Effect { override val resultName: String? = null }
}

sealed interface Condition {
    data class ResourceAtLeast(val resource: String, val amount: Numeric) : Condition
    data class Compare(val left: Numeric, val operator: String, val right: Numeric) : Condition
}

fun Effect.descendants(): Sequence<Effect> = sequence {
    yield(this@descendants)
    when (val effect = this@descendants) {
        is Effect.Delay -> effect.effects.forEach { yieldAll(it.descendants()) }
        is Effect.Repeat -> effect.effects.forEach { yieldAll(it.descendants()) }
        is Effect.Branch -> (effect.onTrue + effect.onFalse).forEach { yieldAll(it.descendants()) }
        else -> Unit
    }
}

enum class EffectTarget { ACTOR, TARGET }
enum class Activation { ACTIVATED, PASSIVE }

data class AbilityDef(
    val id: String,
    val name: String,
    val activation: Activation,
    val cooldownTicks: Int,
    val costs: List<Cost>,
    val effects: List<Effect>,
)

data class Grant(val name: String, val slot: String?, val ability: AbilityDef)
data class ClassDef(val id: String, val name: String, val grants: Map<String, Grant>)

data class DefinitionSet(
    val packs: Map<String, Pack>,
    val resources: Map<String, ResourceDef>,
    val abilities: Map<String, AbilityDef>,
    val classes: Map<String, ClassDef>,
    val fingerprint: String,
)

sealed interface CompileResult {
    data class Valid(val definitions: DefinitionSet) : CompileResult
    data class Invalid(val diagnostics: List<Diagnostic>) : CompileResult
}
