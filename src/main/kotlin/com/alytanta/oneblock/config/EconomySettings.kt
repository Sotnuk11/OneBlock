package com.alytanta.oneblock.config

import org.bukkit.configuration.ConfigurationSection

data class EconomySettings(val dailyCoins: Long = 0, val weeklyCoins: Long = 0, val workerHourlyCoins: Long = 0,
    val coreAdvanceCost: Long = 250, val infusionCost: Long = 500, val workerHireCost: Long = 100,
    val swordCost: Long = 150, val pickaxeCost: Long = 200,
    val dailyGoal: Long = 100, val weeklyGoal: Long = 500, val masteryPerTier: Int = 100,
    val maxWorkers: Int = 5, val maxInfusion: Int = 10) {
    companion object {
        // Legacy shape remains readable; all retired income/progression settings are ignored.
        fun load(config: ConfigurationSection): EconomySettings = EconomySettings()
    }
}
