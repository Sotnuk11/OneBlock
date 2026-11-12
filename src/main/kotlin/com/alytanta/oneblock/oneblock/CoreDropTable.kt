package com.alytanta.oneblock.oneblock

import org.bukkit.Material
import kotlin.random.Random

data class CoreDrop(
    val block: Material,
    val reward: Material,
    val weight: Int,
    val minAmount: Int = 1,
    val maxAmount: Int = minAmount,
)

object CoreDropTable {
    const val TOTAL_WEIGHT = 10_000

    val entries: List<CoreDrop> = listOf(
        CoreDrop(Material.OAK_LOG, Material.OAK_LOG, 1_200),
        CoreDrop(Material.SPRUCE_LOG, Material.SPRUCE_LOG, 500),
        CoreDrop(Material.BIRCH_LOG, Material.BIRCH_LOG, 500),
        CoreDrop(Material.OAK_LEAVES, Material.OAK_SAPLING, 900),
        CoreDrop(Material.GRASS_BLOCK, Material.GRASS_BLOCK, 700),
        CoreDrop(Material.DIRT, Material.DIRT, 800),
        CoreDrop(Material.STONE, Material.STONE, 1_000),
        CoreDrop(Material.COBBLESTONE, Material.COBBLESTONE, 900),
        CoreDrop(Material.COAL_ORE, Material.COAL, 1_000),
        CoreDrop(Material.IRON_ORE, Material.RAW_IRON, 700),
        CoreDrop(Material.COPPER_ORE, Material.RAW_COPPER, 500),
        CoreDrop(Material.OAK_PLANKS, Material.OAK_PLANKS, 400),
        CoreDrop(Material.OAK_LEAVES, Material.OAK_LEAVES, 250),
        CoreDrop(Material.OAK_LEAVES, Material.APPLE, 150),
        CoreDrop(Material.OAK_LEAVES, Material.STICK, 150),
        CoreDrop(Material.SAND, Material.SAND, 120),
        CoreDrop(Material.GRAVEL, Material.GRAVEL, 100),
        CoreDrop(Material.REDSTONE_ORE, Material.REDSTONE, 70),
        CoreDrop(Material.LAPIS_ORE, Material.LAPIS_LAZULI, 30),
        CoreDrop(Material.GOLD_ORE, Material.RAW_GOLD, 20),
        CoreDrop(Material.DIAMOND_ORE, Material.DIAMOND, 8),
        CoreDrop(Material.EMERALD_ORE, Material.EMERALD, 1),
        CoreDrop(Material.ANCIENT_DEBRIS, Material.ANCIENT_DEBRIS, 1),
    ).also { drops ->
        require(drops.all { it.block != Material.AIR && it.reward != Material.AIR && it.weight > 0 }) {
            "OneBlock drop table contains an invalid entry"
        }
        require(drops.sumOf(CoreDrop::weight) == TOTAL_WEIGHT) {
            "OneBlock drop weights must sum to $TOTAL_WEIGHT"
        }
    }

    fun randomDrop(random: Random = Random.Default): CoreDrop {
        var roll = random.nextInt(TOTAL_WEIGHT)
        for (drop in entries) {
            roll -= drop.weight
            if (roll < 0) return drop
        }
        error("OneBlock drop table is invalid")
    }
}
