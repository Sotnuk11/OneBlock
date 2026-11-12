package com.alytanta.oneblock.combat

/** Shared opportunity cost for use abilities and native attacks. Swapping items cannot create free actions. */
internal class ActionRecovery(var until:Long=0,var ability:Boolean=false) {
    fun canUse(now:Long)=now-until>=0
    fun blocksNative(now:Long)=ability && !canUse(now)
    fun used(now:Long,seconds:Double) {until=now+(seconds.coerceIn(0.0,30.0)*1e9).toLong();ability=true}
    fun native(now:Long,seconds:Double) {until=now+(seconds.coerceIn(0.0,2.0)*1e9).toLong();ability=false}
}
