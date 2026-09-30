package dev.archetype

import dev.archetype.definitions.*
import dev.archetype.runtime.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.util.UUID

class MaintainedAreaTest {
    private val actor = UUID(0, 1)
    private class World(private val actor: UUID) : WorldOps {
        val heals = mutableListOf<Double>()
        override fun validTarget(actor: UUID, target: UUID) = true
        override fun heal(target: UUID, amount: Double): Double { heals += amount; return amount }
        override fun damage(actor: UUID, target: UUID, amount: Double, damageType: String) = amount
        override fun position(entity: UUID) = Position("test", Vec(0.0, 0.0, 0.0))
        override fun view(actor: UUID, target: UUID) = EntityView(target, position(target), 20.0, 20.0, true)
        override fun loaded(position: Position) = true
        override fun candidates(actor: UUID, origin: Position, radius: Double, limit: Int) =
            listOf(EntityView(this.actor, position(this.actor), 20.0, 20.0, true))
    }

    private fun compile(lifetime: String = "lifetime: maintained"): CompileResult {
        val files = listOf(
            "workshop/pack.yaml" to "format: 1\nid: workshop\nname: Workshop",
            "workshop/class.yaml" to """kind: class
id: keeper
name: Keeper
abilities:
  aura:
    definition:
      name: Aura
      activation: {type: passive}
      effects: [{type: create_area, area: aura, anchor: {attached: actor}}]""",
            "workshop/other.yaml" to "kind: class\nid: other\nname: Other\nabilities:\n  action:\n    definition:\n      name: Action\n      effects: [{type: heal, target: actor, amount: 1}]",
            "workshop/aura.yaml" to """kind: area
id: aura
shape: {type: sphere, radius: 3}
$lifetime
sample_every: 50ms
targets: {type: living_entities, include_actor: true, line_of_sight: false, filters: [{type: relation, is: self}]}
enter: [{type: heal, target: target, amount: 1}]
periodic:
  every: 100ms
  effects: [{type: heal, target: target, amount: 2}]
expired: [{type: heal, target: actor, amount: 100}]""",
        )
        return ManifestCompiler().compile(PackSnapshot(files.map { SourceFile(it.first, it.second.toByteArray()) }, "v1"))
    }

    @Test fun `maintained passive area keeps pulsing and cancels without expiry gameplay`() {
        val compiled = compile()
        assertTrue(compiled is CompileResult.Valid, "$compiled")
        val world = World(actor)
        val runtime = AbilityRuntime(world)
        runtime.publish((compiled as CompileResult.Valid).definitions)
        assertTrue(runtime.selectClass(actor, "workshop:keeper"))
        assertEquals(listOf(1.0), world.heals)
        repeat(100) { runtime.tick(listOf(actor)) }
        assertEquals(50, world.heals.count { it == 2.0 })
        assertTrue(runtime.selectClass(actor, "workshop:other"))
        repeat(5) { runtime.tick(listOf(actor)) }
        assertEquals(0, world.heals.count { it == 100.0 })
        assertEquals(50, world.heals.count { it == 2.0 })
    }

    @Test fun `area lifetime form is exclusive and located`() {
        val both = compile("duration: 1s\nlifetime: maintained") as CompileResult.Invalid
        assertTrue(both.diagnostics.any { it.field == "duration" })
        val missing = compile("") as CompileResult.Invalid
        assertTrue(missing.diagnostics.any { it.field == "duration" })
        val invalid = compile("lifetime: forever") as CompileResult.Invalid
        assertTrue(invalid.diagnostics.any { it.field == "lifetime" })
    }
}
