package com.alytanta.oneblock.combat

/** Transient, entity-owned combo state. Times are monotonic nanoseconds, not persisted. */
internal data class GuardStance(val hits:Int=0,val lastHit:Long=0,val activeUntil:Long=0,val active:Boolean=false) {
    fun hit(now:Long,needed:Int,window:Long,duration:Long):GuardStance {
        val count=if(hits>0 && now-lastHit in 0..window) hits+1 else 1
        return if(count>=needed) GuardStance(0,now,now+duration,true)
        else copy(hits=count,lastHit=now)
    }
    fun protects(now:Long)=active && now-activeUntil<0
}
