package com.alytanta.oneblock.menu

import com.alytanta.oneblock.market.*
import com.alytanta.oneblock.trade.TradeSettings
import org.bukkit.Material
import org.bukkit.configuration.file.YamlConfiguration
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

class GuiContractsTest {
    @TempDir lateinit var dir:Path
    private fun market(text:String):MarketMenuLayouts=MarketMenuLayouts.read(dir.resolve("market.yml").toFile().apply {writeText(text)}).first
    private fun staticRoot():java.io.File {
        val root=dir.resolve("menus").toFile()
        for(group in listOf("main-menu","release","guides","combat","dungeon"))for(file in listOf("commands.yml","layout.yml","statistics.yml")) {
            val target=root.resolve("$group/$file");target.parentFile.mkdirs()
            target.writeText(javaClass.getResourceAsStream("/menus/$group/$file")!!.reader(Charsets.UTF_8).use {it.readText()})
        }
        return root
    }
    @Test fun `coverage includes every inventory factory all configured windows and the virtual sign`() {
        val sources=java.io.File("src/main/kotlin").walkTopDown().filter {it.extension=="kt"}.toList()
        assertEquals(setOf("PlayerMenu.kt","ProfileController.kt","AdminMenu.kt","MarketController.kt","TradeController.kt"),sources.filter {"createInventory(" in it.readText()}.map {it.name}.toSet())
        assertEquals(setOf("VirtualSignInput.kt"),sources.filter {"openVirtualSign(" in it.readText()}.map {it.name}.toSet())
        val bundled=market(javaClass.getResourceAsStream("/market-menus.yml")!!.reader().use {it.readText()})
        assertEquals(42,bundled.windows.size)
        assertEquals(47,GuiContracts.all.size)
        assertEquals(31,MenuConfiguration.read(staticRoot()).menus.size)
        GuiContracts.all.forEach {(_,c)->assertEquals(c.controls.size,c.controls.values.map {it.source}.distinct().size)}
    }
    @Test fun `named actions move hide and reject unknown aliases collisions and invalid styles atomically`() {
        val good="auction-layout-version: 3\nwindows: {auction-main: {buttons: {browse: {slot: 12, icon: CHEST, texture: null}, manage: {enabled: false}}}}"
        val active=market(good.replace(", texture: null",""))
        assertEquals(12,active.window("auction-main",null).target(11))
        assertNull(active.window("auction-main",null).target(15))
        for(bad in listOf(
            "buttons: {unknown: {}}","buttons: {browse: {slot: 13}}","buttons: {browse: {slot: '12'}}",
            "buttons: {browse: {slot: 12}, '11': {slot: 10}}","buttons: {browse: {glow: wrong}}",
            "buttons: {browse: {icon: NOT_A_MATERIAL}}","buttons: {browse: {texture: 'https://example.org/skin'}}",
            "buttons: {browse: {states: {unavailable: {slot: 1}}}}","grids: {wrong: [1]}","templates: {preview: {slot: 1}}",
            "templates: {content: {amount: '2'}}","background: {unknown: 1}","decorations: {'54': {material: PAPER}}"
        )) assertThrows(Exception::class.java,{market("auction-layout-version: 3\nwindows: {auction-main: {$bad}}")},bad)
        assertEquals(12,active.window("auction-main",null).target(11))
    }
    @Test fun `legacy indexed claims migrate in memory without replacing custom title positions or source files`() {
        val root=staticRoot();val file=root.resolve("release/layout.yml")
        val y=YamlConfiguration().apply {load(file)}
        y.set("menus.release-claims.content",null);y.set("menus.release-claims.entry",null);y.set("menus.release-claims.empty",null)
        y.set("menus.release-claims.slots.46",null);y.set("menus.release-claims.slots.52",null)
        y.set("menus.release-claims.title","Custom claims")
        for(i in 0..6)y.set("menus.release-claims.slots.${19+i}",mapOf("material" to "CHEST","command" to "/obmenu release-claim-$i"))
        y.save(file)
        val commands=root.resolve("release/commands.yml");val c=YamlConfiguration().apply {load(commands)}
        for(i in 0..6)c.set("commands.release-claim-$i",mapOf("aliases" to emptyList<String>(),"type" to "release","execute" to "claim $i","requires-open" to listOf("release-claims")))
        c.save(commands)
        val before=file.readText();val config=MenuConfiguration.read(root);val view=config.menus.getValue("release-claims")
        assertEquals("Custom claims",view.title);assertEquals((19..25).toList(),view.content)
        assertTrue(view.slots.values.any {it.command=="/obmenu claims-next"})
        assertEquals(before,file.readText())
    }
    @Test fun `rich templates support states heads models and configurable empty cells`() {
        val parsed=market("""
            auction-layout-version: 3
            windows:
              bazaar-claims:
                templates:
                  content: {icon: PLAYER_HEAD, texture: '{player}', glow: true, custom-model-data: 72, item-model: 'oneblock:claim', states: {selected: {name: Selected}}}
                  empty: {slot: 22, name: Empty}
        """.trimIndent()).window("bazaar-claims",null)
        val style=parsed.guiStyle(2,"content","selected")!!
        assertEquals("Selected",style.name);assertEquals("{player}",style.texture);assertEquals(72,style.customModelData)
        assertEquals("oneblock:claim",style.model);assertEquals(22,parsed.templates["empty"]?.slot)
    }
    @Test fun `smaller trade areas moved actions and hidden states remain isolated from offered items`() {
        val file=dir.resolve("trade.yml").toFile().apply {writeText("""
            menu:
              rows: 3
              own-items: [0,1]
              other-items: [7,8]
              divider-slots: [4,13]
              own-coins: 9
              other-coins: 17
              own-ready: 10
              other-ready: 16
              cancel: 22
              own-name: 18
              other-name: 26
              status: 21
              styles:
                own-coins: {slot: 11, icon: EMERALD}
                own-wait: {enabled: false}
        """.trimIndent())}
        val settings=TradeSettings.read(file).first
        assertEquals(27,settings.rows*9);assertEquals(listOf(0,1),settings.ownSlots)
        assertEquals("coins",settings.actionAt(11,false));assertNull(settings.actionAt(9,false))
        assertNull(settings.actionAt(10,false));assertEquals("ready",settings.actionAt(10,true))
        assertEquals("cancel",settings.actionAt(22,false));settings.ownSlots.forEach {assertNull(settings.actionAt(it,false))}
        val good=file.readText()
        for(bad in listOf(good.replace("other-items: [7,8]","other-items: [1,8]"),good.replace("rows: 3","rows: 2"),good.replace("styles:","styles:\n    other-item: {enabled: false}"))) {
            file.writeText(bad);assertThrows(Exception::class.java) {TradeSettings.read(file)}
        }
        assertEquals("coins",settings.actionAt(11,false))
    }
}
