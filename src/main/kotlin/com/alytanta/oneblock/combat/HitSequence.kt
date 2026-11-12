package com.alytanta.oneblock.combat

import java.util.UUID

internal data class HitSequence(val target:UUID,val hits:Int,val time:Long) {
    fun next(victim:UUID,now:Long,window:Long,cap:Int):HitSequence =
        HitSequence(victim,if(victim==target && now-time in 0..window)(hits+1).coerceAtMost(cap) else 1,now)
    companion object {
        fun next(previous:HitSequence?,victim:UUID,now:Long,window:Long,cap:Int)=previous?.next(victim,now,window,cap)?:HitSequence(victim,1,now)
    }
}
