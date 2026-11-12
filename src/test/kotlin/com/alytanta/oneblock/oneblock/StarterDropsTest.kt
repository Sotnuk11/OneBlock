package com.alytanta.oneblock.oneblock

import com.alytanta.oneblock.island.Island
import com.alytanta.oneblock.island.IslandProgressStore
import com.alytanta.oneblock.island.IslandProgressionService
import org.bukkit.Material
import org.bukkit.configuration.file.YamlConfiguration
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.random.Random

class StarterDropsTest {
    @Test fun `exactly first 100 successful shared island breaks use starter rewards`() {
        val owner = UUID.randomUUID()
        var state = Island(owner, "island", 0, 100, 0, CoreState(CoreMode.WOOD))
        val store = object : IslandProgressStore {
            override fun update(owner: UUID, change: (Island) -> Island): Island? {
                if (owner != state.owner) return null
                return change(state).also { state = it }
            }
        }
        val starter = WeightedDropTable(listOf(CoreDrop(Material.OAK_LOG, Material.OAK_LOG, 1)))
        val main = WeightedDropTable(listOf(CoreDrop(Material.DIAMOND_ORE, Material.DIAMOND, 1)))
        val tables = DropSettings(100, starter, main)
        val progression = IslandProgressionService(store)
        repeat(100) {
            assertEquals(Material.OAK_LOG, progression.mine(owner) { tables.tableFor(it.core.breaks).randomDrop().reward }?.material)
        }
        assertEquals(100L, state.core.breaks)
        assertEquals(Material.DIAMOND, progression.mine(owner) { tables.tableFor(it.core.breaks).randomDrop().reward }?.material)
        assertEquals(101L, state.core.breaks)
        assertTrue(state.collections.isEmpty())
        assertTrue(state.collections.isEmpty())
        assertNull(progression.mine(UUID.randomUUID(), Material.COAL))
        assertEquals(101L, state.core.breaks)
        assertSame(main, tables.copy(starterBreaks = 0).tableFor(0))
    }

    @Test fun `weights are relative and can exceed integer total without overflow`() {
        val entries = listOf(CoreDrop(Material.OAK_LOG, Material.OAK_LOG, Int.MAX_VALUE), CoreDrop(Material.IRON_ORE, Material.RAW_IRON, Int.MAX_VALUE))
        val table = WeightedDropTable(entries)
        assertEquals(Int.MAX_VALUE.toLong() * 2, table.totalWeight)
        assertEquals(entries.toSet(), (0..100).map { table.randomDrop(Random(it)) }.toSet())
        assertThrows(IllegalArgumentException::class.java) { WeightedDropTable(emptyList()) }
        assertThrows(IllegalArgumentException::class.java) { WeightedDropTable(listOf(entries[0].copy(weight = 0))) }
    }

    @Test fun `empty malformed and unknown material configurations fail explicitly`() {
        for (entry in listOf("[]", "[42]", "[{block: NOT_REAL, reward: COAL, weight: 1}]", "[{block: OAK_LOG, reward: COAL, weight: -1}]")) {
            val yaml = YamlConfiguration().apply { loadFromString("core:\n  drops:\n    starter: $entry\n") }
            assertThrows(RuntimeException::class.java) { DropSettings.load(yaml) }
        }
    }

    @Test fun `custom amounts and exact ranges reload without hardcoded reward quantities`() {
        val yaml = YamlConfiguration().apply { loadFromString("""
            core:
              bonus-drops:
                enabled: false
              drops:
                main:
                  - {block: DIAMOND_ORE, reward: DIAMOND, weight: 1, min-amount: 2, max-amount: 5}
        """.trimIndent()) }
        val settings = DropSettings.load(yaml)
        val drop = settings.main.entries.single()
        val rich = CoreState(CoreMode.WOOD, mutation = CoreMutation.ANCIENT, infusion = 10)
        val amounts = (0..100).map { settings.amountFor(drop, rich, 10, Random(it)) }.toSet()
        assertEquals(setOf(2, 3, 4, 5), amounts)
        val bonuses = settings.copy(bonusEnabled = true, ancientChance = 1.0, infusionChancePerLevel = 1.0)
        assertEquals(2, bonuses.amountFor(drop.copy(minAmount = 2, maxAmount = 2), rich, 0, Random(1))) // Retired mutation/infusion remain inert.
        assertFalse(settings.rewardMessages)
    }

    @Test fun `invalid amounts starter counts and bonus chances are rejected`() {
        for (fields in listOf("amount: 0", "amount: 65", "amount: 1.5", "min-amount: 5, max-amount: 3", "max-amount: 10000000000")) {
            val yaml = YamlConfiguration().apply { loadFromString("core:\n  drops:\n    main: [{block: STONE, reward: COAL, weight: 1, $fields}]") }
            assertThrows(RuntimeException::class.java) { DropSettings.load(yaml) }
        }
        for ((path, value) in listOf("core.starter-breaks" to 1.5, "core.bonus-drops.fortune-chance-per-level" to "bad",
            "core.bonus-drops.fortune-chance-per-level" to -0.1, "core.bonus-drops.fortune-chance-per-level" to Double.NaN)) {
            assertThrows(RuntimeException::class.java) { DropSettings.load(YamlConfiguration().apply { set(path, value) }) }
        }
    }
}
