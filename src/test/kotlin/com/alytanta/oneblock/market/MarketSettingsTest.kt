package com.alytanta.oneblock.market

import org.bukkit.configuration.file.YamlConfiguration
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.io.InputStreamReader

class MarketSettingsTest {
    @Test fun `sign templates categories and hover limits are configurable and strictly validated`() {
        val yaml = YamlConfiguration().apply {
            set("market.sign.input-line", 2)
            set("market.sign.price-lines", listOf("Price", "", "{value}", ""))
            set("market.products.OAK_LOG.category", "farming")
            set("market.products.OAK_LOG.display-name", "Дубова колода")
            set("market.hover-orders-per-side", 12)
        }
        val loaded = MarketSettings.load(yaml)
        assertEquals(2, loaded.ui.inputLine); assertEquals(12, loaded.ui.hoverOrders)
        assertEquals("{value}", loaded.ui.priceLines[2])
        assertEquals(MarketCategory.FARMING, loaded.products.getValue("OAK_LOG").category)
        assertEquals("Дубова колода", loaded.products.getValue("OAK_LOG").displayName)
        for ((path, value) in listOf("market.sign.input-line" to 4, "market.sign.price-lines" to listOf("{value}"),
            "market.sign.price-lines" to listOf("Read-only input", "", "", ""), "market.hover-orders-per-side" to 0,
            "market.buy-spread-bps" to 10001, "market.products.OAK_LOG.category" to "bad")) {
            assertThrows(RuntimeException::class.java) { MarketSettings.load(YamlConfiguration().apply { set(path, value) }) }
        }
    }

    @Test fun `bundled market has configurable prices and sane limits`() {
        val yaml = requireNotNull(javaClass.getResourceAsStream("/config.yml")).use { YamlConfiguration.loadConfiguration(InputStreamReader(it)) }
        val settings = MarketSettings.load(yaml)
        assertEquals(61, settings.products.size)
        assertEquals(2, settings.products.getValue("OAK_LOG").sellPrice)
        assertFalse(settings.products.getValue("oneblock_battle_fragment").shop);assertEquals(org.bukkit.Material.PRISMARINE_SHARD,settings.products.getValue("oneblock_battle_fragment").material)
        assertEquals(125, settings.sellTaxBps)
        assertEquals(listOf(1L, 10L, 100L), settings.priceSteps)
    }
    @Test fun `invalid prices overflowed limits fractional coins and colliding NPC bindings fail`() {
        val bad = listOf("market.products.OAK_LOG.sell-price" to -1,
            "market.products.OAK_LOG.sell-price" to 1.5, "market.max-quantity" to 4_294_967_297L,
            "market.max-orders-per-island" to 4_294_967_297L, "market.session-seconds" to Long.MAX_VALUE,
            "market.products.OAK_LOG.min-order-price" to 0, "market.sell-tax-bps" to 10001)
        for ((path, value) in bad) {
            val yaml = YamlConfiguration().apply { set(path, value) }
            assertThrows(RuntimeException::class.java) { MarketSettings.load(yaml) }
        }
        assertThrows(IllegalArgumentException::class.java) { MarketSettings.load(YamlConfiguration().apply {
            set("market.npcs.bazaar", "same"); set("market.npcs.shop", "same")
        }) }
        assertThrows(IllegalArgumentException::class.java) { MarketSettings.load(YamlConfiguration().apply {
            set("market.products.OAK_LOG.min-order-price", 10); set("market.products.OAK_LOG.max-order-price", 5)
        }) }
    }
    @Test fun `retired NPC purchase settings have no effect on merchant configuration`() {
        val settings = MarketSettings.load(YamlConfiguration().apply {
            set("market.products.OAK_LOG.sell-price", 10)
            set("market.products.OAK_LOG.buy-price", 0)
            set("market.products.OAK_LOG.initial-stock", -1)
            set("market.products.OAK_LOG.max-stock", 0)
        })
        assertEquals(10, settings.products.getValue("OAK_LOG").sellPrice)
    }

    @Test fun `custom merchant and Bazaar catalogs and sign limits reject malformed values`() {
        for ((path, value) in listOf("market.quantities" to listOf(1, "bad"), "market.quantities" to emptyList<Int>(),
            "market.price-steps" to listOf(1.5), "market.products.OAK_LOG.shop" to "bad", "market.products.OAK_LOG" to 1,
            "market.input-timeout-seconds" to 0, "market.input-timeout-seconds" to 301)) {
            assertThrows(RuntimeException::class.java) { MarketSettings.load(YamlConfiguration().apply { set(path, value) }) }
        }
    }
}
