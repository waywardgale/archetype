package dev.archetype

import dev.archetype.definitions.*
import dev.archetype.runtime.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.util.UUID

class ChargeRuntimeTest {
    private val actor = UUID(0, 1)
    private class World : WorldOps {
        var healing = 0
        var damageApplied = 0.0
        override fun validTarget(actor: UUID, target: UUID) = actor == target
        override fun heal(target: UUID, amount: Double): Double { healing++; return amount }
        override fun damage(actor: UUID, target: UUID, amount: Double, damageType: String) = damageApplied
    }
    private fun compile(capacity: Int, mode: String = "sequential", fingerprint: String = "v1", recharge: String = "100ms",
                        effects: String = "[{type: heal, target: actor, amount: 1}]", cooldown: String? = null): CompileResult {
        val files = listOf(
            "workshop/pack.yaml" to "format: 1\nid: workshop\nname: Workshop",
            "workshop/focus.yaml" to "kind: resource\nid: focus\nscope: player\nmin: 0\nmax: 10\ninitial: 10",
            "workshop/fighter.yaml" to """kind: class
id: fighter
name: Fighter
abilities:
  burst:
    definition:
      name: Burst
      charges: {max: $capacity, recharge: $recharge, mode: $mode}
      ${cooldown?.let { "cooldown: $it" } ?: ""}
      costs: [{resource: focus, amount: 1}]
      effects: $effects""",
            "workshop/other.yaml" to "kind: class\nid: other\nname: Other\nabilities: {}",
        )
        return ManifestCompiler().compile(PackSnapshot(files.map { SourceFile(it.first, it.second.toByteArray()) }, fingerprint))
    }
    private fun definitions(result: CompileResult): DefinitionSet {
        assertTrue(result is CompileResult.Valid, "$result")
        return (result as CompileResult.Valid).definitions
    }
    private fun runtime(world: World, definitions: DefinitionSet) = AbilityRuntime(world).also {
        it.publish(definitions)
        it.selectClass(actor, "workshop:fighter")
    }
    private fun cast(runtime: AbilityRuntime) = runtime.cast(actor, "workshop:fighter", "burst", null, runtime.generation)
    private fun ticks(runtime: AbilityRuntime, count: Int, online: Boolean = true) {
        repeat(count) { runtime.tick(if (online) listOf(actor) else emptyList()) }
    }

    @Test fun `sequential recharge restores one missing use per interval`() {
        val world = World()
        val runtime = runtime(world, definitions(compile(2)))
        assertEquals(CastResult.Applied, cast(runtime))
        assertEquals(CastResult.Applied, cast(runtime))
        assertEquals(CastResult.Rejected("no ability charges available"), cast(runtime))
        assertEquals(8.0, runtime.record(actor).resources["player|workshop:focus"])
        assertEquals(2, world.healing)
        ticks(runtime, 2)
        assertEquals(1, runtime.chargeState(actor, "workshop:fighter", "burst")!!.available)
        assertEquals(listOf(2), runtime.chargeState(actor, "workshop:fighter", "burst")!!.timers)
        ticks(runtime, 2)
        assertEquals(2, runtime.chargeState(actor, "workshop:fighter", "burst")!!.available)
    }

    @Test fun `parallel recharge preserves staggered timers`() {
        val runtime = runtime(World(), definitions(compile(2, "parallel")))
        cast(runtime)
        ticks(runtime, 1)
        cast(runtime)
        assertEquals(listOf(1, 2), runtime.chargeState(actor, "workshop:fighter", "burst")!!.timers)
        ticks(runtime, 1)
        assertEquals(1, runtime.chargeState(actor, "workshop:fighter", "burst")!!.available)
        ticks(runtime, 1)
        assertEquals(2, runtime.chargeState(actor, "workshop:fighter", "burst")!!.available)
    }

    @Test fun `inactive class and offline time follow different recharge rules`() {
        val runtime = runtime(World(), definitions(compile(1)))
        cast(runtime)
        runtime.selectClass(actor, "workshop:other")
        ticks(runtime, 2, online = false)
        assertEquals(0, runtime.chargeState(actor, "workshop:fighter", "burst")!!.available)
        ticks(runtime, 2)
        assertEquals(1, runtime.chargeState(actor, "workshop:fighter", "burst")!!.available)
    }

    @Test fun `capacity increases without free charges and active timer keeps its duration`() {
        val runtime = runtime(World(), definitions(compile(2, "parallel")))
        cast(runtime)
        ticks(runtime, 1)
        runtime.publish(definitions(compile(3, "parallel", "v2", "1s")))
        val state = runtime.chargeState(actor, "workshop:fighter", "burst")!!
        assertEquals(1, state.available)
        assertEquals(listOf(1, 20), state.timers)
        ticks(runtime, 1)
        assertEquals(2, state.available)
        assertEquals(listOf(19), state.timers)
        runtime.publish(definitions(compile(1, "parallel", "v3")))
        assertEquals(1, state.available)
        assertTrue(state.timers.isEmpty())
    }

    @Test fun `invalid charge limits and intervals have located diagnostics`() {
        for ((result, field) in listOf(
            compile(0) to "charges.max",
            compile(17) to "charges.max",
            compile(2, recharge = "0s") to "charges.recharge",
            compile(2, mode = "burst") to "charges.mode",
        )) {
            assertTrue(result is CompileResult.Invalid)
            assertTrue((result as CompileResult.Invalid).diagnostics.any { it.field == field }, "$result")
        }
    }

    @Test fun `confirmed damage can restore a charge while a blocked hit cannot`() {
        val effects = """- type: damage
  target: actor
  amount: 3
  damage_type: minecraft:magic
  as: strike
- type: branch
  when: {type: compare, left: {expr: 'result.strike.health_lost'}, op: gt, right: 0}
  then: [{type: restore_charge, count: 1}]
""".trimEnd()
        val world = World()
        val runtime = runtime(world, definitions(compile(1, effects = "\n${effects.prependIndent("        ")}")))
        assertEquals(CastResult.Applied, cast(runtime))
        assertEquals(0, runtime.chargeState(actor, "workshop:fighter", "burst")!!.available)
        ticks(runtime, 2)
        world.damageApplied = 3.0
        assertEquals(CastResult.Applied, cast(runtime))
        val state = runtime.chargeState(actor, "workshop:fighter", "burst")!!
        assertEquals(1, state.available)
        assertTrue(state.timers.isEmpty())
    }

    @Test fun `cooldown reduction clamps at zero and leaves charge count unchanged`() {
        val world = World()
        val effects = "[{type: reduce_cooldown, amount: 500ms, as: remaining}]"
        val runtime = runtime(world, definitions(compile(2, effects = effects, cooldown = "2s")))
        assertEquals(CastResult.Applied, cast(runtime))
        assertEquals(30, runtime.record(actor).cooldowns["workshop:fighter|burst"])
        assertEquals(1, runtime.chargeState(actor, "workshop:fighter", "burst")!!.available)
    }
}
