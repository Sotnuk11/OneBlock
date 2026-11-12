package com.alytanta.oneblock.hub

import org.bukkit.GameMode
import org.bukkit.entity.*
import org.bukkit.event.*
import org.bukkit.event.entity.*
import org.bukkit.plugin.Plugin
import com.alytanta.oneblock.util.FoliaTasks

class HubRulesListener(private val plugin:Plugin,private val inHub:(org.bukkit.World)->Boolean,private val islandWorld:(org.bukkit.World)->Boolean,initial:HubRules):Listener {
    @Volatile var settings=initial
    private fun managed(entity:Entity)=settings.enabled&&!islandWorld(entity.world)&&inHub(entity.world)
    fun noPush(player:Player)=managed(player)&&!settings.collisions
    private fun canChooseMode(player:Player)=player.isOp||player.hasPermission("oneblock.hub.build")
    fun applyEntryMode(player:Player) {
        // Repair Adventure carried over from the hub before evaluating any hub rule.
        // This also repairs existing island sessions when hub protection has been disabled.
        if(islandWorld(player.world)) {
            if(player.gameMode==GameMode.ADVENTURE)player.gameMode=settings.islandMode.takeUnless { it==GameMode.ADVENTURE }?:GameMode.SURVIVAL
            return
        }
        if(!settings.enabled)return
        if(managed(player)&&settings.adventure&&player.gameMode==GameMode.SURVIVAL&&!canChooseMode(player))player.gameMode=GameMode.ADVENTURE
    }
    private fun afterArrival(player:Player) { FoliaTasks.entity(plugin,player) { if(!player.isDead)applyEntryMode(player) } }
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true) fun damage(event:EntityDamageByEntityEvent) {
        if(event.entity is Player&&managed(event.entity)&&!settings.pvp&&
            (event.damageSource.causingEntity is Player||event.damager is Player||(event.damager as? Projectile)?.shooter is Player))event.isCancelled=true
    }
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true) fun combust(event:EntityCombustByEntityEvent) {
        if(event.entity is Player&&managed(event.entity)&&!settings.pvp&&(event.combuster is Player||(event.combuster as? Projectile)?.shooter is Player))event.isCancelled=true
    }
    @EventHandler(priority=EventPriority.MONITOR) fun join(event:org.bukkit.event.player.PlayerJoinEvent) { afterArrival(event.player) }
    @EventHandler(priority=EventPriority.MONITOR) fun world(event:org.bukkit.event.player.PlayerChangedWorldEvent) { afterArrival(event.player) }
    @EventHandler(priority=EventPriority.MONITOR,ignoreCancelled=true) fun teleport(event:org.bukkit.event.player.PlayerTeleportEvent) { afterArrival(event.player) }
    @EventHandler(priority=EventPriority.MONITOR) fun respawn(event:com.destroystokyo.paper.event.player.PlayerPostRespawnEvent) { afterArrival(event.player) }
    private fun npc(entity:Entity)=entity.hasMetadata("NPC")||entity.hasMetadata("FancyNpcs")||entity.scoreboardTags.any { it.equals("npc",true)||it.startsWith("fancynpcs",true) }
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true) fun spawn(event:CreatureSpawnEvent) {
        if(event.entity is Mob&&managed(event.entity)&&!settings.mobSpawning&&!npc(event.entity))event.isCancelled=true
    }
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true) fun preSpawn(event:com.destroystokyo.paper.event.entity.PreCreatureSpawnEvent) {
        if(settings.enabled&&!settings.mobSpawning&&event.spawnLocation.world?.let { !islandWorld(it)&&inHub(it) }==true) { event.isCancelled=true;event.setShouldAbortSpawn(true) }
    }
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true) fun splash(event:PotionSplashEvent) {
        if(!settings.pvp&&managed(event.entity)&&event.entity.shooter is Player&&event.potion.effects.any { it.type.category==org.bukkit.potion.PotionEffectTypeCategory.HARMFUL })
            event.affectedEntities.filterIsInstance<Player>().forEach { event.setIntensity(it,0.0) }
    }
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true) fun cloud(event:AreaEffectCloudApplyEvent) {
        if(!settings.pvp&&managed(event.entity)&&event.entity.source is Player&&
            (event.entity.basePotionType?.potionEffects.orEmpty()+event.entity.customEffects).any { it.type.category==org.bukkit.potion.PotionEffectTypeCategory.HARMFUL })event.affectedEntities.removeIf { it is Player }
    }
}
