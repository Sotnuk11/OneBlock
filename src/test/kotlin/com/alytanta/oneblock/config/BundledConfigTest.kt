package com.alytanta.oneblock.config

import com.alytanta.oneblock.util.StrictYaml
import org.bukkit.configuration.file.YamlConfiguration
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import com.alytanta.oneblock.hub.generator.HubGeneratorSettings

class BundledConfigTest {
    @TempDir lateinit var folder: Path

    @Test fun `hub settings parse bundled defaults even when an external override exists`() {
        val missing = folder.resolve("missing.yml").toFile()
        val defaults = HubGeneratorSettings.read(missing).first
        assertTrue(defaults.message("command-help").contains("/obadmin hub"))

        val override = folder.resolve("hub-generator.yml").toFile().apply {
            writeText("version: 1\nlanguage: en\n")
        }
        val customized = HubGeneratorSettings.read(override).first
        assertEquals("en", customized.language)
        assertTrue(customized.message("command-help").contains("Hub generator"))
        assertEquals(defaults.palette, customized.palette)
    }

    @Test fun `bundled config loads strictly on a fresh installation without duplicate sections`() {
        val source = requireNotNull(javaClass.getResourceAsStream("/config.yml"))
            .reader(Charsets.UTF_8).use { it.readText() }
        StrictYaml.validate(source)
        val yaml = YamlConfiguration().apply { loadFromString(source) }
        assertTrue(yaml.isConfigurationSection("release"))
        assertTrue(yaml.isBoolean("release.enabled"))
        assertTrue(yaml.getBoolean("release.enabled"))
        assertEquals(1, Regex("(?m)^release:").findAll(source).count())
        assertThrows(Exception::class.java) { StrictYaml.validate(source + "\nrelease:\n  enabled: false\n") }
    }
}
