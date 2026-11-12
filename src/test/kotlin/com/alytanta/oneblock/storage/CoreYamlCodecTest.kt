package com.alytanta.oneblock.storage

import com.alytanta.oneblock.oneblock.CoreMode
import com.alytanta.oneblock.oneblock.CoreState
import org.bukkit.configuration.file.YamlConfiguration
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class CoreYamlCodecTest {
    @Test fun `break counter survives YAML serialization at the starter boundary`() {
        for (breaks in listOf(0L, 99L, 100L, 101L, Long.MAX_VALUE)) {
            val original = CoreState(CoreMode.WOOD, mastery = 3, breaks = breaks)
            val yaml = YamlConfiguration()
            CoreYamlCodec.write(yaml, original)
            val reloaded = YamlConfiguration().apply { loadFromString(yaml.saveToString()) }
            assertEquals(original, CoreYamlCodec.read(reloaded))
        }
    }

    @Test fun `legacy islands migrate using collections rather than spent mastery`() {
        val yaml = YamlConfiguration().apply {
            set("core.mastery", 5)
            set("progress.collections.oak_log", 120L)
            set("progress.collections.coal", 15L)
        }
        assertEquals(135L, CoreYamlCodec.read(yaml).breaks)
        yaml.set("core.breaks", 0)
        assertEquals(0L, CoreYamlCodec.read(yaml).breaks)
        assertEquals(Long.MAX_VALUE, CoreYamlCodec.legacyBreakCount(mapOf("a" to Long.MAX_VALUE, "b" to 2L)))
    }
}
