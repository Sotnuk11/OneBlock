package com.alytanta.oneblock.island

import org.bukkit.Material
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class LegacyVoidPlatformTest {
    private fun original(x: Int, y: Int, z: Int): Material =
        if (y == -60 && x in -8..24 && z in -8..24) {
            if (x == 8 && z == 8) Material.COBBLESTONE else Material.STONE
        } else Material.AIR

    @Test fun `intact lower template is identified at its height`() {
        assertEquals(-60, LegacyVoidPlatform.find(-64, ::original))
    }

    @Test fun `empty world and arbitrary stone floor are never identified`() {
        assertNull(LegacyVoidPlatform.find(-64) { _, _, _ -> Material.AIR })
        assertNull(LegacyVoidPlatform.find(-64) { _, y, _ -> if (y == -60) Material.STONE else Material.AIR })
        assertNull(LegacyVoidPlatform.find(0, ::original))
    }

    @Test fun `missing or replaced stone refuses the whole platform`() {
        for (replacement in listOf(Material.AIR, Material.DIRT, Material.CHEST)) {
            assertNull(LegacyVoidPlatform.find(-64) { x, y, z ->
                if (x == 3 && y == -60 && z == 3) replacement else original(x, y, z)
            })
        }
    }

    @Test fun `builds touching above below and side prevent cleanup`() {
        for ((x, y, z) in listOf(Triple(0, -59, 0), Triple(0, -61, 0), Triple(-9, -60, 0))) {
            assertNull(LegacyVoidPlatform.find(-64) { bx, by, bz ->
                if (bx == x && by == y && bz == z) Material.OAK_PLANKS else original(bx, by, bz)
            })
        }
    }
}
