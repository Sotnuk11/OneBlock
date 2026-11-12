package com.alytanta.oneblock.market

import com.alytanta.oneblock.util.FoliaTasks
import de.oliver.fancynpcs.api.events.NpcInteractEvent
import de.oliver.fancynpcs.api.FancyNpcsPlugin
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.plugin.Plugin

class FancyMarketBridge(private val plugin: Plugin, private val market: MarketController,private val guide:(NpcInteractEvent)->Boolean={false}) : Listener {
    private val opening = java.util.concurrent.ConcurrentHashMap.newKeySet<java.util.UUID>()
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onInteract(event: NpcInteractEvent) {
        if(!InventoryGuard.interactionAllowed(event.player.uniqueId)) {event.isCancelled=true;return}
        val npc = event.npc
        val kind = market.npcKind(npc.data.id, npc.data.name) ?: run {if(guide(event))event.isCancelled=true;return}
        event.isCancelled = true // Do not also run configured console/player/OP actions for this merchant.
        val player = event.player
        if (!opening.add(player.uniqueId)) return
        // Deduplicate one player's queued clicks; the same NPC stays available to every other player.
        FoliaTasks.entity(plugin, player, retired = { opening.remove(player.uniqueId) }) {
            try { market.interact(player, kind, { npc.data.location.clone() }, {
                plugin.server.pluginManager.isPluginEnabled("FancyNpcs") &&
                    FancyNpcsPlugin.get().npcManager.getNpcById(npc.data.id) === npc && npc.isShownFor(player)
            }) } finally { opening.remove(player.uniqueId) }
        }
    }
}
