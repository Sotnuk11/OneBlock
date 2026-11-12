package com.alytanta.oneblock.activities

import com.alytanta.oneblock.island.*
import com.alytanta.oneblock.oneblock.*
import com.alytanta.oneblock.release.*
import com.alytanta.oneblock.market.*
import com.alytanta.oneblock.items.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.UUID

class ActivityDomainTest {
    @TempDir lateinit var dir:Path
    private fun rules():ReleaseRules {
        val f=dir.resolve("progression.yml").toFile();f.writeText(javaClass.getResourceAsStream("/progression.yml")!!.reader().readText())
        val item=dir.resolve("items.yml").toFile();item.writeText(javaClass.getResourceAsStream("/items.yml")!!.reader().readText())
        val base=ReleaseRules.read(f,true).first!!;val a=ActivityRules.read(dir.resolve("activities.yml").toFile());val catalog=ItemCatalog.read(item).first!!
        a.validate(catalog,base);return base.copy(activities=a,recipes=base.recipes+a.recipes,customItems=catalog)
    }
    private fun island()=UUID.randomUUID().let {Island(it,"test",0,60,0,CoreState(CoreMode.WOOD),releaseProgress=ReleaseProgress())}
    private fun accept(i:Island,r:ReleaseRules,id:String="start_mine",now:Long=10000)=ActivityDomain.accept(i,MarketState(),i.owner,id,now,r.activities!!)
    @Test fun `twelve templates offer three at every chapter with no armor coins or combat producer`() {
        val r=rules();val a=r.activities!!;assertEquals(20,a.orders.size)
        ReleaseRules.CHAPTERS.forEach {chapter->assertEquals(3,a.offers(chapter,0).distinct().size);assertTrue(a.offers(chapter,0).all {r.atLeast(chapter,it.chapter)})}
        assertNotEquals(a.offers("start",0).map {it.id}.toSet(),a.offers("start",1).map {it.id}.toSet())
        assertTrue(a.orders.all {it.text.isNotBlank()} && a.orders.map {it.name}.distinct().size==a.orders.size)
        assertTrue(a.orders.none {it.type=="combat"});assertTrue(a.unlocks.isEmpty() && a.recipes.isEmpty());assertEquals(14_400_000,a.orderCooldownMs)
        assertThrows(Exception::class.java) {a.copy(orders=a.orders.map {it.copy(reward=it.reward.copy(items=listOf(ReleaseItem("PLAYER_HEAD",1,"warden_helmet"))))}).validate(r.customItems,r)}
    }
    @Test fun `new actions only shared pace grown requirements and island isolation`() {
        val r=rules();val i=accept(island(),r);val a=r.activities!!;var p=i.releaseProgress!!
        assertEquals(0,p.activity.order!!.progress)
        p=ActivityDomain.credit(p,"STONE",false,true,12000,a);assertEquals(1,p.activity.order!!.progress)
        assertEquals(p,ActivityDomain.credit(p,"STONE",false,true,12001,a))
        assertEquals(0,island().releaseProgress!!.activity.charge)
        val grow=accept(island(),r,"start_grow").releaseProgress!!
        assertEquals(0,ActivityDomain.credit(grow,"OAK_LOG",false,false,12000,a).activity.order!!.progress)
        assertEquals(0,ActivityDomain.credit(grow,"OAK_LOG",true,true,12000,a).activity.order!!.progress)
        assertEquals(1,ActivityDomain.credit(grow,"OAK_LOG",true,false,12000,a).activity.order!!.progress)
    }
    @Test fun `cancel spam cannot reroll offers discard discovery or accept two orders`() {
        val r=rules();val a=r.activities!!;val base=island();val i=accept(base,r)
        assertThrows(Exception::class.java) {accept(i,r)}
        assertThrows(Exception::class.java) {ActivityDomain.cancel(i,MarketState(),i.owner,i.releaseProgress!!.activity.order!!.id,10001,a)}
        val cancelled=ActivityDomain.cancel(i,MarketState(),i.owner,i.releaseProgress!!.activity.order!!.id,13000,a)
        assertEquals(a.offers("start",0),a.offers(cancelled.releaseProgress!!.chapter,cancelled.releaseProgress.activity.completed))
        assertNull(cancelled.releaseProgress.activity.order);assertNotEquals(i.releaseProgress.activity.order!!.id,accept(cancelled,r,now=16000).releaseProgress!!.activity.order!!.id)
        assertThrows(Exception::class.java) {ActivityDomain.accept(base,MarketState(),UUID.randomUUID(),"start_mine",10000,a)}
    }
    @Test fun `shared supplies settle once with one XP award and retained discovery even after cancellation`() {
        val r=rules();val d=ReleaseDomain(r);var i=accept(island(),r,"start_supply");val helper=UUID.randomUUID();i=i.copy(members=mapOf(helper to IslandRole.MEMBER))
        val intent=d.plan(i,MarketState(),helper,"guild-deliver",i.releaseProgress!!.activity.order!!.id.toString())
        assertTrue(intent.ingredients.isNotEmpty());assertEquals(0,intent.coins);assertFalse(intent.personal)
        val t=ItemTransfer(UUID.randomUUID(),i.owner,helper,"STONE",1,TransferKind.RELEASE,0,null,"before","after",release=intent,releaseItems=listOf("exact-component"))
        val prepared=d.prepare(i,MarketState(),t);val result=d.settle(prepared.island,prepared.state,t.id,TransferStatus.DONE)
        assertEquals(1,result.state.claims.size);assertNull(result.state.claims.values.single().recipient)
        assertEquals(100,result.island.releaseProgress!!.member(i.owner).xp["mining"])
        assertTrue(result.island.releaseProgress!!.member(helper).xp.isEmpty());assertEquals(3,result.island.releaseProgress!!.activity.discovery)
        assertEquals(result,d.settle(result.island,result.state,t.id,TransferStatus.DONE))
        assertThrows(Exception::class.java) {accept(result.island,r,now=20000)}
        val at=10000+r.activities!!.orderCooldownMs
        val next=accept(result.island,r,now=at);val cancel=ActivityDomain.cancel(next,result.state,helper,next.releaseProgress!!.activity.order!!.id,at+3000,r.activities!!)
        assertEquals(0,cancel.releaseProgress!!.activity.orderReadyAt)
        assertEquals(3,cancel.releaseProgress!!.activity.discovery)
        assertThrows(Exception::class.java) {d.plan(result.island,result.state,helper,"guild-deliver",intent.value)}
    }
    @Test fun `unapplied supply inventory abort keeps order and awards nothing`() {
        val r=rules();val d=ReleaseDomain(r);val i=accept(island(),r,"start_supply");val q=d.plan(i,MarketState(),i.owner,"guild-deliver",i.releaseProgress!!.activity.order!!.id.toString())
        val t=ItemTransfer(UUID.randomUUID(),i.owner,i.owner,"STONE",1,TransferKind.RELEASE,0,null,"before","after",release=q,releaseItems=listOf("exact"))
        val prep=d.prepare(i,MarketState(),t);assertThrows(Exception::class.java) {ActivityDomain.cancel(prep.island,prep.state,i.owner,i.releaseProgress.activity.order!!.id,30000,r.activities!!)}
        val end=d.settle(prep.island,prep.state,t.id,TransferStatus.ABORTED)
        assertEquals(i.releaseProgress,end.island.releaseProgress);assertTrue(end.state.claims.isEmpty())
    }
    @Test fun `ready persists until consent pause restart downtime never consumes event clock`() {
        val r=rules();var i=island();var p=i.releaseProgress!!
        repeat(r.activities!!.readiness) {n->p=ActivityDomain.credit(p,"STONE",false,true,10000+n*2000L,r.activities!!)}
        assertTrue(p.activity.ready);assertNull(p.activity.event)
        i=i.copy(releaseProgress=p);i=ActivityDomain.start(i,MarketState(),i.owner,"resonance",500000,r.activities!!)
        val id=i.releaseProgress!!.activity.event!!.id
        i=ActivityDomain.tick(i,id,501000,true);assertEquals(1000,i.releaseProgress!!.activity.event!!.elapsed)
        i=ActivityDomain.pause(i);val paused=i.releaseProgress!!.activity.event!!;assertFalse(paused.running)
        i=ActivityDomain.start(i,MarketState(),i.owner,"resonance",99999999,r.activities!!)
        assertEquals(paused.progress,i.releaseProgress!!.activity.event!!.progress);assertEquals(paused.elapsed,i.releaseProgress!!.activity.event!!.elapsed)
        assertThrows(Exception::class.java) {ActivityDomain.start(i,MarketState(),i.owner,"echo",100000000,r.activities!!)}
    }
    @Test fun `event kills match event step interval membership and reward cannot repeat`() {
        val r=rules();var i=island().let {it.copy(releaseProgress=it.releaseProgress!!.copy(activity=ActivityProgress(ready=true)))}
        i=ActivityDomain.start(i,MarketState(),i.owner,"echo",10000,r.activities!!);val e=i.releaseProgress!!.activity.event!!
        assertEquals(i,ActivityDomain.killed(i,e.id,0,10001));assertEquals(i,ActivityDomain.killed(i,UUID.randomUUID(),0,50000))
        repeat(e.rule.goal) {n->i=ActivityDomain.killed(i,e.id,n,10000+(n+1)*e.rule.intervalMs)}
        assertEquals(e.rule.goal,i.releaseProgress!!.activity.event!!.progress)
        val q=ReleaseDomain(r).plan(i,MarketState(),i.owner,"activity-reward",e.id.toString());assertTrue(q.ingredients.isEmpty())
        val t=ItemTransfer(UUID.randomUUID(),i.owner,i.owner,"STONE",1,TransferKind.RELEASE,0,null,"b","b",release=q,releaseItems=listOf(null,"frozen"))
        val d=ReleaseDomain(r);val a=d.prepare(i,MarketState(),t);val b=d.settle(a.island,a.state,t.id,TransferStatus.DONE)
        assertEquals(2,b.state.claims.size);assertFalse(b.island.releaseProgress!!.activity.ready);assertNull(b.island.releaseProgress!!.activity.event)
        assertEquals(b,d.settle(b.island,b.state,t.id,TransferStatus.DONE))
    }
    @Test fun `legacy codec initializes activity fields and frozen active task roundtrips`() {
        val r=rules();val i=accept(island(),r);val gson=com.google.gson.Gson()
        assertEquals(i.releaseProgress,gson.fromJson(ReleaseStateCodec.normalize(gson.toJsonTree(i.releaseProgress)),ReleaseProgress::class.java))
        val old=gson.toJsonTree(ReleaseProgress()).asJsonObject.apply {remove("activity")}
        assertEquals(ActivityProgress(),gson.fromJson(ReleaseStateCodec.normalize(old),ReleaseProgress::class.java).activity)
    }
    @Test fun `timed out attempt cannot share the next event identity or accept a late death`() {
        val r=rules();var i=island().let {it.copy(releaseProgress=it.releaseProgress!!.copy(activity=ActivityProgress(ready=true)))}
        i=ActivityDomain.start(i,MarketState(),i.owner,"echo",10000,r.activities!!)
        val old=i.releaseProgress!!.activity.event!!
        i=i.copy(releaseProgress=i.releaseProgress!!.copy(activity=i.releaseProgress!!.activity.copy(event=old.copy(elapsed=old.rule.durationMs,checkpoint=10000))))
        i=ActivityDomain.tick(i,old.id,11000,true);assertNull(i.releaseProgress!!.activity.event);assertTrue(i.releaseProgress!!.activity.ready)
        val next=ActivityDomain.start(i,MarketState(),i.owner,"resonance",12000,r.activities!!)
        assertNotEquals(old.id,next.releaseProgress!!.activity.event!!.id)
        assertEquals(next,ActivityDomain.killed(next,old.id,0,50000))
    }
}
