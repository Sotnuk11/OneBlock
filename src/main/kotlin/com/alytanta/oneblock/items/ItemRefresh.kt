package com.alytanta.oneblock.items

import com.alytanta.oneblock.market.InventoryGuard
import com.alytanta.oneblock.util.FoliaTasks
import org.bukkit.entity.Player
import org.bukkit.event.*
import org.bukkit.event.player.*
import org.bukkit.event.inventory.InventoryCloseEvent
import org.bukkit.plugin.Plugin
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** Loaded personal inventories only. Frozen claims, SQL snapshots and unopened containers are never rewritten. */
class ItemRefresh(private val plugin:Plugin):Listener {
    private val queued=ConcurrentHashMap.newKeySet<UUID>()
    fun refresh(player:Player) {
        if(!queued.add(player.uniqueId))return
        FoliaTasks.entity(plugin,player,retired={queued.remove(player.uniqueId)}) {
            if(InventoryGuard.busy(player.uniqueId)) {
                player.scheduler.runDelayed(plugin,{queued.remove(player.uniqueId);refresh(player)},{queued.remove(player.uniqueId)},20);return@entity
            }
            queued.remove(player.uniqueId)
            for(slot in 0 until player.inventory.size) {
                val stack=player.inventory.getItem(slot)?:continue
                val copy=stack.clone()
                if(CustomItems.refresh(copy))player.inventory.setItem(slot,copy)
            }
        }
    }
    @EventHandler fun join(e:PlayerJoinEvent)=refresh(e.player)
    @EventHandler fun held(e:PlayerItemHeldEvent)=refresh(e.player)
    @EventHandler fun close(e:InventoryCloseEvent) {val p=e.player as? Player?:return;refresh(p)}
    @EventHandler fun slot(e:io.papermc.paper.event.player.PlayerInventorySlotChangeEvent)=refresh(e.player)
}
