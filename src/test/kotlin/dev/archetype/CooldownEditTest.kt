package dev.archetype

import dev.archetype.definitions.*
import dev.archetype.runtime.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.util.UUID

class CooldownEditTest {
    private val actor = UUID(0, 1)
    private class World : WorldOps {
        override fun validTarget(actor: UUID, target: UUID) = actor == target
        override fun heal(target: UUID, amount: Double) = amount
        override fun damage(actor: UUID, target: UUID, amount: Double, damageType: String) = amount
    }
    private fun compile(edits: Map<String, String>, capacity: Int = 3, cooldown: String? = null): CompileResult {
        val burst = """  burst:
    definition:
      name: Burst
      charges: {max: $capacity, recharge: 200ms, mode: parallel}
      ${cooldown?.let { "cooldown: $it" } ?: ""}
      effects: [{type: heal, target: actor, amount: 1}]"""
        val others = edits.entries.joinToString("\n") { (name, body) ->
            "  $name:\n    definition:\n      name: $name\n      effects:\n${body.prependIndent("        ")}"
        }
        val files = listOf(
            "workshop/pack.yaml" to "format: 1\nid: workshop\nname: Workshop",
            "workshop/class.yaml" to "kind: class\nid: fighter\nname: Fighter\nabilities:\n$burst\n$others",
        )
        return ManifestCompiler().compile(PackSnapshot(files.map { SourceFile(it.first, it.second.toByteArray()) }, "v1"))
    }
    private fun runtime(edits: Map<String, String>, capacity: Int = 3, cooldown: String? = null): AbilityRuntime {
        val result = compile(edits, capacity, cooldown)
        assertTrue(result is CompileResult.Valid, "$result")
        return AbilityRuntime(World()).also {
            it.publish((result as CompileResult.Valid).definitions)
            it.selectClass(actor, "workshop:fighter")
        }
    }
    private fun cast(runtime: AbilityRuntime, grant: String) = runtime.cast(actor, "workshop:fighter", grant, null, runtime.generation)
    private fun tick(runtime: AbilityRuntime) = runtime.tick(listOf(actor))
    private fun charge(runtime: AbilityRuntime) = runtime.chargeState(actor, "workshop:fighter", "burst")!!

    @Test fun `earliest latest and all edit only selected recharge timers`() {
        val runtime = runtime(mapOf(
            "latest" to "- type: reduce_recharge\n  grant: burst\n  amount: 200ms\n  which: latest",
            "all" to "- type: reduce_recharge\n  grant: burst\n  amount: 100ms\n  which: all",
            "earliest" to "- type: reduce_recharge\n  grant: burst\n  amount: 50ms\n  which: earliest",
        ))
        cast(runtime, "burst")
        tick(runtime)
        cast(runtime, "burst")
        tick(runtime)
        cast(runtime, "burst")
        assertEquals(listOf(2, 3, 4), charge(runtime).timers)
        assertEquals(CastResult.Applied, cast(runtime, "latest"))
        assertEquals(1, charge(runtime).available)
        assertEquals(listOf(2, 3), charge(runtime).timers)
        cast(runtime, "all")
        assertEquals(2, charge(runtime).available)
        assertEquals(listOf(1), charge(runtime).timers)
        cast(runtime, "earliest")
        assertEquals(3, charge(runtime).available)
        assertTrue(charge(runtime).timers.isEmpty())
        tick(runtime)
        assertEquals(3, charge(runtime).available)
    }

    @Test fun `named grant group and global edits clear each timer without refilling another use`() {
        val edits = """- {type: reduce_cooldown, grant: burst, amount: 200ms}
- {type: reduce_group_cooldown, group: shared, amount: 200ms}
- {type: reduce_global_cooldown, amount: 100ms}"""
        val runtime = runtime(mapOf("edit" to edits), capacity = 2, cooldown = "{duration: 200ms, groups: [shared], global: 100ms}")
        assertEquals(CastResult.Applied, cast(runtime, "burst"))
        assertEquals(1, charge(runtime).available)
        assertEquals(4, runtime.cooldownRemaining(actor, "workshop:fighter", "burst"))
        assertEquals(CastResult.Applied, cast(runtime, "edit"))
        assertEquals(0, runtime.cooldownRemaining(actor, "workshop:fighter", "burst"))
        assertEquals(1, charge(runtime).available)
        assertEquals(CastResult.Applied, cast(runtime, "burst"))
    }

    @Test fun `another grant restores one use and removes its pending recharge`() {
        val runtime = runtime(mapOf("refill" to "- {type: restore_charge, grant: burst, count: 1}"), capacity = 1)
        cast(runtime, "burst")
        assertEquals(0, charge(runtime).available)
        assertEquals(CastResult.Applied, cast(runtime, "refill"))
        assertEquals(1, charge(runtime).available)
        assertTrue(charge(runtime).timers.isEmpty())
        repeat(4) { tick(runtime) }
        assertEquals(1, charge(runtime).available)
    }

    @Test fun `unknown grants groups and recharge choices receive located diagnostics`() {
        val cases = listOf(
            "- {type: restore_charge, grant: missing}" to "unknown logical grant",
            "- {type: reduce_group_cooldown, group: missing, amount: 1s}" to "unknown cooldown group",
            "- {type: reduce_recharge, grant: burst, amount: 1s, which: random}" to "supported values",
        )
        for ((effect, message) in cases) {
            val result = compile(mapOf("edit" to effect)) as CompileResult.Invalid
            assertTrue(result.diagnostics.any { it.problem.contains(message) }, "$result")
        }
    }
}
