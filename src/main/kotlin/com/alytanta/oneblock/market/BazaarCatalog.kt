package com.alytanta.oneblock.market

import org.bukkit.Material
import org.bukkit.configuration.ConfigurationSection

data class BazaarCategory(val id: String, val name: String, val icon: Material, val color: Material,
    val enabled: Boolean = true, val order: Int = 0, val slot: Int? = null, val lore: List<String> = emptyList())
data class BazaarGroup(val id: String, val name: String, val icon: Material, val category: String? = null,
    val enabled: Boolean = true, val order: Int = 0, val slot: Int? = null, val lore: List<String> = emptyList())

/** IDs are configuration keys, not an enum: administrators can add categories without a new JAR. */
data class BazaarCatalog(val categories: List<BazaarCategory>, val groups: Map<String, BazaarGroup> = defaultGroups) {
    fun category(product: MarketProduct): String = if (product.categoryId == "fishing" && categories.none { it.id == "fishing" }) "woods" else product.categoryId
    fun group(product: MarketProduct): String = product.group ?: when {
        product.category == MarketCategory.WOODS -> "wood"
        product.material.name in setOf("COD", "SALMON", "PUFFERFISH", "TROPICAL_FISH") -> "fish"
        product.material.name in setOf("PRISMARINE_SHARD", "PRISMARINE_CRYSTALS", "PRISMARINE") -> "prismarine"
        product.material.name in setOf("WHEAT", "WHEAT_SEEDS", "HAY_BLOCK") -> "wheat"
        product.material.name in setOf("IRON_INGOT", "IRON_BLOCK", "RAW_IRON", "RAW_IRON_BLOCK") -> "iron"
        product.material.name in setOf("GOLD_INGOT", "GOLD_BLOCK", "RAW_GOLD", "RAW_GOLD_BLOCK") -> "gold"
        product.material.name in setOf("COPPER_INGOT", "COPPER_BLOCK", "RAW_COPPER", "RAW_COPPER_BLOCK") -> "copper"
        product.material.name in setOf("LAPIS_BLOCK", "LAPIS_LAZULI") -> "lapis_lazuli"
        product.material.name.endsWith("_BLOCK") -> product.material.name.removeSuffix("_BLOCK").lowercase()
        else -> product.material.name.lowercase()
    }
    companion object {
        val defaults = listOf(
            BazaarCategory("farming", "Farming", Material.WHEAT, Material.YELLOW_STAINED_GLASS_PANE),
            BazaarCategory("mining", "Mining", Material.IRON_PICKAXE, Material.LIGHT_GRAY_STAINED_GLASS_PANE),
            BazaarCategory("combat", "Combat", Material.IRON_SWORD, Material.RED_STAINED_GLASS_PANE),
            BazaarCategory("woods", "Woods & Fishes", Material.OAK_LOG, Material.ORANGE_STAINED_GLASS_PANE),
            BazaarCategory("other", "Oddities", Material.BREWING_STAND, Material.PURPLE_STAINED_GLASS_PANE))
        val defaultGroups = listOf(
            BazaarGroup("wood", "Wood", Material.OAK_LOG), BazaarGroup("fish", "Fish", Material.COD),
            BazaarGroup("wheat", "Wheat & Seeds", Material.WHEAT), BazaarGroup("iron", "Iron", Material.IRON_INGOT),
            BazaarGroup("gold", "Gold", Material.GOLD_INGOT), BazaarGroup("coal", "Coal", Material.COAL),
            BazaarGroup("copper", "Copper", Material.COPPER_INGOT), BazaarGroup("prismarine", "Prismarine", Material.PRISMARINE_SHARD),
            BazaarGroup("diamond", "Diamond", Material.DIAMOND), BazaarGroup("emerald", "Emerald", Material.EMERALD),
            BazaarGroup("redstone", "Redstone", Material.REDSTONE), BazaarGroup("lapis_lazuli", "Lapis", Material.LAPIS_LAZULI)
        ).associateBy { it.id }
        fun load(config: ConfigurationSection): BazaarCatalog {
            fun id(value: String): String = value.also { require(it.matches(Regex("[a-z0-9_-]{1,40}"))) { "Invalid Bazaar ID: $it" } }
            fun icon(path: String, fallback: Material): Material = if (!config.contains(path)) fallback else
                requireNotNull(Material.matchMaterial(config.getString(path).orEmpty())) { "Invalid $path" }
            val categories = if (!config.contains("market.categories")) defaults else {
                val section = requireNotNull(config.getConfigurationSection("market.categories")) { "market.categories must be a mapping" }
                section.getKeys(false).map { key ->
                    val path = "market.categories.$key"
                    require(config.isConfigurationSection(path))
                    val name = config.getString("$path.name", key)!!.also { require(it.isNotBlank() && it.length <= 80) }
                    BazaarCategory(id(key), name, icon("$path.icon", Material.CHEST), icon("$path.color", Material.GRAY_STAINED_GLASS_PANE),
                        config.getBoolean("$path.enabled", true), config.getInt("$path.order", 0),
                        if (config.contains("$path.slot")) config.getInt("$path.slot").also { require(it in 0..53) } else null,
                        config.getStringList("$path.lore"))
                }.sortedBy { it.order }.also { require(it.isNotEmpty() && it.size <= 50 && it.any { category -> category.enabled }) { "Bazaar needs 1..50 categories with at least one enabled" } }
            }
            require(!config.contains("market.groups") || config.isConfigurationSection("market.groups")) { "market.groups must be a mapping" }
            val groups = config.getConfigurationSection("market.groups")?.getKeys(false)?.associate { key ->
                val path = "market.groups.$key"
                require(config.isConfigurationSection(path)) { "$path must be a mapping" }
                    id(key) to BazaarGroup(key, config.getString("$path.name", key)!!.also { require(it.isNotBlank() && it.length <= 80) }, icon("$path.icon", Material.CHEST),
                        config.getString("$path.category")?.let(::id), config.getBoolean("$path.enabled", true), config.getInt("$path.order", 0),
                        if (config.contains("$path.slot")) config.getInt("$path.slot").also { require(it in 0..53) } else null,
                        config.getStringList("$path.lore"))
            } ?: defaultGroups
            return BazaarCatalog(categories, groups)
        }
    }
}
