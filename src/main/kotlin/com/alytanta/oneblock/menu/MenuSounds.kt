package com.alytanta.oneblock.menu

import org.bukkit.Sound
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.inventory.InventoryClickEvent

/** One click sound for every plugin window; background panes and empty cells stay silent. */
class MenuSounds : Listener {
    @EventHandler(priority = EventPriority.MONITOR)
    fun click(event: InventoryClickEvent) {
        val top = event.view.topInventory
        val holder = top.holder ?: return
        if (!holder.javaClass.name.startsWith("com.alytanta.oneblock.") || event.rawSlot !in 0 until top.size) return
        val item = event.currentItem ?: return
        if (item.type.isAir || item.type.name.endsWith("GLASS_PANE")) return
        (event.whoClicked as? Player)?.let { it.playSound(it.location, Sound.UI_BUTTON_CLICK, .35f, 1.2f) }
    }
}
