package dev.archetype

import dev.archetype.definitions.*
import dev.archetype.runtime.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.util.UUID

class ResourceEditTest {
    private val actor = UUID(0, 1)
    private class World : WorldOps {
        val healing = mutableListOf<Double>()
        override fun validTarget(actor: UUID, target: UUID) = actor == target
        override fun heal(target: UUID, amount: Double): Double { healing += amount; return amount }
        override fun damage(actor: UUID, target: UUID, amount: Double, damageType: String) = amount
    }
    private fun compile(effects: String): CompileResult {
        val files = listOf(
            "workshop/pack.yaml" to "format: 1\nid: workshop\nname: Workshop",
            "workshop/focus.yaml" to "kind: resource\nid: focus\nscope: class\nmin: -10\nmax: 10\ninitial: 5",
            "workshop/class.yaml" to "kind: class\nid: fighter\nname: Fighter\nabilities:\n  edit:\n    definition:\n      name: Edit\n      effects:\n${effects.prependIndent("        ")}",
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
    private fun cast(runtime: AbilityRuntime) = runtime.cast(actor, "workshop:fighter", "edit", null, runtime.generation)

    @Test fun `set clamps to definition bounds and reset reports actual balance changes`() {
        val effects = """- {type: set_resource, resource: focus, value: 100, as: set}
- type: heal
  target: actor
  amount: {expr: 'result.set.current'}
- {type: reset_resource, resource: focus, as: reset}
- type: heal
  target: actor
  amount: {expr: 'result.reset.changed'}"""
        val world = World()
        val runtime = runtime(world, effects)
        assertEquals(CastResult.Applied, cast(runtime))
        assertEquals(listOf(10.0, 5.0), world.healing)
        assertEquals(5.0, runtime.record(actor).resources["workshop:fighter|workshop:focus"])
    }

    @Test fun `signed expression can set a negative declared balance`() {
        val runtime = runtime(World(), "- type: set_resource\n  resource: focus\n  value: {expr: '0 - 7'}")
        assertEquals(CastResult.Applied, cast(runtime))
        assertEquals(-7.0, runtime.record(actor).resources["workshop:fighter|workshop:focus"])
    }

    @Test fun `unknown resource fails compilation`() {
        val result = compile("- {type: reset_resource, resource: missing}") as CompileResult.Invalid
        assertTrue(result.diagnostics.any { it.problem.contains("unknown resource") })
    }
}
