package dev.archetype

import dev.archetype.definitions.*
import dev.archetype.runtime.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.util.UUID

class FacingSelectionTest {
    private val actor = UUID(0, 1)
    private val toward = UUID(0, 2)
    private val away = UUID(0, 3)
    private val origin = Position("test", Vec(0.0, 0.0, 0.0))

    private inner class World : WorldOps {
        val views = listOf(
            EntityView(toward, Position("test", Vec(0.0, 0.0, 5.0)), 20.0, 20.0, false, "minecraft:pig"),
            EntityView(away, Position("test", Vec(5.0, 0.0, 0.0)), 20.0, 20.0, false, "minecraft:zombie"),
        )
        override fun validTarget(actor: UUID, target: UUID) = true
        override fun heal(target: UUID, amount: Double) = amount
        override fun damage(actor: UUID, target: UUID, amount: Double, damageType: String) = amount
        override fun loaded(position: Position) = true
        override fun lineOfSight(origin: Position, target: UUID) = true
        override fun direction(entity: UUID) = when (entity) {
            toward -> Vec(0.0, 0.0, -1.0)
            away -> Vec(1.0, 0.0, 0.0)
            else -> Vec(0.0, 0.0, 1.0)
        }
        override fun candidates(actor: UUID, origin: Position, radius: Double, limit: Int) = views
    }

    @Test fun `facing filter compares live entity look to the selection origin`() {
        val selection = TargetSelection(World())
        val selector = Selector(Shape.Sphere(8.0), maximumFacingAngle = 45.0)
        assertEquals(listOf(toward), selection.select(actor, Frame(origin), selector) {})
        assertEquals(listOf(toward, away), selection.select(actor, Frame(origin), selector.copy(maximumFacingAngle = null)) {})
    }

    @Test fun `facing angle outside its finite bound is located at authoring field`() {
        val files = listOf(
            "workshop/pack.yaml" to "format: 1\nid: workshop\nname: Workshop",
            "workshop/class.yaml" to "kind: class\nid: fighter\nname: Fighter\nabilities:\n  scan: {ref: scan}",
            "workshop/scan.yaml" to """kind: ability
id: scan
name: Scan
effects:
  - type: for_each
    origin: actor
    targets:
      type: living_entities
      shape: {type: sphere, radius: 8}
      filters: [{type: facing_origin, max_degrees: 181}]
    effects: [{type: heal, target: target, amount: 1}]""",
        )
        val result = ManifestCompiler().compile(PackSnapshot(files.map { SourceFile(it.first, it.second.toByteArray()) }, "facing"))
        assertTrue(result is CompileResult.Invalid, "$result")
        assertTrue((result as CompileResult.Invalid).diagnostics.any { it.field.contains("max_degrees") }, "$result")
    }

    @Test fun `entity type filter selects only the declared native entity ID`() {
        val selection = TargetSelection(World())
        val selector = Selector(Shape.Sphere(8.0), entityType = "minecraft:zombie")
        assertEquals(listOf(away), selection.select(actor, Frame(origin), selector) {})
        assertEquals(emptyList<UUID>(), selection.select(actor, Frame(origin), selector.copy(entityType = "minecraft:sheep")) {})
    }
}
