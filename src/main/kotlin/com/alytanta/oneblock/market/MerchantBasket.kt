package com.alytanta.oneblock.market

import com.alytanta.oneblock.menu.MenuInventory
import org.bukkit.inventory.ItemStack

/** Uses only ordinary resource stacks. Reserved utility slot and items with metadata are never sold. */
internal object MerchantBasket {
    fun quote(contents: Array<ItemStack?>, settings: MarketSettings, only: String? = null): List<MerchantLine> {
        val amounts = linkedMapOf<String, Int>()
        for ((slot, item) in contents.withIndex()) {
            if (slot == MenuInventory.SLOT || item == null || item.isEmpty || item.hasItemMeta()) continue
            val key = item.type.name
            if (only != null && key != only || settings.products[key]?.shop != true) continue
            amounts[key] = Math.addExact(amounts[key] ?: 0, item.amount)
        }
        return amounts.map { (key, amount) -> MerchantLine(key, amount, settings.products.getValue(key).sellPrice) }
    }
    fun remove(contents: Array<ItemStack?>, lines: List<MerchantLine>): Array<ItemStack?> {
        if (lines.isEmpty()) throw MarketException("items")
        val after = contents.map { it?.clone() }.toTypedArray()
        for (line in lines) {
            if (line.quantity <= 0) throw MarketException("quantity")
            var left = line.quantity
            for (slot in after.indices) {
                val item = after[slot] ?: continue
                if (slot == MenuInventory.SLOT || item.isEmpty || item.type.name != line.product || item.hasItemMeta()) continue
                val taken = minOf(left, item.amount)
                left -= taken
                if (taken == item.amount) after[slot] = null else item.amount -= taken
                if (left == 0) break
            }
            if (left != 0) throw MarketException("items")
        }
        return after
    }
    fun total(lines: List<MerchantLine>): Long = lines.fold(0L) { sum, line -> Math.addExact(sum, line.total()) }
}
