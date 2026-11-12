package com.alytanta.oneblock.admin

import com.alytanta.oneblock.config.PluginConfig
import com.alytanta.oneblock.island.Island
import com.alytanta.oneblock.island.IslandRole
import com.alytanta.oneblock.oneblock.CoreMode
import com.alytanta.oneblock.oneblock.CoreState
import org.bukkit.configuration.file.YamlConfiguration
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.util.UUID
import java.util.logging.Logger

class AdminEditsTest {
    private val settings = PluginConfig.load(YamlConfiguration(), Logger.getLogger("AdminEditsTest"))

    @Test fun `progress reset keeps buildings identity co-op and kit claim`() {
        val owner = UUID.randomUUID()
        val island = Island(owner, "island", 0, 100, 0, CoreState(CoreMode.STONE, breaks = 1000),
            members = mapOf(UUID.randomUUID() to IslandRole.MEMBER), coins = 1000,
            worldSize = 150.0, startingKitClaimed = true, platformInitialized = true, platformWorldId = UUID.randomUUID())
        val reset = AdminEdits.resetProgress(island, settings)
        assertEquals(island.owner, reset.owner)
        assertEquals(island.worldName, reset.worldName)
        assertEquals(island.members, reset.members)
        assertEquals(150.0, reset.worldSize)
        assertTrue(reset.startingKitClaimed)
        assertTrue(reset.platformInitialized)
        assertEquals(island.platformWorldId, reset.platformWorldId)
        assertEquals(0L, reset.core.breaks)
        assertEquals(0L, reset.coins)
    }

    @Test fun `admin fields validate limits and reject arbitrary island identity edits`() {
        val island = Island(UUID.randomUUID(), "island", 0, 100, 0, CoreState(CoreMode.WOOD))
        assertEquals(200.0, AdminEdits.set(island, "world-size", "200", settings).worldSize)
        assertNull(AdminEdits.set(island.copy(worldSize = 200.0), "world-size", "default", settings).worldSize)
        assertEquals(99L, AdminEdits.set(island, "breaks", "99", settings).core.breaks)
        for ((field, value) in listOf("world-size" to "NaN", "world-size" to "1", "breaks" to "-1", "infusion" to "11",
            "tier" to "0", "upgrade.fortune" to "6", "worldName" to "another")) {
            assertThrows(IllegalArgumentException::class.java) { AdminEdits.set(island, field, value, settings) }
        }
    }
}
