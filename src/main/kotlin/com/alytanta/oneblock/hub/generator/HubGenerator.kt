package com.alytanta.oneblock.hub.generator

import java.util.UUID
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicBoolean

data class HubServices(val ready:Boolean,val details:List<String>)
interface HubBuildPort {
    fun validate(target:HubTarget):CompletableFuture<Unit>
    fun scan(plan:HubPlan,existing:Boolean,rollback:Boolean,live:()->Boolean):CompletableFuture<Unit>
    fun backup(target:HubTarget,id:UUID):CompletableFuture<String>
    fun verifyBackup(target:HubTarget,name:String):CompletableFuture<Unit> = CompletableFuture.completedFuture(Unit)
    fun edit(plan:HubPlan,rollback:Boolean,live:()->Boolean,intent:(Int)->CompletableFuture<Unit>,progress:(Int)->CompletableFuture<Unit>):CompletableFuture<Unit>
    fun save(target:HubTarget):CompletableFuture<Unit>
    fun verify(plan:HubPlan):CompletableFuture<Unit> = CompletableFuture.completedFuture(Unit)
    fun removeServices(plan:HubPlan,id:UUID,required:Boolean):CompletableFuture<Unit> = CompletableFuture.completedFuture(Unit)
    fun services(plan:HubPlan,id:UUID,apply:Boolean):CompletableFuture<HubServices>
    fun activate(plan:HubPlan):CompletableFuture<Unit>
    fun canRollback(target:HubTarget):CompletableFuture<Unit>
}
data class HubPreview(val id:UUID,val actor:String,val plan:HubPlan,val expires:Long)

/** One admitted job. A paused/conflicted job keeps its reservation across process restarts. */
class HubGenerator(initial:HubGeneratorSettings,private val journal:HubJournal,private val port:HubBuildPort,
    private val now:()->Long=System::currentTimeMillis) {
    @Volatile var settings=initial;private set
    private val io=Executors.newSingleThreadExecutor {r->Thread(r,"oneblock-hub-journal").apply {isDaemon=true}}
    private val admission=Any()
    private val jobs=ConcurrentHashMap<UUID,HubJob>()
    private val previews=ConcurrentHashMap<UUID,HubPreview>()
    private val rollbackPreviews=ConcurrentHashMap<String,Pair<UUID,Long>>()
    private val cachedPlans=ConcurrentHashMap<UUID,HubPlan>()
    private val previewing=AtomicBoolean()
    private val auxiliary=AtomicBoolean()
    private class Run(val id:UUID) { val cancel=AtomicBoolean();lateinit var future:CompletableFuture<HubJob> }
    @Volatile private var running:Run?=null
    @Volatile private var closed=false
    val ready:CompletableFuture<Unit> = async {
        val loaded=journal.load();check(loaded.count {it.reserved}<=1) {"Multiple unfinished hub jobs; manual journal review required"}
        loaded.forEach {job->
            val recovered=if(job.reserved)job.copy(state=HubJobState.PAUSED,error="Interrupted job: explicit resume/rollback required") else job
            if(recovered!=job)journal.save(recovered)
            cachedPlans[job.id]=journal.plan(job)
            jobs[job.id]=recovered
        };Unit
    }
    private fun <T> async(action:()->T)=CompletableFuture.supplyAsync({action()},io)
    private fun guard() {check(!closed && ready.isDone && !ready.isCompletedExceptionally) {"Hub generator not ready/closed"}}
    fun all()=jobs.values.sortedBy {it.id.toString()}
    fun job(id:UUID)=requireNotNull(jobs[id]) {"Unknown hub job"}
    fun plan(id:UUID)=requireNotNull(cachedPlans[id]) {"Blueprint not ready"}
    fun reserved()=jobs.values.filter {it.reserved}
    fun applySettings(next:HubGeneratorSettings) { if(next.hash!=settings.hash)running?.cancel?.set(true);settings=next;previews.clear() }
    fun preview(actor:String,target:HubTarget):CompletableFuture<HubPreview> = synchronized(admission) {
        guard();val config=settings
        previews.entries.removeIf {it.value.expires<now()}
        check(running==null && !auxiliary.get() && previewing.compareAndSet(false,true)) {"Another hub operation is in progress"}
        return port.validate(target).thenCompose {async {HubTemplate.plan(target,config)}}.thenCompose {plan->
            port.scan(plan,false,false) { !closed && settings.hash==config.hash }.thenApply {
                check(settings.hash==config.hash);HubPreview(UUID.randomUUID(),actor,plan,now()+300_000).also {
                    previews.entries.removeIf {entry->entry.value.actor==actor}
                    previews[it.id]=it
                }
            }
        }.whenComplete {_,_->previewing.set(false)}
    }
    private fun persist(job:HubJob)=async {journal.save(job);jobs[job.id]=job;job}
    private fun update(id:UUID,change:(HubJob)->HubJob)=async { val next=change(job(id));journal.save(next);jobs[id]=next;next }
    fun previewIds(actor:String):List<UUID> = synchronized(admission) {
        previews.values.filter {it.actor==actor && it.expires>=now() && it.plan.configHash==settings.hash}.map {it.id}
    }
    /** Exact keys only; multiple jobs in one world require an explicit job UUID. */
    fun reference(raw:String):UUID {
        runCatching {UUID.fromString(raw)}.getOrNull()?.let {return job(it).id}
        return all().filter {it.target.key==raw && it.state!=HubJobState.ROLLED_BACK}.singleOrNull()?.id
            ?:error("Unknown/ambiguous hub world; use a job ID from status")
    }
    fun build(actor:String,previewId:UUID?=null):CompletableFuture<HubJob> = synchronized(admission) {
        guard();check(running==null && !previewing.get() && !auxiliary.get() && reserved().isEmpty()) {"An unfinished hub job or another operation is active"}
        val id=previewId?:previewIds(actor).singleOrNull()?:error("No current personal preview; run preview first")
        val preview=requireNotNull(previews[id]) {"Unknown/consumed preview"}
        require(preview.actor==actor && preview.expires>=now() && preview.plan.configHash==settings.hash) {"Expired/foreign/config-stale preview"}
        previews.remove(id)
        val plan=preview.plan;val job=HubJob(UUID.randomUUID(),actor,plan.target,plan.hash(),plan.configHash,plan.blocks.size)
        jobs[job.id]=job
        start(job,plan,true,false)
    }
    fun resume(id:UUID):CompletableFuture<HubJob> = synchronized(admission) {
        guard();check(running==null && !previewing.get() && !auxiliary.get())
        val job=job(id);require(job.state in setOf(HubJobState.PAUSED,HubJobState.CONFLICT))
        val rollback=job.operation=="rollback"
        require(rollback || job.configHash==settings.hash) {"Restore the pinned template config before resuming, or use rollback"}
        start(job,null,false,rollback)
    }
    private fun start(job:HubJob,initial:HubPlan?,create:Boolean,rollback:Boolean):CompletableFuture<HubJob> {
        val run=Run(job.id);running=run
        val live={ !closed && !run.cancel.get() && running===run }
        val plan=async { val p=initial?:journal.plan(job);if(create)journal.create(job,p);cachedPlans[job.id]=p;p }
        val work=plan.thenCompose {p->
            port.validate(p.target).thenCompose {if(rollback)port.canRollback(p.target) else CompletableFuture.completedFuture(Unit)}
                .thenCompose {port.scan(p,job.backup!=null,rollback,live)}.thenCompose {
                    check(live()) {"Paused"}
                    if(rollback || job.backup!=null)CompletableFuture.completedFuture(job.backup) else
                        update(job.id) {it.copy(state=HubJobState.BACKING_UP)}.thenCompose {port.backup(p.target,job.id)}
                }.thenCompose {backup->
                    check(live()) {"Paused"}
                    val verified=if(backup==null)CompletableFuture.completedFuture(Unit) else port.verifyBackup(p.target,backup)
                    verified.thenCompose {update(job.id) {it.copy(backup=backup,state=if(rollback)HubJobState.ROLLING_BACK else HubJobState.BUILDING,operation=if(rollback)"rollback" else "build",error="")}}
                }.thenCompose {if(rollback)port.removeServices(p,job.id,job.servicesAttempted) else CompletableFuture.completedFuture(Unit)}.thenCompose {
                    port.edit(p,rollback,live,{count->update(job.id) {it.copy(intent=count)}.thenApply {}},
                        {count->update(job.id) {it.copy(placed=maxOf(it.placed,count))}.thenApply {}})
                }.thenCompose {check(live()) {"Paused"};if(rollback)CompletableFuture.completedFuture(Unit) else port.scan(p,true,false,live).thenCompose {port.verify(p)}}
                .thenCompose {port.save(p.target)}
                .thenCompose {update(job.id) {it.copy(state=if(rollback)HubJobState.ROLLED_BACK else HubJobState.BUILT,error="")}}
        }
        val result=CompletableFuture<HubJob>();run.future=result
        work.whenComplete {value,error->
            if(error==null) {running=null;result.complete(value)} else {
                val reason=generateSequence(error as Throwable?) {it.cause}.last().message.orEmpty().take(400)
                update(job.id) {it.copy(state=if(run.cancel.get()||closed)HubJobState.PAUSED else HubJobState.CONFLICT,error=reason)}.whenComplete {failed,writeError->
                    running=null
                    if(writeError!=null)result.completeExceptionally(writeError) else result.complete(failed)
                }
            }
        }
        return result
    }
    fun cancel(id:UUID):CompletableFuture<HubJob> = synchronized(admission) {
        guard();val run=running
        if(run==null) {require(job(id).state==HubJobState.PAUSED);CompletableFuture.completedFuture(job(id))}
        else {require(run.id==id);run.cancel.set(true);run.future}
    }
    fun rollback(actor:String,id:UUID,confirm:Boolean):CompletableFuture<HubJob> = synchronized(admission) {
        guard();check(running==null && !previewing.get() && !auxiliary.get());val job=job(id);require(job.state!=HubJobState.ROLLED_BACK)
        if(!confirm) {
            check(previewing.compareAndSet(false,true))
            return async {journal.plan(job)}.thenCompose {plan->port.validate(plan.target).thenCompose {port.canRollback(plan.target)}
                .thenCompose {port.scan(plan,job.backup!=null,true) {!closed}}
            }.thenApply {rollbackPreviews[actor]=id to (now()+300_000);job}.whenComplete {_,_->previewing.set(false)}
        }
        val preview=rollbackPreviews.remove(actor);require(preview?.first==id && preview.second>=now()) {"Run rollback preview first"}
        require(reserved().all {it.id==id})
        start(job,null,false,true)
    }
    private fun <T> exclusive(action:()->CompletableFuture<T>):CompletableFuture<T> = synchronized(admission) {
        guard();check(running==null && !previewing.get() && auxiliary.compareAndSet(false,true)) {"Another hub operation is active"}
        try {action().whenComplete {_,_->auxiliary.set(false)}} catch(e:Throwable) {auxiliary.set(false);throw e}
    }
    fun services(id:UUID,apply:Boolean):CompletableFuture<HubServices> = exclusive {
        guard();val job=job(id);require(!job.reserved && job.state!=HubJobState.ROLLED_BACK)
        val prepared=if(apply)update(id) {it.copy(servicesAttempted=true)} else CompletableFuture.completedFuture(job)
        prepared.thenCompose {async {journal.plan(it)}}.thenCompose {port.services(it,id,apply)}.thenCompose {result->
            if(result.ready && job.state!=HubJobState.ACTIVE)update(id) {it.copy(state=HubJobState.SERVICES_READY)}.thenApply {result}
            else CompletableFuture.completedFuture(result)
        }
    }
    fun activate(id:UUID):CompletableFuture<HubJob> = exclusive {
        guard();val job=job(id);require(job.state in setOf(HubJobState.BUILT,HubJobState.SERVICES_READY,HubJobState.ACTIVE))
        async {journal.plan(job)}.thenCompose {plan->port.validate(plan.target).thenCompose {port.services(plan,id,false)}.thenCompose {
            check(it.ready) {"Services not ready: ${it.details.joinToString()}"};port.activate(plan)
        }}.thenCompose {update(id) {it.copy(state=HubJobState.ACTIVE)}}
    }
    fun worldIntent(key:String)=async {guard();journal.worldIntent(key)}
    fun close() {
        if(closed)return
        closed=true;val run=running;run?.cancel?.set(true);previews.clear()
        // Do not wait on region callbacks during disable. Their prewritten intents remain authoritative.
        if(run!=null)async {val old=job(run.id);val paused=old.copy(state=HubJobState.PAUSED,error="Plugin stopped; verify and resume explicitly");journal.save(paused);jobs[run.id]=paused}
        io.shutdown()
    }
}
