package com.alytanta.oneblock.menu

import com.alytanta.oneblock.player.InventoryStatus
import org.bukkit.Material
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class InventoryStatusTest {
    @Test fun `plugin grants update cached state immediately and respawn watches replace old tasks`() {
        val profiles=mutableMapOf<java.util.UUID,com.alytanta.oneblock.player.PlayerProfile>()
        val storage=object: com.alytanta.oneblock.storage.StorageService {
            override fun loadProfiles()=profiles.values.toList()
            override fun loadIslands()=emptyList<com.alytanta.oneblock.island.Island>()
            override fun saveProfile(profile: com.alytanta.oneblock.player.PlayerProfile) { profiles[profile.uniqueId]=profile }
            override fun saveIsland(island: com.alytanta.oneblock.island.Island) {}
            override fun saveNow() {}
            override fun flush()=true
            override fun backup()=java.io.File("unused")
            override fun close() {}
        }
        val f=MenuFixture()
        val status=InventoryStatus(MenuFixture.stub(org.bukkit.plugin.Plugin::class.java) { _,_->null },com.alytanta.oneblock.player.PlayerService(storage),com.alytanta.oneblock.harvest.HarvestSettings())
        val prior=MenuInventory.slotChanged
        try {
            status.watch(f.player)
            assertFalse(status.full(f.player))
            status.watch(f.player)
            assertEquals(1,f.activeWatches())
            f.fill();status.refresh(f.player)
            assertTrue(status.full(f.player))
            f.slots[0]=Stack(Material.STONE,63);status.slotChanged(f.inventory,0)
            assertFalse(status.full(f.player))
            MenuInventory.slotChanged=status::slotChanged
            assertTrue(MenuInventory.add(f.inventory,Stack(Material.STONE,1)).isEmpty())
            assertTrue(status.full(f.player))
            assertTrue(profiles.getValue(f.id).inventoryFull)
            status.toggle(f.player)
            assertEquals(false,profiles.getValue(f.id).inventoryNotifications)
        } finally { MenuInventory.slotChanged=prior;status.close() }
        assertEquals(0,f.activeWatches())
    }
    @Test fun `full means every usable storage stack is at its own maximum`() {
        val f = MenuFixture()
        assertFalse(InventoryStatus.isFull(f.inventory))
        f.fill(); f.slots[8] = null
        assertTrue(InventoryStatus.isFull(f.inventory))
        f.slots[0] = Stack(Material.ENDER_PEARL,16,limit=16)
        assertTrue(InventoryStatus.isFull(f.inventory))
        f.slots[0] = Stack(Material.ENDER_PEARL,15,limit=16)
        assertFalse(InventoryStatus.isFull(f.inventory))
        f.slots[0] = Stack(Material.DIAMOND_SWORD,1,limit=1)
        assertTrue(InventoryStatus.isFull(f.inventory))
        f.slots[1] = null
        assertFalse(InventoryStatus.isFull(f.inventory))
    }
}
