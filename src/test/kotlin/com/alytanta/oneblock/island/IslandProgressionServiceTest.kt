package com.alytanta.oneblock.island

import com.alytanta.oneblock.oneblock.CoreMode
import com.alytanta.oneblock.oneblock.CoreState
import com.alytanta.oneblock.config.EconomySettings
import org.bukkit.Material
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.ZoneOffset
import java.time.temporal.WeekFields
import java.util.UUID

class IslandProgressionServiceTest {
    @Test
    fun `mining advances only the core counter without retired progression`() {
        val owner = UUID.randomUUID()
        val store = FakeIslandStore(Island(owner = owner, worldName = "islands", x = 0, y = 100, z = 0, core = CoreState(CoreMode.WOOD)))
        val reward = IslandProgressionService(store).mine(owner, Material.OAK_LOG)

        assertEquals(0L, reward?.coins)
        assertEquals(0L, store.island.coins)
        assertEquals(1L, store.island.core.breaks)
        assertEquals(0, store.island.core.mastery)
        assertTrue(store.island.collections.isEmpty())
        assertTrue(store.island.professionXp.isEmpty())
        assertTrue(store.island.questProgress.isEmpty())
        assertEquals(0L, store.island.weeklyProgress)
        assertEquals(0L, store.island.seasonPoints)
    }

    @Test
    fun `purchases scale price and stop at configured maximum`() {
        val owner = UUID.randomUUID()
        val store = FakeIslandStore(Island(
            owner = owner, worldName = "islands", x = 0, y = 100, z = 0,
            core = CoreState(CoreMode.STONE), coins = 300,
        ))
        val service = IslandProgressionService(store)
        val catalog = mapOf("fortune" to (2 to 100L))

        assertEquals(IslandProgressionService.PurchaseResult.BOUGHT, service.purchase(owner, catalog, "fortune", false))
        assertEquals(200, store.island.coins)
        assertEquals(IslandProgressionService.PurchaseResult.BOUGHT, service.purchase(owner, catalog, "fortune", false))
        assertEquals(0, store.island.coins)
        assertEquals(IslandProgressionService.PurchaseResult.MAXED, service.purchase(owner, catalog, "fortune", false))
    }

    @Test
    fun `negative spends are rejected`() {
        val owner = UUID.randomUUID()
        val store = FakeIslandStore(Island(owner = owner, worldName = "islands", x = 0, y = 100, z = 0, core = CoreState(CoreMode.WOOD), coins = 10))
        assertFalse(IslandProgressionService(store).spend(owner, -5))
        assertTrue(store.island.coins == 10L)
    }

    private class FakeIslandStore(var island: Island) : IslandProgressStore {
        override fun update(owner: UUID, change: (Island) -> Island): Island? {
            if (island.owner != owner) return null
            island = change(island)
            return island
        }
    }
}
