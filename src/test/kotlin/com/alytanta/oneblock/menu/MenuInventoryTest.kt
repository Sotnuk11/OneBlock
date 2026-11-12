package com.alytanta.oneblock.menu

import org.bukkit.Material
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import com.alytanta.oneblock.menu.MenuFixture.Companion.icon
import com.alytanta.oneblock.menu.MenuFixture.Companion.isMenu

class MenuInventoryTest {
    @Test fun `foreign item in reserved slot is returned to normal storage without loss`() {
        val f = MenuFixture()
        f.slots[8] = Stack(Material.DIAMOND, 17, tag = "custom")
        f.repair()
        assertTrue(isMenu(f.slots[8]))
        assertEquals(17, f.count(Material.DIAMOND))
        assertTrue(f.slots[0]!!.isSimilar(Stack(Material.DIAMOND, tag = "custom")))
        assertTrue(f.overflow.isEmpty())
    }
    @Test fun `full inventory returns displaced item instead of replacing another item`() {
        val f = MenuFixture().apply { fill(); slots[8] = Stack(Material.DIAMOND, 23) }
        f.repair()
        assertEquals(23, f.count(Material.DIAMOND))
        assertEquals(35 * 64, f.count(Material.COBBLESTONE))
        assertEquals(23, f.overflow.single().amount)
        assertTrue(isMenu(f.slots[8]))
        f.repair()
        assertEquals(1, f.overflow.size)
    }
    @Test fun `repair merges displaced stacks and returns only the remaining excess`() {
        val f = MenuFixture().apply { fill(); slots[0] = Stack(Material.DIAMOND, 60); slots[8] = Stack(Material.DIAMOND, 17) }
        f.repair()
        assertEquals(64, f.slots[0]!!.amount)
        assertEquals(13, f.overflow.single().amount)
        assertEquals(77, f.count(Material.DIAMOND))
    }
    @Test fun `ordinary nether stars remain ordinary while menu copies in armor and offhand disappear`() {
        val f = MenuFixture().apply { slots[8] = Stack(Material.NETHER_STAR, 12); slots[4] = icon(64); slots[36] = icon(); slots[40] = icon() }
        f.repair()
        assertEquals(12, f.count(Material.NETHER_STAR))
        assertEquals(1, f.slots.filter(::isMenu).size)
        assertNull(f.slots[36]); assertNull(f.slots[40])
    }
    @Test fun `grants never use slot eight even when it is empty and armor is available`() {
        val f = MenuFixture().apply { fill(); slots[8] = null }
        val input = Stack(Material.OAK_LOG, 2)
        val leftover = MenuInventory.add(f.inventory, input)
        assertNull(f.slots[8]); assertNull(f.slots[40]); assertEquals(2, leftover.single().amount)
        assertEquals(2, input.amount)
    }
    @Test fun `grants preserve metadata and custom stack limits and merge before empty slots`() {
        val f = MenuFixture().apply { slots[7] = Stack(Material.OAK_LOG, 15, tag = "named", limit = 16) }
        assertTrue(MenuInventory.add(f.inventory, Stack(Material.OAK_LOG, 3, tag = "named", limit = 16)).isEmpty())
        assertEquals(16, f.slots[7]!!.amount); assertEquals(2, f.slots[0]!!.amount)
        assertEquals(18, f.count(Material.OAK_LOG))
    }
    @Test fun `valid menu repair does not rewrite inventory which may be referenced by a market hash`() {
        val f = MenuFixture().apply { slots[8] = icon() }
        val before = f.slots[8]
        f.repair()
        assertSame(before, f.slots[8])
    }

    @Test fun `repair conserves every normal item and its metadata across mixed inventories`() {
        val random = kotlin.random.Random(7621)
        val materials = listOf(Material.DIAMOND, Material.OAK_LOG, Material.NETHER_STAR, Material.DIRT)
        fun contents(f: MenuFixture) = (f.slots.filterNotNull() + f.overflow).filterNot(::isMenu)
            .groupBy { it.clone().apply { amount = 1 } }.mapValues { (_, stacks) -> stacks.sumOf { it.amount } }
        repeat(200) {
            val f = MenuFixture()
            for (slot in f.slots.indices) {
                f.slots[slot] = when (random.nextInt(6)) {
                    0 -> null
                    1 -> icon(random.nextInt(1, 65))
                    else -> Stack(materials.random(random), random.nextInt(1, 65), tag = "data-${random.nextInt(3)}")
                }
            }
            val before = contents(f)
            f.repair()
            assertEquals(before, contents(f))
            assertTrue(isMenu(f.slots[8]))
            assertEquals(1, f.slots.count(::isMenu))
            val overflowCount = f.overflow.size
            f.repair()
            assertEquals(before, contents(f))
            assertEquals(overflowCount, f.overflow.size)
        }
    }
}
