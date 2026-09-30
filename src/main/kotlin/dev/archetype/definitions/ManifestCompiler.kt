package dev.archetype.definitions

import org.snakeyaml.engine.v2.api.Load
import org.snakeyaml.engine.v2.api.LoadSettings
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

/** Compiles only operations backed by runtime handlers. No accepted YAML is stored as an untyped map. */
class ManifestCompiler(private val catalog: MechanicCatalog = BuiltinEffects.catalog) {
    private val loader = Load(
        LoadSettings.builder()
            .setAllowDuplicateKeys(false)
            .setMaxAliasesForCollections(0)
            .setCodePointLimit(1_048_576)
            .build(),
    )

    fun compile(snapshot: PackSnapshot): CompileResult {
        val errors = mutableListOf<Diagnostic>()
        val documents = mutableListOf<Doc>()
        // ASVS 1.5.2, 15.3.5: YAML becomes scalar/list/map data only; every field is then checked.
        for (file in snapshot.files) {
            try {
                val decoder = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                val value = loader.loadFromString(decoder.decode(ByteBuffer.wrap(file.bytes)).toString())
                documents += Doc(file.relativePath, value.asMap(file.relativePath, "$"))
            } catch (failure: InvalidManifest) {
                errors += Diagnostic(file.relativePath, failure.field, failure.message ?: "invalid manifest")
            } catch (failure: Exception) {
                errors += Diagnostic(file.relativePath, "$", failure.message?.lineSequence()?.firstOrNull() ?: "invalid YAML")
            }
        }
        if (errors.isNotEmpty()) return CompileResult.Invalid(errors)

        val packs = linkedMapOf<String, Pack>()
        val definitions = mutableListOf<Pair<Doc, String>>()
        for (doc in documents) {
            val folder = doc.file.substringBefore('/')
            try {
                if (!doc.file.contains('/')) bad("$", "manifest must be inside a pack folder")
                if (doc.file.substringAfterLast('/') == "pack.yaml" && doc.file.count { it == '/' } == 1) {
                    val p = parsePack(doc)
                    if (p.id != folder) bad("id", "pack ID must match its folder")
                    if (packs.putIfAbsent(p.id, p) != null) bad("id", "duplicate pack ID")
                } else {
                    definitions += doc to folder
                }
            } catch (failure: InvalidManifest) {
                errors += Diagnostic(doc.file, failure.field, failure.message ?: "invalid manifest")
            }
        }
        for (pack in packs.values) {
            for (dependency in pack.dependencies) {
                if (dependency !in packs) errors += Diagnostic("${pack.id}/pack.yaml", "dependencies", "missing pack $dependency")
                if (dependency == pack.id) errors += Diagnostic("${pack.id}/pack.yaml", "dependencies", "pack cannot depend on itself")
            }
        }

        val resources = linkedMapOf<String, ResourceDef>()
        val abilities = linkedMapOf<String, AbilityDef>()
        val classes = linkedMapOf<String, ClassDef>()
        val areas = linkedMapOf<String, AreaDef>()
        val statuses = linkedMapOf<String, StatusDef>()
        val rawClasses = mutableListOf<Pair<Doc, String>>()
        val usedIds = mutableSetOf<String>()
        for ((doc, packId) in definitions) {
            try {
                val pack = packs[packId] ?: bad("$", "pack.yaml is missing")
                val kind = doc.data.string("kind", "$")
                val id = qualify(doc.data.string("id", "$"), pack.id, pack, "id")
                if (!usedIds.add(id)) bad("id", "duplicate definition ID $id")
                when (kind) {
                    "resource" -> resources[id] = parseResource(doc.data, id)
                    "ability" -> abilities[id] = parseAbility(doc.data, id, true, pack)
                    "area" -> areas[id] = parseArea(doc.data, id, pack)
                    "status" -> statuses[id] = parseStatus(doc.data, id, pack)
                    "class" -> rawClasses += doc to packId
                    else -> bad("kind", "unsupported definition kind $kind")
                }
            } catch (failure: InvalidManifest) {
                errors += Diagnostic(doc.file, failure.field, failure.message ?: "invalid manifest")
            }
        }
        for ((doc, packId) in rawClasses) {
            try {
                val pack = packs.getValue(packId)
                val id = qualify(doc.data.string("id", "$"), packId, pack, "id")
                classes[id] = parseClass(doc.data, id, pack, abilities)
            } catch (failure: InvalidManifest) {
                errors += Diagnostic(doc.file, failure.field, failure.message ?: "invalid manifest")
            }
        }
        if (classes.size > 128) errors += Diagnostic("packs", "classes", "at most 128 classes are supported")
        if (resources.size > 128) errors += Diagnostic("packs", "resources", "at most 128 resources are supported")
        if (areas.size > 256) errors += Diagnostic("packs", "areas", "at most 256 areas are supported")
        if (statuses.size > 256) errors += Diagnostic("packs", "statuses", "at most 256 statuses are supported")
        // ASVS 2.2.3: one attribute must have one unambiguous composition policy across packs.
        if (statuses.values.mapNotNull { it.speed }.map { it.combination to it.cap }.distinct().size > 1)
            errors += Diagnostic("packs", "statuses.modifiers", "movement speed modifiers must use the same stacking policy and cap")
        for ((id, ability) in abilities + classes.values.flatMap { it.grants.values }.associate { it.ability.id to it.ability }) {
            val pack = packs[id.substringBefore(':')] ?: continue
            for (cost in ability.costs) {
                if (cost.resource !in resources) errors += Diagnostic(id, "costs", "unknown resource ${cost.resource}")
                else if (cost.resource.substringBefore(':') !in pack.dependencies + pack.id) errors += Diagnostic(id, "costs", "undeclared pack dependency")
            }
        }
        val bodies = abilities.mapValues { it.value.effects } + classes.values.flatMap { it.grants.values }.associate { it.ability.id to it.ability.effects } +
            areas.mapValues { (_, area) -> area.enter + area.periodic + area.exit + area.expired } + statuses.mapValues { it.value.bodies }
        val declaredGroups = (abilities.values + classes.values.flatMap { it.grants.values.map(Grant::ability) }).flatMap { it.cooldownGroups }.toSet()
        for ((id, effects) in bodies) for (effect in effects.flatMap { catalog.descendants(it).toList() }) {
            if (effect is Effect.ReduceGroupCooldown && effect.group !in declaredGroups)
                errors += Diagnostic(id, "effects.group", "unknown cooldown group ${effect.group}")
        }
        for ((classId, classDef) in classes) for ((grantName, grant) in classDef.grants) {
            // ASVS 2.2.2, 2.2.3: resolve named grant edits against each actual granting class, including controller callbacks.
            val visited = mutableSetOf<String>()
            val reported = mutableSetOf<String>()
            fun visit(effects: List<Effect>) {
                for (effect in effects.flatMap { catalog.descendants(it).toList() }) {
                    val mechanic = catalog.mechanic(effect)
                    for (ref in mechanic.grants(effect)) if (ref != "self" && ref !in classDef.grants && reported.add(ref))
                        errors += Diagnostic("$classId/$grantName", "effects.grant", "unknown logical grant $ref")
                    for (ref in mechanic.areas(effect)) if (visited.add("area:$ref")) areas[ref]?.let { area ->
                        visit(area.enter + area.periodic + area.exit + area.expired)
                        area.buffs.forEach { buff -> if (visited.add("status:$buff")) statuses[buff]?.let { visit(it.bodies) } }
                    }
                    for (ref in mechanic.createdStatuses(effect)) if (visited.add("status:$ref")) statuses[ref]?.let { visit(it.bodies) }
                }
            }
            visit(grant.ability.effects)
        }
        for ((id, area) in areas) for (status in area.buffs) if (status !in statuses) errors += Diagnostic(id, "buffs", "unknown status $status")
        for ((id, effects) in bodies) for (effect in effects.flatMap { catalog.descendants(it).toList() }) {
            val mechanic = catalog.mechanic(effect)
            for (resource in mechanic.resources(effect)) if (resource !in resources) errors += Diagnostic(id, "effects", "unknown resource $resource")
            for (area in mechanic.areas(effect)) if (area !in areas) errors += Diagnostic(id, "effects", "unknown area $area")
            for (status in mechanic.statuses(effect)) if (status !in statuses) errors += Diagnostic(id, "effects", "unknown status $status")
        }
        for ((id, effects) in bodies) {
            val applications = effects.flatMap { catalog.descendants(it).toList() }.filterIsInstance<Effect.ApplyStatus>()
            if (applications.map { it.applicationId }.distinct().size != applications.size) errors += Diagnostic(id, "effects.id", "duplicate status application identity")
        }
        val visited = mutableSetOf<String>()
        val visiting = mutableSetOf<String>()
        fun visitController(id: String) {
            if (id in visited || (id !in areas && id !in statuses)) return
            if (!visiting.add(id)) {
                errors += Diagnostic(id, "effects", "recursive area or status creation is not supported")
                return
            }
            for (ref in areas[id]?.buffs.orEmpty()) visitController(ref)
            for (effect in bodies.getValue(id).flatMap { catalog.descendants(it).toList() }) {
                val mechanic = catalog.mechanic(effect)
                for (ref in mechanic.areas(effect) + mechanic.createdStatuses(effect)) visitController(ref)
            }
            visiting -= id
            visited += id
        }
        (areas.keys + statuses.keys).forEach(::visitController)
        if (errors.isNotEmpty()) return CompileResult.Invalid(errors)
        return CompileResult.Valid(DefinitionSet(packs, resources, abilities, classes, snapshot.fingerprint, areas, statuses))
    }

    private fun parsePack(doc: Doc): Pack {
        val m = doc.data
        m.only(setOf("format", "id", "name", "dependencies"), "$")
        if (m.integer("format", "$") != 1) bad("format", "supported format is 1")
        val id = m.string("id", "$")
        if (id.length > 64 || !PACK_ID.matches(id)) bad("id", "invalid pack ID")
        val rawDependencies = m.listOrEmpty("dependencies", "$")
        if (rawDependencies.size > 128) bad("dependencies", "at most 128 dependencies are supported")
        val dependencies = rawDependencies.mapIndexed { index, value ->
            val dependency = value.asString("dependencies[$index]")
            if (dependency.length > 64 || !PACK_ID.matches(dependency)) bad("dependencies[$index]", "invalid pack ID")
            dependency
        }.toSet()
        return Pack(id, m.string("name", "$"), dependencies)
    }

    private fun parseResource(m: Map<String, Any?>, id: String): ResourceDef {
        m.only(setOf("kind", "id", "scope", "min", "max", "initial", "regeneration"), "$")
        val scope = when (m.string("scope", "$")) {
            "class" -> ResourceScope.CLASS
            "player" -> ResourceScope.PLAYER
            else -> bad("scope", "supported scopes are class and player")
        }
        val min = m.number("min", "$" )
        val max = m.number("max", "$" )
        val initial = m.number("initial", "$" )
        if (min < -1_000_000_000 || max > 1_000_000_000 || min > max || initial !in min..max) bad("initial", "resource bounds or initial value are invalid")
        val regeneration = m["regeneration"]?.asMap("regeneration", "regeneration")?.let { regen ->
            regen.only(setOf("amount", "every"), "regeneration")
            val amount = regen.number("amount", "regeneration")
            if (amount <= 0 || amount > 1_000_000) bad("regeneration.amount", "must be positive and at most 1000000")
            Regeneration(amount, ticks(regen.string("every", "regeneration"), "regeneration.every"))
        }
        return ResourceDef(id, scope, min, max, initial, regeneration)
    }

    private fun parseAbility(m: Map<String, Any?>, id: String, global: Boolean, pack: Pack): AbilityDef {
        val allowed = setOf("name", "description", "activation", "cooldown", "charges", "costs", "effects", "icon", "target") + if (global) setOf("kind", "id") else emptySet()
        m.only(allowed, "$" )
        for (field in listOf("description", "icon")) if (field in m) m.string(field, "$")
        val activation = when (val mode = m["activation"]) {
            null -> Activation.ACTIVATED
            is Map<*, *> -> {
                val settings = mode.asMap("activation", "activation")
                settings.only(setOf("type"), "activation")
                when (settings.string("type", "activation")) {
                    "activated" -> Activation.ACTIVATED
                    "passive" -> Activation.PASSIVE
                    else -> bad("activation.type", "unsupported activation mode")
                }
            }
            else -> bad("activation", "expected a mapping")
        }
        var cooldown = 0
        var cooldownGroups: Set<String> = emptySet()
        var globalCooldown = 0
        // ASVS 2.2.1, 2.2.3: timing modes and group combinations are checked before publication.
        when (val timing = m["cooldown"]) {
            null -> Unit
            is String -> cooldown = ticks(timing, "cooldown")
            is Map<*, *> -> {
                val settings = timing.asMap("cooldown", "cooldown")
                settings.only(setOf("duration", "groups", "global"), "cooldown")
                cooldown = ticks(settings.string("duration", "cooldown"), "cooldown.duration")
                val raw = settings.listOrEmpty("groups", "cooldown")
                if (raw.size > 8) bad("cooldown.groups", "at most 8 shared cooldown groups are supported")
                val groups = raw.mapIndexed { index, value -> qualify(value.asString("cooldown.groups[$index]"), pack.id, pack, "cooldown.groups[$index]") }
                if (groups.distinct().size != groups.size) bad("cooldown.groups", "duplicate cooldown group")
                cooldownGroups = groups.toSet()
                globalCooldown = settings["global"]?.asString("cooldown.global")?.let { ticks(it, "cooldown.global") } ?: 0
                if ((cooldownGroups.isNotEmpty() || globalCooldown > 0) && cooldown <= 0) bad("cooldown.duration", "shared cooldown requires a positive ability duration")
            }
            else -> bad("cooldown", "expected a duration or timing mapping")
        }
        val charges = m["charges"]?.asMap("charges", "charges")?.let { settings ->
            // ASVS 2.2.1, 2.3.2: finite capacity and positive recharge bound future work.
            settings.only(setOf("max", "recharge", "mode"), "charges")
            val maximum = settings.integer("max", "charges")
            if (maximum !in 1..16) bad("charges.max", "charge capacity must be 1..16")
            val recharge = ticks(settings.string("recharge", "charges"), "charges.recharge")
            if (recharge <= 0) bad("charges.recharge", "recharge interval must be positive")
            val mode = when (settings["mode"] ?: "sequential") {
                "sequential" -> RechargeMode.SEQUENTIAL
                "parallel" -> RechargeMode.PARALLEL
                else -> bad("charges.mode", "supported recharge modes are sequential and parallel")
            }
            ChargeDef(maximum, recharge, mode)
        }
        val rawCosts = m.listOrEmpty("costs", "$")
        if (rawCosts.size > 128) bad("costs", "at most 128 costs are supported")
        val costs = rawCosts.mapIndexed { index, value ->
            val field = "costs[$index]"
            val cost = value.asMap(field, field)
            cost.only(setOf("resource", "amount"), field)
            val resource = qualify(cost.string("resource", field), pack.id, pack, "$field.resource")
            Cost(resource, numeric(cost["amount"], "$field.amount").also { checkNumeric(it, emptySet(), "$field.amount") })
        }
        if (activation == Activation.PASSIVE) bad("activation.type", "passive abilities are not implemented yet")
        val effectNames = mutableSetOf<String>()
        val rawEffects = m.listOrEmpty("effects", "$")
        if (rawEffects.size > 64) bad("effects", "effect list exceeds 64 steps")
        val effects = rawEffects.mapIndexed { index, value ->
            val field = "effects[$index]"
            val effect = parseEffect(value.asMap(field, field), field, pack, 0)
            effect.resultName?.let { if (!effectNames.add(it)) bad("$field.as", "duplicate result name") }
            effect
        }
        if (effects.isEmpty()) bad("effects", "ability must have an effect")
        validateBindings(effects, emptySet(), "effects")
        val targeting = m["target"]?.asMap("target", "target")?.let { target ->
            target.only(setOf("type", "range"), "target")
            val type = when (target.string("type", "target")) {
                "entity" -> Targeting.Type.ENTITY
                "ground" -> Targeting.Type.GROUND
                else -> bad("target.type", "supported targeting modes are entity and ground")
            }
            Targeting(type, boundedNumber(target, "range", "target", 0.01, 32.0, 32.0))
        } ?: Targeting()
        if (targeting.type != Targeting.Type.GROUND && effects.any { requiresGround(it) }) bad("target", "ground positions require target.type: ground")
        validateContexts(effects, targeting.type == Targeting.Type.ENTITY, targeting.type == Targeting.Type.GROUND, "effects")
        return AbilityDef(id, m.string("name", "$"), activation, cooldown, costs, effects, targeting, charges, cooldownGroups, globalCooldown)
    }

    private fun requiresGround(effect: Effect): Boolean = catalog.mechanic(effect).let { mechanic ->
        mechanic.needsGround(effect) || mechanic.nested(effect).any { body -> body.any { requiresGround(it) } }
    }

    private fun parseArea(m: Map<String, Any?>, id: String, pack: Pack): AreaDef {
        m.only(setOf("kind", "id", "shape", "duration", "sample_every", "targets", "enter", "periodic", "exit", "expired", "buffs"), "$")
        val shape = parseShape(m["shape"].asMap("shape", "shape"), "shape")
        val duration = ticks(m.string("duration", "$"), "duration")
        val sample = m["sample_every"]?.asString("sample_every")?.let { ticks(it, "sample_every") } ?: 2
        if (duration <= 0) bad("duration", "area lifetime must be positive")
        if (sample <= 0) bad("sample_every", "sampling interval must be positive")
        val selector = parseSelector(m["targets"].asMap("targets", "targets"), "targets", shape)
        val enter = nestedEffects(m, "enter", "$", pack, 0, false)
        val exit = nestedEffects(m, "exit", "$", pack, 0, false)
        val expired = nestedEffects(m, "expired", "$", pack, 0, false)
        var interval = 0
        val periodic = m["periodic"]?.asMap("periodic", "periodic")?.let { pulse ->
            pulse.only(setOf("every", "effects"), "periodic")
            interval = ticks(pulse.string("every", "periodic"), "periodic.every")
            if (interval <= 0) bad("periodic.every", "periodic interval must be positive")
            nestedEffects(pulse, "effects", "periodic", pack, 0)
        }.orEmpty()
        for ((field, body) in listOf("enter" to enter, "exit" to exit, "expired" to expired, "periodic.effects" to periodic)) {
            validateBindings(body, emptySet(), field)
            validateContexts(body, field != "expired", true, field)
        }
        val buffs = m.listOrEmpty("buffs", "$")
        if (buffs.size > 16) bad("buffs", "at most 16 membership buffs are supported")
        val references = buffs.mapIndexed { index, value -> reference(value, pack, "buffs[$index]") }
        if (references.distinct().size != references.size) bad("buffs", "duplicate membership buff")
        return AreaDef(id, shape, duration, sample, selector, enter, periodic, interval, exit, expired, references)
    }

    private fun parseStatus(m: Map<String, Any?>, id: String, pack: Pack): StatusDef {
        m.only(setOf("kind", "id", "duration", "reapply", "stacks", "periodic", "applied", "refreshed", "stacks_changed", "expired", "modifiers", "tags", "restrictions", "control_categories", "immunities"), "$")
        val duration = ticks(m.string("duration", "$"), "duration")
        if (duration <= 0) bad("duration", "status lifetime must be positive")
        if (m["reapply"] != null && m.string("reapply", "$") != "refresh") bad("reapply", "supported reapplication policy is refresh")
        val stacks = m["stacks"]?.asMap("stacks", "stacks")?.let {
            it.only(setOf("max", "duration"), "stacks")
            val maximum = it.integer("max", "stacks")
            if (maximum !in 1..64) bad("stacks.max", "stack cap must be 1..64")
            val mode = when (it["duration"] ?: "shared") {
                "shared" -> StackDuration.SHARED
                "per_stack" -> StackDuration.PER_STACK
                else -> bad("stacks.duration", "supported stack duration modes are shared and per_stack")
            }
            StatusStacks(maximum, mode)
        }
        var interval = 0
        val periodic = m["periodic"]?.asMap("periodic", "periodic")?.let {
            it.only(setOf("every", "effects"), "periodic")
            interval = ticks(it.string("every", "periodic"), "periodic.every")
            if (interval <= 0) bad("periodic.every", "periodic interval must be positive")
            nestedEffects(it, "effects", "periodic", pack, 0)
        }.orEmpty()
        val callbacks = listOf("applied", "refreshed", "stacks_changed", "expired").associateWith { nestedEffects(m, it, "$", pack, 0, false) }
        for ((field, body) in callbacks + ("periodic.effects" to periodic)) {
            validateBindings(body, setOf("status.stacks"), field)
            validateContexts(body, true, false, field)
        }
        val modifiers = m.listOrEmpty("modifiers", "$")
        if (modifiers.size > 1) bad("modifiers", "one movement speed modifier is supported")
        val speed = modifiers.firstOrNull()?.asMap("modifiers[0]", "modifiers[0]")?.let {
            val field = "modifiers[0]"
            it.only(setOf("type", "attribute", "amount", "stacking", "cap"), field)
            if (it.string("type", field) != "attribute" || it.string("attribute", field) != "minecraft:movement_speed") bad(field, "supported modifier is the minecraft:movement_speed attribute")
            val amount = boundedNumber(it, "amount", field, 0.0, 1.0)
            val combination = when (it["stacking"] ?: "strongest") {
                "strongest" -> BonusCombination.STRONGEST
                "capped_add" -> BonusCombination.CAPPED_ADD
                else -> bad("$field.stacking", "supported policies are strongest and capped_add")
            }
            val cap = if (combination == BonusCombination.CAPPED_ADD) boundedNumber(it, "cap", field, 0.0, 1.0) else null
            if (combination == BonusCombination.STRONGEST && "cap" in it) bad("$field.cap", "a cap requires capped_add")
            if (cap != null && amount > cap) bad("$field.amount", "bonus exceeds its addition cap")
            SpeedBonus(amount, combination, cap)
        }
        val restrictions = m.listOrEmpty("restrictions", "$")
        if (restrictions.size > 1) bad("restrictions", "only one activation restriction is supported")
        val actions = restrictions.mapIndexed { index, value ->
            if (value.asString("restrictions[$index]") != "activate") bad("restrictions[$index]", "supported restriction is activate")
            ActionRestriction.ACTIVATE
        }.toSet()
        val controlCategories = statusLabels(m, "control_categories", pack, "$")
        val immunities = statusLabels(m, "immunities", pack, "$")
        if (controlCategories.any { it in immunities }) bad("immunities", "a status cannot be immune to its own control category")
        return StatusDef(id, duration, stacks, interval, periodic, callbacks.getValue("applied"), callbacks.getValue("refreshed"), callbacks.getValue("stacks_changed"), callbacks.getValue("expired"), speed, statusTags(m, pack, "$"), actions, controlCategories, immunities)
    }

    private fun validateContexts(effects: List<Effect>, entity: Boolean, position: Boolean, field: String) {
        for ((index, effect) in effects.withIndex()) {
            val mechanic = catalog.mechanic(effect)
            val location = "$field[$index]"
            if (mechanic.needsEntityTarget(effect) && !entity) bad(location, "entity target is not available in this context")
            if (mechanic.needsTarget(effect) && !entity && !position) bad(location, "target is not available in this context")
            if (mechanic.needsGround(effect) && !position) bad(location, "ground position is not available in this context")
            for (body in mechanic.nested(effect)) validateContexts(body, entity || mechanic.providesTarget, position, "$location.effects")
        }
    }

    private fun validateBindings(effects: List<Effect>, incoming: Set<String>, field: String): Set<String> {
        val available = incoming.toMutableSet()
        for ((index, effect) in effects.withIndex()) {
            val location = "$field[$index]"
            val mechanic = catalog.mechanic(effect)
            for ((input, numeric) in mechanic.inputs(effect)) checkNumeric(numeric, available, "$location.$input")
            when (effect) {
                is Effect.Branch -> {
                    val whenTrue = validateBindings(effect.onTrue, available, "$location.then")
                    val whenFalse = validateBindings(effect.onFalse, available, "$location.else")
                    available += whenTrue.intersect(whenFalse)
                }
                is Effect.Choose -> {
                    val common = effect.options.mapIndexed { option, branch -> validateBindings(branch.effects, available, "$location.options[$option].effects") }
                        .reduce(Set<String>::intersect)
                    available += common
                }
                is Effect.Sequence -> available += validateBindings(effect.effects, available, "$location.effects")
                else -> for (body in mechanic.nested(effect)) validateBindings(body, available + mechanic.localBindings, "$location.effects")
            }
            effect.resultName?.let { name ->
                if (available.any { it.startsWith("result.$name.") }) bad("$location.as", "duplicate result name")
                available += mechanic.resultFields.map { "result.$name.$it" }
            }
        }
        return available
    }

    private fun checkNumeric(numeric: Numeric, available: Set<String>, field: String) {
        if (numeric !is Numeric.Expression) return
        for (read in Expression.variables(numeric.source)) if (read !in available) bad(field, "$read is not available here")
    }

    private fun parseEffect(m: Map<String, Any?>, field: String, pack: Pack, depth: Int): Effect {
        if (depth > 8) bad(field, "effect nesting exceeds 8 levels")
        val type = m.string("type", field)
        val mechanic = catalog.effects[if (':' in type) type else "archetype:$type"] ?: bad("$field.type", "unsupported effect type $type")
        // ASVS 2.2.1-2.2.3: registration metadata defines the accepted fields before typed decoding.
        m.only(mechanic.fields.keys + setOf("type", "id") + if (mechanic.resultFields.isNotEmpty()) setOf("as") else emptySet(), field)
        for (required in mechanic.required) if (required !in m) bad("$field.$required", "required field is missing")
        val name = m["as"]?.asString("$field.as")
        val identity = m["id"]?.asString("$field.id") ?: field
        if ("id" in m && (identity.length > 128 || !LOCAL_ID.matches(identity))) bad("$field.id", "invalid effect identity")
        if (name != null && !Regex("[a-z0-9_]{1,64}").matches(name)) bad("$field.as", "result name must use lowercase letters, digits or underscores")
        return mechanic.decode(object : EffectReader {
            override val resultName = name
            override val identity = identity
            override fun has(key: String) = key in m
            override fun text(key: String) = m.string(key, field)
            override fun localId(key: String): String {
                val value = text(key)
                if (value.length > 64 || !LOCAL_ID.matches(value)) bad("$field.$key", "expected a local name of at most 64 characters")
                return value
            }
            override fun grant(key: String): String {
                val value = text(key)
                if (value.length > 128 || !LOCAL_ID.matches(value)) bad("$field.$key", "expected a local grant name of at most 128 characters")
                return value
            }
            override fun option(key: String, choices: Set<String>, default: String): String {
                val value = if (key in m) text(key) else default
                if (value !in choices) bad("$field.$key", "supported values are ${choices.joinToString()}")
                return value
            }
            override fun integer(key: String, min: Int, max: Int, default: Int?): Int {
                val value = if (key !in m && default != null) default else m.integer(key, field)
                if (value !in min..max) bad("$field.$key", "must be $min..$max")
                return value
            }
            override fun number(key: String, min: Double, max: Double, default: Double?) = boundedNumber(m, key, field, min, max, default)
            override fun boolean(key: String, default: Boolean) = booleanValue(m, key, field, default)
            override fun numeric(key: String) = this@ManifestCompiler.numeric(m[key], "$field.$key")
            override fun duration(key: String, positive: Boolean, default: Int?): Int {
                val value = if (key !in m && default != null) default else ticks(text(key), "$field.$key")
                if (positive && value <= 0) bad("$field.$key", "interval must be positive")
                return value
            }
            override fun reference(key: String): String {
                return reference(m[key], pack, "$field.$key")
            }
            override fun target(key: String) = parseTarget(text(key), "$field.$key")
            override fun spatialTarget(key: String, default: SpatialTarget) = if (key !in m) default else parseSpatial(text(key), "$field.$key")
            override fun effects(key: String, required: Boolean) = nestedEffects(m, key, field, pack, depth, required)
            override fun choices(key: String): List<WeightedBranch> {
                val values = m.listOrEmpty(key, field)
                if (values.size !in 1..16) bad("$field.$key", "choice list must contain 1..16 branches")
                val branches = values.mapIndexed { index, value ->
                    val path = "$field.$key[$index]"
                    val option = value.asMap(path, path)
                    option.only(setOf("weight", "effects"), path)
                    val weight = option.integer("weight", path)
                    if (weight !in 1..1000) bad("$path.weight", "weight must be 1..1000")
                    WeightedBranch(weight, nestedEffects(option, "effects", path, pack, depth))
                }
                if (branches.sumOf(WeightedBranch::weight) > 10_000) bad("$field.$key", "total choice weight exceeds 10000")
                return branches
            }
            override fun condition(key: String) = parseCondition(m[key].asMap("$field.$key", "$field.$key"), "$field.$key", pack)
            override fun statusFilter() = parseStatusFilter(m, pack, field)
            override fun selector(key: String, shape: Shape?) = parseSelector(m[key].asMap("$field.$key", "$field.$key"), "$field.$key", shape)
            override fun anchor(key: String): Anchor {
                val anchor = m[key].asMap("$field.$key", "$field.$key")
                anchor.only(setOf("position", "attached"), "$field.$key")
                if (("position" in anchor) == ("attached" in anchor)) bad("$field.$key", "anchor needs exactly one of position or attached")
                return if ("position" in anchor) Anchor.Fixed(parseSpatial(anchor.string("position", "$field.$key"), "$field.$key.position"))
                else Anchor.Attached(parseTarget(anchor.string("attached", "$field.$key"), "$field.$key.attached"))
            }
        })
    }

    private fun parseTarget(text: String, field: String): EffectTarget = when (text) {
        "actor" -> EffectTarget.ACTOR
        "target" -> EffectTarget.TARGET
        else -> bad(field, "supported entity targets are actor and target")
    }

    private fun reference(value: Any?, pack: Pack, field: String): String {
        val text = if (value is Map<*, *>) {
            val mapping = value.asMap(field, field)
            mapping.only(setOf("ref"), field)
            mapping.string("ref", field)
        } else value.asString(field)
        return qualify(text, pack.id, pack, field)
    }

    private fun parseSpatial(text: String, field: String): SpatialTarget = when (text) {
        "actor" -> SpatialTarget.ACTOR
        "target" -> SpatialTarget.TARGET
        "ground" -> SpatialTarget.GROUND
        else -> bad(field, "supported positions are actor, target and ground")
    }

    private fun boundedNumber(m: Map<String, Any?>, key: String, field: String, min: Double, max: Double, default: Double? = null): Double {
        val value = if (key !in m && default != null) default else m.number(key, field)
        if (value !in min..max) bad("$field.$key", "must be between $min and $max")
        return value
    }

    private fun booleanValue(m: Map<String, Any?>, key: String, field: String, default: Boolean): Boolean {
        if (key !in m) return default
        return m[key] as? Boolean ?: bad("$field.$key", "expected a Boolean")
    }

    private fun parseShape(m: Map<String, Any?>, field: String): Shape {
        fun size(key: String, default: Double? = null) = boundedNumber(m, key, field, 0.01, 32.0, default)
        val shape = when (m.string("type", field)) {
            "point" -> { m.only(setOf("type", "radius"), field); Shape.Point(size("radius", 0.25)) }
            "sphere" -> { m.only(setOf("type", "radius"), field); Shape.Sphere(size("radius")) }
            "cylinder" -> { m.only(setOf("type", "radius", "height"), field); Shape.Cylinder(size("radius"), size("height")) }
            "ring" -> {
                m.only(setOf("type", "inner_radius", "outer_radius", "height"), field)
                val inner = boundedNumber(m, "inner_radius", field, 0.0, 32.0)
                val outer = size("outer_radius")
                if (inner >= outer) bad("$field.inner_radius", "inner radius must be smaller than outer radius")
                Shape.Ring(inner, outer, size("height"))
            }
            "box" -> { m.only(setOf("type", "width", "height", "depth"), field); Shape.Box(size("width"), size("height"), size("depth")) }
            "segment", "beam" -> { m.only(setOf("type", "length", "radius"), field); Shape.Segment(size("length"), size("radius")) }
            "cone" -> { m.only(setOf("type", "range", "angle"), field); Shape.Cone(size("range"), boundedNumber(m, "angle", field, 0.01, 180.0)) }
            else -> bad("$field.type", "unsupported shape")
        }
        if (shape.bound > 32.0) bad(field, "shape must fit within a 32-block enclosing radius")
        return shape
    }

    private fun parseSelector(m: Map<String, Any?>, field: String, suppliedShape: Shape? = null): Selector {
        m.only(setOf("type", "limit", "filters", "include_actor", "order", "line_of_sight") + if (suppliedShape == null) setOf("shape") else emptySet(), field)
        if (m.string("type", field) != "living_entities") bad("$field.type", "supported selector type is living_entities")
        val shape = suppliedShape ?: parseShape(m["shape"].asMap("$field.shape", "$field.shape"), "$field.shape")
        val limit = if ("limit" in m) m.integer("limit", field) else 16
        if (limit !in 1..64) bad("$field.limit", "limit must be 1..64")
        var relation = Relation.ANY
        var minimumHealth = 0.0
        var maximumHealth = 1.0
        val seen = mutableSetOf<String>()
        val filters = m.listOrEmpty("filters", field)
        if (filters.size > 8) bad("$field.filters", "at most 8 filters are supported")
        for ((index, value) in filters.withIndex()) {
            val path = "$field.filters[$index]"
            val filter = value.asMap(path, path)
            val type = filter.string("type", path)
            if (!seen.add(type)) bad("$path.type", "duplicate filter type")
            when (type) {
                "relation" -> {
                    filter.only(setOf("type", "is"), path)
                    relation = when (filter.string("is", path)) {
                        "any" -> Relation.ANY
                        "ally" -> Relation.ALLY
                        "enemy" -> Relation.ENEMY
                        "self" -> Relation.SELF
                        else -> bad("$path.is", "unsupported relationship")
                    }
                }
                "health_fraction" -> {
                    filter.only(setOf("type", "min", "max"), path)
                    minimumHealth = boundedNumber(filter, "min", path, 0.0, 1.0, 0.0)
                    maximumHealth = boundedNumber(filter, "max", path, 0.0, 1.0, 1.0)
                    if (minimumHealth > maximumHealth) bad(path, "health minimum exceeds maximum")
                }
                else -> bad("$path.type", "unsupported selector filter")
            }
        }
        val order = when (m["order"] ?: "nearest") {
            "nearest" -> TargetOrder.NEAREST
            "lowest_health" -> TargetOrder.LOWEST_HEALTH
            "highest_health" -> TargetOrder.HIGHEST_HEALTH
            else -> bad("$field.order", "unsupported target order")
        }
        return Selector(shape, limit, relation, booleanValue(m, "line_of_sight", field, true), booleanValue(m, "include_actor", field, false), order, minimumHealth, maximumHealth)
    }

    private fun nestedEffects(
        m: Map<String, Any?>, key: String, field: String, pack: Pack, depth: Int, required: Boolean = true,
    ): List<Effect> {
        val effects = m.listOrEmpty(key, field)
        if (required && effects.isEmpty()) bad("$field.$key", "effect list must not be empty")
        if (effects.size > 64) bad("$field.$key", "effect list exceeds 64 steps")
        return effects.mapIndexed { index, value ->
            val childField = "$field.$key[$index]"
            parseEffect(value.asMap(childField, childField), childField, pack, depth + 1)
        }
    }

    private fun parseCondition(m: Map<String, Any?>, field: String, pack: Pack, depth: Int = 0, nodes: IntArray = intArrayOf(0)): Condition {
        // ASVS 2.2.1, 2.3.2: finite Boolean trees cannot multiply evaluation work without bound.
        if (depth > 8 || ++nodes[0] > 128) bad(field, "condition tree exceeds its depth or node limit")
        fun children(key: String): List<Condition> {
            m.only(setOf(key), field)
            val values = m.listOrEmpty(key, field)
            if (values.size !in 1..16) bad("$field.$key", "condition list must contain 1..16 entries")
            return values.mapIndexed { index, value ->
                val path = "$field.$key[$index]"
                parseCondition(value.asMap(path, path), path, pack, depth + 1, nodes)
            }
        }
        return when {
            "all" in m -> Condition.All(children("all"))
            "any" in m -> Condition.Any(children("any"))
            "not" in m -> {
                m.only(setOf("not"), field)
                Condition.Not(parseCondition(m["not"].asMap("$field.not", "$field.not"), "$field.not", pack, depth + 1, nodes))
            }
            else -> when (m.string("type", field)) {
                "has_status" -> {
                    m.only(setOf("type", "target", "status", "tags", "source"), field)
                    Condition.HasStatus(parseTarget(m.string("target", field), "$field.target"), parseStatusFilter(m, pack, field))
                }
                "resource_at_least" -> {
                    m.only(setOf("type", "resource", "amount"), field)
                    Condition.ResourceAtLeast(qualify(m.string("resource", field), pack.id, pack, "$field.resource"), numeric(m["amount"], "$field.amount"))
                }
                "compare" -> {
                    m.only(setOf("type", "left", "op", "right"), field)
                    val operator = m.string("op", field)
                    if (operator !in setOf("lt", "lte", "eq", "gte", "gt")) bad("$field.op", "unsupported comparison")
                    Condition.Compare(numeric(m["left"], "$field.left"), operator, numeric(m["right"], "$field.right"))
                }
                "chance" -> {
                    m.only(setOf("type", "probability"), field)
                    Condition.Chance(boundedNumber(m, "probability", field, 0.0, 1.0))
                }
                else -> bad("$field.type", "unsupported condition type")
            }
        }
    }

    private fun statusLabels(m: Map<String, Any?>, name: String, pack: Pack, field: String): Set<String> {
        if (name !in m) return emptySet()
        val location = if (field == "$") name else "$field.$name"
        val raw = m.listOrEmpty(name, field)
        // ASVS 2.2.1, 2.2.3: bounded labels share namespace validation and reject normalized duplicates.
        if (raw.size !in 1..16) bad(location, "$name must contain 1..16 distinct namespaced or local labels")
        val tags = raw.mapIndexed { index, value -> qualify(value.asString("$location[$index]"), pack.id, pack, "$location[$index]") }
        if (tags.distinct().size != tags.size) bad(location, "duplicate $name label")
        return tags.toSet()
    }

    private fun statusTags(m: Map<String, Any?>, pack: Pack, field: String) = statusLabels(m, "tags", pack, field)

    private fun parseStatusFilter(m: Map<String, Any?>, pack: Pack, field: String): StatusFilter {
        val source = when (if ("source" in m) m["source"] else "any") {
            "any" -> StatusSource.ANY
            "actor" -> StatusSource.ACTOR
            "grant" -> StatusSource.GRANT
            else -> bad("$field.source", "supported status sources are any, actor and grant")
        }
        return StatusFilter(if ("status" in m) reference(m["status"], pack, "$field.status") else null, statusTags(m, pack, field), source)
    }

    private fun parseClass(m: Map<String, Any?>, id: String, pack: Pack, abilities: Map<String, AbilityDef>): ClassDef {
        m.only(setOf("kind", "id", "name", "abilities"), "$")
        val grants = linkedMapOf<String, Grant>()
        val raw = m["abilities"].asMap("abilities", "abilities")
        if (raw.size > 128) bad("abilities", "at most 128 grants are supported per class")
        for ((name, value) in raw) {
            if (name.length > 128 || !LOCAL_ID.matches(name)) bad("abilities.$name", "invalid grant name")
            val field = "abilities.$name"
            val grant = value.asMap(field, field)
            grant.only(setOf("ref", "definition", "slot"), field)
            val hasRef = "ref" in grant
            val hasDefinition = "definition" in grant
            if (hasRef == hasDefinition) bad(field, "grant needs exactly one of ref or definition")
            val ability = if (hasRef) {
                val ref = qualify(grant.string("ref", field), pack.id, pack, "$field.ref")
                abilities[ref] ?: bad("$field.ref", "unknown ability $ref")
            } else {
                parseAbility(grant["definition"].asMap("$field.definition", "$field.definition"), "$id/$name", false, pack)
            }
            val slot = grant["slot"]?.asString("$field.slot")
            if (slot != null && (slot.length > 64 || !LOCAL_ID.matches(slot))) bad("$field.slot", "invalid slot name")
            grants[name] = Grant(name, slot, ability)
        }
        return ClassDef(id, m.string("name", "$"), grants)
    }

    private fun qualify(value: String, owner: String, pack: Pack, field: String): String {
        val id = if (':' in value) value else "$owner:$value"
        if (id.length > 128 || !GLOBAL_ID.matches(id)) bad(field, "invalid namespaced ID")
        if (id.substringBefore(':') !in pack.dependencies + pack.id) bad(field, "cross-pack reference needs a declared dependency")
        return id
    }

    private fun numeric(value: Any?, field: String): Numeric = when (value) {
        is Number -> Numeric.Constant(value.toDouble().also { if (!it.isFinite() || it !in 0.0..1_000_000.0) bad(field, "number must be finite and at most 1000000") })
        is Map<*, *> -> {
            val expression = value.asMap(field, field)
            expression.only(setOf("expr"), field)
            val source = expression.string("expr", field)
            try { Expression.evaluate(source, emptyMap(), validateOnly = true) }
            catch (failure: IllegalArgumentException) { bad(field, failure.message ?: "invalid expression") }
            Numeric.Expression(source)
        }
        else -> bad(field, "expected a number or {expr: ...}")
    }

    private fun ticks(text: String, field: String): Int {
        val match = DURATION.matchEntire(text) ?: bad(field, "expected a duration such as 250ms, 2s, or 1m")
        val amount = match.groupValues[1].toDoubleOrNull() ?: bad(field, "invalid duration")
        val milliseconds = amount * when (match.groupValues[2]) { "ms" -> 1.0; "s" -> 1000.0; else -> 60000.0 }
        if (!milliseconds.isFinite() || milliseconds < 0 || milliseconds > 3_600_000) bad(field, "duration must be between 0 and 1h")
        return kotlin.math.ceil(milliseconds / 50.0).toInt()
    }

    private data class Doc(val file: String, val data: Map<String, Any?>)
    private companion object {
        val PACK_ID = Regex("[a-z0-9_.-]+")
        val LOCAL_ID = Regex("[a-z0-9_./-]+")
        val GLOBAL_ID = Regex("[a-z0-9_.-]+:[a-z0-9_./-]+")
        val DURATION = Regex("(0|[0-9]+(?:\\.[0-9]+)?)(ms|s|m)")
    }
}

private class InvalidManifest(val field: String, message: String) : RuntimeException(message)
private fun bad(field: String, problem: String): Nothing = throw InvalidManifest(field, problem)
private fun Any?.asMap(name: String, field: String): Map<String, Any?> {
    val source = this as? Map<*, *> ?: bad(field, "$name must be a mapping")
    if (source.keys.any { it !is String }) bad(field, "mapping keys must be strings")
    @Suppress("UNCHECKED_CAST")
    return source as Map<String, Any?>
}
private fun Any?.asString(field: String): String = (this as? String)?.takeIf { it.isNotBlank() } ?: bad(field, "expected a nonempty string")
private fun Map<String, Any?>.string(key: String, field: String): String = this[key].asString(if (field == "$") key else "$field.$key")
private fun Map<String, Any?>.integer(key: String, field: String): Int {
    val value = this[key]
    if (value !is Int && value !is Long) bad(if (field == "$") key else "$field.$key", "expected an integer")
    val long = (value as Number).toLong()
    if (long !in Int.MIN_VALUE..Int.MAX_VALUE) bad(if (field == "$") key else "$field.$key", "integer is out of range")
    return long.toInt()
}
private fun Map<String, Any?>.number(key: String, field: String): Double {
    val number = (this[key] as? Number)?.toDouble() ?: bad(if (field == "$") key else "$field.$key", "expected a number")
    if (!number.isFinite()) bad(if (field == "$") key else "$field.$key", "number must be finite")
    return number
}
private fun Map<String, Any?>.listOrEmpty(key: String, field: String): List<Any?> {
    if (key !in this) return emptyList()
    val value = this[key]
    return value as? List<*> ?: bad(if (field == "$") key else "$field.$key", "expected a list")
}
private fun Map<String, Any?>.only(fields: Set<String>, field: String) {
    val unknown = keys.firstOrNull { it !in fields }
    if (unknown != null) bad(if (field == "$") unknown else "$field.$unknown", "unknown field")
}
