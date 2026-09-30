package dev.archetype

import dev.archetype.minecraft.PlayerStore
import dev.archetype.runtime.PlayerRecord
import dev.archetype.runtime.ChargeState
import dev.archetype.definitions.RechargeMode
import dev.archetype.definitions.StateValue
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
            charges["workshop:fighter|primary"] = ChargeState(3, 1, RechargeMode.PARALLEL, mutableListOf(7, 18))
            persistentStates["workshop:fighter|workshop:stance|hits"] = StateValue.Number(2.0)
            persistentStates["player|workshop:stance|mode"] = StateValue.Mode("steady")
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
        assertEquals(record.charges, loaded.charges)
        assertEquals(record.persistentStates, loaded.persistentStates)
    }

    @Test fun `older player records without charges remain readable`() {
        val id = UUID.randomUUID()
        Files.writeString(directory.resolve("$id.json"), """{"version":1,"owned_classes":[],"active_classes":[],"resources":{},"cooldowns":{}}""")
        assertTrue(PlayerStore(directory).load(id)!!.charges.isEmpty())
    }

    @Test fun `invalid charge counts cannot enter player state`() {
        val id = UUID.randomUUID()
        Files.writeString(directory.resolve("$id.json"), """{"version":1,"owned_classes":[],"active_classes":[],"resources":{},"cooldowns":{},"charges":{"workshop:fighter|primary":{"capacity":2,"available":3,"mode":"parallel","timers":[]}}}""")
        assertNull(PlayerStore(directory).load(id))
    }

    @Test fun `invalid saved data are not treated as a fresh player`() {
        val id = UUID.randomUUID()
        Files.writeString(directory.resolve("$id.json"), "{broken")
        assertNull(PlayerStore(directory).load(id))
        assertEquals("{broken", Files.readString(directory.resolve("$id.json")))
    }
}
