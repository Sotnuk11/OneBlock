package com.alytanta.oneblock.release

import org.bukkit.entity.Player
import org.bukkit.event.*
import org.bukkit.event.inventory.CraftItemEvent
import org.bukkit.event.inventory.InventoryAction

class ReleaseEvents(private val runtime: ReleaseRuntime) : Listener {
    @EventHandler(priority=EventPriority.MONITOR,ignoreCancelled=true)
    fun craft(event: CraftItemEvent) {
        val player=event.whoClicked as? Player ?: return
        if(player.gameMode!=org.bukkit.GameMode.SURVIVAL || event.action==InventoryAction.NOTHING)return
        val type=event.recipe.result.type
        if(type in setOf(org.bukkit.Material.CRAFTING_TABLE,org.bukkit.Material.STONE_PICKAXE))runtime.fact(player,"craft:${type.name}")
    }
}
