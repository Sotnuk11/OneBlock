package com.alytanta.oneblock.island

import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.World
import org.bukkit.block.Block

/** Inspect only one column, on that column's region thread. Never clears player buildings. */
internal object IslandLanding {
    private val hazards = setOf(Material.MAGMA_BLOCK, Material.CACTUS, Material.CAMPFIRE, Material.SOUL_CAMPFIRE,
        Material.POWDER_SNOW, Material.SWEET_BERRY_BUSH, Material.WITHER_ROSE, Material.POINTED_DRIPSTONE)

    fun safeFloor(block: Block) = block.isSolid && block.type !in hazards

    fun find(world: World, x: Int, z: Int, firstFloor: Int, lastFloor: Int): Location? {
        val first = firstFloor.coerceAtLeast(world.minHeight)
        val last = lastFloor.coerceAtMost(world.maxHeight - 3)
        for (y in first..last) {
            if (safeFloor(world.getBlockAt(x, y, z)) && world.getBlockAt(x, y + 1, z).isEmpty && world.getBlockAt(x, y + 2, z).isEmpty) {
                return Location(world, x + 0.5, y + 1.0, z + 0.5)
            }
        }
        return null
    }
}
