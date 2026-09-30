package dev.archetype

import dev.archetype.definitions.*
import dev.archetype.runtime.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.util.UUID

class ChargeRuntimeTest {
    private val actor = UUID(0, 111)
    private class World : WorldOps {
        val heals = mutableListOf<Double>()
        override fun validTarget(actor: UUID, target: UUID) = true
        override fun heal(target: UUID, amount: Double) = amount.also(heals::add)
        override fun damage(actor: UUID, target: UUID, amount: Double, damageType: String) = amount
    }
    private fun compile(activation: String = "{type: charge, min_hold: 50ms, max_hold: 150ms}",
        effects: String = "  - {type: heal, target: actor, amount: {expr: 'charge.held_ticks * 2'}}"): CompileResult {
        val files = listOf(
            "workshop/pack.yaml" to "format: 1\nid: workshop\nname: Workshop",
            "workshop/focus.yaml" to "kind: resource\nid: focus\nscope: player\nmin: 0\nmax: 10\ninitial: 5",
            "workshop/ability.yaml" to "kind: ability\nid: strike\nname: Strike\nactivation: $activation\ncooldown: 100ms\ncosts: [{resource: focus, amount: 2}]\neffects:\n$effects",
            "workshop/class.yaml" to "kind: class\nid: mage\nname: Mage\nabilities:\n  strike: {ref: strike}",
            "workshop/other.yaml" to "kind: class\nid: other\nname: Other\nabilities:\n  strike: {ref: strike}",
        )
        return ManifestCompiler().compile(PackSnapshot(files.map { SourceFile(it.first, it.second.toByteArray()) }, activation + effects))
    }
    private fun runtime(world: World): AbilityRuntime = AbilityRuntime(world).also {
        val compiled = compile()
        assertTrue(compiled is CompileResult.Valid, "$compiled")
        it.publish((compiled as CompileResult.Valid).definitions)
        it.selectClass(actor, "workshop:mage")
    }

    @Test fun chargePaysAndStartsCooldownOnlyOnValidRelease() {
        val world = World()
        val runtime = runtime(world)
        assertEquals(CastResult.Applied, runtime.cast(actor, "workshop:mage", "strike", null, runtime.generation))
        assertTrue(runtime.chargeActive(actor, "workshop:mage", "strike"))
        assertTrue(world.heals.isEmpty())
        assertNull(runtime.record(actor).resources["player|workshop:focus"])
        assertEquals(CastResult.Rejected("charge is not ready"), runtime.releaseCharge(actor, "workshop:mage", "strike", null, runtime.generation))
        assertFalse(runtime.chargeActive(actor, "workshop:mage", "strike"))
        assertEquals(CastResult.Applied, runtime.cast(actor, "workshop:mage", "strike", null, runtime.generation))
        runtime.tick(listOf(actor))
        assertEquals(CastResult.Applied, runtime.releaseCharge(actor, "workshop:mage", "strike", null, runtime.generation))
        assertEquals(listOf(2.0), world.heals)
        assertEquals(3.0, runtime.record(actor).resources["player|workshop:focus"])
        assertTrue(runtime.cooldownRemaining(actor, "workshop:mage", "strike") > 0)
    }

    @Test fun maximumHoldReleasesAutomaticallyAndClassSwitchCancelsAnotherHold() {
        val world = World()
        val runtime = runtime(world)
        runtime.cast(actor, "workshop:mage", "strike", null, runtime.generation)
        repeat(3) { runtime.tick(listOf(actor)) }
        assertEquals(listOf(6.0), world.heals)
        assertFalse(runtime.chargeActive(actor, "workshop:mage", "strike"))
        assertEquals(3.0, runtime.record(actor).resources["player|workshop:focus"])
        repeat(2) { runtime.tick(listOf(actor)) }
        runtime.cast(actor, "workshop:mage", "strike", null, runtime.generation)
        runtime.selectClass(actor, "workshop:other")
        assertFalse(runtime.chargeActive(actor, "workshop:mage", "strike"))
        repeat(3) { runtime.tick(listOf(actor)) }
        assertEquals(listOf(6.0), world.heals)
    }

    @Test fun chargeBindingsAreModeSpecificAndTimingIsBounded() {
        val ordinary = compile(activation = "{type: activated}") as CompileResult.Invalid
        assertTrue(ordinary.diagnostics.any { it.problem.contains("charge.held_ticks is not available") })
        val invalid = compile(activation = "{type: charge, min_hold: 200ms, max_hold: 100ms}") as CompileResult.Invalid
        assertTrue(invalid.diagnostics.any { it.field == "activation.max_hold" })
    }
}
