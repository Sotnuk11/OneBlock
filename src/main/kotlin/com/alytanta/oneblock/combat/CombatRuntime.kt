package com.alytanta.oneblock.combat

import com.alytanta.oneblock.island.IslandService
import com.alytanta.oneblock.market.InventoryGuard
import com.alytanta.oneblock.util.Colors
import org.bukkit.Bukkit
import org.bukkit.NamespacedKey
import org.bukkit.entity.*
import org.bukkit.event.*
import org.bukkit.event.entity.*
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.event.block.Action
import org.bukkit.inventory.EquipmentSlot
import org.bukkit.persistence.PersistentDataType
import org.bukkit.plugin.Plugin
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import com.alytanta.oneblock.items.*

/** Event-local ownership checks deliberately avoid reading a remote player's inventory on a hit. */
interface CombatStatProvider {
    /** Must be called on the player's entity scheduler; returns only immutable scalar data. */
    fun snapshot(player:Player):CombatStats?
}
class CombatRuntime(private val plugin:Plugin,private val islands:IslandService,private val current:()->CombatRules?) : Listener,CombatStatProvider {
    val techniques=CombatTechniques(plugin,this)
    internal fun rules()=current()?.takeIf {it.enabled}
    internal fun account(player:Player)=islands.findForPlayer(player.uniqueId)?.owner?:player.uniqueId
    internal fun usable(player:Player)=available(player)
    internal fun permitted(player:Player,ability:ItemAbility,hand:EquipmentSlot)=eligible(player,ability,hand)
    private val spellCounts=ConcurrentHashMap<PoolKey,Int>()
    private val recoveries=ConcurrentHashMap<PoolKey,ActionRecovery>()
    internal fun spellFinished(player:Player,rules:CombatRules,account:UUID,cost:Double) {
        val stats=snapshot(player,rules)
        if(stats.setProperty!="weaver_flow")return
        val key=PoolKey(player.uniqueId,account);val count=spellCounts.merge(key,1,Int::plus)?:1
        if(count%4==0)pools[key]?.let {it.value=(it.value+cost*stats.setStrength).coerceAtMost(stats.maxMana)}
    }
    internal fun pay(player:Player,rules:CombatRules,account:UUID,ability:ItemAbility):Boolean {
        val stats=snapshot(player,rules);val now=System.nanoTime();val key=PoolKey(player.uniqueId,account)
        val cooldownKey=CooldownKey(key,ability.cooldownGroup);val pool=pools.computeIfAbsent(key) {ManaPool(stats.maxMana,now)}
        val recovery=recoveries.computeIfAbsent(key) {ActionRecovery()}
        if(!recovery.canUse(now) || cooldowns[cooldownKey]?.let {now-it<0}==true) {
            player.sendActionBar(Colors.colorize(rules.messages["cooldown"]?:"&7Здібність відновлюється."));return false
        }
        if(!pool.spend(now,stats,rules.manaPerSecond*stats.regenMultiplier,ability.number("mana-cost"),0.0)) {
            player.sendActionBar(Colors.colorize(rules.messages.getValue("mana").replace("{mana}",pool.value.toInt().toString()).replace("{max}",stats.maxMana.toInt().toString())));return false
        }
        cooldowns[cooldownKey]=now+(ability.number("cooldown-seconds")*1e9).toLong()
        val duration=if(ability.type=="wide_cleave")1.0/(player.getAttribute(org.bukkit.attribute.Attribute.ATTACK_SPEED)?.value?:1.0).coerceAtLeast(.5) else ability.number("cooldown-seconds")
        recovery.used(now,duration)
        if(ability.number("mana-cost")>0)player.sendActionBar(Colors.colorize(rules.messages.getValue("mana").replace("{mana}",pool.value.toInt().toString()).replace("{max}",stats.maxMana.toInt().toString())))
        return true
    }
    private val shot=NamespacedKey(plugin,"combat_shot")
    private val damage=NamespacedKey(plugin,"combat_damage")
    private val multiplier=NamespacedKey(plugin,"combat_multiplier")
    private val launched=NamespacedKey(plugin,"combat_launched")
    private data class PoolKey(val player:UUID,val account:UUID)
    private val pools=ConcurrentHashMap<PoolKey,ManaPool>()
    private data class CooldownKey(val pool:PoolKey,val group:String)
    private val cooldowns=ConcurrentHashMap<CooldownKey,Long>()
    private val processed=java.util.Collections.synchronizedMap(java.util.WeakHashMap<EntityDamageByEntityEvent,Boolean>())
    private data class AttackCharge(val target:UUID,val strength:Double,val time:Long)
    private val attackCharges=ConcurrentHashMap<UUID,AttackCharge>()
    private val eventCharges=java.util.Collections.synchronizedMap(java.util.WeakHashMap<EntityDamageByEntityEvent,Double>())
    @EventHandler(priority=EventPriority.MONITOR,ignoreCancelled=true)
    fun beforeAttack(event:io.papermc.paper.event.player.PrePlayerAttackEntityEvent) {
        if(event.willAttack() && Bukkit.isOwnedByCurrentRegion(event.player))attackCharges[event.player.uniqueId]=AttackCharge(event.attacked.uniqueId,event.player.attackCooldown.toDouble(),System.nanoTime())
    }
    internal fun charge(event:EntityDamageByEntityEvent):Double = eventCharges.getOrPut(event) {
        val player=event.damager as? Player
        val captured=player?.uniqueId?.let(attackCharges::get)
        if(captured!=null && captured.target==event.entity.uniqueId && System.nanoTime()-captured.time in 0..100_000_000)captured.strength else player?.attackCooldown?.toDouble()?:0.0
    }
    private data class GuardKey(val pool:PoolKey,val ability:ItemAbility,val item:String)
    private val guards=ConcurrentHashMap<GuardKey,GuardStance>()
    private val guardEvents=java.util.Collections.synchronizedMap(java.util.WeakHashMap<EntityDamageByEntityEvent,Boolean>())
    var worldRole:(Player)->String={"other"}
    var playerDamageAllowed:(Player)->Boolean={it.world.pvp}
    private fun bindings(item:CombatItem,type:String):List<ItemAbility> {
        item.definition?.let {return it.effects(type)}
        val parameters=when(type) {
            "melee_bonus"->if(item.kind==CombatKind.MELEE)mapOf("damage" to item.damage) else null
            "ranged_multiplier"->if(item.kind==CombatKind.RANGED)mapOf("multiplier" to item.multiplier) else null
            "magic_bolt"->if(item.kind==CombatKind.MAGIC)mapOf("damage" to item.damage,"mana-cost" to item.mana,"cooldown-seconds" to item.cooldown,"projectile-speed" to item.speed,"lifetime-ticks" to 100.0) else null
            "armor_stats"->if(item.kind==CombatKind.ARMOR)mapOf("defense" to item.defense,"mana" to item.manaBonus,"magic" to item.magicBonus) else null
            else->null
        }?:return emptyList()
        return listOf(ItemAbility("oneblock:legacy_${item.id}",type,ItemMechanics.rules.getValue(type).trigger,ItemHand.MAIN_HAND,parameters,null,setOf("island","dungeon","other"),"magic"))
    }
    private fun eligible(player:Player,ability:ItemAbility,hand:EquipmentSlot=EquipmentSlot.HAND):Boolean =
        (ability.permission==null || player.hasPermission(ability.permission)) && CombatAccess.world(worldRole(player),ability.worlds,current()?.hubAbilities==true) &&
            (ability.trigger in setOf(ItemTrigger.EQUIP,ItemTrigger.GATHER) || ability.hand==ItemHand.EITHER ||
                ability.hand==ItemHand.MAIN_HAND && hand==EquipmentSlot.HAND || ability.hand==ItemHand.OFF_HAND && hand==EquipmentSlot.OFF_HAND)
    override fun snapshot(player:Player):CombatStats? = current()?.takeIf { it.enabled }?.let { snapshot(player,it) }
    fun snapshot(player:Player,rules:CombatRules):CombatStats {
        check(Bukkit.isOwnedByCurrentRegion(player))
        val inv=player.inventory
        val weapon=CombatItems.resolve(inv.itemInMainHand,rules)?.takeIf { it.kind in setOf(CombatKind.MELEE,CombatKind.RANGED,CombatKind.MAGIC) }
            ?.let { CombatWeapon(it.id,it.kind,it.damage,it.multiplier,it.mana,it.cooldown,it.speed) }
        val pieces=mapOf("HEAD" to inv.helmet,"CHEST" to inv.chestplate,"LEGS" to inv.leggings,"FEET" to inv.boots).mapNotNull {(slot,stack)->
            CombatItems.resolve(stack,rules)?.takeIf {it.kind==CombatKind.ARMOR && it.armorSlot==slot}?.let {item->
                val active=bindings(item,"armor_stats").filter {eligible(player,it)}
                if(active.isEmpty())null else item to active
            }}
        val sets=pieces.groupBy {it.first.set}.filterValues {it.size==4}.keys.mapNotNull(rules.sets::get)
        return CombatStats((pieces.sumOf {it.second.sumOf {a->a.number("defense")}}+sets.sumOf {it.defense}).coerceAtMost(rules.defenseCap),
            rules.baseMana+pieces.sumOf {it.second.sumOf {a->a.number("mana")}}+sets.sumOf {it.mana},
            1.0+pieces.sumOf {it.second.sumOf {a->a.number("magic")}}+sets.sumOf {it.magic},pieces.map {it.first.id}.toSet(),weapon,
            1.0+pieces.sumOf {it.second.sumOf {a->a.number("melee")}},1.0+pieces.sumOf {it.second.sumOf {a->a.number("ranged")}},1.0+pieces.sumOf {it.second.sumOf {a->a.number("regen")}},sets.singleOrNull()?.property,sets.singleOrNull()?.strength?:0.0)
    }
    private fun available(player:Player)=player.isOnline && !player.isDead && CombatAccess.mode(player.gameMode,current()?.creativeAbilities==true) && !InventoryGuard.busy(player.uniqueId)
    internal fun refund(player:Player,rules:CombatRules,account:UUID,ability:ItemAbility) {
        val key=PoolKey(player.uniqueId,account)
        pools[key]?.let {it.value=(it.value+ability.number("mana-cost")).coerceAtMost(snapshot(player,rules).maxMana)}
        cooldowns.remove(CooldownKey(key,ability.cooldownGroup))
        recoveries.remove(key)
    }
    // Vanilla predicts a cancelled use for inert carriers such as BLAZE_ROD in air.
    // Restore the item action early; later protection listeners may still explicitly deny it.
    @EventHandler(priority=EventPriority.LOWEST)
    fun prepareCast(event:PlayerInteractEvent) {
        if(event.action in setOf(Action.RIGHT_CLICK_AIR,Action.RIGHT_CLICK_BLOCK) && CustomItems.resolve(event.item)?.abilities.orEmpty().any {it.trigger==ItemTrigger.USE})event.setUseItemInHand(Event.Result.DEFAULT)
    }
    @EventHandler(priority=EventPriority.HIGH,ignoreCancelled=true)
    fun shoot(event:EntityShootBowEvent) {
        val player=event.entity as? Player ?: return
        val rules=current()?.takeIf { it.enabled } ?: return
        if(!Bukkit.isOwnedByCurrentRegion(player) || !Bukkit.isOwnedByCurrentRegion(event.projectile) || !available(player))return
        val item=CombatItems.resolve(event.bow,rules)
        if(!quick(item) && recoveries[PoolKey(player.uniqueId,account(player))]?.blocksNative(System.nanoTime())==true) {event.isCancelled=true;return}
        if(item==null && (CustomItems.marked(event.bow) || event.bow?.type !in setOf(org.bukkit.Material.BOW,org.bukkit.Material.CROSSBOW)))return
        val effects=item?.let {bindings(it,"ranged_multiplier").filter {a->eligible(player,a,event.hand)}}.orEmpty()
        val armor=snapshot(player,rules).rangedMultiplier
        if(effects.isEmpty() && armor==1.0)return
        event.projectile.persistentDataContainer.set(shot,PersistentDataType.STRING,"ranged")
        event.projectile.persistentDataContainer.set(multiplier,PersistentDataType.DOUBLE,effects.fold(armor) {value,a->value*a.number("multiplier",1.0)})
        event.projectile.persistentDataContainer.set(launched,PersistentDataType.LONG,System.currentTimeMillis())
    }
    @EventHandler(priority=EventPriority.HIGHEST)
    fun cast(event:PlayerInteractEvent) {
        if(event.hand==null || event.action !in setOf(Action.RIGHT_CLICK_AIR,Action.RIGHT_CLICK_BLOCK) ||
            event.useItemInHand()==Event.Result.DENY)return
        val player=event.player;val rules=current()?.takeIf { it.enabled } ?: return
        val item=CombatItems.resolve(event.item,rules) ?: return
        val effects=bindings(item,"magic_bolt")
        if(effects.isEmpty() && item.definition?.abilities.orEmpty().none {it.trigger==ItemTrigger.USE})return
        event.setUseItemInHand(Event.Result.DENY);event.setUseInteractedBlock(Event.Result.DENY)
        if(!Bukkit.isOwnedByCurrentRegion(player) || !available(player))return
        val held=if(event.hand==EquipmentSlot.HAND)player.inventory.itemInMainHand else player.inventory.itemInOffHand
        if(CombatItems.resolve(held,rules)?.id!=item.id)return
        val account=account(player)
        item.definition?.abilities.orEmpty().firstOrNull {it.type=="shortbow" && eligible(player,it,event.hand!!)}?.let {shortbow(player,rules,account,held,it,event.hand!!);return}
        if(techniques.use(player,rules,item,event.hand!!,account))return
        effects.filter {eligible(player,it,event.hand!!)}.forEach {ability->castAbility(player,rules,account,ability)}
    }
    private fun quick(item:CombatItem?)=item?.definition?.abilities.orEmpty().any {it.type=="shortbow"}
    /** Fires at once with the normal shoot event, so damage bonuses, marks and protection work as for any bow. */
    private fun shortbow(player:Player,rules:CombatRules,account:UUID,bow:org.bukkit.inventory.ItemStack,ability:ItemAbility,hand:EquipmentSlot) {
        val inventory=player.inventory
        val creative=player.gameMode==org.bukkit.GameMode.CREATIVE
        val infinite=bow.containsEnchantment(org.bukkit.enchantments.Enchantment.INFINITY)
        val ammo=(listOf(inventory.itemInOffHand)+inventory.storageContents.filterNotNull()).firstOrNull {it.type==org.bukkit.Material.ARROW && !CustomItems.marked(it)}
        if(ammo==null && !creative && !infinite) {player.sendActionBar(Colors.colorize(rules.messages["no-arrows"]?:"&7Немає стріл."));return}
        if(!pay(player,rules,account,ability))return
        val power=bow.getEnchantmentLevel(org.bukkit.enchantments.Enchantment.POWER)
        val base=(2.0+if(power>0).5*(power+1) else 0.0)*ability.number("damage-scale")
        val arrow=player.launchProjectile(Arrow::class.java,player.eyeLocation.direction.multiply(ability.number("speed"))) {a->
            a.damage=base;a.isCritical=false
            a.pickupStatus=if(creative||infinite)AbstractArrow.PickupStatus.CREATIVE_ONLY else AbstractArrow.PickupStatus.ALLOWED
            if(bow.containsEnchantment(org.bukkit.enchantments.Enchantment.FLAME))a.fireTicks=100
        }
        val shot=org.bukkit.event.entity.EntityShootBowEvent(player,bow,(ammo?:org.bukkit.inventory.ItemStack(org.bukkit.Material.ARROW)).asOne(),arrow,hand,1f,false)
        if(!arrow.isValid || !shot.callEvent()) {arrow.remove();refund(player,rules,account,ability);return}
        if(!creative && !infinite)ammo?.let {it.amount-=1}
        if(!creative)player.damageItemStack(hand,1)
        player.swingHand(hand)
        player.world.playSound(player.location,org.bukkit.Sound.ENTITY_ARROW_SHOOT,.8f,1.3f)
    }
    private fun castAbility(player:Player,rules:CombatRules,account:UUID,ability:ItemAbility) {
        val stats=snapshot(player,rules)
        val cost=ability.number("mana-cost")
        if(!pay(player,rules,account,ability))return
        try {
            // A native arrow keeps collision/damage on Folia's owning region and invokes all normal protection events.
            player.launchProjectile(Arrow::class.java,player.eyeLocation.direction.multiply(ability.number("projectile-speed"))) { arrow ->
                arrow.pickupStatus=AbstractArrow.PickupStatus.DISALLOWED;arrow.isCritical=false;arrow.damage=0.01
                arrow.isVisibleByDefault=false
                arrow.persistentDataContainer.set(shot,PersistentDataType.STRING,"magic")
                arrow.persistentDataContainer.set(damage,PersistentDataType.DOUBLE,ability.number("damage")*stats.magicMultiplier)
                arrow.persistentDataContainer.set(launched,PersistentDataType.LONG,System.currentTimeMillis())
                if(rules.magicTrailCount>0)arrow.scheduler.runAtFixedRate(plugin,{task->
                    if(!arrow.isValid || arrow.isDead || arrow.isOnGround)task.cancel()
                    else arrow.world.spawnParticle(org.bukkit.Particle.valueOf(rules.magicTrailParticle),arrow.location,rules.magicTrailCount,0.05,0.05,0.05,0.0)
                },{},1,rules.magicTrailInterval)
                arrow.scheduler.runDelayed(plugin,{arrow.remove()},{},ability.number("lifetime-ticks").toLong())
            }
            spellFinished(player,rules,account,cost)
        } catch(e:Exception) { refund(player,rules,account,ability);plugin.logger.warning("Combat projectile failed: ${e.javaClass.simpleName}") }
    }
    @EventHandler(priority=EventPriority.HIGH,ignoreCancelled=true)
    fun outgoing(event:EntityDamageByEntityEvent) {
        if(event.entity is Player && !playerDamageAllowed(event.entity as Player) && (event.damager is Player || (event.damager as? Projectile)?.shooter is Player)) {event.isCancelled=true;return}
        if(techniques.scripted())return
        val rules=current()?.takeIf { it.enabled } ?: return
        if(processed.put(event,true)!=null)return
        val damager=event.damager
        if(!Bukkit.isOwnedByCurrentRegion(damager))return
        if(damager is Projectile) {
            val p=damager.persistentDataContainer;val kind=p.get(shot,PersistentDataType.STRING) ?: return
            val age=System.currentTimeMillis()-(p.get(launched,PersistentDataType.LONG) ?: return)
            if(kind=="magic" && (event.entity is Player || age !in 0..10000)) { event.isCancelled=true;return }
            if(age !in 0..10000)return
            if(kind=="magic")p.get(damage,PersistentDataType.DOUBLE)?.takeIf { it.isFinite() && it in 0.0..2000.0 }?.let { event.damage=it }
            else if(kind=="ranged")p.get(multiplier,PersistentDataType.DOUBLE)?.takeIf { it.isFinite() && it in 0.1..5.0 }?.let { event.damage*=it }
        } else if(damager is Player && available(damager) && event.cause==EntityDamageEvent.DamageCause.ENTITY_ATTACK) {
            if(recoveries[PoolKey(damager.uniqueId,account(damager))]?.blocksNative(System.nanoTime())==true) {event.isCancelled=true;return}
            val bonus=listOf(EquipmentSlot.HAND to damager.inventory.itemInMainHand,EquipmentSlot.OFF_HAND to damager.inventory.itemInOffHand).sumOf {(hand,stack)->
                CombatItems.resolve(stack,rules)?.let {item->bindings(item,"melee_bonus").filter {eligible(damager,it,hand)}.sumOf {it.number("damage")}}?:0.0}
            if(bonus>0)event.damage=CombatMath.melee(event.damage,bonus,charge(event))
            event.damage*=snapshot(damager,rules).meleeMultiplier
        }
    }
    @EventHandler(priority=EventPriority.MONITOR,ignoreCancelled=true)
    fun nativeRecovery(event:EntityDamageByEntityEvent) {
        if(techniques.scripted() || event.finalDamage<=0 || event.cause!=EntityDamageEvent.DamageCause.ENTITY_ATTACK)return
        val p=event.damager as? Player?:return
        if(!Bukkit.isOwnedByCurrentRegion(p) || !available(p))return
        recoveries.computeIfAbsent(PoolKey(p.uniqueId,account(p))) {ActionRecovery()}.native(System.nanoTime(),1.0/(p.getAttribute(org.bukkit.attribute.Attribute.ATTACK_SPEED)?.value?:4.0).coerceAtLeast(.5))
    }
    @EventHandler(priority=EventPriority.MONITOR,ignoreCancelled=true)
    fun shotRecovery(event:EntityShootBowEvent) {
        val p=event.entity as? Player?:return;if(!Bukkit.isOwnedByCurrentRegion(p) || !available(p) || event.bow?.type !in setOf(org.bukkit.Material.BOW,org.bukkit.Material.CROSSBOW))return
        if(current()?.let {quick(CombatItems.resolve(event.bow,it))}==true)return
        val seconds=if(event.bow!!.type==org.bukkit.Material.CROSSBOW)1.25-.25*event.bow!!.getEnchantmentLevel(org.bukkit.enchantments.Enchantment.QUICK_CHARGE).coerceAtMost(3) else 1.1
        recoveries.computeIfAbsent(PoolKey(p.uniqueId,account(p))) {ActionRecovery()}.native(System.nanoTime(),seconds)
    }
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true)
    fun incoming(event:EntityDamageByEntityEvent) {
        val player=event.entity as? Player ?: return
        val rules=current()?.takeIf { it.enabled } ?: return
        if(!Bukkit.isOwnedByCurrentRegion(player) || !Bukkit.isOwnedByCurrentRegion(event.damager))return
        if((event.damager is Player || (event.damager as? Projectile)?.shooter is Player) && !playerDamageAllowed(player))return
        if(!available(player))return
        val account=account(player)
        val item=CombatItems.resolve(player.inventory.itemInMainHand,rules)
        val guard=if(item==null)0.0 else bindings(item,"charged_guard").filter {eligible(player,it)}.maxOfOrNull {ability->
            val key=GuardKey(PoolKey(player.uniqueId,account),ability,item.id)
            if(guards[key]?.protects(System.nanoTime())==true)ability.number("reduction") else 0.0
        }?:0.0
        event.damage*=(1.0-snapshot(player,rules).defense)*(1.0-guard)
    }
    @EventHandler(priority=EventPriority.MONITOR,ignoreCancelled=true)
    fun chargedGuard(event:EntityDamageByEntityEvent) {
        if(techniques.scripted())return
        val player=event.damager as? Player?:return
        if(event.entity !is LivingEntity || event.entity is Player && !playerDamageAllowed(event.entity as Player) || event.entity is ArmorStand || event.cause!=EntityDamageEvent.DamageCause.ENTITY_ATTACK || event.finalDamage<=0.0 ||
            !Bukkit.isOwnedByCurrentRegion(player) || !available(player) || guardEvents.put(event,true)!=null)return
        val rules=current()?.takeIf {it.enabled}?:return
        val account=account(player)
        val item=CombatItems.resolve(player.inventory.itemInMainHand,rules)?:return
        for(ability in bindings(item,"charged_guard").filter {eligible(player,it) && charge(event)>=it.number("charge-threshold")}) {
            val key=GuardKey(PoolKey(player.uniqueId,account),ability,item.id)
            guards.keys.removeIf {it.pool.player==player.uniqueId && it.ability.id==ability.id && it!=key}
            val now=System.nanoTime()
            val stats=snapshot(player,rules)
            val state=guards.compute(key) {_,old->(old?:GuardStance()).hit(now,ability.number("hits").toInt(),
                (ability.number("window-seconds")*1e9).toLong(),((ability.number("duration-seconds")+if(stats.setProperty=="guardian_resolve")stats.setStrength else 0.0)*1e9).toLong())}
            val message=if(state?.protects(now)==true)rules.messages["guard-active"]?:"&bЗахисна стійка активна!" else
                (rules.messages["guard-charge"]?:"&7Стійка: {hits}/{required}").replace("{hits}",(state?.hits?:0).toString()).replace("{required}",ability.number("hits").toInt().toString())
            player.sendActionBar(Colors.colorize(message))
            if(state?.protects(now)==true)player.world.spawnParticle(org.bukkit.Particle.ELECTRIC_SPARK,player.location.add(0.0,1.0,0.0),5,.35,.4,.35,0.0)
        }
    }
    @EventHandler fun quitGuard(event:org.bukkit.event.player.PlayerQuitEvent) {attackCharges.remove(event.player.uniqueId);guards.keys.removeIf {it.pool.player==event.player.uniqueId};recoveries.keys.removeIf {it.player==event.player.uniqueId}}
    @EventHandler fun deathGuard(event:PlayerDeathEvent) {attackCharges.remove(event.entity.uniqueId);guards.keys.removeIf {it.pool.player==event.entity.uniqueId};recoveries.keys.removeIf {it.player==event.entity.uniqueId}}
}
