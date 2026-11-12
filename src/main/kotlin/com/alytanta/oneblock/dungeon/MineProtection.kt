package com.alytanta.oneblock.dungeon

import com.alytanta.oneblock.util.FoliaTasks
import org.bukkit.*
import org.bukkit.entity.*
import org.bukkit.event.*
import org.bukkit.event.block.*
import org.bukkit.event.entity.*
import org.bukkit.event.player.*
import org.bukkit.inventory.EquipmentSlot
import io.papermc.paper.event.player.AsyncPlayerSpawnLocationEvent

/** Scoped to owned instance worlds; never freezes unrelated islands or server-wide gameplay. */
class MineProtection(private val mine:MineController) : Listener {
    private val shotRun=NamespacedKey(mine.plugin,"mine_shot_run")
    private val shotEpoch=NamespacedKey(mine.plugin,"mine_shot_epoch")
    private val shotDamage=NamespacedKey(mine.plugin,"mine_shot_damage")
    private val flying=java.util.concurrent.ConcurrentHashMap<java.util.UUID,String>()
    private val flightLock=Any()
    private data class LoginKey(val actor:java.util.UUID,val address:java.net.SocketAddress)
    private data class LoginPermit(val connection:io.papermc.paper.connection.PlayerLoginConnection,val expires:Long)
    private val logins=java.util.concurrent.ConcurrentHashMap<LoginKey,LoginPermit>()
    private val interacting=java.util.concurrent.ConcurrentHashMap.newKeySet<java.util.UUID>()
    private fun input(run:MineRun,p:Player,operation:(MineRun)->MineRun,reply:(MineRun)->Unit={}) {
        if(!interacting.add(run.id)) {mine.say(p,"busy");return}
        mine.engine.event(run.id,run.epoch,operation).whenComplete {next,error->
            interacting.remove(run.id)
            if(error==null)reply(next) else FoliaTasks.entity(mine.plugin,p) {mine.say(p,"unavailable")}
        }
    }
    private fun protected(world:World)=mine.managed(world)
    @EventHandler(priority=EventPriority.HIGHEST) fun breakBlock(e:BlockBreakEvent) {if(protected(e.block.world))e.isCancelled=true}
    @EventHandler(priority=EventPriority.HIGHEST) fun place(e:BlockPlaceEvent) {if(protected(e.block.world))e.isCancelled=true}
    @EventHandler(priority=EventPriority.HIGHEST) fun bucket(e:PlayerBucketEmptyEvent) {if(protected(e.player.world))e.isCancelled=true}
    @EventHandler(priority=EventPriority.HIGHEST) fun fill(e:PlayerBucketFillEvent) {if(protected(e.player.world))e.isCancelled=true}
    @EventHandler(priority=EventPriority.HIGHEST) fun drop(e:PlayerDropItemEvent) {if(protected(e.player.world))e.isCancelled=true}
    @EventHandler(priority=EventPriority.HIGHEST) fun pickup(e:EntityPickupItemEvent) {if(protected(e.entity.world))e.isCancelled=true}
    @EventHandler(priority=EventPriority.HIGHEST) fun explode(e:EntityExplodeEvent) {if(protected(e.entity.world)){e.blockList().clear();e.isCancelled=true}}
    @EventHandler(priority=EventPriority.HIGHEST) fun explodeBlock(e:BlockExplodeEvent) {if(protected(e.block.world))e.isCancelled=true}
    @EventHandler(priority=EventPriority.HIGHEST) fun ignite(e:BlockIgniteEvent) {if(protected(e.block.world))e.isCancelled=true}
    @EventHandler(priority=EventPriority.HIGHEST) fun burn(e:BlockBurnEvent) {if(protected(e.block.world))e.isCancelled=true}
    @EventHandler(priority=EventPriority.HIGHEST) fun fluid(e:BlockFromToEvent) {if(protected(e.block.world))e.isCancelled=true}
    @EventHandler(priority=EventPriority.HIGHEST) fun piston(e:BlockPistonExtendEvent) {if(protected(e.block.world))e.isCancelled=true}
    @EventHandler(priority=EventPriority.HIGHEST) fun pistonBack(e:BlockPistonRetractEvent) {if(protected(e.block.world))e.isCancelled=true}
    @EventHandler(priority=EventPriority.HIGHEST) fun portal(e:EntityPortalEvent) {if(protected(e.entity.world))e.isCancelled=true}
    @EventHandler(priority=EventPriority.HIGHEST) fun glide(e:EntityToggleGlideEvent) {if(protected(e.entity.world) && e.isGliding)e.isCancelled=true}
    @EventHandler(priority=EventPriority.HIGHEST) fun spawn(e:CreatureSpawnEvent) {if(protected(e.entity.world) && e.spawnReason!=CreatureSpawnEvent.SpawnReason.CUSTOM)e.isCancelled=true}
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true) fun projectile(e:ProjectileLaunchEvent) {
        if(!protected(e.entity.world))return
        if(e.entity is EnderPearl || e.entity is ThrownExpBottle || e.entity is ThrownPotion) {e.isCancelled=true;return}
        val source=e.entity.shooter as? LivingEntity
        val session=if(source is Player)mine.binding(source.uniqueId) else source?.let {mine.encounters.mobs[it.uniqueId]}?.run?.let(mine.engine::get)
        session?.settings?.waves?.let {rules->
            synchronized(flightLock) {
                if(flying.values.count {it==session.world}>=rules.limits.projectiles) {e.isCancelled=true;return}
                flying[e.entity.uniqueId]=session.world
            }
            // The retirement callback releases the permit when sweep removes an arrow before its timeout.
            val task=e.entity.scheduler.runDelayed(mine.plugin,{
                flying.remove(e.entity.uniqueId);e.entity.remove()
            },{flying.remove(e.entity.uniqueId)},200)
            if(task==null)flying.remove(e.entity.uniqueId)
        }
        source?.let {mine.encounters.mobs[it.uniqueId]}?.let {mob->
            e.entity.persistentDataContainer.set(shotRun,org.bukkit.persistence.PersistentDataType.STRING,mob.run.toString())
            e.entity.persistentDataContainer.set(shotEpoch,org.bukkit.persistence.PersistentDataType.LONG,mob.epoch)
            mob.damage?.let {e.entity.persistentDataContainer.set(shotDamage,org.bukkit.persistence.PersistentDataType.DOUBLE,it)}
        }
        (e.entity as? AbstractArrow)?.pickupStatus=AbstractArrow.PickupStatus.DISALLOWED
    }
    @EventHandler(priority=EventPriority.MONITOR) fun hit(e:ProjectileHitEvent) {
        // Native entity-hit retirement and piercing remain intact. Only embedded block hits need cleanup.
        if(e.hitBlock==null || !flying.containsKey(e.entity.uniqueId))return
        FoliaTasks.entity(mine.plugin,e.entity,retired={flying.remove(e.entity.uniqueId)}) {flying.remove(e.entity.uniqueId);e.entity.remove()}
    }
    @EventHandler(priority=EventPriority.HIGHEST) fun consume(e:PlayerItemConsumeEvent) {if(protected(e.player.world) && e.item.type==Material.CHORUS_FRUIT)e.isCancelled=true}
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true) fun damage(e:EntityDamageByEntityEvent) {
        if(!protected(e.entity.world))return
        (e.damager as? Projectile)?.persistentDataContainer?.let {data->
            val id=data.get(shotRun,org.bukkit.persistence.PersistentDataType.STRING)
            if(id!=null) {
                val run=runCatching {mine.engine.get(java.util.UUID.fromString(id))}.getOrNull()
                if(run==null || run.terminal || run.epoch!=data.get(shotEpoch,org.bukkit.persistence.PersistentDataType.LONG)) {e.isCancelled=true;return}
                data.get(shotDamage,org.bukkit.persistence.PersistentDataType.DOUBLE)?.let {e.damage=it}
            }
        }
        val source=(e.damager as? LivingEntity)?:((e.damager as? Projectile)?.shooter as? LivingEntity)
        source?.let {mine.encounters.mobs[it.uniqueId]}?.damage?.let {e.damage=it}
        val actor=(e.damager as? Player)?:((e.damager as? Projectile)?.shooter as? Player)
        if(e.entity is Player && actor!=null) {e.isCancelled=true;return}
        if(actor!=null) {
            val mob=mine.encounters.mobs[e.entity.uniqueId]
            val run=mine.binding(actor.uniqueId)
            if(mob==null || run==null || run.id!=mob.run || run.epoch!=mob.epoch || run.members[actor.uniqueId]?.let {it.inWorld && it.life==MineLife.ALIVE}!=true)e.isCancelled=true
        }
    }
    @EventHandler(priority=EventPriority.HIGHEST) fun anyDamage(e:EntityDamageEvent) {
        if(e.entity !is Player)return
        if(protected(e.entity.world) && mine.binding(e.entity.uniqueId)?.let {!it.terminal && it.world==e.entity.world.key().asString() && it.members[e.entity.uniqueId]?.let {m->m.inWorld && m.life==MineLife.ALIVE}==true}!=true) {e.isCancelled=true;return}
        val run=mine.binding(e.entity.uniqueId)?:return
        if(run.terminal && run.members[e.entity.uniqueId]?.returnPending==true)e.isCancelled=true
    }
    @EventHandler(priority=EventPriority.HIGHEST) fun died(e:EntityDeathEvent) {
        if(!protected(e.entity.world))return
        e.drops.clear();e.droppedExp=0
        if(e.entity is Player)return
        mine.encounters.died(e.entity.uniqueId)
    }
    @EventHandler(priority=EventPriority.LOWEST) fun suppressLoot(e:EntityDeathEvent) {
        if(protected(e.entity.world)) {e.drops.clear();e.droppedExp=0}
    }
    @EventHandler(priority=EventPriority.HIGHEST) fun transform(e:EntityTransformEvent) {if(protected(e.entity.world))e.isCancelled=true}
    @EventHandler(priority=EventPriority.LOWEST) fun playerDied(e:PlayerDeathEvent) {
        if(mine.binding(e.entity.uniqueId)==null || !protected(e.entity.world))return
        e.keepInventory=true;e.keepLevel=true;e.drops.clear();e.droppedExp=0
        mine.dead(e.entity)
    }
    @EventHandler(priority=EventPriority.HIGHEST) fun respawn(e:PlayerRespawnEvent) {
        if(!mine.ownsRespawn(e.player))return
        val run=mine.binding(e.player.uniqueId)?:return
        // Even a terminal run retains its safe floor until this body leaves. The entity watch then
        // returns it to a newly validated hub, without respawning over an edited old entry point.
        mine.worlds.world(run)?.let {e.respawnLocation=mine.location(it,MineLayout.checkpoint(run))}
        mine.watch(e.player)
    }
    @EventHandler fun afterRespawn(e:com.destroystokyo.paper.event.player.PlayerPostRespawnEvent) {if(mine.ownsRespawn(e.player))FoliaTasks.entity(mine.plugin,e.player) {mine.respawned(e.player)}}
    @EventHandler(priority=EventPriority.MONITOR) fun quit(e:PlayerQuitEvent) {mine.quit(e.player)}
    @EventHandler(priority=EventPriority.MONITOR) fun join(e:PlayerJoinEvent) {FoliaTasks.entity(mine.plugin,e.player) {if(mine.binding(e.player.uniqueId)!=null)mine.joined(e.player);mine.arrived(e.player)}}
    @EventHandler(priority=EventPriority.MONITOR) fun changedWorld(e:PlayerChangedWorldEvent) {FoliaTasks.entity(mine.plugin,e.player) {mine.arrived(e.player)}}
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true) fun target(e:EntityTargetLivingEntityEvent) {
        if(!protected(e.entity.world))return
        val p=e.target as? Player?:return
        val run=mine.binding(p.uniqueId)
        if(run==null || run.world!=e.entity.world.key().asString() || run.members[p.uniqueId]?.let {it.inWorld && it.life==MineLife.ALIVE}!=true)e.isCancelled=true
    }
    /** A player the game has not seen, or one whose OneBlock data was wiped, spawns straight in the hub: no teleport, so nothing can be left half done. An unavailable hub keeps the vanilla spawn. */
    private fun firstArrival(e:AsyncPlayerSpawnLocationEvent,actor:java.util.UUID) {
        if(!e.isNewPlayer && (!mine.storage.healthy || mine.players.find(actor)!=null))return
        try {e.spawnLocation=mine.safeHubDestination().get(5,java.util.concurrent.TimeUnit.SECONDS)}
        catch(_:Exception) {}
    }
    @EventHandler(priority=EventPriority.HIGHEST) fun spawning(e:AsyncPlayerSpawnLocationEvent) {
        val actor=e.connection.profile.id?:return
        val run=mine.binding(actor)?:return firstArrival(e,actor)
        // This event also fires on in-game reconfiguration/respawn. Only a matching, one-use
        // pre-login connection permit authorizes a login return; never redirect an active respawn.
        if(logins.remove(LoginKey(actor,e.connection.address))==null)return
        try {e.spawnLocation=mine.safeHubDestination().get(5,java.util.concurrent.TimeUnit.SECONDS)}
        catch(_:Exception) {e.connection.disconnect(com.alytanta.oneblock.util.Colors.colorize(run.settings.text("return-unavailable")))}
    }
    @EventHandler(priority=EventPriority.HIGHEST) fun login(e:AsyncPlayerPreLoginEvent) {
        val run=mine.binding(e.uniqueId)?:return
        val now=System.currentTimeMillis()
        logins.entries.removeIf {it.value.expires<now || !it.value.connection.isConnected}
        try {
            mine.safeHubDestination().get(5,java.util.concurrent.TimeUnit.SECONDS)
            logins[LoginKey(e.uniqueId,e.connection.address)]=LoginPermit(e.connection,now+300000)
        }
        catch(_:Exception) {e.disallow(AsyncPlayerPreLoginEvent.Result.KICK_OTHER,com.alytanta.oneblock.util.Colors.colorize(run.settings.text("return-unavailable")))}
    }
    @EventHandler(priority=EventPriority.LOW,ignoreCancelled=false) fun interact(e:PlayerInteractEvent) {
        val p=e.player
        if(!protected(p.world))return
        if(e.item?.type in setOf(Material.ENDER_PEARL,Material.CHORUS_FRUIT,Material.WATER_BUCKET,Material.LAVA_BUCKET,Material.BUCKET,Material.FLINT_AND_STEEL,Material.FIRE_CHARGE,Material.BONE_MEAL,Material.TNT,Material.END_CRYSTAL)) {e.isCancelled=true;return}
        val block=e.clickedBlock?:return
        e.setUseInteractedBlock(Event.Result.DENY)
        if(e.hand!=EquipmentSlot.HAND || e.action!=Action.RIGHT_CLICK_BLOCK)return
        val run=mine.binding(p.uniqueId)?:return
        if(run.wave!=null)return
        if(run.world!=p.world.key().asString() || run.members[p.uniqueId]?.let {it.inWorld && it.life==MineLife.ALIVE}!=true)return
        if(block.y==65 && block.z==8 && block.x in listOf(48,52,56)) {
            e.setUseItemInHand(Event.Result.DENY)
            val lever=(block.x-48)/4+1
            input(run,p,{MineState.lever(it,p.uniqueId,lever)}) {next->FoliaTasks.entity(mine.plugin,p) {mine.say(p,"lever","step" to next.puzzleStep,"total" to next.settings.puzzle.size)}}
        } else if(block.x==54 && block.y==66 && block.z==31 && run.phase==MinePhase.PUZZLE) {
            input(run,p,{it.copy(secretOpen=true)})
        } else if(block.x==55 && block.y==65 && block.z==40 && run.secretOpen && run.phase==MinePhase.PUZZLE) {
            input(run,p,{it.copy(secret=true)}) {next->mine.tell(next,"secret")}
        }
    }
}
