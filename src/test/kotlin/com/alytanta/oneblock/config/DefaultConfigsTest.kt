package com.alytanta.oneblock.config

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.io.TempDir
import org.bukkit.configuration.file.YamlConfiguration
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class DefaultConfigsTest {
    @TempDir lateinit var root:Path
    private val folder get()=root.resolve("plugins/one-block").toFile()
    private fun resource(name:String)=javaClass.getResourceAsStream("/$name")
    @Test fun `fresh install creates every bundled config and individual deleted files are restored without overwriting custom siblings`() {
        val created=DefaultConfigs.ensure(folder,::resource)
        assertEquals(33,created.size)
        val bundled=java.io.File("src/main/resources").walkTopDown().filter {it.isFile && it.extension=="yml" && it.name!="plugin.yml"}
            .map {it.relativeTo(java.io.File("src/main/resources")).invariantSeparatorsPath}.toSet()
        assertEquals(bundled,(DefaultConfigs.dataFiles+DefaultConfigs.menuFiles+"auction.yml").toSet())
        val pack=YamlConfiguration.loadConfiguration(folder.resolve("resource-pack.yml"))
        assertFalse(pack.getBoolean("enabled"));assertFalse(pack.getBoolean("required"))
        val commands=root.resolve("plugins/menus/combat/commands.yml")
        Files.writeString(commands,"custom invalid bytes: [")
        folder.resolve("items.yml").delete();folder.resolve("resource-pack.yml").delete()
        val layout=root.resolve("plugins/menus/combat/layout.yml");Files.delete(layout)
        assertEquals(3,DefaultConfigs.ensure(folder,::resource).size)
        assertEquals("custom invalid bytes: [",Files.readString(commands))
        assertArrayEquals(resource("menus/combat/layout.yml")!!.use {it.readBytes()},Files.readAllBytes(layout))
        assertTrue(DefaultConfigs.ensure(folder,::resource).isEmpty())
        assertFalse(folder.resolve(".env").exists());assertFalse(folder.resolve("mine-sessions").exists())
    }
    @Test fun `reload restores captured custom files including auction restrictions instead of bundled defaults`() {
        DefaultConfigs.ensure(folder,::resource)
        val auction=folder.resolve("auction.yml");auction.writeText("auction:\n  allowed-items: []\n")
        val items=folder.resolve("items.yml");items.appendText("\n# custom appearance\n")
        val previous=DefaultConfigs.capture(folder)
        auction.delete();items.delete()
        val restored=DefaultConfigs.ensure(folder,::resource,previous)
        assertEquals(2,restored.size)
        assertEquals("auction:\n  allowed-items: []\n",auction.readText())
        assertTrue(items.readText().endsWith("# custom appearance\n"))
    }
    @Test fun `legacy auction settings migrate once while existing invalid files and directory conflicts remain untouched`() {
        folder.mkdirs();folder.resolve("config.yml").writeText("market:\n  auction:\n    allowed-items: []\n    max-active: 7\n")
        DefaultConfigs.ensure(folder,::resource)
        val auction=YamlConfiguration.loadConfiguration(folder.resolve("auction.yml"))
        assertEquals(emptyList<String>(),auction.getStringList("auction.allowed-items"));assertEquals(7,auction.getInt("auction.max-active"))
        folder.resolve("resource-pack.yml").delete();folder.resolve("resource-pack.yml").mkdir()
        val ex=assertThrows(IllegalArgumentException::class.java) {DefaultConfigs.ensure(folder,::resource)}
        assertTrue(ex.message!!.contains("resource-pack.yml"));assertTrue(folder.resolve("resource-pack.yml").isDirectory)
    }
    @Test fun `failed copy is never published and concurrent creation preserves exactly one complete file`() {
        val target=root.resolve("nested/config.yml")
        assertThrows(java.io.IOException::class.java) {DefaultConfigs.writeMissing(target) {object:InputStream() {
            override fun read():Int=throw java.io.IOException("broken resource")
        }}}
        assertFalse(Files.exists(target))
        assertEquals(0L,Files.list(target.parent).use {it.count()})
        val pool=Executors.newFixedThreadPool(8)
        try {
            val tasks=(1..16).map {i->pool.submit<Boolean> {DefaultConfigs.writeMissing(target) {("complete-$i").byteInputStream()}}}
            assertEquals(1,tasks.count {it.get(10,TimeUnit.SECONDS)})
            assertTrue(Files.readString(target).matches(Regex("complete-\\d+")))
        } finally {pool.shutdownNow()}
    }
}
