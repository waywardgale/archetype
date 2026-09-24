package dev.archetype.definitions

import org.snakeyaml.engine.v2.api.Load
import org.snakeyaml.engine.v2.api.LoadSettings
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

/** Compiles only operations backed by runtime handlers. No accepted YAML is stored as an untyped map. */
class ManifestCompiler {
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
        for ((id, ability) in abilities + classes.values.flatMap { it.grants.values }.associate { it.ability.id to it.ability }) {
            val pack = packs[id.substringBefore(':')] ?: continue
            for (cost in ability.costs) {
                if (cost.resource !in resources) errors += Diagnostic(id, "costs", "unknown resource ${cost.resource}")
                else if (cost.resource.substringBefore(':') !in pack.dependencies + pack.id) errors += Diagnostic(id, "costs", "undeclared pack dependency")
            }
            for (effect in ability.effects.flatMap { it.descendants().toList() }) {
                val resource = when (effect) {
                    is Effect.GainResource -> effect.resource
                    is Effect.SpendResource -> effect.resource
                    else -> null
                }
                if (resource != null && resource !in resources) errors += Diagnostic(id, "effects", "unknown resource $resource")
                if (effect is Effect.Branch && effect.condition is Condition.ResourceAtLeast && effect.condition.resource !in resources) {
                    errors += Diagnostic(id, "effects", "unknown resource ${effect.condition.resource}")
                }
            }
        }
        if (errors.isNotEmpty()) return CompileResult.Invalid(errors)
        return CompileResult.Valid(DefinitionSet(packs, resources, abilities, classes, snapshot.fingerprint))
    }

    private fun parsePack(doc: Doc): Pack {
        val m = doc.data
        m.only(setOf("format", "id", "name", "dependencies"), "$")
        if (m.integer("format", "$") != 1) bad("format", "supported format is 1")
        val id = m.string("id", "$")
        if (id.length > 64 || !PACK_ID.matches(id)) bad("id", "invalid pack ID")
        val dependencies = m.listOrEmpty("dependencies", "$").mapIndexed { index, value ->
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
        val allowed = setOf("name", "description", "activation", "cooldown", "costs", "effects", "icon") + if (global) setOf("kind", "id") else emptySet()
        m.only(allowed, "$" )
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
        val cooldown = m["cooldown"]?.asString("cooldown")?.let { ticks(it, "cooldown") } ?: 0
        val costs = m.listOrEmpty("costs", "$").mapIndexed { index, value ->
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
        return AbilityDef(id, m.string("name", "$"), activation, cooldown, costs, effects)
    }

    private fun validateBindings(effects: List<Effect>, incoming: Set<String>, field: String): Set<String> {
        val available = incoming.toMutableSet()
        for ((index, effect) in effects.withIndex()) {
            val location = "$field[$index]"
            when (effect) {
                is Effect.Heal -> checkNumeric(effect.amount, available, "$location.amount")
                is Effect.Damage -> checkNumeric(effect.amount, available, "$location.amount")
                is Effect.GainResource -> checkNumeric(effect.amount, available, "$location.amount")
                is Effect.SpendResource -> checkNumeric(effect.amount, available, "$location.amount")
                is Effect.Delay -> validateBindings(effect.effects, available, "$location.effects")
                is Effect.Repeat -> validateBindings(effect.effects, available, "$location.effects")
                is Effect.Branch -> {
                    when (val condition = effect.condition) {
                        is Condition.ResourceAtLeast -> checkNumeric(condition.amount, available, "$location.when.amount")
                        is Condition.Compare -> {
                            checkNumeric(condition.left, available, "$location.when.left")
                            checkNumeric(condition.right, available, "$location.when.right")
                        }
                    }
                    val whenTrue = validateBindings(effect.onTrue, available, "$location.then")
                    val whenFalse = validateBindings(effect.onFalse, available, "$location.else")
                    available += whenTrue.intersect(whenFalse)
                }
            }
            effect.resultName?.let { name ->
                val fieldName = when (effect) { is Effect.Heal -> "health_restored"; is Effect.Damage -> "health_lost"; else -> "amount" }
                val key = "result.$name.$fieldName"
                if (!available.add(key)) bad("$location.as", "duplicate result name")
            }
        }
        return available
    }

    private fun checkNumeric(numeric: Numeric, available: Set<String>, field: String) {
        if (numeric !is Numeric.Expression) return
        val reads = Regex("result\\.[a-z0-9_./-]+\\.(?:health_lost|health_restored|amount)")
            .findAll(numeric.source).map { it.value }.toList()
        for (read in reads) if (read !in available) bad(field, "$read is not available here")
    }

    private fun parseEffect(m: Map<String, Any?>, field: String, pack: Pack, depth: Int): Effect {
        if (depth > 8) bad(field, "effect nesting exceeds 8 levels")
        val type = m.string("type", field)
        val resultName = m["as"]?.asString("$field.as")
        if (resultName != null && !LOCAL_ID.matches(resultName)) bad("$field.as", "invalid result name")
        return when (type) {
            "heal", "damage" -> {
                val allowed = setOf("type", "target", "amount", "as", "id") + if (type == "damage") setOf("damage_type") else emptySet()
                m.only(allowed, field)
                val target = when (m.string("target", field)) {
                    "actor" -> EffectTarget.ACTOR
                    "target" -> EffectTarget.TARGET
                    else -> bad("$field.target", "supported targets are actor and target")
                }
                val amount = numeric(m["amount"], "$field.amount")
                if (type == "heal") Effect.Heal(target, amount, resultName)
                else Effect.Damage(target, amount, m.string("damage_type", field), resultName)
            }
            "gain_resource", "spend_resource" -> {
                m.only(setOf("type", "resource", "amount", "as", "id"), field)
                val resource = qualify(m.string("resource", field), pack.id, pack, "$field.resource")
                val amount = numeric(m["amount"], "$field.amount")
                if (type == "gain_resource") Effect.GainResource(resource, amount, resultName)
                else Effect.SpendResource(resource, amount, resultName)
            }
            "delay" -> {
                m.only(setOf("type", "duration", "effects", "id"), field)
                val delay = ticks(m.string("duration", field), "$field.duration")
                if (delay == 0) bad("$field.duration", "delay must be positive")
                Effect.Delay(delay, nestedEffects(m, "effects", field, pack, depth))
            }
            "repeat" -> {
                m.only(setOf("type", "count", "every", "effects", "id"), field)
                val count = m.integer("count", field)
                if (count !in 1..64) bad("$field.count", "repeat count must be 1..64")
                val every = ticks(m.string("every", field), "$field.every")
                if (every == 0) bad("$field.every", "repeat interval must be positive")
                Effect.Repeat(count, every, nestedEffects(m, "effects", field, pack, depth))
            }
            "branch" -> {
                m.only(setOf("type", "when", "then", "else", "id"), field)
                val condition = parseCondition(m["when"].asMap("$field.when", "$field.when"), "$field.when", pack)
                Effect.Branch(condition, nestedEffects(m, "then", field, pack, depth), nestedEffects(m, "else", field, pack, depth, required = false))
            }
            else -> bad("$field.type", "unsupported effect type $type")
        }
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

    private fun parseCondition(m: Map<String, Any?>, field: String, pack: Pack): Condition = when (m.string("type", field)) {
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
        else -> bad("$field.type", "unsupported condition type")
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
    val value = this[key] ?: return emptyList()
    return value as? List<*> ?: bad(if (field == "$") key else "$field.$key", "expected a list")
}
private fun Map<String, Any?>.only(fields: Set<String>, field: String) {
    val unknown = keys.firstOrNull { it !in fields }
    if (unknown != null) bad(if (field == "$") unknown else "$field.$unknown", "unknown field")
}
