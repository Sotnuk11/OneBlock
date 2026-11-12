package com.alytanta.oneblock.locale

import org.bukkit.configuration.file.YamlConfiguration
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.InputStreamReader

class LocaleResourcesTest {
    @Test fun `auction UI hints migrate exact old defaults while keeping personalised wording`() {
        for ((language, prior) in listOf(
            "en" to "&eDuration: &f{hours} hours\n&7Click for the next option.",
            "uk" to "&eТривалість: &f{hours} год\n&7Натисни для наступного варіанта."
        )) {
            val bundled = javaClass.getResourceAsStream("/lang/$language.yml")!!.reader(Charsets.UTF_8).use { YamlConfiguration.loadConfiguration(it) }
            val custom = YamlConfiguration().apply { set("auction.duration", prior); set("auction.rarity", "My custom tier label") }
            assertTrue(LocaleService.migrateLegacyMessages(language, custom, bundled))
            org.junit.jupiter.api.Assertions.assertEquals(bundled.getString("auction.duration"), custom.getString("auction.duration"))
            org.junit.jupiter.api.Assertions.assertEquals("My custom tier label", custom.getString("auction.rarity"))
            val table = LocaleService.messageTable(custom, bundled)
            for (key in listOf("auction.sort-most_bids", "auction.duration-menu", "auction.sort-menu", "auction.rarity-menu"))
                assertNotNull(table[key], "$language missing $key")
        }
    }
    @Test fun `legacy coin hints migrate while customized messages remain unchanged`() {
        val custom = YamlConfiguration().apply {
            set("progress.core-reward", "{prefix}&a+{item} &8| &6+{coins} coins")
            set("progress.worker-status", "my custom worker message")
        }
        val bundled = YamlConfiguration().apply { set("progress.core-reward", "sell resources"); set("progress.worker-status", "rate {rate}") }
        assertTrue(LocaleService.migrateLegacyMessages("en", custom, bundled))
        org.junit.jupiter.api.Assertions.assertEquals("sell resources", custom.getString("progress.core-reward"))
        org.junit.jupiter.api.Assertions.assertEquals("my custom worker message", custom.getString("progress.worker-status"))
    }
    @Test
    fun `old custom language files inherit new admin keys without replacing user text`() {
        val custom = YamlConfiguration().apply { set("prefix", "custom prefix"); set("admin.core-respawned", "custom response") }
        val bundled = YamlConfiguration().apply {
            set("prefix", "default prefix")
            set("admin.menu.title", "new menu")
            set("admin.core-respawned", "default response")
        }
        val messages = LocaleService.messageTable(custom, bundled)
        org.junit.jupiter.api.Assertions.assertEquals("custom prefix", messages["prefix"])
        org.junit.jupiter.api.Assertions.assertEquals("custom response", messages["admin.core-respawned"])
        org.junit.jupiter.api.Assertions.assertEquals("new menu", messages["admin.menu.title"])
    }

    @Test
    fun `all declared message keys exist in both bundled locales`() {
        for (language in LocaleService.SUPPORTED) {
            val resource = requireNotNull(javaClass.getResourceAsStream("/lang/$language.yml"))
            val yaml = resource.use { YamlConfiguration.loadConfiguration(InputStreamReader(it, Charsets.UTF_8)) }
            for (message in Lang.entries) {
                assertNotNull(yaml.getString(message.key), "$language is missing ${message.key}")
            }
            assertTrue(yaml.getString("prefix").orEmpty().isNotBlank())
        }
    }
}
