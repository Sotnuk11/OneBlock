package com.alytanta.oneblock.market

import com.alytanta.oneblock.locale.Lang
import com.alytanta.oneblock.locale.LocaleService
import com.alytanta.oneblock.storage.OperationLog
import com.alytanta.oneblock.storage.PostgresStorageService
import com.alytanta.oneblock.util.FoliaTasks
import de.oliver.fancynpcs.api.FancyNpcsPlugin
import de.oliver.fancynpcs.api.NpcData
import de.oliver.fancynpcs.api.skins.SkinData
import org.bukkit.entity.EntityType
import org.bukkit.entity.Player
import org.bukkit.plugin.Plugin

/** Optional dependency kept in its own class. Called only on the player's scheduler after dependency checks. */
object FancyMarketNpc {
    fun create(plugin: Plugin, storage: PostgresStorageService, player: Player, name: String, locales: LocaleService,
        displayName:String=name,type:String="VILLAGER",skin:String="") {
        val actor = player.uniqueId
        val position = player.location.clone()
        try {
            val fancy = FancyNpcsPlugin.get()
            require(type in setOf("VILLAGER","WANDERING_TRADER","PLAYER")) {"npc.type must be VILLAGER, WANDERING_TRADER or PLAYER"}
            require(displayName.isNotBlank() && displayName.length<=128)
            synchronized(fancy.npcManager) {
                check(fancy.npcManager.isLoaded) { "npc-not-ready" }
                if (fancy.npcManager.getNpcById(name) != null || fancy.npcManager.allNpcs.any { it.data.name == name }) {
                    locales.send(player, Lang.MERCHANT_NPC_EXISTS, "name" to name)
                    storage.audit(OperationLog(operation = "NPC_CREATE", actor = actor, account = null, success = false,
                        status = "FAILED", reason = "already-exists", details = mapOf("name" to name)))
                    return
                }
            }
            // FancyNpcs skin lookup may contact Mojang. It runs only for this explicit command and never on a Folia tick thread.
            FoliaTasks.async(plugin) {
                val skinData=if(type=="PLAYER" && skin.isNotBlank())runCatching {fancy.skinManager.getByIdentifier(skin,SkinData.SkinVariant.AUTO)}
                    .onFailure {plugin.logger.warning("Could not resolve configured NPC skin '$skin': ${it.javaClass.simpleName}; using the configured entity fallback")}.getOrNull() else null
                try {plugin.server.regionScheduler.execute(plugin,position) {
                    try {
                        synchronized(fancy.npcManager) {
                            check(fancy.npcManager.isLoaded) {"FancyNpcs stopped before NPC creation"}
                            check(fancy.npcManager.getNpcById(name)==null && fancy.npcManager.allNpcs.none {it.data.name==name}) {"NPC '$name' already exists; it was not moved"}
                            val data=NpcData(name,actor,position).setDisplayName(displayName).setType(EntityType.valueOf(type))
                                .setCollidable(false).setShowInTab(false).setTurnToPlayer(true).setInteractionCooldown(0.4f)
                            if(skinData!=null)data.setSkinData(skinData)
                            val npc=fancy.npcAdapter.apply(data);npc.isSaveToFile=true;npc.create();fancy.npcManager.registerNpc(npc);npc.spawnForAll()
                        }
                        FoliaTasks.async(plugin) {
                            try {fancy.npcManager.saveNpcs(true)
                                storage.audit(OperationLog(operation="NPC_CREATE",actor=actor,account=null,success=true,status="SUCCESS",details=mapOf("role" to name,"world" to position.world?.key()?.asString(),"x" to position.x,"y" to position.y,"z" to position.z)))
                                FoliaTasks.entity(plugin,player) {locales.send(player,Lang.MERCHANT_NPC_CREATED,"name" to name)}
                            } catch(error:Exception) {plugin.logger.log(java.util.logging.Level.WARNING,"Could not save FancyNpcs service NPC",error);FoliaTasks.entity(plugin,player){locales.send(player,Lang.MARKET_ERROR)}}
                        }
                    } catch(error:Throwable) {
                        storage.audit(OperationLog(operation="NPC_CREATE",actor=actor,account=null,success=false,status="FAILED",reason=error.javaClass.simpleName,details=mapOf("name" to name)))
                        plugin.logger.log(java.util.logging.Level.WARNING,"Could not create FancyNpcs service NPC",error)
                        FoliaTasks.entity(plugin,player){locales.send(player,Lang.MARKET_ERROR)}
                    }
                }}catch(error:Throwable) {plugin.logger.log(java.util.logging.Level.WARNING,"Could not schedule FancyNpcs service NPC",error);FoliaTasks.entity(plugin,player){locales.send(player,Lang.MARKET_ERROR)}}
            }
        } catch (error: Exception) {
            storage.audit(OperationLog(operation = "NPC_CREATE", actor = actor, account = null, success = false,
                status = "FAILED", reason = error.javaClass.simpleName, details = mapOf("name" to name)))
            plugin.logger.log(java.util.logging.Level.WARNING, "Could not create FancyNpcs merchant", error)
            locales.send(player, Lang.MARKET_ERROR)
        }
    }
}
