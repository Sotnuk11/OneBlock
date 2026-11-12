package com.alytanta.oneblock.oneblock

import org.bukkit.Material
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.random.Random

class CoreModeTest {
    @Test
    fun `supports the configured island core modes`() {
        assertEquals(listOf("stone", "wood", "nature", "farming"), CoreMode.ids)
        CoreMode.entries.forEach { assertEquals(it, CoreMode.byId(it.id.uppercase())) }
        assertNull(CoreMode.byId("void"))
    }

    @Test
    fun `balanced random table covers expected resources with total weight 100 percent`() {
        assertEquals(CoreDropTable.TOTAL_WEIGHT, CoreDropTable.entries.sumOf(CoreDrop::weight))
        assertTrue(CoreDropTable.entries.all { it.weight > 0 && it.block != Material.AIR && it.reward != Material.AIR })
        assertTrue(CoreDropTable.entries.any { it.reward == Material.OAK_LOG })
        assertTrue(CoreDropTable.entries.any { it.reward == Material.OAK_SAPLING })
        assertTrue(CoreDropTable.entries.any { it.reward == Material.COAL })
        assertTrue(CoreDropTable.entries.any { it.reward == Material.RAW_IRON })
        assertTrue(CoreDropTable.entries.any { it.reward == Material.GRASS_BLOCK })
    }

    @Test
    fun `valuable resources have very low fixed odds and are never awarded as blocks`() {
        val weights = CoreDropTable.entries.associate { it.reward to it.weight }

        assertEquals(8, weights[Material.DIAMOND])
        assertEquals(1, weights[Material.EMERALD])
        assertEquals(1, weights[Material.ANCIENT_DEBRIS])
        assertFalse(CoreDropTable.entries.any {
            it.reward == Material.DIAMOND_BLOCK || it.reward == Material.NETHERITE_BLOCK
        })
    }

    @Test
    fun `random drops always come from the same global pool`() {
        val validDrops = CoreDropTable.entries.toSet()
        repeat(1_000) {
            assertNotNull(CoreDropTable.randomDrop(Random(it)))
            assertTrue(CoreDropTable.randomDrop(Random(it)) in validDrops)
        }
    }
}
