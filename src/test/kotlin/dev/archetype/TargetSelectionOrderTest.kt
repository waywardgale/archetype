package dev.archetype

import dev.archetype.definitions.*
import dev.archetype.runtime.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.util.UUID

class TargetSelectionOrderTest {
    private val actor = UUID(0, 120)
    private val first = UUID(0, 1)
    private val second = UUID(0, 2)
    private val third = UUID(0, 3)
    private val origin = Position("test", Vec(0.0, 0.0, 0.0))
    private class World(private val views: List<EntityView>, private val rolls: ArrayDeque<Double> = ArrayDeque()) : WorldOps {
        override fun validTarget(actor: UUID, target: UUID) = true
        override fun heal(target: UUID, amount: Double) = amount
        override fun damage(actor: UUID, target: UUID, amount: Double, damageType: String) = amount
        override fun loaded(position: Position) = true
        override fun lineOfSight(origin: Position, target: UUID) = true
        override fun candidates(actor: UUID, origin: Position, radius: Double, limit: Int) = views
        override fun roll(actor: UUID) = rolls.removeFirst()
    }
    private fun views() = listOf(
        EntityView(third, Position("test", Vec(3.0, 0.0, 0.0)), 10.0, 10.0, false),
        EntityView(first, Position("test", Vec(1.0, 0.0, 0.0)), 10.0, 10.0, false),
        EntityView(second, Position("test", Vec(2.0, 0.0, 0.0)), 10.0, 10.0, false),
    )

    @Test fun farthestAndRandomSelectionAreBoundedAndStable() {
        val frame = Frame(origin)
        val selector = Selector(Shape.Sphere(4.0), limit = 2, order = TargetOrder.FARTHEST)
        assertEquals(listOf(third, second), TargetSelection(World(views())).select(actor, frame, selector) {})
        val random = selector.copy(order = TargetOrder.RANDOM)
        val world = World(views(), ArrayDeque(listOf(0.0, 0.0)))
        assertEquals(listOf(second, third), TargetSelection(world).select(actor, frame, random) {})
        val bad = World(views(), ArrayDeque(listOf(1.0)))
        assertThrows(IllegalArgumentException::class.java) { TargetSelection(bad).select(actor, frame, random) {} }
    }
}
