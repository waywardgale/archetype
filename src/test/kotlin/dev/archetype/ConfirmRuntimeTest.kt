package dev.archetype

import dev.archetype.definitions.*
import dev.archetype.runtime.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.util.UUID

class ConfirmRuntimeTest {
    private val actor = UUID(0, 161)
    private class World : WorldOps {
        val hits = mutableListOf<UUID>()
        var dimension = "test"
        override fun validTarget(actor: UUID, target: UUID) = true
        override fun heal(target: UUID, amount: Double) = amount
        override fun damage(actor: UUID, target: UUID, amount: Double, damageType: String): Double {
            hits += target
            return amount
        }
        override fun position(entity: UUID) = Position(dimension, Vec(0.0, 0.0, 0.0))
    }
    private fun compile(fingerprint: String = "v1", window: String = "100ms"): CompileResult {
        val files = listOf(
            "workshop/pack.yaml" to "format: 1\nid: workshop\nname: Workshop",
            "workshop/resource.yaml" to "kind: resource\nid: focus\nscope: class\nmin: 0\nmax: 10\ninitial: 10",
            "workshop/ability.yaml" to """kind: ability
id: confirm_strike
name: Confirm Strike
activation: {type: confirm, window: $window}
target: {type: entity, range: 10}
costs: [{resource: focus, amount: 2}]
cooldown: 1s
effects:
  - {type: damage, target: target, amount: 3, damage_type: minecraft:magic}""",
            "workshop/class.yaml" to "kind: class\nid: fighter\nname: Fighter\nabilities:\n  confirm_strike: {ref: confirm_strike}",
            "workshop/other.yaml" to "kind: class\nid: other\nname: Other\nabilities:\n  confirm_strike: {ref: confirm_strike}",
        )
        return ManifestCompiler().compile(PackSnapshot(files.map { SourceFile(it.first, it.second.toByteArray()) }, fingerprint))
    }
    @Test fun firstPressArmsWithoutPaymentAndSecondPressCommitsOnce() {
        val world = World()
        val runtime = AbilityRuntime(world)
        runtime.publish((compile() as CompileResult.Valid).definitions)
        runtime.selectClass(actor, "workshop:fighter")
        val target = UUID(0, 162)
        assertEquals(CastResult.Applied, runtime.cast(actor, "workshop:fighter", "confirm_strike", target, runtime.generation))
        assertTrue(runtime.confirmActive(actor, "workshop:fighter", "confirm_strike"))
        assertEquals(10.0, runtime.record(actor).resources.getOrDefault("workshop:fighter|workshop:focus", 10.0))
        assertEquals(0, runtime.cooldownRemaining(actor, "workshop:fighter", "confirm_strike"))
        assertEquals(CastResult.Applied, runtime.cast(actor, "workshop:fighter", "confirm_strike", null, runtime.generation))
        assertEquals(listOf(target), world.hits)
        assertFalse(runtime.confirmActive(actor, "workshop:fighter", "confirm_strike"))
        assertEquals(8.0, runtime.record(actor).resources["workshop:fighter|workshop:focus"])
        assertTrue(runtime.cooldownRemaining(actor, "workshop:fighter", "confirm_strike") > 0)
    }

    @Test fun expiredOrInvalidatedArmCannotCommit() {
        val world = World()
        val runtime = AbilityRuntime(world)
        runtime.publish((compile() as CompileResult.Valid).definitions)
        runtime.selectClass(actor, "workshop:fighter")
        runtime.cast(actor, "workshop:fighter", "confirm_strike", null, runtime.generation)
        repeat(2) { runtime.tick(listOf(actor)) }
        assertFalse(runtime.confirmActive(actor, "workshop:fighter", "confirm_strike"))
        runtime.cast(actor, "workshop:fighter", "confirm_strike", null, runtime.generation)
        assertTrue(runtime.confirmActive(actor, "workshop:fighter", "confirm_strike"))
        runtime.selectClass(actor, "workshop:other")
        assertFalse(runtime.confirmActive(actor, "workshop:fighter", "confirm_strike"))
        runtime.selectClass(actor, "workshop:fighter")
        runtime.cast(actor, "workshop:fighter", "confirm_strike", null, runtime.generation)
        world.dimension = "changed"
        runtime.tick(listOf(actor))
        assertFalse(runtime.confirmActive(actor, "workshop:fighter", "confirm_strike"))
        assertTrue(world.hits.isEmpty())
        assertEquals(10.0, runtime.record(actor).resources.getOrDefault("workshop:fighter|workshop:focus", 10.0))
    }

    @Test fun confirmationWindowIsBounded() {
        val invalid = compile(window = "61s") as CompileResult.Invalid
        assertTrue(invalid.diagnostics.any { it.field == "activation.window" })
    }
}
