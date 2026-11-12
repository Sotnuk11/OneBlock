package com.alytanta.oneblock.menu

import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.PlayerInventory

/** All plugin grants use the 35 storage slots available beside the permanent menu button. */
object MenuInventory {
    const val SLOT = 8
    private const val STORAGE_SIZE = 36
    var slotChanged: ((PlayerInventory,Int)->Unit)? = null

    fun add(inventory: PlayerInventory, vararg items: ItemStack): List<ItemStack> {
        val overflow = mutableListOf<ItemStack>()
        for (source in items) {
            if (source.isEmpty) continue
            val remaining = source.clone()
            // Fill existing compatible stacks before using empty slots. Never mutate the caller's stack.
            for (slot in 0 until STORAGE_SIZE) {
                if (slot == SLOT || remaining.amount == 0) continue
                val current = inventory.getItem(slot) ?: continue
                if (current.isEmpty || !current.isSimilar(remaining)) continue
                val capacity = minOf(inventory.maxStackSize, current.maxStackSize, remaining.maxStackSize)
                val count = minOf((capacity - current.amount).coerceAtLeast(0), remaining.amount)
                if (count > 0) {
                    inventory.setItem(slot, current.clone().apply { amount += count })
                    slotChanged?.invoke(inventory,slot)
                    remaining.amount -= count
                }
            }
            for (slot in 0 until STORAGE_SIZE) {
                if (slot == SLOT || remaining.amount == 0) continue
                val current = inventory.getItem(slot)
                if (current != null && !current.isEmpty) continue
                val count = minOf(inventory.maxStackSize, remaining.maxStackSize, remaining.amount)
                inventory.setItem(slot, remaining.clone().apply { amount = count })
                slotChanged?.invoke(inventory,slot)
                remaining.amount -= count
            }
            if (remaining.amount > 0) overflow += remaining
        }
        return overflow
    }

    /** Remove only marked utility copies. Foreign items in the reserved slot are relocated or returned. */
    fun repair(inventory: PlayerInventory, expected: ItemStack, isMenu: (ItemStack?) -> Boolean): List<ItemStack> {
        for (slot in 0 until inventory.size) {
            if (slot != SLOT && isMenu(inventory.getItem(slot))) inventory.setItem(slot, null)
        }
        val current = inventory.getItem(SLOT)
        val displaced = current?.takeUnless { it.isEmpty || isMenu(it) }?.clone()
        if (current == null || current.amount != 1 || !current.isSimilar(expected)) {
            inventory.setItem(SLOT, expected.clone().apply { amount = 1 })
        }
        return if (displaced == null) emptyList() else add(inventory, displaced)
    }
}
