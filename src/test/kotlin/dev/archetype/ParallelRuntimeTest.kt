package dev.archetype

import dev.archetype.definitions.*
import dev.archetype.runtime.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.util.UUID

class ParallelRuntimeTest {
    private val actor = UUID(0, 81)
    private class World : WorldOps {
        val heals = mutableListOf<Double>()
        override fun validTarget(actor: UUID, target: UUID) = true
        override fun heal(target: UUID, amount: Double) = amount.also(heals::add)
        override fun damage(actor: UUID, target: UUID, amount: Double, damageType: String) = amount
    }

    private fun compile(effects: String): CompileResult = ManifestCompiler().compile(PackSnapshot(listOf(
        SourceFile("workshop/pack.yaml", "format: 1\nid: workshop\nname: Workshop".toByteArray()),
        SourceFile("workshop/focus.yaml", "kind: resource\nid: focus\nscope: player\nmin: 0\nmax: 20\ninitial: 0".toByteArray()),
        SourceFile("workshop/ability.yaml", "kind: ability\nid: cast\nname: Cast\neffects:\n$effects".toByteArray()),
        SourceFile("workshop/class.yaml", "kind: class\nid: mage\nname: Mage\nabilities:\n  cast: {ref: cast}".toByteArray()),
    ), effects))

    private fun runtime(effects: String, world: World): AbilityRuntime = AbilityRuntime(world).also {
        val result = compile(effects)
        assertTrue(result is CompileResult.Valid, "$result")
        it.publish((result as CompileResult.Valid).definitions)
        it.selectClass(actor, "workshop:mage")
    }

    @Test fun allJoinWaitsForEveryBranchAndKeepsBindingsPrivate() {
        val effects = """  - type: parallel
    join: all
    branches:
      - effects: [{type: gain_resource, resource: focus, amount: 1, as: local}]
      - after: 100ms
        effects: [{type: gain_resource, resource: focus, amount: 2}]
    then: [{type: heal, target: actor, amount: {expr: 'parallel.completed'}}]
    failed: [{type: heal, target: actor, amount: 99}]"""
        val world = World()
        val runtime = runtime(effects, world)
        assertEquals(CastResult.Applied, runtime.cast(actor, "workshop:mage", "cast", null, runtime.generation))
        assertEquals(1.0, runtime.record(actor).resources["player|workshop:focus"])
        assertTrue(world.heals.isEmpty())
        runtime.tick(listOf(actor))
        assertTrue(world.heals.isEmpty())
        runtime.tick(listOf(actor))
        assertEquals(3.0, runtime.record(actor).resources["player|workshop:focus"])
        assertEquals(listOf(2.0), world.heals)
        val leaking = compile(effects.replace("parallel.completed", "result.local.current")) as CompileResult.Invalid
        assertTrue(leaking.diagnostics.any { it.problem.contains("not available") })
    }

    @Test fun firstSuccessCancelsLosingDescendantsAndPublishesWinnerIndex() {
        val effects = """  - type: parallel
    join: first_success
    branches:
      - effects:
          - {type: gain_resource, resource: focus, amount: 1, as: gained}
          - {type: delay, duration: 100ms, effects: [{type: heal, target: actor, amount: 77}]}
        success_when: {type: compare, left: {expr: 'result.gained.amount'}, op: gt, right: 10}
      - effects: [{type: gain_resource, resource: focus, amount: 2}]
      - after: 100ms
        effects: [{type: heal, target: actor, amount: 88}]
    then: [{type: heal, target: actor, amount: {expr: 'parallel.index + 1'}}]
    failed: [{type: heal, target: actor, amount: 99}]"""
        val world = World()
        val runtime = runtime(effects, world)
        assertEquals(CastResult.Applied, runtime.cast(actor, "workshop:mage", "cast", null, runtime.generation))
        assertEquals(3.0, runtime.record(actor).resources["player|workshop:focus"], "both immediate branches should run")
        repeat(3) { runtime.tick(listOf(actor)) }
        assertTrue(runtime.drainFailures().isEmpty(), "parallel execution failed")
        assertEquals(listOf(2.0), world.heals)
        assertEquals(3.0, runtime.record(actor).resources["player|workshop:focus"])
    }

    @Test fun unsuccessfulBranchesTakeFailurePathAfterLastCompletion() {
        val effects = """  - type: parallel
    join: first_success
    branches:
      - effects: [{type: gain_resource, resource: focus, amount: 1}]
        success_when: {type: resource_at_least, resource: focus, amount: 10}
      - after: 50ms
        effects: [{type: gain_resource, resource: focus, amount: 1}]
        success_when: {type: resource_at_least, resource: focus, amount: 10}
    then: [{type: heal, target: actor, amount: 77}]
    failed: [{type: heal, target: actor, amount: {expr: 'parallel.completed + 3'}}]"""
        val world = World()
        val runtime = runtime(effects, world)
        runtime.cast(actor, "workshop:mage", "cast", null, runtime.generation)
        assertTrue(world.heals.isEmpty())
        runtime.tick(listOf(actor))
        assertEquals(listOf(3.0), world.heals)
    }

    @Test fun excessiveParallelWorkIsRejectedBeforeCost() {
        val body = List(32) { "{type: heal, target: actor, amount: 1}" }.joinToString(", ")
        val effects = """  - type: parallel
    join: all
    branches:
      - effects: [{type: repeat, count: 64, every: 50ms, effects: [$body]}]
      - effects: [{type: repeat, count: 64, every: 50ms, effects: [$body]}]"""
        val world = World()
        val runtime = runtime(effects, world)
        assertEquals(CastResult.Rejected("ability work limit exceeded"), runtime.cast(actor, "workshop:mage", "cast", null, runtime.generation))
        assertTrue(world.heals.isEmpty())
    }
}
