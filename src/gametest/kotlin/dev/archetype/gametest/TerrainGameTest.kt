package dev.archetype.gametest

import dev.archetype.minecraft.TerrainBridge
import dev.archetype.minecraft.MinecraftWorldOps
import dev.archetype.definitions.BlockPatternDef
import dev.archetype.definitions.PatternCell
import dev.archetype.definitions.PatternMirror
import dev.archetype.definitions.Position
import dev.archetype.definitions.Vec
import dev.archetype.definitions.Effect
import dev.archetype.definitions.SpatialTarget
import dev.archetype.definitions.TerrainFilter
import dev.archetype.definitions.TerrainOperation
import dev.archetype.definitions.TerrainRegion
import dev.archetype.runtime.TerrainAccess
import dev.archetype.runtime.TerrainEdit
import dev.archetype.runtime.TerrainJournal
import dev.archetype.runtime.TerrainPos
import net.fabricmc.fabric.api.gametest.v1.CustomTestMethodInvoker
import net.fabricmc.fabric.api.gametest.v1.GameTest
import net.minecraft.commands.arguments.blocks.BlockStateParser
import net.minecraft.core.BlockPos
import net.minecraft.gametest.framework.GameTestHelper
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.entity.EntityTypes
import java.lang.reflect.Method
import java.nio.file.Files
import java.util.UUID

class TerrainGameTest : CustomTestMethodInvoker {
    override fun invokeTestMethod(helper: GameTestHelper, method: Method) { method.invoke(this, helper) }

    @GameTest
    fun authoredPatternUsesNativePlacementAndRestoration(helper: GameTestHelper) {
        val player = helper.makeMockServerPlayerInLevel()
        val level = helper.level
        val pos = helper.absolutePos(BlockPos(2, 1, 2))
        helper.setBlock(2, 1, 2, Blocks.STONE)
        val previous = TerrainBridge.journal
        val directory = Files.createTempDirectory("archetype-native-pattern-")
        val world = MinecraftWorldOps(level.server, directory.resolve("terrain.bin"))
        try {
            check(world.supportsBlockTag("minecraft:flowers") && !world.supportsBlockTag("minecraft:missing_terrain_tag"))
            val pattern = BlockPatternDef("test:single", listOf(PatternCell(0, 0, 0, "minecraft:stone_bricks")))
            val at = Position(level.dimension().identifier().toString(), Vec(pos.x.toDouble(), pos.y.toDouble(), pos.z.toDouble()))
            val source = UUID(0, 475)
            check(world.placePattern(player.uuid, source, pattern, at, 0, PatternMirror.NONE, 20, false, false) == 1) {
                "native adapter rejected a loaded ordinary block pattern"
            }
            check(level.getBlockState(pos).`is`(Blocks.STONE_BRICKS) && world.terrainActive(source))
            world.cancelTerrain(source)
            check(level.getBlockState(pos).`is`(Blocks.STONE) && !world.terrainActive(source))
            val neighbor = pos.offset(1, 0, 0)
            level.setBlock(neighbor, Blocks.DIRT.defaultBlockState(), 3)
            val replace = Effect.EditTerrain(TerrainOperation.REPLACE, SpatialTarget.GROUND,
                TerrainRegion.Box(2, 1, 1), "minecraft:stone_bricks",
                TerrainFilter(blocks = setOf("minecraft:stone")), 20, false, false, false, null)
            check(world.editTerrain(player.uuid, source, replace, at) == 1)
            check(level.getBlockState(pos).`is`(Blocks.STONE_BRICKS) && level.getBlockState(neighbor).`is`(Blocks.DIRT))
            world.cancelTerrain(source)
            check(level.getBlockState(pos).`is`(Blocks.STONE))
            val breakEdit = Effect.EditTerrain(TerrainOperation.BREAK, SpatialTarget.GROUND,
                TerrainRegion.Point, null, TerrainFilter(), null, false, false, true, null)
            check(world.editTerrain(player.uuid, UUID(0, 476), breakEdit, at) == 1)
            check(level.getBlockState(pos).isAir)
        } finally {
            world.closeTerrain()
            TerrainBridge.journal = previous
            Files.deleteIfExists(directory.resolve("terrain.bin"))
            Files.deleteIfExists(directory)
        }
        helper.succeed()
    }

    @GameTest
    fun nativeBlockWriteReleasesTemporaryOwnership(helper: GameTestHelper) {
        val level = helper.level
        val pos = helper.absolutePos(BlockPos(1, 1, 1))
        helper.setBlock(1, 1, 1, Blocks.STONE)
        val key = TerrainBridge.key(level, pos)
        val originalJournal = TerrainBridge.journal
        val directory = Files.createTempDirectory("archetype-terrain-gametest-")
        val journal = TerrainJournal(directory.resolve("terrain.bin"), object : TerrainAccess {
            override fun loaded(position: TerrainPos) = level.chunkSource.getChunkNow(position.x shr 4, position.z shr 4) != null
            override fun state(position: TerrainPos): String? =
                if (loaded(position)) BlockStateParser.serialize(level.getBlockState(BlockPos(position.x, position.y, position.z))) else null
            override fun mayEdit(actor: UUID, position: TerrainPos, state: String) = loaded(position)
            override fun write(position: TerrainPos, state: String): Boolean = TerrainBridge.ownedWrite(position) {
                val block = when (state) {
                    "minecraft:stone_bricks" -> Blocks.STONE_BRICKS
                    "minecraft:stone" -> Blocks.STONE
                    else -> error("unexpected state")
                }
                level.setBlock(BlockPos(position.x, position.y, position.z), block.defaultBlockState(), 3)
            }
        })
        TerrainBridge.journal = journal
        try {
            val source = UUID(0, 412)
            check(journal.place(UUID(0, 413), source, listOf(TerrainEdit(key, "minecraft:stone_bricks")), 20) == 1)
            check(level.getBlockState(pos).`is`(Blocks.STONE_BRICKS))
            // An external write to the same state still gives up ownership.
            level.setBlock(pos, Blocks.STONE_BRICKS.defaultBlockState(), 3)
            journal.cancel(source)
            check(level.getBlockState(pos).`is`(Blocks.STONE_BRICKS) && journal.pendingCount() == 0)
            check(journal.place(UUID(0, 413), source, listOf(TerrainEdit(key, "minecraft:stone")), 20) == 1)
            check(level.destroyBlock(pos, true, null, 512))
            check(level.getBlockState(pos).isAir && journal.pendingCount() == 0)
            check(helper.getEntities(EntityTypes.ITEM).isEmpty()) { "temporary block produced a native item drop" }
        } finally {
            TerrainBridge.journal = originalJournal
            Files.deleteIfExists(directory.resolve("terrain.bin"))
            Files.deleteIfExists(directory)
        }
        helper.succeed()
    }
}
