package dev.archetype

import dev.archetype.definitions.*
import dev.archetype.runtime.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.util.UUID

class CooldownGroupTest {
    private val actor = UUID(0, 1)
    private class World : WorldOps {
        override fun validTarget(actor: UUID, target: UUID) = actor == target
        override fun heal(target: UUID, amount: Double) = amount
        override fun damage(actor: UUID, target: UUID, amount: Double, damageType: String) = amount
    }
    private fun compile(fighterCooldown: String, mageCooldown: String, fingerprint: String = "v1"): CompileResult {
        fun klass(id: String, grant: String, cooldown: String) = """kind: class
id: $id
name: $id
abilities:
  $grant:
    definition:
      name: $grant
      cooldown: $cooldown
      effects: [{type: heal, target: actor, amount: 1}]"""
        val files = listOf(
            "workshop/pack.yaml" to "format: 1\nid: workshop\nname: Workshop",
            "workshop/fighter.yaml" to klass("fighter", "strike", fighterCooldown),
            "workshop/mage.yaml" to klass("mage", "spell", mageCooldown),
        )
        return ManifestCompiler().compile(PackSnapshot(files.map { SourceFile(it.first, it.second.toByteArray()) }, fingerprint))
    }
    private fun runtime(result: CompileResult): AbilityRuntime {
        assertTrue(result is CompileResult.Valid, "$result")
        return AbilityRuntime(World()).also {
            it.publish((result as CompileResult.Valid).definitions)
            it.selectClass(actor, "workshop:fighter")
        }
    }
    private fun cast(runtime: AbilityRuntime, classId: String, grant: String) = runtime.cast(actor, "workshop:$classId", grant, null, runtime.generation)
    private fun ticks(runtime: AbilityRuntime, count: Int) { repeat(count) { runtime.tick(listOf(actor)) } }

    @Test fun `named group blocks another class while its own timer stays separate`() {
        val runtime = runtime(compile("{duration: 100ms, groups: [shared]}", "{duration: 100ms, groups: [shared]}"))
        assertEquals(CastResult.Applied, cast(runtime, "fighter", "strike"))
        runtime.selectClass(actor, "workshop:mage")
        assertEquals(2, runtime.cooldownRemaining(actor, "workshop:mage", "spell"))
        assertEquals(CastResult.Rejected("cooldown is active"), cast(runtime, "mage", "spell"))
        ticks(runtime, 2)
        assertEquals(CastResult.Applied, cast(runtime, "mage", "spell"))
        assertEquals(2, runtime.record(actor).cooldowns["group|workshop:shared"])
    }

    @Test fun `global cooldown blocks only grants that opt in`() {
        val runtime = runtime(compile("{duration: 200ms, global: 100ms}", "{duration: 200ms, global: 100ms}"))
        cast(runtime, "fighter", "strike")
        runtime.selectClass(actor, "workshop:mage")
        assertEquals(CastResult.Rejected("cooldown is active"), cast(runtime, "mage", "spell"))
        ticks(runtime, 2)
        assertEquals(CastResult.Applied, cast(runtime, "mage", "spell"))

        val separate = runtime(compile("{duration: 200ms, global: 100ms}", "200ms"))
        cast(separate, "fighter", "strike")
        separate.selectClass(actor, "workshop:mage")
        assertEquals(CastResult.Applied, cast(separate, "mage", "spell"))
    }

    @Test fun `reload keeps remaining group time while changing future duration`() {
        val runtime = runtime(compile("{duration: 100ms, groups: [shared]}", "{duration: 100ms, groups: [shared]}"))
        cast(runtime, "fighter", "strike")
        val newDefinitions = compile("{duration: 1s, groups: [shared]}", "{duration: 1s, groups: [shared]}", "v2") as CompileResult.Valid
        runtime.publish(newDefinitions.definitions)
        assertEquals(2, runtime.cooldownRemaining(actor, "workshop:mage", "spell"))
        ticks(runtime, 2)
        runtime.selectClass(actor, "workshop:mage")
        cast(runtime, "mage", "spell")
        assertEquals(20, runtime.record(actor).cooldowns["group|workshop:shared"])
    }

    @Test fun `malformed structured cooldowns have located diagnostics`() {
        val invalid = listOf(
            "{duration: 0s, groups: [shared]}" to "cooldown.duration",
            "{duration: 1s, groups: [shared, shared]}" to "cooldown.groups",
            "{duration: 1s, global: bad}" to "cooldown.global",
        )
        for ((value, field) in invalid) {
            val result = compile(value, "1s")
            assertTrue(result is CompileResult.Invalid)
            assertTrue((result as CompileResult.Invalid).diagnostics.any { it.field == field }, "$result")
        }
    }
}
