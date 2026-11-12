package com.alytanta.oneblock.harvest

import com.alytanta.oneblock.player.PlayerService
import org.bukkit.Material
import java.util.UUID

class HarvestService(private val players: PlayerService, @Volatile var settings: HarvestSettings) {
    fun credit(actor: UUID, block: Material, drops: List<Pair<Material, Int>>, silk: Boolean, placed: Boolean = false) {
        val amounts = settings.credit(block, drops, silk, placed)
        if (amounts.isEmpty()) return
        players.update(actor) { p ->
            val totals = p.harvested.orEmpty().toMutableMap()
            amounts.forEach { (key, value) ->
                val old = totals[key] ?: 0L
                totals[key] = if (Long.MAX_VALUE - old < value) Long.MAX_VALUE else old + value
            }
            p.copy(harvested = totals)
        }
    }
}
