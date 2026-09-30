package dev.archetype

import dev.archetype.definitions.*
import dev.archetype.runtime.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.nio.file.Path
import java.util.UUID

/** A single manifest composes native outcomes, status stacks, named timers and a finisher. */
class ComboScenarioTest {
    private val actor = UUID(0, 1)
    private val target = UUID(0, 2)
    private class World : WorldOps {
        var blocked = false
        val damage = mutableListOf<Double>()
        override fun validTarget(actor: UUID, target: UUID) = true
        override fun heal(target: UUID, amount: Double) = amount
        override fun damage(actor: UUID, target: UUID, amount: Double, damageType: String): Double {
            val actual = if (blocked) 0.0 else amount
            damage += actual
            return actual
        }
        override fun position(entity: UUID) = Position("test", Vec(0.0, 0.0, 0.0))
        override fun loaded(position: Position) = true
    }
    private fun runtime(world: World): AbilityRuntime {
        val root = Path.of(javaClass.getResource("/packs/spatial")!!.toURI())
        val result = ManifestCompiler().compile(PackCapture.capture(root))
        assertTrue(result is CompileResult.Valid, "$result")
        return AbilityRuntime(world).also {
            it.publish((result as CompileResult.Valid).definitions)
            it.selectClass(actor, "workshop:validation_actor")
        }
    }
    private fun cast(runtime: AbilityRuntime) = runtime.cast(actor, "workshop:validation_actor", "combo", target, runtime.generation)
    private fun ticks(runtime: AbilityRuntime, count: Int) { repeat(count) { runtime.tick(listOf(actor)) } }

    @Test fun `three confirmed hits finish once and a miss does not advance`() {
        val world = World()
        val runtime = runtime(world)
        assertEquals(CastResult.Applied, cast(runtime))
        assertEquals(1, runtime.statuses(actor).single().stacks)
        world.blocked = true
        assertEquals(CastResult.Applied, cast(runtime))
        assertEquals(1, runtime.statuses(actor).single().stacks)
        world.blocked = false
        assertEquals(CastResult.Applied, cast(runtime))
        assertEquals(2, runtime.statuses(actor).single().stacks)
        assertEquals(CastResult.Applied, cast(runtime))
        assertTrue(runtime.statuses(actor).isEmpty())
        assertTrue(runtime.timers(actor).isEmpty())
        assertEquals(listOf(1.0, 0.0, 1.0, 1.0, 10.0), world.damage)
        ticks(runtime, 3)
        assertTrue(runtime.statuses(actor).isEmpty())
        assertEquals(5, world.damage.size)
    }

    @Test fun `old timer expiry cannot clear a refreshed combo window`() {
        val runtime = runtime(World())
        cast(runtime)
        ticks(runtime, 1)
        cast(runtime)
        ticks(runtime, 1)
        assertEquals(2, runtime.statuses(actor).single().stacks)
        ticks(runtime, 1)
        assertTrue(runtime.statuses(actor).isEmpty())
    }
}
