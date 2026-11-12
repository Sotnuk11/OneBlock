package com.alytanta.oneblock.hub

import com.alytanta.oneblock.hub.generator.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.*

class HubGeneratorTest {
    @TempDir lateinit var folder:Path
    private fun settings()=HubGeneratorSettings.read(folder.resolve("absent.yml").toFile()).first
    private fun target(rotation:Int=0)=HubTarget("test:explicit",UUID.randomUUID(),-17,100,19,rotation)
    private fun <T> CompletableFuture<T>.done():T=get(20,TimeUnit.SECONDS)
    private class Model:HubBuildPort {
        val blocks=ConcurrentHashMap<HubPoint,HubBlock>()
        var backupFails=false;var stopAfter=Int.MAX_VALUE;var saveFails=false;var safe=true;var serviceReady=true;var active=false
        var writes=0;var backups=0;var activations=0;var removedServices=0
        var gate=CompletableFuture.completedFuture(Unit)
        val executor=Executors.newSingleThreadExecutor()
        override fun validate(target:HubTarget)=CompletableFuture.completedFuture(Unit)
        override fun scan(plan:HubPlan,existing:Boolean,rollback:Boolean,live:()->Boolean)=CompletableFuture.runAsync {
            check(live());val expected=plan.blocks.associateBy {it.point}
            blocks.forEach { (p,b)->if(plan.target.contains(p.x.toDouble(),p.y.toDouble(),p.z.toDouble(),1) && (!rollback||p in expected))
                check(existing && expected[p]==b) {"foreign block $p"}
            }
        }.thenApply {}
        override fun backup(target:HubTarget,id:UUID)=if(backupFails)CompletableFuture.failedFuture<String>(IllegalStateException("backup failed")) else CompletableFuture.completedFuture("backup-$id").also {backups++}
        override fun edit(plan:HubPlan,rollback:Boolean,live:()->Boolean,intent:(Int)->CompletableFuture<Unit>,progress:(Int)->CompletableFuture<Unit>):CompletableFuture<Unit> =
            gate.thenCompose {CompletableFuture.runAsync({
                var count=0
                for(batch in plan.blocks.chunked(256)) {
                    intent(count+batch.size).get(10,TimeUnit.SECONDS)
                    check(live()) {"Paused"}
                    for(b in batch) {
                        check(live()) {"Paused"};check(count<stopAfter) {"simulated crash"}
                        if(rollback) {check(blocks[b.point]==null || blocks[b.point]==b);blocks.remove(b.point)}
                        else {check(blocks[b.point]==null || blocks[b.point]==b);blocks[b.point]=b}
                        count++;writes++
                    }
                    progress(count).get(10,TimeUnit.SECONDS)
                }
            },executor).thenApply {}}
        override fun save(target:HubTarget)=if(saveFails)CompletableFuture.failedFuture<Unit>(IllegalStateException("save failed")) else CompletableFuture.completedFuture(Unit)
        override fun verify(plan:HubPlan)=if(safe)CompletableFuture.completedFuture(Unit) else CompletableFuture.failedFuture<Unit>(IllegalStateException("unsafe spawn"))
        override fun services(plan:HubPlan,id:UUID,apply:Boolean)=CompletableFuture.completedFuture(HubServices(serviceReady,listOf("model")))
        override fun activate(plan:HubPlan)=verify(plan).thenApply {active=true;activations++;Unit}
        override fun canRollback(target:HubTarget)=if(active)CompletableFuture.failedFuture<Unit>(IllegalStateException("active hub")) else CompletableFuture.completedFuture(Unit)
        override fun removeServices(plan:HubPlan,id:UUID,required:Boolean)=CompletableFuture.completedFuture(Unit).also {if(required)removedServices++}
    }
    private fun engine(model:Model)=HubGenerator(settings(),HubJournal(folder.resolve("jobs")),model).apply {ready.done()}
    private fun finish(e:HubGenerator,m:Model) {e.close();m.executor.shutdownNow()}

    @Test fun `template is deterministic bounded and all anchors have clear feet and supported floors for each rotation`() {
        val s=settings()
        for(rotation in listOf(0,90,180,270)) {
            val t=target(rotation);val plan=HubTemplate.plan(t,s);assertEquals(plan,HubTemplate.plan(t,s));assertEquals(plan.hash(),HubTemplate.plan(t,s).hash())
            assertTrue(plan.blocks.size in 15_000..50_000);assertEquals(plan.blocks.size,plan.blocks.map {it.point}.toSet().size)
            val map=plan.blocks.associateBy {it.point}
            plan.blocks.forEach {val p=it.point;assertTrue(t.contains(p.x.toDouble(),p.y.toDouble(),p.z.toDouble()))}
            plan.anchors.forEach {val p=it.point;assertNotNull(map[p.copy(y=p.y-1)],it.role);assertNull(map[p],it.role);assertNull(map[p.copy(y=p.y+1)],it.role)}
            plan.anchors.filter {it.role in setOf("merchant","bazaar","auction","guide","workshop")}.forEach {a->
                val radians=Math.toRadians(a.yaw.toDouble())
                assertTrue(-kotlin.math.sin(radians)*(t.x-a.point.x)+kotlin.math.cos(radians)*(t.z-a.point.z)>0,"NPC must face the plaza: ${a.role}")
            }
            assertEquals(setOf("merchant","bazaar","auction","workshop","guide","spawn","dungeon1","dungeon2","dungeon3"),plan.anchors.map {it.role}.toSet())
            assertTrue(plan.blocks.none {it.data.contains("lava")||it.data.contains("tnt")||it.data.contains("chest")})
            assertTrue(plan.blocks.none {it.data=="minecraft:grass_block"},"Default covered ground must not decay and invalidate rollback")
            // A player can actually walk from the plaza to every service, not just stand at its anchor.
            val passable=plan.blocks.filter {it.point.y==t.y}.map {it.point.copy(y=t.y+1)}.filter {p->
                listOf(p,p.copy(y=p.y+1)).all {map[it]?.data?.contains("sign")!=false}
            }.toSet()
            val reached=mutableSetOf(t.point(0,1,0));val queue=java.util.ArrayDeque(reached)
            while(queue.isNotEmpty()) {val p=queue.removeFirst()
                listOf(p.copy(x=p.x+1),p.copy(x=p.x-1),p.copy(z=p.z+1),p.copy(z=p.z-1)).forEach {next->if(next in passable && reached.add(next))queue.add(next)}
            }
            plan.anchors.forEach {assertTrue(it.point in reached,"Unreachable service: ${it.role}, rotation $rotation")}
        }
    }
    @Test fun `config defaults preserve absent file disable creation and reject dangerous palettes and bad budgets`() {
        val file=folder.resolve("hub.yml").toFile();assertFalse(settings().allowVoid);assertFalse(file.exists())
        file.writeText("version: 1\nallow-void-worlds: true\nblocks-per-batch: 64\n")
        val s=HubGeneratorSettings.read(file).first;assertTrue(s.allowVoid);assertEquals(64,s.batchSize);assertEquals(4,s.sign("guide").size)
        file.appendText("palette:\n  roof: TNT\n");assertThrows(IllegalArgumentException::class.java) {HubGeneratorSettings.read(file)}
        file.writeText("version: 1\nblocks-per-batch: 0\n");assertThrows(IllegalArgumentException::class.java) {HubGeneratorSettings.read(file)}
    }
    @Test fun `preview is read only build consumes actor bound preview and never activates spawn`() {
        val m=Model();val e=engine(m)
        try {
            val p=e.preview("alice",target()).done();assertEquals(0,m.writes);assertEquals(0,m.backups)
            assertThrows(IllegalArgumentException::class.java) {e.build("bob",p.id)}
            val job=e.build("alice",p.id).done();assertEquals(HubJobState.BUILT,job.state);assertNotNull(job.backup)
            assertEquals(job.total,m.blocks.size);assertFalse(m.active)
            assertThrows(IllegalArgumentException::class.java) {e.build("alice",p.id)}
        } finally {finish(e,m)}
    }
    @Test fun `foreign construction after preview aborts before backup and any world edit`() {
        val m=Model();val e=engine(m)
        try {
            val p=e.preview("alice",target()).done();val point=p.plan.target.point(0,0,0)
            m.blocks[point]=HubBlock(point,"minecraft:gold_block")
            val j=e.build("alice",p.id).done();assertEquals(HubJobState.CONFLICT,j.state);assertEquals(0,m.writes);assertEquals(0,m.backups)
        } finally {finish(e,m)}
    }
    @Test fun `latest personal preview replaces old selection and concurrent build consumes it once`() {
        val m=Model();val e=engine(m);val pool=Executors.newFixedThreadPool(2)
        try {
            val old=e.preview("alice",target()).done()
            val bob=e.preview("bob",target().copy(x=500)).done()
            val latest=e.preview("alice",target().copy(x=1000)).done()
            assertEquals(listOf(latest.id),e.previewIds("alice"));assertEquals(listOf(bob.id),e.previewIds("bob"))
            assertThrows(IllegalArgumentException::class.java) {e.build("alice",old.id)}
            assertThrows(IllegalArgumentException::class.java) {e.build("bob",latest.id)}
            val barrier=CyclicBarrier(2)
            val results=(1..2).map {pool.submit<Boolean> {barrier.await(5,TimeUnit.SECONDS);try {e.build("alice").done();true} catch(_:IllegalStateException) {false}}}.map {it.get(30,TimeUnit.SECONDS)}
            assertEquals(1,results.count {it});assertEquals(1,e.all().size);assertEquals(1,m.backups)
            assertTrue(e.previewIds("alice").isEmpty());assertEquals(1000,e.all().single().target.x)
        } finally {pool.shutdownNow();finish(e,m)}
    }
    @Test fun `world references never guess among two jobs and UUID stays exact`() {
        val m=Model();val e=engine(m)
        try {
            val first=e.preview("alice",target()).done();val j1=e.build("alice").done()
            assertEquals(j1.id,e.reference(first.plan.target.key))
            e.preview("alice",first.plan.target.copy(x=1000)).done();val j2=e.build("alice").done()
            assertThrows(IllegalStateException::class.java) {e.reference(first.plan.target.key)}
            assertEquals(j1.id,e.reference(j1.id.toString()));assertEquals(j2.id,e.reference(j2.id.toString()))
            assertThrows(IllegalArgumentException::class.java) {e.reference(first.id.toString())}
            e.rollback("alice",j1.id,false).done();e.rollback("alice",j1.id,true).done()
            assertEquals(j2.id,e.reference(first.plan.target.key))
        } finally {finish(e,m)}
    }
    @Test fun `new command hints preserve old full config hash and custom text without rewriting`() {
        val yaml=org.bukkit.configuration.file.YamlConfiguration().apply {
            loadFromString(javaClass.getResourceAsStream("/hub-generator.yml")!!.reader().readText())
            for(lang in listOf("uk","en"))for(key in listOf("command-help","next-preview","next-job","preview-missing","preview-stale","job-ambiguous"))set("messages.$lang.$key",null)
            set("npcs",null) // Legacy block-plan hashes predate manual NPC appearance settings.
            set("messages.uk.usage","my existing admin help")
        }
        val old=yaml.saveToString();val file=folder.resolve("old-hub.yml");Files.writeString(file,old)
        val read=HubGeneratorSettings.read(file.toFile()).first
        assertEquals(HubPlan.hashText(old),read.hash)
        assertEquals("my existing admin help",read.message("usage"));assertTrue(read.message("next-preview","world" to "test:hub").contains("/obadmin hub build"))
        assertEquals(old,Files.readString(file))
    }
    @Test fun `failed backup cannot mutate blocks and retry still requires empty area`() {
        val m=Model();m.backupFails=true;val e=engine(m)
        try {
            val p=e.preview("alice",target()).done();val j=e.build("alice",p.id).done()
            assertEquals(HubJobState.CONFLICT,j.state);assertEquals(0,m.writes);assertNull(j.backup)
            val b=p.plan.blocks.first();m.blocks[b.point]=b;m.backupFails=false
            assertEquals(HubJobState.CONFLICT,e.resume(j.id).done().state);assertEquals(0,m.writes)
        } finally {finish(e,m)}
    }
    @Test fun `cancel waits for in flight edit then resumes and duplicate concurrent jobs are rejected`() {
        val m=Model();val e=engine(m);m.gate=CompletableFuture()
        try {
            val p=e.preview("alice",target()).done();val work=e.build("alice",p.id)
            val id=e.all().single().id
            assertThrows(IllegalStateException::class.java) {e.build("alice",p.id)}
            val cancelled=e.cancel(id);assertFalse(cancelled.isDone);m.gate.complete(Unit)
            assertEquals(HubJobState.PAUSED,cancelled.done().state);assertEquals(HubJobState.PAUSED,work.done().state)
            assertEquals(HubJobState.BUILT,e.resume(id).done().state)
        } finally {finish(e,m)}
    }
    @Test fun `restart reconciles partly saved world without replaying effects or claiming foreign blocks`() {
        val m=Model();var e=engine(m)
        try {
            val p=e.preview("alice",target()).done();m.stopAfter=512
            val failed=e.build("alice",p.id).done();assertEquals(HubJobState.CONFLICT,failed.state);assertEquals(512,m.blocks.size)
            e.close();m.blocks.keys.take(100).forEach {m.blocks.remove(it)};m.stopAfter=Int.MAX_VALUE
            e=engine(m);assertEquals(HubJobState.PAUSED,e.job(failed.id).state)
            assertEquals(HubJobState.BUILT,e.resume(failed.id).done().state);assertEquals(p.plan.blocks.size,m.blocks.size);assertEquals(1,m.backups)
            assertThrows(IllegalArgumentException::class.java) {e.resume(failed.id)}
        } finally {finish(e,m)}
    }
    @Test fun `rollback validates conflicts preserves foreign edits and refuses an active destination`() {
        val m=Model();val e=engine(m)
        try {
            val p=e.preview("alice",target()).done();val job=e.build("alice",p.id).done()
            e.services(job.id,true).done();e.activate(job.id).done();assertTrue(m.active)
            assertThrows(ExecutionException::class.java) {e.rollback("alice",job.id,false).done()}
            m.active=false
            // A separate unactivated build exercises targeted rollback with an altered block.
            val p2=e.preview("alice",p.plan.target.copy(x=1000)).done();val j=e.build("alice",p2.id).done()
            val b=p2.plan.blocks.first();m.blocks[b.point]=b.copy(data="minecraft:gold_block")
            assertThrows(ExecutionException::class.java) {e.rollback("alice",j.id,false).done()}
            assertEquals("minecraft:gold_block",m.blocks[b.point]!!.data)
            m.blocks[b.point]=b;e.rollback("alice",j.id,false).done()
            assertThrows(IllegalArgumentException::class.java) {e.rollback("bob",j.id,true)}
            assertEquals(HubJobState.ROLLED_BACK,e.rollback("alice",j.id,true).done().state)
            assertTrue(p2.plan.blocks.none {m.blocks.containsKey(it.point)});assertEquals(job.total,m.blocks.size)
        } finally {finish(e,m)}
    }
    @Test fun `reload invalidates previews and resume pins original template while rollback retains its blueprint`() {
        val m=Model();val e=engine(m)
        try {
            val p=e.preview("alice",target()).done();e.applySettings(settings().copy(hash="changed"))
            assertThrows(IllegalArgumentException::class.java) {e.build("alice",p.id)}
            e.applySettings(settings());val p2=e.preview("alice",target()).done();m.stopAfter=256
            val j=e.build("alice",p2.id).done();e.applySettings(settings().copy(hash="changed"))
            assertThrows(IllegalArgumentException::class.java) {e.resume(j.id)}
            m.stopAfter=Int.MAX_VALUE;e.rollback("alice",j.id,false).done();assertEquals(HubJobState.ROLLED_BACK,e.rollback("alice",j.id,true).done().state)
        } finally {finish(e,m)}
    }
    @Test fun `tampered blueprint prevents recovery and no background build starts on load`() {
        val m=Model();val e=engine(m)
        val p=e.preview("alice",target()).done();m.backupFails=true;val j=e.build("alice",p.id).done();e.close()
        val file=folder.resolve("jobs/plan-${j.id}.json");Files.writeString(file,Files.readString(file).replaceFirst("minecraft:","changed:"))
        val reopened=HubGenerator(settings(),HubJournal(folder.resolve("jobs")),m)
        try {assertThrows(ExecutionException::class.java) {reopened.ready.done()};assertEquals(0,m.writes)} finally {finish(reopened,m)}
    }
}
