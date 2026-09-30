package dev.archetype

import dev.archetype.definitions.*
import dev.archetype.runtime.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.util.UUID

class RecastRuntimeTest {
    private val actor = UUID(0, 171)
    private class World : WorldOps {
        val heals = mutableListOf<Double>()
        override fun validTarget(actor: UUID, target: UUID) = true
        override fun heal(target: UUID, amount: Double) = amount.also(heals::add)
        override fun damage(actor: UUID, target: UUID, amount: Double, damageType: String) = amount
    }
    private fun compile(fingerprint: String = "v1", recastBody: String = "  - {type: heal, target: actor, amount: 5}"): CompileResult {
        val files = listOf(
            "workshop/pack.yaml" to "format: 1\nid: workshop\nname: Workshop",
            "workshop/resource.yaml" to "kind: resource\nid: focus\nscope: class\nmin: 0\nmax: 10\ninitial: 10",
            "workshop/ability.yaml" to """kind: ability
id: recall
name: Recall
activation: {type: recast, window: 500ms}
costs: [{resource: focus, amount: 2}]
cooldown: 2s
effects:
  - type: delay
    duration: 1s
    effects: [{type: heal, target: actor, amount: 1}]
recast_effects:
$recastBody""",
            "workshop/class.yaml" to "kind: class\nid: fighter\nname: Fighter\nabilities:\n  recall: {ref: recall}",
        )
        return ManifestCompiler().compile(PackSnapshot(files.map { SourceFile(it.first, it.second.toByteArray()) }, fingerprint))
    }
    @Test fun recastRunsSecondaryBodyOnceWithoutAnotherPaymentAndCancelsInitialWork() {
        val world = World()
        val runtime = AbilityRuntime(world)
        runtime.publish((compile() as CompileResult.Valid).definitions)
        runtime.selectClass(actor, "workshop:fighter")
        assertEquals(CastResult.Applied, runtime.cast(actor, "workshop:fighter", "recall", null, runtime.generation))
        assertTrue(runtime.recastActive(actor, "workshop:fighter", "recall"))
        assertEquals(8.0, runtime.record(actor).resources["workshop:fighter|workshop:focus"])
        assertEquals(CastResult.Applied, runtime.cast(actor, "workshop:fighter", "recall", null, runtime.generation))
        assertEquals(listOf(5.0), world.heals)
        assertFalse(runtime.recastActive(actor, "workshop:fighter", "recall"))
        assertEquals(8.0, runtime.record(actor).resources["workshop:fighter|workshop:focus"])
        repeat(25) { runtime.tick(listOf(actor)) }
        assertEquals(listOf(5.0), world.heals)
    }

    @Test fun timeoutAndAffectedReloadCancelUnspentRecastWindow() {
        val world = World()
        val runtime = AbilityRuntime(world)
        runtime.publish((compile() as CompileResult.Valid).definitions)
        runtime.selectClass(actor, "workshop:fighter")
        runtime.cast(actor, "workshop:fighter", "recall", null, runtime.generation)
        repeat(10) { runtime.tick(listOf(actor)) }
        assertFalse(runtime.recastActive(actor, "workshop:fighter", "recall"))
        repeat(31) { runtime.tick(listOf(actor)) }
        assertTrue(world.heals.isEmpty())
        runtime.cast(actor, "workshop:fighter", "recall", null, runtime.generation)
        assertTrue(runtime.recastActive(actor, "workshop:fighter", "recall"))
        val edited = compile("v2", "  - {type: heal, target: actor, amount: 6}") as CompileResult.Valid
        runtime.publish(edited.definitions)
        assertFalse(runtime.recastActive(actor, "workshop:fighter", "recall"))
    }

    @Test fun recastRequiresFiniteWindowAndSecondBody() {
        val invalid = compile(recastBody = "") as CompileResult.Invalid
        assertTrue(invalid.diagnostics.any { it.field == "recast_effects" })
    }
}
