package dev.archetype

import dev.archetype.definitions.*
import dev.archetype.runtime.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.util.UUID

class StatusDispelTest {
    private val actor = UUID(0, 1)
    private val recipient = UUID(0, 2)
    private val other = UUID(0, 3)

    private class World : WorldOps {
        val positions = linkedMapOf<UUID, Position>()
        val healing = mutableListOf<Pair<UUID, Double>>()
        val speeds = mutableMapOf<UUID, Double>()
        var visible = true
        fun put(id: UUID, x: Double) { positions[id] = Position("test", Vec(x, 0.0, 0.0)) }
        override fun validTarget(actor: UUID, target: UUID) = availableTarget(actor, target)
        override fun availableTarget(actor: UUID, target: UUID) = actor in positions && target in positions
        override fun position(entity: UUID) = positions[entity]
        override fun loaded(position: Position) = true
        override fun view(actor: UUID, target: UUID) = positions[target]?.let { EntityView(target, it, 10.0, 20.0, true) }
        override fun candidates(actor: UUID, origin: Position, radius: Double, limit: Int) = positions.keys.mapNotNull { view(actor, it) }.take(limit)
        override fun lineOfSight(origin: Position, target: UUID) = visible
        override fun heal(target: UUID, amount: Double): Double { healing += target to amount; return amount }
        override fun damage(actor: UUID, target: UUID, amount: Double, damageType: String) = amount
        override fun movementSpeedBonus(target: UUID, amount: Double) { speeds[target] = amount }
    }

    private fun world() = World().also { it.put(actor, 0.0); it.put(recipient, 1.0); it.put(other, 8.0) }
    private fun status(id: String = "mark", extra: String = "", duration: String = "2s") = "kind: status\nid: $id\nduration: $duration\n$extra"
    private fun apply(status: String = "mark", id: String = "mark") = "- type: apply_status\n  id: $id\n  target: target\n  status: $status"
    private fun heal(amount: String, target: String = "actor") = "- type: heal\n  target: $target\n  amount: $amount"
    private fun branch(filter: String, onTrue: String = heal("1"), onFalse: String = heal("0"), target: String = "target") =
        "- type: branch\n  when: {type: has_status, target: $target$filter}\n  then:\n${onTrue.prependIndent("    ")}\n  else:\n${onFalse.prependIndent("    ")}"
    private fun compile(
        abilities: Map<String, String>, statuses: List<String> = listOf(status()), areas: List<String> = emptyList(),
        options: Map<String, String> = emptyMap(), fingerprint: String = "first",
    ): CompileResult {
        val grants = abilities.keys.joinToString("\n") { "  $it: {ref: cast/$it}" }
        val files = listOf(
            "workshop/pack.yaml" to "format: 1\nid: workshop\nname: Workshop",
            "workshop/class.yaml" to "kind: class\nid: fighter\nname: Fighter\nabilities:\n$grants",
            "workshop/resource.yaml" to "kind: resource\nid: focus\nscope: player\nmin: 0\nmax: 100\ninitial: 100",
        ) + abilities.map { (id, effects) -> "workshop/$id.yaml" to "kind: ability\nid: cast/$id\nname: $id\n${options[id].orEmpty()}\neffects:\n${effects.prependIndent("  ")}" } +
            statuses.mapIndexed { i, value -> "workshop/status$i.yaml" to value } + areas.mapIndexed { i, value -> "workshop/area$i.yaml" to value }
        return ManifestCompiler().compile(PackSnapshot(files.map { SourceFile(it.first, it.second.toByteArray()) }, fingerprint))
    }
    private fun definitions(result: CompileResult): DefinitionSet {
        assertTrue(result is CompileResult.Valid, "$result")
        return (result as CompileResult.Valid).definitions
    }
    private fun runtime(world: World, result: CompileResult) = AbilityRuntime(world).also {
        it.publish(definitions(result))
        it.selectClass(actor, "workshop:fighter")
        it.selectClass(other, "workshop:fighter")
    }
    private fun cast(runtime: AbilityRuntime, grant: String, owner: UUID = actor, target: UUID? = recipient) =
        runtime.cast(owner, "workshop:fighter", grant, target, runtime.generation)
    private fun ticks(runtime: AbilityRuntime, count: Int) { repeat(count) { runtime.tick(listOf(actor, other)) } }
    private val bonus = "modifiers: [{type: attribute, attribute: 'minecraft:movement_speed', amount: 0.05}]"
    private val area = "kind: area\nid: field\nshape: {type: sphere, radius: 2}\nduration: 1s\nsample_every: 50ms\ntargets: {type: living_entities, filters: [{type: relation, is: ally}]}\nbuffs: [child]"
    private val create = "- type: create_area\n  area: field\n  anchor: {attached: actor}"

    @Test fun `dispel intersects definition tags and source and reports removed contributions and stacks once`() {
        val remove = "- type: dispel\n  target: target\n  status: mark\n  tags: [harmful, fire]\n  source: actor\n  count: 64\n  as: removed\n" +
            heal("{expr: 'result.removed.contributions_removed * 10 + result.removed.stacks_removed'}")
        val world = world()
        val runtime = runtime(world, compile(mapOf("mark" to apply(), "cold" to apply("cold"), "strip" to remove), listOf(
            status(extra = "tags: [harmful, fire]\nstacks: {max: 3}"), status("cold", "tags: [harmful, cold]"),
        )))
        repeat(3) { cast(runtime, "mark") }
        cast(runtime, "mark", other)
        cast(runtime, "cold")
        assertEquals(CastResult.Applied, cast(runtime, "strip"))
        assertEquals(listOf(actor to 13.0), world.healing)
        assertEquals(setOf(other to "workshop:mark", actor to "workshop:cold"), runtime.statuses(recipient).map { it.owner to it.status }.toSet())
        cast(runtime, "strip")
        assertEquals(actor to 0.0, world.healing.last())
    }

    @Test fun `default count removes oldest application and refreshing does not change removal order`() {
        val world = world()
        val runtime = runtime(world, compile(mapOf("first" to apply(), "second" to apply(), "strip" to "- type: dispel\n  target: target")))
        cast(runtime, "first")
        cast(runtime, "second")
        ticks(runtime, 1)
        cast(runtime, "first")
        cast(runtime, "strip")
        assertEquals("second", runtime.statuses(recipient).single().grant)
    }

    @Test fun `grant source matches logical grant across casts without removing another grant or owner`() {
        val toggle = branch(", status: mark, source: grant", "- type: dispel\n  target: target\n  source: grant\n  count: 64", apply())
        val world = world()
        val runtime = runtime(world, compile(mapOf("primary" to toggle, "secondary" to toggle)))
        cast(runtime, "primary")
        cast(runtime, "secondary")
        cast(runtime, "primary", other)
        assertEquals(3, runtime.statuses(recipient).size)
        cast(runtime, "primary")
        assertEquals(setOf(actor to "secondary", other to "primary"), runtime.statuses(recipient).map { it.owner to it.grant }.toSet())
    }

    @Test fun `has status reads current contributions and checks actor targets as well as selected targets`() {
        val effects = branch(", tags: [harmful]") + "\n" + apply() + "\n" + branch(", status: mark, tags: [harmful], source: actor") +
            "\n- type: dispel\n  target: target\n" + branch(", status: mark")
        val world = world()
        val runtime = runtime(world, compile(mapOf("sequence" to effects), listOf(status(extra = "tags: [harmful]"))))
        cast(runtime, "sequence")
        assertEquals(listOf(0.0, 1.0, 0.0), world.healing.map { it.second })
        assertTrue(runtime.statuses(recipient).isEmpty())
        val self = runtime(world, compile(mapOf("sequence" to effects.replace("target: target", "target: actor")), listOf(status(extra = "tags: [harmful]"))))
        assertEquals(CastResult.Applied, cast(self, "sequence", target = null))
        assertTrue(self.statuses(actor).isEmpty())
    }

    @Test fun `self inspection and removal are valid and cancel the callback while its outer ability continues`() {
        val callback = branch(", status: mark, source: grant", "- type: dispel\n  status: mark\n  target: target\n  source: grant") + "\n" + heal("99")
        val world = world()
        val runtime = runtime(world, compile(mapOf("mark" to (apply() + "\n" + heal("7"))), listOf(status(extra =
            "applied:\n${callback.prependIndent("  ")}\nexpired:\n${heal("88").prependIndent("  ")}",
        ))))
        assertEquals(CastResult.Applied, cast(runtime, "mark"))
        ticks(runtime, 50)
        assertTrue(runtime.statuses(recipient).isEmpty())
        assertEquals(listOf(actor to 7.0), world.healing)
        assertTrue(runtime.drainFailures().isEmpty())
    }

    @Test fun `dispel cancels delayed work and descendant areas and buffs but preserves independent source work`() {
        val delayed = "- type: delay\n  duration: 100ms\n  effects:\n${heal("2").prependIndent("    ")}"
        val parent = status(extra = "applied:\n${("$create\n$delayed").prependIndent("  ")}\nexpired:\n${heal("99").prependIndent("  ")}")
        val child = status("child", "$bonus\nperiodic:\n  every: 100ms\n  effects:\n${heal("3", "target").prependIndent("    ")}")
        val world = world().also { it.put(other, 0.0) }
        val runtime = runtime(world, compile(mapOf("mark" to (apply() + "\n" + delayed.replace("amount: 2", "amount: 1")),
            "strip" to "- type: dispel\n  target: target\n  status: mark\n  source: actor"), listOf(parent, child), listOf(area)))
        cast(runtime, "mark")
        cast(runtime, "mark", other)
        cast(runtime, "strip")
        assertEquals(setOf(other), runtime.statuses(recipient).map { it.owner }.toSet())
        assertEquals(0.05, world.speeds[recipient])
        ticks(runtime, 2)
        assertEquals(listOf(1.0), world.healing.filter { it.first == actor && it.second != 3.0 }.map { it.second })
        assertEquals(listOf(1.0, 2.0), world.healing.filter { it.first == other && it.second != 3.0 }.map { it.second }.sorted())
        assertEquals(listOf(recipient to 3.0), world.healing.filter { it.first == recipient })
        assertFalse(world.healing.any { it.second == 99.0 })
    }

    @Test fun `dispelled membership buff stays absent until reentry while the area keeps its own periodic work`() {
        val world = world()
        val periodic = "\nperiodic:\n  every: 100ms\n  effects:\n${heal("1", "target").prependIndent("    ")}"
        val runtime = runtime(world, compile(mapOf("aura" to create, "strip" to "- type: dispel\n  target: target\n  tags: [beneficial]"),
            listOf(status("child", "$bonus\ntags: [beneficial]")), listOf(area + periodic)))
        cast(runtime, "aura")
        cast(runtime, "strip")
        assertEquals(0.0, world.speeds[recipient])
        ticks(runtime, 2)
        assertTrue(runtime.statuses(recipient).isEmpty())
        assertEquals(listOf(recipient to 1.0), world.healing)
        world.put(recipient, 10.0)
        ticks(runtime, 1)
        world.put(recipient, 1.0)
        ticks(runtime, 1)
        assertEquals(1, runtime.statuses(recipient).size)
        assertEquals(0.05, world.speeds[recipient])
        runtime.shutdown()
        assertEquals(0.0, world.speeds[recipient])
    }

    @Test fun `dispel updates the strongest bonus and makes the freed recipient slot reusable`() {
        val applications = (1..64).joinToString("\n") { apply(if (it == 1) "strong" else "mark", "application_$it") }
        val world = world()
        val runtime = runtime(world, compile(mapOf("fill" to applications, "single" to apply(),
            "strip" to "- type: dispel\n  target: target\n  status: strong"), listOf(status(extra = bonus), status("strong", bonus.replace("0.05", "0.1")))))
        cast(runtime, "fill")
        assertEquals(0.1, world.speeds[recipient])
        assertEquals(CastResult.Rejected("status contribution limit reached"), cast(runtime, "single", other))
        cast(runtime, "strip")
        assertEquals(0.05, world.speeds[recipient])
        assertEquals(CastResult.Applied, cast(runtime, "single", other))
        assertEquals(64, runtime.statuses(recipient).size)
    }

    @Test fun `target loss skips status checks and dispel results instead of treating absence as no status`() {
        val body = branch(", status: mark") + "\n- type: dispel\n  target: target\n  as: removed\n" +
            heal("{expr: 'result.removed.contributions_removed'}") + "\n" + heal("7")
        val world = world()
        val runtime = runtime(world, compile(mapOf("check" to "- type: delay\n  duration: 100ms\n  effects:\n${body.prependIndent("    ")}")))
        assertEquals(CastResult.Rejected("target is unavailable"), cast(runtime, "check", target = null))
        cast(runtime, "check")
        world.positions.remove(recipient)
        ticks(runtime, 2)
        assertEquals(listOf(actor to 7.0), world.healing)
    }

    @Test fun `selected status operations revalidate obstruction on delayed execution`() {
        val body = branch(", status: mark") + "\n- type: dispel\n  target: target\n  count: 64"
        val select = "- type: for_each\n  targets: {type: living_entities, shape: {type: sphere, radius: 2}}\n  effects:\n    - type: delay\n      duration: 100ms\n      effects:\n${body.prependIndent("        ")}"
        val world = world()
        val runtime = runtime(world, compile(mapOf("mark" to apply(), "check" to select)))
        cast(runtime, "mark")
        cast(runtime, "check")
        world.visible = false
        ticks(runtime, 2)
        assertEquals(1, runtime.statuses(recipient).size)
        assertTrue(world.healing.isEmpty())
    }

    @Test fun `read only references do not reserve status callback work but still participate in reload cancellation`() {
        val huge = "applied:\n  - type: repeat\n    count: 64\n    every: 1s\n    effects:\n" + List(64) { heal("1").prependIndent("      ") }.joinToString("\n")
        val read = branch(", status: mark") + "\n- type: dispel\n  target: target\n  status: mark\n" +
            "- type: delay\n  duration: 100ms\n  effects:\n${heal("7").prependIndent("    ")}"
        val abilities = mapOf("check" to read)
        val world = world()
        val runtime = runtime(world, compile(abilities, listOf(status(extra = huge))))
        assertEquals(CastResult.Applied, cast(runtime, "check"))
        runtime.publish(definitions(compile(abilities, listOf(status(extra = huge, duration = "3s")), fingerprint = "changed")))
        ticks(runtime, 2)
        assertEquals(listOf(actor to 0.0), world.healing)
    }

    @Test fun `query before creation still expands the created status dependencies`() {
        val root = branch(", status: mark") + "\n" + apply()
        val parent = status(extra = "applied:\n  - type: delay\n    duration: 100ms\n    effects:\n${apply("child").prependIndent("      ")}")
        val world = world()
        val abilities = mapOf("mark" to root)
        val runtime = runtime(world, compile(abilities, listOf(parent, status("child"))))
        cast(runtime, "mark")
        runtime.publish(definitions(compile(abilities, listOf(parent, status("child", duration = "3s")), fingerprint = "changed")))
        assertTrue(runtime.statuses(recipient).isEmpty())
        ticks(runtime, 2)
        assertTrue(runtime.statuses(recipient).isEmpty())
    }

    @Test fun `known excessive dispel and query work rejects before cost or cooldown payment`() {
        val world = world()
        for (step in listOf("- type: dispel\n  target: actor", branch("", target = "actor"))) {
            val runtime = runtime(world, compile(mapOf("expensive" to List(16) { step }.joinToString("\n")),
                options = mapOf("expensive" to "cooldown: 1s\ncosts: [{resource: focus, amount: 3}]")))
            assertEquals(CastResult.Rejected("ability work limit exceeded"), cast(runtime, "expensive"))
            assertTrue(runtime.record(actor).resources.isEmpty())
            assertTrue(runtime.record(actor).cooldowns.isEmpty())
        }
    }

    @Test fun `invalid tags filters counts references and contexts give located diagnostics`() {
        val invalid = listOf("count: 0", "count: 65", "count: 1.5", "count: null", "source: invalid", "source: null", "status: missing",
            "status: other:mark", "tags: []", "tags: [harmful, workshop:harmful]", "tags: [Bad]", "tags: [other:tag]", "tags: [null]", "tags: [true]",
            "tags: [${(1..17).joinToString { "tag$it" }}]", "unknown: true")
        for (option in invalid) {
            val result = compile(mapOf("strip" to "- type: dispel\n  target: target\n  $option"))
            assertTrue(result is CompileResult.Invalid, option)
            assertTrue((result as CompileResult.Invalid).diagnostics.all { it.file.isNotBlank() && it.field != "$" }, "$result")
        }
        assertTrue(compile(mapOf("check" to branch(", status: missing"))) is CompileResult.Invalid)
        assertTrue(compile(mapOf("check" to branch(", source: invalid"))) is CompileResult.Invalid)
        assertTrue(compile(mapOf("mark" to apply()), listOf(status(extra = "tags: [harmful, workshop:harmful]"))) is CompileResult.Invalid)
        assertTrue(compile(mapOf("check" to branch(", status: mark")), options = mapOf("check" to "target: {type: ground}")) is CompileResult.Invalid)
        assertTrue(compile(mapOf("strip" to "- type: dispel\n  target: target"), options = mapOf("strip" to "target: {type: ground}")) is CompileResult.Invalid)
    }
}
