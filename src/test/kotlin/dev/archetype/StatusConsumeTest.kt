package dev.archetype

import dev.archetype.definitions.*
import dev.archetype.runtime.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.util.UUID

class StatusConsumeTest {
    private val actor = UUID(0, 1)
    private val other = UUID(0, 2)
    private val target = UUID(0, 3)
    private class World : WorldOps {
        val healing = mutableListOf<Pair<UUID, Double>>()
        val damage = mutableListOf<Double>()
        override fun validTarget(actor: UUID, target: UUID) = true
        override fun heal(target: UUID, amount: Double): Double { healing += target to amount; return amount }
        override fun damage(actor: UUID, target: UUID, amount: Double, damageType: String): Double { damage += amount; return amount }
        override fun position(entity: UUID) = Position("test", Vec(0.0, 0.0, 0.0))
        override fun loaded(position: Position) = true
    }
    private fun compile(consume: String, fingerprint: String = "v1"): CompileResult {
        val classFile = """kind: class
id: fighter
name: Fighter
abilities:
  mark:
    definition:
      name: Mark
      effects: [{type: apply_status, id: mark_application, status: mark, target: target}]
  detonate:
    definition:
      name: Detonate
      effects:
${consume.prependIndent("        ")}"""
        val status = """kind: status
id: mark
duration: 500ms
stacks: {max: 3, duration: per_stack}
tags: [harmful]
stacks_changed:
  - type: heal
    target: actor
    amount: {expr: 'status.stacks'}
expired:
  - type: heal
    target: actor
    amount: 99"""
        val files = listOf(
            "workshop/pack.yaml" to "format: 1\nid: workshop\nname: Workshop",
            "workshop/class.yaml" to classFile,
            "workshop/status.yaml" to status,
        )
        return ManifestCompiler().compile(PackSnapshot(files.map { SourceFile(it.first, it.second.toByteArray()) }, fingerprint))
    }
    private fun runtime(world: World, consume: String): AbilityRuntime {
        val result = compile(consume)
        assertTrue(result is CompileResult.Valid, "$result")
        return AbilityRuntime(world).also {
            it.publish((result as CompileResult.Valid).definitions)
            it.selectClass(actor, "workshop:fighter")
            it.selectClass(other, "workshop:fighter")
        }
    }
    private fun cast(runtime: AbilityRuntime, grant: String, owner: UUID = actor) = runtime.cast(owner, "workshop:fighter", grant, target, runtime.generation)

    @Test fun `partial consume reports actual stacks and runs one changed callback`() {
        val consume = """- type: consume_status
  target: target
  status: mark
  count: 2
  as: used
- type: damage
  target: target
  amount: {expr: 'result.used.stacks_removed * 4 + result.used.contributions_removed'}
  damage_type: minecraft:magic"""
        val world = World()
        val runtime = runtime(world, consume)
        repeat(3) { assertEquals(CastResult.Applied, cast(runtime, "mark")) }
        assertEquals(3, runtime.statuses(target).single().stacks)
        assertEquals(CastResult.Applied, cast(runtime, "detonate"))
        assertEquals(1, runtime.statuses(target).single().stacks)
        assertEquals(listOf(8.0), world.damage)
        assertEquals(listOf(actor to 2.0, actor to 3.0, actor to 1.0), world.healing)
        assertEquals(CastResult.Applied, cast(runtime, "detonate"))
        assertTrue(runtime.statuses(target).isEmpty())
        assertEquals(listOf(8.0, 5.0), world.damage)
        repeat(10) { runtime.tick(listOf(actor, other)) }
        assertFalse(world.healing.any { it.second == 99.0 })
    }

    @Test fun `owner filter does not consume another players mark`() {
        val consume = """- type: consume_status
  target: target
  tags: [harmful]
  source: actor
  count: 4
  as: used
- type: damage
  target: target
  amount: {expr: 'result.used.stacks_removed'}
  damage_type: minecraft:magic"""
        val world = World()
        val runtime = runtime(world, consume)
        repeat(2) { cast(runtime, "mark") }
        repeat(2) { cast(runtime, "mark", other) }
        cast(runtime, "detonate")
        assertEquals(listOf(2.0), world.damage)
        assertEquals(listOf(other to 2), runtime.statuses(target).map { it.owner to it.stacks })
    }

    @Test fun `status reads see live stacks before and after consumption`() {
        val effects = """- type: read_status
  target: target
  status: mark
  source: actor
  as: before
- type: consume_status
  target: target
  status: mark
  source: actor
  count: 1
  as: used
- type: read_status
  target: target
  status: mark
  source: actor
  as: after
- type: damage
  target: target
  amount: {expr: 'result.before.stacks * 10 + result.used.stacks_removed + result.after.stacks'}
  damage_type: minecraft:magic"""
        val world = World()
        val runtime = runtime(world, effects)
        repeat(2) { cast(runtime, "mark") }
        repeat(3) { cast(runtime, "mark", other) }
        assertEquals(CastResult.Applied, cast(runtime, "detonate"))
        assertEquals(listOf(22.0), world.damage)
        assertEquals(listOf(actor to 1, other to 3), runtime.statuses(target).map { it.owner to it.stacks })
    }

    @Test fun `invalid count and unknown status receive diagnostics`() {
        val badCount = compile("- type: consume_status\n  target: target\n  count: 0") as CompileResult.Invalid
        assertTrue(badCount.diagnostics.any { it.field.endsWith("count") })
        val unknown = compile("- type: consume_status\n  target: target\n  count: 1\n  status: missing") as CompileResult.Invalid
        assertTrue(unknown.diagnostics.any { it.problem.contains("unknown status") })
    }
}
