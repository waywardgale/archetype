package dev.archetype

import dev.archetype.definitions.*
import dev.archetype.runtime.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.util.UUID

class ToggleRuntimeTest {
    private val player = UUID(0, 91)
    private class World : WorldOps {
        val heals = mutableListOf<Double>()
        override fun validTarget(actor: UUID, target: UUID) = true
        override fun heal(target: UUID, amount: Double) = amount.also(heals::add)
        override fun damage(actor: UUID, target: UUID, amount: Double, damageType: String) = amount
    }
    private fun compile(effects: String = """  - {type: gain_resource, resource: focus, amount: 1}
  - {type: delay, duration: 100ms, effects: [{type: heal, target: actor, amount: 5}]}""",
        activation: String = "{type: toggle}"): CompileResult {
        val files = listOf(
            "workshop/pack.yaml" to "format: 1\nid: workshop\nname: Workshop",
            "workshop/focus.yaml" to "kind: resource\nid: focus\nscope: player\nmin: 0\nmax: 20\ninitial: 10",
            "workshop/toggle.yaml" to "kind: ability\nid: stance\nname: Stance\nactivation: $activation\ncooldown: 100ms\ncosts: [{resource: focus, amount: 2}]\neffects:\n$effects",
            "workshop/class.yaml" to "kind: class\nid: mage\nname: Mage\nabilities:\n  stance: {ref: stance}",
            "workshop/other.yaml" to "kind: class\nid: other\nname: Other\nabilities:\n  stance: {ref: stance}",
        )
        return ManifestCompiler().compile(PackSnapshot(files.map { SourceFile(it.first, it.second.toByteArray()) }, effects))
    }

    @Test fun secondPressEndsToggleWithoutNewCostAndCancelsOwnedWork() {
        val world = World()
        val runtime = AbilityRuntime(world)
        runtime.publish((compile() as CompileResult.Valid).definitions)
        runtime.selectClass(player, "workshop:mage")
        assertEquals(CastResult.Applied, runtime.cast(player, "workshop:mage", "stance", null, runtime.generation))
        assertTrue(runtime.toggleActive(player, "workshop:mage", "stance"))
        assertEquals(9.0, runtime.record(player).resources["player|workshop:focus"])
        assertEquals(CastResult.Applied, runtime.cast(player, "workshop:mage", "stance", null, runtime.generation))
        assertFalse(runtime.toggleActive(player, "workshop:mage", "stance"))
        assertEquals(9.0, runtime.record(player).resources["player|workshop:focus"])
        repeat(3) { runtime.tick(listOf(player)) }
        assertTrue(world.heals.isEmpty())
        assertEquals(CastResult.Applied, runtime.cast(player, "workshop:mage", "stance", null, runtime.generation))
        assertTrue(runtime.toggleActive(player, "workshop:mage", "stance"))
        runtime.selectClass(player, "workshop:other")
        assertFalse(runtime.toggleActive(player, "workshop:mage", "stance"))
        repeat(3) { runtime.tick(listOf(player)) }
        assertTrue(world.heals.isEmpty())
    }

    @Test fun invalidToggleSettingsAreLocated() {
        val invalid = compile(activation = "{type: toggle, every: 0ms}") as CompileResult.Invalid
        assertTrue(invalid.diagnostics.any { it.field == "activation.every" })
    }
}
