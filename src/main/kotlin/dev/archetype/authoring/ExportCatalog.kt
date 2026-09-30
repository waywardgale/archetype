package dev.archetype.authoring

import com.google.gson.GsonBuilder
import dev.archetype.definitions.*
import java.nio.file.Files
import java.nio.file.Path

/** Structural editor assistance; the shared compiler remains responsible for binding and world checks. */
object CatalogExport {
    private fun ref(name: String) = mapOf("\$ref" to "#/\$defs/$name")
    private fun text(pattern: String? = null): Map<String, Any> = mapOf<String, Any>("type" to "string", "minLength" to 1) + if (pattern == null) emptyMap() else mapOf("pattern" to pattern)
    private fun number(min: Double, max: Double) = mapOf("type" to "number", "minimum" to min, "maximum" to max)
    private fun integer(min: Int, max: Int) = mapOf("type" to "integer", "minimum" to min, "maximum" to max)
    private fun enum(vararg choices: String) = mapOf("enum" to choices.toList())
    private fun obj(properties: Map<String, Any>, required: List<String> = properties.keys.toList()) = mapOf(
        "type" to "object", "properties" to properties, "required" to required, "additionalProperties" to false,
    )
    private fun array(items: Any, max: Int, min: Int = 0) = mapOf("type" to "array", "items" to items, "minItems" to min, "maxItems" to max)
    private val duration = text("^(0|[0-9]+(?:\\.[0-9]+)?)(ms|s|m)$")
    private val localId = text("^[a-z0-9_./-]+$")
    private val packId = text("^[a-z0-9_.-]+$")
    private val reference = mapOf("oneOf" to listOf(text(), obj(mapOf("ref" to text()))))

    fun schema(catalog: MechanicCatalog = BuiltinEffects.catalog): Map<String, Any> {
        val effects = catalog.effects.values.map { mechanic ->
            val type = if (mechanic.type.startsWith("archetype:")) enum(mechanic.type, mechanic.type.removePrefix("archetype:")) else mapOf("const" to mechanic.type)
            val fields = mechanic.fields + mapOf("type" to type, "id" to localId) +
                if (mechanic.resultFields.isEmpty()) emptyMap() else mapOf("as" to text("^[a-z0-9_]{1,64}$"))
            obj(fields, listOf("type") + mechanic.required) + mapOf("description" to mechanic.lifecycle)
        }
        val conditions = listOf(
            obj(mapOf("type" to mapOf("const" to "compare"), "left" to ref("numeric"), "op" to enum("lt", "lte", "eq", "gte", "gt"), "right" to ref("numeric"))),
            obj(mapOf("type" to mapOf("const" to "resource_at_least"), "resource" to text(), "amount" to ref("numeric"))),
            obj(mapOf("type" to mapOf("const" to "chance"), "probability" to number(0.0, 1.0))),
            obj(BuiltinEffects.statusFilterFields + mapOf("type" to mapOf("const" to "has_status"), "target" to enum("actor", "target")), listOf("type", "target")),
            obj(mapOf("all" to array(ref("condition"), 16, 1))),
            obj(mapOf("any" to array(ref("condition"), 16, 1))),
            obj(mapOf("not" to ref("condition"))),
        )
        val sizes = number(0.01, 32.0)
        fun shape(type: String, fields: Map<String, Any>, optional: Set<String> = emptySet()) = obj(fields + ("type" to mapOf("const" to type)), listOf("type") + (fields.keys - optional))
        val shapes = listOf(
            shape("point", mapOf("radius" to sizes), setOf("radius")),
            shape("sphere", mapOf("radius" to sizes)),
            shape("cylinder", mapOf("radius" to sizes, "height" to sizes)),
            shape("ring", mapOf("inner_radius" to number(0.0, 32.0), "outer_radius" to sizes, "height" to sizes)),
            shape("box", mapOf("width" to sizes, "height" to sizes, "depth" to sizes)),
            shape("segment", mapOf("length" to sizes, "radius" to sizes)),
            shape("beam", mapOf("length" to sizes, "radius" to sizes)),
            shape("cone", mapOf("range" to sizes, "angle" to number(0.01, 180.0))),
        )
        val selectorFields = mapOf(
            "type" to mapOf("const" to "living_entities"),
            "limit" to mapOf("type" to "integer", "minimum" to 1, "maximum" to 64, "default" to 16),
            "include_actor" to mapOf("type" to "boolean", "default" to false),
            "line_of_sight" to mapOf("type" to "boolean", "default" to true),
            "order" to enum("nearest", "lowest_health", "highest_health"),
            "filters" to array(mapOf("oneOf" to listOf(
                obj(mapOf("type" to mapOf("const" to "relation"), "is" to enum("any", "ally", "enemy", "self"))),
                obj(mapOf("type" to mapOf("const" to "health_fraction"), "min" to number(0.0, 1.0), "max" to number(0.0, 1.0)), listOf("type")),
            )), 8),
        )
        val abilityFields = mapOf(
            "name" to text(), "description" to text(), "icon" to text(),
            "activation" to obj(mapOf("type" to mapOf("const" to "activated"))),
            "target" to obj(mapOf("type" to enum("entity", "ground"), "range" to sizes), listOf("type")),
            "cooldown" to mapOf("oneOf" to listOf(duration,
                obj(mapOf("duration" to duration, "groups" to array(text("^(?:[a-z0-9_.-]+:)?[a-z0-9_./-]+$"), 8), "global" to duration), listOf("duration")))),
            "charges" to obj(mapOf("max" to integer(1, 16), "recharge" to duration, "mode" to enum("sequential", "parallel")), listOf("max", "recharge")),
            "costs" to array(obj(mapOf("resource" to text(), "amount" to ref("numeric"))), 128),
            "effects" to array(ref("effect"), 64, 1),
        )
        val definitions = linkedMapOf<String, Any>(
            "numeric" to mapOf("oneOf" to listOf(
                number(0.0, 1_000_000.0),
                obj(mapOf("expr" to (text() + mapOf("maxLength" to 256)))),
            )),
            "reference" to reference,
            "status_tags" to (array(text("^(?:[a-z0-9_.-]+:)?[a-z0-9_./-]+$") + mapOf("maxLength" to 128), 16, 1) + mapOf("uniqueItems" to true)),
            "effect" to mapOf("oneOf" to effects),
            "condition" to mapOf("oneOf" to conditions),
            "shape" to mapOf("oneOf" to shapes),
            "selector" to obj(selectorFields, listOf("type")),
            "shaped_selector" to obj(selectorFields + ("shape" to ref("shape")), listOf("type", "shape")),
            "anchor" to mapOf("oneOf" to listOf(obj(mapOf("position" to enum("actor", "target", "ground"))), obj(mapOf("attached" to enum("actor", "target"))))),
            "inline_ability" to obj(abilityFields, listOf("name", "effects")),
            "pack" to obj(mapOf("format" to mapOf("const" to 1), "id" to packId, "name" to text(), "dependencies" to array(packId, 128)), listOf("format", "id", "name")),
            "ability" to obj(abilityFields + mapOf("kind" to mapOf("const" to "ability"), "id" to text()), listOf("kind", "id", "name", "effects")),
            "resource" to obj(mapOf(
                "kind" to mapOf("const" to "resource"), "id" to text(), "scope" to enum("class", "player"),
                "min" to number(-1_000_000_000.0, 1_000_000_000.0), "max" to number(-1_000_000_000.0, 1_000_000_000.0), "initial" to number(-1_000_000_000.0, 1_000_000_000.0),
                "regeneration" to obj(mapOf("amount" to mapOf("type" to "number", "exclusiveMinimum" to 0, "maximum" to 1_000_000), "every" to duration)),
            ), listOf("kind", "id", "scope", "min", "max", "initial")),
            "area" to obj(mapOf(
                "kind" to mapOf("const" to "area"), "id" to text(), "shape" to ref("shape"), "duration" to duration,
                "sample_every" to duration, "targets" to ref("selector"),
                "enter" to array(ref("effect"), 64), "exit" to array(ref("effect"), 64), "expired" to array(ref("effect"), 64), "buffs" to array(ref("reference"), 16),
                "periodic" to obj(mapOf("every" to duration, "effects" to array(ref("effect"), 64, 1))),
            ), listOf("kind", "id", "shape", "duration", "targets")),
            "status" to obj(mapOf(
                "kind" to mapOf("const" to "status"), "id" to text(), "duration" to duration, "reapply" to mapOf("const" to "refresh"),
                "tags" to ref("status_tags"),
                "control_categories" to ref("status_tags"),
                "immunities" to ref("status_tags"),
                "restrictions" to array(enum("activate"), 1),
                "stacks" to obj(mapOf("max" to integer(1, 64), "duration" to enum("shared", "per_stack")), listOf("max")),
                "applied" to array(ref("effect"), 64), "refreshed" to array(ref("effect"), 64), "stacks_changed" to array(ref("effect"), 64), "expired" to array(ref("effect"), 64),
                "periodic" to obj(mapOf("every" to duration, "effects" to array(ref("effect"), 64, 1))),
                "modifiers" to array(mapOf("oneOf" to listOf(
                    obj(mapOf("type" to mapOf("const" to "attribute"), "attribute" to mapOf("const" to "minecraft:movement_speed"), "amount" to number(0.0, 1.0), "stacking" to mapOf("const" to "strongest")), listOf("type", "attribute", "amount")),
                    obj(mapOf("type" to mapOf("const" to "attribute"), "attribute" to mapOf("const" to "minecraft:movement_speed"), "amount" to number(0.0, 1.0), "stacking" to mapOf("const" to "capped_add"), "cap" to number(0.0, 1.0))),
                )), 1),
            ), listOf("kind", "id", "duration")),
            "class" to obj(mapOf(
                "kind" to mapOf("const" to "class"), "id" to text(), "name" to text(),
                "abilities" to mapOf("type" to "object", "maxProperties" to 128, "propertyNames" to (localId + mapOf("maxLength" to 128)),
                    "additionalProperties" to mapOf("oneOf" to listOf(
                        obj(mapOf("ref" to text(), "slot" to (localId + mapOf("maxLength" to 64))), listOf("ref")),
                        obj(mapOf("definition" to ref("inline_ability"), "slot" to (localId + mapOf("maxLength" to 64))), listOf("definition")),
                    ))),
            )),
        )
        return mapOf(
            "\$schema" to "https://json-schema.org/draft/2020-12/schema",
            "title" to "Archetype supported manifest grammar",
            "description" to "Structural editor schema. Run validatePacks for result availability, references, positive intervals, related bounds and recursion. Native registry checks require the server.",
            "\$defs" to definitions,
            "oneOf" to listOf("pack", "ability", "resource", "area", "status", "class").map(::ref),
        )
    }

    fun reference(catalog: MechanicCatalog = BuiltinEffects.catalog): String = buildString {
        append("# Supported effect catalog\n\nGenerated by `./gradlew exportCatalog` from the registrations used by the compiler and runtime.\n\n")
        append("This is the currently implemented catalog. The accepted v1 catalog also requires the remaining mechanics in ability-coverage.md.\n\n")
        append("All effects accept an optional stable `id`. Effects with results accept `as`; fields are read through `result.<name>.<field>`. Nested invocation bindings stay local.\n\n")
        for (mechanic in catalog.effects.values) {
            append("## ${mechanic.type}\n\n${mechanic.lifecycle}\n\n")
            append("| Field | Required | Structure |\n| --- | --- | --- |\n")
            for ((field, schema) in mechanic.fields) append("| `$field` | ${if (field in mechanic.required) "yes" else "no"} | `${GsonBuilder().create().toJson(schema)}` |\n")
            if (mechanic.resultFields.isNotEmpty()) append("\nResults: ${mechanic.resultFields.joinToString { "`$it`" }}. Target loss supplies no result and skips dependent work.\n")
            if (mechanic.localBindings.isNotEmpty()) append("\nInvocation reads: ${mechanic.localBindings.joinToString { "`$it`" }}. Indices start at zero; chain hit count records preceding selected hits.\n")
            append("\n")
        }
    }
}

fun main(args: Array<String>) {
    require(args.size == 1) { "Usage: exportCatalog <output-directory>" }
    val output = Path.of(args[0])
    Files.createDirectories(output)
    Files.writeString(output.resolve("manifest.schema.json"), GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create().toJson(CatalogExport.schema()) + "\n")
    Files.writeString(output.resolve("effects.md"), CatalogExport.reference())
    println("Exported supported editor schema and effect reference to $output")
}
