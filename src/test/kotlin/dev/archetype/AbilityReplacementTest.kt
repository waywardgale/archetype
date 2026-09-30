package dev.archetype

import dev.archetype.definitions.*
import dev.archetype.runtime.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.util.UUID

class AbilityReplacementTest {
    private val actor = UUID(0, 131)
    private class World : WorldOps {
        val heals = mutableListOf<Double>()
        override fun validTarget(actor: UUID, target: UUID) = true
        override fun heal(target: UUID, amount: Double) = amount.also(heals::add)
        override fun damage(actor: UUID, target: UUID, amount: Double, damageType: String) = amount
    }
    private fun compile(fingerprint: String = "v1", status: String = """kind: status
id: form
duration: 100ms
modifiers:
  - {type: replace, target: {ability: primary}, replacement: {ref: alternate}, priority: 10}"""): CompileResult {
        val files = listOf(
            "workshop/pack.yaml" to "format: 1\nid: workshop\nname: Workshop",
            "workshop/focus.yaml" to "kind: resource\nid: focus\nscope: player\nmin: 0\nmax: 10\ninitial: 10",
            "workshop/primary.yaml" to """kind: ability
id: primary
name: Primary
charges: {max: 2, recharge: 10s}
costs: [{resource: focus, amount: 1}]
effects:
  - {type: apply_status, status: form, target: actor}""",
            "workshop/alternate.yaml" to """kind: ability
id: alternate
name: Alternate
charges: {max: 2, recharge: 10s}
costs: [{resource: focus, amount: 2}]
effects:
  - {type: heal, target: actor, amount: 5}""",
            "workshop/form.yaml" to status,
            "workshop/class.yaml" to "kind: class\nid: mage\nname: Mage\nabilities:\n  primary: {ref: primary}",
            "workshop/other.yaml" to "kind: class\nid: other\nname: Other\nabilities:\n  primary: {ref: primary}",
        )
        return ManifestCompiler().compile(PackSnapshot(files.map { SourceFile(it.first, it.second.toByteArray()) }, fingerprint))
    }

    @Test fun formReplacesCompleteAbilityAndRestoresLogicalGrantState() {
        val world = World()
        val runtime = AbilityRuntime(world)
        runtime.publish((compile() as CompileResult.Valid).definitions)
        runtime.selectClass(actor, "workshop:mage")
        assertEquals(CastResult.Applied, runtime.cast(actor, "workshop:mage", "primary", null, runtime.generation))
        assertEquals("workshop:alternate", runtime.effectiveAbility(actor, "workshop:mage", "primary")?.id)
        assertEquals(1, runtime.chargeState(actor, "workshop:mage", "primary")?.available)
        assertEquals(CastResult.Applied, runtime.cast(actor, "workshop:mage", "primary", null, runtime.generation))
        assertEquals(listOf(5.0), world.heals)
        assertEquals(7.0, runtime.record(actor).resources["player|workshop:focus"])
        assertEquals(0, runtime.chargeState(actor, "workshop:mage", "primary")?.available)
        runtime.publish((compile("v2") as CompileResult.Valid).definitions)
        assertEquals("workshop:alternate", runtime.effectiveAbility(actor, "workshop:mage", "primary")?.id)
        repeat(2) { runtime.tick(listOf(actor)) }
        assertEquals("workshop:primary", runtime.effectiveAbility(actor, "workshop:mage", "primary")?.id)
        assertEquals(0, runtime.chargeState(actor, "workshop:mage", "primary")?.available)
    }

    @Test fun classChangeEndsFormAndInvalidReplacementIsRejected() {
        val runtime = AbilityRuntime(World())
        runtime.publish((compile() as CompileResult.Valid).definitions)
        runtime.selectClass(actor, "workshop:mage")
        runtime.cast(actor, "workshop:mage", "primary", null, runtime.generation)
        runtime.selectClass(actor, "workshop:other")
        assertEquals("workshop:primary", runtime.effectiveAbility(actor, "workshop:other", "primary")?.id)
        val bad = compile(status = """kind: status
id: form
duration: 1s
modifiers:
  - {type: replace, target: {ability: primary}, replacement: {ref: missing}}""") as CompileResult.Invalid
        assertTrue(bad.diagnostics.any { it.problem.contains("unknown ability") })
    }

    @Test fun selfAppliedFormStopsOldToggleWorkButKeepsItsTimedContribution() {
        val files = listOf(
            "workshop/pack.yaml" to "format: 1\nid: workshop\nname: Workshop",
            "workshop/primary.yaml" to """kind: ability
id: primary
name: Primary
activation: {type: toggle}
effects:
  - {type: apply_status, status: form, target: actor}
  - {type: delay, duration: 50ms, effects: [{type: heal, target: actor, amount: 77}]}""",
            "workshop/alternate.yaml" to "kind: ability\nid: alternate\nname: Alternate\neffects:\n  - {type: heal, target: actor, amount: 5}",
            "workshop/form.yaml" to """kind: status
id: form
duration: 100ms
modifiers:
  - {type: replace, target: {ability: primary}, replacement: {ref: alternate}, priority: 10}""",
            "workshop/class.yaml" to "kind: class\nid: mage\nname: Mage\nabilities:\n  primary: {ref: primary}",
        )
        val compiled = ManifestCompiler().compile(PackSnapshot(files.map { SourceFile(it.first, it.second.toByteArray()) }, "self-form"))
        assertTrue(compiled is CompileResult.Valid, "$compiled")
        val world = World()
        val runtime = AbilityRuntime(world)
        runtime.publish((compiled as CompileResult.Valid).definitions)
        runtime.selectClass(actor, "workshop:mage")
        assertEquals(CastResult.Applied, runtime.cast(actor, "workshop:mage", "primary", null, runtime.generation))
        assertFalse(runtime.toggleActive(actor, "workshop:mage", "primary"))
        assertEquals(1, runtime.statuses(actor).size)
        assertEquals("workshop:alternate", runtime.effectiveAbility(actor, "workshop:mage", "primary")?.id)
        assertEquals(CastResult.Applied, runtime.cast(actor, "workshop:mage", "primary", null, runtime.generation))
        repeat(2) { runtime.tick(listOf(actor)) }
        assertEquals(listOf(5.0), world.heals)
        assertEquals("workshop:primary", runtime.effectiveAbility(actor, "workshop:mage", "primary")?.id)
    }
}
