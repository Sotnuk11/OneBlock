package com.alytanta.oneblock.hub.generator

import org.bukkit.Location
import org.bukkit.block.Sign
import org.bukkit.event.*
import org.bukkit.event.block.*
import org.bukkit.event.entity.*
import org.bukkit.event.player.*
import org.bukkit.event.world.*

/** Reservation survives pause/restart; no lock is applied to other areas of the world. */
class HubBuildProtection(private val controller:HubGeneratorController,private val plugin:org.bukkit.plugin.Plugin):Listener {
    private val engine get()=controller.engine
    private class Watch {var safe:Location?=null;var returning=false;var task:io.papermc.paper.threadedregions.scheduler.ScheduledTask?=null}
    private val watches=java.util.concurrent.ConcurrentHashMap<java.util.UUID,Watch>()
    private val poll=plugin.server.globalRegionScheduler.runAtFixedRate(plugin,{
        if(engine.reserved().isNotEmpty())plugin.server.onlinePlayers.forEach {p->
            if(watches.containsKey(p.uniqueId))return@forEach
            val watch=Watch()
            if(watches.putIfAbsent(p.uniqueId,watch)==null)com.alytanta.oneblock.util.FoliaTasks.entity(plugin,p,retired={watches.remove(p.uniqueId,watch)}) {
                watch.task=p.scheduler.runAtFixedRate(plugin,{
                    if(engine.reserved().isEmpty()) {watch.task?.cancel();watches.remove(p.uniqueId,watch)}
                    else {
                        val location=p.location
                        if(!protected(location))watch.safe=location.clone()
                        else {
                            engine.reserved().filter {it.target.key==location.world?.key()?.asString()}.forEach {runCatching {engine.cancel(it.id)}}
                            if(!watch.returning) {
                                val safe=watch.safe
                                if(safe==null) {p.kick(net.kyori.adventure.text.Component.text(engine.settings.message("reserved")))}
                                else {watch.returning=true;p.teleportAsync(safe).whenComplete {_,_->com.alytanta.oneblock.util.FoliaTasks.entity(plugin,p) {watch.returning=false}}}
                            }
                        }
                    }
                },{watches.remove(p.uniqueId,watch)},1,1)
            }
        }
    },1,1)
    fun close() {poll.cancel();watches.values.forEach {it.task?.cancel()};watches.clear()}
    private fun protected(location:Location)=engine.reserved().any {it.target.key==location.world?.key()?.asString() && it.target.contains(location.x,location.y,location.z,1)}
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true) fun place(e:BlockPlaceEvent) {if(protected(e.block.location))e.isCancelled=true}
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true) fun multiPlace(e:BlockMultiPlaceEvent) {if(e.replacedBlockStates.any {protected(it.location)})e.isCancelled=true}
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true) fun breakBlock(e:BlockBreakEvent) {if(protected(e.block.location))e.isCancelled=true}
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true) fun move(e:PlayerMoveEvent) {if(protected(e.to))e.isCancelled=true}
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true) fun teleport(e:PlayerTeleportEvent) {if(e.to?.let(::protected)==true)e.isCancelled=true}
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true) fun flow(e:BlockFromToEvent) {if(protected(e.block.location)||protected(e.toBlock.location))e.isCancelled=true}
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true) fun spread(e:BlockSpreadEvent) {if(protected(e.block.location))e.isCancelled=true}
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true) fun form(e:BlockFormEvent) {if(protected(e.block.location))e.isCancelled=true}
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true) fun fade(e:BlockFadeEvent) {if(protected(e.block.location))e.isCancelled=true}
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true) fun grow(e:BlockGrowEvent) {if(protected(e.block.location))e.isCancelled=true}
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true) fun structure(e:StructureGrowEvent) {if(e.blocks.any {protected(it.location)})e.isCancelled=true}
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true) fun fertilize(e:BlockFertilizeEvent) {if(e.blocks.any {protected(it.location)})e.isCancelled=true}
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true) fun extend(e:BlockPistonExtendEvent) {if(protected(e.block.location)||e.blocks.any {protected(it.location)||protected(it.getRelative(e.direction).location)})e.isCancelled=true}
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true) fun retract(e:BlockPistonRetractEvent) {if(protected(e.block.location)||e.blocks.any {protected(it.location)||protected(it.getRelative(e.direction).location)})e.isCancelled=true}
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true) fun entityBlock(e:EntityChangeBlockEvent) {if(protected(e.block.location))e.isCancelled=true}
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true) fun explosion(e:EntityExplodeEvent) {if(e.blockList().any {protected(it.location)})e.isCancelled=true}
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true) fun blockExplosion(e:BlockExplodeEvent) {if(e.blockList().any {protected(it.location)})e.isCancelled=true}
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true) fun bucket(e:PlayerBucketEmptyEvent) {if(protected(e.block.location)||protected(e.blockClicked.location))e.isCancelled=true}
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true) fun fill(e:PlayerBucketFillEvent) {if(protected(e.block.location))e.isCancelled=true}
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true) fun spawn(e:CreatureSpawnEvent) {if(protected(e.location))e.isCancelled=true}
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true) fun ignite(e:BlockIgniteEvent) {if(protected(e.block.location))e.isCancelled=true}
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true) fun interact(e:PlayerInteractEvent) {
        val b=e.clickedBlock?:return
        if(protected(b.location)) {e.isCancelled=true;return}
        if(e.action==Action.RIGHT_CLICK_BLOCK && b.state is Sign && controller.interact(e.player,b.world,b.x,b.y,b.z))e.isCancelled=true
    }
    @EventHandler fun join(e:PlayerJoinEvent) {if(protected(e.player.location))e.player.kick(net.kyori.adventure.text.Component.text(engine.settings.message("reserved")))}
    @EventHandler fun vehicle(e:org.bukkit.event.vehicle.VehicleMoveEvent) {
        if(protected(e.to)) {
            engine.reserved().filter {it.target.key==e.to.world?.key()?.asString()}.forEach {runCatching {engine.cancel(it.id)}}
            com.alytanta.oneblock.util.FoliaTasks.entity(plugin,e.vehicle) {e.vehicle.teleportAsync(e.from)}
        }
    }
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true) fun unload(e:WorldUnloadEvent) {
        engine.reserved().filter {it.target.key==e.world.key().asString() && it.state in setOf(HubJobState.BUILDING,HubJobState.BACKING_UP,HubJobState.ROLLING_BACK,HubJobState.PREPARED)}.forEach {
            e.isCancelled=true;runCatching {engine.cancel(it.id)}
        }
    }
}
