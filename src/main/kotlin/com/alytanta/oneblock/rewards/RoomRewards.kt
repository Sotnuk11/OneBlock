package com.alytanta.oneblock.rewards

import com.alytanta.oneblock.combat.*
import com.alytanta.oneblock.island.*
import com.alytanta.oneblock.market.*
import com.google.gson.Gson
import org.bukkit.configuration.file.YamlConfiguration
import java.util.UUID

data class LootItem(val id:String,val product:String,val data:String)
data class LootChoice(val item:String,val amount:Int,val weight:Int)
data class RoomLootRule(val id:String,val chest:Boolean,val min:Long,val max:Long,val every:Int,val boss:String,
    val chance:Double,val price:Long,val guaranteed:List<RewardEntry>,val rolls:Int,val pool:List<LootChoice>,val title:String=id,
    val final:Boolean=false,val minBosses:Long=0,val goodChance:Double=0.0,val goodChancePerBoss:Double=0.0,val goodChanceMax:Double=1.0,
    val equipmentPriceBonus:Long=0,val goodChancePerWave:Double=0.0,val perWave:List<RewardEntry> = emptyList(),val perBoss:List<RewardEntry> = emptyList(),val pricePerBoss:Long=0)
data class PreparedChest(val id:String,val price:Long,val items:List<LootItem>)
data class PreparedPity(val after:Int,val price:Long,val parts:Map<String,List<LootItem>>)
data class PreparedRoomLoot(val chests:List<PreparedChest>,val pity:PreparedPity?,val created:Long)
data class ChestOffer(val id:UUID,val account:UUID,val recipient:UUID,val label:String,val price:Long,val items:List<LootItem>,
    val created:Long,val expires:Long?,val status:String="OFFERED",val opened:Long?=null) {
    fun remaining(now:Long)=(expires?.minus(now)?:Long.MAX_VALUE).coerceAtLeast(0)
    fun available(now:Long)=status=="OFFERED" && (expires==null || now<expires)
    fun fingerprint()=com.alytanta.oneblock.dungeon.WaveRecords.hash(Gson().toJson(this))
}
data class PityCounter(val misses:Int=0,val cursor:Int=0)
data class ChestProgress(val id:UUID,val account:UUID,val recipient:UUID,val selected:String="warden",val counters:Map<String,PityCounter> = emptyMap(),val created:Long=System.currentTimeMillis())

/** Data only, pinned in each run. No script execution or fresh roll on purchase/reload/replay. */
data class RoomRewardRules(val rules:List<RoomLootRule>,val pityAfter:Int,val pityPrice:Long,val pityBossOnly:Boolean) {
    fun validateBudget(catalog:CombatRewardCatalog,limit:Int,party:Int,bossEvery:Int) {
        fun bytes(id:String)=catalog.encoded.getValue(id).toByteArray(Charsets.UTF_8).size.toLong()+384
        var size=Gson().toJson(catalog).toByteArray(Charsets.UTF_8).size.toLong()+512*1024
        for(wave in 1..limit) {
            val boss=wave%bossEvery==0
            val eligible=rules.filter {!it.final && wave>=it.min && wave<=it.max && wave%it.every==0 && (it.boss=="any" || (it.boss=="boss")==boss)}
            require(eligible.filterNot {it.chest}.sumOf {r->r.guaranteed.sumOf {it.amount}+r.rolls*(r.pool.maxOfOrNull {it.amount}?:0)}<=256) {"Too many ordinary items in one room reward"}
            var member=eligible.sumOf {r->
                1024L+r.guaranteed.sumOf {it.amount*bytes(it.item)}+r.rolls*(r.pool.maxOfOrNull {it.amount*bytes(it.item)}?:0)
            }
            if(pityAfter>0 && (!pityBossOnly || boss)) {
                val parts=catalog.rules.items.values.filter {it.kind==CombatKind.ARMOR}
                member+=parts.sumOf {bytes(it.id)}+(parts.maxOfOrNull {bytes(it.id)}?:0)+1024
            }
            size+=member*party+2048
        }
        // End-of-run chests hold at most 64 items each.
        size+=rules.filter {it.final}.sumOf {r->1024L+64L*((r.guaranteed.map {it.item}+r.perWave.map {it.item}+r.perBoss.map {it.item}+r.pool.map {it.item}).maxOfOrNull(::bytes)?:0L)}*party
        require(size<7L*1024*1024) {"Configured room rewards exceed durable journal capacity; reduce reward-wave-limit or loot counts"}
    }
    fun validate(catalog:CombatRules) {
        rules.forEach {r->(r.guaranteed.map {it.item} + r.pool.map {it.item} + r.perWave.map {it.item} + r.perBoss.map {it.item}).forEach {id->
            val item=catalog.items.getValue(id)
            require(r.chest || item.kind!=CombatKind.ARMOR) {"Armor is available only in room chests"}
        }}
        if(pityAfter>0)require(setOf("warden","ranger","weaver").all {set->catalog.items.values.count {it.kind==CombatKind.ARMOR && it.set==set}==4})
    }
    fun prepare(request:CombatRewardRequest,catalog:CombatRewardCatalog,wave:Long,boss:Boolean,now:Long,final:Boolean=false,bossesKilled:Long=0,bossEvery:Long=5):PreparedCombatReward {
        val random=java.util.Random(request.event.mostSignificantBits xor request.event.leastSignificantBits xor request.recipient.leastSignificantBits)
        fun item(id:String)=catalog.rules.items.getValue(id).let {LootItem("oneblock:$id",it.material,catalog.encoded.getValue(id))}
        val ordinary=mutableListOf<FrozenCombatItem>();val chests=mutableListOf<PreparedChest>()
        fun matches(r:RoomLootRule,number:Long,isBoss:Boolean)=!r.final && bossesKilled>=r.minBosses &&
            number>=r.min && number<=r.max && number%r.every==0L && (r.boss=="any" || (r.boss=="boss")==isBoss)
        // At the end of a run `wave` is the last rewarded wave: every wave up to it pays once, then the final rules.
        val selected=if(final)(1L..wave).flatMap {number->rules.filter {matches(it,number,bossEvery>0 && number%bossEvery==0L)}}+rules.filter {it.final && bossesKilled>=it.minBosses}
            else rules.filter {matches(it,wave,boss)}
        selected.forEach {r->
            if(random.nextDouble()>=r.chance)return@forEach
            val entries=r.guaranteed.flatMap {e->List(e.amount) {item(e.item)}}.toMutableList()
            if(final) {
                r.perWave.forEach {e->repeat((e.amount*wave).coerceAtMost(64).toInt()) {entries+=item(e.item)}}
                r.perBoss.forEach {e->repeat((e.amount*bossesKilled).coerceAtMost(64).toInt()) {entries+=item(e.item)}}
            }
            repeat(r.rolls) {
                val good=r.final && random.nextDouble() < (r.goodChance+r.goodChancePerBoss*bossesKilled+r.goodChancePerWave*wave).coerceAtMost(r.goodChanceMax)
                val choices=if(good)r.pool.filterNot {it.item=="battle_fragment"} else r.pool.filter {it.item=="battle_fragment"}
                val selected=if(choices.isEmpty())r.pool else choices
                var roll=random.nextInt(selected.sumOf {it.weight});val choice=selected.first {roll-=it.weight;roll<0}
                repeat(choice.amount) {entries+=item(choice.item)}
            }
            val equipment=entries.any {
                val id=it.id.removePrefix("oneblock:")
                id=="tempered_core" || catalog.rules.items.getValue(id).kind in setOf(CombatKind.MELEE,CombatKind.RANGED,CombatKind.MAGIC,CombatKind.ARMOR)
            }
            if(entries.isEmpty())return@forEach
            val price=Math.addExact(Math.addExact(r.price,if(final)r.pricePerBoss*bossesKilled else 0L),if(equipment)r.equipmentPriceBonus else 0L)
            if(r.chest) {if(chests.size<16)chests+=PreparedChest(r.title,price,entries.take(64))} else ordinary+=entries.map {FrozenCombatItem(it.product,it.data)}
        }
        val pity=if((!final || bossesKilled>=1) && pityAfter>0 && (!pityBossOnly || boss))PreparedPity(pityAfter,pityPrice,
            catalog.rules.items.values.filter {it.kind==CombatKind.ARMOR}.groupBy {it.set!!}.mapValues {(_,parts)->parts.sortedBy {it.armorSlot}.map {item(it.id)}}) else null
        val room=PreparedRoomLoot(chests,pity,now)
        return PreparedCombatReward(request,ordinary,room).let {it.copy(request=request.copy(outputHash=PreparedCombatReward.payloadHash(it.items,it.room)))}.also {it.validate()}
    }
    companion object {
        fun read(source:YamlConfiguration):RoomRewardRules {
            val y=if(source.contains("room-rewards"))source else YamlConfiguration().apply {loadFromString(RoomRewardRules::class.java.getResourceAsStream("/dungeon.yml")!!.reader().readText())}
            val s=requireNotNull(y.getConfigurationSection("room-rewards"))
            fun whole(v:Any?,min:Long,max:Long)=v.toString().toLong().also {require(it in min..max)}
            fun id(v:Any?)=v.toString().removePrefix("oneblock:").also {require(it.matches(Regex("[a-z][a-z0-9_]{0,47}")))}
            val rows=s.getMapList("offers");require(rows.size in 1..16)
            val rules=rows.map {r->
                val chest=r["chest"] as? Boolean?:error("offer chest must be boolean")
                val guaranteed=(r["guaranteed"] as? List<*>).orEmpty().map {row->val e=row as Map<*,*>;RewardEntry(id(e["item"]),whole(e["amount"],1,16).toInt())}
                val pool=(r["pool"] as? List<*>).orEmpty().map {row->val e=row as Map<*,*>;LootChoice(id(e["item"]),whole(e["amount"]?:1,1,16).toInt(),whole(e["weight"]?:1,1,10000).toInt())}
                val rolls=whole(r["rolls"]?:0,0,8).toInt();require(rolls==0 || pool.isNotEmpty());require(pool.size<=64)
                fun scaled(key:String)=(r[key] as? List<*>).orEmpty().map {row->val e=row as Map<*,*>;RewardEntry(id(e["item"]),whole(e["amount"],1,16).toInt())}
                val goodPerWave=(r["good-chance-per-wave"]?:0).toString().toDouble().also {require(it in 0.0..1.0)};val perWave=scaled("per-wave");val perBoss=scaled("per-boss")
                val pricePerBoss=whole(r["price-per-boss"]?:0,0,1_000_000_000)
                require(guaranteed.sumOf {it.amount}+rolls*(pool.maxOfOrNull {it.amount}?:0) in (if(perWave.isEmpty()&&perBoss.isEmpty())1 else 0)..64)
                val price=whole(r["price"]?:0,0,1_000_000_000);require(chest || price==0L)
                val chance=(r["chance"]?:1).toString().toDouble().also {require(it.isFinite() && it in 0.0..1.0)}
                val min=whole(r["min-wave"]?:1,1,Long.MAX_VALUE);val max=whole(r["max-wave"]?:Long.MAX_VALUE,min,Long.MAX_VALUE)
                val title=(r["name"]?:if(chest)"Скриня" else "Нагорода").toString().also {require(it.length in 1..80 && it.none(Char::isISOControl))}
                val final=r["final"] as? Boolean ?: false
                val minBosses=whole(r["min-bosses"]?:0,0,Long.MAX_VALUE)
                val goodChance=(r["good-chance"]?:0).toString().toDouble().also {require(it in 0.0..1.0)}
                val goodPerBoss=(r["good-chance-per-boss"]?:0).toString().toDouble().also {require(it in 0.0..1.0)}
                val goodMax=(r["good-chance-max"]?:1).toString().toDouble().also {require(it in 0.0..1.0 && it>=goodChance)}
                val equipmentPriceBonus=whole(r["equipment-price-bonus"]?:0,0,1_000_000_000)
                RoomLootRule(id(r["id"]),chest,min,max,whole(r["every"]?:1,1,10000).toInt(),r["on"].toString().also {require(it in setOf("any","boss","normal"))},chance,price,guaranteed,rolls,pool,title,final,minBosses,goodChance,goodPerBoss,goodMax,equipmentPriceBonus,goodPerWave,perWave,perBoss,pricePerBoss)
            }
            require(rules.map {it.id}.distinct().size==rules.size)
            val after=whole(s.get("pity.after")?:3,0,100).toInt();val price=whole(s.get("pity.price")?:500,0,1_000_000_000)
            require(!s.contains("pity.boss-only") || s.isBoolean("pity.boss-only"))
            return RoomRewardRules(rules,after,price,s.getBoolean("pity.boss-only",true))
        }
    }
}

/** Called inside the wallet+ledger critical section. Receipts, pity and offers commit atomically. */
object ChestDomain {
    const val TTL=48*60*60*1000L
    fun setName(set:String)=mapOf("warden" to "Вартовий","ranger" to "Стрілець","weaver" to "Ткач чарів")[set]?:set
    fun progressId(account:UUID,actor:UUID)=UUID.nameUUIDFromBytes("chest-progress:$account:$actor".toByteArray())
    fun grant(state:MarketState,reward:PreparedCombatReward):MarketState {
        val room=reward.room?:return state;val request=reward.request
        val offers=state.chests.toMutableMap();var progress=state.chestProgress
        val id=progressId(request.account,request.recipient)
        var p=progress[id]?:ChestProgress(id,request.account,request.recipient,created=room.created)
        val prepared=room.chests.toMutableList()
        room.pity?.let {rule->
            val set=p.selected;val parts=rule.parts.getValue(set);val old=p.counters[set]?:PityCounter()
            // Random offers (including duplicates and expired offers) never delay the guarantee.
            // Ownership comes from durable OPENED receipts, not GUI previews or a mutable inventory.
            val owned=offers.values.filter {it.account==request.account && it.recipient==request.recipient && it.status=="OPENED"}.flatMap {it.items}.map {it.id}.toSet()
            val misses=old.misses+1
            val guarantee=misses>=rule.after
            val index=(parts.indices.map {(old.cursor+it)%parts.size}.firstOrNull {parts[it].id !in owned}?:old.cursor%parts.size)
            if(guarantee)prepared+=PreparedChest("Гарантована частина: ${setName(set)}",rule.price,listOf(parts[index]))
            p=p.copy(counters=p.counters+(set to PityCounter(if(guarantee)0 else misses,if(guarantee)(index+1)%parts.size else old.cursor)))
            progress=progress+(id to p)
        }
        prepared.forEachIndexed {index,c->
            val offerId=UUID.nameUUIDFromBytes("room-chest:${request.receipt}:$index".toByteArray())
            require(offerId !in offers)
            offers[offerId]=ChestOffer(offerId,request.account,request.recipient,c.id,c.price,c.items,room.created,if(c.price>0)Math.addExact(room.created,TTL) else null)
        }
        return state.copy(chests=offers,chestProgress=progress)
    }
    fun open(island:Island,state:MarketState,actor:UUID,shown:ChestOffer,now:Long):MarketChange<ChestOffer> {
        require(island.retiredAt==null && (island.ownerPlayer==actor || island.members[actor]==IslandRole.MEMBER)) {"access"}
        val offer=state.chests.getValue(shown.id)
        require(offer.account==island.owner && offer.recipient==actor && offer.items==shown.items && offer.price==shown.price && offer.created==shown.created) {"stale"}
        if(offer.status=="OPENED")return MarketChange(island.coins,state,offer)
        require(offer.available(now)) {"expired"}
        require(island.coins>=offer.price) {"funds"}
        require(state.transfers.values.none {it.actor==actor && it.status in setOf(TransferStatus.PREPARED,TransferStatus.REVIEW)}) {"busy"}
        val claims=state.claims.toMutableMap()
        offer.items.forEachIndexed {index,item->
            val claim=UUID.nameUUIDFromBytes("chest-content:${offer.id}:$index".toByteArray());require(claim !in claims)
            claims[claim]=MarketClaim(claim,island.owner,item.product,1,"chest:${offer.id}",item.data,actor,now)
        }
        val opened=offer.copy(status="OPENED",opened=now)
        return MarketChange(island.coins-offer.price,state.copy(claims=claims,chests=state.chests+(offer.id to opened)),opened,
            com.alytanta.oneblock.storage.OperationLog(operation="ROOM_CHEST_OPEN",actor=actor,account=island.owner,success=true,status="SUCCESS",correlation=offer.id,details=mapOf("price" to offer.price)))
    }
}
