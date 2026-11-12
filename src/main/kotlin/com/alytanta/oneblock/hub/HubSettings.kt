package com.alytanta.oneblock.hub

import org.bukkit.configuration.ConfigurationSection

data class HubSettings(
    val world: String,
    val useWorldSpawn: Boolean,
    val x: Double,
    val y: Double,
    val z: Double,
    val yaw: Float,
    val pitch: Float,
) {
    companion object {
        fun load(config: ConfigurationSection): HubSettings {
            val settings = HubSettings(
                config.getString("hub.world", "world")!!.trim(),
                config.getBoolean("hub.use-world-spawn", true),
                config.getDouble("hub.x", 0.5), config.getDouble("hub.y", 100.0), config.getDouble("hub.z", 0.5),
                config.getDouble("hub.yaw", 0.0).toFloat(), config.getDouble("hub.pitch", 0.0).toFloat(),
            )
            require(settings.world.isNotEmpty()) { "hub.world must not be empty" }
            require(!settings.world.startsWith("oneblock_dungeons:")) { "Temporary dungeon world cannot be the hub" }
            require(listOf(settings.x, settings.y, settings.z).all(Double::isFinite) &&
                settings.yaw.isFinite() && settings.pitch.isFinite() && settings.pitch in -90f..90f) { "Invalid hub coordinates" }
            return settings
        }
    }
}
