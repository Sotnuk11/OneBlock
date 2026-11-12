package com.alytanta.oneblock.menu

import com.alytanta.oneblock.market.MarketMenuLayouts
import com.alytanta.oneblock.trade.TradeSettings
import org.bukkit.Material
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

class InterfaceMenusTest {
    @TempDir lateinit var directory: Path
    private fun layout(text: String) = InterfaceMenus.read(directory.resolve("interface-menus.yml").toFile().apply { writeText(text) }).first

    @Test fun `bundled screens cover all dynamic interfaces and missing file retains defaults`() {
        val fallback = InterfaceMenus.read(directory.resolve("absent.yml").toFile()).first
        val bundled = layout(javaClass.getResourceAsStream("/interface-menus.yml")!!.reader().use { it.readText() })
        assertEquals(InterfaceMenus.catalog.keys, bundled.windows.keys)
        for (id in InterfaceMenus.catalog.keys) {
            assertEquals(fallback.window(id, null).rows, bundled.window(id, null).rows)
            val window=bundled.window(id,null)
            assertEquals(fallback.window(id,null).buttons.keys,window.buttons.keys)
            val contract=GuiContracts.all.getValue(id)
            for((action,control) in contract.controls) {
                val expected=when(action) {"back"->(window.rows-1)*9;"previous"->(window.rows-1)*9+1;"next"->(window.rows-1)*9+7;"close"->(window.rows-1)*9+4;else->null}
                if(expected!=null)assertEquals(expected,window.target(control.source),"$id/$action")
            }
            assertNull(fallback.window(id,null).target(1000))
            assertNotNull(bundled.window(id,null).target(1000))
        }
    }

    @Test fun `smaller confirmation moves actions hides information and keeps full custom style`() {
        val parsed = layout("""
            version: 1
            windows:
              profile-delete:
                rows: 1
                title: '&8Delete {name}'
                filler: BLUE_STAINED_GLASS_PANE
                buttons:
                  '11': {slot: 2, icon: RED_DYE, name: 'Remove {name}', lore: ['Member {member}'], amount: 2, glow: true, custom-model-data: 123}
                  '13': {enabled: false}
                  '15': {slot: 6}
        """.trimIndent()).window("profile-delete", null)
        assertEquals(2, parsed.target(11)); assertNull(parsed.target(13)); assertEquals(6, parsed.target(15))
        val styled = parsed.style(11, MenuItemSpec(Material.BOOK, "default", emptyList()))!!
        assertEquals(Material.RED_DYE, styled.material); assertEquals(2, styled.amount)
        assertTrue(styled.glow); assertEquals(123, styled.customModel)
        assertEquals(listOf("Member {member}"), styled.lore)
    }

    @Test fun `invalid menu layouts fail before publication including inherited collisions`() {
        listOf(
            "windows: {unknown: {rows: 3}}",
            "windows: {profile-delete: {rows: 1}}",
            "windows: {profile-delete: {buttons: {'11': {slot: 15}}}}",
            "windows: {profile-delete: {buttons: {'11': {enabled: yesplease}}}}",
            "windows: {profile-delete: {buttons: {'11': {lore: [2]}}}}",
            "windows: {profile-delete: {buttons: {'11': {amount: 0}}}}",
            "windows: {profile-delete: {buttons: {'11': {icon: AIR}}}}",
            "windows: {admin-members: {grids: {content: [1, 1]}}}",
            "defaults: {buttons: {'11': {slot: 15}}}",
        ).forEach { text -> assertThrows(IllegalArgumentException::class.java, { layout("version: 1\n$text") }, text) }
    }

    @Test fun `trade styling is optional and default files remain compatible`() {
        val file = directory.resolve("trades.yml").toFile()
        file.writeText(javaClass.getResourceAsStream("/trades.yml")!!.reader().use { it.readText() })
        val bundled = TradeSettings.read(file).first
        assertEquals(6, bundled.rows); assertTrue(bundled.visible("own-wait"))
        file.writeText("menu:\n  title: '&8With {player}'\n  own-item-lore: []\n  styles:\n    own-wait: {enabled: false}\n    own-coins: {icon: EMERALD, name: '{coins}', lore: [], glow: true}\n")
        val styled = TradeSettings.read(file).first
        assertFalse(styled.visible("own-wait")); assertEquals(emptyList<String>(), styled.ownItemLore)
        assertEquals(Material.EMERALD, styled.styles.getValue("own-coins").icon)
        assertEquals(bundled.ownSlots, styled.ownSlots)
    }

    @Test fun `trade rejects grid collisions out of bounds and unrecognized styles`() {
        val file = directory.resolve("trades.yml").toFile()
        listOf("menu: {rows: 2}", "menu: {own-coins: 39}", "menu: {styles: {cancel: {slot: 2}}}",
            "menu: {styles: {unknown: {}}}", "menu: {styles: {cancel: {glow: blah}}}", "menu: {own-item-lore: [true]}").forEach {
            file.writeText(it); assertThrows(IllegalArgumentException::class.java, { TradeSettings.read(file) }, it)
        }
    }
}
