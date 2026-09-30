package dev.archetype

import dev.archetype.definitions.*
import dev.archetype.minecraft.CombatBridge
import dev.archetype.runtime.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.util.UUID

class BreakOnDamageTest {
    private val target = UUID(0, 1)
    private val first = UUID(0, 2)
    private val second = UUID(0, 3)

    private class World : WorldOps {
        override fun validTarget(actor: UUID, target: UUID) = true
        override fun heal(target: UUID, amount: Double) = amount
        override fun damage(actor: UUID, target: UUID, amount: Double, damageType: String) = amount
        override fun position(entity: UUID) = Position("test", Vec(0.0, 0.0, 0.0))
        override fun loaded(position: Position) = true
    }

    private fun compile(rule: String = "{minimum_health_loss: 1}", broken: String = "[ {type: gain_resource, resource: focus, amount: 1} ]"): CompileResult {
        val files = listOf(
            "workshop/pack.yaml" to "format: 1\nid: workshop\nname: Workshop",
            "workshop/focus.yaml" to "kind: resource\nid: focus\nscope: player\nmin: 0\nmax: 100\ninitial: 0",
            "workshop/class.yaml" to """kind: class
id: scout
name: Scout
abilities:
  mark:
    definition:
      name: Mark
      effects: [{type: apply_status, id: mark_application, status: fragile, target: target}]""",
            "workshop/fragile.yaml" to """kind: status
id: fragile
duration: 10s
break_on_damage: $rule
broken: $broken""",
        )
        return ManifestCompiler().compile(PackSnapshot(files.map { SourceFile(it.first, it.second.toByteArray()) }, "v1"))
    }

    @Test fun `only observed health loss breaks overlapping contributions and callbacks run once`() {
        val compiled = compile()
        assertTrue(compiled is CompileResult.Valid, "$compiled")
        val runtime = AbilityRuntime(World())
        runtime.publish((compiled as CompileResult.Valid).definitions)
        listOf(target, first, second).forEach { runtime.selectClass(it, "workshop:scout") }
        fun mark(owner: UUID) = runtime.cast(owner, "workshop:scout", "mark", target, runtime.generation)
        assertEquals(CastResult.Applied, mark(first))
        assertEquals(CastResult.Applied, mark(second))
        assertEquals(2, runtime.statuses(target).size)
        CombatBridge.runtime = runtime
        try {
            CombatBridge.beginning(target, 10.0f)
            CombatBridge.committed(target, 10.0f)
            CombatBridge.finished(target)
            assertEquals(2, runtime.statuses(target).size)
            CombatBridge.beginning(target, 10.0f)
            CombatBridge.committed(target, 9.5f)
            CombatBridge.finished(target)
            assertEquals(2, runtime.statuses(target).size)
            CombatBridge.beginning(target, 10.0f)
            CombatBridge.committed(target, 9.0f)
            CombatBridge.finished(target)
            assertTrue(runtime.statuses(target).isEmpty())
            assertEquals(1.0, runtime.record(first).resources.values.single())
            assertEquals(1.0, runtime.record(second).resources.values.single())
            runtime.onNativeHealthLoss(target, 1.0)
            assertEquals(1.0, runtime.record(first).resources.values.single())
        } finally { CombatBridge.runtime = null }
    }

    @Test fun `break rule and callback validate at their fields`() {
        val negative = compile(rule = "{minimum_health_loss: -1}") as CompileResult.Invalid
        assertTrue(negative.diagnostics.any { it.field == "break_on_damage.minimum_health_loss" })
        val missing = compile(rule = "null") as CompileResult.Invalid
        assertTrue(missing.diagnostics.any { it.field == "broken" })
    }
}
