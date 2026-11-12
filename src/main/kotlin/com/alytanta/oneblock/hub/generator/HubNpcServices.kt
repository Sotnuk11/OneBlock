package com.alytanta.oneblock.hub.generator

import com.alytanta.oneblock.market.MarketSettings
import de.oliver.fancynpcs.api.FancyNpcsPlugin
import de.oliver.fancynpcs.api.NpcData
import org.bukkit.Location
import org.bukkit.plugin.Plugin
import java.util.UUID
import java.util.concurrent.CompletableFuture

/** Loaded only when FancyNpcs is enabled; IDs are resolved using the same names as MarketController. */
object HubNpcServices {
    fun guide(event:de.oliver.fancynpcs.api.events.NpcInteractEvent,controller:HubGeneratorController,open:(org.bukkit.entity.Player,String)->Unit,plugin:Plugin):Boolean {
        val npc=event.npc
        if(npc.data.name!=controller.engine.settings.guideNpc)return false
        val position=npc.data.location
        val job=controller.engine.all().firstOrNull {it.target.worldId==position.world?.uid && it.state in setOf(HubJobState.BUILT,HubJobState.SERVICES_READY,HubJobState.ACTIVE)}?:return false
        val player=event.player
        com.alytanta.oneblock.util.FoliaTasks.entity(plugin,player) {
            if(player.world.uid==job.target.worldId && player.location.distanceSquared(position)<=36 && npc.isShownFor(player))open(player,"start")
        };return true
    }
    fun removeOwned(plugin:Plugin,plan:HubPlan,job:UUID):CompletableFuture<Unit> {
        val fancy=FancyNpcsPlugin.get();check(fancy.npcManager.isLoaded)
        val owned=fancy.npcManager.allNpcs.filter {it.data.creator==job}
        require(owned.all {val l=it.data.location;l.world?.uid==plan.target.worldId && plan.target.contains(l.x,l.y,l.z)}) {"Owned NPC moved outside the job; review it before rollback"}
        var flow=CompletableFuture.completedFuture(Unit)
        owned.forEach {npc->flow=flow.thenCompose {CompletableFuture<Unit>().also {done->
            try {plugin.server.regionScheduler.execute(plugin,npc.data.location) {
                try {synchronized(fancy.npcManager) {
                    check(npc.data.creator==job);npc.removeForAll();fancy.npcManager.removeNpc(npc)
                };done.complete(Unit)}catch(e:Throwable){done.completeExceptionally(e)}
            }}catch(e:Throwable){done.completeExceptionally(e)}
        }}}
        return flow.thenCompose {CompletableFuture.runAsync {fancy.npcManager.saveNpcs(true)}.thenApply {}}
    }
    fun sync(plugin:Plugin,plan:HubPlan,job:UUID,apply:Boolean,market:MarketSettings,settings:HubGeneratorSettings):CompletableFuture<HubServices> {
        val fancy=FancyNpcsPlugin.get();check(fancy.npcManager.isLoaded) {"FancyNpcs is not ready"}
        val names=mapOf("merchant" to market.shopNpc,"bazaar" to market.bazaarNpc,"auction" to market.auction.npc,"guide" to settings.guideNpc)
        require(names.values.toSet().size==4) {"Guide and merchant NPC names must be distinct"}
        var ready=true;val details=mutableListOf<String>()
        if(!market.enabled)details+="Market disabled in config"
        for((role,name) in names) {
            val matches=fancy.npcManager.allNpcs.filter {it.data.id==name || it.data.name==name}
            if(matches.size>1){ready=false;details+="$role: ambiguous configured NPC $name"}
            else if(matches.isEmpty())details+="$role: place manually with /obadmin market npc $role"
            else details+="$role: configured name exists; generator leaves it untouched"
        }
        details+="hub build/services never create, move or remove service NPCs"
        return CompletableFuture.completedFuture(HubServices(ready,details))
    }
}
