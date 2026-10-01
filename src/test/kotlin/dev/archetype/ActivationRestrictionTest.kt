package dev.archetype

import dev.archetype.definitions.*
import dev.archetype.runtime.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.util.UUID

class ActivationRestrictionTest {
    private val actor = UUID(0, 1)
    private val other = UUID(0, 2)
    private val third = UUID(0, 3)
    private class World : WorldOps {
        var heals = 0
        var displacements = 0
        override fun validTarget(actor: UUID, target: UUID) = true
        override fun heal(target: UUID, amount: Double): Double { heals++; return amount }
        override fun damage(actor: UUID, target: UUID, amount: Double, damageType: String) = amount
        override fun position(entity: UUID) = Position("test", Vec(0.0, 0.0, 0.0))
        override fun loaded(position: Position) = true
        override fun displace(entity: UUID, direction: Vec, distance: Double): MotionResult {
            displacements++
            return MotionResult(distance, false)
        }
    }
    private fun compile(restrictions: String = "[activate]"): CompileResult {
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
  strike:
    definition:
      name: Strike
      effects: [{type: heal, target: actor, amount: 1}]
  dash:
    definition:
      name: Dash
      effects: [{type: dash, distance: 4}]""",
            "workshop/status.yaml" to "kind: status\nid: silence\nduration: 500ms\nrestrictions: $restrictions",
        )
        return ManifestCompiler().compile(PackSnapshot(files.map { SourceFile(it.first, it.second.toByteArray()) }, "v1"))
    }
    private fun runtime(world: World, restrictions: String = "[activate]"): AbilityRuntime {
        val result = compile(restrictions)
        assertTrue(result is CompileResult.Valid, "$result")
        return AbilityRuntime(world).also {
            it.publish((result as CompileResult.Valid).definitions)
            listOf(actor, other, third).forEach { player -> it.selectClass(player, "workshop:fighter") }
        }
    }
    private fun cast(runtime: AbilityRuntime, player: UUID, grant: String, target: UUID? = null) =
        runtime.cast(player, "workshop:fighter", grant, target, runtime.generation)

    @Test fun `overlapping silences block casts until every source ends`() {
        val world = World()
        val runtime = runtime(world)
        assertEquals(CastResult.Applied, cast(runtime, other, "silence", actor))
        assertEquals(CastResult.Applied, cast(runtime, third, "silence", actor))
        assertEquals(2, runtime.statuses(actor).size)
        assertEquals(CastResult.Rejected("ability activation is restricted"), cast(runtime, actor, "strike"))
        assertEquals(0, world.heals)
        runtime.onLogout(other)
        assertEquals(CastResult.Rejected("ability activation is restricted"), cast(runtime, actor, "strike"))
        runtime.onLogout(third)
        assertEquals(CastResult.Applied, cast(runtime, actor, "strike"))
        assertEquals(1, world.heals)
    }

    @Test fun `unsupported action restrictions fail at the authoring field`() {
        val result = compile("[fly]") as CompileResult.Invalid
        assertTrue(result.diagnostics.any { it.field == "restrictions[0]" })
    }

    @Test fun `movement jump and attack restrictions compose and moving status blocks authored dash`() {
        val world = World()
        val runtime = runtime(world, "[move, jump, attack]")
        assertEquals(CastResult.Applied, cast(runtime, other, "silence", actor))
        assertTrue(runtime.isRestricted(actor, ActionRestriction.MOVE))
        assertTrue(runtime.isRestricted(actor, ActionRestriction.JUMP))
        assertTrue(runtime.isRestricted(actor, ActionRestriction.ATTACK))
        assertFalse(runtime.isRestricted(actor, ActionRestriction.ACTIVATE))
        assertEquals(CastResult.Applied, cast(runtime, actor, "dash"))
        assertEquals(0, world.displacements)
        runtime.onLogout(other)
        assertFalse(runtime.isRestricted(actor, ActionRestriction.MOVE))
        assertEquals(CastResult.Applied, cast(runtime, actor, "dash"))
        assertEquals(1, world.displacements)
    }

    @Test fun `duplicate action restrictions are rejected`() {
        val result = compile("[move, move]") as CompileResult.Invalid
        assertTrue(result.diagnostics.any { it.field == "restrictions" })
    }
}
