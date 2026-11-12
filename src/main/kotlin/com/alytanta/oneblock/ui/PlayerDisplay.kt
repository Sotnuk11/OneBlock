package com.alytanta.oneblock.ui

import com.alytanta.oneblock.hub.*
import com.alytanta.oneblock.island.*
import com.alytanta.oneblock.locale.LocaleService
import com.alytanta.oneblock.storage.StorageService
import com.alytanta.oneblock.util.FoliaTasks
import org.bukkit.entity.Player
import org.bukkit.event.*
import org.bukkit.event.player.*
import org.bukkit.plugin.Plugin
import java.time.*
import java.time.format.DateTimeFormatter
import java.util.*
import java.util.concurrent.ConcurrentHashMap

/** Per-entity heartbeat owns display packets and collision flags; it never changes game mode. */
class PlayerDisplay(private val plugin:Plugin,initial:SidebarSettings,private val islands:IslandService,private val hub:HubService,
    private val rules:HubRulesListener,private val storage:StorageService,private val locales:LocaleService):Listener {
    @Volatile private var settings=initial
    var contextualLines:(Player)->List<String>?={null}
    var extra:(Player)->Map<String,String> = {emptyMap()}
    private class Watch {
        val view=ClientDisplayPackets.View()
        var task:io.papermc.paper.threadedregions.scheduler.ScheduledTask?=null
        var collidable:Boolean?=null
        val exemptions=HubCollisionExemptions()
    }
    private val watches=ConcurrentHashMap<UUID,Watch>()
    @Volatile private var packets=runCatching { ClientDisplayPackets() }.onFailure {
        plugin.logger.log(java.util.logging.Level.SEVERE,"Client display packet API unavailable on this server build",it)
    }.getOrNull()
    fun watch(player:Player) {
        if(watches.containsKey(player.uniqueId))return
        val state=Watch();watches[player.uniqueId]=state
        rules.applyEntryMode(player)
        update(player,state)
        state.task=player.scheduler.runAtFixedRate(plugin,{ update(player,state) },{ watches.remove(player.uniqueId,state) },1L,settings.interval)
    }
    private fun update(player:Player,state:Watch) {
        val noPush=rules.noPush(player)
        if(noPush) {
            if(state.collidable==null)state.collidable=player.isCollidable
            // collidable=false also makes LivingEntity.isPickable false, so arrows/wind never hit.
            player.isCollidable=state.collidable!!
            state.exemptions.update(player.collidableExemptions,plugin.server.onlinePlayers.map {it.uniqueId}.filter {it!=player.uniqueId}.toSet())
        } else state.collidable?.let { player.isCollidable=it;state.collidable=null;state.exemptions.clear(player.collidableExemptions) }
        val wire=packets?:return
        try {
            wire.collision(player,state.view,noPush)
            val config=settings
            if(!config.enabled) { wire.hide(player,state.view);return }
            val language=config.languages[locales.language(player)]?:config.languages.getValue("en")
            val account=islands.findForPlayer(player.uniqueId)
            val current=islands.findByWorld(player.world)
            val members=account?.members?.values?.count { it==IslandRole.MEMBER }?:0
            val time=(player.world.time+6000)%24000
            val number=java.text.NumberFormat.getIntegerInstance(if(locales.language(player)=="uk")Locale.forLanguageTag("uk-UA") else Locale.US)
            val coins=account?.coins?:0
            fun compact(value:Long)=when { value>=1_000_000_000->String.format(Locale.ROOT,"%.1fb",value/1e9);value>=1_000_000->String.format(Locale.ROOT,"%.1fm",value/1e6);value>=1000->String.format(Locale.ROOT,"%.1fk",value/1e3);else->value.toString() }
            val values=mapOf("player" to player.name,"date" to LocalDate.now(ZoneId.of(config.zone)).format(DateTimeFormatter.ofPattern("dd.MM.yy")),
                "world_day" to (player.world.fullTime/24000+1).toString(),"world_time" to String.format(Locale.ROOT,"%02d:%02d",time/1000,(time%1000)*60/1000),
                "location" to (if(hub.isHubWorld(player.world))language.hub else if(current!=null)language.island else language.elsewhere),
                "profile" to (account?.let { islands.profileName(it) }?:language.noProfile),"profile_type" to (if(account==null)"—" else if(members>0)language.coop else language.solo),
                "members" to (members+1).toString(),"coins" to number.format(coins),"coins_short" to compact(coins),"blocks" to number.format(account?.core?.breaks?:0),
                "online" to plugin.server.onlinePlayers.size.toString(),
                "trading" to (if(storage.healthy)language.available else language.unavailable))
            val all=values+extra(player)
            fun format(template:String)=all.entries.fold(template) { text,(key,value)->text.replace("{$key}",value) }
            wire.sidebar(player,state.view,format(language.title),(contextualLines(player)?:language.lines).map(::format))
        } catch(e:Exception) {
            packets=null;plugin.logger.log(java.util.logging.Level.SEVERE,"Client sidebar/collision packets disabled after failure",e)
        }
    }
    fun apply(next:SidebarSettings) {
        settings=next
        if(packets==null)packets=runCatching { ClientDisplayPackets() }.getOrNull()
        plugin.server.onlinePlayers.forEach { player -> FoliaTasks.entity(plugin,player) {
            watches.remove(player.uniqueId)?.let { old->old.task?.cancel();cleanup(player,old) };watch(player)
        } }
    }
    private fun cleanup(player:Player,state:Watch) {
        state.exemptions.clear(player.collidableExemptions)
        state.collidable?.let { player.isCollidable=it }
        packets?.let { runCatching { it.hide(player,state.view);it.collision(player,state.view,false) } }
    }
    fun close() {
        val old=watches.toMap();watches.clear()
        old.forEach { (id,state)->state.task?.cancel();plugin.server.getPlayer(id)?.let { player ->
            if(org.bukkit.Bukkit.isOwnedByCurrentRegion(player))cleanup(player,state)
            else if(plugin.isEnabled)FoliaTasks.entity(plugin,player) { cleanup(player,state) }
        } }
    }
    @EventHandler(priority=EventPriority.MONITOR) fun join(event:PlayerJoinEvent) { FoliaTasks.entity(plugin,event.player) { watch(event.player) } }
    @EventHandler fun quit(event:PlayerQuitEvent) { watches.remove(event.player.uniqueId)?.let { it.task?.cancel();cleanup(event.player,it) } }
    @EventHandler(priority=EventPriority.MONITOR) fun world(event:PlayerChangedWorldEvent) { watches[event.player.uniqueId]?.let { update(event.player,it) } }
    @EventHandler(priority=EventPriority.MONITOR) fun respawn(event:com.destroystokyo.paper.event.player.PlayerPostRespawnEvent) {
        watches[event.player.uniqueId]?.let { state->packets?.hide(event.player,state.view);update(event.player,state) }
    }
}
