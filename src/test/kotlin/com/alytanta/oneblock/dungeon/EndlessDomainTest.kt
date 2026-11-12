package com.alytanta.oneblock.dungeon

import com.alytanta.oneblock.combat.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.*

class EndlessDomainTest {
    @TempDir lateinit var directory:Path
    private fun settings():MineSettings {
        val f=directory.resolve("dungeon.yml").toFile().apply {writeText(this@EndlessDomainTest.javaClass.getResourceAsStream("/dungeon.yml")!!.reader().readText())}
        return MineSettings.read(f).first!!.copy(enabled=true,allowWorlds=true)
    }
    private fun catalog():CombatRewardCatalog {
        val f=directory.resolve("combat.yml").toFile().apply {writeText(this@EndlessDomainTest.javaClass.getResourceAsStream("/combat.yml")!!.reader().readText())}
        val items=directory.resolve("items.yml").toFile().apply {writeText(this@EndlessDomainTest.javaClass.getResourceAsStream("/items.yml")!!.reader().readText())}
        val c=com.alytanta.oneblock.items.ItemCatalog.read(items).first!!
        val rules=CombatRules.read(f,c,(1..3).flatMap {listOf("pickaxe_$it","axe_$it","hoe_$it")}.toSet()).first!!
        return CombatRewardCatalog(rules,rules.items.mapValues {"exact:${it.key}"})
    }
    private fun run(size:Int=1):MineRun {
        val id=UUID.randomUUID();val config=settings()
        val members=List(size) {MineMember(UUID.randomUUID(),UUID.randomUUID(),"actor$it",20.0,20.0,"SURVIVAL",inWorld=true)}
        return MineRun(id,MineJournal.world(id),config,members.associateBy {it.actor},catalog(),phase=MinePhase.WAVE,
            wave=WaveProgress(config.waves!!.plan(id,1,size),started=1000))
    }
    private fun engine(path:String="runs")=MineEngine(MineJournal(directory.resolve(path)),{_,_->},{}) .also {it.initialize();it.ready.get(5,TimeUnit.SECONDS)}
    @Test fun `unlimited waves reuse finite arenas cap stats and stop all new rewards exactly after limit`() {
        val engine=engine();var s=run(4)
        try {
            val arenas=mutableSetOf<Int>()
            for(n in 1L..1000) {
                val p=s.wave!!.plan;assertEquals(n,p.number);assertEquals(n%5==0L,p.boss);arenas+=p.arena
                assertTrue(p.spawns.size in 1..s.settings.waves!!.limits.entities)
                val previous=s.rewards
                s=engine.completeWave(s,1000+n*100)
                assertEquals(n,s.wave!!.completed);assertEquals(n*100,s.wave!!.elapsed)
                assertEquals(s,engine.completeWave(s,999999))
                if(n>15)assertEquals(previous,s.rewards)
                if(n%5==0L) {assertEquals(MinePhase.INTERMISSION,s.phase);s=engine.continueRun(s)}
                s=s.copy(phase=MinePhase.WAVE)
            }
            assertEquals(0,s.rewards.size);assertTrue(arenas.size>=3)
            val plan=s.settings.waves!!.plan(s.id,Long.MAX_VALUE,4)
            assertTrue(plan.spawns.all {it.health.isFinite() && it.health<=1024 && it.damage<=40 && it.speed<=.4})
        } finally {engine.close()}
    }
    @Test fun `plan frozen across journal reload and wipe and different pools never mix forbidden enemies`() {
        val s=run(2);val journal=MineJournal(directory.resolve("plan"));journal.save(s)
        val loaded=journal.load().single();assertEquals(s,loaded)
        val wiped=s.members.keys.fold(s) {r,id->MineState.death(r,id,false,100)}.let(MineState::wipe)
        assertEquals(s.wave,wiped.wave);assertEquals(MinePhase.WAVE_WARNING,wiped.phase)
        val rules=s.settings.waves!!
        for(n in 1L..200) {
            val plan=rules.plan(s.id,n,2);assertEquals(plan,rules.plan(s.id,n,2))
            val allowed=rules.pools.single {it.id==plan.pool}.enemies
            assertTrue(plan.spawns.all {it.enemy.id in allowed})
        }
        // Old public YAML without endless gets the new route; old serialized settings keep null.
        val old=directory.resolve("old.yml").toFile().apply {writeText("version: 1")}
        val y=org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(old)
        assertEquals(rules,WaveRules.read(y))
    }
    @Test fun `completed time does not drift during boss wait defeat or result synchronization`() {
        val engine=engine();val first=run();val rules=first.settings.waves!!
        try {
            val s=first.copy(wave=first.wave!!.copy(plan=rules.plan(first.id,5,1)))
            val complete=engine.completeWave(s,6000);val record=WaveRecords.record(complete)!!
            assertEquals(5000,record.elapsed);assertEquals(6000,record.reached)
            val failed=complete.copy(phase=MinePhase.ABORTED,reason="attempts",wave=complete.wave!!.copy(ended=900000))
            assertEquals(record.elapsed,WaveRecords.record(failed)!!.elapsed)
            assertEquals(complete.rewards,failed.rewards)
            assertEquals(complete,engine.completeWave(complete,900000))
            assertFalse(WaveRecords.synced(failed));assertTrue(WaveRecords.synced(failed.copy(recordAck=WaveRecords.stamp(failed))))
        } finally {engine.close()}
    }
    @Test fun `duplicate wave clear race creates one set of claims independent from another session`() {
        val engine=engine();val sample=run(2)
        val a=engine.create(sample.settings,sample.members.values.toList(),sample.catalog).get()
        val b=engine.create(sample.settings,run().members.values.toList(),sample.catalog).get()
        val start=engine.update(a.id) {it.copy(phase=MinePhase.WAVE,wave=it.wave!!.copy(started=1000))}.get()
        val pool=Executors.newFixedThreadPool(8);val gate=CyclicBarrier(8)
        try {
            (1..8).map {pool.submit {gate.await();engine.event(a.id,start.epoch) {engine.completeWave(it,5000)}.get()}}.forEach {it.get(10,TimeUnit.SECONDS)}
            val next=engine.get(a.id)!!;assertEquals(1,next.wave!!.completed);assertEquals(0,next.rewards.size)
            assertEquals(0,next.rewards.values.map {it.request.receipt}.distinct().size)
            assertEquals(0,engine.get(b.id)!!.wave!!.completed)
        } finally {pool.shutdownNow();engine.close()}
    }
    @Test fun `restart preserves earned rewards plan and terminal journal until SQL record reward and player acks`() {
        val first=engine("seed");val initial=run();val earned=first.completeWave(initial,5000);first.close()
        val journal=MineJournal(directory.resolve("restart"));journal.save(earned)
        val engine=MineEngine(journal,{_,_->},{})
        engine.initialize();engine.ready.get()
        try {
            val recovered=engine.get(initial.id)!!;assertTrue(recovered.terminal);assertEquals("restart",recovered.reason)
            assertEquals(earned.rewards,recovered.rewards);assertEquals(earned.wave!!.plan,recovered.wave!!.plan)
            assertEquals(4000,recovered.wave.elapsed)
            engine.update(recovered.id) {it.copy(phase=MinePhase.CLOSED,worldRemoved=true,acknowledged=it.rewards.keys,members=it.members.mapValues {(_,m)->m.copy(returnPending=false)})}.get()
            assertNotNull(engine.get(recovered.id));assertEquals(1,journal.load().size)
            engine.update(recovered.id) {it.copy(recordAck=WaveRecords.stamp(it))}.get()
            assertNull(engine.get(recovered.id));assertTrue(journal.load().isEmpty())
        } finally {engine.close()}
    }
    @Test fun `reward limit zero and elapsed timeout do not stop record progression`() {
        val engine=engine();val base=run();val s=base.copy(settings=base.settings.copy(waves=base.settings.waves!!.copy(rewardLimit=0)))
        try {
            val expired=MineState.expire(s,s.created+999999999);assertFalse(expired.terminal)
            val won=engine.completeWave(s,5000);assertTrue(won.rewards.isEmpty());assertEquals(1,won.wave!!.completed)
            val m=s.members.keys.single();val away=MineState.death(s,m,true,100)
            val failed=MineState.reconnectFailed(MineState.reconnect(away,m,101),m,away.members.getValue(m).deadline!!,102)
            assertEquals(away.members.getValue(m).deadline,failed.members.getValue(m).deadline);assertEquals(s.wave!!.plan,failed.wave!!.plan)
        } finally {engine.close()}
    }
    @Test fun `invalid numeric types abilities combinations and unsafe spawn dimensions are rejected`() {
        settings();val file=directory.resolve("dungeon.yml").toFile();val source=file.readText().replace("\r\n","\n")
        val invalid=listOf(source.replace("\"speed\": 0.4","\"speed\": .nan"),source.replace("\"ability\": \"guardian\"","\"ability\": \"eval\""),source.replace("\"size\": 24","\"size\": 12"),source.replace("\"archer\"\n","\"missing\"\n"),source.replace("\"reward-wave-limit\": 15","\"reward-wave-limit\": -1"))
        invalid.forEachIndexed {index,text->assertNotEquals(source,text,"mutation $index");file.writeText(text);assertThrows(Exception::class.java,{MineSettings.read(file)},"mutation $index")}
    }
    @Test fun `leaderboard keeps the best row per player or group and at most five pages`() {
        fun row(wave:Long,elapsed:Long,vararg ids:UUID)=WaveRecord(UUID.randomUUID(),wave,elapsed,null,0,null,null,null,ids.map {WaveParticipant(it,it,"n")},ids.size,"endless","v","h")
        val a=UUID.randomUUID();val b=UUID.randomUUID()
        val best=row(10,500,a);val other=row(9,100,b)
        assertEquals(listOf(best,other),WaveRecords.leaderboard(listOf(best,row(10,900,a),other,row(2,50,a))))
        assertEquals(listOf(best),WaveRecords.leaderboard(listOf(best,row(5,1,a))))
        val group=row(4,4,a,b);assertEquals(listOf(group),WaveRecords.leaderboard(listOf(group,row(3,3,b,a))))
        assertEquals(50,WaveRecords.leaderboard(List(70) {row(70L-it,1,UUID.randomUUID())}).size)
    }
    @Test fun `cube shell is closed with safe empty interior and bounded template count`() {
        val rules=settings().waves!!;val blocks=MineLayout.arenas(rules);assertEquals(blocks.size,blocks.map {Triple(it.x,it.y,it.z)}.distinct().size)
        val positions=blocks.map {Triple(it.x,it.y,it.z)}.toSet()
        rules.arenas.forEachIndexed {slot,a->
            for(x in 0..a.size)for(z in 0..a.size) {assertTrue(Triple(slot*64+x,64,z) in positions);assertTrue(Triple(slot*64+x,65+a.height,z) in positions)}
            for(y in 65 until 65+a.height)for(n in 0..a.size) {
                assertTrue(Triple(slot*64,y,n) in positions);assertTrue(Triple(slot*64+a.size,y,n) in positions)
                assertTrue(Triple(slot*64+n,y,0) in positions);assertTrue(Triple(slot*64+n,y,a.size) in positions)
            }
            assertFalse(Triple((a.entry.x+slot*64).toInt(),65,a.entry.z.toInt()) in positions)
        }
    }
    @Test fun `late callback after final pruning cannot resurrect a run or report a journal fault`() {
        val failures=CopyOnWriteArrayList<Throwable>();val journal=MineJournal(directory.resolve("closed"))
        val engine=MineEngine(journal,{_,_->},failures::add);engine.initialize();engine.ready.get()
        val sample=run();val s=engine.create(sample.settings,sample.members.values.toList(),sample.catalog).get()
        try {
            engine.abort(s.id,"test").get()
            engine.update(s.id) {r->val next=r.copy(worldRemoved=true,phase=MinePhase.CLOSED,members=r.members.mapValues {(_,m)->m.copy(returnPending=false)});next.copy(recordAck=WaveRecords.stamp(next))}.get()
            assertNull(engine.get(s.id));assertThrows(ExecutionException::class.java) {engine.returned(s.id,s.members.keys.single()).get()}
            assertTrue(failures.isEmpty());assertTrue(journal.load().isEmpty())
        } finally {engine.close()}
    }
}
