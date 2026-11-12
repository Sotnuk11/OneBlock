package com.alytanta.oneblock.util

import org.bukkit.Location
import org.bukkit.NamespacedKey
import org.bukkit.entity.Item
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.entity.EntityPickupItemEvent
import org.bukkit.event.entity.ItemMergeEvent
import org.bukkit.event.inventory.InventoryPickupItemEvent
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataType
import org.bukkit.plugin.Plugin
import java.util.UUID

/** Overflow belongs to one recipient, including after a disconnect; never distribute it to the co-op. */
class OwnedItems(private val plugin: Plugin,private val islands:com.alytanta.oneblock.island.IslandService?=null) : Listener {
    private val key = NamespacedKey(plugin, "reward_recipient")
    private val manualOwner=NamespacedKey(plugin,"manual_drop_owner")
    private val manualProfile=NamespacedKey(plugin,"manual_drop_profile")
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true)
    fun manual(event:org.bukkit.event.player.PlayerDropItemEvent) {
        val account=islands?.findForPlayer(event.player.uniqueId)
        if(account==null || !islands.persistenceAvailable() || !islands.canBuild(account,event.player.uniqueId)) {event.isCancelled=true;return}
        val item=event.itemDrop
        item.owner=null // Native owner gating would also exclude authorized co-op members before our listener.
        item.thrower=event.player.uniqueId;item.setCanMobPickup(false)
        item.persistentDataContainer.set(manualOwner,PersistentDataType.STRING,event.player.uniqueId.toString())
        item.persistentDataContainer.set(manualProfile,PersistentDataType.STRING,account.owner.toString())
    }

    fun drop(location: Location, stack: ItemStack, recipient: UUID) {
        FoliaTasks.region(plugin, location) {
            location.world?.dropItem(location, stack) { item ->
                item.owner = recipient
                item.thrower = recipient
                item.setCanMobPickup(false)
                item.persistentDataContainer.set(key, PersistentDataType.STRING, recipient.toString())
            }
        }
    }

    private fun recipient(item: Item): String? = item.persistentDataContainer.get(key, PersistentDataType.STRING)

    fun belongsTo(item: Item, player: UUID): Boolean = recipient(item) == player.toString()
    private fun bound(item:Item)=recipient(item)!=null || item.persistentDataContainer.has(manualOwner) || item.persistentDataContainer.has(manualProfile)

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onPickup(event: EntityPickupItemEvent) {
        val owner=recipient(event.item)
        if(owner!=null) {if(event.entity !is Player || event.entity.uniqueId.toString()!=owner)event.isCancelled=true;return}
        if(!bound(event.item))return
        val actor=event.entity as? Player
        val account=runCatching {UUID.fromString(event.item.persistentDataContainer.get(manualProfile,PersistentDataType.STRING))}.getOrNull()
        val knownOwner=runCatching {UUID.fromString(event.item.persistentDataContainer.get(manualOwner,PersistentDataType.STRING))}.getOrNull()
        if(actor==null || account==null || knownOwner==null || islands?.persistenceAvailable()!=true || islands.findByOwner(account)?.let {islands.canBuild(it,actor.uniqueId)}!=true)event.isCancelled=true
    }

    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled = true) fun onHopper(event: InventoryPickupItemEvent) {
        if (bound(event.item)) event.isCancelled = true
    }

    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled = true) fun onMerge(event: ItemMergeEvent) {
        if (bound(event.entity) || bound(event.target)) event.isCancelled = true
    }
}
