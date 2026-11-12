package com.alytanta.oneblock.release

import com.alytanta.oneblock.island.Island
import org.bukkit.configuration.file.YamlConfiguration
import java.io.File
import java.time.LocalDate

/** Coins per streak day. A day needs fresh play (core breaks, an order or an arena run), so idle alt profiles cannot collect it. */
data class DailyRules(val enabled: Boolean = true, val requiredBreaks: Long = 50, val coins: List<Long> = listOf(100, 150, 200, 250, 300, 400, 600)) {
    companion object {
        fun read(file: File): DailyRules {
            val defaults = DailyRules()
            if (!file.exists()) return defaults
            val y = YamlConfiguration.loadConfiguration(file)
            val coins = y.getLongList("daily.coins").ifEmpty { defaults.coins }
            require(coins.all { it > 0 }) { "daily.coins must be positive" }
            val breaks = y.getLong("daily.required-breaks", defaults.requiredBreaks)
            require(breaks >= 0) { "daily.required-breaks must not be negative" }
            return DailyRules(y.getBoolean("daily.enabled", true), breaks, coins)
        }
    }
}

data class DailyStatus(val claimedToday: Boolean, val progress: Long, val needed: Long, val streak: Int, val coins: Long, val otherPlay: Boolean = false) {
    val ready get() = !claimedToday && (progress >= needed || otherPlay)
}

object DailyReward {
    fun today(): Long = LocalDate.now().toEpochDay()
    /** Missing a day restarts the series; after the last day it starts over. */
    private fun nextStreak(p: ReleaseProgress, rules: DailyRules, day: Long) =
        if (p.dailyDay == day - 1 && p.dailyStreak in 1 until rules.coins.size) p.dailyStreak + 1 else 1
    fun status(p: ReleaseProgress, rules: DailyRules, day: Long): DailyStatus {
        val claimed = p.dailyDay == day
        val streak = if (claimed) p.dailyStreak else nextStreak(p, rules, day)
        return DailyStatus(claimed, (p.eligibleCoreBreaks - p.dailyBreaks).coerceAtLeast(0), rules.requiredBreaks, streak, rules.coins[streak - 1],
            p.activity.completed > p.dailyOrders || p.arenaRuns > p.dailyArena)
    }
    /** Coins and the claimed day change in one island update, so a repeated click cannot pay twice. */
    fun claim(island: Island, rules: DailyRules, day: Long): Island {
        val p = requireNotNull(island.releaseProgress) { "legacy" }
        val s = status(p, rules, day)
        require(rules.enabled) { "daily-off" }
        require(!s.claimedToday) { "daily-repeat" }
        require(s.ready) { "daily-wait" }
        return island.copy(coins = Math.addExact(island.coins, s.coins),
            releaseProgress = p.copy(dailyDay = day, dailyStreak = s.streak, dailyBreaks = p.eligibleCoreBreaks, dailyOrders = p.activity.completed, dailyArena = p.arenaRuns))
    }
}
