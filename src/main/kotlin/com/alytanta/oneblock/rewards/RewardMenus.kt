package com.alytanta.oneblock.rewards

import com.alytanta.oneblock.island.IslandService
import com.alytanta.oneblock.market.*
import com.alytanta.oneblock.menu.*
import com.alytanta.oneblock.storage.PostgresStorageService
import com.alytanta.oneblock.util.*
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.plugin.Plugin
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class RewardMenus(private val plugin:Plugin,private val menu:PlayerMenu,private val islands:IslandService,
    private val storage:PostgresStorageService,market:MarketController):org.bukkit.event.Listener {
    @Volatile var rules:(UUID)->RoomRewardRules?={null}
    val collection=RewardCollection(plugin,islands,storage,market)
    private data class View(val account:UUID,val generation:Long,val config:Any,val page:Int,val chests:Map<UUID,ChestOffer>)
    private data class Quote(val view:View,val offer:ChestOffer,val page:Int=0)
    private val views=ConcurrentHashMap<UUID,View>();private val quotes=ConcurrentHashMap<UUID,Quote>()
    private val watches=ConcurrentHashMap<UUID,io.papermc.paper.threadedregions.scheduler.ScheduledTask>()
    init {
        menu.statistics.register("reward.quote") {p->quotes[p.uniqueId]?.offer?.let {"&6${it.label}\n&fЦіна: ${it.price} монет\n&7${time(it)}\n&fВміст: ${it.items.size} предметів"}.orEmpty()}
        menu.statistics.register("reward.pity") {p->islands.findForPlayer(p.uniqueId)?.let {a->
            val progress=storage.marketState().chestProgress[ChestDomain.progressId(a.owner,p.uniqueId)]
            val policy=rules(p.uniqueId);val after=policy?.pityAfter?:0
            "&eКомплект: ${ChestDomain.setName(progress?.selected?:"warden")}\n"+
                if(after==0)"&7Гарантію вимкнено" else "&7${if(policy?.pityBossOnly==true)"Походи до гарантії" else "Походи до гарантії"}: ${progress?.counters?.get(progress.selected)?.misses?:0}/$after\n&7Ціна гарантованої скрині: ${policy?.pityPrice} монет"
        }.orEmpty()}
    }
    private fun time(c:ChestOffer):String {
        if(c.price==0L)return "Безкоштовна скриня — підтвердьте окремо"
        val seconds=c.remaining(System.currentTimeMillis())/1000
        return if(seconds==0L)"Пропозицію завершено" else "Залишилось: ${seconds/3600} год ${(seconds%3600)/60} хв ${seconds%60} с"
    }
    private fun valid(p:Player,v:View)=storage.healthy && storage.generation==v.generation && menu.configurationIdentity===v.config && islands.findForPlayer(p.uniqueId)?.let {it.owner==v.account && islands.canBuild(it,p.uniqueId)}==true
    fun open(p:Player,page:Int=0) {
        val a=islands.findForPlayer(p.uniqueId)?:return
        val state=storage.marketState();val claims=state.claims.values.filter {it.account==a.owner && (it.recipient==null || it.recipient==p.uniqueId)}.sortedWith(compareBy({it.created},{it.id}))
        val now=System.currentTimeMillis()
        val chests=state.chests.values.filter {it.account==a.owner && it.recipient==p.uniqueId && it.available(now)}.sortedWith(compareBy({it.created},{it.id}))
        val grid=menu.definition("release-claims")?.content.orEmpty();if(grid.isEmpty())return
        val entries=claims.map {c->c.id.toString() to MenuItemSpec(c.product?.let {com.alytanta.oneblock.items.CustomProducts.material(it)}?:Material.GOLD_NUGGET,"&a${c.amount} × ${c.product?:"монет"}",listOf("&7Звичайна належна нагорода"))}+
            chests.map {c->"chest:${c.id}" to MenuItemSpec(if(c.remaining(System.currentTimeMillis())==0L)Material.GRAY_CONCRETE else if(c.price>0)Material.ENDER_CHEST else Material.CHEST,"&6${c.label}",listOf("&fЦіна: ${c.price} монет","&7${time(c)}","&eПереглянути точний вміст"))}
        val index=page.coerceIn(0,(entries.size-1).coerceAtLeast(0)/grid.size)
        val view=View(a.owner,storage.generation,menu.configurationIdentity,index,chests.associateBy {it.id});views[p.uniqueId]=view
        watch(p)
        menu.page(p,"release-claims",entries,index,displayFor={id->
            if(id.startsWith("chest:")) {
                val c=view.chests.getValue(UUID.fromString(id.substringAfter(':')))
                org.bukkit.inventory.ItemStack(if(c.price>0)Material.ENDER_CHEST else Material.CHEST).apply {editMeta {meta->
                    meta.displayName(Colors.colorize("&6${c.label}"));meta.lore(listOf("&fЦіна: ${c.price} монет","&7${time(c)}","&eПереглянути точний вміст").map(Colors::colorize))
                    meta.persistentDataContainer.set(org.bukkit.NamespacedKey(plugin,"reward_chest_id"),org.bukkit.persistence.PersistentDataType.STRING,c.id.toString())
                }}
            } else claims.firstOrNull {it.id.toString()==id && it.itemData!=null}?.let {c->
                AuctionItems.decode(c.itemData!!).apply {editMeta {meta->
                    meta.persistentDataContainer.set(org.bukkit.NamespacedKey(plugin,"reward_claim_id"),org.bukkit.persistence.PersistentDataType.STRING,c.id.toString())
                }}
            }
        }) {id->
            if(!valid(p,view) || collection.busy(p.uniqueId))return@page
            if(id.startsWith("chest:")) {
                val c=view.chests[UUID.fromString(id.substringAfter(':'))]?:return@page
                if(storage.marketState().chests[c.id]!=c)return@page
                quotes[p.uniqueId]=Quote(view,c);preview(p,c)
            } else collection.collect(p,listOf(UUID.fromString(id))) {open(p,index)}
        }
    }
    private fun watch(p:Player) {
        watches.remove(p.uniqueId)?.cancel()
        val task=p.scheduler.runAtFixedRate(plugin,{t->
            if(menu.currentMenuId(p) !in setOf("release-claims","chest-preview","chest-confirm")) {t.cancel();watches.remove(p.uniqueId);return@runAtFixedRate}
            menu.refreshControls(p)
        },{watches.remove(p.uniqueId)},20,20)
        if(task!=null)watches[p.uniqueId]=task
    }
    private fun preview(p:Player,c:ChestOffer,requested:Int=0) {
        val entries=c.items.mapIndexed {index,item->index.toString() to MenuItemSpec(Material.valueOf(item.product),item.id,listOf(item.id))}
        val size=menu.definition("chest-preview")?.content?.size?.coerceAtLeast(1)?:36
        val page=requested.coerceIn(0,(entries.size-1)/size)
        quotes.computeIfPresent(p.uniqueId) {_,q->q.copy(page=page)}
        menu.page(p,"chest-preview",entries,page,displayFor={id->AuctionItems.decode(c.items[id.toInt()].data)}) {}
    }
    fun action(p:Player,raw:String):Boolean {
        if(raw !in setOf("claims","claims-next","claims-previous","claims-all","chests-confirm","chests-buy","chests-set","chests-loot-next","chests-loot-previous"))return false
        if(collection.busy(p.uniqueId))return true
        val view=views[p.uniqueId]
        if(raw=="claims") {open(p,view?.page?:0);return true}
        if(view==null || !valid(p,view))return true
        when(raw) {
            "chests-loot-next","chests-loot-previous"->{val q=quotes[p.uniqueId];if(q!=null && menu.currentMenuId(p)=="chest-preview")preview(p,q.offer,q.page+if(raw.endsWith("next"))1 else -1)}
            "claims-next"->open(p,view.page+1)
            "claims-previous"->open(p,view.page-1)
            "claims-all"->{
                val ids=storage.marketState().claims.values.filter {it.account==view.account && (it.recipient==null || it.recipient==p.uniqueId)}.sortedWith(compareBy({it.created},{it.id})).map {it.id}
                collection.collect(p,ids) {open(p,view.page)}
            }
            "chests-set"->{
                val token=InventoryGuard.acquireToken(p.uniqueId)?:return true
                try {
                    check(InventoryGuard.canStart(p.uniqueId))
                    islands.marketUpdate(view.account) {live,state->
                        val id=ChestDomain.progressId(live.owner,p.uniqueId);val old=state.chestProgress[id]?:ChestProgress(id,live.owner,p.uniqueId)
                        val sets=listOf("warden","ranger","weaver");val next=sets[(sets.indexOf(old.selected)+1)%sets.size]
                        MarketChange(live.coins,state.copy(chestProgress=state.chestProgress+(id to old.copy(selected=next))),Unit)
                    }
                    storage.flushAsync().whenComplete {_,_->InventoryGuard.release(p.uniqueId,token);FoliaTasks.entity(plugin,p) {open(p,view.page)}}
                } catch(e:Exception) {InventoryGuard.release(p.uniqueId,token)}
            }
            "chests-confirm"->{val q=quotes[p.uniqueId];if(q!=null && q.view==view && menu.currentMenuId(p)=="chest-preview")menu.show(p,"chest-confirm")}
            "chests-buy"->{
                val q=quotes[p.uniqueId]?:return true
                if(q.view!=view || menu.currentMenuId(p)!="chest-confirm" || !p.hasPermission("oneblock.command"))return true
                val token=InventoryGuard.acquireToken(p.uniqueId)?:return true
                try {
                    require(InventoryGuard.canStart(p.uniqueId) && !p.isDead && p.itemOnCursor.isEmpty)
                    islands.marketUpdate(view.account) {live,state->ChestDomain.open(live,state,p.uniqueId,q.offer,System.currentTimeMillis())}
                    storage.flushAsync().whenComplete {_,error->InventoryGuard.release(p.uniqueId,token);FoliaTasks.entity(plugin,p) {
                        if(error==null) {quotes.remove(p.uniqueId);p.sendMessage(Colors.colorize("&aВміст скрині збережено у звичайних нагородах."));open(p,view.page)}
                        else p.sendMessage(Colors.colorize("&eРезультат перевіряється після відновлення БД. Оновіть нагороди; повтор цієї скрині не списує монети вдруге."))
                    }}
                } catch(e:Exception) {
                    InventoryGuard.release(p.uniqueId,token)
                    val message=when(e.message) {"expired"->"Строк пропозиції завершився.";"funds"->"Недостатньо монет.";"busy"->"Дочекайтеся завершення передачі предметів.";"access"->"Немає доступу до вихідного профілю.";"stale"->"Відкрийте пропозицію знову.";else->"Зараз відкрити скриню неможливо. Оновіть нагороди."}
                    p.sendMessage(Colors.colorize("&c$message"))
                }
            }
        }
        return true
    }
    @org.bukkit.event.EventHandler fun quit(e:org.bukkit.event.player.PlayerQuitEvent) {views.remove(e.player.uniqueId);quotes.remove(e.player.uniqueId);watches.remove(e.player.uniqueId)?.cancel()}
}
