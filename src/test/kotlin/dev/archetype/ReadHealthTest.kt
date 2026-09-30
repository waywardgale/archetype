package dev.archetype

import dev.archetype.definitions.*
import dev.archetype.runtime.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.util.UUID

class ReadHealthTest {
    private val actor = UUID(0, 1)
    private val recipient = UUID(0, 2)

    private class World(private val recipient: UUID) : WorldOps {
        var health = 4.0
        var unavailableView = false
        var heals = 0
        override fun validTarget(actor: UUID, target: UUID) = true
        override fun heal(target: UUID, amount: Double): Double {
            heals++
            val applied = minOf(amount, 10.0 - health)
            health += applied
            return applied
        }
        override fun damage(actor: UUID, target: UUID, amount: Double, damageType: String) = amount
        override fun view(actor: UUID, target: UUID): EntityView? = if (target == recipient && unavailableView) null
            else EntityView(target, Position("test", Vec(0.0, 0.0, 0.0)), if (target == recipient) health else 10.0, 10.0, false)
    }

    private fun definitions(): DefinitionSet {
        val files = listOf(
            "workshop/pack.yaml" to "format: 1\nid: workshop\nname: Workshop",
            "workshop/class.yaml" to """kind: class
id: healer
name: Healer
abilities:
  rescue:
    definition:
      name: Rescue
      effects:
        - {type: read_health, target: target, as: vital}
        - type: branch
          when: {type: compare, left: {expr: 'result.vital.fraction'}, op: lt, right: 0.5}
          then:
            - {type: heal, target: target, amount: {expr: 'result.vital.missing'}}""",
        )
        val compiled = ManifestCompiler().compile(PackSnapshot(files.map { SourceFile(it.first, it.second.toByteArray()) }, "v1"))
        assertTrue(compiled is CompileResult.Valid, "$compiled")
        return (compiled as CompileResult.Valid).definitions
    }

    @Test fun `health read is live and unavailable reads do not run dependent branches`() {
        val world = World(recipient)
        val runtime = AbilityRuntime(world)
        runtime.publish(definitions())
        runtime.selectClass(actor, "workshop:healer")
        fun rescue() = runtime.cast(actor, "workshop:healer", "rescue", recipient, runtime.generation)

        assertEquals(CastResult.Applied, rescue())
        assertEquals(10.0, world.health)
        assertEquals(1, world.heals)
        assertEquals(CastResult.Applied, rescue())
        assertEquals(1, world.heals)
        world.health = 3.0
        world.unavailableView = true
        assertEquals(CastResult.Applied, rescue())
        assertEquals(3.0, world.health)
        assertEquals(1, world.heals)
    }
}
