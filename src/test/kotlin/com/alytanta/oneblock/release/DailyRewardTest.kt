package com.alytanta.oneblock.release

import com.alytanta.oneblock.island.Island
import com.google.gson.Gson
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.util.UUID

class DailyRewardTest {
    private val rules = DailyRules(true, 50, listOf(100, 200, 300))
    private fun island(breaks: Long, coins: Long = 0) = UUID.randomUUID().let { id ->
        Island(owner = id, worldName = "w", x = 0, y = 64, z = 0, core = com.alytanta.oneblock.oneblock.CoreState(com.alytanta.oneblock.oneblock.CoreMode.WOOD), coins = coins, releaseProgress = ReleaseProgress(eligibleCoreBreaks = breaks))
    }
    private fun Island.mined(n: Long) = copy(releaseProgress = releaseProgress!!.let { it.copy(eligibleCoreBreaks = it.eligibleCoreBreaks + n) })

    @Test fun `needs fresh breaks and pays once a day`() {
        val first = island(49)
        assertEquals("daily-wait", assertThrows(IllegalArgumentException::class.java) { DailyReward.claim(first, rules, 10) }.message)
        val paid = DailyReward.claim(first.mined(1), rules, 10)
        assertEquals(100, paid.coins)
        assertEquals("daily-repeat", assertThrows(IllegalArgumentException::class.java) { DailyReward.claim(paid.mined(500), rules, 10) }.message)
    }
    @Test fun `breaks before a claim do not count towards the next day`() {
        val paid = DailyReward.claim(island(500), rules, 10)
        assertEquals(0, DailyReward.status(paid.releaseProgress!!, rules, 11).progress)
        assertThrows(IllegalArgumentException::class.java) { DailyReward.claim(paid, rules, 11) }
    }
    @Test fun `series grows wraps and restarts after a missed day`() {
        var i = DailyReward.claim(island(50), rules, 10)
        i = DailyReward.claim(i.mined(50), rules, 11);assertEquals(300, i.coins)
        i = DailyReward.claim(i.mined(50), rules, 12);assertEquals(600, i.coins);assertEquals(3, i.releaseProgress!!.dailyStreak)
        i = DailyReward.claim(i.mined(50), rules, 13);assertEquals(700, i.coins);assertEquals(1, i.releaseProgress!!.dailyStreak)
        i = DailyReward.claim(i.mined(50), rules, 20);assertEquals(800, i.coins);assertEquals(1, i.releaseProgress!!.dailyStreak)
    }
    @Test fun `an order or an arena run also counts and is spent by the claim`() {
        val start = DailyReward.claim(island(50), rules, 10)
        val p = start.releaseProgress!!
        val ordered = start.copy(releaseProgress = p.copy(activity = p.activity.copy(completed = p.activity.completed + 1)))
        val paid = DailyReward.claim(ordered, rules, 11);assertEquals(300, paid.coins)
        assertThrows(IllegalArgumentException::class.java) { DailyReward.claim(paid, rules, 12) }
        val ran = paid.copy(releaseProgress = paid.releaseProgress!!.copy(arenaRuns = 1))
        assertEquals(600, DailyReward.claim(ran, rules, 12).coins)
    }
    @Test fun `old saved progress without daily fields loads with defaults`() {
        val tree = Gson().toJsonTree(ReleaseProgress()).asJsonObject
        listOf("dailyDay", "dailyStreak", "dailyBreaks").forEach { tree.remove(it) }
        val loaded = Gson().fromJson(ReleaseStateCodec.normalize(tree), ReleaseProgress::class.java)
        assertEquals(0, loaded.dailyDay);assertEquals(0, loaded.dailyStreak)
    }
    @Test fun `bundled rules read from progression file`() {
        val parsed = DailyRules.read(java.io.File("src/main/resources/progression.yml"))
        assertTrue(parsed.enabled);assertEquals(7, parsed.coins.size);assertEquals(50, parsed.requiredBreaks)
    }
}
