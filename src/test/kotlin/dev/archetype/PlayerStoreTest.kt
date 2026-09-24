package dev.archetype

import dev.archetype.minecraft.PlayerStore
import dev.archetype.runtime.PlayerRecord
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID

class PlayerStoreTest {
    @TempDir lateinit var directory: Path

    @Test fun `save and load preserve earned class state and paused timers`() {
        val id = UUID.randomUUID()
        val record = PlayerRecord().apply {
            ownedClasses += "workshop:fighter"
            activeClasses += "workshop:fighter"
            resources["workshop:fighter|workshop:focus"] = 7.0
            cooldowns["workshop:fighter|primary"] = 35
            regenerationTimers["workshop:fighter|workshop:focus"] = 12
        }
        val store = PlayerStore(directory)
        store.save(id, record)
        val loaded = store.load(id)
        assertNotNull(loaded)
        assertEquals(record.ownedClasses, loaded!!.ownedClasses)
        assertEquals(record.activeClasses, loaded.activeClasses)
        assertEquals(record.resources, loaded.resources)
        assertEquals(record.cooldowns, loaded.cooldowns)
        assertEquals(record.regenerationTimers, loaded.regenerationTimers)
    }

    @Test fun `invalid saved data are not treated as a fresh player`() {
        val id = UUID.randomUUID()
        Files.writeString(directory.resolve("$id.json"), "{broken")
        assertNull(PlayerStore(directory).load(id))
        assertEquals("{broken", Files.readString(directory.resolve("$id.json")))
    }
}
