package com.alytanta.oneblock.market

import com.alytanta.oneblock.menu.MenuInventory
import org.bukkit.inventory.ItemStack

/** Plans on copies only. No mutation is applied until the handoff intent has committed. */
internal object ItemTransferPlan {
    fun deposit(before: Array<ItemStack?>, slot: Int, expected: ItemStack): Array<ItemStack?> {
        if (slot !in before.indices || slot == MenuInventory.SLOT || before[slot] != expected) throw MarketException("items")
        return before.map { it?.clone() }.toTypedArray().also { it[slot] = null }
    }
    fun withdraw(before: Array<ItemStack?>, exact: ItemStack, inventoryLimit: Int): Array<ItemStack?> {
        val after = before.map { it?.clone() }.toTypedArray()
        var remaining = exact.amount
        for (slot in after.indices) {
            if (slot == MenuInventory.SLOT) continue
            val current = after[slot]?.takeUnless { it.isEmpty }
            if (current != null && !current.isSimilar(exact)) continue
            val capacity = minOf(exact.maxStackSize, inventoryLimit)
            val count = minOf((capacity - (current?.amount ?: 0)).coerceAtLeast(0), remaining)
            if (count > 0) after[slot] = exact.clone().apply { amount = (current?.amount ?: 0) + count }
            remaining -= count
            if (remaining == 0) return after
        }
        throw MarketException("inventory-full")
    }
}
