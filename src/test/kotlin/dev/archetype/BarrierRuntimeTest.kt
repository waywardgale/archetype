package dev.archetype

import dev.archetype.definitions.*
import dev.archetype.runtime.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.util.UUID

class BarrierRuntimeTest {
    private val owner = UUID(0, 1)
    private val target = UUID(0, 2)

    private class World : WorldOps {
        override fun validTarget(actor: UUID, target: UUID) = true
        override fun heal(target: UUID, amount: Double) = amount
        override fun damage(actor: UUID, target: UUID, amount: Double, damageType: String) = amount
        override fun position(entity: UUID) = Position("test", Vec(0.0, 0.0, 0.0))
        override fun loaded(position: Position) = true
    }

    private fun runtime(): AbilityRuntime {
        val files = listOf(
            "workshop/pack.yaml" to "format: 1\nid: workshop\nname: Workshop",
            "workshop/focus.yaml" to "kind: resource\nid: focus\nscope: player\nmin: 0\nmax: 100\ninitial: 0",
            "workshop/class.yaml" to """kind: class
id: guardian
name: Guardian
abilities:
  magic_ward:
    definition:
      name: Magic Ward
      effects:
        - type: shield
          target: target
          capacity: 5
          duration: 1s
          priority: 10
          damage_type: minecraft:magic
          depleted: [{type: gain_resource, resource: focus, amount: 3}]
  ward:
    definition:
      name: Ward
      effects:
        - type: shield
          target: target
          capacity: 7
          duration: 1s
          depleted: [{type: gain_resource, resource: focus, amount: 5}]""",
        )
        val compiled = ManifestCompiler().compile(PackSnapshot(files.map { SourceFile(it.first, it.second.toByteArray()) }, "v1"))
        assertTrue(compiled is CompileResult.Valid, "$compiled")
        return AbilityRuntime(World()).also {
            it.publish((compiled as CompileResult.Valid).definitions)
            it.selectClass(owner, "workshop:guardian")
        }
    }

    private fun cast(runtime: AbilityRuntime, grant: String) =
        runtime.cast(owner, "workshop:guardian", grant, target, runtime.generation)

    @Test fun `native prevention is consumed first and depleted callbacks run only after commit`() {
        val runtime = runtime()
        assertEquals(CastResult.Applied, cast(runtime, "magic_ward"))
        assertEquals(CastResult.Applied, cast(runtime, "ward"))
        assertEquals(0.0, runtime.absorbNativeDamage(target, "minecraft:fire", 3.0, 3.0))
        assertEquals(listOf(5.0, 7.0), runtime.barriers(target).map { it.remaining })
        assertEquals(7.0, runtime.absorbNativeDamage(target, "minecraft:fire", 10.0, 3.0))
        assertEquals(listOf(5.0), runtime.barriers(target).map { it.remaining })
        assertTrue(runtime.record(owner).resources.isEmpty())
        runtime.nativeDamageCommitted(target)
        assertEquals(5.0, runtime.record(owner).resources.values.single())

        assertEquals(5.0, runtime.absorbNativeDamage(target, "minecraft:magic", 12.0, 2.0))
        assertTrue(runtime.barriers(target).isEmpty())
        assertEquals(5.0, runtime.record(owner).resources.values.single())
        runtime.nativeDamageCommitted(target)
        assertEquals(8.0, runtime.record(owner).resources.values.single())
    }

    @Test fun `priority and creation order control consumption while expiry and cancellation grant nothing`() {
        val runtime = runtime()
        assertEquals(CastResult.Applied, cast(runtime, "ward"))
        assertEquals(CastResult.Applied, cast(runtime, "magic_ward"))
        assertEquals(6.0, runtime.absorbNativeDamage(target, "minecraft:magic", 8.0, 2.0))
        assertEquals(listOf(6.0), runtime.barriers(target).map { it.remaining })
        runtime.nativeDamageCommitted(target)
        assertEquals(3.0, runtime.record(owner).resources.values.single())
        repeat(20) { runtime.tick(listOf(owner)) }
        assertTrue(runtime.barriers(target).isEmpty())
        assertEquals(3.0, runtime.record(owner).resources.values.single())
        assertEquals(CastResult.Applied, cast(runtime, "ward"))
        runtime.onLogout(owner)
        assertTrue(runtime.barriers(target).isEmpty())
        assertEquals(3.0, runtime.record(owner).resources.values.single())
    }
}
