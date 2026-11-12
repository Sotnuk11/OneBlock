package com.alytanta.oneblock.rewards

import com.alytanta.oneblock.combat.*
import com.alytanta.oneblock.island.*
import com.alytanta.oneblock.market.*
import com.alytanta.oneblock.oneblock.*
import com.alytanta.oneblock.release.ReleaseProgress
import com.google.gson.Gson
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.UUID

class RoomRewardsTest {
    @TempDir lateinit var folder:Path
    private fun catalog():CombatRewardCatalog {
        fun file(name:String)=folder.resolve(name).toFile().apply {writeText(this@RoomRewardsTest.javaClass.getResourceAsStream("/$name")!!.reader().readText())}
        val items=com.alytanta.oneblock.items.ItemCatalog.read(file("items.yml")).first!!
        val rules=CombatRules.read(file("combat.yml"),items,(1..3).flatMap {listOf("pickaxe_$it","axe_$it","hoe_$it")}.toSet()).first!!
        return CombatRewardCatalog(rules,rules.items.mapValues {"exact:${it.key}"})
    }
    private fun rules():RoomRewardRules=org.bukkit.configuration.file.YamlConfiguration().let {y->y.loadFromString(javaClass.getResourceAsStream("/dungeon.yml")!!.reader().readText());RoomRewardRules.read(y)}
    private fun island()=UUID.randomUUID().let {Island(it,"test",0,64,0,CoreState(CoreMode.WOOD),coins=10000,releaseProgress=ReleaseProgress(chapter="developed"))}
    private fun request(i:Island)=CombatRewardRequest("room.test",UUID.randomUUID(),i.owner,i.ownerPlayer,"room")
    private fun paid()=rules().let {r->r.copy(rules=r.rules.filter {it.id=="run_paid"}.map {x->x.copy(final=false,boss="boss",min=1,minBosses=0,chance=1.0,price=1000,equipmentPriceBonus=0,perWave=emptyList(),perBoss=emptyList(),pricePerBoss=0,pool=x.pool.filter {c->c.item.endsWith("_helmet")})})}
    private fun freeNow()=rules().let {r->r.copy(rules=r.rules.filter {it.id=="run_free"}.map {x->x.copy(final=false,boss="any",min=1,minBosses=0,perWave=emptyList())})}
    @Test fun `chests hold fixed loot and waves only raise the gear chance`() {
        val i=island();val reward=rules().prepare(request(i),catalog(),15,true,1000,final=true,bossesKilled=3)
        assertTrue(reward.items.isEmpty());assertEquals(2,reward.room!!.chests.size)
        val free=reward.room.chests.single {it.price==0L};val paid=reward.room.chests.single {it.price>0L}
        assertEquals(4,free.items.size);assertEquals(10,paid.items.size)
        val short=rules().prepare(request(i),catalog(),3,true,1000,final=true,bossesKilled=0)
        assertEquals(listOf(1),short.room!!.chests.map {it.items.size})
        fun gear(wave:Long)=(0 until 400).count {n->val r=rules().prepare(CombatRewardRequest("room.test",UUID.nameUUIDFromBytes("$wave:$n".toByteArray()),i.owner,UUID.nameUUIDFromBytes("$wave-$n".toByteArray()),"room"),catalog(),wave,true,1000,final=true,bossesKilled=1)
            r.room!!.chests.single {it.price>0L}.items.any {it.id!="oneblock:battle_fragment"}}
        assertTrue(gear(15)>gear(1)+40,"gear ${gear(1)} ${gear(15)}")
    }
    @Test fun `luck counter when an admin turns it on offers a missing part after five runs with a boss and skips runs without one`() {
        val i=island();val c=catalog();val r=rules().copy(pityAfter=5,pityPrice=1000);var state=MarketState()
        val empty=r.prepare(request(i),c,3,true,1000,final=true,bossesKilled=0);assertNull(empty.room!!.pity)
        repeat(4) {n->state=CombatRewardDomain.grantPrepared(i,state,r.prepare(request(i),c,15,true,1000L+n,final=true,bossesKilled=3),100000).state}
        assertTrue(state.chests.values.none {it.label.startsWith("Гарантована")})
        state=CombatRewardDomain.grantPrepared(i,state,r.prepare(request(i),c,15,true,2000,final=true,bossesKilled=3),100000).state
        val offer=state.chests.values.single {it.label.startsWith("Гарантована")}
        assertEquals(1000,offer.price);assertTrue(offer.items.single().id.startsWith("oneblock:warden_"))
    }
    @Test fun `paid missing parts progress under adversarial duplicate rolls within twelve bosses`() {
        val c=catalog();var i=island();var state=MarketState();val all=c.rules.items.values.filter {it.set=="warden"}.sortedBy {it.armorSlot}.map {LootItem("oneblock:${it.id}",it.material,c.encoded.getValue(it.id))}
        val parts=c.rules.items.values.filter {it.kind==CombatKind.ARMOR}.groupBy {it.set!!}.mapValues {(_,xs)->xs.sortedBy {it.armorSlot}.map {LootItem("oneblock:${it.id}",it.material,c.encoded.getValue(it.id))}}
        val bought=mutableSetOf<String>()
        repeat(12) {boss->
            val room=PreparedRoomLoot(listOf(PreparedChest("duplicate",500,listOf(all.first()))),PreparedPity(3,500,parts),1000L+boss)
            val req=request(i);val prepared=PreparedCombatReward(req.copy(outputHash=PreparedCombatReward.payloadHash(emptyList(),room)),emptyList(),room)
            val result=CombatRewardDomain.grantPrepared(i,state,prepared,100000);state=result.state
            state.chests.values.filter {it.status=="OFFERED" && it.items.single().id !in bought && it.items.single().id.startsWith("oneblock:warden_")}.toList().forEach {offer->
                if(bought.add(offer.items.single().id)) {val opened=ChestDomain.open(i,state,i.ownerPlayer,offer,2000L+boss);state=opened.state;i=i.copy(coins=opened.coins)}
            }
        }
        assertEquals(4,bought.size);assertEquals(8000,i.coins);assertEquals(4,state.chests.values.count {it.status=="OPENED"})
        assertEquals(4,state.claims.size)
    }
    @Test fun `prepared loot price and identities survive serialization reload and deterministic retry`() {
        val i=island();val r=rules();val c=catalog();r.validate(c.rules);val request=request(i)
        val first=paid().prepare(request,c,5,true,1000)
        assertEquals(first,paid().prepare(request,c,5,true,1000));assertEquals(first,Gson().fromJson(Gson().toJson(first),PreparedCombatReward::class.java))
        first.validate();val state=CombatRewardDomain.grantPrepared(i,MarketState(),first,100000).state
        assertEquals(1,state.chests.size);assertEquals(1000,state.chests.values.single().price)
        assertEquals(ChestDomain.TTL+1000,state.chests.values.single().expires)
        assertEquals(state,CombatRewardDomain.grantPrepared(i,state,first,100000).state)
        val mutated=first.copy(room=first.room!!.copy(chests=first.room.chests.map {it.copy(price=1)}))
        assertThrows(IllegalArgumentException::class.java) {mutated.validate()}
    }
    @Test fun `wallet open is one atomic idempotent operation and content never expires after payment`() {
        val i=island();val p=paid().prepare(request(i),catalog(),5,true,1000)
        val book=CombatRewardDomain.grantPrepared(i,MarketState(),p,100000).state;val offer=book.chests.values.single()
        val opened=ChestDomain.open(i,book,i.ownerPlayer,offer,offer.expires!!-1)
        assertEquals(i.coins-1000,opened.coins);assertEquals(book.claims.size+offer.items.size,opened.state.claims.size)
        val again=ChestDomain.open(i.copy(coins=opened.coins),opened.state,i.ownerPlayer,offer,offer.expires+999999)
        assertEquals(opened.coins,again.coins);assertEquals(opened.state,again.state)
        assertTrue(again.state.claims.values.any {it.source=="chest:${offer.id}" && it.recipient==i.ownerPlayer})
    }
    @Test fun `expiry is exclusive at exact boundary and failure never spends or creates content`() {
        val i=island();val reward=paid().prepare(request(i),catalog(),5,true,1000)
        val book=CombatRewardDomain.grantPrepared(i,MarketState(),reward,100000).state;val offer=book.chests.values.single()
        assertTrue(offer.available(offer.expires!!-1));assertFalse(offer.available(offer.expires));assertFalse(offer.copy(status="OPENED").available(1001))
        for(now in listOf(offer.expires!!,offer.expires+1))assertThrows(IllegalArgumentException::class.java) {ChestDomain.open(i,book,i.ownerPlayer,offer,now)}
        assertThrows(IllegalArgumentException::class.java) {ChestDomain.open(i.copy(coins=0),book,i.ownerPlayer,offer,1001)}
        assertThrows(IllegalArgumentException::class.java) {ChestDomain.open(i,book,UUID.randomUUID(),offer,1001)}
        assertThrows(IllegalArgumentException::class.java) {ChestDomain.open(i.copy(owner=UUID.randomUUID()),book,i.ownerPlayer,offer,1001)}
        assertEquals("OFFERED",book.chests[offer.id]!!.status)
    }
    @Test fun `free chest still requires individual open and ordinary collections cannot consume it`() {
        val i=island();val p=freeNow().prepare(request(i),catalog(),3,false,1000)
        val book=CombatRewardDomain.grantPrepared(i,MarketState(),p,100000).state;val free=book.chests.values.single()
        assertEquals(0,free.price);assertNull(free.expires)
        val withoutOrdinary=book.copy(claims=emptyMap());assertEquals(book.chests,withoutOrdinary.chests)
        val opened=ChestDomain.open(i,withoutOrdinary,i.ownerPlayer,free,Long.MAX_VALUE)
        assertEquals(i.coins,opened.coins);assertEquals(1,opened.state.claims.size)
    }
    @Test fun `pity guarantees chosen set offer stores each set counter and replay cannot advance it`() {
        val i=island();val c=catalog();val rules=rules().copy(rules=rules().rules.filterNot {it.chest},pityAfter=3,pityPrice=500)
        var state=MarketState();val progressId=ChestDomain.progressId(i.owner,i.ownerPlayer)
        repeat(3) {index->
            val p=rules.prepare(request(i),c,5L*(index+1),true,1000L+index)
            val next=CombatRewardDomain.grantPrepared(i,state,p,100000).state
            assertEquals(next,CombatRewardDomain.grantPrepared(i,next,p,100000).state);state=next
        }
        assertEquals(1,state.chests.size);val guaranteed=state.chests.values.single()
        assertTrue(guaranteed.items.single().id.startsWith("oneblock:warden_"));assertEquals(500,guaranteed.price)
        assertEquals(0,state.chestProgress[progressId]!!.counters.getValue("warden").misses)
        val p=state.chestProgress.getValue(progressId);state=state.copy(chestProgress=state.chestProgress+(progressId to p.copy(selected="ranger")))
        state=CombatRewardDomain.grantPrepared(i,state,rules.prepare(request(i),c,20,true,5000),100000).state
        assertEquals(1,state.chestProgress.getValue(progressId).counters.getValue("ranger").misses)
        assertEquals(0,state.chestProgress.getValue(progressId).counters.getValue("warden").misses)
    }
    @Test fun `armor is crafted from enchanted fragments while old frozen armor is not confiscated`() {
        val c=catalog();assertEquals(12,c.rules.items.values.count {it.kind==CombatKind.ARMOR})
        assertEquals(12,c.rules.recipes.values.count {r->r.items.any {c.rules.items[it.tool]?.kind==CombatKind.ARMOR} && r.ingredients.any {it.combatId=="enchanted_fragment"}})
        assertTrue(c.rules.rewards.values.all {r->(r.guaranteed+r.trophies).none {c.rules.items[it.item]?.kind==CombatKind.ARMOR}})
        val i=island();val item=FrozenCombatItem("PLAYER_HEAD","legacy:exact-armor")
        val request=request(i);val legacy=PreparedCombatReward(request.copy(outputHash=PreparedCombatReward.hash(listOf(item))),listOf(item))
        legacy.validate();assertEquals(item.itemData,CombatRewardDomain.grantPrepared(i,MarketState(),legacy,10000).state.claims.values.single().itemData)
    }
    @Test fun `new ordinary market transaction preserves chest and pity maps`() {
        val i=island();val reward=paid().prepare(request(i),catalog(),5,true,1000)
        val book=CombatRewardDomain.grantPrepared(i,MarketState(),reward,10000).state
        val y=org.bukkit.configuration.file.YamlConfiguration().apply {loadFromString(javaClass.getResourceAsStream("/config.yml")!!.reader().readText())}
        val settings=MarketSettings.load(y)
        val next=MarketEngine(settings).transaction(i.owner,i.ownerPlayer,i.coins,book) {Unit}.state
        assertEquals(book.chests,next.chests);assertEquals(book.chestProgress,next.chestProgress)
    }
    @Test fun `invalid costs chances armor-free rewards and giant payload plans fail before play`() {
        val c=catalog();val r=rules()
        assertThrows(IllegalArgumentException::class.java) {r.copy(rules=listOf(RoomLootRule("bad",false,1,99,1,"any",1.0,0,listOf(RewardEntry("warden_helmet",1)),0,emptyList()))).validate(c.rules)}
        val y=org.bukkit.configuration.file.YamlConfiguration().apply {loadFromString(javaClass.getResourceAsStream("/dungeon.yml")!!.reader().readText())}
        y.set("room-rewards.pity.price",-1)
        assertThrows(IllegalArgumentException::class.java) {RoomRewardRules.read(y)}
        assertDoesNotThrow {r.validateBudget(c,15,4,5)}
        assertThrows(IllegalArgumentException::class.java) {r.validateBudget(c.copy(encoded=c.encoded.mapValues {"x".repeat(100000)}),100,4,1)}
    }
    @Test fun `base64 and URL texture formats normalize safely while named profile definitions remain supported`() {
        val url="https://textures.minecraft.net/texture/"+"a".repeat(64)
        val base64=java.util.Base64.getEncoder().encodeToString(("{\"textures\":{\"SKIN\":{\"url\":\""+url.replace("https:","http:")+"\"}}}").toByteArray())
        assertEquals(url,com.alytanta.oneblock.items.HeadTextures.url(base64));assertEquals(url,com.alytanta.oneblock.items.HeadTextures.url(url))
        assertNull(com.alytanta.oneblock.items.HeadTextures.url("https://textures.minecraft.net.evil/texture/aa"))
        assertNull(com.alytanta.oneblock.items.HeadTextures.url(java.util.Base64.getEncoder().encodeToString("{\"textures\":{\"SKIN\":{\"url\":\"https://example.org/x\"}}}".toByteArray())))
        val file=folder.resolve("heads.yml").toFile().apply {writeText(this@RoomRewardsTest.javaClass.getResourceAsStream("/items.yml")!!.reader().readText())}
        val y=org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(file)
        assertNotNull(com.alytanta.oneblock.items.ItemCatalog.read(file).first!!.require("warden_helmet").headTexture)
        y.set("items.oneblock:warden_helmet.head-profile",null);y.set("items.oneblock:warden_helmet.head-texture",base64);y.save(file)
        assertEquals(base64,com.alytanta.oneblock.items.ItemCatalog.read(file).first!!.require("warden_helmet").headTexture)
        y.set("visible-id-format","ID without placeholder");y.save(file)
        assertThrows(IllegalArgumentException::class.java) {com.alytanta.oneblock.items.ItemCatalog.read(file)}
    }
    @Test fun `exact stack splitting is deterministic and aborted withdrawal retains paid personal provenance`() {
        val i=island();val claim=MarketClaim(UUID.randomUUID(),i.owner,"DIAMOND",4,"chest:${UUID.randomUUID()}","exact-four",i.ownerPlayer)
        val book=MarketState(claims=mapOf(claim.id to claim))
        val split=ClaimCapacity.split(book,claim,"exact-one")
        assertEquals(split,ClaimCapacity.split(book,claim,"exact-one"));assertEquals(4,split.claims.size)
        assertEquals(4,split.claims.values.sumOf {it.amount});assertTrue(split.claims.values.all {it.recipient==i.ownerPlayer && it.source==claim.source && it.itemData=="exact-one"})
        assertThrows(IllegalArgumentException::class.java) {ClaimCapacity.split(split,claim,"exact-one")}
        val c=split.claims.values.first();val t=ItemTransfer(UUID.randomUUID(),i.owner,i.ownerPlayer,"DIAMOND",1,TransferKind.WITHDRAW,0,c.id,"before","after",itemData="exact-one",recipient=i.ownerPlayer,originSource=c.source)
        val y=org.bukkit.configuration.file.YamlConfiguration().apply {loadFromString(javaClass.getResourceAsStream("/config.yml")!!.reader().readText())}
        val returned=MarketEngine(MarketSettings.load(y)).transaction(i.owner,i.ownerPlayer,i.coins,split) {prepare(t);abort(t.id)}.state
        assertEquals(4,returned.claims.values.sumOf {it.amount});assertTrue(returned.claims.values.all {it.recipient==i.ownerPlayer && it.source.startsWith("chest:")})
        assertThrows(IllegalArgumentException::class.java) {com.alytanta.oneblock.player.ProfileRemoval.validate(i,com.alytanta.oneblock.player.PlayerProfile(i.ownerPlayer,activeAccount=i.owner),returned,i.ownerPlayer,System.currentTimeMillis())}
    }
}
