package com.alytanta.oneblock.dungeon

import com.alytanta.oneblock.combat.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.*

class MineDomainTest {
    @TempDir lateinit var directory:Path
    private fun settings():MineSettings=directory.resolve("dungeon.yml").toFile().let {file->
        file.writeText(javaClass.getResourceAsStream("/dungeon.yml")!!.reader().readText());MineSettings.read(file).first!!.copy(enabled=true,allowWorlds=true,waves=null,entry=MineEntry("minecraft:overworld",MinePoint(0.5,65.0,0.5))) }
    private fun catalog():CombatRewardCatalog=directory.resolve("combat.yml").toFile().let {file->
        file.writeText(javaClass.getResourceAsStream("/combat.yml")!!.reader().readText())
        val itemsFile=directory.resolve("items.yml").toFile().apply {writeText(this@MineDomainTest.javaClass.getResourceAsStream("/items.yml")!!.reader().readText())}
        val items=com.alytanta.oneblock.items.ItemCatalog.read(itemsFile).first!!
        val tools=(1..3).flatMap {tier->listOf("pickaxe_$tier","axe_$tier","hoe_$tier")}.toSet()
        val rules=CombatRules.read(file,items,tools).first!!;CombatRewardCatalog(rules,rules.items.mapValues {"exact:${it.key}"}) }
    private fun member()=MineMember(UUID.randomUUID(),UUID.randomUUID(),"test",9.0,9.0,"SURVIVAL",inWorld=true)
    private fun run(count:Int=1):MineRun {val id=UUID.randomUUID();return MineRun(id,MineJournal.world(id),settings(),List(count){member()}.associateBy {it.actor},catalog(),phase=MinePhase.ENTRY)}
    @Test fun `death quit and duplicate death within encounter count once while wipe advances attempt`() {
        val s=run(2);val actors=s.members.keys.toList()
        val first=MineState.death(s,actors[0],false,1);val away=MineState.death(first,actors[0],true,2)
        assertEquals(away.members.getValue(actors[0]).deadline,MineState.death(away,actors[0],true,999).members.getValue(actors[0]).deadline)
        assertEquals(away.members.getValue(actors[0]).deadline,MineState.death(away,actors[0],false,1000).members.getValue(actors[0]).deadline)
        assertEquals(1,away.deaths);assertEquals(0,MineState.wipe(away).wipes)
        val wiped=MineState.wipe(MineState.death(away,actors[1],false,3))
        assertEquals(2,wiped.deaths);assertEquals(1,wiped.wipes);assertEquals(2,wiped.remaining);assertEquals(2,wiped.epoch)
        assertEquals(9.0,wiped.members.getValue(actors[1]).health)
    }
    @Test fun `solo outside waits for reconnect then consumes one attempt and never creates extra life`() {
        val s=run();val actor=s.members.keys.single();val away=MineState.death(s,actor,true,100)
        assertEquals(0,MineState.wipe(away).wipes)
        val back=MineState.wipe(MineState.reconnect(away,actor,200));assertEquals(1,back.wipes);assertEquals(1,back.deaths)
        assertThrows(IllegalArgumentException::class.java) {MineState.reconnect(away,actor,200000)}
        assertEquals(MinePhase.ABORTED,MineState.expire(away,200000).phase)
    }
    @Test fun `failed reconnect transport cannot abort party add a death or extend the original deadline`() {
        val s=run(2);val actor=s.members.keys.first();val away=MineState.death(s,actor,true,100)
        val original=away.members.getValue(actor).deadline!!
        val admitted=MineState.reconnect(away,actor,200)
        val failed=MineState.reconnectFailed(admitted,actor,original,300)
        assertEquals(MinePhase.ENTRY,failed.phase);assertEquals(1,failed.deaths);assertEquals(original,failed.members.getValue(actor).deadline)
        assertFalse(failed.members.getValue(actor).inWorld);assertEquals(s.members.getValue(s.members.keys.last()),failed.members.getValue(s.members.keys.last()))
        assertEquals(failed,MineState.reconnectFailed(failed,actor,original,400))
        val won=s.copy(phase=MinePhase.WON);assertEquals(won,MineState.reconnectFailed(won,actor,original,500))
    }
    @Test fun `three total attempts and puzzle failure retries without fee or death`() {
        var s=run();val actor=s.members.keys.single()
        repeat(3) {s=MineState.respawn(MineState.wipe(MineState.death(s,actor,false,it.toLong())),actor)}
        assertEquals(MinePhase.ABORTED,s.phase);assertEquals(0,s.remaining);assertEquals(3,s.deaths)
        var puzzle=run().copy(phase=MinePhase.PUZZLE);val p=puzzle.members.keys.single()
        puzzle=MineState.lever(puzzle,p,2);assertEquals(0,puzzle.puzzleStep);assertEquals(0,puzzle.deaths)
        puzzle.settings.puzzle.forEach {puzzle=MineState.lever(puzzle,p,it)};assertEquals(MinePhase.BOSS,puzzle.phase)
    }
    @Test fun `config death tiers reduce rewards and preserve guaranteed route`() {
        val c=settings();assertEquals(4,c.reward(c.bossReward,0).guaranteed.first().amount)
        assertEquals(3,c.reward(c.bossReward,1).guaranteed.first().amount)
        assertEquals(2,c.reward(c.bossReward,2).guaranteed.first().amount)
        assertEquals(1,c.reward(c.bossReward,100).guaranteed.first().amount)
        assertTrue(c.reward(c.bossReward,2).trophies.first().chance<c.reward(c.bossReward,1).trophies.first().chance)
        val wrong=directory.resolve("dungeon.yml").toFile();wrong.writeText(wrong.readText().replace("\"guaranteed\": 0.5","\"guaranteed\": 1.1"))
        assertThrows(IllegalArgumentException::class.java) {MineSettings.read(wrong)}
    }
    @Test fun `concurrent admission same actor is atomic and independent sessions do not share transitions`() {
        val errors=CopyOnWriteArrayList<Throwable>();val engine=MineEngine(MineJournal(directory.resolve("runs")),{_,_->},errors::add)
        engine.initialize();engine.ready.get(5,TimeUnit.SECONDS)
        val pool=Executors.newFixedThreadPool(8);val member=member();val config=settings();val catalog=catalog();val latch=CountDownLatch(1)
        try {
            val futures=(1..8).map {pool.submit<MineRun?> {latch.await();runCatching {engine.create(config,listOf(member),catalog).get(5,TimeUnit.SECONDS)}.getOrNull()}}
            latch.countDown();val admitted=futures.mapNotNull {it.get(10,TimeUnit.SECONDS)};assertEquals(1,admitted.size)
            val other=engine.create(config,listOf(member()),catalog).get(5,TimeUnit.SECONDS)
            val first=admitted.single();engine.update(first.id) {it.copy(phase=MinePhase.ENTRY)}.get()
            engine.event(first.id,1) {MineState.death(it,member.actor,false,1)}.get()
            assertEquals(1,engine.get(first.id)!!.deaths);assertEquals(0,engine.get(other.id)!!.deaths)
        } finally {pool.shutdownNow();engine.close()}
    }
    @Test fun `barrier races boss death quit and stale callbacks produce at most one frozen entitlement`() {
        val engine=MineEngine(MineJournal(directory.resolve("race")),{_,_->},{})
        engine.initialize();engine.ready.get();val s=run(2);val created=engine.create(s.settings,s.members.values.toList(),s.catalog).get()
        engine.update(created.id) {it.copy(phase=MinePhase.BOSS,secret=true)}.get()
        val actor=s.members.keys.first();val gate=CyclicBarrier(3);val pool=Executors.newFixedThreadPool(3)
        try {
            val work=listOf(
                pool.submit {gate.await();engine.event(created.id,1) {engine.win(it)}.get()},
                pool.submit {gate.await();engine.event(created.id,1) {MineState.death(it,actor,true,System.currentTimeMillis())}.get()},
                pool.submit {gate.await();engine.event(created.id,1) {engine.win(it)}.get()})
            work.forEach {it.get(10,TimeUnit.SECONDS)}
            val won=engine.get(created.id)!!;assertEquals(MinePhase.WON,won.phase);assertEquals(2,won.rewards.size)
            assertEquals(2,won.rewards.values.map {it.request.receipt}.distinct().size)
            engine.event(created.id,1) {it.copy(deaths=999)}.get();assertEquals(won,engine.get(created.id))
        } finally {pool.shutdownNow();engine.close()}
    }
    @Test fun `journal failed publish preserves old state and never releases actor reservation`() {
        var reject=false;val journal=MineJournal(directory.resolve("fault")) {if(reject)throw java.io.IOException("test fs fault")}
        val engine=MineEngine(journal,{_,_->},{})
        engine.initialize();engine.ready.get();val s=run();val member=s.members.values.single();val created=engine.create(s.settings,listOf(member),s.catalog).get()
        try {
            reject=true;assertThrows(ExecutionException::class.java) {engine.returned(created.id,member.actor).get()}
            assertNotNull(engine.binding(member.actor));assertTrue(journal.load().single().members.getValue(member.actor).returnPending)
        } finally {engine.close()}
    }
    @Test fun `barrier boss victory administrator stop and departure have one durable terminal outcome`() {
        val engine=MineEngine(MineJournal(directory.resolve("terminal-race")),{_,_->},{})
        engine.initialize();engine.ready.get();val original=run(2)
        val s=engine.create(original.settings,original.members.values.toList(),original.catalog).get()
        engine.update(s.id) {it.copy(phase=MinePhase.BOSS)}.get()
        val barrier=CyclicBarrier(3);val pool=Executors.newFixedThreadPool(3)
        try {
            val jobs=listOf(
                pool.submit {barrier.await(5,TimeUnit.SECONDS);engine.event(s.id,1) {engine.win(it)}.get(5,TimeUnit.SECONDS)},
                pool.submit {barrier.await(5,TimeUnit.SECONDS);engine.abort(s.id,"administrator").get(5,TimeUnit.SECONDS)},
                pool.submit {barrier.await(5,TimeUnit.SECONDS);engine.event(s.id,1) {MineState.death(it,s.members.keys.first(),true,System.currentTimeMillis())}.get(5,TimeUnit.SECONDS)})
            jobs.forEach {it.get(10,TimeUnit.SECONDS)}
            val terminal=engine.get(s.id)!!
            assertTrue(terminal.phase in setOf(MinePhase.WON,MinePhase.ABORTED))
            assertEquals(if(terminal.phase==MinePhase.WON)2 else 0,terminal.rewards.size)
            assertEquals(terminal,MineJournal(directory.resolve("terminal-race")).load().single())
            engine.event(s.id,1) {it.copy(phase=MinePhase.BOSS)}.get();assertEquals(terminal,engine.get(s.id))
        } finally {pool.shutdownNow();engine.close()}
    }
    @Test fun `physical death wipe then quit before respawn cannot charge second death across epoch`() {
        val s=run();val actor=s.members.keys.single()
        val wiped=MineState.wipe(MineState.death(s,actor,false,1))
        assertEquals(2,wiped.epoch);assertTrue(wiped.members.getValue(actor).awaitingRespawn)
        val quit=MineState.death(wiped,actor,true,2)
        assertEquals(1,quit.deaths);assertEquals(1,quit.wipes)
        val back=MineState.reconnect(MineState.respawn(quit,actor),actor,3)
        assertEquals(1,back.deaths)
    }
    @Test fun `one stalled session journal does not block another and no-op stale events do not fsync`() {
        val blocked=CountDownLatch(1);val release=CountDownLatch(1);val writes=java.util.concurrent.atomic.AtomicInteger()
        val target=java.util.concurrent.atomic.AtomicReference<UUID>()
        val journal=MineJournal(directory.resolve("parallel")) {s->writes.incrementAndGet();if(s.id==target.get()) {blocked.countDown();check(release.await(5,TimeUnit.SECONDS))}}
        val engine=MineEngine(journal,{_,_->},{})
        engine.initialize();engine.ready.get()
        try {
            val s=run();val a=engine.create(s.settings,s.members.values.toList(),s.catalog).get()
            val b=engine.create(s.settings,listOf(member()),s.catalog).get();val initial=writes.get()
            engine.event(a.id,999) {error("stale callback executed")}.get();assertEquals(initial,writes.get())
            target.set(a.id);val waiting=engine.update(a.id) {it.copy(phase=MinePhase.ENTRY)}
            assertTrue(blocked.await(2,TimeUnit.SECONDS));engine.update(b.id) {it.copy(phase=MinePhase.PUZZLE)}.get(2,TimeUnit.SECONDS)
            assertFalse(waiting.isDone);release.countDown();waiting.get(2,TimeUnit.SECONDS)
        } finally {release.countDown();engine.close()}
    }
    @Test fun `restart aborts combat preserves frozen earnings and removes completed journal only after all acks and returns`() {
        val s=run(2).copy(phase=MinePhase.BOSS,secret=true)
        val journal=MineJournal(directory.resolve("restart"));val first=MineEngine(journal,{_,_->},{})
        first.initialize();first.ready.get();val won=first.win(s);journal.save(won);first.close()
        val engine=MineEngine(journal,{_,_->},{})
        engine.initialize(journal.load());engine.ready.get()
        try {
            assertEquals(won.rewards,engine.get(s.id)!!.rewards)
            assertFalse(engine.hasPendingReward(s.members.keys.first()))
            engine.update(s.id) {it.copy(worldRemoved=true,phase=MinePhase.CLOSED,acknowledged=it.rewards.keys)}.get()
            assertTrue(engine.work().isEmpty());assertFalse(engine.hasPendingReward(s.members.keys.first()))
            assertEquals(1,journal.load().size)
            s.members.keys.forEach {engine.returned(s.id,it).get()}
            assertNull(engine.get(s.id));assertTrue(journal.load().isEmpty())
        } finally {engine.close()}
    }
}
