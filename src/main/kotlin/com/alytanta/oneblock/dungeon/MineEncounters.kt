package com.alytanta.oneblock.dungeon

import com.alytanta.oneblock.combat.CombatStatProvider
import com.alytanta.oneblock.util.FoliaTasks
import org.bukkit.*
import org.bukkit.damage.DamageSource
import org.bukkit.damage.DamageType
import org.bukkit.entity.*
import org.bukkit.plugin.Plugin
import java.util.UUID
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicInteger

data class MineMob(val run:UUID,val epoch:Long,val boss:Boolean,val entity:LivingEntity,val damage:Double?=null)
/** Native mobs/projectiles remain native. Only the guardian's two telegraphed attacks are scripted. */
class MineEncounters(private val plugin:Plugin,private val engine:MineEngine,private val worlds:MineWorlds,
    private val stats:CombatStatProvider,private val tell:(MineRun,String)->Unit) {
    val mobs=ConcurrentHashMap<UUID,MineMob>()
    private val started=ConcurrentHashMap<UUID,Long>()
    private val remaining=ConcurrentHashMap<Pair<UUID,Long>,AtomicInteger>()
    private fun live(run:UUID,epoch:Long)=engine.get(run)?.let {!it.terminal && it.epoch==epoch}==true
    fun clear(id:UUID):CompletableFuture<Unit> {
        val tasks=mobs.entries.filter {it.value.run==id}.mapNotNull {(uuid,m)->if(mobs.remove(uuid,m))CompletableFuture<Unit>().also {done->
            FoliaTasks.entity(plugin,m.entity,retired={done.complete(Unit)}) {try {m.entity.remove();done.complete(Unit)}catch(e:Throwable){done.completeExceptionally(e)}}
        } else null}
        started.remove(id);remaining.keys.removeIf {it.first==id}
        return CompletableFuture.allOf(*tasks.toTypedArray()).thenApply {Unit}
    }
    fun spawn(run:MineRun) {
        if(run.wave!=null) {spawnWave(run);return}
        if(run.phase !in setOf(MinePhase.ENTRY,MinePhase.BOSS) || started.put(run.id,run.epoch)==run.epoch)return
        clearOld(run)
        val world=worlds.world(run)?:return
        val boss=run.phase==MinePhase.BOSS
        val count=if(boss)1 else run.settings.mobCount+(run.members.size-1)*run.settings.mobsPerExtra
        remaining[run.id to run.epoch]=AtomicInteger(count)
        repeat(count) {index->
            val x=if(boss)91.5 else 4.5+(index%7)*4
            val z=if(boss)16.5 else 3.5+(index/7)*3.5
            worlds.region(world,x.toInt() shr 4,z.toInt() shr 4) {
                if(!live(run.id,run.epoch))return@region
                val mob:LivingEntity=if(boss)world.spawn(Location(world,x,65.0,z),IronGolem::class.java) else if(index%3==0)world.spawn(Location(world,x,65.0,z),Skeleton::class.java) else world.spawn(Location(world,x,65.0,z),Zombie::class.java)
                mob.isPersistent=true;mob.isSilent=boss
                if(mob is Mob) mob.removeWhenFarAway=false
                if(mob is Zombie)mob.setShouldBurnInDay(false)
                mob.equipment?.let {e->e.setItemInMainHandDropChance(0f);e.setHelmetDropChance(0f);e.setChestplateDropChance(0f);e.setLeggingsDropChance(0f);e.setBootsDropChance(0f)}
                val health=(if(boss)run.settings.bossHealth else run.settings.mobHealth)*(1+(run.members.size-1)*run.settings.hpPerExtra)
                mob.getAttribute(org.bukkit.attribute.Attribute.MAX_HEALTH)?.let {attribute->
                    attribute.baseValue=health
                    check(kotlin.math.abs(attribute.value-health)<.001) {"Configured scaled mob health exceeds the server attribute range"}
                    mob.health=attribute.value
                }
                mobs[mob.uniqueId]=MineMob(run.id,run.epoch,boss,mob)
                if(boss) {mob.scoreboardTags.add("oneblock:control_resistant");mob.customName(com.alytanta.oneblock.util.Colors.colorize(run.settings.text("guardian-name")));mob.isCustomNameVisible=true;attack(run,mob,0)}
            }.exceptionally {plugin.logger.warning("Mine spawn failed: ${it.javaClass.simpleName}");engine.abort(run.id,"spawn-failed");null}
        }
    }
    private fun clearOld(run:MineRun) {
        mobs.entries.filter {it.value.run==run.id && it.value.epoch!=run.epoch}.forEach {(id,m)->if(mobs.remove(id,m))FoliaTasks.entity(plugin,m.entity) {m.entity.remove()}}
    }
    fun died(id:UUID) {
        val mob=mobs.remove(id)?:return
        if(!live(mob.run,mob.epoch))return
        if(remaining[mob.run to mob.epoch]?.decrementAndGet()!=0)return
        engine.event(mob.run,mob.epoch) {s->if(s.wave!=null)engine.completeWave(s) else if(mob.boss)engine.win(s) else MineState.cleared(s,MinePhase.ENTRY)}
    }
    fun warn(run:MineRun):CompletableFuture<Unit> {
        val plan=run.wave?.plan?:return CompletableFuture.completedFuture(Unit);val rules=run.settings.waves!!
        val world=worlds.world(run)?:return CompletableFuture.failedFuture(IllegalStateException("Instance missing"))
        val arena=rules.arena(plan)
        val tasks=arena.spawns.map {arena.point(it,plan.arena)}.map {p->worlds.region(world,p.x.toInt() shr 4,p.z.toInt() shr 4) {
            if(live(run.id,run.epoch))world.spawnParticle(Particle.DUST,p.x,p.y+.3,p.z,20,.5,.2,.5,Particle.DustOptions(Color.ORANGE,1.5f))
        }}
        return CompletableFuture.allOf(*tasks.toTypedArray()).thenApply {Unit}
    }
    private fun spawnWave(run:MineRun) {
        if(run.phase!=MinePhase.WAVE || started.put(run.id,run.epoch)==run.epoch)return
        val world=worlds.world(run)?:return
        val plan=run.wave!!.plan
        remaining[run.id to run.epoch]=AtomicInteger(plan.spawns.size)
        // Sequential placement also bounds work and prevents overlaps between mobs of this wave.
        var flow=CompletableFuture.completedFuture(Unit)
        plan.spawns.forEachIndexed {index,spawn->flow=flow.thenCompose {place(run,world,spawn,index,0)}}
        flow.exceptionally {plugin.logger.log(java.util.logging.Level.WARNING,"Wave ${plan.number} spawn failed in ${run.id}",it);engine.abort(run.id,"spawn-failed");null}
    }
    private fun place(run:MineRun,world:World,s:WaveSpawn,index:Int,attempt:Int):CompletableFuture<Unit> {
        if(!live(run.id,run.epoch))return CompletableFuture.completedFuture(Unit)
        val rules=run.settings.waves!!;val plan=run.wave!!.plan;val arena=rules.arena(plan)
        val candidates=arena.spawns.indices.map {arena.point(arena.spawns[(it+index)%arena.spawns.size],plan.arena)}
        fun tryPoint(offset:Int):CompletableFuture<Boolean> {
            if(offset>=candidates.size)return CompletableFuture.completedFuture(false)
            val p=candidates[offset]
            return worlds.region(world,p.x.toInt() shr 4,p.z.toInt() shr 4) {
                if(!live(run.id,run.epoch))return@region true
                // Fail closed if a participant belongs to another region: never read remote locations.
                val players=MineState.alive(engine.get(run.id)!!).mapNotNull {plugin.server.getPlayer(it.actor)}
                if(players.any {!Bukkit.isOwnedByCurrentRegion(it)})return@region false
                if(players.any {it.world==world && it.location.distanceSquared(Location(world,p.x,p.y,p.z))<rules.minDistance*rules.minDistance})return@region false
                // Full golem-sized footprint: every touched block must belong to this region.
                for(x in kotlin.math.floor(p.x-.75).toInt()..kotlin.math.floor(p.x+.75).toInt())for(z in kotlin.math.floor(p.z-.75).toInt()..kotlin.math.floor(p.z+.75).toInt()) {
                    if(!Bukkit.isOwnedByCurrentRegion(world,x shr 4,z shr 4))return@region false
                    if(!world.getBlockAt(x,64,z).type.isSolid)return@region false
                    for(y in 65..67)if(!world.getBlockAt(x,y,z).isEmpty)return@region false
                }
                // Check the local footprint against all entities owned by this region, including previous spawns.
                val at=Location(world,p.x,p.y,p.z)
                for(x in kotlin.math.floor(p.x-1.5).toInt()..kotlin.math.floor(p.x+1.5).toInt())for(z in kotlin.math.floor(p.z-1.5).toInt()..kotlin.math.floor(p.z+1.5).toInt())
                    if(!Bukkit.isOwnedByCurrentRegion(world,x shr 4,z shr 4))return@region false
                if(world.getNearbyEntities(at,1.5,3.0,1.5).any {it is LivingEntity && !it.isDead})return@region false
                val mob=when(s.enemy.type) {
                    "SKELETON"->world.spawn(at,Skeleton::class.java)
                    "HUSK"->world.spawn(at,Husk::class.java)
                    "STRAY"->world.spawn(at,Stray::class.java)
                    "IRON_GOLEM"->world.spawn(at,IronGolem::class.java)
                    else->world.spawn(at,Zombie::class.java)
                }
                // The server can refuse to add a spawned entity; counting it would keep the wave open forever.
                if(!mob.isValid)return@region false
                // Register immediately, so failure in attribute setup still leaves a removable owned entity.
                mobs[mob.uniqueId]=MineMob(run.id,run.epoch,plan.boss,mob,s.damage)
                // A mob removed without a death event (despawn, world edit) still counts, otherwise the wave never ends.
                val spawned=mob.uniqueId
                mob.scheduler.runAtFixedRate(plugin,{task->if(!mobs.containsKey(spawned))task.cancel()},{died(spawned)},20L,20L)
                if(!live(run.id,run.epoch)) {mobs.remove(mob.uniqueId);mob.remove();return@region true}
                mob.isPersistent=true;mob.removeWhenFarAway=false;mob.setCanPickupItems(false)
                if(mob is Zombie) {mob.setShouldBurnInDay(false);mob.setBaby(false)}
                fun attribute(key:org.bukkit.attribute.Attribute,value:Double) {
                    mob.getAttribute(key)?.let {a->
                        a.modifiers.toList().forEach(a::removeModifier)
                        a.baseValue=value;check(kotlin.math.abs(a.value-value)<.001) {"Server attribute range for ${s.enemy.id}/$key: ${a.value} vs $value"}
                    }
                }
                attribute(org.bukkit.attribute.Attribute.MAX_HEALTH,s.health);mob.health=s.health
                attribute(org.bukkit.attribute.Attribute.ATTACK_DAMAGE,s.damage);attribute(org.bukkit.attribute.Attribute.MOVEMENT_SPEED,s.speed)
                mob.customName(com.alytanta.oneblock.util.Colors.colorize(s.enemy.name));mob.isCustomNameVisible=true
                mob.equipment?.let {e->e.setItemInMainHandDropChance(0f);e.setItemInOffHandDropChance(0f);e.setHelmetDropChance(0f);e.setChestplateDropChance(0f);e.setLeggingsDropChance(0f);e.setBootsDropChance(0f)}
                if(plan.boss)mob.scoreboardTags.add("oneblock:control_resistant")
                if(s.enemy.ability=="guardian") {
                    if(mob !is IronGolem) mob.setAI(false)
                    attack(run,mob,0,s.damage)
                }
                true
            }.thenCompose {ok->if(ok)CompletableFuture.completedFuture(true) else tryPoint(offset+1)}
        }
        return tryPoint(0).thenCompose {ok->
            if(ok || !live(run.id,run.epoch))CompletableFuture.completedFuture(Unit)
            else if(attempt>=30)CompletableFuture.failedFuture(IllegalStateException("No safe warned spawn point"))
            else worlds.region(world,candidates[0].x.toInt() shr 4,candidates[0].z.toInt() shr 4,20) {Unit}.thenCompose {place(run,world,s,index,attempt+1)}
        }
    }
    private fun attack(run:MineRun,boss:LivingEntity,index:Int,waveDamage:Double?=null) {
        if(!live(run.id,run.epoch) || boss.isDead)return
        boss.scheduler.runDelayed(plugin,{
            if(!live(run.id,run.epoch) || boss.isDead)return@runDelayed
            val current=engine.get(run.id)?:return@runDelayed
            val targets=MineState.alive(current).mapNotNull {plugin.server.getPlayer(it.actor)}
            if(targets.isEmpty()) {attack(run,boss,index+1,waveDamage);return@runDelayed}
            val kind=index%2
            val center=boss.location.clone()
            // Target position is captured on its own scheduler; no remote inventory/location reads.
            val chosen=targets[index%targets.size]
            val dispatched=java.util.concurrent.atomic.AtomicBoolean()
            fun retry() {if(dispatched.compareAndSet(false,true))FoliaTasks.entity(plugin,boss) {attack(run,boss,index+1,waveDamage)}}
            FoliaTasks.entity(plugin,chosen,retired=::retry) {
                if(!live(run.id,run.epoch))return@entity
                if(chosen.isDead || chosen.world.key().asString()!=run.world) {retry();return@entity}
                val aim=chosen.location.clone()
                if(!dispatched.compareAndSet(false,true))return@entity
                FoliaTasks.entity(plugin,boss) {
                    if(!live(run.id,run.epoch))return@entity
                    val dx=aim.x-center.x;val dz=aim.z-center.z;val distance=kotlin.math.sqrt(dx*dx+dz*dz).coerceAtLeast(.01)
                    val nx=dx/distance;val nz=dz/distance
                    boss.setRotation(Math.toDegrees(kotlin.math.atan2(-nx,nz)).toFloat(),0f)
                    if(boss is Mob && chosen.isValid && !chosen.isDead && chosen.world == boss.world) boss.target=chosen
                    tell(current,if(kind==0)"cleave" else "area")
                    // Telegraph locations lie inside the arena; each chunk owns its particle emission.
                    val shape=MineAttackShape(kind,MinePoint(center.x,center.y,center.z),MinePoint(aim.x,aim.y,aim.z),run.settings.cleaveLength,run.settings.cleaveWidth,run.settings.areaRadius)
                    val appearance=run.settings.appearance
                    val arena=boss.world
                    val points=shape.boundary().filter {MineLayout.inside(run,it.x,65.0,it.z)}
                    fun emit(impact:Boolean) {
                        if(!appearance.particles)return
                        points.groupBy {kotlin.math.floor(it.x).toInt() shr 4 to (kotlin.math.floor(it.z).toInt() shr 4)}.forEach {(c,list)->worlds.region(arena,c.first,c.second) {
                            if(live(run.id,run.epoch))list.forEach {p->arena.spawnParticle(Particle.DUST,p.x,p.y,p.z,1,Particle.DustOptions(Color.fromRGB(if(impact)appearance.impactColor else appearance.color),1.25f))}
                        }}
                    }
                    fun sound(impact:Boolean) {
                        if(!appearance.sounds)return
                        targets.forEach {p->FoliaTasks.entity(plugin,p) {
                            if(live(run.id,run.epoch) && p.world.key().asString()==run.world)
                                p.playSound(p.location,if(impact)appearance.impactSound else appearance.warningSound,SoundCategory.HOSTILE,.65f,if(impact).8f else 1.2f)
                        }}
                    }
                    emit(false);sound(false)
                    // Finite pulses belong to this boss/epoch, never a permanent per-particle task.
                    var pulse=appearance.pulseTicks
                    while(appearance.particles && pulse<run.settings.warningTicks) {
                        boss.scheduler.runDelayed(plugin,{if(live(run.id,run.epoch) && !boss.isDead)emit(false)},{},pulse)
                        pulse+=appearance.pulseTicks
                    }
                    boss.scheduler.runDelayed(plugin,{
                        if(!live(run.id,run.epoch))return@runDelayed
                        emit(true);sound(true)
                        targets.forEach {p->FoliaTasks.entity(plugin,p) {
                            val s=engine.get(run.id)?:return@entity;val m=s.members[p.uniqueId]?:return@entity
                            if(!live(run.id,run.epoch) || m.life!=MineLife.ALIVE || !m.inWorld || p.isDead || p.world.key().asString()!=run.world)return@entity
                            val at=p.location
                            val inside=shape.contains(at.x,at.z)
                            if(inside) {
                                val base=waveDamage?:((if(kind==0)run.settings.cleaveDamage else run.settings.areaDamage)*(1+(run.members.size-1)*run.settings.damagePerExtra))
                                val defense=stats.snapshot(p)?.defense?:0.0
                                // Generic native MOB_ATTACK with an explicit armor bonus avoids cross-region source reads and double application.
                                p.damage(base*(1-defense),DamageSource.builder(DamageType.MOB_ATTACK).build())
                            }
                        }}
                        attack(run,boss,index+1,waveDamage)
                    },{},run.settings.warningTicks)
                }
            }
        },{},run.settings.attackTicks)
    }
}
