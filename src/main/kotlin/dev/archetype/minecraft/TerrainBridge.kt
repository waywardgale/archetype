package dev.archetype.minecraft

import dev.archetype.runtime.TerrainJournal
import dev.archetype.runtime.TerrainPos
import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.Blocks

/** The Level#setBlock hook releases ownership before every non-Archetype write. */
object TerrainBridge {
    @Volatile var journal: TerrainJournal? = null
    private val writing = ThreadLocal<TerrainPos?>()

    @JvmStatic fun beforeSetBlock(level: Level, pos: BlockPos) {
        if (level !is ServerLevel) return
        val key = key(level, pos)
        val current = journal ?: return
        if (!level.server.isSameThread) {
            check(!current.mayOwnFromAnyThread(key)) { "owned terrain cannot be changed off the server thread" }
            return
        }
        if (writing.get() != key) current.externalWrite(key)
    }

    @JvmStatic fun breakTemporary(level: Level, pos: BlockPos): Boolean {
        if (level !is ServerLevel) return false
        val key = key(level, pos)
        val current = journal ?: return false
        if (!level.server.isSameThread) {
            check(!current.mayOwnFromAnyThread(key)) { "owned terrain cannot be broken off the server thread" }
            return false
        }
        if (!current.owned(key)) return false
        current.externalWrite(key)
        level.setBlock(pos, Blocks.AIR.defaultBlockState(), 3)
        return true
    }

    fun <T> ownedWrite(position: TerrainPos, action: () -> T): T {
        val previous = writing.get()
        writing.set(position)
        try { return action() } finally { writing.set(previous) }
    }

    fun key(level: ServerLevel, pos: BlockPos) = TerrainPos(
        level.dimension().identifier().toString(), pos.x, pos.y, pos.z)
}
