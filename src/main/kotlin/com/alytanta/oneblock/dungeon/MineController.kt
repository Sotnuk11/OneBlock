package com.alytanta.oneblock.dungeon

import com.alytanta.oneblock.combat.*
import com.alytanta.oneblock.hub.HubService
import com.alytanta.oneblock.island.IslandService
import com.alytanta.oneblock.market.InventoryGuard
import com.alytanta.oneblock.menu.PlayerMenu
import com.alytanta.oneblock.player.PlayerService
import com.alytanta.oneblock.storage.PostgresStorageService
import com.alytanta.oneblock.util.*
import org.bukkit.*
import org.bukkit.command.*
import org.bukkit.entity.Player
import org.bukkit.plugin.java.JavaPlugin
import java.util.UUID
import java.util.concurrent.*

data class MineParty(val leader:UUID,val members:Set<UUID>,val ready:Set<UUID> = emptySet())
class MineController(val plugin:JavaPlugin,@Volatile var settings:MineSettings,val islands:IslandService,
    val players:PlayerService,val storage:PostgresStorageService,val hub:HubService,val menu:PlayerMenu,
    private val rewards:CombatRewardService,private val catalog:()->CombatRewardCatalog?,stats:CombatStatProvider) : CommandExecutor,AutoCloseable {
    val worlds=MineWorlds(plugin)
    val engine=MineEngine(MineJournal(plugin.dataFolder.toPath().resolve("mine-sessions")),::changed) {e->plugin.logger.log(java.util.logging.Level.WARNING,"Mine journal transition failed",e)}
    val encounters=MineEncounters(plugin,engine,worlds,stats,::tell)
    val transfers=ConcurrentHashMap.newKeySet<UUID>()
    private val builds=ConcurrentHashMap<UUID,CompletableFuture<World>>()
    private val cleanups=ConcurrentHashMap.newKeySet<UUID>()
    private val returning=ConcurrentHashMap.newKeySet<UUID>()
    private val departing=ConcurrentHashMap.newKeySet<UUID>()
    private val sending=ConcurrentHashMap.newKeySet<String>()
    private val retryAt=ConcurrentHashMap<String,Long>()
    private val nativeDeaths=ConcurrentHashMap<UUID,Player>()
    private val respawning=ConcurrentHashMap.newKeySet<UUID>()
    private val initialArrivals=ConcurrentHashMap<UUID,MutableSet<UUID>>()
    private val waveTasks=ConcurrentHashMap<UUID,Long>()
    private val recording=ConcurrentHashMap.newKeySet<UUID>()
    fun ownsRespawn(player:Player)=binding(player.uniqueId)?.let {s->s.world==player.world.key().asString() ||
        s.members[player.uniqueId]?.inWorld==true && nativeDeaths[player.uniqueId]===player}==true
    private val partyLock=Any()
    private val parties=ConcurrentHashMap<UUID,MineParty>()
    private val starting=ConcurrentHashMap.newKeySet<UUID>()
    private data class Invite(val leader:UUID,val expires:Long)
    private val invites=ConcurrentHashMap<UUID,Invite>()
    @Volatile private var closed=false
    fun initialize(loaded:List<MineRun>?=null) {
        engine.initialize(loaded)
        plugin.server.asyncScheduler.runAtFixedRate(plugin,{
            if(closed || !engine.ready.isDone || engine.ready.isCompletedExceptionally)return@runAtFixedRate
            engine.work().forEach {s->
                if(!s.terminal) {
                    val next=MineState.expire(s,System.currentTimeMillis())
                    if(next!=s)engine.update(s.id) {MineState.expire(it,System.currentTimeMillis())}
                }
                dispatchRewards(s);syncRecord(s)
                if(s.terminal)cleanup(s)
            }
        },1,1,TimeUnit.SECONDS)
    }
    fun binding(actor:UUID)=engine.binding(actor)
    fun engaged(actor:UUID)=actor in transfers || binding(actor)?.let {s->s.members[actor]?.let {m->
        m.inWorld || s.phase==MinePhase.PREPARING || m.returnPending && (s.terminal || m.life==MineLife.LEFT)
    }}==true
    fun profileLocked(actor:UUID)=binding(actor)?.let {!it.terminal || it.members[actor]?.returnPending==true}==true || engine.hasPendingReward(actor)
    fun managed(world:World)=world.key().namespace()=="oneblock_dungeons"
    fun member(player:Player):Pair<MineRun,MineMember>?=binding(player.uniqueId)?.let {s->s.members[player.uniqueId]?.let {s to it}}
    fun say(player:Player,key:String,vararg args:Pair<String,Any?>) {(binding(player.uniqueId)?.settings?:settings).text(key,*args).takeIf {it.isNotBlank()}?.let {player.sendMessage(Colors.colorize(it))}}
    fun tell(run:MineRun,key:String) {run.members.keys.forEach {id->plugin.server.getPlayer(id)?.let {p->FoliaTasks.entity(plugin,p) {
        p.sendMessage(Colors.colorize(run.settings.text(key,"deaths" to run.deaths,"attempts" to run.remaining,"reason" to run.reason,"order" to run.settings.puzzle.joinToString(" → "),
            "wave" to run.wave?.plan?.number,"completed" to run.wave?.completed,"limit" to run.settings.waves?.rewardLimit)))
    }}}}
    fun <T> entity(player:Player,action:()->T)=CompletableFuture<T>().also {done->
        if(!player.scheduler.execute(plugin,{try {done.complete(action())}catch(e:Throwable){done.completeExceptionally(e)}},{done.completeExceptionally(IllegalStateException("Player unavailable"))},1))done.completeExceptionally(IllegalStateException("Player unavailable"))
    }
    fun location(world:World,p:MinePoint)=Location(world,p.x,p.y,p.z,p.yaw,0f)
    /** Explicit configured hub only. The login/configuration thread may await this; tick threads never do. */
    fun safeHubDestination():CompletableFuture<Location> = CompletableFuture<Location>().also {done->
        try {hub.resolve {destination->
            if(destination==null)done.completeExceptionally(IllegalStateException("Hub unavailable"))
            else worlds.safe(destination).whenComplete {safe,error->
                if(error!=null)done.completeExceptionally(error)
                else if(safe==true)done.complete(destination)
                else done.completeExceptionally(IllegalStateException("Hub destination unsafe"))
            }
        }} catch(e:Throwable) {done.completeExceptionally(e)}
    }
    private fun changed(before:MineRun?,run:MineRun) {
        if(closed)return
        syncRecord(run);dispatchRewards(run)
        if(run.phase==MinePhase.PREPARING && !builds.containsKey(run.id)) {
            val flow=worlds.prepare(run) {engine.get(run.id)?.let {!it.terminal && it.epoch==run.epoch}==true}
            builds[run.id]=flow
            flow.whenComplete {_,error->
                if(error!=null) {plugin.logger.log(java.util.logging.Level.WARNING,"Mine ${run.id} preparation failed",error);engine.abort(run.id,"preparation-failed")}
                else engine.event(run.id,run.epoch) {it.copy(worldCreated=true,phase=if(it.wave!=null)MinePhase.TRANSITION else MinePhase.ENTRY)}
            }
            return
        }
        if(run.terminal) {
            waveTasks.remove(run.id);initialArrivals.remove(run.id)
            encounters.clear(run.id)
            if(before?.terminal!=true)tell(run,if(run.phase==MinePhase.WON)"won" else "aborted")
            dispatchRewards(run);cleanup(run);return
        }
        if(run.wave!=null) {waveChanged(before,run);return}
        if(before?.phase==MinePhase.PREPARING && run.phase==MinePhase.ENTRY) {
            run.members.values.forEach {m->plugin.server.getPlayer(m.actor)?.let {p->enter(p,run,false)}?:engine.abort(run.id,"player-left-before-entry")}
        }
        if(before?.epoch!=run.epoch || before.phase!=run.phase || before.secretOpen!=run.secretOpen) {
            worlds.gates(run) {engine.get(run.id)?.let {!it.terminal && it.epoch==run.epoch && it.phase==run.phase && it.secretOpen==run.secretOpen}==true};encounters.spawn(run)
            if(before?.epoch!=run.epoch && before?.phase!=MinePhase.PREPARING) {
                tell(run,if(before?.wipes!=run.wipes)"wipe" else run.phase.name.lowercase())
                run.members.values.filter {it.inWorld && it.life==MineLife.ALIVE}.forEach {m->plugin.server.getPlayer(m.actor)?.let {p->entity(p) {
                    if(p.isDead || p.uniqueId in transfers)return@entity
                    val world=worlds.world(run)?:return@entity
                    if(p.gameMode==GameMode.SPECTATOR) {p.gameMode=GameMode.valueOf(m.mode);p.health=m.entryHealth.coerceAtMost(p.getAttribute(org.bukkit.attribute.Attribute.MAX_HEALTH)!!.value)}
                    transfers.add(m.actor);p.teleportAsync(location(world,MineLayout.checkpoint(run))).whenComplete {_,_->transfers.remove(m.actor)}
                }}}
            }
        }
        if(before?.deaths!=run.deaths)tell(run,"death")
        run.members.values.filter {it.life==MineLife.LEFT && it.returnPending && before?.members?.get(it.actor)?.life!=MineLife.LEFT}.forEach {m->
            plugin.server.getPlayer(m.actor)?.let {p->entity(p) {returnPlayer(p,run)}}
        }
        run.members.values.filter {it.inWorld && it.life==MineLife.DOWN && before?.members?.get(it.actor)?.life!=MineLife.DOWN}.forEach {m->
            plugin.server.getPlayer(m.actor)?.let {p->entity(p) {if(!p.isDead)p.gameMode=GameMode.SPECTATOR}}
        }
    }
    /** Title only for the best result of this group size; the rest stays in the normal finish message. */
    private fun announceRecord(run:MineRun) {
        storage.dungeonRecords(run.members.size).whenComplete {records,error->
            if(error!=null || records?.firstOrNull()?.run!=run.id)return@whenComplete
            run.members.keys.forEach {id->plugin.server.getPlayer(id)?.let {p->entity(p) {
                val text=(binding(id)?.settings?:settings).text("record-new","wave" to (run.wave?.completed?:0))
                p.showTitle(net.kyori.adventure.title.Title.title(Colors.colorize(text),net.kyori.adventure.text.Component.empty()))
                p.playSound(p.location,org.bukkit.Sound.UI_TOAST_CHALLENGE_COMPLETE,.7f,1f)
            }}}
        }
    }
    private val recordViews=ConcurrentHashMap<UUID,Pair<Int,Int>>()
    private fun openRecords(player:Player,size:Int,requested:Int) {
        storage.dungeonRecords(size,limit=1000).whenComplete {rows,error->entity(player) {
            if(error!=null) {say(player,"unavailable");return@entity}
            val best=WaveRecords.leaderboard(rows)
            val page=requested.coerceIn(0,(best.size-1).coerceAtLeast(0)/WaveRecords.PAGE)
            recordViews[player.uniqueId]=size to page
            val settings=binding(player.uniqueId)?.settings?:settings
            val entries=best.mapIndexed {index,r->
                val seconds=r.elapsed/1000
                index.toString() to com.alytanta.oneblock.menu.MenuItemSpec(when(index) {0->Material.GOLD_BLOCK;1->Material.IRON_BLOCK;2->Material.COPPER_BLOCK;else->Material.PAPER},
                    settings.text("record-name","rank" to index+1,"names" to r.participants.joinToString(", ") {it.nickname}),
                    listOf(settings.text("record-wave","wave" to r.wave),settings.text("record-time","time" to "%d:%02d".format(seconds/60,seconds%60))))
            }
            menu.page(player,"mine-records",entries,page,pick={})
        }}
    }
    private fun syncRecord(run:MineRun) {
        if(WaveRecords.synced(run) || !recording.add(run.id))return
        val stamp=WaveRecords.stamp(run)!!
        storage.saveDungeonRecord(WaveRecords.record(run)!!).whenComplete {_,error->
            if(error==null) {
                engine.update(run.id) {it.copy(recordAck=stamp)}.whenComplete {_,_->recording.remove(run.id)}
                if(run.terminal && (run.wave?.completed?:0)>0)announceRecord(run)
            }
            else recording.remove(run.id)
        }
    }
    private fun waveChanged(before:MineRun?,run:MineRun) {
        if(before?.phase==MinePhase.PREPARING && run.phase==MinePhase.TRANSITION) {
            run.members.keys.forEach {id->plugin.server.getPlayer(id)?.let {enter(it,run,false)}?:engine.abort(run.id,"player-left-before-entry")}
            return
        }
        run.members.values.filter {it.life==MineLife.LEFT && it.returnPending}.forEach {m->plugin.server.getPlayer(m.actor)?.let {p->entity(p) {returnPlayer(p,run)}}}
        run.members.values.filter {it.inWorld && it.life==MineLife.DOWN && before?.members?.get(it.actor)?.life!=MineLife.DOWN}.forEach {m->plugin.server.getPlayer(m.actor)?.let {p->entity(p) {if(!p.isDead)p.gameMode=GameMode.SPECTATOR}}}
        run.members.values.filter {it.inWorld && it.life==MineLife.ALIVE && before?.members?.get(it.actor)?.life==MineLife.DOWN}.forEach {m->plugin.server.getPlayer(m.actor)?.let {p->entity(p) {
            if(!p.isDead && !m.awaitingRespawn) {p.gameMode=GameMode.valueOf(m.mode);p.health=m.entryHealth.coerceAtMost(p.getAttribute(org.bukkit.attribute.Attribute.MAX_HEALTH)!!.value)}
        }}}
        if(before?.deaths!=run.deaths)tell(run,"death")
        if(before?.epoch==run.epoch && before.phase==run.phase)return
        when(run.phase) {
            MinePhase.TRANSITION->{
                if(before?.phase!=MinePhase.INTERMISSION)return
                afterArenaClear(run).thenCompose {moveGroup(run)}.whenComplete {_,error->
                    if(error!=null)engine.abort(run.id,"transition-teleport-failed")
                    else engine.event(run.id,run.epoch) {it.copy(phase=MinePhase.WAVE_WARNING)}
                }
            }
            MinePhase.WAVE_WARNING->{
                if(waveTasks.put(run.id,run.epoch)==run.epoch)return
                afterArenaClear(run).thenCompose {
                    if(before?.wipes!=run.wipes && before!=null)moveGroup(run) else CompletableFuture.completedFuture(Unit)
                }.thenCompose {encounters.warn(run)}.whenComplete {_,error->
                    if(error!=null) {engine.abort(run.id,"wave-prepare-failed");return@whenComplete}
                    tell(run,"wave-warning")
                    FoliaTasks.globalDelayed(plugin,run.settings.waves!!.warningTicks+run.settings.waves.pauseTicks) {
                        engine.event(run.id,run.epoch) {s->if(s.phase!=MinePhase.WAVE_WARNING)s else s.copy(phase=MinePhase.WAVE,wave=s.wave!!.copy(started=s.wave.started?:System.currentTimeMillis()))}
                    }
                }
            }
            MinePhase.WAVE->encounters.spawn(run)
            MinePhase.INTERMISSION->{encounters.clear(run.id);tell(run,"intermission")}
            else->Unit
        }
    }
    private fun afterArenaClear(run:MineRun)=encounters.clear(run.id).thenCompose {worlds.sweep(run) {engine.get(run.id)?.let {!it.terminal && it.epoch==run.epoch}==true}}
    private fun moveGroup(run:MineRun):CompletableFuture<Unit> {
        val tasks=run.members.values.filter {it.inWorld && it.life!=MineLife.LEFT && !it.awaitingRespawn}.map {m->
            val p=plugin.server.getPlayer(m.actor)?:return@map CompletableFuture.failedFuture<Boolean>(IllegalStateException("Player left during transition"))
            entity(p) {
                check(!p.isDead && transfers.add(m.actor))
                if(p.gameMode==GameMode.SPECTATOR) {p.gameMode=GameMode.valueOf(m.mode);p.health=m.entryHealth.coerceAtMost(p.getAttribute(org.bukkit.attribute.Attribute.MAX_HEALTH)!!.value)}
                p.teleportAsync(location(requireNotNull(worlds.world(run)),MineLayout.checkpoint(run)))
            }.thenCompose {it}.whenComplete {_,_->transfers.remove(m.actor)}.thenApply {check(it) {"Teleport refused"};true}
        }
        return CompletableFuture.allOf(*tasks.toTypedArray()).thenApply {Unit}
    }
    private fun enter(player:Player,run:MineRun,reconnect:Boolean) {
        if(!transfers.add(player.uniqueId))return
        entity(player) {
            require(!player.isDead && !InventoryGuard.busy(player.uniqueId) && player.itemOnCursor.isEmpty && !player.isInsideVehicle && islands.findForPlayer(player.uniqueId)?.owner==run.members.getValue(player.uniqueId).account)
            player.closeInventory()
            val change=if(reconnect)engine.update(run.id) {MineState.wipe(MineState.reconnect(it,player.uniqueId,System.currentTimeMillis()))}
                else engine.event(run.id,run.epoch) {s->val m=s.members.getValue(player.uniqueId);s.copy(members=s.members+(m.actor to m.copy(inWorld=true)))}
            change.whenComplete {next,error->
                if(error!=null) {transfers.remove(player.uniqueId);return@whenComplete}
                entity(player) {
                    val live=engine.get(run.id)?:return@entity
                    if(live.terminal) {transfers.remove(player.uniqueId);returnPlayer(player,live);return@entity}
                    require(!player.isDead && player.itemOnCursor.isEmpty)
                    val m=live.members.getValue(player.uniqueId);val world=worlds.world(live)?:error("Instance missing")
                    require(m.inWorld && m.life!=MineLife.LEFT && islands.findForPlayer(player.uniqueId)?.owner==m.account)
                    if(m.life==MineLife.DOWN) {player.gameMode=GameMode.SPECTATOR;player.health=minOf(player.health,m.entryHealth)}
                    else if(reconnect) {player.gameMode=GameMode.valueOf(m.mode);player.health=minOf(player.health,m.entryHealth)}
                    player.teleportAsync(location(world,MineLayout.checkpoint(live))).whenComplete {ok,e->
                        transfers.remove(player.uniqueId)
                        if(e!=null || ok!=true)entryFailed(run,player.uniqueId,reconnect,"teleport-failed")
                        else entity(player) {
                            watch(player);if(reconnect)say(player,"reconnected") else say(player,"entry")
                            if(!reconnect && run.wave!=null) {
                                val arrived=initialArrivals.computeIfAbsent(run.id) {ConcurrentHashMap.newKeySet()};arrived.add(player.uniqueId)
                                if(arrived.containsAll(run.members.keys))engine.event(run.id,run.epoch) {if(it.phase==MinePhase.TRANSITION)it.copy(phase=MinePhase.WAVE_WARNING) else it}
                            }
                        }
                    }
                }.exceptionally {entryFailed(run,player.uniqueId,reconnect,"entry-failed");null}
            }
        }.exceptionally {transfers.remove(player.uniqueId);if(!reconnect)engine.abort(run.id,"entry-failed") else entity(player) {say(player,"unavailable")};null}
    }
    private fun entryFailed(run:MineRun,actor:UUID,reconnect:Boolean,reason:String) {
        transfers.remove(actor)
        if(reconnect)engine.update(run.id) {MineState.reconnectFailed(it,actor,run.members.getValue(actor).deadline?:System.currentTimeMillis(),System.currentTimeMillis())}
        else engine.abort(run.id,reason)
    }
    private val watches=ConcurrentHashMap<UUID,io.papermc.paper.threadedregions.scheduler.ScheduledTask>()
    fun watch(player:Player) {
        watches.remove(player.uniqueId)?.cancel()
        val reference=java.util.concurrent.atomic.AtomicReference<io.papermc.paper.threadedregions.scheduler.ScheduledTask>()
        val task=player.scheduler.runAtFixedRate(plugin,{
            val s=binding(player.uniqueId)
            if(s==null) {watches.remove(player.uniqueId)?.cancel();return@runAtFixedRate}
            if(s.terminal) {returnPlayer(player,s);return@runAtFixedRate}
            val m=s.members.getValue(player.uniqueId)
            if(player.isDead || player.uniqueId in transfers)return@runAtFixedRate
            // Folia build8 does not dispatch PlayerPostRespawnEvent on its native respawn path.
            // Observe the actual alive body on its own scheduler, scoped to this recorded death.
            val physicalBody=nativeDeaths[player.uniqueId]===player
            if(m.inWorld && (physicalBody || m.awaitingRespawn && player.world.key().asString()==s.world)) {respawned(player);return@runAtFixedRate}
            if(m.inWorld && player.world.key().asString()!=s.world)depart(player,null)
            else if(player.world.key().asString()==s.world) {
                val p=player.location
                if(!MineLayout.inside(s,p.x,p.y,p.z)) {
                    val w=worlds.world(s)?:return@runAtFixedRate
                    transfers.add(player.uniqueId);player.teleportAsync(location(w,MineLayout.checkpoint(s))).whenComplete {_,_->transfers.remove(player.uniqueId)}
                }
            }
        },{reference.get()?.let {watches.remove(player.uniqueId,it)}},1,5)
        if(task!=null) {reference.set(task);watches[player.uniqueId]=task}
    }
    /** Raw Folia teleportAsync may bypass PlayerTeleportEvent cancellation. Check the arrival. */
    fun arrived(player:Player) {
        if(!managed(player.world))return
        val s=binding(player.uniqueId)
        if(s!=null && s.world==player.world.key().asString() && !s.terminal && (s.members[player.uniqueId]?.inWorld==true || player.uniqueId in transfers)) {watch(player);return}
        if(!returning.add(player.uniqueId))return
        hub.resolve {destination->
            if(destination==null) {returning.remove(player.uniqueId);return@resolve}
            worlds.safe(destination).whenComplete {safe,error->entity(player) {
                if(error!=null || safe!=true) {returning.remove(player.uniqueId);say(player,"return-unavailable");return@entity}
                player.teleportAsync(destination).whenComplete {_,_->returning.remove(player.uniqueId)}
            }}
        }
    }
    fun departed(player:Player,after:(()->Unit)?=null):Boolean {
        val s=binding(player.uniqueId)?:return false
        if(s.terminal)return false
        if(s.members[player.uniqueId]?.inWorld!=true)return false
        depart(player,after);return true
    }
    private fun depart(player:Player,after:(()->Unit)?) {
        val s=binding(player.uniqueId)?:return
        if(!departing.add(player.uniqueId))return
        val hp=if(player.isDead)0.0 else player.health
        engine.update(s.id) {live->
            val next=MineState.death(live,player.uniqueId,true,System.currentTimeMillis())
            val m=next.members.getValue(player.uniqueId)
            MineState.wipe(next.copy(members=next.members+(m.actor to m.copy(health=hp))))
        }.whenComplete {next,error->departing.remove(player.uniqueId);if(error==null)entity(player) {
            if(!player.isDead) {player.gameMode=GameMode.valueOf(next.members.getValue(player.uniqueId).mode);player.health=minOf(player.health,next.members.getValue(player.uniqueId).entryHealth)}
            say(player,"away","seconds" to next.settings.reconnectSeconds);after?.invoke()
        }}
    }
    fun dead(player:Player) {
        val s=binding(player.uniqueId)?:return
        if(nativeDeaths.putIfAbsent(player.uniqueId,player)!=null)return
        engine.update(s.id) {MineState.wipe(MineState.death(it,player.uniqueId,false,System.currentTimeMillis()))}
    }
    fun respawned(player:Player) {
        val s=binding(player.uniqueId)?:return
        if(player.isDead || !respawning.add(player.uniqueId))return
        player.gameMode=GameMode.SPECTATOR
        engine.update(s.id) {MineState.respawn(it,player.uniqueId)}.thenCompose {next->entity(player) {
            nativeDeaths.remove(player.uniqueId)
            val m=next.members.getValue(player.uniqueId)
            player.health=m.entryHealth.coerceAtMost(player.getAttribute(org.bukkit.attribute.Attribute.MAX_HEALTH)!!.value)
            if(next.terminal) {returnPlayer(player,next,true);return@entity}
            player.gameMode=if(m.life==MineLife.DOWN)GameMode.SPECTATOR else GameMode.valueOf(m.mode)
            worlds.world(next)?.let {w->transfers.add(player.uniqueId);player.teleportAsync(location(w,MineLayout.checkpoint(next))).whenComplete {_,_->transfers.remove(player.uniqueId)}}
            watch(player)
        }}.whenComplete {_,_->respawning.remove(player.uniqueId)}
    }
    fun joined(player:Player) {
        val s=binding(player.uniqueId)?:return
        if(s.terminal || s.members[player.uniqueId]?.life==MineLife.LEFT) returnPlayer(player,s)
        else watch(player) // Explicit /dungeon reconnect is required; no automatic healing or re-entry.
    }
    fun quit(player:Player) {
        watches.remove(player.uniqueId)?.cancel()
        nativeDeaths.remove(player.uniqueId)
        returning.remove(player.uniqueId);departing.remove(player.uniqueId);respawning.remove(player.uniqueId);transfers.remove(player.uniqueId)
        synchronized(partyLock) {
            invites.remove(player.uniqueId);invites.entries.removeIf {it.value.leader==player.uniqueId}
            party(player.uniqueId)?.let {p->if(p.leader==player.uniqueId)parties.remove(p.leader) else parties[p.leader]=p.copy(members=p.members-player.uniqueId,ready=emptySet())}
        }
        val s=binding(player.uniqueId)?:return
        if(s.phase==MinePhase.PREPARING)engine.abort(s.id,"preparation-player-left")
        else if(!s.terminal && s.members[player.uniqueId]?.inWorld==true)depart(player,null)
    }
    private fun dispatchRewards(run:MineRun) {
        run.rewards.filterKeys {it !in run.acknowledged}.forEach {(key,reward)->
            val token="${run.id}:$key"
            if(System.currentTimeMillis()<(retryAt[token]?:0) || !sending.add(token))return@forEach
            rewards.awardPrepared(reward).whenComplete {_,error->
                if(error==null)engine.update(run.id) {it.copy(acknowledged=it.acknowledged+key)}.whenComplete {_,_->sending.remove(token)}
                else {retryAt[token]=System.currentTimeMillis()+5000;sending.remove(token)}
            }
        }
    }
    private fun cleanup(run:MineRun) {
        if(!run.terminal)return
        run.members.values.filter {it.returnPending}.forEach {m->plugin.server.getPlayer(m.actor)?.let {p->entity(p) {returnPlayer(p,run)}}}
        if(run.worldRemoved || !cleanups.add(run.id))return
        (builds[run.id]?.handle {_,_->Unit}?:CompletableFuture.completedFuture(Unit)).thenCompose {
            worlds.global {
                val w=worlds.world(run)
                if(w!=null && w.players.isNotEmpty())CompletableFuture.failedFuture(IllegalStateException("Waiting for player evacuation"))
                else worlds.cleanup(run)
            }
        }.whenComplete {_,error->
            if(error==null)engine.update(run.id) {it.copy(worldRemoved=true,phase=MinePhase.CLOSED)}.whenComplete {_,_->cleanups.remove(run.id);builds.remove(run.id)}
            else cleanups.remove(run.id)
        }
    }
    fun returnPlayer(player:Player,run:MineRun) = returnPlayer(player,run,false)
    private fun returnPlayer(player:Player,run:MineRun,forceHub:Boolean) {
        if(run.members[player.uniqueId]?.returnPending==true && !watches.containsKey(player.uniqueId))watch(player)
        val member=run.members.getValue(player.uniqueId)
        if(!member.returnPending || player.uniqueId in transfers)return
        if(player.isDead) {
            // A terminal/explicitly left participant must not pin an instance by ignoring the death screen.
            if((run.terminal || member.life==MineLife.LEFT) && respawning.add(player.uniqueId)) {
                try {player.spigot().respawn();player.scheduler.runDelayed(plugin,{if(player.isDead)respawning.remove(player.uniqueId)},{},40)}
                catch(e:Exception) {respawning.remove(player.uniqueId);plugin.logger.log(java.util.logging.Level.WARNING,"Mine evacuation respawn failed",e)}
            }
            return
        }
        if(!returning.add(player.uniqueId))return
        fun finish() {
            nativeDeaths.remove(player.uniqueId);respawning.remove(player.uniqueId)
            player.gameMode=GameMode.valueOf(run.members.getValue(player.uniqueId).mode)
            player.health=minOf(player.health,run.members.getValue(player.uniqueId).entryHealth)
            engine.returned(run.id,player.uniqueId).whenComplete {_,_->returning.remove(player.uniqueId)}
        }
        if(!forceHub && !member.awaitingRespawn && nativeDeaths[player.uniqueId]!==player && player.world.key().asString()!=run.world && !managed(player.world)) {finish();return}
        hub.resolve {destination->
            if(destination==null) {returning.remove(player.uniqueId);return@resolve}
            worlds.safe(destination).whenComplete {safe,error->
                if(error!=null || safe!=true) {returning.remove(player.uniqueId);return@whenComplete}
                entity(player) {player.teleportAsync(destination).whenComplete {ok,e->
                    if(e==null && ok==true)entity(player) {finish()} else returning.remove(player.uniqueId)
                }}
            }
        }
    }
    fun sidebar(player:Player):List<String>?=binding(player.uniqueId)?.takeIf {!it.terminal}?.let {s->s.settings.sidebar.map {line->
        mapOf("name" to s.settings.name,"phase" to s.settings.text("phase-${s.phase.name.lowercase()}"),"deaths" to s.deaths,"attempts" to s.remaining,"drop" to (s.settings.tier(s.deaths).chance*100).toInt(),"secret" to s.settings.text(if(s.secret)"yes" else "no"),
            "wave" to (s.wave?.plan?.number?:0),"completed" to (s.wave?.completed?:0),"reward-limit" to (s.settings.waves?.rewardLimit?:0))
            .entries.fold(line) {text,(key,value)->text.replace("{$key}",value.toString())}
    }}
    private fun party(actor:UUID)=parties.values.firstOrNull {actor in it.members}
    fun availability(player:Player):String {
        val config=settings
        val reason=when {
            !player.hasPermission("oneblock.command")->"denied"
            !config.enabled || !config.allowWorlds->"disabled"
            !storage.healthy || !engine.ready.isDone || engine.ready.isCompletedExceptionally->"busy"
            islands.findForPlayer(player.uniqueId)?.releaseProgress==null->"need-profile"
            profileLocked(player.uniqueId)->"active-session"
            config.entry.world.isBlank()->"entry-missing"
            else-> {
                val world=plugin.server.getWorld(net.kyori.adventure.key.Key.key(config.entry.world))
                if(world==null || !hub.isHubWorld(world))"entry-missing" else "available"
            }
        }
        return config.text(reason)
    }
    fun lobbySummary(player:Player):String {
        val p=synchronized(partyLock) {party(player.uniqueId)}
        return settings.text("lobby-summary","leader" to (p?.leader?.let {players.find(it)?.lastKnownName?:it.toString().take(8)}?:player.name),
            "ready" to (p?.ready?.size?:0),"players" to (p?.members?.size?:1))
    }
    private fun partyInfo(player:Player) {
        val p=party(player.uniqueId)?:MineParty(player.uniqueId,setOf(player.uniqueId)).also {parties[it.leader]=it}
        say(player,"party","players" to p.members.joinToString {id->players.find(id)?.lastKnownName?:id.toString().take(8)},"ready" to "${p.ready.size}/${p.members.size}")
    }
    override fun onCommand(sender:CommandSender,command:Command,label:String,args:Array<out String>):Boolean {
        val player=sender as? Player?:return true
        if(!player.hasPermission("oneblock.command")) {say(player,"denied");return true}
        command(player,args.toList());return true
    }
    fun command(player:Player,args:List<String>) {
        if(!player.hasPermission("oneblock.command")) {say(player,"denied");return}
        try {
            when(args.firstOrNull()?.lowercase()) {
                "select"->menu.show(player,"mine-lobby")
                null,"menu"->{partyInfo(player);menu.show(player,"mine-lobby")}
                "guide"->say(player,"guide")
                "help"->say(player,"usage")
                "info"->{val s=binding(player.uniqueId);if(s==null)partyInfo(player) else say(player,"status","name" to s.settings.name,"phase" to s.phase,"deaths" to s.deaths,"attempts" to s.remaining,"world" to s.world)}
                "invite"->{require(args.size==2);check(!profileLocked(player.uniqueId));val target=plugin.server.getPlayerExact(args[1])?:error("requirements")
                    synchronized(partyLock) {val p=party(player.uniqueId)?:MineParty(player.uniqueId,setOf(player.uniqueId));val other=party(target.uniqueId);require(p.leader==player.uniqueId && p.members.size<4 && target.uniqueId !in p.members && (other==null || other.leader==target.uniqueId && other.members.size==1) && !profileLocked(target.uniqueId));parties[p.leader]=p;invites[target.uniqueId]=Invite(p.leader,System.currentTimeMillis()+60000)}
                    entity(target) {say(target,"invite","leader" to player.name)};say(player,"invited")}
                "accept"->{val invite=invites[player.uniqueId]?:error("requirements");require(System.currentTimeMillis()<=invite.expires)
                    require(args.size<=2 && (args.size==1 || plugin.server.getPlayerExact(args[1])?.uniqueId==invite.leader))
                    synchronized(partyLock) {val p=parties.getValue(invite.leader);val old=party(player.uniqueId);require(p.members.size<4 && (old==null || old.leader==player.uniqueId && old.members.size==1) && !profileLocked(player.uniqueId))
                        if(old!=null) {parties.remove(old.leader);invites.entries.removeIf {it.value.leader==old.leader}}
                        parties[p.leader]=p.copy(members=p.members+player.uniqueId,ready=emptySet());invites.remove(player.uniqueId,invite)};partyInfo(player)}
                "ready"->{require(!profileLocked(player.uniqueId));synchronized(partyLock) {val p=party(player.uniqueId)?:MineParty(player.uniqueId,setOf(player.uniqueId));parties[p.leader]=p.copy(ready=if(player.uniqueId in p.ready)p.ready-player.uniqueId else p.ready+player.uniqueId)};partyInfo(player)}
                "start"->start(player)
                "continue","finish"->{
                    val s=binding(player.uniqueId)?:error("requirements")
                    require(s.wave!=null && s.phase==MinePhase.INTERMISSION && s.members[player.uniqueId]?.let {it.inWorld && it.life==MineLife.ALIVE}==true)
                    // First serialized choice by an active participant applies to the frozen group.
                    if(args[0]=="finish")engine.event(s.id,s.epoch) {engine.win(it).copy(phase=MinePhase.WON,reason="cash-out",epoch=it.epoch+1)}
                    else engine.event(s.id,s.epoch,engine::continueRun)
                }
                "records"->{val size=(args.getOrNull(1)?.toIntOrNull()?:1).coerceIn(1,4);openRecords(player,size,0)}
                "records-next"->recordViews[player.uniqueId]?.let {openRecords(player,it.first,it.second+1)}
                "records-previous"->recordViews[player.uniqueId]?.let {openRecords(player,it.first,it.second-1)}
                "reconnect"->{val s=binding(player.uniqueId)?:error("expired");require(!s.terminal);enter(player,s,true)}
                "leave"->{val s=binding(player.uniqueId)
                    if(s!=null && !s.terminal)engine.update(s.id) {live->val m=live.members.getValue(player.uniqueId);val dead=MineState.death(live,player.uniqueId,true,System.currentTimeMillis());MineState.wipe(dead.copy(members=dead.members+(m.actor to dead.members.getValue(m.actor).copy(life=MineLife.LEFT))))}.thenAccept {next->entity(player) {returnPlayer(player,next)}}
                    else synchronized(partyLock) {party(player.uniqueId)?.let {p->if(p.leader==player.uniqueId)parties.remove(p.leader) else parties[p.leader]=p.copy(members=p.members-player.uniqueId,ready=emptySet())}}}
                else->say(player,"usage")
            }
            if(args.firstOrNull() in setOf("ready","leave") && menu.currentMenuId(player)=="mine-lobby")menu.show(player,"mine-lobby")
        } catch(e:Exception) {failure(player,e)}
    }
    private fun failure(player:Player,error:Throwable) {
        val known=setOf("expired","busy","disabled","need-party","need-leader","need-ready","need-profile","entry-missing","entry-near","entry-unsafe","active-session","need-mode")
        val reason=generateSequence(error) {it.cause}.mapNotNull {it.message}.firstOrNull {it in known}?:"requirements"
        say(player,reason)
    }
    private fun start(leader:Player) {
        if(!starting.add(leader.uniqueId)) {say(leader,"busy");return}
        try {startChecked(leader)}catch(e:Exception) {starting.remove(leader.uniqueId);throw e}
    }
    private fun startChecked(leader:Player) {
        val config=settings;require(config.enabled && config.allowWorlds) {"disabled"};check(storage.healthy) {"busy"}
        require(!profileLocked(leader.uniqueId)) {"active-session"}
        val party=synchronized(partyLock) {requireNotNull(party(leader.uniqueId)) {"need-party"}.also {
            require(it.leader==leader.uniqueId) {"need-leader"};require(it.ready==it.members) {"need-ready"}}}
        val entryWorld=config.entry.world.takeIf {it.isNotBlank()}?.let {plugin.server.getWorld(net.kyori.adventure.key.Key.key(it))}
        require(entryWorld!=null && hub.isHubWorld(entryWorld)) {"entry-missing"}
        val tasks=party.members.map {id->val p=plugin.server.getPlayer(id)?:error("requirements");entity(p) {
            require(!p.isDead && !InventoryGuard.busy(id) && !profileLocked(id) && p.itemOnCursor.isEmpty && !p.isInsideVehicle)
            require(p.gameMode in setOf(GameMode.SURVIVAL,GameMode.ADVENTURE)) {"need-mode"}
            require(hub.isHubWorld(p.world) && p.world.key().asString()==config.entry.world) {"entry-near"}
            val at=p.location;val entry=config.entry.point;require((at.x-entry.x)*(at.x-entry.x)+(at.y-entry.y)*(at.y-entry.y)+(at.z-entry.z)*(at.z-entry.z)<=config.entry.radius*config.entry.radius) {"entry-near"}
            val account=requireNotNull(islands.findForPlayer(id)) {"need-profile"};require(account.releaseProgress!=null) {"need-profile"}
            val profile=players.find(id);require(profile?.profileSwitch==null && profile?.eviction==null)
            val ledger=storage.marketState()
            require(ledger.transfers.values.none {it.actor==id && it.status in setOf(com.alytanta.oneblock.market.TransferStatus.PREPARED,com.alytanta.oneblock.market.TransferStatus.REVIEW)})
            require(!com.alytanta.oneblock.trade.PlayerTradeLedger.pending(ledger,id))
            MineMember(id,account.owner,p.name,p.health,p.health,p.gameMode.name)
        }}
        say(leader,"loading")
        CompletableFuture.allOf(*tasks.toTypedArray()).thenCompose {worlds.safe(location(entryWorld,config.entry.point))}.thenCompose {safe->check(safe) {"entry-unsafe"};synchronized(partyLock) {
            check(settings===config && parties[party.leader]===party) {"Settings or party changed"}
            engine.create(config,tasks.map {it.join()},requireNotNull(catalog()))
        }}.whenComplete {_,error->
            starting.remove(leader.uniqueId)
            if(error==null)synchronized(partyLock) {parties.remove(party.leader)} else entity(leader) {failure(leader,error)}
        }
    }
    override fun close() {
        closed=true;watches.values.forEach {it.cancel()};watches.clear()
        val pending=engine.all().filter {!it.terminal}.map {engine.abort(it.id,"shutdown")}
        CompletableFuture.allOf(*pending.toTypedArray()).get(20,TimeUnit.SECONDS)
        engine.close()
    }
}
