package com.alytanta.oneblock.player

import com.alytanta.oneblock.harvest.HarvestSettings
import com.alytanta.oneblock.menu.MenuInventory
import com.alytanta.oneblock.util.Colors
import com.destroystokyo.paper.event.player.PlayerPostRespawnEvent
import org.bukkit.entity.Player
import org.bukkit.event.*
import org.bukkit.event.inventory.*
import org.bukkit.event.player.*
import org.bukkit.event.entity.EntityPickupItemEvent
import org.bukkit.plugin.Plugin
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** Event refreshes are coalesced; an entity-owned watchdog catches changes by external plugins. */
class InventoryStatus(private val plugin: Plugin, private val players: PlayerService,
    @Volatile var settings: HarvestSettings) : Listener {
    private val saturation = ConcurrentHashMap<UUID,Long>()
    private val allSlots = ((1L shl 36) - 1) and (1L shl MenuInventory.SLOT).inv()
    private fun publish(player: Player, full: Boolean) {
        val old = players.find(player.uniqueId)
        if (old?.inventoryFull != full) runCatching { players.update(player.uniqueId) { it.copy(inventoryFull = full) } }
        if (!full) notified.remove(player.uniqueId)
    }
    fun slotChanged(inventory: org.bukkit.inventory.PlayerInventory, slot: Int) {
        if(slot !in 0 until 36 || slot == MenuInventory.SLOT) return
        val player = inventory.holder as? Player ?: return
        val prior = saturation[player.uniqueId] ?: return
        val bit = 1L shl slot
        val item = inventory.getItem(slot)
        val full = item != null && !item.isEmpty && item.amount >= minOf(item.maxStackSize,inventory.maxStackSize)
        val next = if(full) prior or bit else prior and bit.inv()
        saturation[player.uniqueId] = next
        publish(player,next == allSlots)
    }
    private val pending = ConcurrentHashMap.newKeySet<UUID>()
    private class Watch { var task: io.papermc.paper.threadedregions.scheduler.ScheduledTask? = null }
    private val watches = ConcurrentHashMap<UUID,Watch>()
    private val notified = ConcurrentHashMap<UUID, Long>()
    fun full(player: Player) = players.find(player.uniqueId)?.inventoryFull == true
    fun refresh(player: Player) {
        if (!player.isOnline) return
        var bits = 0L
        for(slot in 0 until 36) {
            if(slot == MenuInventory.SLOT) continue
            val item = player.inventory.getItem(slot)
            if(item != null && !item.isEmpty && item.amount >= minOf(item.maxStackSize,player.inventory.maxStackSize)) bits = bits or (1L shl slot)
        }
        saturation[player.uniqueId] = bits
        publish(player,bits == allSlots)
    }
    fun changed(player: Player) {
        pending.add(player.uniqueId)
    }
    fun watch(player: Player) {
        val watch = Watch()
        watches.put(player.uniqueId,watch)?.task?.cancel()
        refresh(player)
        val task = player.scheduler.runAtFixedRate(plugin, {
            pending.remove(player.uniqueId)
            refresh(player)
            val now = System.currentTimeMillis()
            if (full(player) && players.find(player.uniqueId)?.inventoryNotifications != false &&
                now - (notified[player.uniqueId] ?: 0L) >= settings.notificationSeconds * 1000L) {
                player.sendMessage(Colors.colorize(settings.notification)); notified[player.uniqueId] = now
            }
        }, { watches.remove(player.uniqueId,watch) }, 1L, 1L)
        watch.task = task
        if(task == null) watches.remove(player.uniqueId,watch)
    }
    fun close() { watches.values.forEach { it.task?.cancel() };watches.clear();pending.clear();saturation.clear();notified.clear() }
    @EventHandler fun postRespawn(e: PlayerPostRespawnEvent) { watch(e.player) }
    @EventHandler fun world(e: PlayerChangedWorldEvent) { watch(e.player) }
    fun toggle(player: Player) { players.update(player.uniqueId) { it.copy(inventoryNotifications = it.inventoryNotifications == false) } }
    @EventHandler(priority=EventPriority.MONITOR) fun join(e: PlayerJoinEvent) { watch(e.player) }
    @EventHandler fun quit(e: PlayerQuitEvent) { watches.remove(e.player.uniqueId)?.task?.cancel(); pending.remove(e.player.uniqueId); notified.remove(e.player.uniqueId); saturation.remove(e.player.uniqueId) }
    @EventHandler(priority=EventPriority.MONITOR) fun click(e: InventoryClickEvent) { (e.whoClicked as? Player)?.let(::changed) }
    @EventHandler(priority=EventPriority.MONITOR) fun drag(e: InventoryDragEvent) { (e.whoClicked as? Player)?.let(::changed) }
    @EventHandler(priority=EventPriority.MONITOR) fun close(e: InventoryCloseEvent) { (e.player as? Player)?.let(::changed) }
    @EventHandler(priority=EventPriority.MONITOR,ignoreCancelled=true) fun pickup(e: EntityPickupItemEvent) { (e.entity as? Player)?.let(::changed) }
    @EventHandler(priority=EventPriority.MONITOR) fun drop(e: PlayerDropItemEvent) { changed(e.player) }
    @EventHandler(priority=EventPriority.MONITOR) fun consume(e: PlayerItemConsumeEvent) { changed(e.player) }
    @EventHandler(priority=EventPriority.MONITOR) fun damage(e: PlayerItemDamageEvent) { changed(e.player) }
    @EventHandler(priority=EventPriority.MONITOR) fun respawn(e: PlayerRespawnEvent) { changed(e.player) }
    @EventHandler(priority=EventPriority.MONITOR) fun swap(e: PlayerSwapHandItemsEvent) { changed(e.player) }
    companion object {
        fun isFull(inventory: org.bukkit.inventory.PlayerInventory): Boolean = (0 until 36).all { slot ->
            if (slot == MenuInventory.SLOT) true else inventory.getItem(slot)?.let {
                !it.isEmpty && it.amount >= minOf(it.maxStackSize, inventory.maxStackSize)
            } == true
        }
    }
}
