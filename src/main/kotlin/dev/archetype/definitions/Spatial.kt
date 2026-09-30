package dev.archetype.definitions

import java.util.UUID
import kotlin.math.*

data class Vec(val x: Double, val y: Double, val z: Double) {
    operator fun plus(other: Vec) = Vec(x + other.x, y + other.y, z + other.z)
    operator fun minus(other: Vec) = Vec(x - other.x, y - other.y, z - other.z)
    operator fun times(scale: Double) = Vec(x * scale, y * scale, z * scale)
    fun dot(other: Vec) = x * other.x + y * other.y + z * other.z
    fun cross(other: Vec) = Vec(y * other.z - z * other.y, z * other.x - x * other.z, x * other.y - y * other.x)
    fun lengthSquared() = dot(this)
    fun normalized(): Vec = lengthSquared().let { if (it > 1e-12) this * (1 / sqrt(it)) else Vec(0.0, 0.0, 1.0) }
    fun finite() = x.isFinite() && y.isFinite() && z.isFinite()
}

data class Position(val dimension: String, val value: Vec)
data class Frame(val position: Position, val forward: Vec = Vec(0.0, 0.0, 1.0)) {
    fun local(point: Vec): Vec {
        val f = forward.normalized()
        val reference = if (abs(f.y) > 0.999) Vec(0.0, 0.0, 1.0) else Vec(0.0, 1.0, 0.0)
        val right = reference.cross(f).normalized()
        val up = f.cross(right)
        val delta = point - position.value
        return Vec(delta.dot(right), delta.dot(up), delta.dot(f))
    }
}

/** One geometry contract for server selection and future client previews. Tests use entity feet. */
sealed interface Shape {
    val bound: Double
    fun contains(local: Vec): Boolean
    data class Point(val radius: Double = 0.25) : Shape {
        override val bound = radius
        override fun contains(local: Vec) = local.lengthSquared() <= radius * radius
    }
    data class Sphere(val radius: Double) : Shape {
        override val bound = radius
        override fun contains(local: Vec) = local.lengthSquared() <= radius * radius
    }
    data class Cylinder(val radius: Double, val height: Double) : Shape {
        override val bound = sqrt(radius * radius + height * height / 4)
        override fun contains(local: Vec) = local.x * local.x + local.z * local.z <= radius * radius && abs(local.y) <= height / 2
    }
    data class Ring(val innerRadius: Double, val outerRadius: Double, val height: Double) : Shape {
        override val bound = sqrt(outerRadius * outerRadius + height * height / 4)
        override fun contains(local: Vec): Boolean = (local.x * local.x + local.z * local.z) in innerRadius * innerRadius..outerRadius * outerRadius && abs(local.y) <= height / 2
    }
    data class Box(val width: Double, val height: Double, val depth: Double) : Shape {
        override val bound = sqrt(width * width + height * height + depth * depth) / 2
        override fun contains(local: Vec) = abs(local.x) <= width / 2 && abs(local.y) <= height / 2 && abs(local.z) <= depth / 2
    }
    data class Segment(val length: Double, val radius: Double) : Shape {
        override val bound = length + radius
        override fun contains(local: Vec): Boolean {
            val closest = Vec(0.0, 0.0, local.z.coerceIn(0.0, length))
            return (local - closest).lengthSquared() <= radius * radius
        }
    }
    data class Cone(val range: Double, val angleDegrees: Double) : Shape {
        override val bound = range
        override fun contains(local: Vec): Boolean {
            val distance = sqrt(local.lengthSquared())
            return distance <= range && (distance == 0.0 || local.z / distance >= cos(Math.toRadians(angleDegrees / 2)))
        }
    }
}

enum class Relation { ANY, ALLY, ENEMY, SELF }
enum class TargetOrder { NEAREST, FARTHEST, LOWEST_HEALTH, HIGHEST_HEALTH, RANDOM }
data class Selector(
    val shape: Shape,
    val limit: Int = 16,
    val relation: Relation = Relation.ANY,
    val lineOfSight: Boolean = true,
    val includeActor: Boolean = false,
    val order: TargetOrder = TargetOrder.NEAREST,
    val minimumHealth: Double = 0.0,
    val maximumHealth: Double = 1.0,
)
data class EntityView(val id: UUID, val position: Position, val health: Double, val maximumHealth: Double, val ally: Boolean)

sealed interface Anchor {
    data class Fixed(val position: SpatialTarget) : Anchor
    data class Attached(val entity: EffectTarget) : Anchor
}
enum class SpatialTarget { ACTOR, TARGET, GROUND }
data class Targeting(val type: Type = Type.ENTITY, val range: Double = 32.0) {
    enum class Type { ENTITY, GROUND }
}
