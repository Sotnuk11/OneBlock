package com.alytanta.oneblock.combat

import com.alytanta.oneblock.items.*
import org.bukkit.*
import org.bukkit.entity.*
import org.bukkit.event.*
import org.bukkit.event.entity.*
import org.bukkit.inventory.EquipmentSlot
import org.bukkit.plugin.Plugin
import org.bukkit.util.BoundingBox
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.*

/** Bounded region-owned attacks. Every target gets a native cancellable, player-attributed damage event. */
class CombatTechniques(private val plugin:Plugin,private val runtime:CombatRuntime) : Listener {
    val wind=WindSpells(plugin,runtime,this)
    private class Frame(val actor:Player,val victim:LivingEntity) {var accepted=false}
    private val frame=ThreadLocal<Frame?>()
    fun scripted()=frame.get()!=null
    private data class Shot(val actor:UUID,val account:UUID,val item:String,val effects:List<ItemAbility>,val expires:Long,val force:Float,val rules:CombatRules,val focusExtra:Double,val volley:UUID)
    private data class Volley(val account:UUID,val item:String,val tick:Int,val id:UUID)
    private val volleys=ConcurrentHashMap<UUID,Volley>()
    private val detonations=ConcurrentHashMap<UUID,Long>()
    private data class SequenceKey(val actor:UUID,val account:UUID,val item:String,val ability:ItemAbility)
    private data class Pending(val key:SequenceKey,val sequence:HitSequence,val reset:Boolean,val volley:UUID?=null)
    private val sequenceVolleys=ConcurrentHashMap<SequenceKey,UUID>()
    private val sequences=ConcurrentHashMap<SequenceKey,HitSequence>()
    private val pending=java.util.Collections.synchronizedMap(java.util.WeakHashMap<EntityDamageByEntityEvent,List<Pending>>())
    private val modified=java.util.Collections.synchronizedMap(java.util.WeakHashMap<EntityDamageByEntityEvent,Boolean>())
    private data class Channel(val id:UUID,val item:String,val slot:Int)
    private val channels=ConcurrentHashMap<UUID,Channel>()
    private val shots=ConcurrentHashMap<UUID,Shot>()
    private val marks=ConcurrentHashMap<Pair<UUID,UUID>,Long>()
    private val seen=java.util.Collections.synchronizedMap(java.util.WeakHashMap<EntityDamageByEntityEvent,Boolean>())
    private val fireSeen=java.util.Collections.synchronizedMap(java.util.WeakHashMap<EntityShootBowEvent,Boolean>())
    @EventHandler fun quit(event:org.bukkit.event.player.PlayerQuitEvent) {
        val actor=event.player.uniqueId;channels.remove(actor);volleys.remove(actor);sequences.keys.removeIf {it.actor==actor};sequenceVolleys.keys.removeIf {it.actor==actor};marks.keys.removeIf {it.first==actor};shots.entries.removeIf {it.value.actor==actor}
    }
    private fun clearSequences(actor:UUID) {sequences.keys.removeIf {it.actor==actor};sequenceVolleys.keys.removeIf {it.actor==actor}}
    @EventHandler fun held(event:org.bukkit.event.player.PlayerItemHeldEvent) {channels.remove(event.player.uniqueId);clearSequences(event.player.uniqueId)}
    @EventHandler fun swap(event:org.bukkit.event.player.PlayerSwapHandItemsEvent) {channels.remove(event.player.uniqueId);clearSequences(event.player.uniqueId)}
    @EventHandler fun slot(event:io.papermc.paper.event.player.PlayerInventorySlotChangeEvent) {
        val actor=event.player.uniqueId
        if(event.slot in setOf(event.player.inventory.heldItemSlot,40) && CombatItems.id(event.oldItemStack)!=CombatItems.id(event.newItemStack))clearSequences(actor)
        val channel=channels[actor]?:return
        if(event.slot==channel.slot && CombatItems.id(event.newItemStack)!=channel.item)channels.remove(actor,channel)
    }
    @EventHandler fun death(event:PlayerDeathEvent) {channels.remove(event.entity.uniqueId);clearSequences(event.entity.uniqueId)}

    /** Refuse at region boundaries rather than accessing another region's chunks/entities. Never loads chunks. */
    fun owned(center:Location,radius:Double):Boolean {
        val world=center.world?:return false
        for(x in (floor(center.x-radius-2).toInt() shr 4)..(floor(center.x+radius+2).toInt() shr 4))
            for(z in (floor(center.z-radius-2).toInt() shr 4)..(floor(center.z+radius+2).toInt() shr 4))
                if(!Bukkit.isOwnedByCurrentRegion(world,x,z))return false
        return true
    }
    fun target(entity:Entity)=entity is LivingEntity && entity !is ArmorStand && Bukkit.isOwnedByCurrentRegion(entity) && entity.isValid && !entity.isDead &&
        (entity !is Player || entity.gameMode in setOf(GameMode.SURVIVAL,GameMode.ADVENTURE) && runtime.playerDamageAllowed(entity)) &&
        !entity.hasMetadata("NPC") && !entity.hasMetadata("FancyNpcs") && entity.scoreboardTags.none {it.startsWith("fancynpcs",true)}
    fun visible(from:Location,to:Location):Boolean {
        if(from.world!==to.world)return false
        val vector=to.toVector().subtract(from.toVector());val distance=vector.length()
        if(distance<.01)return true
        if(!owned(from,distance))return false
        return from.world.rayTraceBlocks(from,vector.multiply(1/distance),distance,FluidCollisionMode.NEVER,true)==null
    }
    fun nearby(center:Location,radius:Double):List<LivingEntity> {
        if(!owned(center,radius))return emptyList()
        return center.world.getNearbyEntities(BoundingBox.of(center,radius,radius,radius)).filter(::target)
            .map {it as LivingEntity}.filter {it.location.distanceSquared(center)<=radius*radius}.sortedBy {it.location.distanceSquared(center)}.take(32)
    }
    fun aim(player:Player,range:Double):LivingEntity? {
        val origin=player.eyeLocation
        if(!owned(origin,range))return null
        val direction=origin.direction
        val wall=origin.world.rayTraceBlocks(origin,direction,range,FluidCollisionMode.NEVER,true)?.hitPosition?.distance(origin.toVector())?:range
        return nearby(origin,range).filter {it.uniqueId!=player.uniqueId}.mapNotNull {mob->mob.boundingBox.expand(.12).rayTrace(origin.toVector(),direction,wall)?.let {mob to it.hitPosition.distanceSquared(origin.toVector())}}
            .minByOrNull {it.second}?.first
    }
    fun hurt(player:Player,victim:LivingEntity,amount:Double):Boolean {
        if(!Bukkit.isOwnedByCurrentRegion(player) || !target(victim) || victim.uniqueId==player.uniqueId || !runtime.usable(player) || !visible(player.eyeLocation,victim.eyeLocation))return false
        val previous=frame.get();val attempt=Frame(player,victim);frame.set(attempt)
        try {victim.damage(amount,player)} finally {frame.set(previous)}
        return attempt.accepted
    }
    internal fun beam(from:Location,to:Location,particle:Particle) {
        if(!owned(from,from.distance(to)))return
        val difference=to.toVector().subtract(from.toVector());val count=(difference.length()*4).toInt().coerceIn(1,100)
        for(i in 0..count)from.world.spawnParticle(particle,from.clone().add(difference.clone().multiply(i.toDouble()/count)),1,0.0,0.0,0.0,0.0)
    }
    internal fun particle(ability:ItemAbility,fallback:Particle)=ability.particle?.let(Particle::valueOf)?:fallback
    internal fun slow(mob:LivingEntity,ability:ItemAbility,rules:CombatRules) {
        val boss=mob is Wither || mob is EnderDragon || "oneblock:control_resistant" in mob.scoreboardTags
        val ticks=(ability.number("duration-ticks")*if(boss)rules.bossControlScale else 1.0).toInt()
        if(ticks<1)return
        mob.addPotionEffect(org.bukkit.potion.PotionEffect(org.bukkit.potion.PotionEffectType.SLOWNESS,ticks,if(boss)0 else ability.number("amplifier").toInt(),false,true,true))
    }
    fun use(player:Player,rules:CombatRules,item:CombatItem,hand:EquipmentSlot,account:UUID):Boolean {
        val abilities=item.definition?.abilities.orEmpty().filter {it.type in setOf("aimed_lightning","wide_cleave","spark_stream","frost_burst")}
        if(abilities.isEmpty())return false
        val permitted=abilities.filter {runtime.permitted(player,it,hand)}
        if(permitted.isEmpty())player.sendActionBar(com.alytanta.oneblock.util.Colors.colorize(rules.messages["world-disabled"]?:"&7Ця здібність вимкнена для цього місця або руки."))
        for(ability in permitted) {
            if(ability.type=="wide_cleave" && !owned(player.eyeLocation,ability.number("range"))) {
                player.sendActionBar(com.alytanta.oneblock.util.Colors.colorize(rules.messages["region-unavailable"]?:"&7Здібність недоступна на межі завантаженої ділянки."));continue
            }
            if(ability.type=="wide_cleave") {
                if(player.attackCooldown<ability.number("charge-threshold")) {player.sendActionBar(com.alytanta.oneblock.util.Colors.colorize(rules.messages["charge"]?:"&7Дочекайся повної готовності удару."));continue}
                val origin=player.eyeLocation;val direction=origin.direction
                if(!owned(origin,ability.number("range")) || !runtime.pay(player,rules,account,ability))continue
                val held=if(hand==EquipmentSlot.HAND)player.inventory.itemInMainHand else player.inventory.itemInOffHand
                val sharpness=held.getEnchantmentLevel(org.bukkit.enchantments.Enchantment.SHARPNESS)
                val damage=(ability.number("damage")+if(sharpness>0).5*sharpness+.5 else 0.0)*runtime.snapshot(player,rules).meleeMultiplier
                for(mob in nearby(origin,ability.number("range")).filter {it.uniqueId!=player.uniqueId && it.eyeLocation.toVector().subtract(origin.toVector()).normalize().dot(direction)>=ability.number("cos-angle")}.take(ability.number("targets").toInt())) {
                    if(hurt(player,mob,damage))mob.world.spawnParticle(particle(ability,Particle.SWEEP_ATTACK),mob.eyeLocation,1)
                }
                if(hand==EquipmentSlot.HAND)player.swingMainHand() else player.swingOffHand()
                ability.sound?.let {origin.world.playSound(origin,it,.4f,1.2f)}
                continue
            }
            if(!runtime.pay(player,rules,account,ability))continue
            val multiplier=runtime.snapshot(player,rules).magicMultiplier
            if(ability.type=="spark_stream") {
                val count=ability.number("pulses").toInt()
                val channel=Channel(UUID.randomUUID(),item.id,if(hand==EquipmentSlot.HAND)player.inventory.heldItemSlot else 40);channels[player.uniqueId]=channel
                if(!wind.launch(player,rules,account,item,ability,ability.number("damage")/count*multiplier)) {
                    channels.remove(player.uniqueId,channel);runtime.refund(player,rules,account,ability);continue
                }
                if(count==1) {channels.remove(player.uniqueId,channel);runtime.spellFinished(player,rules,account,ability.number("mana-cost"))}
                for(index in 1 until count)player.scheduler.runDelayed(plugin,{
                    val held=if(hand==EquipmentSlot.HAND)player.inventory.itemInMainHand else player.inventory.itemInOffHand
                    if(channels[player.uniqueId]!==channel)return@runDelayed
                    if(runtime.rules()!==rules || !runtime.usable(player) || runtime.account(player)!=account || CombatItems.id(held)!=item.id) {channels.remove(player.uniqueId,channel);return@runDelayed}
                    wind.launch(player,rules,account,item,ability,ability.number("damage")/count*multiplier)
                    if(index==count-1) {channels.remove(player.uniqueId,channel);runtime.spellFinished(player,rules,account,ability.number("mana-cost"))}
                },{},index*ability.number("interval-ticks").toLong())
                continue
            }
            if(wind.launch(player,rules,account,item,ability,ability.number("damage")*multiplier))runtime.spellFinished(player,rules,account,ability.number("mana-cost"))
            else runtime.refund(player,rules,account,ability)
        }
        return true
    }
    @EventHandler(priority=EventPriority.MONITOR,ignoreCancelled=true)
    fun shot(event:EntityShootBowEvent) {
        if(fireSeen.put(event,true)!=null)return
        val player=event.entity as? Player?:return
        if(!Bukkit.isOwnedByCurrentRegion(player) || !runtime.usable(player))return
        val rules=runtime.rules()?:return
        val item=CombatItems.resolve(event.bow,rules)?:run {
            if(CustomItems.marked(event.bow) || event.bow?.type !in setOf(Material.BOW,Material.CROSSBOW))return
            val material=event.bow!!.type.name
            CombatItem("minecraft:${material.lowercase()}",material,CombatKind.RANGED,material,emptyList())
        }
        val abilities=item.definition?.abilities.orEmpty().filter {it.type in setOf("ember_mark","wind_slow","ranged_focus") && runtime.permitted(player,it,event.hand)}.toMutableList()
        val account=runtime.account(player)
        val now=System.nanoTime();shots.entries.removeIf {now-it.value.expires>=0};marks.entries.removeIf {now-it.value>=0};detonations.entries.removeIf {now-it.value>=0}
        val stats=runtime.snapshot(player,rules);val extra=if(stats.setProperty=="ranger_focus")stats.setStrength else 0.0
        if(extra>0 && abilities.none {it.type=="ranged_focus"})abilities+=ItemAbility("oneblock:set_focus","ranged_focus",ItemTrigger.SHOOT,ItemHand.EITHER,mapOf("step" to extra/4,"cap" to 0.0,"window-seconds" to 4.0),null,setOf(runtime.worldRole(player)),"ranged")
        val volley=if(item.material=="CROSSBOW")volleys.compute(player.uniqueId) {_,old->
            if(old!=null && old.account==account && old.item==item.id && old.tick==player.ticksLived)old else Volley(account,item.id,player.ticksLived,UUID.randomUUID())
        }!!.id else UUID.randomUUID()
        if(abilities.isNotEmpty() && shots.size<5000)shots[event.projectile.uniqueId]=Shot(player.uniqueId,account,item.id,abilities,now+10_000_000_000,event.force,rules,extra,volley)
    }
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true)
    fun modify(event:EntityDamageByEntityEvent) {
        if(scripted() || modified.put(event,true)!=null || !target(event.entity))return
        val player:Player;val account:UUID;val item:String;val abilities:List<ItemAbility>;val extra:Double
        val projectile=event.damager as? Projectile
        if(projectile!=null) {
            val record=shots[projectile.uniqueId]?:return
            if(record.rules!==runtime.rules() || System.nanoTime()-record.expires>=0)return
            player=projectile.shooter as? Player?:return;account=record.account;item=record.item;abilities=record.effects.filter {it.type=="ranged_focus"};extra=record.focusExtra
        } else {
            player=event.damager as? Player?:return
            if(!Bukkit.isOwnedByCurrentRegion(player) || !runtime.usable(player) || event.cause!=EntityDamageEvent.DamageCause.ENTITY_ATTACK)return
            val rules=runtime.rules()?:return
            val definition=CombatItems.resolve(player.inventory.itemInMainHand,rules)?:return
            account=runtime.account(player);item=definition.id;extra=0.0
            abilities=definition.definition?.effects("precise_slash").orEmpty().filter {runtime.permitted(player,it,EquipmentSlot.HAND)&&runtime.charge(event)>=it.number("charge-threshold")}
        }
        if(!Bukkit.isOwnedByCurrentRegion(player) || runtime.account(player)!=account)return
        val now=System.nanoTime();val victim=event.entity.uniqueId
        val changes=abilities.mapNotNull {ability->
            val key=SequenceKey(player.uniqueId,account,item,ability)
            val record=projectile?.uniqueId?.let(shots::get)
            if(record!=null && sequenceVolleys[key]==record.volley) {
                val old=sequences[key]
                if(old!=null && old.target==victim && now-old.time in 0..(ability.number("window-seconds")*1e9).toLong())
                    event.damage*=1+((old.hits-1)*ability.number("step")).coerceAtMost(ability.number("cap")+extra)
                return@mapNotNull null
            }
            val cap=if(ability.type=="precise_slash")ability.number("hits").toInt() else 100
            val next=HitSequence.next(sequences[key],victim,now,(ability.number("window-seconds")*1e9).toLong(),cap)
            val reset=ability.type=="precise_slash" && next.hits==cap
            if(reset)event.damage*=1+ability.number("bonus")
            else if(ability.type=="ranged_focus")event.damage*=1+((next.hits-1)*ability.number("step")).coerceAtMost(ability.number("cap")+extra)
            Pending(key,next,reset,record?.volley)
        }
        if(changes.isNotEmpty())pending[event]=changes
    }
    @EventHandler(priority=EventPriority.MONITOR)
    fun confirmed(event:EntityDamageByEntityEvent) {
        frame.get()?.let {attempt->
            if(event.damager===attempt.actor && event.entity===attempt.victim)attempt.accepted=!event.isCancelled && event.finalDamage>0
            return
        }
        if(seen.put(event,true)!=null)return
        val changes=pending.remove(event)
        if(event.isCancelled || event.finalDamage<=0)return
        changes?.forEach {change->
            sequences.entries.removeIf {it.key.actor==change.key.actor && it.key.ability.id==change.key.ability.id && it.key!=change.key}
            sequenceVolleys.keys.removeIf {it.actor==change.key.actor && it.ability.id==change.key.ability.id && it!=change.key}
            sequences[change.key]=if(change.reset)change.sequence.copy(hits=0) else change.sequence
            change.volley?.let {sequenceVolleys[change.key]=it}
            if(change.reset) {
                event.entity.world.spawnParticle(particle(change.key.ability,Particle.SWEEP_ATTACK),event.entity.location.add(0.0,1.0,0.0),1)
                change.key.ability.sound?.let {event.entity.world.playSound(event.entity.location,it,.4f,1.2f)}
            }
            val actor=event.damager as? Player?:((event.damager as? Projectile)?.shooter as? Player)
            if(actor!=null && Bukkit.isOwnedByCurrentRegion(actor)) {
                val ability=change.key.ability
                val text=if(ability.type=="precise_slash") {
                    if(change.reset)runtime.rules()?.messages?.get("precision-ready")?:"&bДодатковий розріз!" else
                        (runtime.rules()?.messages?.get("precision-charge")?:"&7Розріз: {hits}/{required}").replace("{hits}",change.sequence.hits.toString()).replace("{required}",ability.number("hits").toInt().toString())
                } else runtime.rules()?.messages?.get("focus")?:"&aВлучання в серії: {hits}"
                actor.sendActionBar(com.alytanta.oneblock.util.Colors.colorize(text.replace("{hits}",change.sequence.hits.toString())))
            }
        }
        val projectile=event.damager as? Projectile?:return
        val record=shots.remove(projectile.uniqueId)?:return
        val victim=event.entity as? LivingEntity?:return
        val player=projectile.shooter as? Player?:return
        if(player.uniqueId!=record.actor || record.rules!==runtime.rules() || !Bukkit.isOwnedByCurrentRegion(player) || !target(victim) || runtime.account(player)!=record.account || System.nanoTime()-record.expires>=0)return
        val rules=runtime.rules()?:return
        for(ability in record.effects) {
            if(ability.type=="wind_slow") {if(record.force>=ability.number("force"))slow(victim,ability,record.rules);continue}
            if(ability.type!="ember_mark")continue
            val key=player.uniqueId to victim.uniqueId;val now=System.nanoTime()
            if(marks[key]?.let {now-it<0}==true)continue
            if(detonations.putIfAbsent(record.volley,record.expires)!=null)continue
            marks[key]=now+(ability.number("cooldown-seconds")*1e9).toLong()
            val amount=(event.damage*ability.number("bonus")).coerceAtMost(50.0)
            victim.world.spawnParticle(particle(ability,Particle.FLAME),victim.eyeLocation,6,.2,.2,.2,0.0)
            victim.scheduler.runDelayed(plugin,{
                if(runtime.rules()!==rules || !Bukkit.isOwnedByCurrentRegion(player) || !runtime.usable(player) || runtime.account(player)!=record.account || !target(victim) || player.world!==victim.world)return@runDelayed
                val origin=victim.eyeLocation
                for(other in nearby(origin,ability.number("radius")).filter {it.uniqueId!=player.uniqueId}.take(ability.number("targets").toInt())) {
                    if(!visible(origin,other.eyeLocation))continue
                    // A newer arrow's hit immunity would swallow the explosion; the next arrow keeps its own window too.
                    val immunity=other.noDamageTicks;other.noDamageTicks=0
                    val hit=hurt(player,other,amount);other.noDamageTicks=immunity
                    if(hit)other.world.spawnParticle(particle(ability,Particle.FLAME),other.eyeLocation,8,.25,.25,.25,0.0)
                }
                origin.world.playSound(origin,ability.sound?:"minecraft:entity.generic.explode",.25f,1.8f)
            },{},ability.number("delay-ticks").toLong())
        }
    }
}
