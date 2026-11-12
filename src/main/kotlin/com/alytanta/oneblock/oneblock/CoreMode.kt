package com.alytanta.oneblock.oneblock

import org.bukkit.Material

enum class CoreMode(
    val id: String,
    val initialMaterial: Material,
) {
    STONE("stone", Material.STONE),
    WOOD("wood", Material.OAK_LOG),
    NATURE("nature", Material.GRASS_BLOCK),
    FARMING("farming", Material.HAY_BLOCK);

    companion object {
        val ids: List<String> = entries.map(CoreMode::id)

        fun byId(id: String): CoreMode? = entries.firstOrNull { it.id.equals(id, ignoreCase = true) }
    }
}
