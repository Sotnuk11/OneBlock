package com.alytanta.oneblock.combat

import com.alytanta.oneblock.util.Colors
import com.alytanta.oneblock.util.FoliaTasks
import org.bukkit.*
import org.bukkit.configuration.ConfigurationSection
import org.bukkit.entity.*
import org.bukkit.event.*
import org.bukkit.event.entity.*
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.plugin.Plugin
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.*

data class DemoSettings(val health:Double=180.0,val damage:Double=8.0,val speed:Double=.20,val warning:Int=28,val recovery:Int=32,
    val length:Double=4.5,val width:Double=1.6,val vulnerable:Double=1.2,val lifetime:Int=2400,val leash:Double=18.0,
    val name:String="&cЛоматель",val warningText:String="&6Ломатель готує широкий удар!",val openingText:String="&aПромах! Час для контратаки.") {
    companion object {
        fun read(s:ConfigurationSection?):DemoSettings {
            if(s==null)return DemoSettings()
            require(s.getKeys(false).all {it in setOf("health","damage","speed","warning-ticks","recovery-ticks","length","width","vulnerability","lifetime-ticks","leash","name","warning","opening")}) {"Unknown combat demo key"}
            fun n(key:String,default:Double,min:Double,max:Double)=s.getDouble(key,default).also {require((!s.contains(key)||s.get(key) is Number)&&it.isFinite()&&it in min..max) {"demo.$key"}}
            fun i(key:String,default:Int,min:Int,max:Int)=n(key,default.toDouble(),min.toDouble(),max.toDouble()).also {require(it%1.0==0.0)}.toInt()
            fun t(key:String,default:String)=s.getString(key,default)!!.also {require(it.isNotBlank()&&it.length<=160)}
            return DemoSettings(n("health",180.0,20.0,2000.0),n("damage",8.0,.1,40.0),n("speed",.20,.05,.35),i("warning-ticks",28,15,100),i("recovery-ticks",32,15,100),
                n("length",4.5,2.0,6.0),n("width",1.6,.5,3.0),n("vulnerability",1.2,1.0,1.5),i("lifetime-ticks",2400,200,12000),n("leash",18.0,8.0,32.0),
                t("name","&cЛоматель"),t("warning","&6Ломатель готує широкий удар!"),t("opening","&aПромах! Час для контратаки."))
        }
    }
}
data class DemoCleave(val x:Double,val y:Double,val z:Double,val nx:Double,val nz:Double,val length:Double,val width:Double) {
    fun contains(px:Double,py:Double,pz:Double):Boolean {
        val dx=px-x;val dz=pz-z
        return abs(py-y)<=2.5 && dx*nx+dz*nz in 0.0..length && abs(dx*nz-dz*nx)<=width
    }
}

/** Explicit admin practice encounter; no rewards, inventory edits, world generation, or dungeon journals. */
class CombatDemo(private val plugin:Plugin,private val runtime:CombatRuntime):Listener,AutoCloseable {
    private class Run(val player:Player,val mob:Zombie,val rules:CombatRules,val origin:Location) {
        var tick=0;var windupUntil=0;var recoverUntil=0;var shape:DemoCleave?=null;var exposed=false;var striking=false
    }
    private val runs=ConcurrentHashMap<UUID,Run>()
    fun start(player:Player) {
        check(Bukkit.isOwnedByCurrentRegion(player))
        stop(player.uniqueId)
        val rules=runtime.rules()?:error("Combat unavailable")
        require(runtime.usable(player) && player.gameMode in setOf(GameMode.SURVIVAL,GameMode.ADVENTURE)) {"Use Survival/Adventure after completing inventory transfers"}
        val direction=player.location.direction.setY(0).normalize()
        val at=player.location.clone().add(direction.multiply(6.0))
        require(runtime.techniques.owned(at,8.0)) {"Practice area crosses an unowned Folia region; choose an open loaded area"}
        require(at.block.isPassable && at.clone().add(0.0,1.0,0.0).block.isPassable) {"Practice spawn is obstructed"}
        val mob=at.world.spawn(at,Zombie::class.java) {m->
            m.setAI(true);m.isPersistent=false;m.removeWhenFarAway=false;m.isSilent=true;m.setShouldBurnInDay(false)
            m.getAttribute(org.bukkit.attribute.Attribute.MOVEMENT_SPEED)!!.baseValue=rules.demo.speed
            m.equipment.clear();m.canPickupItems=false
            m.getAttribute(org.bukkit.attribute.Attribute.MAX_HEALTH)!!.baseValue=rules.demo.health;m.health=rules.demo.health
            m.customName(Colors.colorize(rules.demo.name));m.isCustomNameVisible=true
            m.scoreboardTags.add("oneblock:control_resistant")
        }
        check(mob.isValid) {"Mob spawning is blocked in this area"}
        val run=Run(player,mob,rules,at.clone());runs[player.uniqueId]=run
        mob.scheduler.runAtFixedRate(plugin,{task->
            if(runs[player.uniqueId]!==run || runtime.rules()!==rules || !mob.isValid || mob.isDead || !Bukkit.isOwnedByCurrentRegion(player)) {
                task.cancel();runs.remove(player.uniqueId,run);if(mob.isValid)mob.remove();return@runAtFixedRate
            }
            val p=run.player;val s=rules.demo;run.tick+=2
            if(!p.isOnline || p.isDead || p.world!==mob.world || run.tick>s.lifetime || p.location.distanceSquared(run.origin)>s.leash*s.leash) {
                runs.remove(p.uniqueId,run);mob.remove();task.cancel();return@runAtFixedRate
            }
            if(!runtime.usable(p)) {mob.velocity=org.bukkit.util.Vector();return@runAtFixedRate}
            val atMob=mob.location;val atPlayer=p.location
            if(run.shape!=null) {
                val shape=run.shape!!
                if(run.tick<run.windupUntil) {
                    if(run.tick%4==0)for(side in listOf(-s.width,s.width))for(i in 0..8) {
                        val pos=Location(mob.world,shape.x+shape.nx*s.length*i/8-shape.nz*side,shape.y+.1,shape.z+shape.nz*s.length*i/8+shape.nx*side)
                        if(Bukkit.isOwnedByCurrentRegion(pos))pos.world.spawnParticle(Particle.FLAME,pos,1,0.0,0.0,0.0,0.0)
                    }
                } else {
                    val hit=shape.contains(atPlayer.x,atPlayer.y,atPlayer.z)&&runtime.techniques.visible(mob.eyeLocation,p.eyeLocation)
                    if(hit) {run.striking=true;try {p.damage(s.damage,mob)} finally {run.striking=false}}
                    run.exposed=!hit;run.recoverUntil=run.tick+s.recovery;run.shape=null
                    if(!hit)p.sendActionBar(Colors.colorize(s.openingText))
                }
                return@runAtFixedRate
            }
            if(run.tick<run.recoverUntil)return@runAtFixedRate
            run.exposed=false
            val vector=atPlayer.toVector().subtract(atMob.toVector()).setY(0)
            if(vector.lengthSquared()<=(s.length-.25).pow(2)) {
                val direction=if(vector.lengthSquared()<.01)org.bukkit.util.Vector(0,0,1) else vector.normalize()
                run.shape=DemoCleave(atMob.x,atMob.y,atMob.z,direction.x,direction.z,s.length,s.width)
                run.windupUntil=run.tick+s.warning;mob.setAI(false);mob.velocity=org.bukkit.util.Vector()
                mob.setRotation(Math.toDegrees(atan2(-direction.x,direction.z)).toFloat(),0f)
                p.sendActionBar(Colors.colorize(s.warningText));p.playSound(p.location,Sound.BLOCK_NOTE_BLOCK_HAT,.7f,.8f)
            } else {mob.setAI(true);mob.target=p}
        },{runs.remove(player.uniqueId,run)},2,2)
    }
    fun stop(actor:UUID) {runs.remove(actor)?.let {r->FoliaTasks.entity(plugin,r.mob) {r.mob.remove()}}}
    @EventHandler(priority=EventPriority.HIGH,ignoreCancelled=true) fun damage(event:EntityDamageByEntityEvent) {
        runs.values.firstOrNull {it.mob===event.damager}?.let {if(!it.striking)event.isCancelled=true;return}
        val run=runs.values.firstOrNull {it.mob===event.entity}?:return
        val actor=(event.damager as? Player)?:((event.damager as? Projectile)?.shooter as? Player)
        if(actor?.uniqueId!=run.player.uniqueId) {event.isCancelled=true;return}
        if(run.exposed && run.tick<run.recoverUntil)event.damage*=run.rules.demo.vulnerable
    }
    @EventHandler fun death(event:EntityDeathEvent) {
        val run=runs.values.firstOrNull {it.mob===event.entity}?:return
        event.drops.clear();event.droppedExp=0;runs.remove(run.player.uniqueId,run)
    }
    @EventHandler fun quit(event:PlayerQuitEvent)=stop(event.player.uniqueId)
    override fun close() {runs.keys.toList().forEach(::stop)}
}
