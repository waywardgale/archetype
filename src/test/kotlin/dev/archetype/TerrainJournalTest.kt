package dev.archetype

import dev.archetype.runtime.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.UUID

class TerrainJournalTest {
    @TempDir lateinit var directory: Path
    private val cell = TerrainPos("minecraft:overworld", 3, 70, 4)
    private val wall = UUID(0, 1)
    private val ice = UUID(0, 2)
    private val actor = UUID(0, 3)

    private class World : TerrainAccess {
        val blocks = mutableMapOf<TerrainPos, String>()
        var loaded = true
        var allowed = true
        var writes = 0
        var crashBeforeWrite = false
        var crashAfterWrite = false
        var crashOnWrite: Int? = null
        override fun loaded(position: TerrainPos) = loaded
        override fun state(position: TerrainPos) = if (loaded) blocks[position] else null
        override fun mayEdit(actor: UUID, position: TerrainPos, state: String) = allowed
        override fun write(position: TerrainPos, state: String): Boolean {
            if (crashBeforeWrite) throw AssertionError("simulated process loss")
            blocks[position] = state
            writes++
            if (writes == crashOnWrite) throw AssertionError("simulated batch interruption")
            if (crashAfterWrite) throw AssertionError("simulated process loss")
            return true
        }
    }

    private fun journal(world: World) = TerrainJournal(directory.resolve("terrain.bin"), world)

    @Test fun `covered layer expires without returning after upper layer ends`() {
        val world = World().also { it.blocks[cell] = "stone" }
        val journal = journal(world)
        assertEquals(1, journal.place(actor, wall, listOf(TerrainEdit(cell, "wall")), 2))
        assertEquals(1, journal.place(actor, ice, listOf(TerrainEdit(cell, "ice")), 4))
        repeat(2) { journal.tick() }
        assertEquals("ice", world.blocks[cell])
        repeat(2) { journal.tick() }
        assertEquals("stone", world.blocks[cell])
        assertEquals(0, journal.pendingCount())
    }

    @Test fun `external same-state write releases ownership and preserves the later world state`() {
        val world = World().also { it.blocks[cell] = "stone" }
        val journal = journal(world)
        journal.place(actor, wall, listOf(TerrainEdit(cell, "wall")), 2)
        journal.externalWrite(cell)
        world.blocks[cell] = "wall"
        repeat(3) { journal.tick() }
        assertEquals("wall", world.blocks[cell])
        assertEquals(0, journal.pendingCount())
    }

    @Test fun `permanent edit relinquishes all older temporary layers`() {
        val world = World().also { it.blocks[cell] = "stone" }
        val journal = journal(world)
        journal.place(actor, wall, listOf(TerrainEdit(cell, "wall")), 2)
        journal.place(actor, ice, listOf(TerrainEdit(cell, "brick")), null)
        repeat(3) { journal.tick() }
        assertEquals("brick", world.blocks[cell])
        assertEquals(0, journal.pendingCount())
    }

    @Test fun `permanent transition resolves either side of a process interruption`() {
        for (after in listOf(false, true)) {
            val path = directory.resolve(if (after) "permanent-after.bin" else "permanent-before.bin")
            val world = World().also { it.blocks[cell] = "stone" }
            val journal = TerrainJournal(path, world)
            journal.place(actor, wall, listOf(TerrainEdit(cell, "wall")), 20)
            world.crashBeforeWrite = !after
            world.crashAfterWrite = after
            assertThrows(AssertionError::class.java) {
                journal.place(actor, ice, listOf(TerrainEdit(cell, "brick")), null)
            }
            world.crashBeforeWrite = false; world.crashAfterWrite = false
            val restarted = TerrainJournal(path, world)
            restarted.recoverAfterRestart()
            assertEquals(if (after) "brick" else "stone", world.blocks[cell])
            assertEquals(0, restarted.pendingCount())
        }
    }

    @Test fun `restart resolves intent before or after native mutation`() {
        for (after in listOf(false, true)) {
            val path = directory.resolve(if (after) "after.bin" else "before.bin")
            val world = World().also { it.blocks[cell] = "stone"; it.crashBeforeWrite = !after; it.crashAfterWrite = after }
            assertThrows(AssertionError::class.java) {
                TerrainJournal(path, world).place(actor, wall, listOf(TerrainEdit(cell, "wall")), 20)
            }
            world.crashBeforeWrite = false; world.crashAfterWrite = false
            val restarted = TerrainJournal(path, world)
            restarted.recoverAfterRestart()
            assertEquals("stone", world.blocks[cell])
            assertEquals(0, restarted.pendingCount())
        }
    }

    @Test fun `unloaded cleanup remains journaled until the chunk is available`() {
        val world = World().also { it.blocks[cell] = "stone" }
        val journal = journal(world)
        journal.place(actor, wall, listOf(TerrainEdit(cell, "wall")), 1)
        world.loaded = false
        journal.tick()
        assertEquals(1, journal.pendingCount())
        assertEquals("wall", world.blocks[cell])
        world.loaded = true
        journal.recoverChunk(cell.dimension, cell.x shr 4, cell.z shr 4)
        assertEquals("stone", world.blocks[cell])
        assertEquals(0, journal.pendingCount())
    }

    @Test fun `restart restores every cell after a partially applied batch`() {
        val second = cell.copy(x = 4)
        val world = World().also {
            it.blocks[cell] = "stone"
            it.blocks[second] = "dirt"
            it.crashOnWrite = 1
        }
        assertThrows(AssertionError::class.java) {
            journal(world).place(actor, wall, listOf(TerrainEdit(cell, "wall"), TerrainEdit(second, "wall")), 20)
        }
        assertEquals("wall", world.blocks[cell])
        assertEquals("dirt", world.blocks[second])
        world.crashOnWrite = null
        val restarted = journal(world)
        restarted.recoverAfterRestart()
        assertEquals("stone", world.blocks[cell])
        assertEquals("dirt", world.blocks[second])
        assertEquals(0, restarted.pendingCount())
    }

    @Test fun `unavailable cell rejects the whole batch before mutation`() {
        val world = World().also { it.blocks[cell] = "stone" }
        val journal = journal(world)
        val absent = cell.copy(x = 5)
        assertThrows(IllegalArgumentException::class.java) {
            journal.place(actor, wall, listOf(TerrainEdit(cell, "wall"), TerrainEdit(absent, "wall")), 2)
        }
        assertEquals(0, world.writes)
        assertEquals(0, journal.pendingCount())
    }
}
