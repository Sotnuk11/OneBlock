package com.alytanta.oneblock.config

import org.bukkit.configuration.file.YamlConfiguration
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.io.InputStreamReader
import java.util.logging.Logger

class PluginConfigTest {
    @Test
    fun `bundled configuration sets island world defaults`() {
        val resource = requireNotNull(javaClass.getResourceAsStream("/config.yml"))
        val yaml = resource.use { YamlConfiguration.loadConfiguration(InputStreamReader(it, Charsets.UTF_8)) }
        val settings = PluginConfig.load(yaml, Logger.getLogger("PluginConfigTest"))

        assertEquals(100.0, settings.islandWorldSize)
        assertEquals(1, settings.islandSize)
        assertEquals(10, settings.idleWorldUnloadMinutes)
        assertEquals(100L, settings.drops.starterBreaks)
        org.junit.jupiter.api.Assertions.assertFalse(settings.visibleBorder)
        assertEquals(100L, settings.drops.starter.totalWeight)
        assertEquals(10_000L, settings.drops.main.totalWeight)
    }

    @Test
    fun `world border size is clamped to supported range`() {
        val yaml = YamlConfiguration().apply {
            set("islands.world-size", 1)
            set("islands.idle-unload-minutes", 0)
        }
        val settings = PluginConfig.load(yaml, Logger.getLogger("PluginConfigTest"))

        assertEquals(16.0, settings.islandWorldSize)
        assertEquals(1, settings.idleWorldUnloadMinutes)
    }
}
