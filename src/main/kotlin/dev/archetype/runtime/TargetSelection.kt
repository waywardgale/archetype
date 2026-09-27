package dev.archetype.runtime

import dev.archetype.definitions.*
import java.util.UUID

class TargetSelection(private val world: WorldOps) {
    fun select(actor: UUID, frame: Frame, selector: Selector, excluded: Set<UUID> = emptySet(), charge: (Int) -> Unit): List<UUID> {
        require(frame.position.value.finite() && frame.forward.finite()) { "invalid selection frame" }
        if (!world.loaded(frame.position)) return emptyList()
        val candidates = world.candidates(actor, frame.position, selector.shape.bound, 513)
        // ASVS 2.2.1, 2.3.2: reject an overloaded query instead of choosing an arbitrary prefix.
        require(candidates.size <= 512) { "target query exceeds 512 candidates" }
        charge(candidates.size + 1)
        val eligible = candidates.asSequence().distinctBy { it.id }.filter { it.id !in excluded && matches(actor, frame, selector, it) }
        val ordered = when (selector.order) {
            TargetOrder.NEAREST -> compareBy<EntityView> { (it.position.value - frame.position.value).lengthSquared() }
            TargetOrder.LOWEST_HEALTH -> compareBy { it.health / it.maximumHealth }
            TargetOrder.HIGHEST_HEALTH -> compareByDescending { it.health / it.maximumHealth }
        }.thenBy { it.id.toString() }
        return eligible.sortedWith(ordered).take(selector.limit).map { it.id }.toList()
    }

    fun matches(actor: UUID, frame: Frame, selector: Selector, entity: EntityView): Boolean {
        if (entity.position.dimension != frame.position.dimension || !entity.position.value.finite() ||
            !entity.health.isFinite() || !entity.maximumHealth.isFinite() || entity.health <= 0 || entity.maximumHealth <= 0) return false
        if (!selector.includeActor && entity.id == actor) return false
        val relationship = when (selector.relation) {
            Relation.ANY -> true
            Relation.ALLY -> entity.ally
            Relation.ENEMY -> !entity.ally
            Relation.SELF -> entity.id == actor
        }
        return relationship && entity.health / entity.maximumHealth in selector.minimumHealth..selector.maximumHealth &&
            selector.shape.contains(frame.local(entity.position.value)) &&
            (!selector.lineOfSight || world.lineOfSight(frame.position, entity.id))
    }
}
