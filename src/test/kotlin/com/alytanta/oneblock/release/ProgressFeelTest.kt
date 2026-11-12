package com.alytanta.oneblock.release

import com.alytanta.oneblock.island.Island
import com.alytanta.oneblock.market.MarketState
import com.alytanta.oneblock.oneblock.CoreMode
import com.alytanta.oneblock.oneblock.CoreState
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.io.File
import java.util.UUID

class ProgressFeelTest {
    private val rules = ReleaseRules.read(File("src/main/resources/progression.yml"), true).first!!
    private val feel = CoreFeel()
    private fun island(p: ReleaseProgress) = UUID.randomUUID().let { Island(it, "w", 0, 64, 0, CoreState(CoreMode.WOOD), releaseProgress = p) }

    @Test fun `bar stays inside its cells and handles empty and over range values`() {
        assertEquals("&a&8▱▱▱▱▱▱▱▱▱▱ &f0%", ProgressBar.render(0, 100))
        assertEquals("&a▰▰▰&8▱▱▱▱▱▱▱ &f30%", ProgressBar.render(30, 100))
        assertEquals("&a▰▰▰▰▰▰▰▰▰▰&8 &f100%", ProgressBar.render(500, 100))
        assertEquals("&a▰▰▰▰▰▰▰▰▰▰&8 &f100%", ProgressBar.render(0, 0))
    }
    @Test fun `closed things name the exact missing requirement`() {
        val domain = ReleaseDomain(rules)
        fun reason(p: ReleaseProgress, action: String, value: String) = island(p).let { i -> runCatching { domain.plan(i, MarketState(), i.owner, action, value) }.exceptionOrNull()?.message }
        assertEquals("chapter", reason(ReleaseProgress(), "recipe", "pickaxe_2"))
        assertEquals("level", reason(ReleaseProgress(chapter = "developed"), "recipe", "pickaxe_2"))
        assertEquals("chapter", reason(ReleaseProgress(), "upgrade", "fortune"))
        assertEquals("workshop", domain.upgradeChapter("fortune", 0))
        assertEquals("depths", domain.upgradeChapter("fortune", 2))
        assertEquals("developed", domain.upgradeChapter("fortune", 4))
        assertEquals("living", domain.upgradeChapter("territory", 0))
    }
    @Test fun `no recipe needed for a chapter is hidden behind quest points`() {
        val hidden = rules.activities?.unlocks?.keys.orEmpty()
        assertTrue(rules.chapters.all { it.milestones.recipesAny.isEmpty() || (it.milestones.recipesAny - hidden).isNotEmpty() })
    }
    @Test fun `bundled rewards have no luck counter`() {
        assertEquals(0, org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(File("src/main/resources/dungeon.yml")).getInt("room-rewards.pity.after"))
    }
    @Test fun `chest opens once per threshold and repeat pays nothing`() {
        val i = island(ReleaseProgress(eligibleCoreBreaks = 500))
        val first = CoreChest.open(i, MarketState(), rules, feel)!!
        assertTrue(first.rewarded);assertFalse(first.big);assertEquals(1, first.number)
        assertEquals(feel.rolls.toLong(), first.change.state.claims.values.sumOf { it.amount })
        assertTrue(first.change.state.claims.values.all { it.source == "release:core-chest:1" && it.recipient == null })
        assertNull(CoreChest.open(first.change.island, first.change.state, rules, feel))
    }
    @Test fun `same chest number gives the same claims so a replay cannot duplicate`() {
        val i = island(ReleaseProgress(eligibleCoreBreaks = 500))
        val a = CoreChest.open(i, MarketState(), rules, feel)!!.change.state.claims
        val b = CoreChest.open(i, MarketState(claims = a), rules, feel)!!.change.state.claims
        assertEquals(a, b)
    }
    @Test fun `every fifth chest is big and old profiles only move the counter`() {
        val big = CoreChest.open(island(ReleaseProgress(eligibleCoreBreaks = 2500, coreChests = 4)), MarketState(), rules, feel)!!
        assertTrue(big.big);assertEquals(feel.bigRolls.toLong(), big.change.state.claims.values.sumOf { it.amount })
        val old = CoreChest.open(island(ReleaseProgress(eligibleCoreBreaks = 12000)), MarketState(), rules, feel)!!
        assertFalse(old.rewarded);assertEquals(24, old.change.island.releaseProgress!!.coreChests);assertTrue(old.change.state.claims.isEmpty())
    }
    @Test fun `charge comes only after the workshop chapter`() {
        val early = CoreChest.open(island(ReleaseProgress(eligibleCoreBreaks = 500)), MarketState(), rules, feel)!!
        assertEquals(0, early.change.island.releaseProgress!!.charge)
        val late = CoreChest.open(island(ReleaseProgress(chapter = "workshop", eligibleCoreBreaks = 500)), MarketState(), rules, feel)!!
        assertEquals(feel.charge, late.change.island.releaseProgress!!.charge)
    }
    @Test fun `bundled feel rules are read from progression`() {
        val read = CoreFeel.read(File("src/main/resources/progression.yml"))
        assertEquals(500, read.chestEvery);assertTrue(org.bukkit.Material.DIAMOND in read.rare)
    }
}
