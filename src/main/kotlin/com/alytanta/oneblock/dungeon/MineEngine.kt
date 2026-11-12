package com.alytanta.oneblock.dungeon

import com.alytanta.oneblock.combat.*
import java.util.UUID
import java.util.concurrent.*

/** Per-session ordering, independent I/O workers, immutable published views. No tick-thread waits. */
class MineEngine(private val journal:MineJournal,private val changed:(MineRun?,MineRun)->Unit,
    private val failed:(Throwable)->Unit) : AutoCloseable {
    private val workers=ThreadPoolExecutor(4,4,0,TimeUnit.SECONDS,ArrayBlockingQueue(4096),
        ThreadFactory {r->Thread(r,"oneblock-mine-journal").apply {isDaemon=true}},ThreadPoolExecutor.AbortPolicy())
    private val runs=ConcurrentHashMap<UUID,MineRun>()
    private val bindings=ConcurrentHashMap<UUID,UUID>()
    private val tails=ConcurrentHashMap<UUID,CompletableFuture<*>>()
    private val persisted=ConcurrentHashMap.newKeySet<UUID>()
    private val workIds=ConcurrentHashMap.newKeySet<UUID>()
    private val pendingRecipients=ConcurrentHashMap<UUID,MutableSet<UUID>>()
    private val admission=Any()
    @Volatile private var closed=false
    val ready=CompletableFuture<Unit>()
    fun all():List<MineRun> = runs.values.toList()
    fun get(id:UUID)=runs[id]
    fun work():List<MineRun> = workIds.mapNotNull(runs::get)
    fun hasPendingReward(actor:UUID)=pendingRecipients[actor]?.isNotEmpty()==true
    private fun index(run:MineRun) {
        if(!run.terminal || !run.worldRemoved || !run.acknowledged.containsAll(run.rewards.keys) || !WaveRecords.synced(run))workIds.add(run.id) else workIds.remove(run.id)
        run.members.keys.forEach {actor->
            if(run.rewards.any {(key,r)->r.request.recipient==actor && key !in run.acknowledged})pendingRecipients.computeIfAbsent(actor) {ConcurrentHashMap.newKeySet()}.add(run.id)
            else pendingRecipients.computeIfPresent(actor) {_,ids->ids.remove(run.id);ids.takeIf {it.isNotEmpty()}}
        }
    }
    fun binding(actor:UUID):MineRun? = bindings[actor]?.let(runs::get)
    fun initialize(loaded:List<MineRun>?=null) {
        // Preloaded on the bootstrap I/O worker: return bindings exist before players may join.
        loaded?.forEach {old->val next=recover(old)
            runs[next.id]=next;next.members.values.filter {it.returnPending}.forEach {m->check(bindings.putIfAbsent(m.actor,next.id)==null)} }
        workers.execute {
            try {
                (if(loaded==null)journal.load() else emptyList()).forEach {old->
                    val next=recover(old)
                    journal.save(next);persisted.add(next.id);runs[next.id]=next
                    next.members.values.filter {it.returnPending}.forEach {m->check(bindings.putIfAbsent(m.actor,next.id)==null) {"Conflicting dungeon return journals"}}
                }
                if(loaded!=null)runs.values.forEach {journal.save(it);persisted.add(it.id)}
                runs.values.toList().forEach {s->
                    if(s.terminal && s.worldRemoved && s.members.values.none {it.returnPending} && s.acknowledged.containsAll(s.rewards.keys) && WaveRecords.synced(s)) {journal.remove(s.id);runs.remove(s.id);persisted.remove(s.id)}
                    else index(s)
                }
                ready.complete(Unit);runs.values.forEach {changed(null,it)}
            } catch(e:Throwable) {ready.completeExceptionally(e);failed(e)}
        }
    }
    fun create(settings:MineSettings,members:List<MineMember>,catalog:CombatRewardCatalog):CompletableFuture<MineRun> {
        settings.waves?.let {w->settings.roomRewards?.validateBudget(catalog,w.rewardLimit,members.size,w.bossEvery)}
        val run=synchronized(admission) {
            check(!closed && ready.isDone && !ready.isCompletedExceptionally && settings.enabled && settings.allowWorlds)
            require(members.size in 1..4 && members.map {it.actor}.distinct().size==members.size)
            require(members.none {bindings.containsKey(it.actor) || hasPendingReward(it.actor)}) {"busy"}
            require(runs.values.count {!it.worldRemoved}<settings.maxSessions) {"capacity"}
            val id=UUID.randomUUID();MineRun(id,MineJournal.world(id),settings,members.associateBy {it.actor},catalog,
                wave=settings.waves?.let {WaveProgress(it.plan(id,1,members.size))}).also {
                runs[id]=it;members.forEach {m->bindings[m.actor]=id}
            }
        }
        return update(run.id) {it}
    }
    fun update(id:UUID,operation:(MineRun)->MineRun):CompletableFuture<MineRun> {
        val result=CompletableFuture<MineRun>()
        if(closed)return CompletableFuture.failedFuture(IllegalStateException("Dungeon engine closed"))
        try {tails.compute(id) {_,tail->
            val scheduled=(tail?:CompletableFuture.completedFuture(Unit)).handle {_,_->Unit}.thenRunAsync({
                try {
                    val before=runs[id]
                    // An evacuation/ack callback can already be queued when the last ack prunes the run.
                    // This is an expected closed-session outcome, not a failed journal write.
                    if(before==null) {result.completeExceptionally(IllegalStateException("Dungeon session already closed"));return@thenRunAsync}
                    val next=finalRewards(finishTime(operation(before)))
                    require(next.id==id && next.world==before.world && next.members.keys==before.members.keys)
                    if(id in persisted && next==before) {result.complete(before);return@thenRunAsync}
                    journal.save(next);persisted.add(id);runs[id]=next;index(next)
                    next.members.values.filter {!it.returnPending}.forEach {bindings.remove(it.actor,id)}
                    changed(before,next)
                    if(next.terminal && next.worldRemoved && next.members.values.none {it.returnPending} && next.acknowledged.containsAll(next.rewards.keys) && WaveRecords.synced(next)) {
                        journal.remove(id);runs.remove(id,next);persisted.remove(id);workIds.remove(id)
                    }
                    result.complete(next)
                } catch(e:Throwable) {result.completeExceptionally(e);failed(e)}
            },workers)
            scheduled.whenComplete {_,error->if(error!=null)result.completeExceptionally(error)}
            scheduled
        }} catch(e:Exception) {result.completeExceptionally(e)}
        result.whenComplete {_,_->if(!runs.containsKey(id))tails.remove(id)}
        return result.copy()
    }
    fun event(id:UUID,epoch:Long,operation:(MineRun)->MineRun)=update(id) {s->if(s.terminal || s.epoch!=epoch)s else operation(s)}
    private fun finishTime(s:MineRun):MineRun = if(s.terminal && s.wave!=null && s.wave.ended==null)
        s.copy(wave=s.wave.copy(ended=System.currentTimeMillis())) else s
    private fun finalRewards(s:MineRun):MineRun {
        if(s.rewards.isNotEmpty() || s.phase !in setOf(MinePhase.WON,MinePhase.ABORTED) ||
            // A run cut short (restart, failed transfer) still pays for the waves it completed.
            (s.phase==MinePhase.ABORTED && s.reason!="attempts" && (s.wave?.completed?:0L)<=0L)) return s
        val rewards=mutableMapOf<String,PreparedCombatReward>()
        val rules=s.catalog.rules.copy(rewards=mapOf("mine_boss" to s.settings.reward(s.settings.bossReward,s.deaths),"mine_secret" to s.settings.reward(s.settings.secretReward,s.deaths)))
        val catalog=s.catalog.copy(rules=rules)
        val bosses=s.wave?.completed?.div(s.settings.waves?.bossEvery?.toLong()?:1L)?:0L
        s.members.values.filter {it.life!=MineLife.LEFT}.forEach {m->
            val rewardKinds=if(s.settings.roomRewards!=null) listOf("boss") else listOf("boss","secret").filter {it!="secret" || s.secret}
            rewardKinds.forEach {kind->
                val event=UUID.nameUUIDFromBytes("mine:${s.id}:$kind".toByteArray(Charsets.UTF_8))
                val reached=s.wave?.completed?:0L
                val request=if(reached>0)CombatRewardRequest("endless.v1",event,m.account,m.actor,"mine_$kind",wave=reached.coerceAtMost(100))
                    else CombatRewardRequest("mine.v1",event,m.account,m.actor,"mine_$kind")
                val rewarded=minOf(s.wave?.completed?:0L,s.settings.waves?.rewardLimit?.toLong()?:Long.MAX_VALUE)
                val finalLoot=s.settings.roomRewards?.prepare(request,s.catalog,rewarded,true,System.currentTimeMillis(),final=true,bossesKilled=bosses,
                    bossEvery=s.settings.waves?.bossEvery?.toLong()?:5L)
                rewards["${m.actor}:$kind"]=finalLoot?:PreparedCombatReward.prepare(request,catalog)
            }
        }
        return s.copy(rewards=rewards)
    }
    private fun recover(s:MineRun)=finishTime(if(!s.terminal)s.copy(phase=MinePhase.ABORTED,epoch=s.epoch+1,reason="restart") else s)

    /** Serialized idempotent entitlement boundary. A cleared wave and its exact rewards share one fsynced journal. */
    fun completeWave(original:MineRun,now:Long=System.currentTimeMillis()):MineRun {
        val s=MineState.expire(original,now);val progress=s.wave?:return s
        if(s.terminal || s.phase!=MinePhase.WAVE || progress.completed>=progress.plan.number)return s
        val rules=requireNotNull(s.settings.waves);val plan=progress.plan
        val completed=progress.copy(completed=plan.number,elapsed=maxOf(progress.elapsed,now-requireNotNull(progress.started)),reached=now)
        val next=if(plan.boss)completed else completed.copy(plan=rules.plan(s.id,Math.addExact(plan.number,1),s.members.size))
        return s.copy(wave=next,phase=if(plan.boss)MinePhase.INTERMISSION else MinePhase.WAVE_WARNING,epoch=s.epoch+1,
            members=s.members.mapValues {(_,m)->if(m.life==MineLife.DOWN)m.copy(life=MineLife.ALIVE,health=m.entryHealth) else m})
    }
    fun continueRun(s:MineRun):MineRun {
        if(s.terminal || s.phase!=MinePhase.INTERMISSION || s.wave==null)return s
        val rules=requireNotNull(s.settings.waves)
        return s.copy(wave=s.wave.copy(plan=rules.plan(s.id,Math.addExact(s.wave.completed,1),s.members.size)),phase=MinePhase.TRANSITION,epoch=s.epoch+1)
    }
    fun win(original:MineRun):MineRun {
        val s=MineState.expire(original,System.currentTimeMillis())
        if(s.terminal || (s.wave==null && s.phase!=MinePhase.BOSS) || (s.wave!=null && s.phase!=MinePhase.WON && s.phase!=MinePhase.INTERMISSION))return s
        return s.copy(phase=MinePhase.WON,epoch=s.epoch+1)
    }
    fun returned(id:UUID,actor:UUID)=update(id) {s->val m=s.members.getValue(actor)
        s.copy(members=s.members+(actor to m.copy(returnPending=false,inWorld=false,awaitingRespawn=false))) }
    fun abort(id:UUID,reason:String)=update(id) {s->if(s.terminal)s else s.copy(phase=MinePhase.ABORTED,epoch=s.epoch+1,reason=reason)}
    override fun close() {closed=true;workers.shutdown();check(workers.awaitTermination(20,TimeUnit.SECONDS)) {"Dungeon journal did not drain"}}
}
