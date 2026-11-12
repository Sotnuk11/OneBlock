package com.alytanta.oneblock.combat

import com.alytanta.oneblock.items.CustomItems
import com.alytanta.oneblock.items.ItemAbility
import org.bukkit.*
import org.bukkit.entity.*
import org.bukkit.event.*
import org.bukkit.event.entity.*
import org.bukkit.inventory.ItemStack
import org.bukkit.plugin.Plugin
import org.bukkit.util.Vector
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.pow

/** Visible vanilla carrier; its native damage/explosion is always suppressed. */
class WindSpells(private val plugin:Plugin,private val runtime:CombatRuntime,private val techniques:CombatTechniques):Listener {
    companion object {
        val types=setOf("aimed_lightning","spark_stream","frost_burst")
        private val marker=NamespacedKey("one-block","combat_wind")
        fun marked(entity:Entity)=entity is WindCharge && entity.persistentDataContainer.has(marker,org.bukkit.persistence.PersistentDataType.BYTE)
        fun wand(stack:ItemStack?)=CustomItems.resolve(stack)?.abilities.orEmpty().any {it.type in types}
    }
    private data class Flight(val actor:UUID,val account:UUID,val rules:CombatRules,val ability:ItemAbility,val amount:Double,val origin:Location,val range:Double)
    private val flights=ConcurrentHashMap<UUID,Flight>()
    fun launch(player:Player,rules:CombatRules,account:UUID,item:CombatItem,ability:ItemAbility,amount:Double):Boolean {
        if(flights.size>=4096)return false
        val origin=player.eyeLocation.clone();val speed=ability.number("projectile-speed",item.definition?.stats?.get("projectile-speed")?:item.speed).coerceIn(.5,4.0)
        val direction=origin.direction.multiply(speed)
        var charge:WindCharge?=null
        return try {
            val projectile=player.launchProjectile(WindCharge::class.java,direction) {
                charge=it;it.shooter=player;it.isPersistent=false;it.setIsIncendiary(false);it.yield=0f
                it.acceleration=Vector();it.velocity=direction
                it.persistentDataContainer.set(marker,org.bukkit.persistence.PersistentDataType.BYTE,1)
                flights[it.uniqueId]=Flight(player.uniqueId,account,rules,ability,amount,origin,ability.number("range"))
            }
            if(!projectile.isValid) {flights.remove(projectile.uniqueId);false} else {
                var ticks=0
                projectile.scheduler.runAtFixedRate(plugin,{task->
                    val flight=flights[projectile.uniqueId]
                    if(flight==null || !projectile.isValid || runtime.rules()!==rules || ++ticks>100 || projectile.world!==origin.world || projectile.location.distanceSquared(origin)>flight.range*flight.range) {
                        flights.remove(projectile.uniqueId);projectile.remove();task.cancel()
                    } else projectile.world.spawnParticle(techniques.particle(ability,Particle.ELECTRIC_SPARK),projectile.location,2,.04,.04,.04,0.0)
                },{flights.remove(projectile.uniqueId)},1,1)
                player.swingMainHand();ability.sound?.let {player.world.playSound(player.location,it,.25f,1.4f)};true
            }
        } catch(e:Exception) {
            charge?.let {flights.remove(it.uniqueId);it.remove()}
            plugin.logger.warning("Wind spell launch failed: ${e.javaClass.simpleName}");false
        }
    }
    @EventHandler(priority=EventPriority.HIGHEST)
    fun hit(event:ProjectileHitEvent) {
        val projectile=event.entity
        if(!marked(projectile))return
        val allowed=!event.isCancelled;event.isCancelled=true
        val flight=flights.remove(projectile.uniqueId)
        try {
            if(!allowed || flight==null || flight.rules!==runtime.rules())return
            val player=projectile.shooter as? Player?:return
            if(!Bukkit.isOwnedByCurrentRegion(player) || player.uniqueId!=flight.actor || !runtime.usable(player) || runtime.account(player)!=flight.account)return
            val victim=(event.hitEntity as? LivingEntity)?.takeIf {Bukkit.isOwnedByCurrentRegion(it)}
            val point=when {
                victim!=null->victim.eyeLocation.clone()
                event.hitBlock?.let {Bukkit.isOwnedByCurrentRegion(it)}==true->event.hitBlock!!.location.add(.5,.5,.5).add((event.hitBlockFace?.direction?:Vector()).multiply(.51))
                else->projectile.location.clone()
            }
            if(point.world!==flight.origin.world || point.distanceSquared(flight.origin)>(flight.range+.75).pow(2))return
            point.world.spawnParticle(techniques.particle(flight.ability,Particle.ELECTRIC_SPARK),point,10,.2,.2,.2,0.0)
            flight.ability.sound?.let {point.world.playSound(point,it,.4f,1.3f)}
            impact(player,flight,point,victim)
        } finally {projectile.remove()}
    }
    private fun impact(player:Player,flight:Flight,point:Location,first:LivingEntity?) {
        val ability=flight.ability
        if(ability.type=="frost_burst") {
            techniques.nearby(point,ability.number("radius")).filter {it.uniqueId!=player.uniqueId}.take(ability.number("targets").toInt()).forEach {victim->
                if(techniques.visible(point,victim.eyeLocation) && techniques.hurt(player,victim,flight.amount))techniques.slow(victim,ability,flight.rules)
            }
        } else if(first!=null && first.uniqueId!=player.uniqueId && techniques.target(first)) {
            if(ability.type=="spark_stream") {techniques.hurt(player,first,flight.amount);return}
            val visited=mutableSetOf(player.uniqueId);var victim:LivingEntity?=first;var from=point
            repeat(ability.number("jumps").toInt()+1) {index->
                val mob=victim?:return@repeat
                if(!visited.add(mob.uniqueId)) {victim=null;return@repeat}
                val at=mob.eyeLocation.clone()
                if(techniques.hurt(player,mob,flight.amount*ability.number("falloff").pow(index))) {
                    techniques.beam(from,at,techniques.particle(ability,Particle.ELECTRIC_SPARK));from=at
                    victim=techniques.nearby(at,ability.number("jump-range")).firstOrNull {it.uniqueId !in visited && techniques.visible(at,it.eyeLocation)}
                } else victim=null
            }
        }
    }
    @EventHandler(priority=EventPriority.HIGHEST) fun prime(e:ExplosionPrimeEvent) {if(marked(e.entity)) {e.isCancelled=true;e.radius=0f;e.fire=false}}
    @EventHandler(priority=EventPriority.HIGHEST) fun explode(e:EntityExplodeEvent) {if(marked(e.entity)) {e.isCancelled=true;e.blockList().clear()}}
    @EventHandler(priority=EventPriority.HIGHEST) fun damage(e:EntityDamageByEntityEvent) {if(marked(e.damager) || e.damageSource.directEntity?.let(::marked)==true)e.isCancelled=true}
    @EventHandler(priority=EventPriority.HIGHEST) fun ignite(e:org.bukkit.event.block.BlockIgniteEvent) {if(e.ignitingEntity?.let(::marked)==true)e.isCancelled=true}
}
