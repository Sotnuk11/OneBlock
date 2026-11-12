package com.alytanta.oneblock.menu

import com.alytanta.oneblock.config.ConfigSnapshot
import com.alytanta.oneblock.dungeon.DungeonGuideSettings
import com.alytanta.oneblock.dungeon.MineSettings
import com.alytanta.oneblock.player.ProfileSettings
import org.bukkit.configuration.file.YamlConfiguration
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path
import java.util.logging.Logger

class ConfiguredNavigationTest {
    @TempDir lateinit var temporary:Path
    private fun setup():File {
        val root=temporary.resolve("plugins").toFile().apply {mkdirs()}
        val data=root.resolve("one-block").apply {mkdirs()}
        File("src/main/resources").listFiles()!!.filter {it.extension=="yml"}.forEach {it.copyTo(data.resolve(it.name))}
        File("src/main/resources/menus").copyRecursively(root.resolve("menus"))
        return data
    }
    @Test fun `old reward GUI gets missing chest pages controls and placeholders only in memory`() {
        val data=setup();val folder=data.parentFile.resolve("menus/release")
        val layout=folder.resolve("layout.yml");val y=YamlConfiguration.loadConfiguration(layout)
        y.set("menus.chest-preview",null);y.set("menus.chest-confirm",null);y.set("menus.release-claims.slots.47",null);y.set("menus.release-claims.slots.50",null);y.save(layout)
        val commands=folder.resolve("commands.yml");val c=YamlConfiguration.loadConfiguration(commands)
        listOf("claims-all","chests-confirm","chests-buy","chests-set","chests-back","chests-loot-next","chests-loot-previous").forEach {c.set("commands.$it",null)};c.save(commands)
        val statistics=folder.resolve("statistics.yml");val v=YamlConfiguration.loadConfiguration(statistics);v.set("values.reward_quote",null);v.set("values.reward_pity",null);v.save(statistics)
        val before=listOf(layout,commands,statistics).associateWith {it.readText()}
        val menus=MenuConfiguration.read(data.parentFile.resolve("menus"))
        assertTrue(menus.menus.keys.containsAll(listOf("chest-preview","chest-confirm")))
        assertTrue(menus.routes.keys.containsAll(listOf("claims-all","chests-buy")))
        assertEquals("reward.quote",menus.statistics.values["reward_quote"]!!.source)
        assertEquals("/obmenu claims-all",menus.menus["release-claims"]!!.slots[47]!!.command)
        before.forEach {(file,text)->assertEquals(text,file.readText())}
    }
    private fun validate(data:File) {
        val before=data.parentFile.walkTopDown().filter {it.extension=="yml"}.associate {it.path to it.readText()}
        val config=ConfigSnapshot.read(data.resolve("config.yml"),Logger.getAnonymousLogger())
        val menus=config.mainMenus!!
        // Check the actual configured parent of EVERY displayed static route, including state buttons.
        menus.menus.values.forEach {window->window.slots.values.flatMap {listOf(it)+it.states.values}.forEach {cell->
            cell.command?.let {command->
                val route=menus.routeFor(command)!!
                assertTrue(route.requiresOpen.isEmpty() || "*" in route.requiresOpen || window.id in route.requiresOpen,
                    "${window.id}: $command cannot be reached from its displayed parent")
                if(window.id in setOf("main-menu","economy","dungeon-select","mine-lobby"))assertNull(route.permission,"Player navigation must not expose administrative actions")
            }
        }}
        val root=menus.menus.getValue("main-menu")
        val actions=root.slots.values.mapNotNull {it.command?.let(menus::routeFor)?.id}.toSet()
        assertTrue(actions.containsAll(setOf("home","profiles","coop","release-core","release-skills","release-workshop","combat-weapons","economy","release-claims","release-goals","guide-index","hub","dungeons")))
        for(id in listOf("economy","dungeon-select","mine-lobby","release-core","release-skills","release-workshop","release-goals","release-claims","combat-weapons","combat-armor","combat-components","guides-index","guide-page")) {
            val window=menus.menus.getValue(id)
            assertEquals("/obmenu close",window.slots[(window.rows-1)*9+4]?.command,id)
            assertNotNull(window.slots[(window.rows-1)*9]?.command,"$id needs back")
        }
        assertEquals(1,menus.menus.getValue("dungeon-select").slots.values.count {it.command=="/obmenu mine-menu"})
        assertEquals("select",menus.routes.getValue("dungeons").target)
        val profiles=ProfileSettings.read(data.resolve("profiles.yml")).first
        assertNotNull(profiles.back);assertEquals(45,profiles.backSlot)
        config.interfaces.windows.values.forEach {w->
            if(w.buttons.containsKey(GuiContracts.source(w.id,"close")))assertEquals((w.rows-1)*9+4,w.target(GuiContracts.source(w.id,"close")),w.id)
        }
        assertEquals(before,data.parentFile.walkTopDown().filter {it.extension=="yml"}.associate {it.path to it.readText()})
        assertTrue(config.inputsUnchanged(data.resolve("config.yml")))
    }
    @Test fun `bundled menu graph has every player section valid routes back close and one implemented dungeon`()=validate(setup())
    @Test fun `actual staged server candidate retains valid configuration and navigation without touching runtime data`() {
        val path=System.getenv("ONEBLOCK_CONFIG_AUDIT")?.takeIf {it.isNotBlank()}?:return
        validate(File(path))
    }
    @Test fun `NPC config binds the role without reading legacy automatic-spawn coordinates`() {
        assertFalse(DungeonGuideSettings.read(YamlConfiguration()).enabled)
        val y=YamlConfiguration().apply {loadFromString("npc: {enabled: true, world: '', type: VILLAGER}")}
        assertTrue(DungeonGuideSettings.read(y).enabled)
        y.set("npc.world","oneblock_dungeons:mine");y.set("npc.x",Double.NaN)
        assertTrue(DungeonGuideSettings.read(y).enabled)
        y.set("npc.type","WITHER");assertThrows(IllegalArgumentException::class.java) {DungeonGuideSettings.read(y)}
    }
    @Test fun `old dungeon configurations keep all balance values and get new feedback without rewriting file`() {
        val file=setup().resolve("dungeon.yml")
        val y=YamlConfiguration().apply {load(file)}
        y.set("npc",null);y.set("estimated-minutes",null);y.set("messages.entry-near",null);y.save(file)
        val before=file.readText();val config=MineSettings.read(file).first!!
        assertFalse(config.npc.enabled);assertEquals("10–15",config.estimatedMinutes)
        assertTrue(config.text("entry-near").contains("радіус"));assertEquals(before,file.readText())
    }
    @Test fun `profile back control rejects collisions and retains old configurations`() {
        val file=setup().resolve("profiles.yml");val y=YamlConfiguration().apply {load(file)}
        y.set("back.slot",49);y.save(file)
        assertThrows(IllegalArgumentException::class.java) {ProfileSettings.read(file)}
        y.set("back",null);y.save(file);assertNull(ProfileSettings.read(file).first.back)
    }
}
