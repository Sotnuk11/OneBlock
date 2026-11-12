package com.alytanta.oneblock.release

import com.alytanta.oneblock.island.Island
import com.alytanta.oneblock.market.MarketClaim
import org.bukkit.Material
import org.bukkit.configuration.file.YamlConfiguration
import java.io.File
import java.util.UUID
import kotlin.random.Random

object ProgressBar {
    /** Legacy-coded text bar, for example `&a▰▰▰&8▱▱▱▱▱▱▱ &f30%`. */
    fun render(current: Long, max: Long, cells: Int = 10): String {
        val part = if (max <= 0) 1.0 else (current.toDouble() / max).coerceIn(0.0, 1.0)
        val filled = (part * cells).toInt()
        return "&a" + "▰".repeat(filled) + "&8" + "▱".repeat(cells - filled) + " &f${(part * 100).toInt()}%"
    }
}

data class CoreFeel(val chestEvery: Long = 500, val bigEvery: Long = 5, val rolls: Int = 8, val bigRolls: Int = 24,
    val charge: Long = 100, val bigCharge: Long = 300,
    val rare: Set<Material> = setOf(Material.DIAMOND, Material.EMERALD, Material.ANCIENT_DEBRIS)) {
    companion object {
        fun read(file: File): CoreFeel {
            val d = CoreFeel()
            if (!file.exists()) return d
            val y = YamlConfiguration.loadConfiguration(file)
            val rare = y.getStringList("core-feel.rare").map { requireNotNull(Material.matchMaterial(it)) { "core-feel.rare: unknown $it" } }.toSet()
            return CoreFeel(y.getLong("core-feel.chest-every", d.chestEvery).coerceAtLeast(1), y.getLong("core-feel.big-every", d.bigEvery).coerceAtLeast(1),
                y.getInt("core-feel.rolls", d.rolls).coerceIn(1, 64), y.getInt("core-feel.big-rolls", d.bigRolls).coerceIn(1, 256),
                y.getLong("core-feel.charge", d.charge).coerceAtLeast(0), y.getLong("core-feel.big-charge", d.bigCharge).coerceAtLeast(0),
                rare.ifEmpty { d.rare })
        }
    }
}

object CoreChest {
    data class Opened(val change: ReleaseChange, val number: Long, val big: Boolean, val rewarded: Boolean)

    /**
     * Claims and the chest counter change in one update, and the claim IDs are derived from the chest number,
     * so a repeated call pays nothing. A gap of several chests (a profile older than this feature) only moves the counter.
     */
    fun open(island: Island, state: com.alytanta.oneblock.market.MarketState, rules: ReleaseRules, feel: CoreFeel): Opened? {
        val p = island.releaseProgress ?: return null
        val due = p.eligibleCoreBreaks / feel.chestEvery
        if (due <= p.coreChests) return null
        val marked = island.copy(releaseProgress = p.copy(coreChests = due))
        if (due - p.coreChests > 1) return Opened(ReleaseChange(marked, state), due, false, false)
        val big = due % feel.bigEvery == 0L
        val table = rules.table(p, rules.tables.getValue("start"))
        val random = Random("${island.owner}:$due".hashCode())
        val loot = linkedMapOf<Material, Long>()
        repeat(if (big) feel.bigRolls else feel.rolls) {
            var roll = random.nextLong(table.totalWeight)
            val drop = table.entries.first { roll -= it.weight; roll < 0 }
            loot.merge(drop.reward, 1L, Long::plus)
        }
        val claims = state.claims.toMutableMap()
        loot.forEach { (material, amount) ->
            val id = UUID.nameUUIDFromBytes("core-chest:${island.owner}:$due:$material".toByteArray(Charsets.UTF_8))
            if (id !in claims) claims[id] = MarketClaim(id, island.owner, material.name, amount, "release:core-chest:$due")
        }
        val charged = if (rules.atLeast(p.chapter, "workshop")) ReleaseDomain.saturate(p.charge, if (big) feel.bigCharge else feel.charge) else p.charge
        val next = island.copy(releaseProgress = p.copy(coreChests = due, charge = charged))
        return Opened(ReleaseChange(next, state.copy(claims = claims)), due, big, true)
    }
}
