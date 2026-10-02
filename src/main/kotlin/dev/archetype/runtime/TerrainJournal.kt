package dev.archetype.runtime

import java.io.DataInputStream
import java.io.DataOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.util.UUID

data class TerrainPos(val dimension: String, val x: Int, val y: Int, val z: Int)
data class TerrainEdit(val position: TerrainPos, val state: String, val loot: Boolean = false)

/** Native access must be restricted to the server thread and must never load a chunk. */
interface TerrainAccess {
    fun loaded(position: TerrainPos): Boolean
    fun state(position: TerrainPos): String?
    fun mayEdit(actor: UUID, position: TerrainPos, state: String): Boolean
    fun write(position: TerrainPos, state: String): Boolean
    fun breakWithLoot(actor: UUID, position: TerrainPos): Boolean = false
}

/** A write-ahead journal for temporary block ownership. The world adapter reports every other block write. */
class TerrainJournal(private val file: Path, private val world: TerrainAccess) {
    private data class Layer(val owner: UUID, val state: String, val expires: Long)
    private data class Cell(val original: String, var visible: String, var previous: String,
        val layers: MutableList<Layer>, var permanentTarget: String? = null)
    private val cells = linkedMapOf<TerrainPos, Cell>()
    @Volatile private var ownedSnapshot: Set<TerrainPos> = emptySet()
    private var clock = 0L

    init { load() }

    fun active(owner: UUID): Boolean = cells.values.any { cell -> cell.layers.any { it.owner == owner } }
    fun owned(position: TerrainPos): Boolean = cells.containsKey(position)
    fun mayOwnFromAnyThread(position: TerrainPos): Boolean = position in ownedSnapshot
    fun pendingCount(): Int = cells.size

    /** All authored cells are checked before the first mutation. A failed temporary batch is unwound. */
    fun place(actor: UUID, owner: UUID, edits: List<TerrainEdit>, durationTicks: Int?): Int {
        require(edits.size in 1..256 && edits.map { it.position }.distinct().size == edits.size) { "invalid terrain batch" }
        require(durationTicks == null || durationTicks in 1..72_000) { "invalid terrain duration" }
        require(durationTicks == null || edits.none { it.loot }) { "temporary terrain cannot yield loot" }
        require(cells.size + edits.size <= 4096) { "terrain journal is full" }
        val originalStates = linkedMapOf<TerrainPos, String>()
        for (edit in edits) {
            require(edit.state.length in 1..256 && edit.position.dimension.length in 1..128) { "invalid terrain state" }
            require(world.loaded(edit.position) && world.mayEdit(actor, edit.position, edit.state)) { "terrain position is unavailable" }
            originalStates[edit.position] = world.state(edit.position)
                ?: throw IllegalArgumentException("terrain position is unavailable")
        }
        if (durationTicks != null) {
            require(edits.all { (cells[it.position]?.layers?.size ?: 0) < 32 }) { "terrain layer limit reached" }
            val prior = edits.associate { edit -> edit.position to cells[edit.position]?.let { cell ->
                Cell(cell.original, cell.visible, cell.previous, cell.layers.toMutableList(), cell.permanentTarget)
            } }
            try {
                for (edit in edits) {
                    val actual = originalStates.getValue(edit.position)
                    val current = cells[edit.position]
                    if (current != null && actual != current.visible && actual != current.previous)
                        cells.remove(edit.position)
                    val cell = cells.getOrPut(edit.position) { Cell(actual, actual, actual, mutableListOf()) }
                    cell.previous = actual
                    cell.visible = edit.state
                    cell.layers += Layer(owner, edit.state, clock + durationTicks)
                }
                save() // All intents are durable before any position changes; one sync per batch.
            } catch (failure: Exception) {
                for ((position, cell) in prior) if (cell == null) cells.remove(position) else cells[position] = cell
                throw failure
            }
        }
        var placed = 0
        try {
            for (edit in edits) {
                val currentState = world.state(edit.position) ?: error("terrain position became unavailable")
                if (!world.loaded(edit.position) || !world.mayEdit(actor, edit.position, edit.state))
                    error("terrain position became unavailable")
                if (currentState != originalStates.getValue(edit.position)) error("terrain changed during batch")
                if (durationTicks == null) {
                    // A prior temporary claim needs a durable transition until this native write is known to have happened.
                    cells[edit.position]?.let { cell ->
                        cell.layers.clear()
                        cell.previous = currentState
                        cell.visible = currentState
                        cell.permanentTarget = edit.state
                        save()
                    }
                    if (!(if (edit.loot) world.breakWithLoot(actor, edit.position)
                        else world.write(edit.position, edit.state))) error("native terrain write failed")
                    if (cells.remove(edit.position) != null) save()
                } else {
                    require(cells[edit.position]?.layers?.any { it.owner == owner } == true) { "terrain ownership was lost" }
                    if (!world.write(edit.position, edit.state)) error("native terrain write failed")
                }
                placed++
            }
        } catch (failure: Exception) {
            if (durationTicks != null) cancel(owner)
            throw failure
        }
        return placed
    }

    /** Called before an unowned native mutation, even if the new state equals our visible state. */
    fun externalWrite(position: TerrainPos) {
        val previous = cells.remove(position) ?: return
        try { save() } catch (failure: Exception) {
            cells[position] = previous
            throw failure
        }
    }

    fun cancel(owner: UUID) {
        val affected = cells.filterValues { cell -> cell.layers.any { it.owner == owner } }.keys.toList()
        for (position in affected) {
            val cell = cells[position] ?: continue
            cell.layers.removeIf { it.owner == owner }
            settle(position, cell)
        }
    }

    fun tick() {
        clock++
        for ((position, cell) in cells.toList()) {
            if (cell.layers.removeIf { it.expires <= clock }) settle(position, cell)
            else if (world.loaded(position)) reconcile(position, cell)
        }
    }

    /** Startup ends all casts. Unloaded cells remain journaled until the chunk becomes available. */
    fun recoverAfterRestart() {
        for ((position, cell) in cells.toList()) {
            cell.layers.clear()
            settle(position, cell)
        }
    }

    fun recoverLoaded(position: TerrainPos) { cells[position]?.let { reconcile(position, it) } }

    fun recoverChunk(dimension: String, chunkX: Int, chunkZ: Int) {
        for (position in cells.keys.toList()) if (position.dimension == dimension &&
            position.x shr 4 == chunkX && position.z shr 4 == chunkZ && world.loaded(position))
            recoverLoaded(position)
    }

    private fun settle(position: TerrainPos, cell: Cell) {
        cell.permanentTarget?.let { permanent ->
            if (!world.loaded(position)) return
            val actual = world.state(position) ?: return
            if (actual == permanent || (actual != cell.visible && actual != cell.previous)) {
                cells.remove(position)
                save()
                return
            }
            cell.permanentTarget = null
            cell.layers.clear()
            // The native permanent write never completed. End the old temporary claim safely.
        }
        val desired = cell.layers.lastOrNull()?.state ?: cell.original
        if (!world.loaded(position)) {
            cell.previous = cell.visible
            cell.visible = desired
            save()
            return
        }
        val actual = world.state(position) ?: return
        if (actual != cell.visible && actual != cell.previous) {
            cells.remove(position)
            save()
            return
        }
        if (actual != desired) {
            cell.previous = actual
            cell.visible = desired
            save() // Recovery target is durable before touching the block.
            if (!world.write(position, desired)) return
        }
        if (cell.layers.isEmpty()) { cells.remove(position); save() }
        else { cell.previous = desired; cell.visible = desired; save() }
    }

    private fun reconcile(position: TerrainPos, cell: Cell) {
        if (cell.permanentTarget != null) { settle(position, cell); return }
        val actual = world.state(position) ?: return
        if (actual != cell.visible && actual != cell.previous) {
            cells.remove(position)
            save()
        } else if (actual != cell.visible || cell.layers.isEmpty()) settle(position, cell)
    }

    private fun save() {
        Files.createDirectories(file.parent)
        val temporary = file.resolveSibling(file.fileName.toString() + ".tmp")
        DataOutputStream(Files.newOutputStream(temporary, StandardOpenOption.CREATE,
            StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)).use { out ->
            out.writeInt(0x41525432)
            out.writeInt(cells.size)
            for ((position, cell) in cells) {
                out.writeUTF(position.dimension); out.writeInt(position.x); out.writeInt(position.y); out.writeInt(position.z)
                out.writeUTF(cell.original); out.writeUTF(cell.visible); out.writeUTF(cell.previous)
                out.writeBoolean(cell.permanentTarget != null)
                cell.permanentTarget?.let(out::writeUTF)
                out.writeInt(cell.layers.size)
                for (layer in cell.layers) {
                    out.writeLong(layer.owner.mostSignificantBits); out.writeLong(layer.owner.leastSignificantBits)
                    out.writeUTF(layer.state); out.writeLong(layer.expires)
                }
            }
        }
        java.nio.channels.FileChannel.open(temporary, StandardOpenOption.WRITE).use { it.force(true) }
        Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        java.nio.channels.FileChannel.open(file.parent, StandardOpenOption.READ).use { it.force(true) }
        ownedSnapshot = cells.keys.toSet()
    }

    private fun load() {
        if (!Files.exists(file)) return
        DataInputStream(Files.newInputStream(file)).use { input ->
            require(input.readInt() == 0x41525432) { "unsupported terrain journal" }
            val count = input.readInt()
            require(count in 0..4096) { "invalid terrain journal size" }
            repeat(count) {
                val position = TerrainPos(input.readUTF(), input.readInt(), input.readInt(), input.readInt())
                val original = input.readUTF(); val visible = input.readUTF(); val previous = input.readUTF()
                val permanentTarget = if (input.readBoolean()) input.readUTF() else null
                val layerCount = input.readInt()
                require(layerCount in 0..32 && original.length in 1..256 && visible.length in 1..256 &&
                    previous.length in 1..256 && (permanentTarget == null || permanentTarget.length in 1..256))
                val layers = MutableList(layerCount) {
                    Layer(UUID(input.readLong(), input.readLong()), input.readUTF(), input.readLong())
                }
                require(cells.putIfAbsent(position, Cell(original, visible, previous, layers, permanentTarget)) == null)
            }
            require(input.read() == -1) { "trailing terrain journal data" }
        }
        ownedSnapshot = cells.keys.toSet()
    }
}
