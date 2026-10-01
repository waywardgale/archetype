package dev.archetype

import dev.archetype.definitions.*
import dev.archetype.runtime.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.util.UUID

class ReflectionRuntimeTest {
    private val actor = UUID(0, 811)
    private val attacker = UUID(0, 812)
    private class World : WorldOps {
        val hits = mutableListOf<Triple<UUID, UUID, Double>>()
        override fun validTarget(actor: UUID, target: UUID) = true
        override fun heal(target: UUID, amount: Double) = amount
        override fun damage(actor: UUID, target: UUID, amount: Double, damageType: String): Double {
            hits += Triple(actor, target, amount)
            return amount
        }
    }

    private fun compiled(status: String = """kind: status
id: mirror
duration: 5s
modifiers:
  - {type: reflect, fraction: 0.5, cap: 3, damage_type: minecraft:magic}"""): CompileResult {
        val files = listOf(
            "workshop/pack.yaml" to "format: 1\nid: workshop\nname: Workshop",
            "workshop/class.yaml" to "kind: class\nid: mage\nname: Mage\nabilities:\n  mirror: {ref: mirror_cast}",
            "workshop/mirror-cast.yaml" to "kind: ability\nid: mirror_cast\nname: Mirror\neffects: [{type: apply_status, status: mirror, target: actor}]",
            "workshop/mirror.yaml" to status,
        )
        return ManifestCompiler().compile(PackSnapshot(files.map { SourceFile(it.first, it.second.toByteArray()) }, "mirror"))
    }

    @Test fun `reflection uses actual health loss with a cap and status lifetime`() {
        val world = World()
        val runtime = AbilityRuntime(world)
        runtime.publish((compiled() as CompileResult.Valid).definitions)
        runtime.selectClass(actor, "workshop:mage")
        assertEquals(CastResult.Applied, runtime.cast(actor, "workshop:mage", "mirror", null, runtime.generation))
        assertEquals(0.0, runtime.reflectNativeDamage(actor, attacker, 0.0))
        assertEquals(2.0, runtime.reflectNativeDamage(actor, attacker, 4.0))
        assertEquals(3.0, runtime.reflectNativeDamage(actor, attacker, 10.0))
        assertEquals(listOf(Triple(actor, attacker, 2.0), Triple(actor, attacker, 3.0)), world.hits)
        runtime.onDeath(actor)
        assertEquals(0.0, runtime.reflectNativeDamage(actor, attacker, 10.0))
    }

    @Test fun `reflection fraction and cap are bounded`() {
        val invalid = compiled("""kind: status
id: mirror
duration: 5s
modifiers:
  - {type: reflect, fraction: 1.5, cap: 3, damage_type: minecraft:magic}""") as CompileResult.Invalid
        assertTrue(invalid.diagnostics.any { it.field.contains("fraction") })
    }
}
