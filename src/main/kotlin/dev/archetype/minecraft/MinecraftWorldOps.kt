package dev.archetype.minecraft

import dev.archetype.runtime.WorldOps
import dev.archetype.definitions.EntityView
import dev.archetype.definitions.Position
import dev.archetype.definitions.Vec
import net.minecraft.core.BlockPos
import net.minecraft.core.registries.Registries
import net.minecraft.resources.Identifier
import net.minecraft.resources.ResourceKey
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerPlayer
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.damagesource.DamageSource
import net.minecraft.world.damagesource.DamageType
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.ai.attributes.AttributeModifier
import net.minecraft.world.entity.ai.attributes.Attributes
import net.minecraft.world.entity.projectile.ProjectileUtil
import net.minecraft.world.level.ClipContext
import net.minecraft.world.level.entity.EntityTypeTest
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.HitResult
import net.minecraft.world.phys.Vec3
import java.util.UUID

class MinecraftWorldOps(private val server: MinecraftServer) : WorldOps {
    private val speedBonusId = Identifier.parse("archetype:status_movement_speed")
    override fun movementSpeedBonus(target: UUID, amount: Double) {
        require(amount.isFinite() && amount in 0.0..1.0) { "invalid movement speed bonus" }
        val attribute = entity(target)?.getAttribute(Attributes.MOVEMENT_SPEED) ?: return
        // Source composition happens in the runtime; preserve base values and other mods' modifiers.
        if (amount == 0.0) attribute.removeModifier(speedBonusId)
        else attribute.addOrUpdateTransientModifier(AttributeModifier(speedBonusId, amount, AttributeModifier.Operation.ADD_VALUE))
    }
    private fun entity(id: UUID): LivingEntity? = server.allLevels.asSequence()
        .mapNotNull { it.getEntityInAnyDimension(id) as? LivingEntity }
        .firstOrNull()

    override fun validTarget(actor: UUID, target: UUID) = validTarget(actor, target, 32.0)

    override fun availableTarget(actor: UUID, target: UUID): Boolean {
        val player = server.playerList.getPlayer(actor) ?: return false
        val entity = entity(target) ?: return false
        return player.isAlive && entity.isAlive && entity.level() == player.level()
    }

    override fun validTarget(actor: UUID, target: UUID, range: Double): Boolean {
        val player = server.playerList.getPlayer(actor) ?: return false
        val entity = entity(target) ?: return false
        return availableTarget(actor, target) && player.distanceToSqr(entity) <= range * range && player.hasLineOfSight(entity)
    }

    override fun position(entity: UUID): Position? = entity(entity)?.let { living ->
        Position(living.level().dimension().identifier().toString(), living.position().vector())
    }

    override fun direction(entity: UUID): Vec = entity(entity)?.lookAngle?.vector() ?: Vec(0.0, 0.0, 1.0)

    private fun level(position: Position) = server.allLevels.firstOrNull { it.dimension().identifier().toString() == position.dimension }

    override fun loaded(position: Position): Boolean = position.value.finite() && level(position)?.let { level ->
        val block = BlockPos.containing(position.value.x, position.value.y, position.value.z)
        level.chunkSource.getChunkNow(block.x shr 4, block.z shr 4) != null
    } == true

    override fun ground(actor: UUID, range: Double): Position? {
        val player = server.playerList.getPlayer(actor) ?: return null
        val start = player.eyePosition
        val end = start.add(player.lookAngle.scale(range))
        if (!loadedPath(player.level(), start, end)) return null
        // ASVS 2.2.2: the authenticated actor's server position and view determine ground placement.
        val hit = player.level().clip(ClipContext(start, end, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, player))
        if (hit.type != HitResult.Type.BLOCK) return null
        val point = Position(player.level().dimension().identifier().toString(), hit.location.vector())
        return point.takeIf { loaded(it) }
    }

    override fun aim(actor: UUID, range: Double): UUID? {
        val player = server.playerList.getPlayer(actor) ?: return null
        val start = player.eyePosition
        val end = start.add(player.lookAngle.scale(range))
        if (!loadedPath(player.level(), start, end)) return null
        val wall = player.level().clip(ClipContext(start, end, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, player))
        val stop = if (wall.type == HitResult.Type.BLOCK) wall.location else end
        val found = mutableListOf<LivingEntity>()
        player.level().getEntities(EntityTypeTest.forClass(LivingEntity::class.java), AABB(start, stop).inflate(1.0),
            { it.isAlive && it.uuid != actor }, found, 513)
        if (found.size > 512) return null
        return found.mapNotNull { entity ->
            val box = entity.boundingBox.inflate(ProjectileUtil.computeMargin(entity).toDouble())
            val hit = if (box.contains(start)) start else box.clip(start, stop).orElse(null) ?: return@mapNotNull null
            entity.uuid to start.distanceToSqr(hit)
        }.sortedWith(compareBy<Pair<UUID, Double>> { it.second }.thenBy { it.first.toString() }).firstOrNull()?.first
    }

    override fun candidates(actor: UUID, origin: Position, radius: Double, limit: Int): List<EntityView> {
        val player = server.playerList.getPlayer(actor) ?: return emptyList()
        val level = level(origin) ?: return emptyList()
        if (level != player.level() || !loaded(origin)) return emptyList()
        val p = origin.value
        val bounds = AABB(p.x - radius, p.y - radius, p.z - radius, p.x + radius, p.y + radius, p.z + radius).inflate(0.001)
        val found = mutableListOf<LivingEntity>()
        // Native bounded iteration visits loaded entity sections and never requests chunks.
        level.getEntities(EntityTypeTest.forClass(LivingEntity::class.java), bounds, { it.isAlive }, found, limit)
        return found.map { view(player, it) }
    }

    override fun view(actor: UUID, target: UUID): EntityView? {
        val player = server.playerList.getPlayer(actor) ?: return null
        val entity = entity(target) ?: return null
        return view(player, entity)
    }

    private fun view(player: ServerPlayer, entity: LivingEntity) = EntityView(
        entity.uuid, Position(entity.level().dimension().identifier().toString(), entity.position().vector()), entity.health.toDouble(), entity.maxHealth.toDouble(),
        entity.uuid == player.uuid || entity is ServerPlayer || player.isAlliedTo(entity),
    )

    override fun lineOfSight(origin: Position, target: UUID): Boolean {
        val level = level(origin) ?: return false
        val targetEntity = entity(target) ?: return false
        if (targetEntity.level() != level || !loaded(origin)) return false
        val p = origin.value
        val start = Vec3(p.x, p.y + 0.1, p.z)
        val end = targetEntity.eyePosition
        // Clip loaded blocks only. Unavailable chunks obstruct gameplay selection.
        if (!loadedPath(level, start, end)) return false
        return level.clip(ClipContext(start, end, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, targetEntity)).type == HitResult.Type.MISS
    }

    private fun loadedPath(level: ServerLevel, start: Vec3, end: Vec3): Boolean {
        if (start.distanceToSqr(end) > 128.0 * 128.0) return false
        val from = BlockPos.containing(start)
        val to = BlockPos.containing(end)
        for (x in (minOf(from.x, to.x) shr 4)..(maxOf(from.x, to.x) shr 4))
            for (z in (minOf(from.z, to.z) shr 4)..(maxOf(from.z, to.z) shr 4))
                if (level.chunkSource.getChunkNow(x, z) == null) return false
        return true
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
        if (!availableTarget(actor, target)) return 0.0
        // Players are allies by default. No friendly-fire override is exposed in this slice.
        if (entity is ServerPlayer || player.isAlliedTo(entity)) return 0.0
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

private fun Vec3.vector() = Vec(x, y, z)
