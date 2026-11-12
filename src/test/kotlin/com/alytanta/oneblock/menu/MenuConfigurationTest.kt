package com.alytanta.oneblock.menu

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

class MenuConfigurationTest {
    @TempDir lateinit var dir: Path
    private fun setup(): java.io.File {
        val root=dir.resolve("menus").toFile(); val folder=root.resolve("main-menu").apply { mkdirs() }
        for(name in listOf("layout.yml","commands.yml","statistics.yml")) folder.resolve(name).writeText(javaClass.getResourceAsStream("/menus/main-menu/$name")!!.reader(Charsets.UTF_8).use { it.readText() })
        val release=root.resolve("release").apply { mkdirs() }
        for(name in listOf("layout.yml","commands.yml","statistics.yml")) release.resolve(name).writeText(javaClass.getResourceAsStream("/menus/release/$name")!!.reader(Charsets.UTF_8).use { it.readText() })
        val guides=root.resolve("guides").apply { mkdirs() }
        for(name in listOf("layout.yml","commands.yml","statistics.yml")) guides.resolve(name).writeText(javaClass.getResourceAsStream("/menus/guides/$name")!!.reader(Charsets.UTF_8).use { it.readText() })
        val combat=root.resolve("combat").apply { mkdirs() }
        for(name in listOf("layout.yml","commands.yml","statistics.yml")) combat.resolve(name).writeText(javaClass.getResourceAsStream("/menus/combat/$name")!!.reader(Charsets.UTF_8).use { it.readText() })
        val dungeon=root.resolve("dungeon").apply {mkdirs()}
        for(name in listOf("layout.yml","commands.yml","statistics.yml"))dungeon.resolve(name).writeText(javaClass.getResourceAsStream("/menus/dungeon/$name")!!.reader(Charsets.UTF_8).use {it.readText()})
        return root
    }
    @Test fun `startup with an old layout missing no island uses bundled fallback without writing custom files`() {
        val root=setup()
        val layout=root.resolve("main-menu/layout.yml")
        val y=org.bukkit.configuration.file.YamlConfiguration().apply {load(layout)}
        y.set("menus.no-island",null);y.set("menus.main-menu.title","Custom title")
        val home=y.get("menus.main-menu.slots.20");y.set("menus.main-menu.slots.20",null);y.set("menus.main-menu.slots.21",home);y.save(layout)
        val commands=root.resolve("main-menu/commands.yml")
        val c=org.bukkit.configuration.file.YamlConfiguration().apply {load(commands)}
        c.set("commands.no-island",null);c.set("commands.profiles.permission","custom.profiles")
        c.set("commands.profiles.requires-open",listOf("main-menu"));c.save(commands)
        val before=root.walkTopDown().filter {it.isFile}.associate {it.relativeTo(root).path to it.readBytes().toList()}
        val plugin=MenuFixture.stub(org.bukkit.plugin.Plugin::class.java) {name,args->when(name) {
            "getDataFolder"->dir.resolve("one-block").toFile();"getResource"->javaClass.getResourceAsStream("/${args!![0]}");else->null
        }}
        MenuConfiguration.ensure(plugin)
        val config=MenuConfiguration.read(root)
        assertEquals(3,config.menus.getValue("no-island").rows)
        assertEquals("no-island",config.routes.getValue("no-island").target)
        assertEquals("Custom title",config.menus.getValue("main-menu").title)
        assertEquals("/obmenu home",config.menus.getValue("main-menu").slots.getValue(10).command)
        assertEquals("custom.profiles",config.routes.getValue("profiles").permission)
        assertEquals(setOf("main-menu"),config.routes.getValue("profiles").requiresOpen)
        assertTrue(config.inputsUnchanged())
        assertEquals(before,root.walkTopDown().filter {it.isFile}.associate {it.relativeTo(root).path to it.readBytes().toList()})
    }
    @Test fun `custom no island layout and route take priority over the bundled fallback`() {
        val root=setup();val file=root.resolve("main-menu/layout.yml")
        val y=org.bukkit.configuration.file.YamlConfiguration().apply {load(file)}
        y.set("menus.no-island.rows",1);y.set("menus.no-island.title","Custom missing profile")
        y.set("menus.no-island.slots",mapOf("4" to mapOf("material" to "EMERALD","name" to "My profiles","command" to "/obmenu profiles")));y.save(file)
        val commands=root.resolve("main-menu/commands.yml");val c=org.bukkit.configuration.file.YamlConfiguration().apply {load(commands)}
        c.set("commands.no-island.permission","custom.missing-profile");c.set("commands.no-island.requires-open",listOf("main-menu"));c.save(commands)
        val config=MenuConfiguration.read(root)
        assertEquals(1,config.menus.getValue("no-island").rows)
        assertEquals("Custom missing profile",config.menus.getValue("no-island").title)
        assertEquals(setOf(4),config.menus.getValue("no-island").slots.keys)
        assertEquals("custom.missing-profile",config.routes.getValue("no-island").permission)
        assertEquals(setOf("main-menu"),config.routes.getValue("no-island").requiresOpen)
    }
    @Test fun `fallback does not restore missing gameplay or guide routes or leave dangling buttons`() {
        val root=setup()
        for(group in listOf("main-menu","release","guides","combat","dungeon")) {
            val file=root.resolve("$group/layout.yml");val y=org.bukkit.configuration.file.YamlConfiguration().apply {load(file)}
            if(group=="main-menu")y.set("menus.no-island",null)
            val menus=y.getConfigurationSection("menus")!!
            for(id in menus.getKeys(false)) {
                val slots=menus.getConfigurationSection("$id.slots")?:continue
                for(key in slots.getKeys(false).toList())if(slots.getString("$key.command") in setOf("/obmenu profiles","/obmenu guide-index"))slots.set(key,null)
            };y.save(file)
        }
        for((group,id) in listOf("main-menu" to "profiles","guides" to "guide-index")) {
            val file=root.resolve("$group/commands.yml");val y=org.bukkit.configuration.file.YamlConfiguration().apply {load(file)}
            y.set("commands.$id",null);y.save(file)
        }
        val config=MenuConfiguration.read(root)
        assertFalse("profiles" in config.routes);assertFalse("guide-index" in config.routes)
        val fallback=config.menus.getValue("no-island")
        assertTrue(listOf(13,15).all {fallback.slots.getValue(it).command==null})
        assertTrue(listOf(13,15).all {"@gui.action_unavailable" in fallback.slots.getValue(it).item!!.lore})
        assertEquals("/obmenu close",fallback.slots.getValue(22).command)
    }
    @Test fun `invalid custom no island is rejected and is never masked by the fallback`() {
        val root=setup();val active=MenuConfiguration.read(root)
        val file=root.resolve("main-menu/layout.yml");val y=org.bukkit.configuration.file.YamlConfiguration().apply {load(file)}
        y.set("menus.no-island.rows",0);y.save(file);val invalid=file.readText()
        assertThrows(IllegalArgumentException::class.java) {MenuConfiguration.read(root)}
        assertEquals(invalid,file.readText());assertEquals(3,active.menus.getValue("no-island").rows)
        assertFalse(active.inputsUnchanged())
    }
    @Test fun `no island screen can use configured rows and button positions`() {
        val root = setup()
        val file = root.resolve("main-menu/layout.yml")
        val y = org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(file)
        val previous = y.get("menus.no-island.slots.13")
        y.set("menus.no-island.rows", 1); y.set("menus.no-island.slots", null)
        y.set("menus.no-island.slots.4", previous); y.save(file)
        val screen = MenuConfiguration.read(root).menus.getValue("no-island")
        assertEquals(1, screen.rows); assertEquals(setOf(4), screen.slots.keys)
    }
    @Test fun `no island menu and persisted toggle states are configured alongside custom menus`() {
        val root=setup();val config=MenuConfiguration.read(root)
        assertEquals(3,config.menus.getValue("no-island").rows)
        assertEquals(setOf(13,15,22),config.menus.getValue("no-island").slots.keys)
        assertEquals("profiles",config.routes.getValue("profiles").type)
        val button=config.menus.getValue("settings").slots.getValue(40)
        assertEquals(setOf("true","false"),button.states.keys)
        assertEquals("player.inventory_notifications",button.stateSource)
        assertEquals(setOf("settings"),config.routes.getValue("inventory-notifications").requiresOpen)
        assertNotNull(config.routes.getValue("inventory-notifications").directPermission)
        assertFalse("collections" in config.menus)
        val custom=root.resolve("custom").apply { mkdirs() }
        custom.resolve("layout.yml").writeText("""
            version: 1
            menus:
              custom:
                rows: 3
                title: Custom
                slots:
                  '13':
                    initial-state: first
                    states:
                      first: {material: STONE, command: /obmenu close, next-state: second}
                      second: {material: DIRT, command: /obmenu close, next-state: first}
        """.trimIndent())
        assertEquals("second",MenuConfiguration.read(root).menus.getValue("custom").slots.getValue(13).states.getValue("first").nextState)
    }
    @Test fun `existing menu configs migrate with backups and preserve customized titles`() {
        val root=setup()
        val commands=root.resolve("main-menu/commands.yml")
        val c=org.bukkit.configuration.file.YamlConfiguration().apply { loadFromString(commands.readText()) }
        c.set("commands.profiles.type","player-command");c.set("commands.profiles.execute","ob profiles")
        c.set("commands.no-island",null); c.save(commands)
        val layout=root.resolve("main-menu/layout.yml")
        val l=org.bukkit.configuration.file.YamlConfiguration().apply { loadFromString(layout.readText()) }
        l.set("menus.no-island",null);l.set("menus.main-menu.title","Custom title");l.save(layout)
        val plugin=MenuFixture.stub(org.bukkit.plugin.Plugin::class.java) { name,args -> when(name) {
            "getDataFolder" -> dir.resolve("one-block").toFile()
            "getResource" -> javaClass.getResourceAsStream("/${args!![0]}")
            else -> null
        } }
        MenuConfiguration.ensure(plugin)
        assertEquals("player-command",org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(commands).getString("commands.profiles.type"))
        assertFalse(root.resolve("main-menu/commands.yml.before-v16").exists())
        MenuConfiguration.migrateLegacy(plugin)
        val config=MenuConfiguration.read(root)
        assertEquals("profiles",config.routes.getValue("profiles").type)
        assertEquals("Custom title",config.menus.getValue("main-menu").title)
        assertEquals(3,config.menus.getValue("no-island").rows)
        assertTrue(root.resolve("main-menu/commands.yml.before-v16").isFile)
        MenuConfiguration.ensure(plugin)
    }
    @Test fun `invalid transitions fail before publication and dependency can be disabled explicitly`() {
        val root=setup();val commands=root.resolve("main-menu/commands.yml")
        val commandYaml=org.bukkit.configuration.file.YamlConfiguration().apply {load(commands)}
        commandYaml.set("commands.inventory-notifications.require-open-menu",false);commandYaml.save(commands)
        assertTrue(MenuConfiguration.read(root).routes.getValue("inventory-notifications").requiresOpen.isEmpty())
        val layout=root.resolve("main-menu/layout.yml")
        val layoutYaml=org.bukkit.configuration.file.YamlConfiguration().apply {load(layout)}
        layoutYaml.set("menus.settings.slots.31.initial-state","missing");layoutYaml.save(layout)
        assertThrows(IllegalArgumentException::class.java) { MenuConfiguration.read(root) }
    }
}
