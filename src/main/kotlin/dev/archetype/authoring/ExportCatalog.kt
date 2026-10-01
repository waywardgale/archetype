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
            obj(BuiltinEffects.statusFilterFields + mapOf("type" to mapOf("const" to "has_status"), "target" to enum("actor", "target", "event.target")), listOf("type", "target")),
            obj(mapOf("type" to mapOf("const" to "state_is"), "state" to ref("reference"), "field" to localId,
                "value" to mapOf("oneOf" to listOf(number(-1_000_000_000.0, 1_000_000_000.0), mapOf("type" to "boolean"), localId)))),
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
            "order" to enum("nearest", "farthest", "lowest_health", "highest_health", "random"),
            "filters" to array(mapOf("oneOf" to listOf(
                obj(mapOf("type" to mapOf("const" to "relation"), "is" to enum("any", "ally", "enemy", "self"))),
                obj(mapOf("type" to mapOf("const" to "health_fraction"), "min" to number(0.0, 1.0), "max" to number(0.0, 1.0)), listOf("type")),
                obj(mapOf("type" to mapOf("const" to "facing_origin"), "max_degrees" to number(0.0, 180.0)), listOf("type")),
                obj(mapOf("type" to mapOf("const" to "entity_type"), "id" to text("^[a-z0-9_.-]+:[a-z0-9_./-]+$"))),
            )), 8),
        )
        val grantMap = mapOf("type" to "object", "maxProperties" to 128, "propertyNames" to (localId + mapOf("maxLength" to 128)),
            "additionalProperties" to mapOf("oneOf" to listOf(
                obj(mapOf("ref" to text(), "slot" to (localId + mapOf("maxLength" to 64))), listOf("ref")),
                obj(mapOf("definition" to ref("inline_ability"), "slot" to (localId + mapOf("maxLength" to 64))), listOf("definition")),
            )))
        val abilityFields = mapOf(
            "name" to text(), "description" to text(), "icon" to text(),
            "activation" to obj(mapOf("type" to enum("activated", "passive", "toggle", "channel", "charge", "confirm", "recast"),
                "every" to duration, "max_duration" to duration,
                "min_hold" to duration, "max_hold" to duration, "window" to duration,
                "periodic_costs" to mapOf("type" to "array", "maxItems" to 128, "items" to obj(mapOf("resource" to ref("reference"), "amount" to ref("numeric")), listOf("resource", "amount")))), listOf("type")),
            "target" to obj(mapOf("type" to enum("entity", "ground"), "range" to sizes), listOf("type")),
            "cooldown" to mapOf("oneOf" to listOf(duration,
                obj(mapOf("duration" to duration, "groups" to array(text("^(?:[a-z0-9_.-]+:)?[a-z0-9_./-]+$"), 8), "global" to duration), listOf("duration")))),
            "charges" to obj(mapOf("max" to integer(1, 16), "recharge" to duration, "mode" to enum("sequential", "parallel")), listOf("max", "recharge")),
            "costs" to array(obj(mapOf("resource" to text(), "amount" to ref("numeric"))), 128),
            "recast_effects" to array(ref("effect"), 64),
            "effects" to array(ref("effect"), 64, 1),
        )
        val definitions = linkedMapOf<String, Any>(
            "numeric" to mapOf("oneOf" to listOf(
                number(0.0, 1_000_000.0),
                obj(mapOf("expr" to (text() + mapOf("maxLength" to 256)))),
            )),
            "reference" to reference,
            "projectile_call" to mapOf("oneOf" to listOf(text(), obj(mapOf("ref" to text(), "with" to mapOf(
                "type" to "object", "maxProperties" to 32, "propertyNames" to text("^[a-z0-9_]{1,64}$"),
                "additionalProperties" to ref("numeric"))), listOf("ref")))),
            "status_tags" to (array(text("^(?:[a-z0-9_.-]+:)?[a-z0-9_./-]+$") + mapOf("maxLength" to 128), 16, 1) + mapOf("uniqueItems" to true)),
            "effect" to mapOf("oneOf" to effects),
            "condition" to mapOf("oneOf" to conditions),
            "shape" to mapOf("oneOf" to shapes),
            "selector" to obj(selectorFields, listOf("type")),
            "shaped_selector" to obj(selectorFields + ("shape" to ref("shape")), listOf("type", "shape")),
            "anchor" to mapOf("oneOf" to listOf(obj(mapOf("position" to enum("actor", "target", "ground", "event.position"))), obj(mapOf("attached" to enum("actor", "target", "event.target"))))),
            "inline_ability" to obj(abilityFields, listOf("name", "effects")),
            "pack" to obj(mapOf("format" to mapOf("const" to 1), "id" to packId, "name" to text(), "dependencies" to array(packId, 128)), listOf("format", "id", "name")),
            "ability" to obj(abilityFields + mapOf("kind" to mapOf("const" to "ability"), "id" to text()), listOf("kind", "id", "name", "effects")),
            "resource" to obj(mapOf(
                "kind" to mapOf("const" to "resource"), "id" to text(), "scope" to enum("class", "player"),
                "min" to number(-1_000_000_000.0, 1_000_000_000.0), "max" to number(-1_000_000_000.0, 1_000_000_000.0), "initial" to number(-1_000_000_000.0, 1_000_000_000.0),
                "regeneration" to obj(mapOf("amount" to mapOf("type" to "number", "exclusiveMinimum" to 0, "maximum" to 1_000_000), "every" to duration)),
            ), listOf("kind", "id", "scope", "min", "max", "initial")),
            "state" to obj(mapOf(
                "kind" to mapOf("const" to "state"), "id" to text(), "scope" to enum("player", "class", "activation", "status"),
                "persistent" to mapOf("type" to "boolean"),
                "fields" to mapOf("type" to "object", "minProperties" to 1, "maxProperties" to 64,
                    "propertyNames" to (localId + mapOf("maxLength" to 64)),
                    "additionalProperties" to mapOf("oneOf" to listOf(
                        obj(mapOf("type" to mapOf("const" to "boolean"), "initial" to mapOf("type" to "boolean"))),
                        obj(mapOf("type" to mapOf("const" to "enum"), "values" to array(localId, 32, 1), "initial" to localId)),
                        obj(mapOf("type" to mapOf("const" to "integer"), "min" to integer(-1_000_000_000, 1_000_000_000),
                            "max" to integer(-1_000_000_000, 1_000_000_000), "initial" to integer(-1_000_000_000, 1_000_000_000))),
                        obj(mapOf("type" to mapOf("const" to "number"), "min" to number(-1_000_000_000.0, 1_000_000_000.0),
                            "max" to number(-1_000_000_000.0, 1_000_000_000.0), "initial" to number(-1_000_000_000.0, 1_000_000_000.0))),
                    ))),
            ), listOf("kind", "id", "scope", "fields")),
            "projectile" to obj(mapOf(
                "kind" to mapOf("const" to "projectile"), "id" to text(), "speed" to number(0.1, 64.0),
                "gravity" to number(0.0, 64.0), "lifetime" to duration,
                "parameters" to mapOf("type" to "object", "maxProperties" to 32, "propertyNames" to text("^[a-z0-9_]{1,64}$"),
                    "additionalProperties" to obj(mapOf("type" to mapOf("const" to "number"), "min" to number(-1_000_000.0, 1_000_000.0),
                        "max" to number(-1_000_000.0, 1_000_000.0), "default" to number(-1_000_000.0, 1_000_000.0)), listOf("type", "min", "max"))),
                "collision" to obj(mapOf("entities" to enum("enemies", "allies", "any"), "blocks" to mapOf("const" to "solid")), emptyList()),
                "pierce" to obj(mapOf("additional_entities" to integer(1, 16))),
                "bounce" to obj(mapOf("blocks" to integer(1, 16))),
                "repeat_hit" to obj(mapOf("max_per_entity" to integer(2, 8), "interval" to duration)),
                "homing" to obj(mapOf("turn_degrees_per_tick" to number(0.1, 180.0))),
                "entity_hit" to array(ref("effect"), 64), "block_hit" to array(ref("effect"), 64), "expiry" to array(ref("effect"), 64),
            ), listOf("kind", "id", "speed", "lifetime")),
            "area" to obj(mapOf(
                "kind" to mapOf("const" to "area"), "id" to text(), "shape" to ref("shape"), "duration" to duration, "lifetime" to mapOf("const" to "maintained"),
                "sample_every" to duration, "targets" to ref("selector"),
                "enter" to array(ref("effect"), 64), "exit" to array(ref("effect"), 64), "expired" to array(ref("effect"), 64), "buffs" to array(ref("reference"), 16),
                "periodic" to obj(mapOf("every" to duration, "effects" to array(ref("effect"), 64, 1))),
            ), listOf("kind", "id", "shape", "targets")) + mapOf("oneOf" to listOf(
                mapOf("required" to listOf("duration"), "not" to mapOf("required" to listOf("lifetime"))),
                mapOf("required" to listOf("lifetime"), "not" to mapOf("required" to listOf("duration"))),
            )),
            "status" to obj(mapOf(
                "kind" to mapOf("const" to "status"), "id" to text(), "state" to ref("reference"), "duration" to duration, "reapply" to mapOf("const" to "refresh"),
                "tags" to ref("status_tags"),
                "control_categories" to ref("status_tags"),
                "immunities" to ref("status_tags"),
                "break_on_damage" to obj(mapOf("minimum_health_loss" to number(0.0, 1_000_000.0)), emptyList()),
                "broken" to array(ref("effect"), 64),
                "restrictions" to array(enum("activate", "move", "jump", "attack"), 4),
                "stacks" to obj(mapOf("max" to integer(1, 64), "duration" to enum("shared", "per_stack")), listOf("max")),
                "applied" to array(ref("effect"), 64), "refreshed" to array(ref("effect"), 64), "stacks_changed" to array(ref("effect"), 64), "expired" to array(ref("effect"), 64),
                "periodic" to obj(mapOf("every" to duration, "effects" to array(ref("effect"), 64, 1))),
                "modifiers" to array(mapOf("oneOf" to listOf(
                    obj(mapOf("type" to mapOf("const" to "attribute"), "attribute" to mapOf("const" to "minecraft:movement_speed"), "amount" to number(0.0, 1.0), "stacking" to mapOf("const" to "strongest")), listOf("type", "attribute", "amount")),
                    obj(mapOf("type" to mapOf("const" to "attribute"), "attribute" to mapOf("const" to "minecraft:movement_speed"), "amount" to number(0.0, 1.0), "stacking" to mapOf("const" to "capped_add"), "cap" to number(0.0, 1.0))),
                    obj(mapOf("type" to mapOf("const" to "replace"), "target" to obj(mapOf("ability" to localId)),
                        "replacement" to ref("reference"), "priority" to integer(-100, 100)), listOf("type", "target", "replacement")),
                    obj(mapOf("type" to mapOf("const" to "reflect"), "fraction" to number(0.0, 1.0),
                        "cap" to number(0.01, 128.0), "damage_type" to text()), listOf("type", "fraction", "cap", "damage_type")),
                )), 8),
            ), listOf("kind", "id", "duration")),
            "class" to obj(mapOf(
                "kind" to mapOf("const" to "class"), "id" to text(), "name" to text(),
                "abilities" to grantMap,
            )),
            "specialization" to obj(mapOf("kind" to mapOf("const" to "specialization"), "id" to text(),
                "name" to text(), "class" to ref("reference"), "abilities" to grantMap)),
            "progression_track" to obj(mapOf(
                "kind" to mapOf("const" to "progression_track"), "id" to text(), "scope" to enum("player", "class"),
                "classes" to array(ref("reference"), 128),
                "levels" to array(obj(mapOf("level" to integer(1, 128),
                    "xp" to mapOf("type" to "integer", "minimum" to 0, "maximum" to 1_000_000_000_000L),
                    "awards" to array(obj(mapOf("id" to localId, "type" to mapOf("const" to "talent_points"),
                        "budget" to localId, "amount" to integer(1, 1000))), 16)), listOf("level", "xp")), 128, 1),
                "cap" to obj(mapOf("level" to integer(1, 128), "overflow" to enum("stop", "bank")), emptyList()),
                "earn" to mapOf("type" to "object", "maxProperties" to 64, "propertyNames" to localId,
                    "additionalProperties" to obj(mapOf("event" to enum("entity_death", "vanilla_xp"), "phase" to enum("after"),
                        "when" to obj(mapOf("type" to mapOf("const" to "credited_to_owner"))),
                        "amount" to integer(1, 1_000_000), "xp" to integer(1, 1_000_000),
                        "recipients" to obj(mapOf("type" to enum("actor", "contributors", "nearby_allies"),
                            "range" to number(0.1, 64.0)), listOf("type")),
                        "distribution" to enum("each", "split")), listOf("event"))),
            ), listOf("kind", "id", "scope", "levels")),
            "empowerment" to obj(mapOf(
                "kind" to mapOf("const" to "empowerment"), "id" to text(),
                "changes" to array(obj(mapOf("type" to mapOf("const" to "replace"),
                    "target" to obj(mapOf("ability" to localId)), "replacement" to ref("reference"),
                    "priority" to integer(-100, 100)), listOf("type", "target", "replacement")), 32, 1),
            )),
            "unlock_tree" to obj(mapOf(
                "kind" to mapOf("const" to "unlock_tree"), "id" to text(), "track" to ref("reference"),
                "specializations" to array(ref("reference"), 128),
                "nodes" to mapOf("type" to "object", "minProperties" to 1, "maxProperties" to 128,
                    "propertyNames" to localId,
                    "additionalProperties" to obj(mapOf(
                        "selection" to enum("automatic", "talent"),
                        "requires" to obj(mapOf("type" to mapOf("const" to "level_at_least"),
                            "track" to ref("reference"), "level" to integer(1, 128))),
                        "prerequisites" to array(localId, 16), "ranks" to integer(1, 16),
                        "choice_group" to localId,
                        "cost" to obj(mapOf("budget" to localId, "amount" to integer(1, 1000))),
                        "grants" to array(mapOf("oneOf" to listOf(
                            obj(mapOf("type" to mapOf("const" to "empowerment"),
                                "ref" to ref("reference"))),
                            obj(mapOf("type" to mapOf("const" to "ability"),
                                "ref" to ref("reference"), "grant" to localId,
                                "slot" to localId), listOf("type", "ref", "grant")),
                        )), 16),
                    ), listOf("selection"))),
            ), listOf("kind", "id", "track", "nodes")),
        )
        return mapOf(
            "\$schema" to "https://json-schema.org/draft/2020-12/schema",
            "title" to "Archetype supported manifest grammar",
            "description" to "Structural editor schema. Run validatePacks for result availability, references, positive intervals, related bounds and recursion. Native registry checks require the server.",
            "\$defs" to definitions,
            "oneOf" to listOf("pack", "ability", "resource", "state", "area", "status", "projectile", "class", "specialization", "progression_track", "empowerment", "unlock_tree").map(::ref),
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
