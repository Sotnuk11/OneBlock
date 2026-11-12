package com.alytanta.oneblock.market

import org.bukkit.configuration.file.YamlConfiguration
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

class AuctionLayoutsTest {
    @TempDir lateinit var folder: Path
    private fun read(yaml: YamlConfiguration): MarketMenuLayouts {
        val file = folder.resolve("market-menus.yml").toFile().apply { writeText(yaml.saveToString(), Charsets.UTF_8) }
        return MarketMenuLayouts.read(file).first
    }

    @Test fun `bundled menus have reference footer positions and no overlapping controls`() {
        val file = folder.resolve("market-menus.yml").toFile().apply {
            writeText(AuctionLayoutsTest::class.java.getResourceAsStream("/market-menus.yml")!!.reader().readText(), Charsets.UTF_8)
        }
        val menus = MarketMenuLayouts.read(file).first
        assertEquals(4, menus.window("auction-main", null).rows)
        assertEquals(27, menus.window("auction-main", null).target(45))
        assertEquals(31, menus.window("auction-main", null).target(1000))
        val browser = menus.window("auction-browse-all", null)
        for ((source, slot) in mapOf(7 to 48, 45 to 45, 47 to 50, 51 to 51, 49 to 53, 52 to 52, 1000 to 49)) assertEquals(slot, browser.target(source))
        assertEquals(listOf(0,9,18,27,36,44), browser.grid("categories", emptyList()).map { browser.target(it) })
        val confirm = menus.window("auction-confirm", null)
        assertEquals(3, confirm.rows); assertEquals(11, confirm.target(31)); assertEquals(18, confirm.target(45));assertEquals(22,confirm.target(1000))
        val draft = menus.window("auction-draft", null)
        assertEquals(29, draft.target(31)); assertEquals(31, draft.target(25)); assertEquals(33, draft.target(22)); assertEquals(48, draft.target(19))
        assertTrue(menus.windows.keys.containsAll(listOf("auction-sort", "auction-rarity", "auction-duration")))
    }

    @Test fun `legacy generated layouts upgrade in memory without rewriting the server file`() {
        val yaml = YamlConfiguration().apply {
            set("windows.auction-main.rows", 6)
            for (slot in listOf(4,11,13,15,20,24,31,45,46,47,48,49,50,51,52,53)) set("windows.auction-main.buttons.$slot.slot", slot)
            set("windows.auction-browse-all.rows", 6)
            for (slot in listOf(4,7,8,45,46,47,48,49,50,51,52,53)) set("windows.auction-browse-all.buttons.$slot.slot", slot)
            set("windows.auction-browse-all.grids.content", AuctionLayouts.content)
            set("windows.auction-browse-all.grids.categories", listOf(0,9,18,27,36))
            set("windows.auction-browse-all.grids.separators", listOf(1,10,19,28,37))
        }
        val menus = read(yaml)
        assertEquals(4, menus.window("auction-main", null).rows)
        assertEquals(52, menus.window("auction-browse-all", null).target(49))
        assertEquals(yaml.saveToString(), folder.resolve("market-menus.yml").toFile().readText())
    }

    @Test fun `legacy personalised positions and labels remain authoritative`() {
        val yaml = YamlConfiguration().apply {
            set("windows.auction-browse-all.rows", 6)
            set("windows.auction-browse-all.buttons.7.slot", 5)
            set("windows.auction-browse-all.buttons.7.name", "Custom search")
            set("windows.auction-browse-all.buttons.47.slot", 47)
        }
        val browser = read(yaml).window("auction-browse-all", null)
        assertEquals(5, browser.target(7)); assertEquals("Custom search", browser.buttons[7]?.name)
        assertEquals(47, browser.target(47)); assertEquals(6, browser.target(6))
    }

    @Test fun `short root and confirm do not inherit unused controls outside their inventory`() {
        val menus = MarketMenuLayouts(defaults = MarketWindow(buttons = mapOf(53 to MarketMenuButton(name = "Custom refresh"))))
        val root = menus.window("auction-main", null); val confirm = menus.window("auction-confirm", null)
        assertNull(root.target(53)); assertNull(confirm.target(53))
        assertEquals("Custom refresh", root.buttons[53]?.name)
    }
    @Test fun `version two stock collections upgrade while personalised layouts remain unchanged`() {
        val yaml = YamlConfiguration().apply {
            set("auction-layout-version", 2)
            set("windows.auction-claims.rows", 6)
            set("windows.auction-claims.filler", "INHERIT")
            set("windows.auction-claims.grids.content", (0..44).toList())
            for ((source, slot) in mapOf(45 to 49, 46 to 46, 52 to 52)) set("windows.auction-claims.buttons.$source.slot", slot)
        }
        val upgraded = read(yaml).window("auction-claims", null)
        assertEquals(AuctionLayouts.personal, upgraded.grid("content", emptyList()))
        assertEquals(48, upgraded.target(47)); assertEquals(53, upgraded.target(52))
        yaml.set("windows.auction-claims.title", "Custom collections")
        val custom = read(yaml).window("auction-claims", null)
        assertEquals((0..44).toList(), custom.grid("content", emptyList()))
        assertEquals(52, custom.target(52))
    }
}
