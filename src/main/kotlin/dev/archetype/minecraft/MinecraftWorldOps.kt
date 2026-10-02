package dev.archetype.minecraft

import dev.archetype.runtime.WorldOps
import dev.archetype.runtime.ProjectileContact
import dev.archetype.runtime.MotionResult
import dev.archetype.runtime.TerrainAccess
import dev.archetype.runtime.TerrainEdit
import dev.archetype.runtime.TerrainJournal
import dev.archetype.runtime.TerrainPos
import dev.archetype.definitions.BlockPatternDef
import dev.archetype.definitions.PatternMirror
import dev.archetype.definitions.Effect
import dev.archetype.definitions.TerrainOperation
import dev.archetype.definitions.TerrainRegion
import dev.archetype.definitions.TerrainFilter
import dev.archetype.definitions.ProjectileEntities
import dev.archetype.definitions.EntityView
import dev.archetype.definitions.Position
import dev.archetype.definitions.Vec
import net.minecraft.core.BlockPos
import net.minecraft.commands.arguments.blocks.BlockStateParser
import net.minecraft.core.Direction
import net.minecraft.tags.TagKey
import net.minecraft.core.particles.ParticleTypes
import net.minecraft.core.registries.Registries
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.resources.Identifier
import net.minecraft.resources.ResourceKey
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerPlayer
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.damagesource.DamageSource
import net.minecraft.world.damagesource.DamageType
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.MoverType
import net.minecraft.world.entity.ai.attributes.AttributeModifier
import net.minecraft.world.entity.ai.attributes.Attributes
import net.minecraft.world.entity.projectile.ProjectileUtil
import net.minecraft.world.level.block.FallingBlock
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.ClipContext
import net.minecraft.world.level.entity.EntityTypeTest
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.HitResult
import net.minecraft.world.phys.Vec3
import java.util.UUID
import java.nio.file.Path
import kotlin.math.roundToInt

class MinecraftWorldOps @JvmOverloads constructor(private val server: MinecraftServer, terrainPath: Path? = null) : WorldOps {
    private val terrain = terrainPath?.let { path -> TerrainJournal(path, object : TerrainAccess {
        override fun loaded(position: TerrainPos): Boolean = terrainLevel(position)?.let { level ->
            level.chunkSource.getChunkNow(position.x shr 4, position.z shr 4) != null
        } == true

        override fun state(position: TerrainPos): String? = terrainLevel(position)?.takeIf { loaded(position) }
            ?.getBlockState(BlockPos(position.x, position.y, position.z))?.let(BlockStateParser::serialize)

        override fun mayEdit(actor: UUID, position: TerrainPos, state: String): Boolean {
            val player = server.playerList.getPlayer(actor) ?: return false
            val level = terrainLevel(position) ?: return false
            val pos = BlockPos(position.x, position.y, position.z)
            val parsed = parseBlockState(state) ?: return false
            return player.level() == level && loaded(position) && !level.isOutsideBuildHeight(pos) &&
                level.worldBorder.isWithinBounds(pos) && level.mayInteract(player, pos) &&
                level.getBlockEntity(pos) == null && !level.getBlockState(pos).hasBlockEntity() &&
                !parsed.hasBlockEntity()
        }

        override fun write(position: TerrainPos, state: String): Boolean {
            val level = terrainLevel(position) ?: return false
            if (!loaded(position)) return false
            val parsed = parseBlockState(state) ?: return false
            return TerrainBridge.ownedWrite(position) {
                level.setBlock(BlockPos(position.x, position.y, position.z), parsed, 3)
            }
        }
        override fun breakWithLoot(actor: UUID, position: TerrainPos): Boolean {
            val player = server.playerList.getPlayer(actor) ?: return false
            val level = terrainLevel(position) ?: return false
            if (!loaded(position) || player.level() != level) return false
            return TerrainBridge.ownedWrite(position) {
                level.destroyBlock(BlockPos(position.x, position.y, position.z), true, player, 512)
            }
        }
    }).also { TerrainBridge.journal = it; it.recoverAfterRestart() } }

    private fun terrainLevel(position: TerrainPos): ServerLevel? = server.allLevels.firstOrNull {
        it.dimension().identifier().toString() == position.dimension
    }

    private fun parseBlockState(value: String): BlockState? = try {
        BlockStateParser.parseForBlock(server.registryAccess().lookupOrThrow(Registries.BLOCK), value, false).blockState()
    } catch (_: Exception) { null }

    fun supportsBlockState(value: String): Boolean = parseBlockState(value) != null
    fun supportsBlockTag(value: String): Boolean = try {
        server.registryAccess().lookupOrThrow(Registries.BLOCK)
            .get(TagKey.create(Registries.BLOCK, Identifier.parse(value))).isPresent
    } catch (_: Exception) { false }
    fun tickTerrain() { terrain?.tick() }
    fun terrainChunkLoaded(level: ServerLevel, chunkX: Int, chunkZ: Int) {
        terrain?.recoverChunk(level.dimension().identifier().toString(), chunkX, chunkZ)
    }
    fun closeTerrain() { if (TerrainBridge.journal === terrain) TerrainBridge.journal = null }
    fun terrainPlayerBreak(level: ServerLevel, pos: BlockPos): Boolean {
        val key = TerrainBridge.key(level, pos)
        if (terrain?.owned(key) != true) return false
        terrain.externalWrite(key)
        level.setBlock(pos, Blocks.AIR.defaultBlockState(), 3)
        return true
    }

    override fun terrainActive(source: UUID): Boolean = terrain?.active(source) == true
    override fun cancelTerrain(source: UUID) { terrain?.cancel(source) }
    override fun placePattern(actor: UUID, source: UUID, pattern: BlockPatternDef, at: Position,
        rotation: Int, mirror: PatternMirror, durationTicks: Int?, allowFluid: Boolean,
        allowGravity: Boolean): Int? {
        val journal = terrain ?: return null
        val level = level(at) ?: return null
        val origin = BlockPos.containing(at.value.x, at.value.y, at.value.z)
        val edits = pattern.cells.map { cell ->
            var x = if (mirror == PatternMirror.X) -cell.x else cell.x
            var z = if (mirror == PatternMirror.Z) -cell.z else cell.z
            repeat(rotation / 90) { val nextX = -z; z = x; x = nextX }
            TerrainEdit(TerrainBridge.key(level, origin.offset(x, cell.y, z)), cell.block)
        }
        if (edits.isEmpty() || edits.any { level.chunkSource.getChunkNow(it.position.x shr 4, it.position.z shr 4) == null }) return null
        val allowed = edits.filterNot { edit ->
            val pos = BlockPos(edit.position.x, edit.position.y, edit.position.z)
            level.getBlockEntity(pos) != null || level.getBlockState(pos).hasBlockEntity()
        }
        if (allowed.any { edit ->
                val state = parseBlockState(edit.state) ?: return@any true
                (!allowFluid && !state.fluidState.isEmpty) || (!allowGravity && state.block is FallingBlock)
            }) return null
        return if (allowed.isEmpty()) 0 else journal.place(actor, source, allowed, durationTicks)
    }

    override fun editTerrain(actor: UUID, source: UUID, edit: Effect.EditTerrain, at: Position): Int? {
        val journal = terrain ?: return null
        val level = level(at) ?: return null
        val origin = BlockPos.containing(at.value.x, at.value.y, at.value.z)
        val offsets = when (val region = edit.region) {
            TerrainRegion.Point -> listOf(BlockPos.ZERO)
            is TerrainRegion.Line -> {
                val steps = maxOf(kotlin.math.abs(region.x), kotlin.math.abs(region.y), kotlin.math.abs(region.z))
                (0..steps).map { step -> BlockPos((region.x * step.toDouble() / steps).roundToInt(),
                    (region.y * step.toDouble() / steps).roundToInt(), (region.z * step.toDouble() / steps).roundToInt()) }.distinct()
            }
            is TerrainRegion.Box -> (0 until region.width).flatMap { x -> (0 until region.height).flatMap { y ->
                (0 until region.depth).map { z -> BlockPos(x, y, z) }
            } }
            is TerrainRegion.Sphere -> (-region.radius..region.radius).flatMap { x ->
                (-region.radius..region.radius).flatMap { y -> (-region.radius..region.radius).mapNotNull { z ->
                    BlockPos(x, y, z).takeIf { x * x + y * y + z * z <= region.radius * region.radius }
                } }
            }
        }
        val positions = offsets.map { origin.offset(it.x, it.y, it.z) }
        if (positions.size > 256 || positions.any { level.chunkSource.getChunkNow(it.x shr 4, it.z shr 4) == null }) return null
        val desired = if (edit.operation == TerrainOperation.BREAK) "minecraft:air" else edit.block ?: return null
        val state = parseBlockState(desired) ?: return null
        if ((!edit.allowFluid && !state.fluidState.isEmpty) || (!edit.allowGravity && state.block is FallingBlock)) return null
        val selected = positions.filter { pos ->
            val current = level.getBlockState(pos)
            !current.hasBlockEntity() && level.getBlockEntity(pos) == null &&
                (edit.operation != TerrainOperation.BREAK || !current.isAir) && matchesTerrainFilter(current, edit.filter)
        }.map { pos -> TerrainEdit(TerrainBridge.key(level, pos), desired, edit.loot) }
        return if (selected.isEmpty()) 0 else journal.place(actor, source, selected, edit.durationTicks)
    }

    private fun matchesTerrainFilter(state: BlockState, filter: TerrainFilter): Boolean = !filter.defined ||
        BuiltInRegistries.BLOCK.getKey(state.block).toString() in filter.blocks || filter.tags.any { id ->
            state.`is`(TagKey.create(Registries.BLOCK, Identifier.parse(id)))
        }
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

    override fun displace(entity: UUID, direction: Vec, distance: Double): MotionResult? {
        val subject = entity(entity) ?: return null
        if (!subject.isAlive || !direction.finite() || direction.lengthSquared() !in 0.999..1.001 ||
            !distance.isFinite() || distance !in 0.01..32.0) return null
        val start = subject.position()
        val requested = Vec3(direction.x * distance, direction.y * distance, direction.z * distance)
        val level = subject.level()
        // Include the whole body for wide entities and its swept path before invoking native collision.
        val path = subject.boundingBox.expandTowards(requested).inflate(0.1)
        val minX = BlockPos.containing(path.minX, 0.0, 0.0).x shr 4
        val maxX = BlockPos.containing(path.maxX, 0.0, 0.0).x shr 4
        val minZ = BlockPos.containing(0.0, 0.0, path.minZ).z shr 4
        val maxZ = BlockPos.containing(0.0, 0.0, path.maxZ).z shr 4
        if (maxX - minX > 8 || maxZ - minZ > 8) return null
        for (chunkX in minX..maxX) for (chunkZ in minZ..maxZ)
            if (level.chunkSource.getChunkNow(chunkX, chunkZ) == null) return null
        subject.move(MoverType.SELF, requested)
        val actual = subject.position().subtract(start)
        // Project onto the requested direction; native step-up can add vertical distance without extending the dash.
        val travelled = (actual.x * direction.x + actual.y * direction.y + actual.z * direction.z)
            .coerceIn(0.0, distance)
        if (subject is ServerPlayer) subject.teleportTo(subject.x, subject.y, subject.z)
        return MotionResult(travelled, travelled < distance - 0.01)
    }

    override fun safeTeleport(entity: UUID, destination: Position): Boolean? {
        val subject = entity(entity) ?: return null
        if (!subject.isAlive || subject.level().dimension().identifier().toString() != destination.dimension ||
            !destination.value.finite()) return null
        val level = subject.level()
        val current = subject.position()
        val point = Vec3(destination.value.x, destination.value.y, destination.value.z)
        if (current.distanceToSqr(point) > 32.0 * 32.0) return null
        val bounds = subject.boundingBox.move(point.subtract(current))
        val minX = BlockPos.containing(bounds.minX, 0.0, 0.0).x shr 4
        val maxX = BlockPos.containing(bounds.maxX, 0.0, 0.0).x shr 4
        val minZ = BlockPos.containing(0.0, 0.0, bounds.minZ).z shr 4
        val maxZ = BlockPos.containing(0.0, 0.0, bounds.maxZ).z shr 4
        if (maxX - minX > 8 || maxZ - minZ > 8) return null
        for (chunkX in minX..maxX) for (chunkZ in minZ..maxZ)
            if (level.chunkSource.getChunkNow(chunkX, chunkZ) == null) return null
        val below = BlockPos.containing(point.x, point.y - 0.1, point.z)
        val feet = BlockPos.containing(point)
        val head = BlockPos.containing(point.x, point.y + 1.0, point.z)
        if (level.isOutsideBuildHeight(below) || !level.worldBorder.isWithinBounds(bounds) ||
            !level.getBlockState(below).isFaceSturdy(level, below, Direction.UP) ||
            !level.getBlockState(feet).isAir || !level.getBlockState(head).isAir ||
            !level.noCollision(subject, bounds)) return false
        subject.teleportTo(point.x, point.y, point.z)
        return true
    }

    override fun projectileOrigin(actor: UUID): Position? = server.playerList.getPlayer(actor)?.let { player ->
        Position(player.level().dimension().identifier().toString(), player.eyePosition.vector())
    }

    override fun projectileStep(actor: UUID, from: Position, to: Position, entities: ProjectileEntities, excluded: Set<UUID>): ProjectileContact {
        val player = server.playerList.getPlayer(actor) ?: return ProjectileContact.Unavailable
        val level = level(from) ?: return ProjectileContact.Unavailable
        if (from.dimension != to.dimension || player.level() != level || !player.isAlive) return ProjectileContact.Unavailable
        val start = Vec3(from.value.x, from.value.y, from.value.z)
        val end = Vec3(to.value.x, to.value.y, to.value.z)
        if (!loadedPath(level, start, end)) return ProjectileContact.Unavailable
        val block = level.clip(ClipContext(start, end, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, player))
        val stop = if (block.type == HitResult.Type.BLOCK) block.location else end
        val found = mutableListOf<LivingEntity>()
        level.getEntities(EntityTypeTest.forClass(LivingEntity::class.java), AABB(start, stop).inflate(1.0),
            { entity -> entity.isAlive && entity.uuid != actor && entity.uuid !in excluded && when (entities) {
                ProjectileEntities.ENEMIES -> !view(player, entity).ally
                ProjectileEntities.ALLIES -> view(player, entity).ally
                ProjectileEntities.ANY -> true
            } }, found, 513)
        if (found.size > 512) return ProjectileContact.Unavailable
        val entityHit = found.mapNotNull { entity ->
            val box = entity.boundingBox.inflate(ProjectileUtil.computeMargin(entity).toDouble())
            val point = if (box.contains(start)) start else box.clip(start, stop).orElse(null) ?: return@mapNotNull null
            Triple(entity.uuid, point, start.distanceToSqr(point))
        }.sortedWith(compareBy<Triple<UUID, Vec3, Double>> { it.third }.thenBy { it.first.toString() }).firstOrNull()
        if (entityHit != null) return ProjectileContact.Entity(entityHit.first, Position(from.dimension, entityHit.second.vector()))
        if (block.type == HitResult.Type.BLOCK) return ProjectileContact.Block(Position(from.dimension, block.location.vector()),
            Vec(block.direction.stepX.toDouble(), block.direction.stepY.toDouble(), block.direction.stepZ.toDouble()))
        return ProjectileContact.Miss(to)
    }

    override fun projectileVisible(position: Position) {
        val level = level(position) ?: return
        val p = position.value
        level.sendParticles(ParticleTypes.CRIT, p.x, p.y, p.z, 1, 0.0, 0.0, 0.0, 0.0)
    }

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

    override fun nearbyAlliedPlayers(actor: UUID, origin: Position, radius: Double, limit: Int): List<UUID> {
        val owner = server.playerList.getPlayer(actor) ?: return emptyList()
        if (radius !in 0.1..64.0 || limit !in 1..128 || origin.dimension != owner.level().dimension().identifier().toString())
            return emptyList()
        val radiusSquared = radius * radius
        return server.playerList.players.asSequence().filter { candidate ->
            candidate.isAlive && candidate.level() == owner.level() &&
                (candidate.uuid == actor || owner.isAlliedTo(candidate)) &&
                (candidate.x - origin.value.x) * (candidate.x - origin.value.x) +
                    (candidate.y - origin.value.y) * (candidate.y - origin.value.y) +
                    (candidate.z - origin.value.z) * (candidate.z - origin.value.z) <= radiusSquared
        }.map { it.uuid }.sortedBy(UUID::toString).take(limit).toList()
    }

    private fun view(player: ServerPlayer, entity: LivingEntity) = EntityView(
        entity.uuid, Position(entity.level().dimension().identifier().toString(), entity.position().vector()), entity.health.toDouble(), entity.maxHealth.toDouble(),
        entity.uuid == player.uuid || entity is ServerPlayer || player.isAlliedTo(entity),
        BuiltInRegistries.ENTITY_TYPE.getKey(entity.type).toString(),
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
