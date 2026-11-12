package com.alytanta.oneblock.rewards

import com.alytanta.oneblock.island.IslandService
import com.alytanta.oneblock.market.*
import com.alytanta.oneblock.menu.MenuInventory
import com.alytanta.oneblock.storage.PostgresStorageService
import com.alytanta.oneblock.util.FoliaTasks
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import org.bukkit.plugin.Plugin
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

object ClaimCapacity {
    fun fit(contents:Array<ItemStack?>,item:ItemStack,limit:Int):Int=contents.indices.filter {it!=MenuInventory.SLOT}.sumOf {slot->
        val current=contents[slot]?.takeUnless {it.isEmpty}
        if(current!=null && !current.isSimilar(item))0 else (minOf(limit,item.maxStackSize)-(current?.amount?:0)).coerceAtLeast(0)
    }
    /** Split UUIDs derive from the source claim, never a GUI position. All units remain exact serialized items. */
    fun split(state:MarketState,shown:MarketClaim,unitData:String):MarketState {
        require(state.claims[shown.id]==shown && shown.amount in 2..64 && shown.itemData!=null)
        require(state.transfers.values.none {it.claimId==shown.id && it.status in setOf(TransferStatus.PREPARED,TransferStatus.REVIEW)})
        val claims=state.claims.toMutableMap();claims.remove(shown.id)
        repeat(shown.amount.toInt()) {index->
            val id=UUID.nameUUIDFromBytes("claim-unit:${shown.id}:$index".toByteArray());require(id !in claims)
            claims[id]=shown.copy(id=id,amount=1,itemData=unitData)
        }
        return state.copy(claims=claims)
    }
}
class RewardCollection(private val plugin:Plugin,private val islands:IslandService,private val storage:PostgresStorageService,
    private val market:MarketController) {
    private val working=ConcurrentHashMap.newKeySet<UUID>()
    fun busy(actor:UUID)=actor in working
    fun collect(player:Player,ids:List<UUID>,done:()->Unit) {
        if(!working.add(player.uniqueId))return
        val account=islands.findForPlayer(player.uniqueId)?.owner
        if(account==null) {working.remove(player.uniqueId);return}
        if(islands.findByOwner(account)?.let {islands.canBuild(it,player.uniqueId)}!=true) {working.remove(player.uniqueId);return}
        val generation=storage.generation;val queue=java.util.ArrayDeque(ids.distinct());var items=0L;var coins=0L;var stopped=false
        fun finish() {
            working.remove(player.uniqueId)
            val left=storage.marketState().claims.values.count {it.account==account && (it.recipient==null || it.recipient==player.uniqueId)}
            player.sendMessage(com.alytanta.oneblock.util.Colors.colorize("&aОтримано: $items предметів, $coins монет. &7Залишено нагород: $left."+if(stopped)" &eВидачу призупинено; збережене перевіряється без повторних витрат." else ""))
            done()
        }
        lateinit var step:()->Unit
        fun next()=FoliaTasks.entity(plugin,player,retired={working.remove(player.uniqueId)}) {step()}
        step=step@{
            if(player.isDead || !storage.healthy || storage.generation!=generation || islands.findForPlayer(player.uniqueId)?.owner!=account) {stopped=true;finish();return@step}
            val id=queue.pollFirst()?:run {finish();return@step}
            val claim=storage.marketState().claims[id]
            if(claim==null || claim.account!=account || claim.recipient!=null && claim.recipient!=player.uniqueId) {next();return@step}
            try {
                if(claim.product==null) {
                    val token=InventoryGuard.acquireToken(player.uniqueId)?:run {stopped=true;finish();return@step}
                    try {
                        require(InventoryGuard.canStart(player.uniqueId))
                        islands.marketUpdate(account) {live,state->MarketEngine(market.settings).transaction(account,player.uniqueId,live.coins,state) {collectCoins(id)}}
                        storage.flushAsync().whenComplete {_,error->InventoryGuard.release(player.uniqueId,token);FoliaTasks.entity(plugin,player,retired={working.remove(player.uniqueId)}) {
                            if(error!=null) {stopped=true;finish()} else {coins+=claim.amount;step()}
                        }}
                    } catch(e:Exception) {InventoryGuard.release(player.uniqueId,token);throw e}
                    return@step
                }
                val exact=claim.itemData?.let(AuctionItems::decode)?:(if(com.alytanta.oneblock.items.CustomProducts.custom(claim.product!!))com.alytanta.oneblock.items.CustomProducts.stack(claim.product,1) else ItemStack(Material.valueOf(claim.product),1))
                if(claim.itemData!=null)require(exact.amount.toLong()==claim.amount)
                // Normalize only visible-ID lore on the owned claim, before preparing an inventory intent.
                // This lets old frozen components stack with already migrated personal components.
                if(claim.itemData!=null && com.alytanta.oneblock.items.CustomItems.ensureVisibleId(exact)) {
                    val bytes=AuctionItems.encode(exact,65536)
                    val token=InventoryGuard.acquireToken(player.uniqueId)?:run {stopped=true;finish();return@step}
                    try {
                        require(InventoryGuard.canStart(player.uniqueId))
                        islands.marketUpdate(account) {live,state->
                            require(state.claims[id]==claim && state.transfers.values.none {it.claimId==id && it.status in setOf(TransferStatus.PREPARED,TransferStatus.REVIEW)})
                            MarketChange(live.coins,state.copy(claims=state.claims+(id to claim.copy(itemData=bytes))),Unit)
                        }
                        storage.flushAsync().whenComplete {_,error->InventoryGuard.release(player.uniqueId,token);FoliaTasks.entity(plugin,player,retired={working.remove(player.uniqueId)}) {
                            if(error!=null) {stopped=true;finish()} else {queue.addFirst(id);step()}
                        }}
                    } catch(e:Exception) {InventoryGuard.release(player.uniqueId,token);throw e}
                    return@step
                }
                val fit=ClaimCapacity.fit(player.inventory.storageContents,exact,player.inventory.maxStackSize)
                if(fit==0) {next();return@step}
                if(claim.itemData!=null && fit<claim.amount && claim.amount>1) {
                    val unit=exact.clone().apply {amount=1};val encoded=AuctionItems.encode(unit,65536)
                    val token=InventoryGuard.acquireToken(player.uniqueId)?:run {stopped=true;finish();return@step}
                    try {
                        require(InventoryGuard.canStart(player.uniqueId))
                        islands.marketUpdate(account) {live,state->MarketChange(live.coins,ClaimCapacity.split(state,claim,encoded),Unit)}
                        storage.flushAsync().whenComplete {_,error->InventoryGuard.release(player.uniqueId,token);FoliaTasks.entity(plugin,player,retired={working.remove(player.uniqueId)}) {
                            if(error!=null) {stopped=true;finish()} else {
                                repeat(claim.amount.toInt()) {index->queue.addLast(UUID.nameUUIDFromBytes("claim-unit:${claim.id}:$index".toByteArray()))};step()
                            }
                        }}
                    } catch(e:Exception) {InventoryGuard.release(player.uniqueId,token);throw e}
                    return@step
                }
                val amount=if(claim.itemData!=null)claim.amount.toInt() else minOf(claim.amount,fit.toLong(),64).toInt()
                market.handoffs.transfer(player,account,claim.product,amount,TransferKind.WITHDRAW,claimId=id,silent=true,
                    failed={FoliaTasks.entity(plugin,player,retired={working.remove(player.uniqueId)}) {stopped=true;finish()}}) {
                    items+=amount
                    if(claim.itemData==null && claim.amount>amount)queue.addLast(id)
                    next()
                }
            } catch(e:Exception) {stopped=true;finish()}
        }
        step()
    }
}
