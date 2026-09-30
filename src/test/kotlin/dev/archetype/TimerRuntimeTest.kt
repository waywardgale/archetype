package dev.archetype

import dev.archetype.definitions.*
import dev.archetype.runtime.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.util.UUID

class TimerRuntimeTest {
    private val actor = UUID(0, 1)
    private val other = UUID(0, 2)
    private val target = UUID(0, 3)
    private val secondTarget = UUID(0, 4)

    private class World : WorldOps {
        val positions = mutableMapOf<UUID, Position>()
        val healing = mutableListOf<Pair<UUID, Double>>()
        override fun validTarget(actor: UUID, target: UUID) = availableTarget(actor, target)
        override fun availableTarget(actor: UUID, target: UUID) = actor in positions && target in positions
        override fun position(entity: UUID) = positions[entity]
        override fun loaded(position: Position) = true
        override fun heal(target: UUID, amount: Double): Double { healing += target to amount; return amount }
        override fun damage(actor: UUID, target: UUID, amount: Double, damageType: String) = amount
        override fun view(actor: UUID, target: UUID) = positions[target]?.let { EntityView(target, it, 10.0, 20.0, true) }
        fun put(id: UUID) { positions[id] = Position("test", Vec(0.0, 0.0, 0.0)) }
    }

    private fun compile(grants: Map<String, String>, fingerprint: String = "v1", statuses: List<String> = emptyList()): CompileResult {
        val abilities = grants.entries.joinToString("\n") { (name, effects) ->
            "  $name:\n    definition:\n      name: $name\n      effects:\n${effects.prependIndent("        ")}"
        }
        val files = listOf(
            "workshop/pack.yaml" to "format: 1\nid: workshop\nname: Workshop",
            "workshop/class.yaml" to "kind: class\nid: fighter\nname: Fighter\nabilities:\n$abilities",
            "workshop/other.yaml" to "kind: class\nid: other\nname: Other\nabilities: {}",
        ) + statuses.mapIndexed { index, text -> "workshop/status$index.yaml" to text }
        return ManifestCompiler().compile(PackSnapshot(files.map { SourceFile(it.first, it.second.toByteArray()) }, fingerprint))
    }

    private fun definitions(result: CompileResult): DefinitionSet {
        assertTrue(result is CompileResult.Valid, "$result")
        return (result as CompileResult.Valid).definitions
    }
    private fun runtime(world: World, grants: Map<String, String>, fingerprint: String = "v1") = AbilityRuntime(world).also {
        it.publish(definitions(compile(grants, fingerprint)))
        it.selectClass(actor, "workshop:fighter")
        it.selectClass(other, "workshop:fighter")
    }
    private fun cast(runtime: AbilityRuntime, grant: String, owner: UUID = actor, recipient: UUID = target) =
        runtime.cast(owner, "workshop:fighter", grant, recipient, runtime.generation)
    private fun ticks(runtime: AbilityRuntime, count: Int) { repeat(count) { runtime.tick(listOf(actor, other)) } }
    private fun set(name: String = "combo", amount: Int = 1, recipient: String = "target", duration: String = "100ms") =
        "- type: set_timer\n  name: $name\n  target: $recipient\n  duration: $duration\n  expired:\n    - type: heal\n      target: actor\n      amount: $amount"

    @Test fun `refresh replaces the old expiry and keeps the new due tick`() {
        val world = World().also { listOf(actor, other, target, secondTarget).forEach(it::put) }
        val runtime = runtime(world, mapOf("mark" to set()))
        assertEquals(CastResult.Applied, cast(runtime, "mark"))
        ticks(runtime, 1)
        assertEquals(CastResult.Applied, cast(runtime, "mark"))
        ticks(runtime, 1)
        assertTrue(world.healing.isEmpty())
        assertEquals(1, runtime.timers(actor).single().remainingTicks)
        ticks(runtime, 1)
        assertEquals(listOf(actor to 1.0), world.healing)
        assertTrue(runtime.timers(actor).isEmpty())
        ticks(runtime, 2)
        assertEquals(1, world.healing.size)
    }

    @Test fun `read and cancel return actual state and do not run expiry`() {
        val toggle = "- type: read_timer\n  name: combo\n  target: target\n  as: clock\n" +
            "- type: branch\n  when: {type: compare, left: {expr: 'result.clock.active'}, op: eq, right: 1}\n" +
            "  then:\n    - type: cancel_timer\n      name: combo\n      target: target\n      as: stopped\n" +
            "    - type: heal\n      target: actor\n      amount: {expr: 'result.clock.remaining_ticks + result.stopped.cancelled'}\n" +
            "  else:\n${set().prependIndent("    ")}"
        val world = World().also { listOf(actor, other, target, secondTarget).forEach(it::put) }
        val runtime = runtime(world, mapOf("toggle" to toggle))
        assertEquals(CastResult.Applied, cast(runtime, "toggle"))
        assertEquals(1, runtime.timers(actor).size)
        assertEquals(CastResult.Applied, cast(runtime, "toggle"))
        assertTrue(runtime.timers(actor).isEmpty())
        ticks(runtime, 3)
        assertEquals(listOf(actor to 3.0), world.healing)
    }

    @Test fun `same name remains independent across grants owners and targets`() {
        val world = World().also { listOf(actor, other, target, secondTarget).forEach(it::put) }
        val runtime = runtime(world, mapOf("first" to set(amount = 1), "second" to set(amount = 2)))
        cast(runtime, "first")
        cast(runtime, "first", recipient = secondTarget)
        cast(runtime, "first", owner = other)
        cast(runtime, "second")
        assertEquals(3, runtime.timers(actor).size)
        assertEquals(1, runtime.timers(other).size)
        ticks(runtime, 2)
        assertEquals(listOf(1.0, 1.0, 1.0, 2.0), world.healing.map { it.second })
    }

    @Test fun `timers created by separate status contributions keep separate lifetimes`() {
        val world = World().also { listOf(actor, other, target, secondTarget).forEach(it::put) }
        val apply = "- type: apply_status\n  id: first\n  status: mark\n  target: target\n" +
            "- type: apply_status\n  id: second\n  status: mark\n  target: target"
        val status = "kind: status\nid: mark\nduration: 500ms\napplied:\n${set(amount = 2).prependIndent("  ")}"
        val runtime = AbilityRuntime(world).also {
            it.publish(definitions(compile(mapOf("mark" to apply), statuses = listOf(status))))
            it.selectClass(actor, "workshop:fighter")
        }
        assertEquals(CastResult.Applied, cast(runtime, "mark"))
        assertEquals(2, runtime.timers(actor).size)
        ticks(runtime, 2)
        assertEquals(listOf(actor to 2.0, actor to 2.0), world.healing)
    }

    @Test fun `class change and definition replacement cancel timers without expiry callbacks`() {
        val world = World().also { listOf(actor, other, target, secondTarget).forEach(it::put) }
        val first = mapOf("mark" to set())
        val runtime = runtime(world, first)
        cast(runtime, "mark")
        runtime.selectClass(actor, "workshop:other")
        ticks(runtime, 2)
        assertTrue(world.healing.isEmpty())
        runtime.selectClass(actor, "workshop:fighter")
        cast(runtime, "mark")
        runtime.publish(definitions(compile(mapOf("mark" to set(amount = 2)), "v2")))
        ticks(runtime, 2)
        assertTrue(runtime.timers(actor).isEmpty())
        assertTrue(world.healing.isEmpty())
    }

    @Test fun `invalid names and nonpositive durations have located diagnostics`() {
        val invalidName = compile(mapOf("bad" to set(name = "Bad Name")))
        assertTrue(invalidName is CompileResult.Invalid)
        assertTrue((invalidName as CompileResult.Invalid).diagnostics.any { it.field.endsWith("name") })
        val invalidDuration = compile(mapOf("bad" to set(duration = "0s")))
        assertTrue(invalidDuration is CompileResult.Invalid)
        assertTrue((invalidDuration as CompileResult.Invalid).diagnostics.any { it.field.endsWith("duration") })
    }
}
