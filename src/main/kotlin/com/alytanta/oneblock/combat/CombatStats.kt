package com.alytanta.oneblock.combat

/** Immutable PvE contract for future encounter code; vanilla armor/resistance still applies afterward. */
data class CombatWeapon(val id:String,val kind:CombatKind,val damage:Double,val multiplier:Double,val manaCost:Double,val cooldownSeconds:Double,val projectileSpeed:Double)
data class CombatStats(val defense:Double,val maxMana:Double,val magicMultiplier:Double,val equipment:Set<String>,val weapon:CombatWeapon?=null,
    val meleeMultiplier:Double=1.0,val rangedMultiplier:Double=1.0,val regenMultiplier:Double=1.0,val setProperty:String?=null,val setStrength:Double=0.0)
object CombatMath {
    fun stats(rules:CombatRules,armor:Map<String,String>):CombatStats {
        val pieces=armor.mapNotNull { (slot,id) -> rules.items[id]?.takeIf { it.kind==CombatKind.ARMOR && it.armorSlot==slot } }
        val complete=pieces.groupBy { it.set }.filterValues { it.size==4 }.keys.mapNotNull(rules.sets::get)
        return CombatStats((pieces.sumOf { it.defense }+complete.sumOf { it.defense }).coerceAtMost(rules.defenseCap),
            rules.baseMana+pieces.sumOf { it.manaBonus }+complete.sumOf { it.mana },
            1.0+pieces.sumOf { it.magicBonus }+complete.sumOf { it.magic },pieces.map { it.id }.toSet(),null,
            1.0+pieces.sumOf {it.meleeBonus},1.0+pieces.sumOf {it.rangedBonus},1.0+pieces.sumOf {it.regenBonus},complete.singleOrNull()?.property,complete.singleOrNull()?.strength?:0.0)
    }
    fun melee(base:Double,bonus:Double,cooldown:Double)=base+bonus*cooldown.coerceIn(0.0,1.0).let { 0.2+0.8*it*it }
}
/** Entity-owned monotonic clock; no per-tick task, no database resource, no class choice. */
class ManaPool(var value:Double,var time:Long,var nextCast:Long=time) {
    fun spend(now:Long,stats:CombatStats,regen:Double,cost:Double,cooldown:Double):Boolean {
        value=(value+(now-time).coerceAtLeast(0)/1e9*regen).coerceAtMost(stats.maxMana);time=now
        if(now-nextCast<0 || value<cost)return false
        value-=cost;nextCast=now+(cooldown*1e9).toLong();return true
    }
}
