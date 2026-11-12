package com.alytanta.oneblock.harvest

import org.bukkit.Material.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class HarvestSettingsTest {
    private val rules = HarvestSettings(setOf(IRON_ORE,COAL_ORE,DIAMOND_ORE,STONE,WHEAT),setOf(WHEAT))
    @Test fun `ores require the actual mineral and use ore keys with fortune quantity`() {
        assertEquals(mapOf("iron_ore" to 3L),rules.credit(IRON_ORE,listOf(RAW_IRON to 3),false))
        assertEquals(mapOf("coal_ore" to 2L),rules.credit(COAL_ORE,listOf(COAL to 2),false))
        assertEquals(mapOf("diamond_ore" to 4L),rules.credit(DIAMOND_ORE,listOf(DIAMOND to 4),false))
        assertTrue(rules.credit(IRON_ORE,listOf(IRON_ORE to 1),false).isEmpty())
        assertTrue(rules.credit(IRON_ORE,emptyList(),false).isEmpty())
        assertTrue(HarvestSettings(setOf(RAW_IRON)).credit(IRON_ORE,listOf(RAW_IRON to 1),false).isEmpty())
    }
    @Test fun `silk excludes all non plants and placed blocks cannot be farmed`() {
        assertTrue(rules.credit(STONE,listOf(STONE to 1),true).isEmpty())
        assertTrue(rules.credit(STONE,listOf(COBBLESTONE to 1),false,true).isEmpty())
        assertTrue(rules.credit(IRON_ORE,listOf(RAW_IRON to 1),true).isEmpty())
        assertEquals(mapOf("wheat" to 1L),rules.credit(WHEAT,listOf(WHEAT to 1,WHEAT_SEEDS to 1),true,true))
    }
    @Test fun `bundled allowlist uses known materials and plant exceptions`() {
        val file=kotlin.io.path.createTempFile("harvest-", ".yml").toFile()
        try {
            file.writeText(javaClass.getResourceAsStream("/harvest.yml")!!.reader(Charsets.UTF_8).use { it.readText() })
            val config=HarvestSettings.read(file).first
            assertTrue(IRON_ORE in config.materials)
            assertTrue(RAW_IRON !in config.materials)
            assertTrue(OAK_SAPLING in config.plants)
        } finally { file.delete() }
    }
    @Test fun `only explicit block or item allowlist is counted`() {
        assertTrue(rules.credit(OAK_LOG,listOf(OAK_LOG to 1),false).isEmpty())
        assertEquals(mapOf("stone" to 1L),rules.credit(STONE,listOf(COBBLESTONE to 1),false))
        assertEquals(mapOf("wheat" to 1L),HarvestSettings(setOf(WHEAT)).credit(HAY_BLOCK,listOf(WHEAT to 1),false))
    }
}
