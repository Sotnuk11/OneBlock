package com.alytanta.oneblock.market

import com.alytanta.oneblock.menu.Stack
import org.bukkit.Material
import org.bukkit.inventory.ItemStack
import org.bukkit.configuration.file.YamlConfiguration
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class MerchantBasketTest {
    private fun settings() = MarketSettings.load(YamlConfiguration().apply {
        set("market.products.DIAMOND.sell-price", 100); set("market.products.OAK_LOG.sell-price", 4)
        set("market.products.EMERALD.sell-price", 50); set("market.products.EMERALD.shop", false)
    })
    private fun items() = arrayOfNulls<ItemStack>(36).apply {
        this[0] = Stack(Material.DIAMOND, 20); this[1] = Stack(Material.OAK_LOG, 64)
        this[2] = Stack(Material.DIAMOND, 2, tag = "custom"); this[3] = Stack(Material.EMERALD, 10)
        this[8] = Stack(Material.DIAMOND, 64); this[9] = Stack(Material.DIAMOND, 5)
    }
    @Test fun `specific-resource sale removes all diamonds only and never sells reserved or custom items`() {
        val before = items(); val quote = MerchantBasket.quote(before, settings(), "DIAMOND")
        assertEquals(listOf(MerchantLine("DIAMOND", 25, 100)), quote)
        val after = MerchantBasket.remove(before, quote)
        assertNull(after[0]); assertNull(after[9]); assertEquals(before[1], after[1]); assertEquals(before[2], after[2]); assertEquals(before[8], after[8])
        assertEquals(20, before[0]!!.amount)
    }
    @Test fun `global sell all includes every eligible product and no disallowed material`() {
        val before = items(); val quote = MerchantBasket.quote(before, settings())
        assertEquals(setOf("DIAMOND", "OAK_LOG"), quote.map { it.product }.toSet()); assertEquals(2756L, MerchantBasket.total(quote))
        val after = MerchantBasket.remove(before, quote)
        assertNull(after[1]); assertEquals(before[3], after[3])
    }
    @Test fun `stale basket fails as one operation without partly changing input inventory`() {
        val before = items(); val copy = before.map { it?.clone() }.toTypedArray()
        val quote = MerchantBasket.quote(before, settings()) + MerchantLine("IRON_INGOT", 1, 5)
        assertThrows(MarketException::class.java) { MerchantBasket.remove(before, quote) }
        assertArrayEquals(copy, before)
    }
}
