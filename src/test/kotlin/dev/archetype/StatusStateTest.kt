package dev.archetype

import dev.archetype.definitions.*
import dev.archetype.runtime.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.util.UUID

class StatusStateTest {
    private val first = UUID(0, 71)
    private val second = UUID(0, 72)
    private val recipient = UUID(0, 73)

    private class World : WorldOps {
        val heals = mutableListOf<Pair<UUID, Double>>()
        val damages = mutableListOf<Pair<UUID, Double>>()
        override fun validTarget(actor: UUID, target: UUID) = true
        override fun heal(target: UUID, amount: Double) = amount.also { heals += target to it }
        override fun damage(actor: UUID, target: UUID, amount: Double, damageType: String) = amount.also { damages += target to it }
    }

    private fun compile(ability: String = """kind: ability
id: apply_mark
name: Mark
effects:
  - {type: apply_status, id: mark, status: mark, target: target}""",
        state: String = """kind: state
id: memory
scope: status
fields:
  hits: {type: integer, min: 0, max: 10, initial: 0}""",
        status: String = """kind: status
id: mark
state: memory
duration: 1s
applied:
  - {type: set_state, state: memory, field: hits, value: 1}
periodic:
  every: 100ms
  effects:
    - {type: add_state, state: memory, field: hits, amount: 1}
    - {type: heal, target: target, amount: {expr: "state.workshop.memory.hits"}}"""): CompileResult {
        val files = listOf(
            "workshop/pack.yaml" to "format: 1\nid: workshop\nname: Workshop",
            "workshop/mark.yaml" to ability,
            "workshop/read.yaml" to """kind: ability
id: read_mark
name: Read mark
effects:
  - {type: read_status_state, state: memory, field: hits, target: target, status: mark, source: actor, as: memory}
  - {type: heal, target: actor, amount: {expr: "result.memory.value"}}""",
            "workshop/write.yaml" to """kind: ability
id: write_mark
name: Write mark
effects:
  - {type: write_status_state, state: memory, field: hits, target: target, status: mark, source: actor, operation: add, amount: 3, as: changed}
  - {type: heal, target: actor, amount: {expr: "result.changed.contributions"}}""",
            "workshop/reset.yaml" to """kind: ability
id: reset_mark
name: Reset mark
effects:
  - {type: write_status_state, state: memory, field: hits, target: target, status: mark, source: actor, operation: reset}""",
            "workshop/detonate.yaml" to """kind: ability
id: detonate
name: Detonate
effects:
  - {type: read_status_state, state: memory, field: hits, target: target, status: mark, source: actor, as: memory}
  - {type: consume_status, target: target, status: mark, source: actor, count: 1, as: removed}
  - type: branch
    when: {type: compare, left: {expr: "result.removed.contributions_removed"}, op: gt, right: 0}
    then:
      - {type: damage, target: target, amount: {expr: "result.memory.value"}, damage_type: minecraft:magic}""",
            "workshop/memory.yaml" to state,
            "workshop/status.yaml" to status,
            "workshop/class.yaml" to "kind: class\nid: keeper\nname: Keeper\nabilities:\n  mark: {ref: apply_mark}\n  read: {ref: read_mark}\n  write: {ref: write_mark}\n  reset: {ref: reset_mark}\n  detonate: {ref: detonate}",
        )
        return ManifestCompiler().compile(PackSnapshot(files.map { SourceFile(it.first, it.second.toByteArray()) }, ability + state + status))
    }

    @Test fun independentContributionsKeepSeparateStateAcrossRefresh() {
        val compiled = compile()
        assertTrue(compiled is CompileResult.Valid, "$compiled")
        val world = World()
        val runtime = AbilityRuntime(world)
        runtime.publish((compiled as CompileResult.Valid).definitions)
        runtime.selectClass(first, "workshop:keeper")
        runtime.selectClass(second, "workshop:keeper")
        fun cast(owner: UUID) = runtime.cast(owner, "workshop:keeper", "mark", recipient, runtime.generation)
        assertEquals(CastResult.Applied, cast(first))
        assertEquals(CastResult.Applied, cast(second))
        repeat(2) { runtime.tick(listOf(first, second)) }
        assertEquals(listOf(recipient to 2.0, recipient to 2.0), world.heals)
        assertEquals(CastResult.Applied, runtime.cast(first, "workshop:keeper", "read", recipient, runtime.generation))
        assertEquals(first to 2.0, world.heals.last())
        assertEquals(CastResult.Applied, runtime.cast(second, "workshop:keeper", "read", recipient, runtime.generation))
        assertEquals(second to 2.0, world.heals.last())
        assertEquals(CastResult.Applied, cast(first))
        repeat(2) { runtime.tick(listOf(first, second)) }
        assertEquals(listOf(recipient to 3.0, recipient to 3.0), world.heals.takeLast(2))
        runtime.onLogout(first)
        repeat(2) { runtime.tick(listOf(second)) }
        assertEquals(recipient to 4.0, world.heals.last())
        assertEquals(1, runtime.statuses(recipient).size)
    }

    @Test fun statusStateCannotBePersistedOrAccessedOutsideItsContribution() {
        val badPersistence = compile(state = """kind: state
id: memory
scope: status
persistent: true
fields:
  hits: {type: integer, min: 0, max: 10, initial: 0}""") as CompileResult.Invalid
        assertTrue(badPersistence.diagnostics.any { it.field == "persistent" })
        val outside = compile(ability = """kind: ability
id: apply_mark
name: Mark
effects:
  - {type: add_state, state: memory, field: hits, amount: 1}""") as CompileResult.Invalid
        assertTrue(outside.diagnostics.any { it.problem.contains("only available in its status instance") })
        val wrongScope = compile(state = """kind: state
id: memory
scope: player
fields:
  hits: {type: integer, min: 0, max: 10, initial: 0}""") as CompileResult.Invalid
        assertTrue(wrongScope.diagnostics.any { it.problem.contains("status-scoped") })
    }

    @Test fun markStateDetonatesOnlyTheOwnersContributionOnce() {
        val world = World()
        val runtime = AbilityRuntime(world)
        runtime.publish((compile() as CompileResult.Valid).definitions)
        runtime.selectClass(first, "workshop:keeper")
        runtime.selectClass(second, "workshop:keeper")
        runtime.cast(first, "workshop:keeper", "mark", recipient, runtime.generation)
        runtime.cast(second, "workshop:keeper", "mark", recipient, runtime.generation)
        repeat(2) { runtime.tick(listOf(first, second)) }
        assertEquals(CastResult.Applied, runtime.cast(first, "workshop:keeper", "detonate", recipient, runtime.generation))
        assertEquals(listOf(recipient to 2.0), world.damages)
        assertEquals(1, runtime.statuses(recipient).size)
        assertEquals(CastResult.Applied, runtime.cast(first, "workshop:keeper", "detonate", recipient, runtime.generation))
        assertEquals(listOf(recipient to 2.0), world.damages)
        assertEquals(CastResult.Applied, runtime.cast(second, "workshop:keeper", "detonate", recipient, runtime.generation))
        assertEquals(listOf(recipient to 2.0, recipient to 2.0), world.damages)
        assertTrue(runtime.statuses(recipient).isEmpty())
    }

    @Test fun externalWritesAreFilteredToEachOwnersLiveContribution() {
        val world = World()
        val runtime = AbilityRuntime(world)
        runtime.publish((compile() as CompileResult.Valid).definitions)
        runtime.selectClass(first, "workshop:keeper")
        runtime.selectClass(second, "workshop:keeper")
        runtime.cast(first, "workshop:keeper", "mark", recipient, runtime.generation)
        runtime.cast(second, "workshop:keeper", "mark", recipient, runtime.generation)
        assertEquals(CastResult.Applied, runtime.cast(first, "workshop:keeper", "write", recipient, runtime.generation))
        assertEquals(first to 1.0, world.heals.last())
        runtime.cast(first, "workshop:keeper", "read", recipient, runtime.generation)
        assertEquals(first to 4.0, world.heals.last())
        runtime.cast(second, "workshop:keeper", "read", recipient, runtime.generation)
        assertEquals(second to 1.0, world.heals.last())
        runtime.cast(first, "workshop:keeper", "reset", recipient, runtime.generation)
        runtime.cast(first, "workshop:keeper", "read", recipient, runtime.generation)
        assertEquals(first to 0.0, world.heals.last())
    }

    @Test fun invalidExternalWritesAreRejectedAtCompilation() {
        val wrongType = compile(ability = """kind: ability
id: apply_mark
name: Mark
effects:
  - {type: write_status_state, state: memory, field: hits, target: target, status: mark, operation: set, value: true}""") as CompileResult.Invalid
        assertTrue(wrongType.diagnostics.any { it.problem.contains("does not match") })
        val wrongField = compile(ability = """kind: ability
id: apply_mark
name: Mark
effects:
  - {type: write_status_state, state: memory, field: missing, target: target, status: mark, operation: reset}""") as CompileResult.Invalid
        assertTrue(wrongField.diagnostics.any { it.problem.contains("unknown state field") })
    }
}
