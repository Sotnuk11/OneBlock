package com.alytanta.oneblock.combat

import com.alytanta.oneblock.island.Island
import com.alytanta.oneblock.island.IslandService
import com.alytanta.oneblock.market.*
import com.alytanta.oneblock.release.*
import com.alytanta.oneblock.storage.PostgresStorageService
import com.alytanta.oneblock.util.FoliaTasks
import org.bukkit.plugin.Plugin
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.CompletableFuture

/** A trusted producer MUST persist this request before submission, and retry it until acknowledged.
 * One event belongs to one immutable account + recipient. No client command accepts these fields. */
data class CombatRewardRequest(val source:String,val event:UUID,val account:UUID,val recipient:UUID,val reward:String,val outputHash:String?=null,val wave:Long?=null) {
    init { require(source.matches(Regex("[a-z][a-z0-9_.-]{0,63}")) && reward.matches(Regex("[a-z][a-z0-9_]{0,47}"))) }
    val receipt:UUID get()=UUID.nameUUIDFromBytes("combat:$source:$event:$recipient".toByteArray(Charsets.UTF_8))
    val fingerprint:String get()=MessageDigest.getInstance("SHA-256").digest(("$source:$event:$account:$recipient:$reward"+(outputHash?.let { ":$it" }?:"")+(wave?.let {":wave=$it"}?:"")).toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
}
data class FrozenCombatItem(val product:String,val itemData:String)
/** Durable producer payload, independent of later config reloads. One exact item per entry. */
data class PreparedCombatReward(val request:CombatRewardRequest,val items:List<FrozenCombatItem>,val room:com.alytanta.oneblock.rewards.PreparedRoomLoot?=null) {
    fun validate() {
        require((items.isNotEmpty() || room!=null) && items.size<=256 && items.all {it.itemData.length<=100000 && org.bukkit.Material.matchMaterial(it.product)!=null && it.product !in setOf("AIR","CAVE_AIR","VOID_AIR")})
        require(request.outputHash==payloadHash(items,room))
        fun loot(item:com.alytanta.oneblock.rewards.LootItem) {
            require(com.alytanta.oneblock.items.ItemCatalog.validId(item.id) && item.data.length in 1..100000 && org.bukkit.Material.matchMaterial(item.product)!=null && item.product !in setOf("AIR","CAVE_AIR","VOID_AIR"))
        }
        room?.let {r->
            require(r.created>0 && r.chests.size<=16)
            r.chests.forEach {c->require(c.price in 0L..1_000_000_000L && c.items.size in 1..64);c.items.forEach(::loot)}
            r.pity?.let {p->
                require(p.after in 1..100 && p.price in 0L..1_000_000_000L && p.parts.keys.containsAll(setOf("warden","ranger","weaver")))
                p.parts.values.forEach {parts->require(parts.size==4 && parts.map {it.id}.distinct().size==4);parts.forEach(::loot)}
            }
        }
    }
    companion object {
        fun payloadHash(items:List<FrozenCombatItem>,room:com.alytanta.oneblock.rewards.PreparedRoomLoot?):String = if(room==null)hash(items) else com.alytanta.oneblock.dungeon.WaveRecords.hash(com.google.gson.Gson().toJson(listOf(items,room)))
        fun hash(items:List<FrozenCombatItem>):String = MessageDigest.getInstance("SHA-256").digest(com.google.gson.Gson().toJson(items).toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
        fun prepare(request:CombatRewardRequest,catalog:CombatRewardCatalog):PreparedCombatReward {
            val items=CombatRewardDomain.selected(request,catalog.rules.rewards.getValue(request.reward)).flatMap { entry ->
                List(entry.amount) {FrozenCombatItem(catalog.rules.items.getValue(entry.item).material,catalog.encoded.getValue(entry.item))} }
            return PreparedCombatReward(request.copy(outputHash=hash(items)),items).also {it.validate()}
        }
    }
}
data class CombatReceipt(val id:UUID,val account:UUID,val recipient:UUID,val fingerprint:String,val created:Long=System.currentTimeMillis())
/** Immutable compiled output bytes: no Bukkit item factory or entity access in the DB worker. */
data class CombatRewardCatalog(val rules:CombatRules,val encoded:Map<String,String>) {
    companion object {
        fun compile(rules:CombatRules)=CombatRewardCatalog(rules,rules.items.mapValues { (_,item) -> AuctionItems.encode(CombatItems.create(item),65536) })
    }
}
object CombatRewardDomain {
    fun replay(island:Island,state:MarketState,request:CombatRewardRequest):Boolean {
        require(island.owner==request.account)
        val previous=state.combatReceipts[request.receipt] ?: return false
        require(previous.account==request.account && previous.recipient==request.recipient && previous.fingerprint==request.fingerprint) { "Reward event reused with a different request" };return true
    }
    /** Rolls are derived from event identity, so pre-commit retries never reroll a trophy. */
    fun selected(request:CombatRewardRequest,rule:CombatReward):List<RewardEntry> = rule.guaranteed+rule.trophies.filterIndexed { index,entry ->
        val bytes=MessageDigest.getInstance("SHA-256").digest("${request.receipt}:$index".toByteArray(Charsets.UTF_8))
        val roll=java.nio.ByteBuffer.wrap(bytes).long.ushr(11).toDouble()/9007199254740992.0
        roll<entry.chance
    }
    fun grant(island:Island,state:MarketState,request:CombatRewardRequest,catalog:CombatRewardCatalog):ReleaseChange {
        if(replay(island,state,request))return ReleaseChange(island,state)
        requireNotNull(island.releaseProgress);require(island.retiredAt==null);val rules=catalog.rules
        require(rules.enabled && (island.ownerPlayer==request.recipient || island.members[request.recipient]==com.alytanta.oneblock.island.IslandRole.MEMBER)) { "Reward recipient is not an active member" }
        require(state.combatReceipts.size<rules.receiptLimit) { "Reward receipt capacity reached; archival requires preserving deduplication" }
        val entries=selected(request,rules.rewards.getValue(request.reward))
        val claims=state.claims.toMutableMap()
        entries.forEachIndexed { index,entry ->
            val id=UUID.nameUUIDFromBytes("combat-claim:${request.receipt}:$index".toByteArray(Charsets.UTF_8))
            require(id !in claims)
            val item=rules.items.getValue(entry.item)
            // Keep exact serialized output at one item per claim for unstackable equipment and components.
            // Amount-one encoding is reused; larger components are represented by independent claims below.
            repeat(entry.amount) { unit ->
                val unitId=if(unit==0)id else UUID.nameUUIDFromBytes("combat-claim:${request.receipt}:$index:$unit".toByteArray(Charsets.UTF_8))
                require(unitId !in claims)
                claims[unitId]=MarketClaim(unitId,island.owner,item.material,1,"combat:${request.receipt}",catalog.encoded.getValue(item.id),request.recipient)
            }
        }
        val receipt=CombatReceipt(request.receipt,request.account,request.recipient,request.fingerprint)
        return ReleaseChange(island,state.copy(claims=claims,combatReceipts=state.combatReceipts+(receipt.id to receipt)))
    }
    fun grantPrepared(island:Island,state:MarketState,reward:PreparedCombatReward,limit:Int):ReleaseChange {
        reward.validate();val request=reward.request
        if(replay(island,state,request))return ReleaseChange(island,state)
        require(island.retiredAt==null && island.releaseProgress!=null)
        require(island.ownerPlayer==request.recipient || island.members[request.recipient]==com.alytanta.oneblock.island.IslandRole.MEMBER)
        require(state.combatReceipts.size<limit)
        val claims=state.claims.toMutableMap()
        reward.items.forEachIndexed { index,item ->
            val id=UUID.nameUUIDFromBytes("combat-frozen:${request.receipt}:$index".toByteArray(Charsets.UTF_8));require(id !in claims)
            claims[id]=MarketClaim(id,island.owner,item.product,1,"combat:${request.receipt}",item.itemData,request.recipient)
        }
        val receipt=CombatReceipt(request.receipt,request.account,request.recipient,request.fingerprint)
        val credited=if(request.source=="endless.v1" && request.wave!=null && island.releaseProgress!=null) {require(request.wave in 1..100);island.copy(releaseProgress=island.releaseProgress.copy(facts=island.releaseProgress.facts+"arena:wave:${request.wave}",arenaRuns=island.releaseProgress.arenaRuns+1))} else island
        return ReleaseChange(credited,com.alytanta.oneblock.rewards.ChestDomain.grant(state.copy(claims=claims,combatReceipts=state.combatReceipts+(receipt.id to receipt)),reward))
    }
}
/** Success means receipt AND exact personal claims crossed the same PostgreSQL durability barrier.
 * Failure/timeout is an unknown outcome: resubmit the SAME request, never invent a new event ID. */
class CombatRewardService(private val plugin:Plugin,private val storage:PostgresStorageService,private val islands:IslandService,
    private val current:()->CombatRewardCatalog?) {
    private val closed=java.util.concurrent.atomic.AtomicBoolean()
    fun close() { closed.set(true) }
    fun award(request:CombatRewardRequest):CompletableFuture<Unit> {
        require(request.outputHash==null) {"Prepared requests require awardPrepared"}
        return submit(request,null)
    }
    fun awardPrepared(reward:PreparedCombatReward):CompletableFuture<Unit> {
        reward.validate();return submit(reward.request,reward)
    }
    private fun submit(request:CombatRewardRequest,prepared:PreparedCombatReward?):CompletableFuture<Unit> {
        val result=CompletableFuture<Unit>();val generation=storage.generation;val catalog=current()
        if(closed.get())return CompletableFuture.failedFuture(IllegalStateException("Reward service closed"))
        try { FoliaTasks.async(plugin) {
            try {
                check(!closed.get() && storage.healthy && storage.generation==generation) { "Storage unavailable or replaced" }
                islands.releaseUpdate(request.account) { island,state ->
                    if(CombatRewardDomain.replay(island,state,request))ReleaseChange(island,state)
                    else if(prepared!=null)CombatRewardDomain.grantPrepared(island,state,prepared,catalog?.rules?.receiptLimit?:100000)
                    else CombatRewardDomain.grant(island,state,request,requireNotNull(catalog) { "Combat disabled" })
                }
                storage.flushAsync().whenComplete { _,error ->
                    if(error!=null)result.completeExceptionally(error)
                    else if(storage.generation!=generation)result.completeExceptionally(IllegalStateException("Storage replaced; retry request"))
                    else result.complete(Unit)
                }
            } catch(e:Exception) { result.completeExceptionally(e) }
        } } catch(e:Exception) { result.completeExceptionally(e) }
        return result.copy()
    }
}
