package com.alytanta.oneblock.admin

import com.alytanta.oneblock.config.PluginConfig
import com.alytanta.oneblock.island.Island
import com.alytanta.oneblock.oneblock.CoreMode
import com.alytanta.oneblock.oneblock.CoreState
import org.bukkit.Material

object AdminEdits {
    val fields = listOf("breaks", "mode", "coins", "world-size", "upgrade.fortune",
        "upgrade.island_size", "name-number")

    fun set(island: Island, field: String, value: String, settings: PluginConfig): Island {
        fun number(max: Long = 1_000_000_000_000L): Long = requireNotNull(value.toLongOrNull()?.takeIf { it in 0..max })
        fun int(max: Int = Int.MAX_VALUE) = number(max.toLong()).toInt()
        val key = field.lowercase()
        return when (key) {
            "breaks" -> island.copy(core = island.core.copy(breaks = number(Long.MAX_VALUE)))
            "mode" -> island.copy(core = island.core.copy(mode = requireNotNull(CoreMode.byId(value)), currentDrop = null))
            "name-number" -> island.copy(nameNumber = int(46).also { require(it >= 1) }, profileName = null)
            "coins" -> island.copy(coins = number())
            "world-size" -> island.copy(worldSize = if (value.equals("default", true)) null else
                requireNotNull(value.toDoubleOrNull()?.takeIf { it.isFinite() && it in 16.0..59_999_968.0 }))
            else -> {
                val name = key.substringAfter('.', "")
                require(name.isNotBlank())
                when (key.substringBefore('.')) {
                    "upgrade" -> island.copy(upgrades = island.upgrades + (name to int(requireNotNull(settings.upgrades[name]).first)))
                    else -> throw IllegalArgumentException("Unknown field")
                }
            }
        }
    }

    /** Clears game progress but deliberately retains buildings, ownership, members and the one-time kit flag. */
    fun resetProgress(island: Island, settings: PluginConfig): Island = Island(
        owner = island.owner, worldName = island.worldName, x = island.x, y = island.y, z = island.z,
        core = CoreState(settings.startingMode, stability = settings.defaultStability), members = island.members,
        platformInitialized = island.platformInitialized, startingKitClaimed = island.startingKitClaimed, worldSize = island.worldSize,
        platformWorldId = island.platformWorldId, visitorSettings = island.visitorSettings,
        ownerPlayerId = island.ownerPlayerId, profileName = island.profileName, createdAt = island.createdAt,
        publicId = island.publicId, nameNumber = island.nameNumber,
    )
}
