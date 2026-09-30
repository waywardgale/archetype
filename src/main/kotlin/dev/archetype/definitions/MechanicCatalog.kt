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
    val stateReferences: (E) -> List<String> = { emptyList() },
    val projectileReferences: (E) -> List<String> = { emptyList() },
    val areaReferences: (E) -> List<String> = { emptyList() },
    val statusReferences: (E) -> List<String> = { emptyList() },
    val statusCreations: (E) -> List<String> = statusReferences,
    val grantReferences: (E) -> List<String> = { emptyList() },
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
    fun states(effect: Effect) = stateReferences(configuration.cast(effect))
    fun projectiles(effect: Effect) = projectileReferences(configuration.cast(effect))
    fun areas(effect: Effect) = areaReferences(configuration.cast(effect))
    fun statuses(effect: Effect) = statusReferences(configuration.cast(effect))
    fun createdStatuses(effect: Effect) = statusCreations(configuration.cast(effect))
    fun grants(effect: Effect) = grantReferences(configuration.cast(effect))
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
    fun has(key: String): Boolean
    fun text(key: String): String
    fun localId(key: String): String
    fun grant(key: String): String
    fun option(key: String, choices: Set<String>, default: String): String
    fun integer(key: String, min: Int, max: Int, default: Int? = null): Int
    fun number(key: String, min: Double, max: Double, default: Double? = null): Double
    fun boolean(key: String, default: Boolean): Boolean
    fun numeric(key: String): Numeric
    fun stateInput(key: String): StateInput
    fun duration(key: String, positive: Boolean = false, default: Int? = null): Int
    fun reference(key: String): String
    fun referenceWith(key: String): Pair<String, Map<String, Numeric>>
    fun target(key: String): EffectTarget
    fun spatialTarget(key: String, default: SpatialTarget = SpatialTarget.ACTOR): SpatialTarget
    fun effects(key: String, required: Boolean = true): List<Effect>
    fun choices(key: String): List<WeightedBranch>
    fun parallelBranches(key: String): List<ParallelBranch>
    fun condition(key: String): Condition
    fun statusFilter(): StatusFilter
    fun selector(key: String, shape: Shape? = null): Selector
    fun anchor(key: String): Anchor
    val resultName: String?
    val identity: String
}

/** Runtime services available to registered handlers. The implementation owns budgets and cleanup. */
interface EffectExecution {
    fun heal(target: EffectTarget, amount: Numeric): Healing?
    fun damage(target: EffectTarget, amount: Numeric, damageType: String): Double?
    fun readHealth(target: EffectTarget): Map<String, Double>?
    fun shield(effect: Effect.Shield): Map<String, Double>?
    fun resource(id: String, amount: Numeric, spend: Boolean): Double
    fun setResource(id: String, value: Numeric?): Map<String, Double>
    fun delay(ticks: Int, effects: List<Effect>, count: Int = 1, every: Int = 0)
    fun sequence(effects: List<Effect>)
    fun branch(condition: Condition, onTrue: List<Effect>, onFalse: List<Effect>)
    fun choose(effect: Effect.Choose): Map<String, Double>
    fun forEach(effect: Effect.ForEach)
    fun chain(effect: Effect.Chain)
    fun area(effect: Effect.CreateArea)
    fun status(effect: Effect.ApplyStatus)
    fun dispel(effect: Effect.Dispel): Map<String, Double>?
    fun consumeStatus(effect: Effect.ConsumeStatus): Map<String, Double>?
    fun readStatus(effect: Effect.ReadStatus): Map<String, Double>?
    fun setTimer(effect: Effect.SetTimer)
    fun cancelTimer(effect: Effect.CancelTimer): Map<String, Double>?
    fun readTimer(effect: Effect.ReadTimer): Map<String, Double>?
    fun restoreCharge(effect: Effect.RestoreCharge): Map<String, Double>
    fun reduceCooldown(effect: Effect.ReduceCooldown): Map<String, Double>
    fun reduceRecharge(effect: Effect.ReduceRecharge): Map<String, Double>
    fun reduceGroupCooldown(effect: Effect.ReduceGroupCooldown): Map<String, Double>
    fun reduceGlobalCooldown(effect: Effect.ReduceGlobalCooldown): Map<String, Double>
    fun setState(effect: Effect.SetState): Map<String, Double>
    fun addState(effect: Effect.AddState): Map<String, Double>
    fun resetState(effect: Effect.ResetState): Map<String, Double>
    fun readState(effect: Effect.ReadState): Map<String, Double>
    fun readStatusState(effect: Effect.ReadStatusState): Map<String, Double>?
    fun writeStatusState(effect: Effect.WriteStatusState): Map<String, Double>?
    fun launchProjectile(effect: Effect.LaunchProjectile): Map<String, Double>?
    fun waitFor(effect: Effect.WaitFor)
    fun parallel(effect: Effect.Parallel)
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
    private val target = mapOf<String, Any>("enum" to listOf("actor", "target", "event.target"))
    private val spatial = mapOf<String, Any>("enum" to listOf("actor", "target", "ground", "event.position"))
    private val bool = mapOf<String, Any>("type" to "boolean")
    private val timerName = mapOf<String, Any>("type" to "string", "pattern" to "^[a-z0-9_./-]{1,64}$")
    private val grantName = mapOf<String, Any>("type" to "string", "pattern" to "^[a-z0-9_./-]{1,128}$")
    private val stateValue = mapOf<String, Any>("oneOf" to listOf(numeric, bool, text))
    val statusFilterFields = mapOf(
        "status" to ref("reference"), "tags" to ref("status_tags"),
        "source" to mapOf<String, Any>("enum" to listOf("any", "actor", "grant"), "default" to "any"),
    )
    val catalog = MechanicCatalog(listOf(
        EffectMechanic("archetype:parallel", Effect.Parallel::class.java,
            mapOf("join" to mapOf<String, Any>("enum" to listOf("all", "first_success")),
                "branches" to mapOf<String, Any>("type" to "array", "minItems" to 2, "maxItems" to 8,
                    "items" to mapOf("type" to "object", "properties" to mapOf("after" to duration, "effects" to body,
                        "success_when" to ref("condition")), "required" to listOf("effects"), "additionalProperties" to false)),
                "then" to (body + ("minItems" to 0)), "failed" to (body + ("minItems" to 0))),
            setOf("join", "branches"),
            "Runs 2..8 declaration-ordered branches on the server with private bindings and one shared work budget. Join waits for all successful branches or the first branch whose success_when passes; a failed join runs failed. Losing race branches are cancelled without undoing completed effects.",
            children = { it.branches.map(ParallelBranch::effects) + listOf(it.then, it.failed) },
            localBindings = setOf("parallel.index", "parallel.completed"),
            numericInputs = { parallel -> parallel.branches.flatMapIndexed { index, branch ->
                branch.successWhen?.leaves()?.toList().orEmpty().flatMapIndexed { leaf, condition -> when (condition) {
                    is Condition.Compare -> listOf("branches[$index].success_when[$leaf].left" to condition.left,
                        "branches[$index].success_when[$leaf].right" to condition.right)
                    is Condition.ResourceAtLeast -> listOf("branches[$index].success_when[$leaf].amount" to condition.amount)
                    else -> emptyList()
                } }
            }.toMap() },
            resourceReferences = { parallel -> parallel.branches.flatMap { it.successWhen?.leaves()?.filterIsInstance<Condition.ResourceAtLeast>()?.map(Condition.ResourceAtLeast::resource)?.toList().orEmpty() } },
            statusReferences = { parallel -> parallel.branches.flatMap { it.successWhen?.leaves()?.filterIsInstance<Condition.HasStatus>()?.mapNotNull { leaf -> leaf.filter.status }?.toList().orEmpty() } },
            statusCreations = { emptyList() },
            stateReferences = { parallel -> parallel.branches.flatMap { it.successWhen?.leaves()?.filterIsInstance<Condition.StateIs>()?.map(Condition.StateIs::state)?.toList().orEmpty() } },
            requiresTarget = { parallel -> parallel.branches.any { it.successWhen?.leaves()?.filterIsInstance<Condition.HasStatus>()?.any { leaf -> leaf.target == EffectTarget.TARGET } == true } },
            decode = { reader ->
                val join = when (reader.option("join", setOf("all", "first_success"), "all")) {
                    "all" -> ParallelJoin.ALL
                    "first_success" -> ParallelJoin.FIRST_SUCCESS
                    else -> error("invalid parallel join")
                }
                Effect.Parallel(reader.parallelBranches("branches"), join, reader.effects("then", false), reader.effects("failed", false))
            }, execute = { e, c -> c.parallel(e); null }),
        EffectMechanic("archetype:read_status_state", Effect.ReadStatusState::class.java,
            statusFilterFields + mapOf("state" to ref("reference"), "field" to timerName, "target" to target),
            setOf("state", "field", "target"),
            "Reads the sum of a numeric or Boolean field across matching live status contributions on one recipient. Returns zero when no matching contribution exists.",
            setOf("value", "contributions"), stateReferences = { listOf(it.state) },
            statusReferences = { listOfNotNull(it.filter.status) }, statusCreations = { emptyList() },
            requiresTarget = { it.target == EffectTarget.TARGET },
            decode = { Effect.ReadStatusState(it.reference("state"), it.localId("field"), it.target("target"), it.statusFilter(), it.resultName) },
            execute = { e, c -> c.readStatusState(e) }),
        EffectMechanic("archetype:write_status_state", Effect.WriteStatusState::class.java,
            statusFilterFields + mapOf("state" to ref("reference"), "field" to timerName, "target" to target,
                "operation" to mapOf("enum" to listOf("set", "add", "reset")), "value" to stateValue, "amount" to numeric),
            setOf("state", "field", "target", "operation"),
            "Writes a declared field on every matching live status contribution. Set, add, and reset use the contribution's own state map; returns the number changed and numeric sums when available.",
            setOf("contributions", "previous", "current"),
            numericInputs = { effect -> when (effect.operation) {
                StatusStateOperation.SET -> (effect.value as? StateInput.Number)?.let { mapOf("value" to it.value) } ?: emptyMap()
                StatusStateOperation.ADD -> effect.amount?.let { mapOf("amount" to it) } ?: emptyMap()
                StatusStateOperation.RESET -> emptyMap()
            } },
            stateReferences = { listOf(it.state) }, statusReferences = { listOfNotNull(it.filter.status) },
            statusCreations = { emptyList() }, requiresTarget = { it.target == EffectTarget.TARGET },
            decode = { reader ->
                val operation = when (reader.option("operation", setOf("set", "add", "reset"), "set")) {
                    "set" -> StatusStateOperation.SET
                    "add" -> StatusStateOperation.ADD
                    "reset" -> StatusStateOperation.RESET
                    else -> error("invalid status state operation")
                }
                if (operation == StatusStateOperation.SET && (!reader.has("value") || reader.has("amount")))
                    error("set requires value and forbids amount")
                if (operation == StatusStateOperation.ADD && (!reader.has("amount") || reader.has("value")))
                    error("add requires amount and forbids value")
                if (operation == StatusStateOperation.RESET && (reader.has("value") || reader.has("amount")))
                    error("reset forbids value and amount")
                Effect.WriteStatusState(reader.reference("state"), reader.localId("field"), reader.target("target"),
                    reader.statusFilter(), operation,
                    if (operation == StatusStateOperation.SET) reader.stateInput("value") else null,
                    if (operation == StatusStateOperation.ADD) reader.numeric("amount") else null, reader.resultName)
            }, execute = { e, c -> c.writeStatusState(e) }),
        EffectMechanic("archetype:launch_projectile", Effect.LaunchProjectile::class.java,
            mapOf("projectile" to ref("projectile_call"), "direction" to mapOf<String, Any>("enum" to listOf("actor.aim", "actor.to_target", "actor.to_ground"))),
            setOf("projectile"),
            "Launches a bounded source-owned logical projectile from the actor. Native ray collisions choose the first eligible entity or solid block; cancellation produces no impact or expiry callback.",
            setOf("launched", "handle"), projectileReferences = { listOf(it.projectile) },
            numericInputs = { it.arguments.mapKeys { (name, _) -> "projectile.with.$name" } },
            requiresTarget = { it.direction == ProjectileDirection.TARGET }, requiresEntityTarget = { it.direction == ProjectileDirection.TARGET },
            requiresGround = { it.direction == ProjectileDirection.GROUND },
            decode = { reader ->
                val direction = when (reader.option("direction", setOf("actor.aim", "actor.to_target", "actor.to_ground"), "actor.aim")) {
                    "actor.aim" -> ProjectileDirection.AIM
                    "actor.to_target" -> ProjectileDirection.TARGET
                    "actor.to_ground" -> ProjectileDirection.GROUND
                    else -> error("invalid projectile direction")
                }
                val (projectile, arguments) = reader.referenceWith("projectile")
                Effect.LaunchProjectile(projectile, direction, reader.resultName, arguments)
            }, execute = { e, c -> c.launchProjectile(e) }),
        EffectMechanic("archetype:wait_for", Effect.WaitFor::class.java,
            mapOf("handle" to text, "event" to mapOf<String, Any>("enum" to listOf("projectile.entity_hit", "projectile.block_hit", "projectile.expiry")),
                "timeout" to duration, "matched" to (body + ("minItems" to 0)), "timed_out" to (body + ("minItems" to 0))),
            setOf("handle", "event", "timeout"),
            "Registers one source-owned wait for a specific projectile handle and event. A bounded receipt catches an earlier outcome; natural timeout runs timed_out, while source cancellation runs neither body.",
            children = { listOf(it.matched, it.timedOut) },
            decode = { reader ->
                val handle = reader.text("handle")
                val event = when (reader.option("event", setOf("projectile.entity_hit", "projectile.block_hit", "projectile.expiry"), "projectile.entity_hit")) {
                    "projectile.entity_hit" -> ProjectileEvent.ENTITY_HIT
                    "projectile.block_hit" -> ProjectileEvent.BLOCK_HIT
                    "projectile.expiry" -> ProjectileEvent.EXPIRY
                    else -> error("invalid projectile event")
                }
                Effect.WaitFor(handle, event, reader.duration("timeout", positive = true), reader.effects("matched", false), reader.effects("timed_out", false))
            }, execute = { e, c -> c.waitFor(e); null }),
        EffectMechanic("archetype:set_state", Effect.SetState::class.java,
            mapOf("state" to ref("reference"), "field" to timerName, "value" to stateValue), setOf("state", "field", "value"),
            "Sets a declared player, class, or activation field. Numeric values clamp to its bounds; enum and Boolean values must match the declaration.",
            setOf("previous", "current"), numericInputs = { (it.value as? StateInput.Number)?.let { input -> mapOf("value" to input.value) } ?: emptyMap() },
            stateReferences = { listOf(it.state) },
            decode = { Effect.SetState(it.reference("state"), it.localId("field"), it.stateInput("value"), it.resultName) },
            execute = { e, c -> c.setState(e) }),
        EffectMechanic("archetype:add_state", Effect.AddState::class.java,
            mapOf("state" to ref("reference"), "field" to timerName, "amount" to numeric), setOf("state", "field", "amount"),
            "Adds to a bounded numeric state field, clamping to its declared range.", setOf("previous", "current"),
            numericInputs = { mapOf("amount" to it.amount) }, stateReferences = { listOf(it.state) },
            decode = { Effect.AddState(it.reference("state"), it.localId("field"), it.numeric("amount"), it.resultName) },
            execute = { e, c -> c.addState(e) }),
        EffectMechanic("archetype:reset_state", Effect.ResetState::class.java,
            mapOf("state" to ref("reference"), "field" to timerName), setOf("state", "field"),
            "Resets a declared field to its initial value.", setOf("previous", "current"),
            stateReferences = { listOf(it.state) },
            decode = { Effect.ResetState(it.reference("state"), it.localId("field"), it.resultName) },
            execute = { e, c -> c.resetState(e) }),
        EffectMechanic("archetype:read_state", Effect.ReadState::class.java,
            mapOf("state" to ref("reference"), "field" to timerName), setOf("state", "field"),
            "Reads a declared numeric or Boolean field into a result. Boolean values are zero or one.", setOf("value"),
            stateReferences = { listOf(it.state) },
            decode = { Effect.ReadState(it.reference("state"), it.localId("field"), it.resultName) },
            execute = { e, c -> c.readState(e) }),
        EffectMechanic("archetype:apply_status", Effect.ApplyStatus::class.java, mapOf("status" to ref("reference"), "target" to target), setOf("status", "target"), "Refreshes a stable source contribution without restarting its periodic cadence. Temporary presence remains owned by the source class.",
            statusReferences = { listOf(it.status) }, requiresTarget = { it.target == EffectTarget.TARGET },
            decode = { Effect.ApplyStatus(it.reference("status"), it.target("target"), it.identity) },
            execute = { e, c -> c.status(e); null }),
        EffectMechanic("archetype:dispel", Effect.Dispel::class.java,
            statusFilterFields + mapOf("target" to target, "count" to (integer(1, 64) + ("default" to 1))), setOf("target"),
            "Removes up to count matching source contributions, oldest application first. Cancels owned work without expiry callbacks. Other contributions and the enclosing area membership remain active.",
            setOf("contributions_removed", "stacks_removed"),
            statusReferences = { listOfNotNull(it.filter.status) }, statusCreations = { emptyList() },
            requiresTarget = { it.target == EffectTarget.TARGET },
            decode = { Effect.Dispel(it.target("target"), it.statusFilter(), it.integer("count", 1, 64, 1), it.resultName) },
            execute = { e, c -> c.dispel(e) }),
        EffectMechanic("archetype:consume_status", Effect.ConsumeStatus::class.java,
            statusFilterFields + mapOf("target" to target, "count" to integer(1, 64)), setOf("target", "count"),
            "Consumes up to count matching stacks, oldest contribution and earliest-expiring stack first. Partial contributions run stacks_changed; empty contributions are cancelled without expiry gameplay callbacks.",
            setOf("stacks_removed", "contributions_removed"),
            statusReferences = { listOfNotNull(it.filter.status) }, statusCreations = { emptyList() },
            requiresTarget = { it.target == EffectTarget.TARGET },
            decode = { Effect.ConsumeStatus(it.target("target"), it.statusFilter(), it.integer("count", 1, 64), it.resultName) },
            execute = { e, c -> c.consumeStatus(e) }),
        EffectMechanic("archetype:read_status", Effect.ReadStatus::class.java,
            statusFilterFields + mapOf("target" to target), setOf("target"),
            "Reads live matching contributions and their total stack count on one recipient. An eligible target with no matches returns zeroes.",
            setOf("contributions", "stacks"),
            statusReferences = { listOfNotNull(it.filter.status) }, statusCreations = { emptyList() },
            requiresTarget = { it.target == EffectTarget.TARGET },
            decode = { Effect.ReadStatus(it.target("target"), it.statusFilter(), it.resultName) },
            execute = { e, c -> c.readStatus(e) }),
        EffectMechanic("archetype:set_timer", Effect.SetTimer::class.java,
            mapOf("name" to timerName, "target" to target, "duration" to duration, "expired" to (body + ("minItems" to 0))),
            setOf("name", "target", "duration"),
            "Sets or refreshes a named timer for this logical grant and recipient. An old expiry cannot fire after refresh. The optional expiry body runs once on natural expiry; cancellation and source cleanup never run it.",
            children = { listOf(it.expired) }, requiresTarget = { it.target == EffectTarget.TARGET },
            decode = { Effect.SetTimer(it.localId("name"), it.target("target"), it.duration("duration", positive = true), it.effects("expired", false)) },
            execute = { e, c -> c.setTimer(e); null }),
        EffectMechanic("archetype:cancel_timer", Effect.CancelTimer::class.java,
            mapOf("name" to timerName, "target" to target), setOf("name", "target"),
            "Cancels this logical grant's named timer for the recipient, including its owned continuations, without an expiry callback.",
            setOf("cancelled"), requiresTarget = { it.target == EffectTarget.TARGET },
            decode = { Effect.CancelTimer(it.localId("name"), it.target("target"), it.resultName) },
            execute = { e, c -> c.cancelTimer(e) }),
        EffectMechanic("archetype:read_timer", Effect.ReadTimer::class.java,
            mapOf("name" to timerName, "target" to target), setOf("name", "target"),
            "Reads this logical grant's named timer for the recipient. An absent timer returns zero for both fields.",
            setOf("active", "remaining_ticks"), requiresTarget = { it.target == EffectTarget.TARGET },
            decode = { Effect.ReadTimer(it.localId("name"), it.target("target"), it.resultName) },
            execute = { e, c -> c.readTimer(e) }),
        EffectMechanic("archetype:restore_charge", Effect.RestoreCharge::class.java,
            mapOf("count" to (integer(1, 16) + ("default" to 1)), "grant" to grantName), emptySet(),
            "Restores up to count missing uses on a logical grant (`self` by default). Removes the corresponding latest recharge timers. Does nothing if the grant has no charges.",
            setOf("restored", "available_charges"),
            grantReferences = { listOf(it.grant) },
            decode = { Effect.RestoreCharge(it.integer("count", 1, 16, 1), if (it.has("grant")) it.grant("grant") else "self", it.resultName) },
            execute = { e, c -> c.restoreCharge(e) }),
        EffectMechanic("archetype:reduce_cooldown", Effect.ReduceCooldown::class.java,
            mapOf("amount" to duration, "grant" to grantName), setOf("amount"),
            "Reduces a logical grant's active cooldown by a fixed duration, clamping at zero. `grant` defaults to `self`; this never grants a charge.",
            setOf("remaining_ticks"),
            grantReferences = { listOf(it.grant) },
            decode = { Effect.ReduceCooldown(it.duration("amount"), if (it.has("grant")) it.grant("grant") else "self", it.resultName) },
            execute = { e, c -> c.reduceCooldown(e) }),
        EffectMechanic("archetype:reduce_recharge", Effect.ReduceRecharge::class.java,
            mapOf("amount" to duration, "grant" to grantName, "which" to mapOf<String, Any>("enum" to listOf("earliest", "latest", "all"))), setOf("amount"),
            "Reduces the selected active recharge timer or timers on a logical grant. An expired timer restores one charge; later sequential timers keep their full interval.",
            setOf("restored", "available_charges", "next_recharge_ticks"),
            grantReferences = { listOf(it.grant) },
            decode = { reader ->
                val selection = when (reader.option("which", setOf("earliest", "latest", "all"), "earliest")) {
                    "earliest" -> RechargeSelection.EARLIEST
                    "latest" -> RechargeSelection.LATEST
                    "all" -> RechargeSelection.ALL
                    else -> error("invalid recharge selection")
                }
                Effect.ReduceRecharge(reader.duration("amount"), if (reader.has("grant")) reader.grant("grant") else "self", selection, reader.resultName)
            }, execute = { e, c -> c.reduceRecharge(e) }),
        EffectMechanic("archetype:reduce_group_cooldown", Effect.ReduceGroupCooldown::class.java,
            mapOf("group" to ref("reference"), "amount" to duration), setOf("group", "amount"),
            "Reduces a named shared cooldown timer by a fixed duration, clamping at zero.", setOf("remaining_ticks"),
            decode = { Effect.ReduceGroupCooldown(it.reference("group"), it.duration("amount"), it.resultName) },
            execute = { e, c -> c.reduceGroupCooldown(e) }),
        EffectMechanic("archetype:reduce_global_cooldown", Effect.ReduceGlobalCooldown::class.java,
            mapOf("amount" to duration), setOf("amount"),
            "Reduces the player-wide opt-in global cooldown by a fixed duration, clamping at zero.", setOf("remaining_ticks"),
            decode = { Effect.ReduceGlobalCooldown(it.duration("amount"), it.resultName) },
            execute = { e, c -> c.reduceGlobalCooldown(e) }),
        EffectMechanic("archetype:heal", Effect.Heal::class.java, mapOf("target" to target, "amount" to numeric), setOf("target", "amount"), "Immediate native healing.", setOf("health_restored", "overheal"),
            numericInputs = { mapOf("amount" to it.amount) }, requiresTarget = { it.target == EffectTarget.TARGET },
            decode = { Effect.Heal(it.target("target"), it.numeric("amount"), it.resultName) },
            execute = { e, c -> c.heal(e.target, e.amount)?.let { mapOf("health_restored" to it.restored, "overheal" to it.overheal) } }),
        EffectMechanic("archetype:damage", Effect.Damage::class.java, mapOf("target" to target, "amount" to numeric, "damage_type" to text), setOf("target", "amount", "damage_type"), "Immediate native damage credited to the actor.", setOf("health_lost"),
            numericInputs = { mapOf("amount" to it.amount) }, requiresTarget = { it.target == EffectTarget.TARGET },
            decode = { Effect.Damage(it.target("target"), it.numeric("amount"), it.text("damage_type"), it.resultName) },
            execute = { e, c -> c.damage(e.target, e.amount, e.damageType)?.let { mapOf("health_lost" to it) } }),
        EffectMechanic("archetype:read_health", Effect.ReadHealth::class.java, mapOf("target" to target), setOf("target"),
            "Reads the recipient's current native health after target revalidation. Returns health, maximum, missing, and fraction; target loss supplies no result.",
            setOf("health", "maximum", "missing", "fraction"), requiresTarget = { it.target == EffectTarget.TARGET },
            decode = { Effect.ReadHealth(it.target("target"), it.resultName) },
            execute = { e, c -> c.readHealth(e.target) }),
        EffectMechanic("archetype:shield", Effect.Shield::class.java,
            mapOf("target" to target, "capacity" to numeric, "duration" to duration,
                "priority" to integer(-100, 100), "damage_type" to text, "depleted" to (body + ("minItems" to 0))),
            setOf("target", "capacity", "duration"),
            "Creates a finite owned barrier. Matching damage consumes it after native armor, magic and absorption; priority descends, then creation order. The optional depleted body runs after the native hit commits, never on expiry or cancellation.",
            setOf("capacity"), numericInputs = { mapOf("capacity" to it.capacity) }, children = { listOf(it.depleted) },
            requiresTarget = { it.target == EffectTarget.TARGET },
            decode = { Effect.Shield(it.target("target"), it.numeric("capacity"), it.duration("duration", positive = true),
                it.integer("priority", -100, 100, 0), if (it.has("damage_type")) it.text("damage_type") else null,
                it.effects("depleted", false), it.resultName) },
            execute = { e, c -> c.shield(e) }),
        EffectMechanic("archetype:gain_resource", Effect.GainResource::class.java, mapOf("resource" to ref("reference"), "amount" to numeric), setOf("resource", "amount"), "Immediate clamped resource gain.", setOf("amount"),
            numericInputs = { mapOf("amount" to it.amount) }, resourceReferences = { listOf(it.resource) },
            decode = { Effect.GainResource(it.reference("resource"), it.numeric("amount"), it.resultName) },
            execute = { e, c -> mapOf("amount" to c.resource(e.resource, e.amount, false)) }),
        EffectMechanic("archetype:spend_resource", Effect.SpendResource::class.java, mapOf("resource" to ref("reference"), "amount" to numeric), setOf("resource", "amount"), "Immediate resource consumption, interrupted on insufficient balance.", setOf("amount"),
            numericInputs = { mapOf("amount" to it.amount) }, resourceReferences = { listOf(it.resource) },
            decode = { Effect.SpendResource(it.reference("resource"), it.numeric("amount"), it.resultName) },
            execute = { e, c -> mapOf("amount" to c.resource(e.resource, e.amount, true)) }),
        EffectMechanic("archetype:set_resource", Effect.SetResource::class.java, mapOf("resource" to ref("reference"), "value" to numeric), setOf("resource", "value"),
            "Sets a resource balance to a bounded value, clamped to its definition. Returns previous, current and absolute amount changed.",
            setOf("previous", "current", "changed"), numericInputs = { mapOf("value" to it.value) }, resourceReferences = { listOf(it.resource) },
            decode = { Effect.SetResource(it.reference("resource"), it.numeric("value"), it.resultName) },
            execute = { e, c -> c.setResource(e.resource, e.value) }),
        EffectMechanic("archetype:reset_resource", Effect.ResetResource::class.java, mapOf("resource" to ref("reference")), setOf("resource"),
            "Resets a resource balance to its declared initial value. Returns previous, current and absolute amount changed.",
            setOf("previous", "current", "changed"), resourceReferences = { listOf(it.resource) },
            decode = { Effect.ResetResource(it.reference("resource"), it.resultName) },
            execute = { e, c -> c.setResource(e.resource, null) }),
        EffectMechanic("archetype:delay", Effect.Delay::class.java, mapOf("duration" to duration, "effects" to body), setOf("duration", "effects"), "Owned continuation, cancelled with its source; shares the originating budget.",
            children = { listOf(it.effects) },
            decode = { Effect.Delay(it.duration("duration", positive = true), it.effects("effects")) },
            execute = { e, c -> c.delay(e.ticks, e.effects); null }),
        EffectMechanic("archetype:sequence", Effect.Sequence::class.java, mapOf("effects" to body), setOf("effects"),
            "Runs a nested effect list in order in the current scope. Results produced by the sequence remain available to following steps.",
            children = { listOf(it.effects) },
            decode = { Effect.Sequence(it.effects("effects")) },
            execute = { e, c -> c.sequence(e.effects); null }),
        EffectMechanic("archetype:repeat", Effect.Repeat::class.java, mapOf("count" to integer(1, 64), "every" to duration, "effects" to body), setOf("count", "every", "effects"), "Finite positive-interval repetition with independent invocation bindings and a shared budget.",
            children = { listOf(it.effects) },
            decode = { Effect.Repeat(it.integer("count", 1, 64), it.duration("every", positive = true), it.effects("effects")) },
            execute = { e, c -> c.delay(e.everyTicks, e.effects, e.count, e.everyTicks); null }),
        EffectMechanic("archetype:branch", Effect.Branch::class.java, mapOf("when" to ref("condition"), "then" to body, "else" to (body + ("minItems" to 0))), setOf("when", "then"), "Ordered conditional sequence in the current scope.",
            children = { listOf(it.onTrue, it.onFalse) },
            numericInputs = { branch -> branch.condition.leaves().toList().flatMapIndexed { index, condition -> when (condition) {
                is Condition.Compare -> listOf("when[$index].left" to condition.left, "when[$index].right" to condition.right)
                is Condition.ResourceAtLeast -> listOf("when[$index].amount" to condition.amount)
                else -> emptyList()
            } }.toMap() },
            resourceReferences = { branch -> branch.condition.leaves().filterIsInstance<Condition.ResourceAtLeast>().map { it.resource }.toList() },
            statusReferences = { branch -> branch.condition.leaves().filterIsInstance<Condition.HasStatus>().mapNotNull { it.filter.status }.toList() },
            stateReferences = { branch -> branch.condition.leaves().filterIsInstance<Condition.StateIs>().map { it.state }.toList() },
            statusCreations = { emptyList() },
            requiresTarget = { branch -> branch.condition.leaves().filterIsInstance<Condition.HasStatus>().any { it.target == EffectTarget.TARGET } },
            decode = { Effect.Branch(it.condition("when"), it.effects("then"), it.effects("else", false)) },
            execute = { e, c -> c.branch(e.condition, e.onTrue, e.onFalse); null }),
        EffectMechanic("archetype:choose", Effect.Choose::class.java,
            mapOf("options" to mapOf<String, Any>("type" to "array", "minItems" to 1, "maxItems" to 16,
                "items" to mapOf("type" to "object", "properties" to mapOf("weight" to integer(1, 1000), "effects" to body),
                    "required" to listOf("weight", "effects"), "additionalProperties" to false))), setOf("options"),
            "Draws once on the server and executes one weighted body. Weights are positive integers; ties follow declaration order. The selected zero-based index is available through `as`.",
            setOf("index"), children = { it.options.map(WeightedBranch::effects) },
            decode = { Effect.Choose(it.choices("options"), it.resultName) },
            execute = { e, c -> c.choose(e) }),
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
