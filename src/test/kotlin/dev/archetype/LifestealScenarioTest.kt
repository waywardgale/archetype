package dev.archetype

import dev.archetype.definitions.*
import dev.archetype.runtime.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.nio.file.Path
import java.util.UUID

class LifestealScenarioTest {
    private val actor = UUID(0, 901)
    private val victim = UUID(0, 902)
    private class World(private val actor: UUID) : WorldOps {
        var actorHealth = 9.0
        var damageApplied = 8.0
        override fun validTarget(actor: UUID, target: UUID) = true
        override fun view(actor: UUID, target: UUID) = if (target == this.actor)
            EntityView(target, Position("test", Vec(0.0, 0.0, 0.0)), actorHealth, 10.0, true) else null
        override fun heal(target: UUID, amount: Double): Double {
            val restored = minOf(amount, 10.0 - actorHealth)
            actorHealth += restored
            return restored
        }
        override fun damage(actor: UUID, target: UUID, amount: Double, damageType: String) = minOf(amount, damageApplied)
    }

    @Test fun `manifest lifesteal converts only actual overheal and changes on reload`() {
        val root = Path.of(javaClass.getResource("/packs/spatial")!!.toURI())
        val captured = PackCapture.capture(root)
        val compiled = ManifestCompiler().compile(captured) as CompileResult.Valid
        val world = World(actor)
        val runtime = AbilityRuntime(world)
        runtime.publish(compiled.definitions)
        runtime.selectClass(actor, "workshop:validation_actor")
        assertEquals(CastResult.Applied, runtime.cast(actor, "workshop:validation_actor", "leech", victim, runtime.generation))
        assertEquals(10.0, world.actorHealth)
        assertEquals(3.0, runtime.barriers(actor).single().remaining)

        runtime.onDeath(actor)
        world.actorHealth = 9.0
        world.damageApplied = 0.0
        assertEquals(CastResult.Applied, runtime.cast(actor, "workshop:validation_actor", "leech", victim, runtime.generation))
        assertEquals(9.0, world.actorHealth)
        assertTrue(runtime.barriers(actor).isEmpty())

        val edited = PackSnapshot(captured.files.map { file ->
            if (file.relativePath.endsWith("lifesteal-convert.yaml"))
                file.copy(bytes = file.bytes.toString(Charsets.UTF_8).replace("amount: 8", "amount: 4").toByteArray())
            else file
        }, "lifesteal-edit")
        runtime.publish((ManifestCompiler().compile(edited) as CompileResult.Valid).definitions)
        world.damageApplied = 4.0
        assertEquals(CastResult.Applied, runtime.cast(actor, "workshop:validation_actor", "leech", victim, runtime.generation))
        assertEquals(1.0, runtime.barriers(actor).single().remaining)
    }
}
