package dev.archetype

import dev.archetype.definitions.*
import dev.archetype.runtime.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.util.UUID

class PassiveRuntimeTest {
    private val player = UUID(0, 1)

    private class World : WorldOps {
        override fun validTarget(actor: UUID, target: UUID) = true
        override fun heal(target: UUID, amount: Double) = amount
        override fun damage(actor: UUID, target: UUID, amount: Double, damageType: String) = amount
        override fun position(entity: UUID) = Position("test", Vec(0.0, 0.0, 0.0))
        override fun loaded(position: Position) = true
    }

    private fun compile(extra: String = ""): CompileResult {
        val files = listOf(
            "workshop/pack.yaml" to "format: 1\nid: workshop\nname: Workshop",
            "workshop/focus.yaml" to "kind: resource\nid: focus\nscope: player\nmin: 0\nmax: 100\ninitial: 0",
            "workshop/boon.yaml" to "kind: status\nid: boon\nduration: 60m",
            "workshop/keeper.yaml" to """kind: class
id: keeper
name: Keeper
abilities:
  aura:
    definition:
      name: Aura
      activation: {type: passive}
      effects:
        - {type: apply_status, id: boon_application, status: boon, target: actor}
        - {type: gain_resource, resource: focus, amount: 1}
$extra""",
            "workshop/other.yaml" to "kind: class\nid: other\nname: Other\nabilities:\n  strike:\n    definition:\n      name: Strike\n      effects: [{type: heal, target: actor, amount: 1}]",
        )
        return ManifestCompiler().compile(PackSnapshot(files.map { SourceFile(it.first, it.second.toByteArray()) }, "v1"))
    }

    @Test fun `passive setup runs once per class lifetime and cleans up on switch death and reload`() {
        val compiled = compile()
        assertTrue(compiled is CompileResult.Valid, "$compiled")
        val runtime = AbilityRuntime(World())
        val definitions = (compiled as CompileResult.Valid).definitions
        runtime.publish(definitions)
        assertTrue(runtime.selectClass(player, "workshop:keeper"))
        assertEquals(1, runtime.statuses(player).size)
        assertEquals(1.0, runtime.record(player).resources.values.single())
        repeat(5) { runtime.tick(listOf(player)) }
        assertTrue(runtime.selectClass(player, "workshop:keeper"))
        assertEquals(1, runtime.statuses(player).size)
        assertEquals(1.0, runtime.record(player).resources.values.single())

        runtime.publish(definitions.copy(fingerprint = "unrelated-edit"))
        assertEquals(1, runtime.statuses(player).size)
        assertEquals(1.0, runtime.record(player).resources.values.single())
        assertTrue(runtime.selectClass(player, "workshop:other"))
        assertTrue(runtime.statuses(player).isEmpty())
        assertTrue(runtime.selectClass(player, "workshop:keeper"))
        assertEquals(1, runtime.statuses(player).size)
        assertEquals(2.0, runtime.record(player).resources.values.single())

        val keeper = definitions.classes.getValue("workshop:keeper")
        val aura = keeper.grants.getValue("aura")
        val changedAbility = aura.ability.copy(effects = aura.ability.effects + Effect.GainResource("workshop:focus", Numeric.Constant(2.0), null))
        val changed = definitions.copy(classes = definitions.classes + (keeper.id to keeper.copy(grants = keeper.grants + ("aura" to aura.copy(ability = changedAbility)))), fingerprint = "changed-ability")
        runtime.publish(changed)
        assertEquals(1, runtime.statuses(player).size)
        assertEquals(5.0, runtime.record(player).resources.values.single())
        runtime.onDeath(player)
        assertTrue(runtime.statuses(player).isEmpty())
        runtime.tick(listOf(player))
        assertEquals(1, runtime.statuses(player).size)
        assertEquals(3.0, runtime.record(player).resources.values.single())
    }

    @Test fun `passive manifests reject unused activation costs and targets`() {
        val badCosts = compile("      costs: [{resource: focus, amount: 1}]") as CompileResult.Invalid
        assertTrue(badCosts.diagnostics.any { it.field == "costs" })
        val badTarget = compile("      target: {type: entity}") as CompileResult.Invalid
        assertTrue(badTarget.diagnostics.any { it.field == "target" })
    }
}
