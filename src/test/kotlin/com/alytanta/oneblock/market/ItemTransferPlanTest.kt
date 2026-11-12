package com.alytanta.oneblock.market

import com.alytanta.oneblock.menu.Stack
import org.bukkit.Material
import org.bukkit.inventory.ItemStack
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class ItemTransferPlanTest {
    @Test fun `auction deposit removes only the exact selected stack and preserves input inventory`() {
        val item = Stack(Material.DIAMOND_SWORD, tag = "enchanted,name,lore,pdc", limit = 1)
        val before = arrayOfNulls<ItemStack>(36).apply { this[3] = item; this[8] = Stack(Material.NETHER_STAR, menu = true) }
        val after = ItemTransferPlan.deposit(before, 3, item.clone())
        assertNull(after[3]); assertEquals(item, before[3]); assertEquals(before[8], after[8])
        assertThrows(MarketException::class.java) { ItemTransferPlan.deposit(before, 8, before[8]!!) }
        assertThrows(MarketException::class.java) { ItemTransferPlan.deposit(before, 3, Stack(Material.DIAMOND_SWORD, tag = "different")) }
    }
    @Test fun `withdraw preserves custom metadata and never fills the utility slot`() {
        val before = arrayOfNulls<ItemStack>(36).apply { this[0] = Stack(Material.DIAMOND_SWORD, tag = "ordinary", limit = 1) }
        val exact = Stack(Material.DIAMOND_SWORD, tag = "enchanted,name,lore,pdc", limit = 1)
        val after = ItemTransferPlan.withdraw(before, exact, 64)
        assertEquals(exact, after[1]); assertNull(after[8]); assertNull(before[1])
    }
    @Test fun `full inventory cannot consume a claim by putting it in reserved slot`() {
        val before = Array<ItemStack?>(36) { Stack(Material.STONE, 64) }.apply { this[8] = null }
        val copy = before.map { it?.clone() }.toTypedArray()
        assertThrows(MarketException::class.java) { ItemTransferPlan.withdraw(before, Stack(Material.DIAMOND_SWORD, limit = 1), 64) }
        assertArrayEquals(copy, before)
    }
    @Test fun `partial capacity failure preserves all old stacks while compatible metadata stacks merge`() {
        val before = Array<ItemStack?>(36) { Stack(Material.STONE, 64) }.apply { this[1] = Stack(Material.DIAMOND, 63, tag = "custom") }
        val exact = Stack(Material.DIAMOND, 2, tag = "custom")
        assertThrows(MarketException::class.java) { ItemTransferPlan.withdraw(before, exact, 64) }
        assertEquals(63, before[1]!!.amount)
        exact.amount = 1
        assertEquals(64, ItemTransferPlan.withdraw(before, exact, 64)[1]!!.amount)
    }
}
