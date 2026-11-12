package com.alytanta.oneblock.island

import org.bukkit.Material
import com.alytanta.oneblock.config.EconomySettings

data class MineReward(
    val material: Material,
    val coins: Long,
    val tierUp: Boolean,
    val mutationChanged: Boolean,
    val amount: Int = 1,
    val notifyReward: Boolean = false,
)

class IslandProgressionService(private val islands: IslandProgressStore, @Volatile var economy: EconomySettings = EconomySettings()) {
    fun mine(owner: java.util.UUID, material: Material): MineReward? {
        return mine(owner) { material }
    }

    /** Selection and counter update share the island transaction (also used by co-op and admin edits). */
    fun mine(owner: java.util.UUID, selectMaterial: (Island) -> Material): MineReward? =
        mineBlock(owner) { island -> selectMaterial(island) to island.core.currentDrop }

    fun mineBlock(owner: java.util.UUID, credit: (Island, Material) -> Island = { island, _ -> island }, available: (Island) -> Boolean = { true }, select: (Island) -> Pair<Material, com.alytanta.oneblock.oneblock.CoreDrop?>): MineReward? {
        var reward: MineReward? = null
        islands.update(owner) { island ->
            if(!available(island))return@update island
            val (material, nextDrop) = select(island)
            // Retired tier/quests/workers/season fields remain inert for legacy backups.
            val updated = island.copy(core = island.core.copy(
                breaks = island.core.breaks + if (island.core.breaks < Long.MAX_VALUE) 1 else 0,
                currentDrop = nextDrop))
            reward = MineReward(material, 0, tierUp = false, mutationChanged = false)
            credit(updated, material)
        }
        return reward
    }

    fun purchase(owner: java.util.UUID, catalog: Map<String, Pair<Int, Long>>, key: String, skill: Boolean): PurchaseResult {
        if (skill) return PurchaseResult.MISSING
        var result = PurchaseResult.MISSING
        islands.update(owner) { island ->
            val (maxLevel, baseCost) = catalog[key] ?: return@update island
            val levels = (if (skill) island.skills else island.upgrades).toMutableMap()
            val current = levels[key] ?: 0
            when {
                current >= maxLevel -> {
                    result = PurchaseResult.MAXED
                    island
                }
                island.coins < baseCost * (current + 1) -> {
                    result = PurchaseResult.POOR
                    island
                }
                else -> {
                    levels[key] = current + 1
                    result = PurchaseResult.BOUGHT
                    if (skill) island.copy(coins = island.coins - baseCost * (current + 1), skills = levels)
                    else island.copy(coins = island.coins - baseCost * (current + 1), upgrades = levels)
                }
            }
        }
        return result
    }

    fun spend(owner: java.util.UUID, amount: Long): Boolean {
        var paid = false
        islands.update(owner) { island ->
            if (amount < 0 || island.coins < amount) island else {
                paid = true
                island.copy(coins = island.coins - amount)
            }
        }
        return paid
    }

    enum class PurchaseResult { BOUGHT, POOR, MAXED, MISSING }
}
