package dev.archetype

import dev.archetype.definitions.*
import dev.archetype.runtime.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.util.UUID

class ControlImmunityTest {
    private val actor = UUID(0, 1)
    private val other = UUID(0, 2)
    private val third = UUID(0, 3)

    private class World : WorldOps {
        var heals = 0
        override fun validTarget(actor: UUID, target: UUID) = true
        override fun heal(target: UUID, amount: Double): Double { heals++; return amount }
        override fun damage(actor: UUID, target: UUID, amount: Double, damageType: String) = amount
        override fun position(entity: UUID) = Position("test", Vec(0.0, 0.0, 0.0))
        override fun loaded(position: Position) = true
    }

    private fun compile(control: String = "[silence]", immunities: String = "[silence]", wardControl: String = ""): CompileResult {
        val files = listOf(
            "workshop/pack.yaml" to "format: 1\nid: workshop\nname: Workshop",
            "workshop/class.yaml" to """kind: class
id: fighter
name: Fighter
abilities:
  silence:
    definition:
      name: Silence
      effects: [{type: apply_status, id: silence_application, status: silence, target: target}]
  ward:
    definition:
      name: Ward
      effects: [{type: apply_status, id: ward_application, status: ward, target: target}]
  strike:
    definition:
      name: Strike
      effects: [{type: heal, target: actor, amount: 1}]""",
            "workshop/silence.yaml" to """kind: status
id: silence
duration: 500ms
control_categories: $control
restrictions: [activate]
expired: [{type: heal, target: target, amount: 1}]""",
            "workshop/ward.yaml" to """kind: status
id: ward
duration: 250ms
immunities: $immunities$wardControl""",
        )
        return ManifestCompiler().compile(PackSnapshot(files.map { SourceFile(it.first, it.second.toByteArray()) }, "v1"))
    }

    @Test fun `immunity clears overlapping control sources and blocks new applications until expiry`() {
        val compiled = compile()
        assertTrue(compiled is CompileResult.Valid, "$compiled")
        val world = World()
        val runtime = AbilityRuntime(world)
        runtime.publish((compiled as CompileResult.Valid).definitions)
        listOf(actor, other, third).forEach { runtime.selectClass(it, "workshop:fighter") }
        fun cast(player: UUID, grant: String, target: UUID? = null) =
            runtime.cast(player, "workshop:fighter", grant, target, runtime.generation)

        assertEquals(CastResult.Applied, cast(other, "silence", actor))
        assertEquals(CastResult.Applied, cast(third, "silence", actor))
        assertEquals(2, runtime.statuses(actor).count { it.status == "workshop:silence" })
        assertEquals(CastResult.Rejected("ability activation is restricted"), cast(actor, "strike"))
        assertEquals(CastResult.Applied, cast(other, "ward", actor))
        assertEquals(listOf("workshop:ward"), runtime.statuses(actor).map { it.status })
        assertEquals(0, world.heals) // Immunity cleansing does not run natural-expiry callbacks.
        assertEquals(CastResult.Applied, cast(third, "silence", actor))
        assertEquals(listOf("workshop:ward"), runtime.statuses(actor).map { it.status })
        assertEquals(CastResult.Applied, cast(actor, "strike"))

        repeat(5) { runtime.tick(listOf(actor, other, third)) }
        assertTrue(runtime.statuses(actor).isEmpty())
        assertEquals(CastResult.Applied, cast(third, "silence", actor))
        assertEquals(CastResult.Rejected("ability activation is restricted"), cast(actor, "strike"))
        assertEquals(1, world.heals)
    }

    @Test fun `control labels are bounded and a status cannot immunize against itself`() {
        val duplicate = compile(control = "[silence, silence]") as CompileResult.Invalid
        assertTrue(duplicate.diagnostics.any { it.field == "control_categories" })
        val empty = compile(immunities = "[]") as CompileResult.Invalid
        assertTrue(empty.diagnostics.any { it.field == "immunities" })
        val selfImmune = compile(wardControl = "\ncontrol_categories: [silence]") as CompileResult.Invalid
        assertTrue(selfImmune.diagnostics.any { it.field == "immunities" })
    }
}
