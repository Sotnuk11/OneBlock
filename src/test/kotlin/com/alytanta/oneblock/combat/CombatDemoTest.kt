package com.alytanta.oneblock.combat

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.bukkit.configuration.file.YamlConfiguration

class CombatDemoTest {
    @Test fun `locked shape hits only in front within width and height`() {
        val shape=DemoCleave(0.0,60.0,0.0,0.0,1.0,4.5,1.6)
        assertTrue(shape.contains(1.0,60.0,4.0));assertFalse(shape.contains(2.0,60.0,3.0))
        assertFalse(shape.contains(0.0,60.0,-1.0));assertFalse(shape.contains(0.0,64.0,3.0))
        assertFalse(shape.contains(0.0,60.0,5.0))
    }
    @Test fun `demo refuses unsafe numbers and preserves compatible defaults`() {
        assertEquals(28,DemoSettings.read(null).warning)
        for((key,value) in listOf("health" to Double.NaN,"speed" to 2.0,"warning-ticks" to 1.0,"recovery-ticks" to 1.5,"vulnerability" to 5.0)) {
            val y=YamlConfiguration();val s=y.createSection("demo");s.set(key,value)
            assertThrows(IllegalArgumentException::class.java) {DemoSettings.read(s)}
        }
    }
}
