package com.alytanta.oneblock.activities

import com.alytanta.oneblock.island.*
import com.alytanta.oneblock.market.*
import com.alytanta.oneblock.release.*
import java.util.UUID

/** Account-local state. All callers publish it through the existing serialized island/ledger mutation. */
object ActivityDomain {
    private fun key(s:String)=UUID.nameUUIDFromBytes(s.toByteArray(Charsets.UTF_8))
    fun access(i:Island,actor:UUID)=i.retiredAt==null && (i.ownerPlayer==actor || i.members[actor]==IslandRole.MEMBER)
    fun idle(i:Island,s:MarketState) {require(s.transfers.values.none {it.account==i.owner && it.kind==TransferKind.RELEASE && it.status in setOf(TransferStatus.PREPARED,TransferStatus.REVIEW)}) {"busy"}}
    fun accept(i:Island,s:MarketState,actor:UUID,offer:String,now:Long,r:ActivityRules):Island {
        require(r.enabled && access(i,actor));idle(i,s)
        val p=requireNotNull(i.releaseProgress);val a=p.activity
        require(a.order==null && now-a.lastChange>=r.actionMs) {"busy"}
        require(now>=a.orderReadyAt) {"cooldown"}
        val rule=r.offers(p.chapter,a.completed).first {it.id==offer}
        val serial=Math.addExact(a.acceptedSerial,1)
        return i.copy(releaseProgress=p.copy(activity=a.copy(order=GuildOrder(key("guild:${i.owner}:$serial"),rule,actor,now),acceptedSerial=serial,lastChange=now,orderReadyAt=now+r.orderCooldownMs)))
    }
    fun cancel(i:Island,s:MarketState,actor:UUID,id:UUID,now:Long,r:ActivityRules):Island {
        require(r.enabled && access(i,actor));idle(i,s);val p=i.releaseProgress!!;val a=p.activity
        require(a.order?.id==id && now-a.lastChange>=r.actionMs)
        // A cancelled quest paid nothing, so it does not cost the wait.
        return i.copy(releaseProgress=p.copy(activity=a.copy(order=null,lastChange=now,orderReadyAt=0)))
    }
    fun credit(p:ReleaseProgress,block:String,grown:Boolean,core:Boolean,now:Long,r:ActivityRules):ReleaseProgress {
        val a=p.activity;if(!r.enabled || now-a.lastCredit<r.creditMs)return p
        var order=a.order
        if(order!=null && order.progress<order.rule.target && block in order.rule.blocks &&
            (order.rule.type=="mine" || order.rule.type=="grow" && grown && !core))order=order.copy(progress=order.progress+1)
        val charge=if(a.ready || a.event!=null)a.charge else (a.charge+1).coerceAtMost(r.readiness)
        var event=a.event
        if(event!=null && event.running && event.rule.kind=="resonance" && block in event.rule.blocks && now-event.lastStep>=event.rule.intervalMs && event.progress<event.rule.goal && event.elapsed+now-event.checkpoint<=event.rule.durationMs)
            event=event.copy(progress=event.progress+1,lastStep=now)
        return p.copy(activity=a.copy(order=order,charge=charge,ready=a.ready || charge>=r.readiness,lastCredit=now,event=event))
    }
    fun start(i:Island,s:MarketState,actor:UUID,kind:String,now:Long,r:ActivityRules):Island {
        require(r.enabled && access(i,actor));idle(i,s);val p=i.releaseProgress!!;val a=p.activity
        require(a.ready)
        val e=a.event
        val serial=if(e==null)Math.addExact(a.eventSerial,1) else a.eventSerial
        val next=if(e!=null) {require(!e.running && e.rule.kind==kind);e.copy(running=true,checkpoint=now,lastStep=now)}
            else IslandActivityEvent(key("island-event:${i.owner}:$serial"),r.events.getValue(kind),actor,lastStep=now,checkpoint=now)
        return i.copy(releaseProgress=p.copy(activity=a.copy(event=next,eventSerial=serial)))
    }
    fun pause(i:Island):Island {
        val p=i.releaseProgress?:return i;val a=p.activity;val e=a.event?.takeIf {it.running}?:return i
        // Checkpoints charge only time spent with an active player, never offline/restart downtime.
        return i.copy(releaseProgress=p.copy(activity=a.copy(event=e.copy(running=false,checkpoint=0))))
    }
    fun tick(i:Island,id:UUID,now:Long,active:Boolean):Island {
        val p=i.releaseProgress?:return i;val a=p.activity;val e=a.event?.takeIf {it.id==id && it.running}?:return i
        val elapsed=e.elapsed+(now-e.checkpoint).coerceIn(0,5000)
        val next=if(elapsed>e.rule.durationMs && e.progress<e.rule.goal)null else e.copy(elapsed=elapsed,checkpoint=if(active)now else 0,running=active)
        return i.copy(releaseProgress=p.copy(activity=a.copy(event=next)))
    }
    fun killed(i:Island,id:UUID,expected:Int,now:Long):Island {
        val p=i.releaseProgress?:return i;val a=p.activity;val e=a.event?:return i
        if(e.id!=id || !e.running || e.rule.kind!="echo" || e.progress!=expected || e.progress>=e.rule.goal || now-e.lastStep<e.rule.intervalMs || e.elapsed>e.rule.durationMs)return i
        return i.copy(releaseProgress=p.copy(activity=a.copy(event=e.copy(progress=e.progress+1,lastStep=now))))
    }
    fun plan(i:Island,actor:UUID,action:String,value:String):ReleaseIntent {
        require(access(i,actor));val a=i.releaseProgress!!.activity
        val reward:ActivityReward;val recipient:UUID;val ingredients:List<Ingredient>
        if(action=="guild-deliver") {
            val o=requireNotNull(a.order);require(o.id.toString()==value)
            require(o.rule.type=="supply" || o.progress>=o.rule.target) {"requirements"}
            reward=o.rule.reward;recipient=o.actor;ingredients=if(o.rule.type=="supply")o.rule.ingredients else emptyList()
        } else {
            require(action=="activity-reward");val e=requireNotNull(a.event);require(e.id.toString()==value && e.progress>=e.rule.goal) {"requirements"}
            reward=e.rule.reward;recipient=e.actor;ingredients=emptyList()
        }
        return ReleaseIntent(action,value,0,0,ingredients,reward.items,false,reward.skill,recipient,reward.xp)
    }
    fun settle(p:ReleaseProgress,intent:ReleaseIntent,domain:ReleaseDomain):ReleaseProgress {
        val a=p.activity;val reward:ActivityReward;val next:ActivityProgress
        if(intent.action=="guild-deliver") {
            val o=requireNotNull(a.order);require(o.id.toString()==intent.value);reward=o.rule.reward
            next=a.copy(order=null,completed=Math.addExact(a.completed,1),discovery=ReleaseDomain.saturate(a.discovery,reward.discovery))
        } else {
            val e=requireNotNull(a.event);require(e.id.toString()==intent.value && e.progress>=e.rule.goal);reward=e.rule.reward
            next=a.copy(event=null,ready=false,charge=0)
        }
        require(intent.items==reward.items && intent.xpSkill==reward.skill && intent.xpAmount==reward.xp && intent.coins==0L && intent.rewardCoins==0L)
        return domain.xp(p.copy(activity=next,facts=if(intent.action=="activity-reward")p.facts+"activity:${p.activity.event!!.rule.kind}" else p.facts),requireNotNull(intent.xpActor),reward.skill,reward.xp)
    }
}
