package com.alytanta.oneblock.island

import org.bukkit.Material

/** Conservative recognizer for the unmodified 33x33 vanilla void spawn platform centred at (8,8). */
object LegacyVoidPlatform {
    const val MIN = -8
    const val MAX = 24
    const val CENTER = 8
    fun expected(x: Int, z: Int): Material = if (x == CENTER && z == CENTER) Material.COBBLESTONE else Material.STONE

    /** One layer only, with clear air above, below and around it. Partial/modified platforms are refused. */
    fun find(minHeight: Int, block: (Int, Int, Int) -> Material): Int? =
        (minHeight + 1..minHeight + 8).firstOrNull { y ->
            (MIN - 1..MAX + 1).all { x -> (MIN - 1..MAX + 1).all { z ->
                val inside = x in MIN..MAX && z in MIN..MAX
                val expected = if (inside) expected(x, z) else Material.AIR
                block(x, y, z) == expected && block(x, y - 1, z) == Material.AIR && block(x, y + 1, z) == Material.AIR
            } }
        }
}
