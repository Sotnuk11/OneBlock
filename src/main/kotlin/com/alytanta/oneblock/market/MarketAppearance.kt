package com.alytanta.oneblock.market

import org.bukkit.Material
import org.bukkit.configuration.ConfigurationSection

/** Stable navigation layout; labels/lore live in locale YAML and button icons may be overridden. */
data class MarketAppearance(val filler: Material? = Material.BLACK_STAINED_GLASS_PANE,
    val icons: Map<String, Material> = emptyMap()) {
    companion object {
        fun load(config: ConfigurationSection): MarketAppearance {
            val name = config.getString("market.appearance.filler", "BLACK_STAINED_GLASS_PANE")!!
            val filler = if (name.equals("NONE", true)) null else requireNotNull(Material.matchMaterial(name))
            require(filler !in setOf(Material.AIR, Material.CAVE_AIR, Material.VOID_AIR))
            require(!config.contains("market.appearance.icons") || config.isConfigurationSection("market.appearance.icons"))
            val icons = config.getConfigurationSection("market.appearance.icons")?.getValues(true)?.filterValues { it is String }
                ?.mapValues { (_, value) -> requireNotNull(Material.matchMaterial(value.toString())).also {
                    require(it !in setOf(Material.AIR, Material.CAVE_AIR, Material.VOID_AIR))
                } }.orEmpty()
            return MarketAppearance(filler, icons)
        }
    }
}
