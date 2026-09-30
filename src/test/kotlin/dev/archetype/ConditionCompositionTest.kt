package dev.archetype

import dev.archetype.definitions.*
import dev.archetype.runtime.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.util.UUID

class ConditionCompositionTest {
    private val actor = UUID(0, 1)
    private class World : WorldOps {
        val healing = mutableListOf<Double>()
        val rolls = ArrayDeque<Double>()
        override fun validTarget(actor: UUID, target: UUID) = actor == target
        override fun heal(target: UUID, amount: Double): Double { healing += amount; return amount }
        override fun damage(actor: UUID, target: UUID, amount: Double, damageType: String) = amount
        override fun position(entity: UUID) = Position("test", Vec(0.0, 0.0, 0.0))
        override fun loaded(position: Position) = true
        override fun roll(actor: UUID) = rolls.removeFirst()
    }
    private fun compile(effects: String): CompileResult {
        val files = listOf(
            "workshop/pack.yaml" to "format: 1\nid: workshop\nname: Workshop",
            "workshop/focus.yaml" to "kind: resource\nid: focus\nscope: class\nmin: 0\nmax: 10\ninitial: 10",
            "workshop/mark.yaml" to "kind: status\nid: mark\nduration: 1s",
            "workshop/class.yaml" to "kind: class\nid: fighter\nname: Fighter\nabilities:\n  check:\n    definition:\n      name: Check\n      effects:\n${effects.prependIndent("        ")}",
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
    private fun cast(runtime: AbilityRuntime) = runtime.cast(actor, "workshop:fighter", "check", null, runtime.generation)

    @Test fun `all any and not compose live resource and status checks`() {
        val effects = """- type: branch
  when:
    all:
      - {type: resource_at_least, resource: focus, amount: 5}
      - not: {type: has_status, target: actor, status: mark}
  then: [{type: heal, target: actor, amount: 1}]
  else: [{type: heal, target: actor, amount: 2}]
- type: apply_status
  id: mark_application
  status: mark
  target: actor
- type: branch
  when:
    any:
      - {type: compare, left: 1, op: lt, right: 0}
      - {type: has_status, target: actor, status: mark}
  then: [{type: heal, target: actor, amount: 3}]
  else: [{type: heal, target: actor, amount: 4}]"""
        val world = World()
        val runtime = runtime(world, effects)
        assertEquals(CastResult.Applied, cast(runtime))
        assertEquals(CastResult.Applied, cast(runtime))
        assertEquals(listOf(1.0, 3.0, 2.0, 3.0), world.healing)
    }

    @Test fun `nested conditions validate every leaf and reject oversized trees`() {
        val missing = compile("""- type: branch
  when:
    all:
      - {type: compare, left: 1, op: eq, right: 1}
      - {type: compare, left: {expr: 'result.missing.amount'}, op: gt, right: 0}
  then: [{type: heal, target: actor, amount: 1}]""") as CompileResult.Invalid
        assertTrue(missing.diagnostics.any { it.problem.contains("result.missing.amount") })

        val empty = compile("- type: branch\n  when: {any: []}\n  then: [{type: heal, target: actor, amount: 1}]") as CompileResult.Invalid
        assertTrue(empty.diagnostics.any { it.field.endsWith("any") })

        val deep = "{not: ".repeat(9) + "{type: compare, left: 1, op: eq, right: 1}" + "}".repeat(9)
        val tooDeep = compile("- type: branch\n  when: $deep\n  then: [{type: heal, target: actor, amount: 1}]") as CompileResult.Invalid
        assertTrue(tooDeep.diagnostics.any { it.problem.contains("depth or node limit") })
    }

    @Test fun `chance branches sample once per execution and validate probability bounds`() {
        val world = World().also { it.rolls.addAll(listOf(0.1, 0.8, 0.49)) }
        val effects = """- type: branch
  when: {type: chance, probability: 0.5}
  then: [{type: heal, target: actor, amount: 1}]
  else: [{type: heal, target: actor, amount: 2}]"""
        val runtime = runtime(world, effects)
        repeat(3) { assertEquals(CastResult.Applied, cast(runtime)) }
        assertEquals(listOf(1.0, 2.0, 1.0), world.healing)
        assertTrue(world.rolls.isEmpty())
        for (value in listOf("-0.1", "1.1")) {
            val invalid = compile(effects.replace("0.5", value)) as CompileResult.Invalid
            assertTrue(invalid.diagnostics.any { it.field.endsWith("probability") })
        }
    }
}
