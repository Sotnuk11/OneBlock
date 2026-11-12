package com.alytanta.oneblock.dungeon

import com.alytanta.oneblock.combat.PreparedCombatReward
import java.util.UUID

enum class MinePhase { PREPARING, ENTRY, PUZZLE, BOSS, WON, ABORTED, CLOSED, WAVE_WARNING, WAVE, INTERMISSION, TRANSITION }
enum class MineLife { ALIVE, DOWN, LEFT }
data class MineMember(val actor:UUID,val account:UUID,val name:String,val health:Double,val entryHealth:Double,
    val mode:String,val life:MineLife=MineLife.ALIVE,val inWorld:Boolean=false,val deadline:Long?=null,
    val deathEpoch:Long?=null,val returnPending:Boolean=true,val awaitingRespawn:Boolean=false)
data class MineRun(val id:UUID,val world:String,val settings:MineSettings,val members:Map<UUID,MineMember>,
    val catalog:com.alytanta.oneblock.combat.CombatRewardCatalog,
    val phase:MinePhase=MinePhase.PREPARING,val epoch:Long=1,val deaths:Int=0,val wipes:Int=0,val puzzleStep:Int=0,
    val secret:Boolean=false,val secretOpen:Boolean=false,val created:Long=System.currentTimeMillis(),val lastEvent:Long=0,
    val rewards:Map<String,PreparedCombatReward> = emptyMap(),val acknowledged:Set<String> = emptySet(),
    val wave:WaveProgress?=null,val recordAck:String?=null,
    val worldCreated:Boolean=false,val worldRemoved:Boolean=false,val reason:String?=null) {
    val terminal:Boolean get()=phase in setOf(MinePhase.WON,MinePhase.ABORTED,MinePhase.CLOSED)
    val remaining:Int get()=(settings.attempts-wipes).coerceAtLeast(0)
}
/** Pure encounter transitions. The session coordinator serializes and durably journals each accepted transition. */
object MineState {
    fun alive(s:MineRun)=s.members.values.filter {it.life==MineLife.ALIVE && it.inWorld}
    fun death(s:MineRun,actor:UUID,away:Boolean,now:Long):MineRun {
        if(s.terminal)return s
        val m=s.members[actor]?:return s;if(m.life==MineLife.LEFT)return s
        val first=m.deathEpoch!=s.epoch && !m.awaitingRespawn
        val member=m.copy(life=MineLife.DOWN,inWorld=if(away)false else m.inWorld,
            deadline=if(away)m.deadline?:now+s.settings.reconnectSeconds*1000L else if(!m.inWorld)m.deadline else null,
            deathEpoch=s.epoch,health=if(first)0.0 else m.health,awaitingRespawn=m.awaitingRespawn || !away)
        return s.copy(members=s.members+(actor to member),deaths=s.deaths+if(first)1 else 0,lastEvent=now)
    }
    fun respawn(s:MineRun,actor:UUID):MineRun {
        val m=s.members.getValue(actor);return s.copy(members=s.members+(actor to m.copy(awaitingRespawn=false)))
    }
    fun reconnect(s:MineRun,actor:UUID,now:Long):MineRun {
        require(!s.terminal);val m=s.members.getValue(actor)
        require(m.life!=MineLife.LEFT && m.deadline!=null && now<=m.deadline)
        return s.copy(members=s.members+(actor to m.copy(inWorld=true,deadline=null)))
    }
    /** A failed transport never aborts the other members or grants a fresh reconnect window. */
    fun reconnectFailed(s:MineRun,actor:UUID,originalDeadline:Long,now:Long):MineRun {
        if(s.terminal)return s
        val m=s.members[actor]?:return s
        if(m.life==MineLife.LEFT)return s
        return wipe(expire(s.copy(members=s.members+(actor to m.copy(inWorld=false,deadline=minOf(originalDeadline,m.deadline?:originalDeadline)))),now))
    }
    fun wipe(s:MineRun):MineRun {
        if(s.terminal || s.phase==MinePhase.PREPARING || alive(s).isNotEmpty() || s.members.values.none {it.inWorld && it.life!=MineLife.LEFT})return s
        val wipes=s.wipes+1
        if(wipes>=s.settings.attempts)return s.copy(phase=MinePhase.ABORTED,wipes=wipes,epoch=s.epoch+1,reason="attempts")
        return s.copy(wipes=wipes,epoch=s.epoch+1,puzzleStep=0,
            phase=if(s.wave!=null && s.phase==MinePhase.WAVE)MinePhase.WAVE_WARNING else s.phase,
            members=s.members.mapValues {(_,m)->if(m.life==MineLife.LEFT)m else m.copy(life=MineLife.ALIVE,health=m.entryHealth)})
    }
    fun cleared(s:MineRun,expected:MinePhase):MineRun {
        if(s.terminal || s.phase!=expected)return s
        val next=when(expected) {MinePhase.ENTRY->MinePhase.PUZZLE;MinePhase.BOSS->MinePhase.WON;else->return s}
        return s.copy(phase=next,epoch=s.epoch+1,members=s.members.mapValues {(_,m)->if(m.life==MineLife.DOWN)m.copy(life=MineLife.ALIVE,health=m.entryHealth) else m})
    }
    fun lever(s:MineRun,actor:UUID,lever:Int):MineRun {
        if(s.phase!=MinePhase.PUZZLE || s.members[actor]?.let {it.inWorld && it.life==MineLife.ALIVE}!=true)return s
        val step=if(s.settings.puzzle[s.puzzleStep]==lever)s.puzzleStep+1 else 0
        return if(step==s.settings.puzzle.size)s.copy(phase=MinePhase.BOSS,epoch=s.epoch+1,puzzleStep=step) else s.copy(puzzleStep=step)
    }
    fun expire(s:MineRun,now:Long):MineRun {
        if(s.terminal)return s
        val members=s.members.mapValues {(_,m)->if(m.deadline!=null && now>m.deadline)m.copy(life=MineLife.LEFT,inWorld=false,deadline=null) else m}
        return if(members.values.all {it.life==MineLife.LEFT} || s.wave==null && now-s.created>s.settings.maxSeconds*1000L)
            s.copy(members=members,phase=MinePhase.ABORTED,epoch=s.epoch+1,reason="timeout") else s.copy(members=members)
    }
}
