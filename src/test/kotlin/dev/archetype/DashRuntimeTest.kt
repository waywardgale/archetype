package dev.archetype

import dev.archetype.definitions.*
import dev.archetype.runtime.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.util.UUID

class DashRuntimeTest {
    private val actor = UUID(0, 1)
    private val enemy = UUID(0, 2)

    private inner class World(private val wall: Double) : WorldOps {
        var actorX = 0.0
        var enemyX = 10.0
        var groundX = 6.0
        var landingSafe = true
        var groundLoaded = true
        val hits = mutableListOf<Double>()
        val heals = mutableListOf<Double>()
        override fun validTarget(actor: UUID, target: UUID) = validTarget(actor, target, 16.0)
        override fun validTarget(actor: UUID, target: UUID, range: Double) =
            actor == this@DashRuntimeTest.actor && target == enemy && kotlin.math.abs(enemyX - actorX) <= range
        override fun availableTarget(actor: UUID, target: UUID) = actor == this@DashRuntimeTest.actor &&
            (target == actor || target == enemy)
        override fun position(entity: UUID) = when (entity) {
            actor -> Position("test", Vec(actorX, 0.0, 0.0))
            enemy -> Position("test", Vec(enemyX, 0.0, 0.0))
            else -> null
        }
        override fun direction(entity: UUID) = Vec(1.0, 0.0, 0.0)
        override fun ground(actor: UUID, range: Double) = Position("test", Vec(groundX, 0.0, 0.0))
        override fun loaded(position: Position) = groundLoaded
        override fun safeTeleport(entity: UUID, destination: Position): Boolean? {
            if (entity != actor) return null
            if (!landingSafe) return false
            actorX = destination.value.x
            return true
        }
        override fun displace(entity: UUID, direction: Vec, distance: Double): MotionResult? {
            if (entity != actor && entity != enemy) return null
            val current = if (entity == enemy) enemyX else actorX
            val travelled = if (direction.x > 0.0) minOf(distance, (wall - current).coerceAtLeast(0.0)) else distance
            if (entity == enemy) enemyX += travelled * direction.x else actorX += travelled * direction.x
            return MotionResult(travelled, travelled < distance - 0.01)
        }
        override fun heal(target: UUID, amount: Double): Double { heals += amount; return amount }
        override fun damage(actor: UUID, target: UUID, amount: Double, damageType: String): Double {
            hits += amount
            return amount
        }
    }

    private fun compile(distance: String = "8", range: Int = 16): CompileResult {
        val files = listOf(
            "workshop/pack.yaml" to "format: 1\nid: workshop\nname: Workshop",
            "workshop/class.yaml" to "kind: class\nid: runner\nname: Runner\nabilities:\n  strike: {ref: strike}",
            "workshop/strike.yaml" to """kind: ability
id: strike
name: Dash strike
target: {type: entity, range: $range}
effects:
  - {type: dash, direction: actor.aim, distance: $distance, as: motion}
  - type: damage
    target: target
    amount: {expr: 'result.motion.travelled + result.motion.blocked'}
    damage_type: minecraft:magic""",
        )
        return ManifestCompiler().compile(PackSnapshot(files.map { SourceFile(it.first, it.second.toByteArray()) }, "dash"))
    }

    private fun compileImpulse(direction: String): CompileResult {
        val files = listOf(
            "workshop/pack.yaml" to "format: 1\nid: workshop\nname: Workshop",
            "workshop/class.yaml" to "kind: class\nid: runner\nname: Runner\nabilities:\n  strike: {ref: strike}",
            "workshop/strike.yaml" to """kind: ability
id: strike
name: Impulse strike
target: {type: entity, range: 16}
effects:
  - {type: impulse, target: target, direction: $direction, distance: 8, as: motion}
  - type: damage
    target: target
    amount: {expr: 'result.motion.travelled + result.motion.blocked'}
    damage_type: minecraft:magic""",
        )
        return ManifestCompiler().compile(PackSnapshot(files.map { SourceFile(it.first, it.second.toByteArray()) }, "impulse"))
    }

    private fun compileTeleport(): CompileResult {
        val files = listOf(
            "workshop/pack.yaml" to "format: 1\nid: workshop\nname: Workshop",
            "workshop/class.yaml" to "kind: class\nid: runner\nname: Runner\nabilities:\n  strike: {ref: strike}",
            "workshop/strike.yaml" to """kind: ability
id: strike
name: Safe arrival
target: {type: ground, range: 16}
effects:
  - {type: safe_teleport, target: actor, destination: ground, as: jump}
  - {type: heal, target: actor, amount: {expr: '1 + result.jump.arrived'}}""",
        )
        return ManifestCompiler().compile(PackSnapshot(files.map { SourceFile(it.first, it.second.toByteArray()) }, "teleport"))
    }

    @Test fun `collision-limited dash exposes travel and blocked result to follow-up`() {
        val compiled = compile()
        assertTrue(compiled is CompileResult.Valid, "$compiled")
        val world = World(4.0)
        val runtime = AbilityRuntime(world)
        runtime.publish((compiled as CompileResult.Valid).definitions)
        assertTrue(runtime.selectClass(actor, "workshop:runner"))
        assertEquals(CastResult.Applied, runtime.cast(actor, "workshop:runner", "strike", enemy, runtime.generation))
        assertEquals(4.0, world.actorX)
        assertEquals(listOf(5.0), world.hits)
    }

    @Test fun `follow-up target is checked again after dash changes its range`() {
        val compiled = compile(range = 2)
        assertTrue(compiled is CompileResult.Valid, "$compiled")
        val world = World(4.0).apply { enemyX = 1.0 }
        val runtime = AbilityRuntime(world)
        runtime.publish((compiled as CompileResult.Valid).definitions)
        assertTrue(runtime.selectClass(actor, "workshop:runner"))
        assertEquals(CastResult.Applied, runtime.cast(actor, "workshop:runner", "strike", enemy, runtime.generation))
        assertEquals(4.0, world.actorX)
        assertTrue(world.hits.isEmpty())
    }

    @Test fun `out-of-range constant distance is rejected at compile time`() {
        val result = compile(distance = "33")
        assertTrue(result is CompileResult.Invalid, "$result")
        assertTrue((result as CompileResult.Invalid).diagnostics.any { it.problem.contains("dash distance") }, "$result")
    }

    @Test fun `push moves selected target through collision and reports blocked travel`() {
        val compiled = compileImpulse("away")
        assertTrue(compiled is CompileResult.Valid, "$compiled")
        val world = World(4.0).apply { enemyX = 2.0 }
        val runtime = AbilityRuntime(world)
        runtime.publish((compiled as CompileResult.Valid).definitions)
        assertTrue(runtime.selectClass(actor, "workshop:runner"))
        assertEquals(CastResult.Applied, runtime.cast(actor, "workshop:runner", "strike", enemy, runtime.generation))
        assertEquals(0.0, world.actorX)
        assertEquals(4.0, world.enemyX)
        assertEquals(listOf(3.0), world.hits)
    }

    @Test fun `pull direction moves target toward the caster`() {
        val compiled = compileImpulse("toward")
        assertTrue(compiled is CompileResult.Valid, "$compiled")
        val world = World(20.0).apply { enemyX = 10.0 }
        val runtime = AbilityRuntime(world)
        runtime.publish((compiled as CompileResult.Valid).definitions)
        assertTrue(runtime.selectClass(actor, "workshop:runner"))
        assertEquals(CastResult.Applied, runtime.cast(actor, "workshop:runner", "strike", enemy, runtime.generation))
        assertEquals(2.0, world.enemyX)
        assertEquals(listOf(8.0), world.hits)
    }

    @Test fun `safe teleport exposes arrival and leaves blocked landing in place`() {
        val compiled = compileTeleport()
        assertTrue(compiled is CompileResult.Valid, "$compiled")
        val world = World(20.0)
        val runtime = AbilityRuntime(world)
        runtime.publish((compiled as CompileResult.Valid).definitions)
        assertTrue(runtime.selectClass(actor, "workshop:runner"))
        assertEquals(CastResult.Applied, runtime.cast(actor, "workshop:runner", "strike", null, runtime.generation))
        assertEquals(6.0, world.actorX)
        assertEquals(listOf(2.0), world.heals)
        world.landingSafe = false
        world.groundX = 9.0
        assertEquals(CastResult.Applied, runtime.cast(actor, "workshop:runner", "strike", null, runtime.generation))
        assertEquals(6.0, world.actorX)
        assertEquals(listOf(2.0, 1.0), world.heals)
    }
}
