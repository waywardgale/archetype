package dev.archetype.definitions

/** Configuration, authoring metadata and execution share a registration. No YAML reaches execution. */
class EffectMechanic<E : Effect>(
    val type: String,
    val configuration: Class<E>,
    val fields: Map<String, Map<String, Any>>,
    val required: Set<String>,
    val lifecycle: String,
    val resultFields: Set<String> = emptySet(),
    val numericInputs: (E) -> Map<String, Numeric> = { emptyMap() },
    val children: (E) -> List<List<Effect>> = { emptyList() },
    val localBindings: Set<String> = emptySet(),
    val resourceReferences: (E) -> List<String> = { emptyList() },
    val areaReferences: (E) -> List<String> = { emptyList() },
    val requiresTarget: (E) -> Boolean = { false },
    val requiresEntityTarget: (E) -> Boolean = requiresTarget,
    val requiresGround: (E) -> Boolean = { false },
    val providesTarget: Boolean = false,
    val decode: (EffectReader) -> E,
    val execute: (E, EffectExecution) -> Map<String, Double>?,
) {
    fun inputs(effect: Effect) = numericInputs(configuration.cast(effect))
    fun nested(effect: Effect) = children(configuration.cast(effect))
    fun resources(effect: Effect) = resourceReferences(configuration.cast(effect))
    fun areas(effect: Effect) = areaReferences(configuration.cast(effect))
    fun needsTarget(effect: Effect) = requiresTarget(configuration.cast(effect))
    fun needsEntityTarget(effect: Effect) = requiresEntityTarget(configuration.cast(effect))
    fun needsGround(effect: Effect) = requiresGround(configuration.cast(effect))
    fun run(effect: Effect, context: EffectExecution) = execute(configuration.cast(effect), context)
}

class MechanicCatalog(registrations: List<EffectMechanic<out Effect>>) {
    val effects = registrations.associateBy { it.type }
    private val configurations = registrations.associateBy { it.configuration }
    init {
        require(effects.size == registrations.size && configurations.size == registrations.size) { "duplicate mechanic registration" }
        require(registrations.all { Regex("[a-z0-9_.-]+:[a-z0-9_./-]+").matches(it.type) && it.type.length <= 128 }) { "mechanic type must be namespaced" }
        require(registrations.all { it.lifecycle.isNotBlank() && it.required.all(it.fields::containsKey) }) { "invalid mechanic metadata" }
    }
    fun mechanic(effect: Effect): EffectMechanic<out Effect> = configurations[effect.javaClass] ?: error("unregistered effect ${effect.javaClass.simpleName}")
    fun descendants(effect: Effect): Sequence<Effect> = sequence {
        yield(effect)
        for (body in mechanic(effect).nested(effect)) for (child in body) yieldAll(descendants(child))
    }
}

/** A bounded compiler reader supplied to built-ins and extensions. It retains source field paths. */
interface EffectReader {
    fun text(key: String): String
    fun integer(key: String, min: Int, max: Int, default: Int? = null): Int
    fun number(key: String, min: Double, max: Double, default: Double? = null): Double
    fun boolean(key: String, default: Boolean): Boolean
    fun numeric(key: String): Numeric
    fun duration(key: String, positive: Boolean = false, default: Int? = null): Int
    fun reference(key: String): String
    fun target(key: String): EffectTarget
    fun spatialTarget(key: String, default: SpatialTarget = SpatialTarget.ACTOR): SpatialTarget
    fun effects(key: String, required: Boolean = true): List<Effect>
    fun condition(key: String): Condition
    fun selector(key: String, shape: Shape? = null): Selector
    fun anchor(key: String): Anchor
    val resultName: String?
}

/** Runtime services available to registered handlers. The implementation owns budgets and cleanup. */
interface EffectExecution {
    fun heal(target: EffectTarget, amount: Numeric): Healing?
    fun damage(target: EffectTarget, amount: Numeric, damageType: String): Double?
    fun resource(id: String, amount: Numeric, spend: Boolean): Double
    fun delay(ticks: Int, effects: List<Effect>, count: Int = 1, every: Int = 0)
    fun branch(condition: Condition, onTrue: List<Effect>, onFalse: List<Effect>)
    fun forEach(effect: Effect.ForEach)
    fun chain(effect: Effect.Chain)
    fun area(effect: Effect.CreateArea)
}

data class Healing(val restored: Double, val overheal: Double)

object BuiltinEffects {
    private fun number(min: Double, max: Double) = mapOf<String, Any>("type" to "number", "minimum" to min, "maximum" to max)
    private fun integer(min: Int, max: Int) = mapOf<String, Any>("type" to "integer", "minimum" to min, "maximum" to max)
    private val text = mapOf<String, Any>("type" to "string", "minLength" to 1)
    private val duration = mapOf<String, Any>("type" to "string", "pattern" to "^(0|[0-9]+(?:\\.[0-9]+)?)(ms|s|m)$")
    private fun ref(name: String) = mapOf<String, Any>("\$ref" to "#/\$defs/$name")
    private val numeric = ref("numeric")
    private val body = mapOf<String, Any>("type" to "array", "minItems" to 1, "maxItems" to 64, "items" to ref("effect"))
    private val target = mapOf<String, Any>("enum" to listOf("actor", "target"))
    private val spatial = mapOf<String, Any>("enum" to listOf("actor", "target", "ground"))
    private val bool = mapOf<String, Any>("type" to "boolean")
    val catalog = MechanicCatalog(listOf(
        EffectMechanic("archetype:heal", Effect.Heal::class.java, mapOf("target" to target, "amount" to numeric), setOf("target", "amount"), "Immediate native healing.", setOf("health_restored", "overheal"),
            numericInputs = { mapOf("amount" to it.amount) }, requiresTarget = { it.target == EffectTarget.TARGET },
            decode = { Effect.Heal(it.target("target"), it.numeric("amount"), it.resultName) },
            execute = { e, c -> c.heal(e.target, e.amount)?.let { mapOf("health_restored" to it.restored, "overheal" to it.overheal) } }),
        EffectMechanic("archetype:damage", Effect.Damage::class.java, mapOf("target" to target, "amount" to numeric, "damage_type" to text), setOf("target", "amount", "damage_type"), "Immediate native damage credited to the actor.", setOf("health_lost"),
            numericInputs = { mapOf("amount" to it.amount) }, requiresTarget = { it.target == EffectTarget.TARGET },
            decode = { Effect.Damage(it.target("target"), it.numeric("amount"), it.text("damage_type"), it.resultName) },
            execute = { e, c -> c.damage(e.target, e.amount, e.damageType)?.let { mapOf("health_lost" to it) } }),
        EffectMechanic("archetype:gain_resource", Effect.GainResource::class.java, mapOf("resource" to ref("reference"), "amount" to numeric), setOf("resource", "amount"), "Immediate clamped resource gain.", setOf("amount"),
            numericInputs = { mapOf("amount" to it.amount) }, resourceReferences = { listOf(it.resource) },
            decode = { Effect.GainResource(it.reference("resource"), it.numeric("amount"), it.resultName) },
            execute = { e, c -> mapOf("amount" to c.resource(e.resource, e.amount, false)) }),
        EffectMechanic("archetype:spend_resource", Effect.SpendResource::class.java, mapOf("resource" to ref("reference"), "amount" to numeric), setOf("resource", "amount"), "Immediate resource consumption, interrupted on insufficient balance.", setOf("amount"),
            numericInputs = { mapOf("amount" to it.amount) }, resourceReferences = { listOf(it.resource) },
            decode = { Effect.SpendResource(it.reference("resource"), it.numeric("amount"), it.resultName) },
            execute = { e, c -> mapOf("amount" to c.resource(e.resource, e.amount, true)) }),
        EffectMechanic("archetype:delay", Effect.Delay::class.java, mapOf("duration" to duration, "effects" to body), setOf("duration", "effects"), "Owned continuation, cancelled with its source; shares the originating budget.",
            children = { listOf(it.effects) },
            decode = { Effect.Delay(it.duration("duration", positive = true), it.effects("effects")) },
            execute = { e, c -> c.delay(e.ticks, e.effects); null }),
        EffectMechanic("archetype:repeat", Effect.Repeat::class.java, mapOf("count" to integer(1, 64), "every" to duration, "effects" to body), setOf("count", "every", "effects"), "Finite positive-interval repetition with independent invocation bindings and a shared budget.",
            children = { listOf(it.effects) },
            decode = { Effect.Repeat(it.integer("count", 1, 64), it.duration("every", positive = true), it.effects("effects")) },
            execute = { e, c -> c.delay(e.everyTicks, e.effects, e.count, e.everyTicks); null }),
        EffectMechanic("archetype:branch", Effect.Branch::class.java, mapOf("when" to ref("condition"), "then" to body, "else" to (body + ("minItems" to 0))), setOf("when", "then"), "Ordered conditional sequence in the current scope.",
            children = { listOf(it.onTrue, it.onFalse) },
            numericInputs = { when (val condition = it.condition) {
                is Condition.Compare -> mapOf("when.left" to condition.left, "when.right" to condition.right)
                is Condition.ResourceAtLeast -> mapOf("when.amount" to condition.amount)
            } },
            resourceReferences = { (it.condition as? Condition.ResourceAtLeast)?.let { listOf(it.resource) }.orEmpty() },
            decode = { Effect.Branch(it.condition("when"), it.effects("then"), it.effects("else", false)) },
            execute = { e, c -> c.branch(e.condition, e.onTrue, e.onFalse); null }),
        EffectMechanic("archetype:for_each", Effect.ForEach::class.java, mapOf("origin" to spatial, "targets" to ref("shaped_selector"), "effects" to body), setOf("targets", "effects"), "Bounded fresh selection; each target receives private bindings. Delays retain the selected target.",
            children = { listOf(it.effects) }, localBindings = setOf("selection.index"),
            requiresTarget = { it.origin == SpatialTarget.TARGET }, requiresEntityTarget = { false }, requiresGround = { it.origin == SpatialTarget.GROUND }, providesTarget = true,
            decode = { Effect.ForEach(it.spatialTarget("origin"), it.selector("targets"), it.effects("effects")) },
            execute = { e, c -> c.forEach(e); null }),
        EffectMechanic("archetype:chain", Effect.Chain::class.java, mapOf("max_targets" to integer(1, 64), "hop_range" to number(0.01, 32.0), "delay" to duration, "revisit" to bool, "targets" to ref("selector"), "effects" to body), setOf("max_targets", "hop_range", "targets", "effects"), "Advancing origin and bounded visited history. Source cancellation stops outstanding hops.",
            children = { listOf(it.effects) }, localBindings = setOf("chain.index", "chain.hit_count"),
            requiresTarget = { true }, providesTarget = true,
            decode = { r ->
                val range = r.number("hop_range", 0.01, 32.0)
                Effect.Chain(r.integer("max_targets", 1, 64), range, r.duration("delay", default = 0), r.boolean("revisit", false), r.selector("targets", Shape.Sphere(range)), r.effects("effects"))
            }, execute = { e, c -> c.chain(e); null }),
        EffectMechanic("archetype:create_area", Effect.CreateArea::class.java, mapOf("area" to ref("reference"), "anchor" to ref("anchor")), setOf("area", "anchor"), "Finite owned controller. Cancellation unregisters membership without exit or expiry gameplay callbacks.",
            areaReferences = { listOf(it.area) },
            requiresTarget = { it.anchor == Anchor.Fixed(SpatialTarget.TARGET) || it.anchor == Anchor.Attached(EffectTarget.TARGET) },
            requiresEntityTarget = { it.anchor == Anchor.Attached(EffectTarget.TARGET) },
            requiresGround = { it.anchor == Anchor.Fixed(SpatialTarget.GROUND) },
            decode = { Effect.CreateArea(it.reference("area"), it.anchor("anchor")) },
            execute = { e, c -> c.area(e); null }),
    ))
}
