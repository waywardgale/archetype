package dev.archetype.minecraft

import dev.archetype.runtime.WorldOps
import net.minecraft.core.registries.Registries
import net.minecraft.resources.Identifier
import net.minecraft.resources.ResourceKey
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.damagesource.DamageSource
import net.minecraft.world.damagesource.DamageType
import net.minecraft.world.entity.LivingEntity
import java.util.UUID

class MinecraftWorldOps(private val server: MinecraftServer) : WorldOps {
    private fun entity(id: UUID): LivingEntity? = server.allLevels.asSequence()
        .mapNotNull { it.getEntityInAnyDimension(id) as? LivingEntity }
        .firstOrNull()

    override fun validTarget(actor: UUID, target: UUID): Boolean {
        val player = server.playerList.getPlayer(actor) ?: return false
        val entity = entity(target) ?: return false
        return entity.isAlive && entity.level() == player.level() && player.distanceToSqr(entity) <= 32.0 * 32.0 && player.hasLineOfSight(entity)
    }

    override fun heal(target: UUID, amount: Double): Double {
        val entity = entity(target) ?: return 0.0
        val before = entity.health
        entity.heal(amount.toFloat())
        return (entity.health - before).toDouble().coerceAtLeast(0.0)
    }

    override fun damage(actor: UUID, target: UUID, amount: Double, damageType: String): Double {
        val player = server.playerList.getPlayer(actor) ?: return 0.0
        val entity = entity(target) ?: return 0.0
        if (!validTarget(actor, target)) return 0.0
        // Players are allies by default. No friendly-fire override is exposed in this slice.
        if (entity is ServerPlayer) return 0.0
        val key = ResourceKey.create(Registries.DAMAGE_TYPE, Identifier.parse(damageType))
        val holder = entity.level().registryAccess().lookupOrThrow(Registries.DAMAGE_TYPE).getOrThrow(key)
        val source = DamageSource(holder, player)
        val before = entity.health
        entity.hurtServer(player.level(), source, amount.toFloat())
        return (before - entity.health).toDouble().coerceAtLeast(0.0)
    }

    fun supportsDamageType(id: String): Boolean = try {
        val key: ResourceKey<DamageType> = ResourceKey.create(Registries.DAMAGE_TYPE, Identifier.parse(id))
        server.overworld().registryAccess().lookupOrThrow(Registries.DAMAGE_TYPE).get(key).isPresent
    } catch (_: Exception) { false }
}
