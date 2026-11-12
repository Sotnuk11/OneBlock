package com.alytanta.oneblock.config

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.logging.Logger

class ConfigSnapshotTest {
    @TempDir lateinit var temporary: Path
    // plugins/menus is a sibling of the data folder. Keep that sibling inside this test's TempDir.
    private val directory:Path get()=temporary.resolve("one-block").also {java.nio.file.Files.createDirectories(it)}
    private fun read(text: String): ConfigSnapshot {
        val file = directory.resolve("config.yml").toFile().apply { writeText(text) }
        val root = com.alytanta.oneblock.menu.MenuConfiguration.root(file.parentFile).resolve("main-menu")
        root.mkdirs()
        listOf("commands.yml","layout.yml","statistics.yml").forEach { name ->
            val target = root.resolve(name)
            if(!target.exists()) target.writeText(javaClass.getResourceAsStream("/menus/main-menu/$name")!!.reader(Charsets.UTF_8).use { it.readText() })
        }
        val release=root.parentFile.resolve("release").apply { mkdirs() }
        listOf("commands.yml","layout.yml","statistics.yml").forEach { name ->
            val target=release.resolve(name)
            if(!target.exists())target.writeText(javaClass.getResourceAsStream("/menus/release/$name")!!.reader(Charsets.UTF_8).use { it.readText() })
        }
        val guides=root.parentFile.resolve("guides").apply { mkdirs() }
        listOf("commands.yml","layout.yml","statistics.yml").forEach { name ->
            guides.resolve(name).writeText(javaClass.getResourceAsStream("/menus/guides/$name")!!.reader(Charsets.UTF_8).use { it.readText() })
        }
        val combat=root.parentFile.resolve("combat").apply { mkdirs() }
        listOf("commands.yml","layout.yml","statistics.yml").forEach { name ->
            combat.resolve(name).writeText(javaClass.getResourceAsStream("/menus/combat/$name")!!.reader(Charsets.UTF_8).use { it.readText() })
        }
        val dungeon=root.parentFile.resolve("dungeon").apply {mkdirs()}
        for(name in listOf("layout.yml","commands.yml","statistics.yml"))dungeon.resolve(name).writeText(javaClass.getResourceAsStream("/menus/dungeon/$name")!!.reader(Charsets.UTF_8).use {it.readText()})
        return ConfigSnapshot.read(file, Logger.getAnonymousLogger())
    }

    @Test fun `reload candidates reject malformed YAML and invalid settings without touching a working snapshot`() {
        val active = read("market:\n  products:\n    OAK_LOG: {sell-price: 4}\n")
        for (invalid in listOf("core: [", "core: []", "core:\n  reward-messages: nonsense", "market:\n  products: []",
            "market:\n  products:\n    OAK_LOG: {sell-price: -1}", "core:\n  drops:\n    main: []")) {
            assertThrows(Exception::class.java) { read(invalid) }
            assertEquals(4L, active.market.products.getValue("OAK_LOG").sellPrice)
            assertFalse(active.plugin.drops.rewardMessages)
        }
    }
    @Test fun `custom tool statistics reach recipe previews and invalid GUI custom stacks reject the candidate`() {
        for(name in listOf("items.yml","combat.yml","progression.yml"))directory.resolve(name).toFile().writeText(javaClass.getResourceAsStream("/$name")!!.reader(Charsets.UTF_8).use {it.readText()})
        val items=directory.resolve("items.yml").toFile();val y=org.bukkit.configuration.file.YamlConfiguration().apply {load(items)}
        y.set("items.oneblock:pickaxe_1.stats.durability",.42);y.save(items)
        val active=read("release:\n  enabled: true\n")
        assertEquals(.42,active.release!!.recipes.getValue("pickaxe_1").durability)
        val layout=com.alytanta.oneblock.menu.MenuConfiguration.root(directory.toFile()).resolve("release/layout.yml")
        val menu=org.bukkit.configuration.file.YamlConfiguration().apply {load(layout)}
        val section=menu.getConfigurationSection("menus")!!
        val cells=section.getKeys(false).flatMap {id->menu.getConfigurationSection("menus.$id.slots")!!.getKeys(false).map {slot->"menus.$id.slots.$slot"}}
        val cell=cells.first {menu.getString("$it.states.other.custom-item")=="oneblock:pickaxe_1"};menu.set("$cell.states.other.amount",2);menu.save(layout)
        assertThrows(IllegalArgumentException::class.java) {read("release:\n  enabled: true\n")}
        assertEquals(.42,active.release!!.recipes.getValue("pickaxe_1").durability)
    }
    @Test fun `startup snapshot accepts old menus without no island and keeps all configuration files unchanged`() {
        read("")
        val root=com.alytanta.oneblock.menu.MenuConfiguration.root(directory.toFile())
        val layout=root.resolve("main-menu/layout.yml")
        val l=org.bukkit.configuration.file.YamlConfiguration().apply {load(layout)}
        l.set("menus.no-island",null);l.set("menus.main-menu.title","Existing server menu");l.save(layout)
        val commands=root.resolve("main-menu/commands.yml")
        val c=org.bukkit.configuration.file.YamlConfiguration().apply {load(commands)}
        c.set("commands.no-island",null);c.set("commands.profiles.requires-open",emptyList<String>());c.save(commands)
        val beforeLayout=layout.readBytes().toList();val beforeCommands=commands.readBytes().toList()
        val configFile=directory.resolve("config.yml").toFile()
        val snapshot=ConfigSnapshot.read(configFile,Logger.getAnonymousLogger())
        assertEquals("Existing server menu",snapshot.mainMenus!!.menus.getValue("main-menu").title)
        assertEquals(3,snapshot.mainMenus.menus.getValue("no-island").rows)
        assertTrue(snapshot.inputsUnchanged(configFile))
        assertEquals(beforeLayout,layout.readBytes().toList());assertEquals(beforeCommands,commands.readBytes().toList())
    }

    @Test fun `edited catalogs replace the old catalog and do not regain bundled products`() {
        val first = read("market:\n  products:\n    OAK_LOG: {sell-price: 4}\n    DIAMOND: {sell-price: 100}")
        val second = read("market:\n  products:\n    DIAMOND: {sell-price: 8, shop: false, bazaar: true}")
        assertEquals(2, first.market.products.size)
        assertEquals(setOf("DIAMOND"), second.market.products.keys)
        assertFalse(second.market.products.getValue("DIAMOND").shop)
        assertTrue(read("market:\n  products: {}\n").market.products.isEmpty())
    }
    @Test fun `interface config participates in atomic reload input checks`() {
        val active = read("")
        val file = directory.resolve("interface-menus.yml").toFile()
        file.writeText("version: 1\nwindows: {profile-delete: {title: Changed}}")
        assertFalse(active.inputsUnchanged(directory.resolve("config.yml").toFile()))
        val next = read("")
        assertEquals("Changed", next.interfaces.window("profile-delete", null).title)
        assertTrue(next.inputsUnchanged(directory.resolve("config.yml").toFile()))
        file.writeText("version: 1\nwindows: {profile-delete: {rows: 1}}")
        assertThrows(IllegalArgumentException::class.java) { read("") }
        assertEquals("Changed", next.interfaces.window("profile-delete", null).title)
    }

    @Test fun `candidate loads hub economy timing and custom core amounts together`() {
        val settings = read("""
            hub:
              world: custom_hub
            economy:
              worker-hourly-coins: 12
            storage:
              flush-interval-seconds: 4
            core:
              starter-breaks: 12
              drops:
                main:
                  - {block: DIAMOND_ORE, reward: DIAMOND, weight: 1, amount: 3}
            market:
              input-timeout-seconds: 25
              products:
                DIAMOND: {sell-price: 8, shop: false}
        """.trimIndent())
        assertEquals("custom_hub", settings.plugin.hub.world)
        assertEquals(0L, settings.plugin.economy.workerHourlyCoins)
        assertEquals(4L, settings.plugin.flushIntervalSeconds)
        assertEquals(12L, settings.plugin.drops.starterBreaks)
        assertEquals(3, settings.plugin.drops.main.entries.single().maxAmount)
        assertEquals(25L, settings.market.inputSeconds)
    }
    @Test fun `dedicated auction file replaces legacy rules and requires explicit allowlist`() {
        directory.resolve("auction.yml").toFile().writeText("auction:\n  allowed-items: [DIAMOND_SWORD]\n  listing-fee-bps: 250\n")
        val snapshot = read("market:\n  auction:\n    listing-fee-bps: 999\n")
        assertEquals(250, snapshot.market.auction.listingFeeBps)
        assertEquals(setOf("DIAMOND_SWORD"), snapshot.market.auction.allowedItems)
        assertTrue(snapshot.inputsUnchanged(directory.resolve("config.yml").toFile()))
        directory.resolve("auction.yml").toFile().writeText("auction:\n  allowed-items: []\n")
        assertFalse(snapshot.inputsUnchanged(directory.resolve("config.yml").toFile()))
        assertEquals(emptySet<String>(), ConfigSnapshot.read(directory.resolve("config.yml").toFile(), Logger.getAnonymousLogger()).market.auction.allowedItems)
        directory.resolve("auction.yml").toFile().writeText("auction:\n  allowed-items: [not_a_material]\n")
        assertThrows(Exception::class.java) { read("market: {}") }
    }
    @Test fun `creating auction file migrates admin rules once and preserves later edits`() {
        read("market:\n  auction:\n    listing-fee-bps: 77\n    duration-hours: [2, 8]\n")
        val plugin = com.alytanta.oneblock.menu.MenuFixture.stub(org.bukkit.plugin.Plugin::class.java) { name, args -> when(name) {
            "getDataFolder" -> directory.toFile()
            "getResource" -> javaClass.getResourceAsStream("/${args!![0]}")
            else -> null
        } }
        AuctionConfig.ensure(plugin)
        val file = directory.resolve("auction.yml").toFile()
        val snapshot = ConfigSnapshot.read(directory.resolve("config.yml").toFile(), Logger.getAnonymousLogger())
        assertEquals(77, snapshot.market.auction.listingFeeBps); assertEquals(listOf(2L, 8L), snapshot.market.auction.durations)
        assertTrue(snapshot.market.auction.allowedItems!!.contains("DIAMOND_SWORD"))
        file.writeText("auction:\n  allowed-items: []\n")
        AuctionConfig.ensure(plugin); assertEquals("auction:\n  allowed-items: []\n", file.readText())
    }
}
