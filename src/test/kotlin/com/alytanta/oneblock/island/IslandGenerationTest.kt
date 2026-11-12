package com.alytanta.oneblock.island

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class IslandGenerationTest {
    @Test fun `void preset contains no platform features structures or decorations`() {
        val preset = IslandGeneration.voidPreset()
        assertEquals("minecraft:plains", preset.biome().asString())
        assertFalse(preset.features())
        assertFalse(preset.decoration())
        assertFalse(preset.lakes())
        assertTrue(preset.structures().isEmpty())
        assertTrue(preset.layers().all { it.block().asString() == "minecraft:air" })
    }
}
