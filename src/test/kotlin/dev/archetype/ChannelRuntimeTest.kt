package dev.archetype

import dev.archetype.definitions.*
import dev.archetype.runtime.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.util.UUID

class ChannelRuntimeTest {
    private val actor = UUID(0, 101)
    private class World : WorldOps {
        val heals = mutableListOf<Double>()
        override fun validTarget(actor: UUID, target: UUID) = true
        override fun heal(target: UUID, amount: Double) = amount.also(heals::add)
        override fun damage(actor: UUID, target: UUID, amount: Double, damageType: String) = amount
    }
    private fun compile(initial: Int = 5, activation: String = "{type: channel, every: 50ms, max_duration: 200ms, periodic_costs: [{resource: focus, amount: 1}]}"): CompileResult {
        val files = listOf(
            "workshop/pack.yaml" to "format: 1\nid: workshop\nname: Workshop",
            "workshop/focus.yaml" to "kind: resource\nid: focus\nscope: player\nmin: 0\nmax: 10\ninitial: $initial",
            "workshop/beam.yaml" to """kind: ability
id: beam
name: Beam
activation: $activation
costs: [{resource: focus, amount: 2}]
effects:
  - {type: heal, target: actor, amount: 1}""",
            "workshop/class.yaml" to "kind: class\nid: mage\nname: Mage\nabilities:\n  beam: {ref: beam}",
            "workshop/other.yaml" to "kind: class\nid: other\nname: Other\nabilities:\n  beam: {ref: beam}",
        )
        return ManifestCompiler().compile(PackSnapshot(files.map { SourceFile(it.first, it.second.toByteArray()) }, activation + initial))
    }
    private fun runtime(initial: Int, world: World): AbilityRuntime = AbilityRuntime(world).also {
        val compiled = compile(initial)
        assertTrue(compiled is CompileResult.Valid, "$compiled")
        it.publish((compiled as CompileResult.Valid).definitions)
        it.selectClass(actor, "workshop:mage")
    }

    @Test fun channelCommitsStartAndPeriodicCostsThenExpires() {
        val world = World()
        val runtime = runtime(5, world)
        assertEquals(CastResult.Applied, runtime.cast(actor, "workshop:mage", "beam", null, runtime.generation))
        assertEquals(listOf(1.0), world.heals)
        assertEquals(3.0, runtime.record(actor).resources["player|workshop:focus"])
        assertTrue(runtime.channelActive(actor, "workshop:mage", "beam"))
        repeat(3) { runtime.tick(listOf(actor)) }
        assertEquals(listOf(1.0, 1.0, 1.0, 1.0), world.heals)
        assertEquals(0.0, runtime.record(actor).resources["player|workshop:focus"])
        runtime.tick(listOf(actor))
        assertFalse(runtime.channelActive(actor, "workshop:mage", "beam"))
        assertEquals(4, world.heals.size)
    }

    @Test fun releaseAndInsufficientPeriodicResourceStopChannelWithoutRefund() {
        val world = World()
        val runtime = runtime(3, world)
        runtime.cast(actor, "workshop:mage", "beam", null, runtime.generation)
        assertFalse(runtime.releaseChannel(actor, "workshop:mage", "beam", runtime.generation + 1))
        runtime.tick(listOf(actor))
        assertEquals(0.0, runtime.record(actor).resources["player|workshop:focus"])
        runtime.tick(listOf(actor))
        assertFalse(runtime.channelActive(actor, "workshop:mage", "beam"))
        assertEquals(2, world.heals.size)

        val another = runtime(5, World())
        another.cast(actor, "workshop:mage", "beam", null, another.generation)
        assertTrue(another.releaseChannel(actor, "workshop:mage", "beam", another.generation))
        assertFalse(another.channelActive(actor, "workshop:mage", "beam"))
    }

    @Test fun invalidChannelSettingsAndReferencesAreLocated() {
        val interval = compile(activation = "{type: channel, every: 0ms}") as CompileResult.Invalid
        assertTrue(interval.diagnostics.any { it.field == "activation.every" })
        val badResource = compile(activation = "{type: channel, every: 50ms, periodic_costs: [{resource: missing, amount: 1}]}") as CompileResult.Invalid
        assertTrue(badResource.diagnostics.any { it.problem.contains("unknown resource") })
    }
}
