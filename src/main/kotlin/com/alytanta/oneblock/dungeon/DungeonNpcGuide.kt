package com.alytanta.oneblock.dungeon

import com.alytanta.oneblock.util.FoliaTasks
import de.oliver.fancynpcs.api.FancyNpcsPlugin
import de.oliver.fancynpcs.api.events.NpcInteractEvent
import org.bukkit.event.HandlerList
import org.bukkit.event.Listener
import org.bukkit.event.server.PluginDisableEvent
import java.util.concurrent.ConcurrentHashMap

/** Interaction adapter only. NPC creation, persistence and relocation are explicit FancyNpcs admin actions. */
class DungeonNpcGuide(private val mine:MineController):AutoCloseable,Listener {
    private val plugin get()=mine.plugin
    private val opening=ConcurrentHashMap.newKeySet<java.util.UUID>()
    @Volatile private var closed=false
    init {plugin.server.pluginManager.registerEvents(this,plugin)}

    @org.bukkit.event.EventHandler fun dependencyDisabled(event:PluginDisableEvent) {
        if(event.plugin.name=="FancyNpcs")close()
    }

    fun interact(event:NpcInteractEvent):Boolean {
        if(closed)return false
        val settings=mine.settings.npc
        val npc=event.npc
        if(!settings.enabled || npc.data.id!=settings.name && npc.data.name!=settings.name)return false
        event.isCancelled=true
        val player=event.player
        if(!opening.add(player.uniqueId))return true
        FoliaTasks.entity(plugin,player,retired={opening.remove(player.uniqueId)}) {
            try {
                val current=mine.settings.npc
                val location=npc.data.location
                if(!closed && current.enabled && current.name==settings.name &&
                    plugin.server.pluginManager.isPluginEnabled("FancyNpcs") &&
                    FancyNpcsPlugin.get().npcManager.getNpcById(npc.data.id)===npc &&
                    player.world.uid==location.world?.uid && mine.hub.isHubWorld(player.world) &&
                    player.location.distanceSquared(location)<=current.distance*current.distance && npc.isShownFor(player)) {
                    // Existing route opens dungeon selection; its configured menu continues through lobby/readiness/session checks.
                    mine.menu.open(player,"dungeons")
                }
            } finally {opening.remove(player.uniqueId)}
        }
        return true
    }

    override fun close() {
        if(closed)return
        closed=true;opening.clear();HandlerList.unregisterAll(this)
    }
}
