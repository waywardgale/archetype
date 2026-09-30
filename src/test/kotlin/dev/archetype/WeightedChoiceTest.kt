package dev.archetype

import dev.archetype.definitions.*
import dev.archetype.runtime.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.util.UUID

class WeightedChoiceTest {
    private val actor = UUID(0, 1)
    private class World : WorldOps {
        val rolls = ArrayDeque<Double>()
        val healing = mutableListOf<Double>()
        override fun validTarget(actor: UUID, target: UUID) = actor == target
        override fun heal(target: UUID, amount: Double): Double { healing += amount; return amount }
        override fun damage(actor: UUID, target: UUID, amount: Double, damageType: String) = amount
        override fun roll(actor: UUID) = rolls.removeFirst()
    }
    private fun compile(effects: String): CompileResult {
        val files = listOf(
            "workshop/pack.yaml" to "format: 1\nid: workshop\nname: Workshop",
            "workshop/class.yaml" to "kind: class\nid: fighter\nname: Fighter\nabilities:\n  choose:\n    definition:\n      name: Choose\n      effects:\n${effects.prependIndent("        ")}",
        )
        return ManifestCompiler().compile(PackSnapshot(files.map { SourceFile(it.first, it.second.toByteArray()) }, "v1"))
    }
    private fun runtime(world: World, effects: String): AbilityRuntime {
        val result = compile(effects)
        assertTrue(result is CompileResult.Valid, "$result")
        return AbilityRuntime(world).also {
            it.publish((result as CompileResult.Valid).definitions)
            it.selectClass(actor, "workshop:fighter")
        }
    }
    private fun cast(runtime: AbilityRuntime) = runtime.cast(actor, "workshop:fighter", "choose", null, runtime.generation)

    @Test fun `weighted choice draws once and publishes the selected index`() {
        val effects = """- type: choose
  as: picked
  options:
    - weight: 1
      effects: [{type: heal, target: actor, amount: 1}]
    - weight: 3
      effects: [{type: heal, target: actor, amount: 2}]
- type: heal
  target: actor
  amount: {expr: 'result.picked.index * 10'}"""
        val world = World().also { it.rolls.addAll(listOf(0.1, 0.25, 0.99)) }
        val runtime = runtime(world, effects)
        repeat(3) { assertEquals(CastResult.Applied, cast(runtime)) }
        assertEquals(listOf(1.0, 0.0, 2.0, 10.0, 2.0, 10.0), world.healing)
        assertTrue(world.rolls.isEmpty())
    }

    @Test fun `a branch local result is unavailable after choice unless every branch provides it`() {
        val effects = """- type: choose
  options:
    - weight: 1
      effects: [{type: heal, target: actor, amount: 1, as: one}]
    - weight: 1
      effects: [{type: heal, target: actor, amount: 2}]
- type: heal
  target: actor
  amount: {expr: 'result.one.health_restored'}"""
        val result = compile(effects) as CompileResult.Invalid
        assertTrue(result.diagnostics.any { it.problem.contains("result.one.health_restored") })
    }

    @Test fun `a result supplied by every choice branch remains available`() {
        val effects = """- type: choose
  options:
    - weight: 1
      effects: [{type: heal, target: actor, amount: 1, as: value}]
    - weight: 1
      effects: [{type: heal, target: actor, amount: 2, as: value}]
- type: heal
  target: actor
  amount: {expr: 'result.value.health_restored * 2'}"""
        val world = World().also { it.rolls += 0.75 }
        val runtime = runtime(world, effects)
        assertEquals(CastResult.Applied, cast(runtime))
        assertEquals(listOf(2.0, 4.0), world.healing)
    }

    @Test fun `choice weights and list bounds produce located diagnostics`() {
        val effects = "- type: choose\n  options:\n    - weight: 0\n      effects: [{type: heal, target: actor, amount: 1}]"
        val zero = compile(effects) as CompileResult.Invalid
        assertTrue(zero.diagnostics.any { it.field.endsWith("weight") })
        val empty = compile("- type: choose\n  options: []") as CompileResult.Invalid
        assertTrue(empty.diagnostics.any { it.field.endsWith("options") })
        val many = (1..11).joinToString("\n") { "    - weight: 1000\n      effects: [{type: heal, target: actor, amount: 1}]" }
        val total = compile("- type: choose\n  options:\n$many") as CompileResult.Invalid
        assertTrue(total.diagnostics.any { it.problem.contains("total choice weight") })
    }

    @Test fun `nested sequence preserves ordered results for following effects`() {
        val effects = """- type: sequence
  effects:
    - {type: heal, target: actor, amount: 2, as: first}
    - type: heal
      target: actor
      amount: {expr: 'result.first.health_restored * 3'}
      as: second
- type: heal
  target: actor
  amount: {expr: 'result.second.health_restored + 1'}"""
        val world = World()
        val runtime = runtime(world, effects)
        assertEquals(CastResult.Applied, cast(runtime))
        assertEquals(listOf(2.0, 6.0, 7.0), world.healing)
    }
}
