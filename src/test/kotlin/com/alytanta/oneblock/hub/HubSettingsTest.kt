package com.alytanta.oneblock.hub

import org.bukkit.configuration.file.YamlConfiguration
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class HubSettingsTest {
    @Test fun `existing config without hub uses the backend world spawn`() {
        val settings = HubSettings.load(YamlConfiguration())
        assertEquals("world", settings.world)
        assertTrue(settings.useWorldSpawn)
    }

    @Test fun `exact hub position and facing survive YAML serialization`() {
        val yaml = YamlConfiguration().apply {
            set("hub.world", "oneblock_hub"); set("hub.use-world-spawn", false)
            set("hub.x", 12.5); set("hub.y", 80.0); set("hub.z", -24.5)
            set("hub.yaw", 120.0); set("hub.pitch", -20.0)
        }
        val restored = YamlConfiguration().apply { loadFromString(yaml.saveToString()) }
        assertEquals(HubSettings("oneblock_hub", false, 12.5, 80.0, -24.5, 120f, -20f), HubSettings.load(restored))
    }

    @Test fun `invalid coordinates and empty world are rejected`() {
        for ((path, value) in listOf("hub.world" to " ", "hub.x" to Double.NaN, "hub.y" to Double.POSITIVE_INFINITY,
            "hub.yaw" to Double.POSITIVE_INFINITY, "hub.pitch" to 91.0)) {
            val yaml = YamlConfiguration().apply { set(path, value) }
            assertThrows(IllegalArgumentException::class.java) { HubSettings.load(yaml) }
        }
    }
}
