package com.alytanta.oneblock.config

import org.bukkit.configuration.file.YamlConfiguration
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path

class InterfaceConfigUpdatesTest {
    @TempDir lateinit var root:Path
    private val folder get()=root.resolve("plugins/one-block").toFile()
    private fun resource(name:String):InputStream?=javaClass.getResourceAsStream("/$name")
    private fun bundled(name:String)=resource(name)!!.use {it.readBytes()}

    @Test fun `old interface files are replaced once with a backup and settings stay untouched`() {
        DefaultConfigs.ensure(folder,::resource)
        val sidebar=folder.resolve("sidebar.yml");sidebar.writeText("old: true\n")
        val layout=root.resolve("plugins/menus/main-menu/layout.yml");Files.writeString(layout,"old: layout\n")
        val config=folder.resolve("harvest.yml");config.appendText("\n# hub settings\n")
        val items=folder.resolve("trades.yml");items.appendText("\n# custom items\n")
        val keptConfig=config.readText();val keptItems=items.readText()

        val first=InterfaceConfigUpdates.apply(folder,::resource)
        assertTrue(first.failed.isEmpty(),first.failed.toString())
        assertTrue(first.updated.containsAll(listOf("sidebar.yml","menus/main-menu/layout.yml")))
        assertArrayEquals(bundled("sidebar.yml"),sidebar.readBytes())
        assertArrayEquals(bundled("menus/main-menu/layout.yml"),Files.readAllBytes(layout))
        assertEquals("old: true\n",Files.readString(first.backup.resolve("sidebar.yml")))
        assertEquals("old: layout\n",Files.readString(first.backup.resolve("menus/main-menu/layout.yml")))
        assertEquals(keptConfig,config.readText());assertEquals(keptItems,items.readText())

        sidebar.writeText("edited: after-update\n")
        val second=InterfaceConfigUpdates.apply(folder,::resource)
        assertTrue(second.updated.isEmpty(),second.updated.toString())
        assertEquals("edited: after-update\n",sidebar.readText())
    }
    @Test fun `new bundled version replaces an edited file again`() {
        DefaultConfigs.ensure(folder,::resource)
        InterfaceConfigUpdates.apply(folder,::resource)
        val sidebar=folder.resolve("sidebar.yml");sidebar.writeText("edited: true\n")
        val next:(String)->InputStream?={name->if(name=="sidebar.yml")"title: new\n".byteInputStream() else resource(name)}
        assertEquals(listOf("sidebar.yml"),InterfaceConfigUpdates.apply(folder,next).updated)
        assertEquals("title: new\n",sidebar.readText())
    }
    @Test fun `dungeon keeps entry and balance and only takes bundled messages`() {
        DefaultConfigs.ensure(folder,::resource)
        val file=folder.resolve("dungeon.yml")
        val y=YamlConfiguration.loadConfiguration(file)
        y.set("enabled",true);y.set("entry.world","oneblock_hubs:hub");y.set("messages.won","old text");y.save(file)
        val result=InterfaceConfigUpdates.apply(folder,::resource)
        assertTrue("dungeon.yml" in result.updated)
        val after=YamlConfiguration.loadConfiguration(file)
        val source=YamlConfiguration().apply {loadFromString(bundled("dungeon.yml").toString(Charsets.UTF_8))}
        assertTrue(after.getBoolean("enabled"));assertEquals("oneblock_hubs:hub",after.getString("entry.world"))
        assertEquals(source.getString("messages.won"),after.getString("messages.won"))
    }
    @Test fun `combat recipes and missing items arrive while custom items stay`() {
        DefaultConfigs.ensure(folder,::resource)
        val items=folder.resolve("items.yml");val y=YamlConfiguration.loadConfiguration(items)
        y.set("items.oneblock:ember_shortbow",null);y.set("items.oneblock:warden_blade.name","Custom");y.save(items)
        val combat=folder.resolve("combat.yml");val c=YamlConfiguration.loadConfiguration(combat);c.set("recipes.ember_shortbow",null);c.save(combat)
        assertTrue(InterfaceConfigUpdates.apply(folder,::resource).updated.containsAll(listOf("items.yml","combat.yml")))
        val after=YamlConfiguration.loadConfiguration(items)
        assertEquals("BOW",after.getString("items.oneblock:ember_shortbow.material"));assertEquals("Custom",after.getString("items.oneblock:warden_blade.name"))
        assertTrue(YamlConfiguration.loadConfiguration(combat).contains("recipes.ember_shortbow"))
    }
    @Test fun `failed file keeps current bytes and stays pending`() {
        DefaultConfigs.ensure(folder,::resource)
        val sidebar=folder.resolve("sidebar.yml");sidebar.writeText("old: true\n")
        val broken:(String)->InputStream?={name->if(name=="sidebar.yml")null else resource(name)}
        assertTrue("sidebar.yml" in InterfaceConfigUpdates.apply(folder,broken).failed)
        assertEquals("old: true\n",sidebar.readText())
        assertTrue("sidebar.yml" in InterfaceConfigUpdates.apply(folder,::resource).updated)
    }
}
