package com.alytanta.oneblock.market

import org.bukkit.Material
import org.bukkit.configuration.ConfigurationSection

data class MarketUiSettings(val hoverOrders: Int = 6, val buySpreadBps: Int = 500, val sellSpreadBps: Int = 1000,
    val priceLines: List<String> = listOf("{value}", "^^^^^^^^^^^^^^^", "{label}", ""),
    val amountLines: List<String> = listOf("{value}", "^^^^^^^^^^^^^^^", "{label}", ""), val inputLine: Int = 0) {
    companion object {
        fun load(config: ConfigurationSection): MarketUiSettings {
            fun number(path: String, default: Int, range: IntRange): Int {
                val value = config.get(path)?.toString()?.toIntOrNull()
                    ?: if (config.contains(path)) error("$path must be an integer") else default
                require(value in range) { "$path is outside supported limits" }
                return value
            }
            val line = number("market.sign.input-line", 0, 0..3)
            fun lines(path: String): List<String> {
                val defaults = List(4) { index -> when (index) { line -> "{value}"; (line + 1) % 4 -> "^^^^^^^^^^^^^^^"; (line + 2) % 4 -> "{label}"; else -> "" } }
                if (!config.contains(path)) return defaults
                val raw = config.getList(path) ?: error("$path must be a list")
                require(raw.size == 4 && raw.all { it is String && it.length <= 80 && '\n' !in it && '\r' !in it }) { "$path needs exactly four short text lines" }
                val result = raw.map { it as String }
                require(result[line] == "{value}") { "$path input-line must contain only {value}" }
                return result
            }
            return MarketUiSettings(number("market.hover-orders-per-side", 6, 1..20),
                number("market.buy-spread-bps", 500, 1..10000), number("market.sell-spread-bps", 1000, 1..10000),
                lines("market.sign.price-lines"), lines("market.sign.amount-lines"), line)
        }
    }
}

enum class MarketCategory(val icon: Material) {
    FARMING(Material.WHEAT), MINING(Material.IRON_PICKAXE), COMBAT(Material.IRON_SWORD), WOODS(Material.OAK_LOG), FISHING(Material.COD), OTHER(Material.CHEST);

    companion object {
        fun infer(material: Material): MarketCategory = when {
            listOf("LOG", "PLANK", "LEAVES", "SAPLING").any { it in material.name } || material == Material.STICK -> WOODS
            material.name in setOf("HAY_BLOCK", "WHEAT", "WHEAT_SEEDS", "APPLE", "CARROT", "POTATO", "POISONOUS_POTATO", "SUGAR_CANE", "SUGAR", "PUMPKIN", "PUMPKIN_SEEDS", "MELON", "MELON_SLICE", "MELON_SEEDS", "CACTUS", "COCOA_BEANS", "BEETROOT", "BEETROOT_SEEDS", "NETHER_WART", "BROWN_MUSHROOM", "RED_MUSHROOM", "LEATHER", "BEEF", "PORKCHOP", "CHICKEN", "FEATHER", "MUTTON", "RABBIT", "RABBIT_HIDE", "RABBIT_FOOT", "EGG") -> FARMING
            material.name in setOf("COD", "SALMON", "PUFFERFISH", "TROPICAL_FISH", "PRISMARINE_SHARD", "PRISMARINE_CRYSTALS") -> FISHING
            material.name in setOf("ROTTEN_FLESH", "BONE", "STRING", "SPIDER_EYE", "GUNPOWDER", "ENDER_PEARL", "BLAZE_ROD", "SLIME_BALL") -> COMBAT
            listOf("ORE", "INGOT", "RAW_", "STONE", "DIRT", "GRASS", "SAND", "GRAVEL", "COAL", "DIAMOND", "EMERALD", "DEBRIS", "REDSTONE", "LAPIS").any { it in material.name } -> MINING
            else -> OTHER
        }
    }
}
