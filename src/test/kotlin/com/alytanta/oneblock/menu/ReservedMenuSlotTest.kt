package com.alytanta.oneblock.menu

import com.alytanta.oneblock.market.InventoryGuard
import com.destroystokyo.paper.event.player.PlayerPostRespawnEvent
import com.destroystokyo.paper.event.player.PlayerRecipeBookClickEvent
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.block.Block
import org.bukkit.block.BlockFace
import org.bukkit.damage.DamageSource
import org.bukkit.entity.Item
import org.bukkit.event.block.Action
import org.bukkit.event.block.BlockBreakEvent
import org.bukkit.event.entity.PlayerDeathEvent
import org.bukkit.event.inventory.*
import org.bukkit.event.player.*
import org.bukkit.inventory.EquipmentSlot
import org.bukkit.inventory.ShapelessRecipe
import org.bukkit.inventory.RecipeChoice
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import com.alytanta.oneblock.menu.MenuFixture.Companion.icon
import com.alytanta.oneblock.menu.MenuFixture.Companion.isMenu
import com.alytanta.oneblock.menu.MenuFixture.Companion.stub

@Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
class ReservedMenuSlotTest {
    @Test fun `post respawn replaces retired watch and restores menu after death cleared inventory`() {
        val f = MenuFixture()
        f.controller.watch(f.player)
        f.dead = true
        f.slots.fill(null)
        f.tick()
        assertNull(f.slots[8])
        f.dead = false
        f.controller.onRespawn(PlayerPostRespawnEvent(f.player, Location(null, 0.0, 100.0, 0.0), false, false, false,
            PlayerRespawnEvent.RespawnReason.DEATH))
        assertTrue(isMenu(f.slots[8]))
        assertEquals(1, f.activeWatches())
    }

    @Test fun `watchdog repairs clear and give commands without eating displaced resources`() {
        val f = MenuFixture()
        f.controller.watch(f.player)
        f.slots[8] = null
        f.tick()
        assertTrue(isMenu(f.slots[8]))
        f.slots[8] = Stack(Material.DIAMOND, 11)
        f.tick()
        assertTrue(isMenu(f.slots[8]))
        assertEquals(11, f.count(Material.DIAMOND))
        f.tick()
        assertEquals(11, f.count(Material.DIAMOND))
    }

    @Test fun `join does not change the saved inventory before market recovery gets its turn`() {
        val f = MenuFixture()
        f.controller.onJoin(PlayerJoinEvent(f.player, null as net.kyori.adventure.text.Component?))
        assertNull(f.slots[8])
        assertEquals(listOf(2L), f.scheduledDelays)
        f.drain()
        assertTrue(isMenu(f.slots[8]))
    }

    @Test fun `handoff lock defers repair until inventory snapshots are released`() {
        val f = MenuFixture().apply { slots[8] = Stack(Material.GOLD_INGOT, 7) }
        InventoryGuard.acquire(f.id)
        try {
            f.controller.watch(f.player)
            f.tick()
            assertFalse(isMenu(f.slots[8]))
            assertTrue(f.overflow.isEmpty())
        } finally { InventoryGuard.release(f.id) }
        f.tick()
        assertTrue(isMenu(f.slots[8]))
        assertEquals(7, f.count(Material.GOLD_INGOT))
    }

    @Test fun `death removes utility tokens from drops and keep list without altering normal death loot`() {
        for (keep in listOf(false, true)) {
            val f = MenuFixture()
            val source = stub(DamageSource::class.java) { _, _ -> null }
            val normal = Stack(Material.DIAMOND, 5)
            val event = PlayerDeathEvent(f.player, source, mutableListOf<org.bukkit.inventory.ItemStack>(icon(), normal), 0,
                null as net.kyori.adventure.text.Component?, true)
            event.keepInventory = keep
            event.itemsToKeep += icon()
            event.itemsToKeep += normal
            f.controller.onDeath(event)
            assertEquals(listOf(normal), event.drops)
            assertEquals(listOf(normal), event.itemsToKeep)
            assertEquals(keep, event.keepInventory)
        }
    }

    @Test fun `foreign item is not trapped after clicking reserved slot`() {
        val f = MenuFixture().apply { slots[8] = Stack(Material.RAW_IRON, 19) }
        val click = InventoryClickEvent(f.view(), InventoryType.SlotType.QUICKBAR, 108, ClickType.LEFT, InventoryAction.PICKUP_ALL)
        f.controller.onClick(click)
        assertTrue(click.isCancelled)
        f.drain()
        assertTrue(isMenu(f.slots[8]))
        assertEquals(19, f.count(Material.RAW_IRON))
    }

    @Test fun `number nine swaps and creative menu copies are rejected`() {
        val f = MenuFixture().apply { slots[8] = icon() }
        val swap = InventoryClickEvent(f.view(), InventoryType.SlotType.CONTAINER, 0, ClickType.NUMBER_KEY, InventoryAction.HOTBAR_SWAP, 8)
        f.controller.onClick(swap)
        assertTrue(swap.isCancelled)
        val creative = InventoryCreativeEvent(f.view(), InventoryType.SlotType.CONTAINER, 0, icon(64))
        f.controller.onClick(creative)
        assertTrue(creative.isCancelled)
        val normal = InventoryClickEvent(f.view(), InventoryType.SlotType.QUICKBAR, 100, ClickType.LEFT, InventoryAction.PICKUP_ALL)
        f.controller.onClick(normal)
        assertFalse(normal.isCancelled)
    }

    @Test fun `drag into reserved slot or with marked cursor is rejected and cursor duplicate is removed`() {
        val f = MenuFixture().apply { cursor = icon() }
        val drag = InventoryDragEvent(f.view(), Stack(Material.DIAMOND), Stack(Material.DIAMOND, 2), false,
            mapOf(108 to Stack(Material.DIAMOND)))
        f.controller.onDrag(drag)
        assertTrue(drag.isCancelled)
        f.drain()
        assertTrue(f.cursor.isEmpty)
        assertTrue(isMenu(f.slots[8]))
    }

    @Test fun `dropping and swapping menu never transfers the star`() {
        val f = MenuFixture().apply { held = 8; slots[8] = icon() }
        val entity = stub(Item::class.java) { name, _ -> if (name == "getItemStack") icon() else null }
        val drop = PlayerDropItemEvent(f.player, entity)
        f.controller.onDrop(drop)
        assertTrue(drop.isCancelled)
        val swap = PlayerSwapHandItemsEvent(f.player, Stack(Material.DIAMOND), icon())
        f.controller.onSwap(swap)
        assertTrue(swap.isCancelled)
    }

    @Test fun `pickup waits for menu restoration instead of filling its empty slot`() {
        val f = MenuFixture()
        val entity = stub(Item::class.java) { name, _ -> if (name == "getItemStack") Stack(Material.OAK_LOG, 3) else null }
        val attempt = PlayerAttemptPickupItemEvent(f.player, entity, 0)
        f.controller.onAttemptPickup(attempt)
        assertTrue(attempt.isCancelled)
        f.drain()
        val retry = PlayerAttemptPickupItemEvent(f.player, entity, 0)
        f.controller.onAttemptPickup(retry)
        assertFalse(retry.isCancelled)
        assertTrue(isMenu(f.slots[8]))
    }

    @Test fun `menu hand cannot mine while its right click opens the menu only once`() {
        val f = MenuFixture().apply { held = 8; slots[8] = icon() }
        val block = stub(Block::class.java) { _, _ -> null }
        val mine = BlockBreakEvent(block, f.player)
        f.controller.onMine(mine)
        assertTrue(mine.isCancelled)
        val click = PlayerInteractEvent(f.player, Action.RIGHT_CLICK_AIR, icon(), null, BlockFace.SELF, EquipmentSlot.HAND)
        val offhand = PlayerInteractEvent(f.player, Action.RIGHT_CLICK_AIR, Stack(Material.DIRT), null, BlockFace.SELF, EquipmentSlot.OFF_HAND)
        f.controller.onInteract(click)
        f.controller.onInteract(offhand)
        f.drain()
        assertEquals(1, f.opened)
        assertTrue(click.isCancelled); assertTrue(offhand.isCancelled)
    }

    @Test fun `recipe book never empties slot to borrow utility star and normal recipes are allowed`() {
        val f = MenuFixture().apply { slots[8] = icon() }
        val key = NamespacedKey.minecraft("test_menu_recipe")
        fun choice(material: Material) = object : RecipeChoice {
            override fun getItemStack() = Stack(material)
            override fun clone(): RecipeChoice = this
            override fun test(stack: org.bukkit.inventory.ItemStack) = stack.type == material
        }
        f.recipe = ShapelessRecipe(key, Stack(Material.BEACON)).addIngredient(choice(Material.NETHER_STAR))
        val beacon = PlayerRecipeBookClickEvent(f.player, key, true)
        f.controller.onRecipe(beacon)
        assertTrue(beacon.isCancelled)
        assertTrue(isMenu(f.slots[8]))
        f.recipe = ShapelessRecipe(key, Stack(Material.STICK)).addIngredient(choice(Material.OAK_PLANKS))
        val normal = PlayerRecipeBookClickEvent(f.player, key, false)
        f.controller.onRecipe(normal)
        assertFalse(normal.isCancelled)
        assertTrue(isMenu(f.slots[8]))
    }

    @Test fun `watch removal on quit and disable prevents later inventory edits`() {
        val f = MenuFixture()
        f.controller.watch(f.player)
        f.controller.onQuit(PlayerQuitEvent(f.player, null as net.kyori.adventure.text.Component?))
        assertEquals(0, f.activeWatches())
        f.slots[8] = null
        f.tick()
        assertNull(f.slots[8])
        f.controller.watch(f.player)
        f.controller.close()
        assertEquals(0, f.activeWatches())
    }

    @Test fun `utility tokens on the ground cannot be picked up by player or hopper`() {
        val f = MenuFixture()
        var removed = 0
        val entity = stub(Item::class.java) { name, _ -> when (name) {
            "getItemStack" -> icon()
            "remove" -> { removed++; null }
            else -> null
        } }
        val pickup = PlayerAttemptPickupItemEvent(f.player, entity, 0)
        f.controller.onAttemptPickup(pickup)
        val hopper = InventoryPickupItemEvent(f.inventory, entity)
        f.controller.onHopper(hopper)
        assertTrue(pickup.isCancelled); assertTrue(hopper.isCancelled)
        assertEquals(2, removed)
    }

    @Test fun `marked cursor and offhand copies disappear while normal offhand items survive repair`() {
        val f = MenuFixture().apply { cursor = icon(); slots[40] = Stack(Material.SHIELD) }
        f.controller.ensureItem(f.player)
        assertTrue(f.cursor.isEmpty)
        assertEquals(Material.SHIELD, f.slots[40]!!.type)
        f.slots[40] = icon(32)
        f.controller.ensureItem(f.player)
        assertNull(f.slots[40])
        assertEquals(1, f.slots.count(::isMenu))
    }

    @Test fun `holding menu does not disable pressure plates or other physical interactions`() {
        val f = MenuFixture().apply { held = 8; slots[8] = icon() }
        val plate = stub(Block::class.java) { _, _ -> null }
        val physical = PlayerInteractEvent(f.player, Action.PHYSICAL, null, plate, BlockFace.UP)
        f.controller.onInteract(physical)
        assertFalse(physical.isCancelled)
        f.drain()
        assertEquals(0, f.opened)
    }
}
