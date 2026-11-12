package com.alytanta.oneblock.smoke

import com.alytanta.oneblock.OneBlock
import com.alytanta.oneblock.combat.*
import com.alytanta.oneblock.dungeon.*
import com.alytanta.oneblock.hub.HubSettings
import com.alytanta.oneblock.island.IslandRole
import com.alytanta.oneblock.items.CustomItems
import com.alytanta.oneblock.market.*
import com.alytanta.oneblock.release.*
import com.alytanta.oneblock.rewards.*
import net.kyori.adventure.key.Key
import net.thenextlvl.worlds.*
import net.thenextlvl.worlds.generator.GeneratorType
import org.bukkit.*
import org.bukkit.entity.*
import org.bukkit.event.*
import org.bukkit.event.entity.*
import org.bukkit.event.inventory.*
import org.bukkit.event.player.*
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataType
import org.bukkit.plugin.java.JavaPlugin
import java.util.UUID
import java.util.concurrent.*

/** Real native inventories/entities and SQL. Only this async driver blocks; all game access uses its owner. */
class FoliaRewardsSmokePlugin:JavaPlugin(),Listener {
    private lateinit var main:OneBlock;private lateinit var mine:MineController;private lateinit var rewards:RewardMenus
    private lateinit var dropWorld:World
    private val joins=ConcurrentHashMap.newKeySet<UUID>();private val drops=ConcurrentHashMap<UUID,Item>()
    private val deaths=ConcurrentHashMap.newKeySet<UUID>();private val notes=CopyOnWriteArrayList<String>()
    private var stage="boot"
    private val ownerKey=NamespacedKey("one-block","manual_drop_owner");private val profileKey=NamespacedKey("one-block","manual_drop_profile")
    private fun field(o:Any,n:String)=o.javaClass.getDeclaredField(n).apply {isAccessible=true}.get(o)
    private fun <T> CompletableFuture<T>.waitFor():T=get(45,TimeUnit.SECONDS)
    private fun await(s:String,condition:()->Boolean) {stage=s;val end=System.nanoTime()+45_000_000_000;while(!condition()) {check(System.nanoTime()<end) {"Timeout $s"};Thread.sleep(25)}}
    private fun note(s:String) {stage=s;notes+=s;logger.info("Rewards: $s")}
    private fun p(n:Int)=server.getPlayerExact("MineBot$n")!!
    private fun account(n:Int)=mine.islands.findForPlayer(p(n).uniqueId)!!
    private fun command(n:Int,s:String) {mine.entity(p(n)) {check(p(n).performCommand(s));Unit}.waitFor()}
    private fun <T> entity(e:Entity,action:()->T)=CompletableFuture<T>().also {d->e.scheduler.execute(this,{try {d.complete(action())}catch(t:Throwable){d.completeExceptionally(t)}},{d.completeExceptionally(IllegalStateException("Entity retired"))},1)}
    private fun waitTicks(ticks:Long) {CompletableFuture<Unit>().also {d->server.globalRegionScheduler.runDelayed(this,{d.complete(Unit)},ticks)}.waitFor()}
    @EventHandler fun join(e:PlayerJoinEvent) {joins+=e.player.uniqueId}
    @EventHandler(priority=EventPriority.MONITOR,ignoreCancelled=true) fun drop(e:PlayerDropItemEvent) {drops[e.player.uniqueId]=e.itemDrop;e.itemDrop.pickupDelay=32767}
    @EventHandler(priority=EventPriority.MONITOR) fun death(e:PlayerDeathEvent) {deaths+=e.entity.uniqueId}
    override fun onEnable() {
        dataFolder.mkdirs();server.pluginManager.registerEvents(this,this)
        server.globalRegionScheduler.runDelayed(this,{
            CompletableFuture.runAsync {
                try {
                    main=server.pluginManager.getPlugin("one-block") as OneBlock;mine=field(main,"mine") as MineController
                    mine.engine.ready.waitFor()
                    rewards=HandlerList.getRegisteredListeners(main).map {it.listener}.filterIsInstance<RewardMenus>().first()
                    setup();dataFolder.resolve("bot-ready").writeText("775");await("six clients") {joins.size==6}
                    val collecting=field(field(main,"trades"),"collecting") as Set<*>
                    await("login recovery") {(1..6).none {InventoryGuard.busy(p(it).uniqueId) || p(it).uniqueId in collecting}}
                    if(dataFolder.resolve("phase1.ok").exists())second() else first()
                    val phase=if(dataFolder.resolve("phase1.ok").exists())2 else 1
                    dataFolder.resolve("rewards-$phase.json").writeText(com.google.gson.GsonBuilder().setPrettyPrinting().create().toJson(mapOf("assertions" to notes,"nativeClients" to 6,"humanPlaytest" to false)))
                    dataFolder.resolve("phase$phase.ok").writeText("PASS")
                } catch(t:Throwable) {logger.log(java.util.logging.Level.SEVERE,"REWARDS FAILED: $stage",t);dataFolder.resolve("failed.txt").writeText("$stage\n${t.stackTraceToString()}")}
                finally {server.globalRegionScheduler.execute(this) {server.shutdown()}}
            }
        },40)
    }
    private fun setup() {
        val hub=server.getWorld(Key.key("minecraft:overworld"))!!;floor(hub)
        mine.hub.persist(HubSettings(hub.key().asString(),false,5.5,101.0,5.5,0f,0f))
        val api=WorldsAccess.access();val key=Key.key("oneblock_smoke:reward_drops")
        dropWorld=mine.worlds.global {
            val old=server.getWorld(key)
            if(old!=null)CompletableFuture.completedFuture(old)
            else if(api.worldRegistry.isRegistered(key))api.load(key)
            else {
                val level=Level.builder(key).generatorType(GeneratorType.FLAT.with(com.alytanta.oneblock.island.IslandGeneration.voidPreset())).structures(false).build()
                api.create(level).thenCompose {w->mine.worlds.global {api.worldRegistry.register(level,true);CompletableFuture.completedFuture(w)}}
            }
        }.waitFor();floor(dropWorld)
        val source=main.dataFolder.resolve("dungeon.yml");val y=org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(source)
        mapOf("enabled" to true,"allow-instance-worlds" to true,"entry.world" to hub.key().asString(),"entry.x" to 5.5,"entry.y" to 101.0,"entry.z" to 5.5,
            "endless.warning-ticks" to 20,"endless.between-wave-ticks" to 1,"endless.count" to 2,"endless.growth.count" to 0.0).forEach {(k,v)->y.set(k,v)}
        y.save(source);mine.settings=MineSettings.read(source).first!!
    }
    private fun floor(w:World) {
        w.getChunkAtAsync(0,0,true).waitFor();mine.worlds.region(w,0,0) {for(x in 0..15)for(z in 0..15)for(y in 100..104)w.getBlockAt(x,y,z).setType(if(y==100)Material.STONE else Material.AIR,false)}.waitFor()
    }
    private fun prepare(n:Int) {
        mine.entity(p(n)) {
            val actor=p(n);actor.isOp=true;actor.isInvulnerable=true;actor.gameMode=GameMode.SURVIVAL;actor.health=20.0
            val a=mine.islands.findForPlayer(actor.uniqueId)?:mine.islands.createProfile(actor.uniqueId,"Rewards test")!!
            mine.players.update(actor.uniqueId) {it.copy(activeAccount=a.owner)}
            mine.islands.releaseUpdate(a.owner) {i,s->ReleaseChange(i.copy(coins=20000,releaseProgress=ReleaseProgress(chapter="developed")),s)}
            for(slot in 0..35)if(slot!=8)actor.inventory.setItem(slot,ItemStack(Material.DIRT,64))
            actor.teleportAsync(Location(server.getWorld(Key.key(mine.settings.entry.world)),5.5,101.0,5.5))
        }.thenCompose {it}.waitFor()
    }
    private fun grant(n:Int,wave:Long,boss:Boolean):PreparedCombatReward {
        val catalog=field(main,"combatCatalog") as CombatRewardCatalog
        val actor=p(n).uniqueId;val a=account(n)
        val request=CombatRewardRequest("rooms.native",UUID.randomUUID(),a.owner,actor,"room")
        val prepared=mine.settings.roomRewards!!.prepare(request,catalog,wave,boss,System.currentTimeMillis(),final=true,bossesKilled=if(boss)1L else 0L)
        main.combatRewards!!.awardPrepared(prepared).waitFor();main.combatRewards!!.awardPrepared(prepared).waitFor()
        return prepared
    }
    private fun offers(n:Int)=mine.storage.marketState().chests.values.filter {it.account==account(n).owner && it.recipient==p(n).uniqueId && it.status=="OFFERED"}
    private fun preview(n:Int,offer:ChestOffer) {
        await("reward menu admission") {mine.storage.healthy && !InventoryGuard.busy(p(n).uniqueId)}
        mine.entity(p(n)) {rewards.open(p(n));Unit}.waitFor()
        await("reward list open") {mine.entity(p(n)) {mine.menu.currentMenuId(p(n))=="release-claims"}.waitFor()}
        mine.entity(p(n)) {
            val actor=p(n);val inv=actor.openInventory.topInventory
            val plain=net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText()
            val key=NamespacedKey("one-block","reward_chest_id")
            val slot=(0 until inv.size).firstOrNull {inv.getItem(it)?.itemMeta?.persistentDataContainer?.get(key,PersistentDataType.STRING)==offer.id.toString()}
                ?:error("Chest UUID ${offer.id} absent from ${mine.menu.currentMenuId(actor)}; visible labels: "+inv.contents.filterNotNull().mapNotNull {it.itemMeta?.displayName()?.let(plain::serialize)})
            val event=InventoryClickEvent(actor.openInventory,org.bukkit.event.inventory.InventoryType.SlotType.CONTAINER,slot,org.bukkit.event.inventory.ClickType.LEFT,org.bukkit.event.inventory.InventoryAction.PICKUP_ALL)
            server.pluginManager.callEvent(event);check(event.isCancelled);Unit
        }.waitFor()
        await("chest preview") {mine.entity(p(n)) {mine.menu.currentMenuId(p(n))=="chest-preview"}.waitFor()}
        mine.entity(p(n)) {
            val inv=p(n).openInventory.topInventory
            val text=inv.getItem(4)!!.itemMeta.lore()!!.joinToString {net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().serialize(it)}
            check(text.contains(offer.price.toString()) && !text.contains("{reward_quote}"))
            val actual=mine.menu.definition("chest-preview")!!.content.mapNotNull {inv.getItem(it)}.mapNotNull(CustomItems::id)
            check(offer.items.map {it.id}.all {it in actual});Unit
        }.waitFor()
    }
    private fun open(n:Int,offer:ChestOffer) {
        preview(n,offer);command(n,"obmenu chests-confirm")
        mine.entity(p(n)) {rewards.action(p(n),"chests-buy");rewards.action(p(n),"chests-buy");Unit}.waitFor()
        await("SQL chest open") {mine.storage.marketState().chests[offer.id]?.status=="OPENED" && !InventoryGuard.busy(p(n).uniqueId)}
    }
    private fun bulk(n:Int) {
        mine.entity(p(n)) {rewards.open(p(n));rewards.action(p(n),"claims-all");rewards.action(p(n),"claims-all");Unit}.waitFor()
        await("bulk completion") {!rewards.collection.busy(p(n).uniqueId) && !InventoryGuard.busy(p(n).uniqueId)}
    }
    private fun first() {
        (1..6).forEach(::prepare);mine.storage.flushAsync().waitFor()
        val paid=grant(1,5,true);grant(1,3,false)
        val offer=offers(1).first {it.price>0};val free=offers(1).first {it.price==0L}
        val startCoins=account(1).coins
        bulk(1);check(mine.storage.marketState().chests[offer.id]!!.status=="OFFERED" && mine.storage.marketState().chests[free.id]!!.status=="OFFERED")
        note("collect-all with full bags opens neither paid nor free chest")
        preview(1,offer);val identity=mine.menu.configurationIdentity;command(1,"obadmin reload")
        await("reload") {mine.menu.configurationIdentity!==identity}
        check(mine.storage.marketState().chests[offer.id]==offer)
        open(1,offer);check(account(1).coins==startCoins-offer.price)
        check(mine.storage.marketState().claims.values.any {it.source=="chest:${offer.id}"})
        check(mine.storage.marketState().combatReceipts.containsKey(paid.request.receipt))
        note("exact loot and price preview, reload, duplicate confirmation and full-bag purchase produce one debit and owned claims")
        mine.entity(p(1)) {p(1).inventory.setItem(0,null);Unit}.waitFor();bulk(1)
        check(mine.storage.marketState().chests[free.id]!!.status=="OFFERED")
        mine.entity(p(1)) {for(slot in listOf(1,2,3,4,5,6))p(1).inventory.setItem(slot,null);Unit}.waitFor();bulk(1)
        check(mine.storage.marketState().claims.values.none {it.account==offer.account && it.recipient==p(1).uniqueId})
        open(1,free);bulk(1)
        note("partial native inventory delivery keeps leftovers; later collect-all takes owned chest contents without opening other offers")
        val expired=offer.copy(id=UUID.randomUUID(),created=System.currentTimeMillis()-ChestDomain.TTL,expires=System.currentTimeMillis()+3000,status="OFFERED",opened=null)
        mine.islands.marketUpdate(offer.account) {i,s->MarketChange(i.coins,s.copy(chests=s.chests+(expired.id to expired)),Unit)};mine.storage.flushAsync().waitFor()
        preview(1,expired);waitTicks(80);val before=account(1).coins;command(1,"obmenu chests-confirm");command(1,"obmenu chests-buy")
        check(account(1).coins==before && mine.storage.marketState().chests[expired.id]!!.status=="OFFERED")
        note("actual expiry passes while preview is open; confirm refuses without a debit")
        exactPartial();sqlFault();armorAndIds();bazaarFragments();dropsAndProfiles();waveProducer()
        val unpaid=grant(2,5,true);val savedOffer=offers(2).first {it.price>0}
        val owned=grant(3,5,true);val savedPaid=offers(3).first {it.price>0};open(3,savedPaid)
        dataFolder.resolve("restart.json").writeText(com.google.gson.Gson().toJson(mapOf("offer" to savedOffer.id.toString(),"paid" to savedPaid.id.toString(),"price" to savedOffer.price,"fingerprint" to savedOffer.fingerprint(),"paidCoins" to account(3).coins,"receipt" to unpaid.request.receipt.toString(),"ownedReceipt" to owned.request.receipt.toString())))
        (1..6).forEach {mine.entity(p(it)) {p(it).saveData();Unit}.waitFor()};mine.storage.flushAsync().waitFor()
        note("unpaid offer, paid contents, pity and manual-drop metadata saved for restart")
    }
    private fun exactPartial() {
        val a=account(5);val actor=p(5).uniqueId
        val encoded=mine.entity(p(5)) {
            val stack=ItemStack(Material.GOLD_INGOT,4).apply {editMeta {it.displayName(net.kyori.adventure.text.Component.text("Exact split probe"))}}
            p(5).inventory.setItem(0,stack.clone().apply {amount=63});AuctionItems.encode(stack,65536)
        }.waitFor()
        val claim=MarketClaim(UUID.randomUUID(),a.owner,"GOLD_INGOT",4,"native-exact-split",encoded,actor)
        mine.islands.marketUpdate(a.owner) {i,s->MarketChange(i.coins,s.copy(claims=s.claims+(claim.id to claim)),Unit)};mine.storage.flushAsync().waitFor()
        bulk(5)
        mine.entity(p(5)) {check(p(5).inventory.getItem(0)!!.amount==64);Unit}.waitFor()
        check(mine.storage.marketState().claims.values.filter {it.account==a.owner && it.source==claim.source}.sumOf {it.amount}==3L)
        mine.entity(p(5)) {p(5).inventory.setItem(0,null);Unit}.waitFor();bulk(5)
        check(mine.storage.marketState().claims.values.none {it.account==a.owner && it.source==claim.source})
        note("partially fitting exact stack splits once into deterministic UUID claims and collects without loss or duplicate bulk execution")
    }
    private fun sqlFault() {
        grant(4,5,true);val offer=offers(4).first {it.price>0};val before=account(4).coins
        preview(4,offer);command(4,"obmenu chests-confirm")
        dataFolder.resolve("test-db-disconnect").writeText("own isolated PostgreSQL only")
        await("SQL fault signal") {dataFolder.resolve("test-db-disconnected").exists()}
        command(4,"obmenu chests-buy")
        await("failed or committed purchase guard") {!InventoryGuard.busy(p(4).uniqueId)}
        await("SQL recovery") {mine.storage.healthy}
        if(mine.storage.marketState().chests[offer.id]!!.status!="OPENED")open(4,offer)
        mine.storage.flushAsync().waitFor()
        check(account(4).coins==before-offer.price)
        check(mine.storage.marketState().claims.values.count {it.source=="chest:${offer.id}"}==offer.items.size)
        note("native SQL disconnect and retry retain one purchase identity, one debit and one exact set of owned contents")
    }
    /** Custom stacks through the real handoff: order takes them, cancel and withdraw return the same count, plain shards stay. */
    private fun bazaarFragments() {
        val market=field(org.bukkit.event.HandlerList.getRegisteredListeners(main).map {it.listener}.filterIsInstance<com.alytanta.oneblock.release.ReleaseMenus>().first(),"market") as com.alytanta.oneblock.market.MarketController
        val key="oneblock_battle_fragment";val owner=account(1).owner;val player=p(1)
        fun fragments()=player.inventory.storageContents.filter {CustomItems.id(it)=="oneblock:battle_fragment"}.sumOf {it!!.amount}
        fun transfer(kind:com.alytanta.oneblock.market.TransferKind,quantity:Int,price:Long=0,claim:java.util.UUID?=null) {
            val done=CompletableFuture<Unit>()
            mine.entity(player) {market.handoffs.transfer(player,owner,key,quantity,kind,price,claim,failed={done.completeExceptionally(IllegalStateException("handoff $kind"))},silent=true) {done.complete(Unit)}}.waitFor()
            done.waitFor()
        }
        val before=mine.entity(player) {
            player.inventory.setItem(20,CustomItems.create(mine.islands.releaseRules!!.customItems!!.require("battle_fragment")).apply {amount=5})
            player.inventory.setItem(21,org.bukkit.inventory.ItemStack(org.bukkit.Material.PRISMARINE_SHARD,7));fragments()
        }.waitFor()
        check(before>=5)
        transfer(com.alytanta.oneblock.market.TransferKind.SELL_ORDER,3,10)
        check(mine.entity(player) {fragments()}.waitFor()==before-3) {"Order did not take exactly three custom stacks"}
        check(mine.entity(player) {player.inventory.getItem(21)?.amount}.waitFor()==7) {"Plain shards were taken as fragments"}
        val order=mine.storage.marketState().orders.values.single {it.account==owner && it.product==key && it.status==com.alytanta.oneblock.market.OrderStatus.OPEN}
        val live=mine.islands.findByOwner(owner)!!
        mine.islands.marketUpdate(owner) {i,state->com.alytanta.oneblock.market.MarketEngine(market.settings).transaction(owner,player.uniqueId,i.coins,state) {cancel(order.id)}}
        mine.storage.flushAsync().waitFor();check(live.owner==owner)
        val claim=mine.storage.marketState().claims.values.single {it.account==owner && it.product==key}
        transfer(com.alytanta.oneblock.market.TransferKind.WITHDRAW,claim.amount.toInt(),claim=claim.id)
        check(mine.entity(player) {fragments()}.waitFor()==before) {"Withdraw did not return the custom stacks"}
        check(mine.storage.marketState().claims.values.none {it.account==owner && it.product==key})
        mine.entity(player) {player.inventory.setItem(20,null);player.inventory.setItem(21,null)}.waitFor()
        note("Bazaar custom product: order, cancel and withdraw keep the exact count and never touch plain shards")
    }
    private fun armorAndIds() {
        val rules=mine.islands.releaseRules!!;check(rules.recipes.values.count {r->r.items.any {rules.customItems?.find(it.tool.orEmpty())?.kind==com.alytanta.oneblock.items.ItemKind.ARMOR}}==12)
        mine.entity(p(1)) {
            val catalog=rules.customItems!!
            for(d in catalog.items.values) {
                val item=CustomItems.create(d);val text=item.itemMeta.lore()!!.joinToString {net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().serialize(it)}
                check(text.contains("ID: ${d.id}"))
            }
            val stack=CustomItems.create(catalog.require("oneblock:warden_helmet"));stack.addUnsafeEnchantment(org.bukkit.enchantments.Enchantment.PROTECTION,4)
            val meta=stack.itemMeta;meta.displayName(net.kyori.adventure.text.Component.text("Renamed personal helmet"));meta.lore(listOf(net.kyori.adventure.text.Component.text("Foreign personal lore")))
            meta.persistentDataContainer.remove(NamespacedKey("one-block","item_visible_id_line"));stack.itemMeta=meta
            stack.editMeta {m->listOf("item_id","item_schema","item_appearance").forEach {m.persistentDataContainer.remove(NamespacedKey("one-block",it))}}
            check(CustomItems.refresh(stack));check(stack.itemMeta.displayName()==net.kyori.adventure.text.Component.text("Renamed personal helmet"));check(stack.getEnchantmentLevel(org.bukkit.enchantments.Enchantment.PROTECTION)==4)
            check(stack.itemMeta.lore()!!.any {net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().serialize(it)=="Foreign personal lore"})
            val staff=catalog.require("rift_staff");val personal=CustomItems.create(staff)
            personal.editMeta {m->m.displayName(net.kyori.adventure.text.Component.text("Named before balance update"));m.lore(m.lore().orEmpty()+net.kyori.adventure.text.Component.text("Owner annotation"))}
            val changed=staff.copy(stats=staff.stats+("mana-cost" to (staff.stats.getValue("mana-cost")+1)))
            check(CustomItems.refresh(personal,catalog.copy(items=catalog.items+(staff.id to changed))))
            check(personal.itemMeta.displayName()==net.kyori.adventure.text.Component.text("Named before balance update"))
            check(personal.itemMeta.lore()!!.contains(net.kyori.adventure.text.Component.text("Owner annotation")))
            p(1).inventory.setItem(7,stack)
            val texture="https://textures.minecraft.net/texture/"+"a".repeat(64)
            val encoded=java.util.Base64.getEncoder().encodeToString(("{\"textures\":{\"SKIN\":{\"url\":\""+texture.replace("https:","http:")+"\"}}}").toByteArray())
            val helmet=catalog.require("warden_helmet")
            for(value in listOf(texture,encoded)) {
                val variant=helmet.copy(headProfile=null,headTexture=value)
                val h=CustomItems.create(variant).itemMeta as org.bukkit.inventory.meta.SkullMeta
                check(h.ownerProfile?.textures?.skin?.toExternalForm()==texture)
            }
            @Suppress("UNCHECKED_CAST") val cache=field(CustomItems,"headProfileUrls") as ConcurrentHashMap<String,CompletableFuture<String?>>
            val pending=CompletableFuture<String?>();cache["cache_probe"]=pending
            val profile=helmet.copy(headProfile="cache_probe",headTexture=null)
            val head=CustomItems.create(profile);head.editMeta {it.displayName(net.kyori.adventure.text.Component.text("Named cached helmet"))}
            pending.complete(texture)
            val variantCatalog=catalog.copy(items=catalog.items+(profile.id to profile))
            CustomItems.refresh(head,variantCatalog)
            check((head.itemMeta as org.bukkit.inventory.meta.SkullMeta).ownerProfile?.textures?.skin?.toExternalForm()==texture)
            check(head.itemMeta.displayName()==net.kyori.adventure.text.Component.text("Named cached helmet"))
            check(cache["cache_probe"]===pending)
            Unit
        }.waitFor();note("all custom IDs visible; old personal name/lore/enchantments retained; three armor sets and textured head carriers preserved")
    }
    private fun teleport(n:Int,w:World) {mine.entity(p(n)) {p(n).teleportAsync(Location(w,5.5,101.0,5.5))}.thenCompose {it}.waitFor()}
    private fun switch(n:Int,id:UUID) {
        val profiles=(main.getCommand("oneblock")!!.executor as com.alytanta.oneblock.command.OneBlockCommand).profiles
        CompletableFuture<Unit>().also {d->mine.entity(p(n)) {profiles.switch(p(n),id) {d.complete(Unit)}}.exceptionally {d.completeExceptionally(it);null}}.waitFor()
        // The switch callback precedes its asynchronous home teleport. Wait before moving elsewhere.
        await("profile home arrival") {mine.entity(p(n)) {mine.islands.sameWorld(mine.islands.findByOwner(id)!!,p(n).world)}.waitFor()}
    }
    private fun dropped(n:Int):Item {
        drops.remove(p(n).uniqueId)
        mine.entity(p(n)) {p(n).inventory.heldItemSlot=0;p(n).inventory.setItem(0,ItemStack(Material.EMERALD,4));check(p(n).dropItem(false));Unit}.waitFor()
        await("native manual drop") {drops.containsKey(p(n).uniqueId)}
        return drops.getValue(p(n).uniqueId)
    }
    private fun findDrop(id:UUID):Item?=mine.worlds.region(dropWorld,0,0) {dropWorld.getChunkAt(0,0).entities.filterIsInstance<Item>().firstOrNull {it.uniqueId==id}}.waitFor()
    private fun pickup(n:Int,item:Item):Boolean=mine.entity(p(n)) {
        check(Bukkit.isOwnedByCurrentRegion(item)) {"Pickup fixture must own both player and item"}
        val e=EntityPickupItemEvent(p(n),item,0);server.pluginManager.callEvent(e);!e.isCancelled
    }.waitFor()
    private fun dropsAndProfiles() {
        teleport(1,dropWorld);teleport(2,dropWorld)
        val source=account(1).owner;val other=account(2).owner
        val item=dropped(1)
        entity(item) {item.pickupDelay=32767;check(item.persistentDataContainer.get(ownerKey,PersistentDataType.STRING)==p(1).uniqueId.toString());check(item.persistentDataContainer.get(profileKey,PersistentDataType.STRING)==source.toString());item.velocity=org.bukkit.util.Vector();Unit}.waitFor()
        check(pickup(1,item));check(!pickup(2,item))
        mine.islands.setMemberRole(source,p(2).uniqueId,IslandRole.MEMBER,true);switch(2,source);teleport(2,dropWorld);check(pickup(2,item))
        switch(2,other);teleport(2,dropWorld);check(!pickup(2,item));mine.islands.setMemberRole(source,p(2).uniqueId,null,true);check(!pickup(2,item))
        val next=mine.islands.createProfile(p(1).uniqueId,"Drop isolation")!!.owner
        switch(1,next);teleport(1,dropWorld);check(!pickup(1,item));switch(1,source);teleport(1,dropWorld);check(pickup(1,item))
        note("native manual drop binds owner and source profile; solo denies outsiders; co-op membership and active-profile changes are enforced")
        mine.worlds.region(dropWorld,0,0) {
            val hopper=dropWorld.getBlockAt(10,100,10).apply {type=Material.HOPPER}.state as org.bukkit.block.Hopper
            val take=InventoryPickupItemEvent(hopper.inventory,item);server.pluginManager.callEvent(take);check(take.isCancelled)
            val mob=dropWorld.spawn(Location(dropWorld,10.5,101.0,10.5),Zombie::class.java)
            val event=EntityPickupItemEvent(mob,item,0);server.pluginManager.callEvent(event);check(event.isCancelled);mob.remove()
            val natural=dropWorld.dropItem(Location(dropWorld,12.5,101.0,12.5),ItemStack(Material.EMERALD));natural.pickupDelay=32767
            check(!natural.persistentDataContainer.has(ownerKey));val merge=ItemMergeEvent(item,natural);server.pluginManager.callEvent(merge);check(merge.isCancelled)
            val normalHopper=InventoryPickupItemEvent(hopper.inventory,natural);server.pluginManager.callEvent(normalHopper);check(!normalHopper.isCancelled);natural.remove();Unit
        }.waitFor()
        teleport(1,server.getWorld(Key.key(mine.settings.entry.world))!!);teleport(2,server.getWorld(Key.key(mine.settings.entry.world))!!)
        val id=item.uniqueId;val api=WorldsAccess.access();check(mine.worlds.global {api.unload(dropWorld,true)}.waitFor())
        dropWorld=mine.worlds.global {api.load(Key.key("oneblock_smoke:reward_drops"))}.waitFor();dropWorld.getChunkAtAsync(0,0,true).waitFor()
        await("item chunk reload") {findDrop(id)!=null};val loaded=findDrop(id)!!
        entity(loaded) {check(loaded.persistentDataContainer.get(profileKey,PersistentDataType.STRING)==source.toString());check(loaded.persistentDataContainer.get(ownerKey,PersistentDataType.STRING)==p(1).uniqueId.toString());Unit}.waitFor()
        dataFolder.resolve("drop.txt").writeText("$id\n$source")
        note("hopper, mob and cross-owner merge denied; natural loot unchanged; entity PDC survives real Worlds unload/load")
    }
    private fun waveProducer() {
        command(6,"dungeon menu");command(6,"dungeon ready");command(6,"dungeon start")
        await("wave run") {mine.binding(p(6).uniqueId)?.phase==MinePhase.WAVE};val id=mine.binding(p(6).uniqueId)!!.id
        for(n in 1L..5) {
            await("wave $n") {mine.engine.get(id)?.let {s->s.phase==MinePhase.WAVE && s.wave!!.plan.number==n && mine.encounters.mobs.values.count {it.run==id && it.epoch==s.epoch}==s.wave.plan.spawns.size}==true}
            mine.encounters.mobs.values.filter {it.run==id}.forEach {m->entity(m.entity) {m.entity.damage(1000000.0);Unit}.waitFor()}
            await("wave clear") {mine.engine.get(id)?.wave?.completed==n}
        }
        command(6,"dungeon finish");await("wave cleanup") {mine.engine.get(id)==null}
        check(offers(6).any {it.price>0} && offers(6).any {it.price==0L})
        note("actual completed waves publish separate free/paid offers through the durable room producer")
    }
    private fun second() {
        val json=com.google.gson.JsonParser.parseString(dataFolder.resolve("restart.json").readText()).asJsonObject
        val unpaid=mine.storage.marketState().chests.getValue(UUID.fromString(json["offer"].asString));check(unpaid.price==json["price"].asLong && unpaid.fingerprint()==json["fingerprint"].asString)
        val bought=mine.storage.marketState().chests.getValue(UUID.fromString(json["paid"].asString));check(bought.status=="OPENED" && account(3).coins==json["paidCoins"].asLong)
        check(mine.storage.marketState().claims.values.any {it.source=="chest:${bought.id}"})
        val lines=dataFolder.resolve("drop.txt").readLines();dropWorld.getChunkAtAsync(0,0,true).waitFor();await("restart manual item") {findDrop(UUID.fromString(lines[0]))!=null}
        val item=findDrop(UUID.fromString(lines[0]))!!;entity(item) {item.pickupDelay=32767;check(item.persistentDataContainer.get(profileKey,PersistentDataType.STRING)==lines[1]);Unit}.waitFor()
        note("second native boot preserves exact unpaid offer price/loot, paid owned contents, wallet and manual-drop PDC")
        val before=account(3).coins
        val replay=mine.islands.marketUpdate(bought.account) {i,s->ChestDomain.open(i,s,p(3).uniqueId,bought,System.currentTimeMillis()+ChestDomain.TTL)}
        mine.storage.flushAsync().waitFor();check(account(3).coins==before && replay.status=="OPENED")
        mine.entity(p(3)) {for(slot in 0..7)p(3).inventory.setItem(slot,null);rewards.open(p(3));rewards.action(p(3),"claims-all");Unit}.waitFor()
        await("post restart collection") {!rewards.collection.busy(p(3).uniqueId)}
        check(mine.storage.marketState().claims.values.none {it.source=="chest:${bought.id}"})
        note("paid chest replay after original expiry does not debit; retained contents collect after restart")
    }
}
