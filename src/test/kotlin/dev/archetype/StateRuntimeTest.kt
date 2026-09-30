package dev.archetype

import dev.archetype.definitions.*
import dev.archetype.runtime.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.util.UUID

class StateRuntimeTest {
    private val player = UUID(0, 42)
    private class World : WorldOps {
        val heals = mutableListOf<Double>()
        override fun validTarget(actor: UUID, target: UUID) = true
        override fun heal(target: UUID, amount: Double) = amount.also { heals += it }
        override fun damage(actor: UUID, target: UUID, amount: Double, damageType: String) = amount
    }

    private fun compile(ability: String, state: String = """kind: state
id: stance
scope: class
fields:
  mode: {type: enum, values: [steady, mobile], initial: steady}
  hits: {type: integer, min: 0, max: 3, initial: 0}"""): CompileResult {
        val files = listOf(
            "workshop/pack.yaml" to "format: 1\nid: workshop\nname: Workshop",
            "workshop/stance.yaml" to state,
            "workshop/ability.yaml" to "kind: ability\nid: strike\nname: Strike\neffects:\n$ability",
            "workshop/class.yaml" to "kind: class\nid: fighter\nname: Fighter\nabilities:\n  strike: {ref: strike}",
            "workshop/other.yaml" to "kind: class\nid: other\nname: Other\nabilities:\n  strike: {ref: strike}",
        )
        return ManifestCompiler().compile(PackSnapshot(files.map { SourceFile(it.first, it.second.toByteArray()) }, ability + state))
    }

    @Test fun `class state is bounded, branches on modes, and resets after deactivation`() {
        val source = """  - {type: add_state, state: stance, field: hits, amount: 2, as: count}
  - {type: set_state, state: stance, field: mode, value: mobile}
  - type: branch
    when: {type: state_is, state: stance, field: mode, value: mobile}
    then:
      - {type: add_state, state: stance, field: hits, amount: 2}
  - {type: read_state, state: stance, field: hits, as: final}
  - {type: heal, target: actor, amount: {expr: "result.final.value"}}"""
        val definitions = (compile(source) as CompileResult.Valid).definitions
        val runtime = AbilityRuntime(World())
        runtime.publish(definitions)
        assertTrue(runtime.selectClass(player, "workshop:fighter"))
        assertEquals(CastResult.Applied, runtime.cast(player, "workshop:fighter", "strike", null, runtime.generation))
        assertEquals(StateValue.Number(3.0), runtime.readDeclaredState(player, "workshop:fighter", "workshop:stance", "hits"))
        assertEquals(StateValue.Mode("mobile"), runtime.readDeclaredState(player, "workshop:fighter", "workshop:stance", "mode"))
        assertEquals(CastResult.Applied, runtime.cast(player, "workshop:fighter", "strike", null, runtime.generation))
        assertEquals(StateValue.Number(3.0), runtime.readDeclaredState(player, "workshop:fighter", "workshop:stance", "hits"))
        runtime.selectClass(player, "workshop:fighter")
        assertEquals(StateValue.Number(3.0), runtime.readDeclaredState(player, "workshop:fighter", "workshop:stance", "hits"))
        runtime.selectClass(player, "workshop:other")
        runtime.selectClass(player, "workshop:fighter")
        assertEquals(StateValue.Number(0.0), runtime.readDeclaredState(player, "workshop:fighter", "workshop:stance", "hits"))
        runtime.onLogout(player)
        assertEquals(StateValue.Number(0.0), runtime.readDeclaredState(player, "workshop:fighter", "workshop:stance", "hits"))
    }

    @Test fun `persistent class state survives deactivation and compatible bound edits`() {
        val ability = "  - {type: add_state, state: stance, field: hits, amount: 2}"
        val persistent = """kind: state
id: stance
scope: class
persistent: true
fields:
  hits: {type: integer, min: 0, max: 3, initial: 0}"""
        val runtime = AbilityRuntime(World())
        runtime.publish((compile(ability, persistent) as CompileResult.Valid).definitions)
        runtime.selectClass(player, "workshop:fighter")
        assertEquals(CastResult.Applied, runtime.cast(player, "workshop:fighter", "strike", null, runtime.generation))
        runtime.selectClass(player, "workshop:other")
        assertEquals(StateValue.Number(2.0), runtime.readDeclaredState(player, "workshop:fighter", "workshop:stance", "hits"))
        val changed = persistent.replace("max: 3", "max: 1")
        runtime.publish((compile(ability, changed) as CompileResult.Valid).definitions)
        assertEquals(StateValue.Number(1.0), runtime.readDeclaredState(player, "workshop:fighter", "workshop:stance", "hits"))
        val incompatible = changed.replace("type: integer", "type: number")
        val generation = runtime.generation
        assertThrows(IllegalArgumentException::class.java) {
            runtime.publish((compile(ability, incompatible) as CompileResult.Valid).definitions)
        }
        assertEquals(generation, runtime.generation)
        assertEquals(StateValue.Number(1.0), runtime.readDeclaredState(player, "workshop:fighter", "workshop:stance", "hits"))
    }

    @Test fun `activation state does not leak into a later cast`() {
        val state = """kind: state
id: local
scope: activation
fields:
  hits: {type: integer, min: 0, max: 3, initial: 0}"""
        val ability = """  - {type: read_state, state: local, field: hits, as: before}
  - {type: heal, target: actor, amount: {expr: "result.before.value"}}
  - {type: add_state, state: local, field: hits, amount: 1}"""
        val world = World()
        val runtime = AbilityRuntime(world)
        runtime.publish((compile(ability, state) as CompileResult.Valid).definitions)
        runtime.selectClass(player, "workshop:fighter")
        repeat(2) { assertEquals(CastResult.Applied, runtime.cast(player, "workshop:fighter", "strike", null, runtime.generation)) }
        assertEquals(listOf(0.0, 0.0), world.heals)
        assertNull(runtime.readDeclaredState(player, "workshop:fighter", "workshop:local", "hits"))
    }

    @Test fun `transient player state is shared across classes and resets on death`() {
        val state = """kind: state
id: shared
scope: player
fields:
  hits: {type: integer, min: 0, max: 3, initial: 0}"""
        val ability = "  - {type: add_state, state: shared, field: hits, amount: 1}"
        val runtime = AbilityRuntime(World())
        runtime.publish((compile(ability, state) as CompileResult.Valid).definitions)
        runtime.selectClass(player, "workshop:fighter")
        assertEquals(CastResult.Applied, runtime.cast(player, "workshop:fighter", "strike", null, runtime.generation))
        runtime.selectClass(player, "workshop:other")
        assertEquals(StateValue.Number(1.0), runtime.readDeclaredState(player, "workshop:other", "workshop:shared", "hits"))
        runtime.onDeath(player)
        assertEquals(StateValue.Number(0.0), runtime.readDeclaredState(player, "workshop:other", "workshop:shared", "hits"))
    }

    @Test fun `expressions read fresh declared state and reject missing fields`() {
        val ability = """  - {type: add_state, state: stance, field: hits, amount: 1}
  - {type: heal, target: actor, amount: {expr: "state.workshop.stance.hits * 2"}}"""
        val world = World()
        val runtime = AbilityRuntime(world)
        runtime.publish((compile(ability) as CompileResult.Valid).definitions)
        runtime.selectClass(player, "workshop:fighter")
        repeat(2) { assertEquals(CastResult.Applied, runtime.cast(player, "workshop:fighter", "strike", null, runtime.generation)) }
        assertEquals(listOf(2.0, 4.0), world.heals)
        val invalid = compile(ability.replace("stance.hits", "stance.missing")) as CompileResult.Invalid
        assertTrue(invalid.diagnostics.any { it.problem.contains("unknown state variable") })
    }

    @Test fun `invalid state usage is rejected before publication`() {
        val bad = compile("  - {type: add_state, state: stance, field: mode, amount: 1}") as CompileResult.Invalid
        assertTrue(bad.diagnostics.any { it.problem.contains("numeric field") })
        val wrongMode = compile("  - {type: set_state, state: stance, field: mode, value: unknown}") as CompileResult.Invalid
        assertTrue(wrongMode.diagnostics.any { it.problem.contains("does not match") })
        val badState = compile("  - {type: read_state, state: stance, field: hits}", "kind: state\nid: stance\nscope: activation\npersistent: true\nfields:\n  hits: {type: integer, min: 0, max: 3, initial: 0}") as CompileResult.Invalid
        assertTrue(badState.diagnostics.any { it.field == "persistent" })
    }
}
