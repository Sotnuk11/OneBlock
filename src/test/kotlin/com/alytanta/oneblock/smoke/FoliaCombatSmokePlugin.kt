package com.alytanta.oneblock.smoke

import com.alytanta.oneblock.OneBlock
import com.alytanta.oneblock.combat.*
import com.alytanta.oneblock.island.*
import com.alytanta.oneblock.market.*
import com.alytanta.oneblock.menu.PlayerMenu
import com.alytanta.oneblock.player.PlayerService
import com.alytanta.oneblock.release.*
import com.alytanta.oneblock.items.*
import com.alytanta.oneblock.storage.PostgresStorageService
import org.bukkit.*
import org.bukkit.entity.*
import org.bukkit.event.*
import org.bukkit.event.entity.*
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.event.block.Action
import org.bukkit.inventory.*
import org.bukkit.plugin.java.JavaPlugin
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.atomic.AtomicInteger

/** Test-only native integration; synthetic trusted actions use the real connected player's entity scheduler. */
class FoliaCombatSmokePlugin:JavaPlugin(),Listener {
    private lateinit var production:OneBlock
    private lateinit var islands:IslandService
    private lateinit var storage:PostgresStorageService
    private lateinit var players:PlayerService
    private lateinit var menu:PlayerMenu
    private val hits=AtomicInteger();private val launches=AtomicInteger()
    private val windLaunches=AtomicInteger()
    private val protectionCancels=AtomicInteger()
    @Volatile private var lastWind:WindCharge?=null
    private val joined=java.util.concurrent.atomic.AtomicBoolean()
    private val rangedHits=AtomicInteger()
    @Volatile private var rangedBase=0.0
    @Volatile private var rangedDamage=0.0
    @Volatile private var magicDamage=0.0
    private var balanceTarget:UUID?=null
    private val balanceRows=java.util.concurrent.ConcurrentLinkedQueue<Pair<Double,Double>>()
    private val balanceRaw=java.util.Collections.synchronizedMap(java.util.WeakHashMap<EntityDamageByEntityEvent,Double>())
    @Volatile private var stage="startup"
    private val eventId=UUID.fromString("75777f8d-1e8f-4c26-ad56-ef03683d4620")
    override fun onEnable() {
        dataFolder.mkdirs();server.pluginManager.registerEvents(this,this)
        server.globalRegionScheduler.runDelayed(this,{
            runCatching { start() }.getOrElse { CompletableFuture.failedFuture(it) }.orTimeout(300,java.util.concurrent.TimeUnit.SECONDS).whenComplete { _,error ->
                CompletableFuture.runAsync {
                    if(error!=null) { logger.log(java.util.logging.Level.SEVERE,"COMBAT SMOKE FAILED",error);dataFolder.resolve("failed.txt").writeText(error.stackTraceToString()) }
                    else dataFolder.resolve(if(dataFolder.resolve("phase1.ok").exists())"phase2.ok" else "phase1.ok").writeText("PASS")
                }.whenComplete { _,_ ->
                    if(error==null && java.lang.Boolean.getBoolean("oneblock.combat-visual") && dataFolder.resolve("phase2.ok").exists()) {
                        dataFolder.resolve("visual-ready").writeText(server.port.toString())
                        val deadline=System.nanoTime()+1800_000_000_000
                        server.asyncScheduler.runAtFixedRate(this,{task->if(dataFolder.resolve("visual-finish").exists()||System.nanoTime()-deadline>=0) {task.cancel();server.globalRegionScheduler.execute(this) {server.shutdown()}}},1,1,java.util.concurrent.TimeUnit.SECONDS)
                    } else server.globalRegionScheduler.execute(this) { server.shutdown() }
                }
            }
        },40)
    }
    private fun field(target:Any,name:String)=target.javaClass.getDeclaredField(name).apply { isAccessible=true }.get(target)
    private fun <T> entity(p:Player,action:()->T)=CompletableFuture<T>().also { done ->
        if(!p.scheduler.execute(this,{try {done.complete(action())}catch(e:Throwable){done.completeExceptionally(e)}},{done.completeExceptionally(IllegalStateException("player retired"))},1))done.completeExceptionally(IllegalStateException("player unavailable"))
    }
    private fun <T> global(action:()->T)=CompletableFuture<T>().also {done->server.globalRegionScheduler.execute(this) {
        try {done.complete(action())}catch(e:Throwable){done.completeExceptionally(e)}
    }}
    /** Real server packet handler, including vanilla's prediction of an inert carrier; no graphical client claims. */
    private fun nativeUse(p:Player) {
        val hand=Class.forName("net.minecraft.world.InteractionHand")
        val packetType=Class.forName("net.minecraft.network.protocol.game.ServerboundUseItemPacket")
        val packet=packetType.getConstructor(hand,Int::class.javaPrimitiveType,Float::class.javaPrimitiveType,Float::class.javaPrimitiveType)
            .newInstance(hand.enumConstants.first {(it as Enum<*>).name=="MAIN_HAND"},1,p.location.yaw,p.location.pitch)
        packetType.getField("timestamp").setLong(packet,System.currentTimeMillis())
        val handle=p.javaClass.getMethod("getHandle").invoke(p)
        val connection=handle.javaClass.getField("connection").get(handle)
        connection.javaClass.getMethod("handleUseItem",packetType).invoke(connection,packet)
    }
    private fun await(test:()->Boolean,left:Int=1000):CompletableFuture<Unit> = CompletableFuture<Unit>().also { done ->
        var remaining=left
        server.globalRegionScheduler.runAtFixedRate(this,{ task ->
            try {
                if(done.isDone)task.cancel()
                else if(test()) {task.cancel();done.complete(Unit)}
                else if(--remaining<=0) {task.cancel();done.completeExceptionally(IllegalStateException("Combat smoke timeout at $stage; transfers=${storage.marketState().transfers.values.map {it.status to it.release?.value}}; guard=${server.getPlayerExact("HubSmokeBot")?.uniqueId?.let {InventoryGuard.busy(it)}}"))}
            } catch(e:Throwable) {task.cancel();done.completeExceptionally(e)}
        },1,1)
    }
    private fun stage(value:String) {stage=value;logger.info("Combat smoke: $value")}
    private fun waitEntity(p:Player,left:Int=400,test:()->Boolean):CompletableFuture<Unit> = entity(p) {test()}.thenCompose {ready->
        if(ready)CompletableFuture.completedFuture(Unit) else {
            check(left>0) {"Entity condition timeout at $stage"}
            CompletableFuture<Unit>().also {done->p.scheduler.runDelayed(this,{waitEntity(p,left-1,test).whenComplete {_,e->if(e==null)done.complete(Unit) else done.completeExceptionally(e)}},{done.completeExceptionally(IllegalStateException("retired"))},2)}
        }
    }
    private fun demoBattle(p:Player):CompletableFuture<Unit> {
        stage("completed Breaker demonstration: locked telegraph, dodge, vulnerability, three weapons")
        val demo=field(production,"combatDemo") as CombatDemo
        fun run():Any?=(field(demo,"runs") as Map<*,*>)[p.uniqueId]
        fun boss()=field(run()!!,"mob") as Zombie
        return entity(p) {
            (production.combatStats as CombatRuntime).techniques.nearby(p.location,12.0).filterIsInstance<Zombie>().forEach {it.remove()}
            for(x in -8..18)for(z in -8..18) {check(Bukkit.isOwnedByCurrentRegion(p.world,x shr 4,z shr 4));p.world.getBlockAt(x,199,z).setType(Material.STONE,false)}
            p.setRotation(0f,0f);demo.start(p)
        }.thenCompose {waitEntity(p) {run()!=null && field(run()!!,"shape")!=null}}.thenCompose {entity(p) {
            stage("Breaker locked windup")
            val shape=field(run()!!,"shape") as DemoCleave
            check(shape.contains(p.location.x,p.location.y,p.location.z))
            p.teleportAsync(Location(p.world,shape.x-shape.nz*7,200.0,shape.z+shape.nx*7))
        }}.thenCompose {it}.thenCompose {check(it);waitEntity(p) {run()!=null && field(run()!!,"exposed")==true}}.thenCompose {entity(p) {
            stage("Breaker dodge and counterattack")
            val mob=boss();val health=mob.health
            p.inventory.setItemInMainHand(CombatItems.create(rules().items.getValue("warden_blade")))
            mob.damage(8.0,p)
            check(health-mob.health>8.0) {"Counterattack opening did not amplify damage"}
            val at=p.eyeLocation;val dir=mob.eyeLocation.toVector().subtract(at.toVector()).normalize()
            val arrow=p.launchProjectile(Arrow::class.java,dir.multiply(2.0));arrow.damage=4.0;arrow.pickupStatus=AbstractArrow.PickupStatus.DISALLOWED
            EntityShootBowEvent(p,CombatItems.create(rules().items.getValue("ember_crossbow")),ItemStack(Material.ARROW),arrow,EquipmentSlot.HAND,1f,true).callEvent()
        }}.thenCompose {waitEntity(p) {run()!=null && boss().health<rules().demo.health-12}}.thenCompose {entity(p) {
            stage("Breaker full fight")
            // Test driver fights at native cadence and dodges by explicit teleport; this is not a human playtest.
            p.scheduler.runAtFixedRate(this,{task->
                if(run()==null) {task.cancel();return@runAtFixedRate}
                val mob=boss()
                if(!Bukkit.isOwnedByCurrentRegion(mob))return@runAtFixedRate
                val shape=field(run()!!,"shape") as DemoCleave?
                if(shape!=null)p.teleportAsync(Location(p.world,shape.x-shape.nz*6,200.0,shape.z+shape.nx*6))
                val delta=mob.eyeLocation.toVector().subtract(p.eyeLocation.toVector());p.setRotation(Math.toDegrees(kotlin.math.atan2(-delta.x,delta.z)).toFloat(),0f)
                p.inventory.setItemInMainHand(CombatItems.create(rules().items.getValue("rift_staff")))
                PlayerInteractEvent(p,Action.RIGHT_CLICK_AIR,p.inventory.itemInMainHand,null,org.bukkit.block.BlockFace.SELF,EquipmentSlot.HAND).callEvent()
                if(p.location.distanceSquared(mob.location)<=36) {p.inventory.setItemInMainHand(CombatItems.create(rules().items.getValue("warden_blade")));mob.damage(8.0,p)}
            },{},24,24)
        }}.thenCompose {waitEntity(p,1000) {run()==null}}.thenApply {stage("Breaker complete demonstration PASS (automated driver)");Unit}
    }
    private fun start():CompletableFuture<Unit> {
        production=server.pluginManager.getPlugin("one-block") as OneBlock
        storage=field(production,"storage") as PostgresStorageService;islands=field(production,"activeIslands") as IslandService
        players=field(islands,"players") as PlayerService;menu=field(production,"mainMenu") as PlayerMenu
        check(storage.healthy)
        return CompletableFuture.runAsync {dataFolder.resolve("bot-ready").writeText("775")}.thenCompose {await({joined.get() && server.getPlayerExact("HubSmokeBot")!=null})}.thenCompose {
            val p=server.getPlayerExact("HubSmokeBot")!!
            packAdmission(p).thenCompose {await({!InventoryGuard.busy(p.uniqueId) && p.uniqueId !in (field(field(production,"trades")!!,"collecting") as Set<*>)} )}.thenCompose {entity(p) {
                p.isOp=true;p.gameMode=GameMode.CREATIVE;p.allowFlight=true;p.isFlying=true
                p.teleportAsync(Location(server.getWorld(net.kyori.adventure.key.Key.key("minecraft:overworld")),2.5,200.0,2.5,0f,0f))
            }}.thenCompose {it}.thenCompose { ok -> check(ok)
                if(dataFolder.resolve("phase1.ok").exists())second(p).thenCompose {hostFailure(p,true)} else first(p).thenCompose {hostFailure(p,false)}
            }
        }
    }
    private fun hostFailure(p:Player,offline:Boolean):CompletableFuture<Unit> {
        val gate=field(production,"packGate") as ResourcePackGate
        if(!gate.admission.settings.enabled)return CompletableFuture.completedFuture(Unit)
        stage(if(offline)"unavailable loopback hosting rejects applied admission" else "real HTTP 404 download failure rejects applied admission")
        return CompletableFuture<Unit>().also {done->server.globalRegionScheduler.execute(this) {
            val old=gate.admission.settings
            gate.apply(old.copy(url=if(offline)"http://127.0.0.1:1/offline.zip" else old.url.substringBeforeLast('/')+"/missing.zip",sha1=(if(offline)"c" else "b").repeat(40)))
            check(!gate.allowed(p.uniqueId));done.complete(Unit)
        }}.thenCompose {await({server.getPlayer(p.uniqueId)==null && dataFolder.resolve("HubSmokeBot-pack-download-error").exists()})}
            .thenApply {stage("hosting failure disconnect PASS; no public URL or real-server changes");Unit}
    }
    private fun packAdmission(p:Player):CompletableFuture<Unit> {
        val gate=field(production,"packGate") as ResourcePackGate
        if(!gate.admission.settings.enabled)return CompletableFuture.completedFuture(Unit)
        stage("mandatory pack: ACCEPTED remains blocked, real loopback ZIP download")
        return await({gate.admission.current(p.uniqueId)?.state==PackAdmission.State.ACCEPTED}).thenCompose {entity(p) {
            check(!gate.allowed(p.uniqueId) && InventoryGuard.busy(p.uniqueId))
            val old=menu.currentMenuId(p);menu.show(p,"main");check(menu.currentMenuId(p)==old)
            val swap=org.bukkit.event.player.PlayerSwapHandItemsEvent(p,ItemStack(Material.DIRT),ItemStack(Material.STICK));swap.callEvent();check(swap.isCancelled)
            val click=PlayerInteractEvent(p,Action.RIGHT_CLICK_AIR,ItemStack(Material.STICK),null,org.bukkit.block.BlockFace.SELF,EquipmentSlot.HAND);click.callEvent();check(click.useItemInHand()==Event.Result.DENY)
        }}.thenCompose {CompletableFuture.runAsync {dataFolder.resolve("HubSmokeBot-pack-hold").delete()}}
            .thenCompose {await({gate.allowed(p.uniqueId) && server.getPlayerExact("ItemPeer")?.let {gate.allowed(it.uniqueId)}==true})}
            .thenCompose {await({listOf("PackDecline","PackFail","PackTimeout","PackPremature").all {dataFolder.resolve("$it-failure.txt").exists() && server.getPlayerExact(it)==null}})}
            .thenCompose {entity(p) {
                val ticket=gate.admission.current(p.uniqueId)!!
                check(ticket.state==PackAdmission.State.READY)
                check(!gate.admission.status(p.uniqueId,UUID.randomUUID(),"FAILED_DOWNLOAD"))
                check(gate.allowed(p.uniqueId))
                gate.offer(p) // New request on the same session: download cache must still acknowledge application.
                check(!gate.allowed(p.uniqueId) && gate.admission.current(p.uniqueId)!!.id!=ticket.id)
            }}.thenCompose {await({gate.allowed(p.uniqueId)})}.thenApply {stage("pack failure/decline/timeout/premature status, per-player isolation and re-offer PASS");Unit}
    }
    private fun rules()=islands.releaseRules!!.combat!!
    private fun request(account:UUID,p:Player)=CombatRewardRequest("native.smoke",eventId,account,p.uniqueId,"elite_encounter")
    private fun first(p:Player):CompletableFuture<Unit> = entity(p) {
        val i=islands.createProfile(p.uniqueId,"CombatSmoke")!!
        players.update(p.uniqueId) {it.copy(activeAccount=i.owner)}
        islands.releaseUpdate(i.owner) { live,state -> ReleaseChange(live.copy(coins=1000000,releaseProgress=ReleaseProgress(chapter="developed")),state) }
        for(item in rules().items.values) {
            val native=CombatItems.create(item);val copy=ItemStack.deserializeBytes(native.serializeAsBytes())
            check(CombatItems.resolve(copy,rules())?.id==item.id)
            val plain=net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText()
            check(copy.lore()?.any {plain.serialize(it).contains("ID: oneblock:${item.id}")}==true) {"Visible catalog ID missing"}
            val oldStyle=copy.clone();val foreign=NamespacedKey("smoke","foreign")
            oldStyle.editMeta {meta->
                meta.persistentDataContainer.set(NamespacedKey("one-block","item_appearance"),org.bukkit.persistence.PersistentDataType.STRING,"previous-style")
                meta.persistentDataContainer.set(foreign,org.bukkit.persistence.PersistentDataType.INTEGER,7)
                if(meta is org.bukkit.inventory.meta.Damageable)meta.damage=7
            }
            check(CustomItems.refresh(oldStyle) && !CustomItems.refresh(oldStyle))
            check(oldStyle.amount==copy.amount && CustomItems.id(oldStyle)==CustomItems.id(copy))
            check(oldStyle.itemMeta.persistentDataContainer.get(foreign,org.bukkit.persistence.PersistentDataType.INTEGER)==7)
            if(oldStyle.itemMeta is org.bukkit.inventory.meta.Damageable)check((oldStyle.itemMeta as org.bukkit.inventory.meta.Damageable).damage==7)
            copy.editMeta {it.displayName(net.kyori.adventure.text.Component.text("renamed"));it.lore(emptyList())}
            check(CombatItems.id(copy)==item.id)
            val definition=item.definition!!
                check(CustomItems.id(copy)==definition.id)
                if(definition.model!=null)check(if(CustomItems.customModelsEnabled) copy.itemMeta.itemModel.toString()==definition.model else copy.itemMeta.itemModel==null)
                if(definition.kind==ItemKind.ARMOR) {
                    check(copy.itemMeta.equippable.model==null && definition.equipmentModel==null)
                    check(copy.type.name==definition.material && definition.armorCompensation>=0.0)
                }
            copy.editMeta {meta->for(key in listOf("item_id","item_schema","item_appearance"))meta.persistentDataContainer.remove(NamespacedKey("one-block",key))}
            check(CustomItems.id(copy)==definition.id && CustomItems.refresh(copy))
            check(!CustomItems.refresh(copy) && CustomItems.id(ItemStack.deserializeBytes(copy.serializeAsBytes()))==definition.id)
        }
        for(definition in rules().items.values.mapNotNull {it.definition}.filter {it.kind==ItemKind.ARMOR && it.migrationFrom.isNotEmpty()}) {
            val legacy=ItemStack(Material.valueOf(definition.migrationFrom.single()))
            val foreign=NamespacedKey("smoke","migration_foreign")
            legacy.editMeta {meta->
                meta.persistentDataContainer.set(NamespacedKey("one-block","combat_item"),org.bukkit.persistence.PersistentDataType.STRING,definition.legacyId)
                meta.persistentDataContainer.set(NamespacedKey("one-block","combat_schema"),org.bukkit.persistence.PersistentDataType.INTEGER,1)
                meta.persistentDataContainer.set(foreign,org.bukkit.persistence.PersistentDataType.INTEGER,17)
                (meta as? org.bukkit.inventory.meta.Damageable)?.damage=20
            }
            legacy.addUnsafeEnchantment(org.bukkit.enchantments.Enchantment.UNBREAKING,1)
            check(CustomItems.refresh(legacy))
            check(legacy.type.name==definition.material && CustomItems.id(legacy)==definition.id && legacy.amount==1)
            check(legacy.itemMeta.persistentDataContainer.get(foreign,org.bukkit.persistence.PersistentDataType.INTEGER)==17)
            check(legacy.getEnchantmentLevel(org.bukkit.enchantments.Enchantment.UNBREAKING)==1)
            val serialized=ItemStack.deserializeBytes(legacy.serializeAsBytes())
            check(CustomItems.id(serialized)==definition.id && serialized.type==legacy.type)
        }
        stage("legacy armor carrier migration with enchantments, foreign PDC and serialization PASS")
        val forged=ItemStack(Material.PRISMARINE_SHARD).apply {editMeta {it.displayName(net.kyori.adventure.text.Component.text("Бойовий фрагмент"))}}
        check(runCatching {ReleaseInventoryPlan.remove(arrayOf(forged),listOf(Ingredient(setOf("PRISMARINE_SHARD"),1,"battle_fragment")))}.isFailure)
        p.gameMode=GameMode.SURVIVAL;p.allowFlight=true;p.isFlying=true;p.inventory.heldItemSlot=0
        for(slot in 0..35)if(slot!=8)p.inventory.setItem(slot,ItemStack(Material.DIRT,64))
        p.inventory.setItem(0,ItemStack(Material.STICK,64));p.inventory.setItem(1,ItemStack(Material.NETHERITE_INGOT,64))
        p.inventory.setItem(2,CombatItems.create(rules().items.getValue("enchanted_fragment"),64))
        p.inventory.setItem(3,CombatItems.create(rules().items.getValue("tempered_core"),64))
        menu.show(p,"combat-weapons")
        val shown=menu.statistics.values(p,(menu.configurationIdentity as com.alytanta.oneblock.menu.MenuConfiguration).statistics).getValue("release_recipe_rift_staff")
        check(shown.contains("4 ×") && !shown.contains("{release_")) {"Combat recipe preview was not bound: $shown"}
        check(p.performCommand("obmenu combat-craft-rift_staff"))
        check(menu.currentMenuId(p)=="release-confirm")
        p.performCommand("obmenu release-confirm-action");p.performCommand("obmenu release-confirm-action")
        check(storage.marketState().transfers.values.any {it.release?.value=="rift_staff"}) {"Craft was not submitted: busy=${InventoryGuard.busy(p.uniqueId)}, canStart=${InventoryGuard.canStart(p.uniqueId)}, healthy=${storage.healthy}, account=${islands.findForPlayer(p.uniqueId)?.owner}, menu=${menu.currentMenuId(p)}, available=${ReleaseInventoryPlan.canRemove(p.inventory.storageContents,islands.releaseRules!!.recipes.getValue("rift_staff").ingredients)}"}
        i.owner
    }.thenCompose { account -> await({
        val transfers=storage.marketState().transfers.values.filter {it.release?.value=="rift_staff"}
        check(transfers.none {it.status in setOf(TransferStatus.ABORTED,TransferStatus.REVIEW)}) {"Craft transfer stopped: ${transfers.map {it.status}}"}
        !InventoryGuard.busy(p.uniqueId) && transfers.any {it.status==TransferStatus.DONE}
    }).thenCompose {
        entity(p) {
            check(p.inventory.getItem(0)!!.amount==62 && p.inventory.getItem(1)!!.amount==63 && p.inventory.getItem(2)!!.amount==56 && p.inventory.getItem(3)!!.amount==60)
            check(storage.marketState().transfers.values.count {it.release?.value=="rift_staff"}==1)
            val claim=storage.marketState().claims.values.single {it.source.startsWith("release:")}
            check(CombatItems.id(AuctionItems.decode(claim.itemData!!))=="rift_staff")
            menu.show(p,"release-claims");check(p.performCommand("obmenu claims-all"))
            claim.id
        }.thenCompose { claimId ->
            val rewards=HandlerList.getRegisteredListeners(production).map {it.listener}.filterIsInstance<com.alytanta.oneblock.rewards.RewardMenus>().first()
            await({!rewards.collection.busy(p.uniqueId) && !InventoryGuard.busy(p.uniqueId)}).thenCompose {entity(p) {
                check(claimId in storage.marketState().claims) {"Full bags lost crafted staff"}
                p.inventory.setItem(4,null);menu.show(p,"release-claims")
                check(p.performCommand("obmenu claims-all"));p.performCommand("obmenu claims-all");Unit
            }}.thenCompose {await({!rewards.collection.busy(p.uniqueId) && !InventoryGuard.busy(p.uniqueId) && claimId !in storage.marketState().claims})}
        }.thenCompose {
            entity(p) {check(p.inventory.storageContents.filterNotNull().count {CombatItems.id(it)=="rift_staff"}==1);p.saveData()}
        }.thenCompose { CompletableFuture.allOf(*(1..16).map {production.combatRewards!!.award(request(account,p))}.toTypedArray()) }.thenCompose {
            check(storage.marketState().combatReceipts.size==1)
            storage.flushAsync()
        }.thenCompose {CompletableFuture.supplyAsync {
            val export=storage.backup();export.copyTo(dataFolder.resolve("phase1.backup.sql"),true);Unit
        } }
    } }
    private fun second(p:Player):CompletableFuture<Unit> {
        stage("login inventory recovery and claim collection")
        val trades=field(production,"trades")!!
        @Suppress("UNCHECKED_CAST") val collecting=field(trades,"collecting") as Set<UUID>
        return await({!InventoryGuard.busy(p.uniqueId) && p.uniqueId !in collecting}).thenCompose {entity(p) {
        stage("restart inventory and receipt")
        val account=islands.findForPlayer(p.uniqueId)!!.owner
        check(p.inventory.storageContents.filterNotNull().count {CombatItems.id(it)=="rift_staff"}==1) {"Exact staff lost or duplicated on restart"}
        check(storage.marketState().combatReceipts.size==1) {"Receipts after restart: ${storage.marketState().combatReceipts.size}"}
        account to storage.marketState().claims.size
    } }.thenCompose { (account,count) -> production.combatRewards!!.award(request(account,p)).thenCompose {
        check(storage.marketState().claims.size==count) {"Replay changed claims: $count -> ${storage.marketState().claims.size}"}
        delayEntity(p,25) {p.teleportAsync(Location(p.world,2.5,200.0,2.5,0f,0f))}.thenCompose {it}.thenCompose {ok->check(ok);delayEntity(p,25) {Unit}}.thenCompose {entity(p) {combat(p)}}.thenCompose {it}
    }.thenCompose {await({hits.get()>0})}.thenCompose {
        entity(p) {check(kotlin.math.abs(magicDamage-rules().items.getValue("rift_staff").damage*1.04)<0.0001) {"Native magic damage was $magicDamage"};check(launches.get()>=1);Unit}.thenCompose {demoBattle(p)}.thenCompose {techniqueCases(p)}.thenCompose {windPvp(p)}.thenCompose {anvilCase(p)}.thenCompose {nativeBalance(p)}.thenCompose {configuredReload(p)}
    } }
    }
    private fun <T> delayEntity(p:Player,ticks:Long,action:()->T)=CompletableFuture<T>().also {done->
        p.scheduler.runDelayed(this,{try {done.complete(action())}catch(e:Throwable){done.completeExceptionally(e)}},{done.completeExceptionally(IllegalStateException("retired"))},ticks)
    }
    private fun techniqueCases(p:Player):CompletableFuture<Unit> {
        stage("all weapon mechanics, enchantments, offhand, multishot and boss control")
        val r=rules();lateinit var target:Zombie;lateinit var boss:Zombie;var sparkBefore=0.0;var finalSpellHealth=0.0
        lateinit var saved:ItemStack;lateinit var armor:Array<ItemStack?>
        fun dummy(x:Double,z:Double)=p.world.spawn(Location(p.world,x,200.0,z),Zombie::class.java).apply {
            setAI(false);setGravity(false);setShouldBurnInDay(false);isSilent=true
            fireTicks=0;scoreboardTags.add("combat-test-dummy")
            getAttribute(org.bukkit.attribute.Attribute.KNOCKBACK_RESISTANCE)!!.baseValue=1.0
            getAttribute(org.bukkit.attribute.Attribute.MAX_HEALTH)!!.baseValue=500.0;health=500.0
            getAttribute(org.bukkit.attribute.Attribute.ARMOR)!!.baseValue=0.0
        }
        fun use(id:String,hand:EquipmentSlot=EquipmentSlot.HAND) {
            val stack=CombatItems.create(r.items.getValue(id))
            if(hand==EquipmentSlot.HAND)p.inventory.setItemInMainHand(stack) else p.inventory.setItemInOffHand(stack)
            PlayerInteractEvent(p,Action.RIGHT_CLICK_AIR,stack,null,org.bukkit.block.BlockFace.SELF,hand).callEvent()
        }
        fun shoot(id:String,victim:Zombie,force:Float=1f,hand:EquipmentSlot=EquipmentSlot.HAND):Double {
            val arrow=p.launchProjectile(Arrow::class.java,org.bukkit.util.Vector(0,0,0));arrow.pickupStatus=AbstractArrow.PickupStatus.DISALLOWED
            val bow=CombatItems.create(r.items.getValue(id));bow.addUnsafeEnchantment(org.bukkit.enchantments.Enchantment.UNBREAKING,3)
            val event=EntityShootBowEvent(p,bow,ItemStack(Material.ARROW),arrow,hand,force,true);event.callEvent();event.callEvent()
            victim.noDamageTicks=0;victim.damage(4.0,org.bukkit.damage.DamageSource.builder(org.bukkit.damage.DamageType.ARROW).withDirectEntity(arrow).withCausingEntity(p).build())
            val amount=(victim.lastDamageCause as EntityDamageByEntityEvent).damage;arrow.remove()
            check(bow.getEnchantmentLevel(org.bukkit.enchantments.Enchantment.UNBREAKING)==3)
            return amount
        }
        return entity(p) {p.teleportAsync(Location(p.world,2.5,200.0,2.5,0f,0f))}.thenCompose {it}.thenCompose {ok->check(ok);delayEntity(p,40) {Unit}}.thenCompose {entity(p) {
            saved=p.inventory.itemInMainHand.clone();armor=p.inventory.armorContents;p.inventory.armorContents=arrayOfNulls(4)
            check(!(production.combatStats as CombatRuntime).techniques.owned(Location(p.world,100000.0,200.0,100000.0),4.0)) {"Remote region was accepted"}
            target=dummy(2.5,5.0);boss=dummy(4.0,5.0);boss.scoreboardTags.add("oneblock:control_resistant")
            p.inventory.setItemInMainHand(CombatItems.create(r.items.getValue("nether_edge")))
            fun strike():Double {target.noDamageTicks=0;target.damage(8.0,p);return (target.lastDamageCause as EntityDamageByEntityEvent).damage}
            val first=strike();val second=strike();val third=strike()
            check(kotlin.math.abs(second-first*2)<.001 && kotlin.math.abs(third-first)<.001) {"Precise slash sequence failed: $first/$second/$third"}
            val duplicate=target.lastDamageCause as EntityDamageByEntityEvent;duplicate.callEvent()
            check(kotlin.math.abs(strike()-first*2)<.001) {"Repeated event advanced precision sequence"}
            shoot("wind_bow",target,.5f);check(!target.hasPotionEffect(org.bukkit.potion.PotionEffectType.SLOWNESS))
            target.scoreboardTags.add("guard-cancel");shoot("wind_bow",target);target.scoreboardTags.remove("guard-cancel")
            check(!target.hasPotionEffect(org.bukkit.potion.PotionEffectType.SLOWNESS)) {"Cancelled shot applied control"}
            shoot("wind_bow",target);check(target.hasPotionEffect(org.bukkit.potion.PotionEffectType.SLOWNESS))
            shoot("wind_bow",boss);check(!boss.hasPotionEffect(org.bukkit.potion.PotionEffectType.SLOWNESS))
            p.inventory.armorContents=listOf("boots","leggings","chestplate","helmet").map {CombatItems.create(r.items.getValue("ranger_$it"))}.toTypedArray()
            val focus=(1..10).map {shoot("ranger_bow",target)}
            check(focus.last()>focus.first() && focus.last()<=focus.first()*1.14001) {"Ranged focus cap failed: $focus"}
            val reset=shoot("ranger_bow",boss);check(kotlin.math.abs(reset-focus.first())<.001) {"Target switch kept focus"}
            check(shoot("ranger_bow",boss,1f,EquipmentSlot.OFF_HAND)>reset) {"Offhand bow lost configured effects"}
            p.inventory.armorContents=arrayOfNulls(4)
            val before=target.health;use("rift_cleaver");check(target.health==before) {"Bow-to-cleave swap bypassed action recovery"}
            @Suppress("UNCHECKED_CAST") val recoveries=field(production.combatStats as CombatRuntime,"recoveries") as MutableMap<Any,Any>
            recoveries.clear() // separate mechanics case after the explicit swap denial assertion
            use("rift_cleaver");val after=target.health;use("rift_cleaver")
            check(after<before && target.health==after) {"Cleave/cooldown failed"}
            val protection=CombatItemProtection {r}
            val helmet=CombatItems.create(r.items.getValue("warden_helmet"));val foreign=NamespacedKey("smoke","book_foreign")
            helmet.editMeta {it.persistentDataContainer.set(foreign,org.bukkit.persistence.PersistentDataType.INTEGER,7)}
            val book=ItemStack(Material.ENCHANTED_BOOK).apply {editMeta { (it as org.bukkit.inventory.meta.EnchantmentStorageMeta).addStoredEnchant(org.bukkit.enchantments.Enchantment.PROTECTION,4,true)}}
            val result=protection.headBook(helmet,book)!!
            check(result.second==8 && result.first.getEnchantmentLevel(org.bukkit.enchantments.Enchantment.PROTECTION)==4 && CombatItems.id(result.first)=="warden_helmet")
            check(result.first.itemMeta.persistentDataContainer.get(foreign,org.bukkit.persistence.PersistentDataType.INTEGER)==7)
            check(helmet.getEnchantmentLevel(org.bukkit.enchantments.Enchantment.PROTECTION)==0 && book.amount==1)
            val hp=target.health;use("rift_staff",EquipmentSlot.OFF_HAND);check(target.health==hp)
        }}.thenCompose {delayEntity(p,30) {
            target.noDamageTicks=0;boss.noDamageTicks=0
            target.removePotionEffect(org.bukkit.potion.PotionEffectType.SLOWNESS);target.scoreboardTags.add("guard-cancel")
            val hp=target.health;use("frost_wand");check(target.health==hp && !target.hasPotionEffect(org.bukkit.potion.PotionEffectType.SLOWNESS)) {"Protected target received frost damage/control"}
        }}.thenCompose {delayEntity(p,45) {
            check(!target.hasPotionEffect(org.bukkit.potion.PotionEffectType.SLOWNESS)) {"Protected target received delayed frost control"}
            target.scoreboardTags.remove("guard-cancel")
            target.noDamageTicks=0;boss.noDamageTicks=0
            use("frost_wand")
        }}.thenCompose {delayEntity(p,8) {
            check(target.hasPotionEffect(org.bukkit.potion.PotionEffectType.SLOWNESS) && !boss.hasPotionEffect(org.bukkit.potion.PotionEffectType.SLOWNESS))
        }}.thenCompose {delayEntity(p,45) {
            target.removePotionEffect(org.bukkit.potion.PotionEffectType.SLOWNESS);target.noDamageTicks=0
            target.fireTicks=0;sparkBefore=target.health;use("apprentice_wand")
        }}.thenCompose {delayEntity(p,8) {
            val hp=target.health;check(hp<sparkBefore) {"Spark stream did not hit"}
            p.inventory.setItemInMainHand(ItemStack(Material.STICK));hp
        }}.thenCompose {hp->delayEntity(p,32) {
            check(target.health==hp) {"Spark channel after switch: health $hp -> ${target.health}; held=${p.inventory.itemInMainHand.type}; last=${target.lastDamageCause?.cause}; channels=${field((production.combatStats as CombatRuntime).techniques,"channels")}"}
            target.noDamageTicks=0
            repeat(3) {shoot("ember_crossbow",target)}
            target.health
        }}.thenCompose {hp->delayEntity(p,30) {
            check(target.health<hp) {"Ember mark did not detonate"}
            val marks=field((production.combatStats as CombatRuntime).techniques,"marks") as Map<*,*>
            check(marks.keys.count {it==(p.uniqueId to target.uniqueId)}==1) {"Multishot duplicated mark"}
            val runtime=production.combatStats as CombatRuntime
            val mode=p.gameMode;p.gameMode=org.bukkit.GameMode.SURVIVAL;p.inventory.setItem(20,ItemStack(Material.ARROW,8))
            val shortbowArrows={p.inventory.storageContents.filterNotNull().filter {it.type==Material.ARROW}.sumOf {it.amount}}
            val beforeShort=shortbowArrows();use("ember_shortbow");check(shortbowArrows()==beforeShort-1) {"Shortbow did not fire: $beforeShort -> ${shortbowArrows()}"}
            use("ember_shortbow");check(shortbowArrows()==beforeShort-1) {"Shortbow ignored its cooldown"}
            logger.info("Combat smoke: instant shortbow fired once and respected cooldown PASS")
            p.inventory.setItem(20,null);p.gameMode=mode;(field(runtime,"recoveries") as MutableMap<*,*>).clear();(field(runtime,"cooldowns") as MutableMap<*,*>).clear()
            @Suppress("UNCHECKED_CAST") val pools=field(runtime,"pools") as Map<Any,ManaPool>
            val entry=pools.entries.single {field(it.key,"player")==p.uniqueId && field(it.key,"account")==islands.findForPlayer(p.uniqueId)!!.owner}
            @Suppress("UNCHECKED_CAST") val counts=field(runtime,"spellCounts") as MutableMap<Any,Int>
            counts.remove(entry.key)
            p.inventory.armorContents=listOf("boots","leggings","chestplate","helmet").map {CombatItems.create(r.items.getValue("weaver_$it"))}.toTypedArray()
            entry.value.value=50.0
            repeat(3) {runtime.spellFinished(p,r,islands.findForPlayer(p.uniqueId)!!.owner,10.0)}
            check(entry.value.value==50.0)
            runtime.spellFinished(p,r,islands.findForPlayer(p.uniqueId)!!.owner,10.0);check(entry.value.value==52.0)
            p.inventory.setHelmet(null);repeat(4) {runtime.spellFinished(p,r,islands.findForPlayer(p.uniqueId)!!.owner,10.0)};check(entry.value.value==52.0) {"Incomplete set granted flow"}
            counts.remove(entry.key);entry.value.value=0.0;entry.value.time=System.nanoTime()
            val before=target.health;val windBefore=windLaunches.get();use("rift_staff");check(target.health==before && windLaunches.get()==windBefore) {"Spell cast without mana"}
            entry.value.value=100.0;entry.value.time=System.nanoTime();target.noDamageTicks=0;use("rift_staff")
            finalSpellHealth=before
            val spent=entry.value.value;val after=target.health;val expected=100-r.items.getValue("rift_staff").mana;check(kotlin.math.abs(spent-expected)<.1 && windLaunches.get()==windBefore+1) {"Wrong mana spend: $spent"}
            use("rift_staff");check(entry.value.value==spent && target.health==after) {"Duplicate cast spent mana or dealt damage"}
            Unit
        }}.thenCompose {delayEntity(p,8) {
            check(target.health<finalSpellHealth) {"Wind spell did not deal delayed impact damage"}
            target.remove();boss.remove();p.inventory.armorContents=armor;p.inventory.setItemInMainHand(saved);p.inventory.setItemInOffHand(null)
            stage("all weapons/enchantment/offhand/control/sequence/channel/multishot PASS");Unit
        }}
    }
    private fun windPvp(p:Player):CompletableFuture<Unit> {
        lateinit var peer:Player;lateinit var world:World
        val hub=field(production,"activeHub") as com.alytanta.oneblock.hub.HubService
        val hubRules=field(production,"hubRules") as com.alytanta.oneblock.hub.HubRulesListener
        val oldHub=hub.currentSettings();val oldRules=hubRules.settings;var oldPvp=true
        var before=0;var hp=0.0;var cancellations=0;lateinit var oldItem:ItemStack
        fun cast() {p.inventory.setItemInMainHand(CombatItems.create(rules().items.getValue("rift_staff")));nativeUse(p)}
        fun peerHealth()=entity(peer) {peer.health}
        return await({server.getPlayerExact("CombatPeer")!=null}).thenCompose {entity(p) {
            stage("real right click, empty air, lobby creative, PvP gates and no vanilla wind burst")
            peer=server.getPlayerExact("CombatPeer")!!;world=p.world;oldItem=p.inventory.itemInMainHand.clone()
            p.teleportAsync(Location(world,2.5,200.0,2.5,90f,0f))
        }}.thenCompose {it}.thenCompose {entity(peer) {
            peer.closeInventory();peer.gameMode=GameMode.SURVIVAL;peer.inventory.armorContents=arrayOfNulls(4)
            peer.foodLevel=18;peer.saturation=0f
            peer.getAttribute(org.bukkit.attribute.Attribute.MAX_HEALTH)!!.baseValue=200.0;peer.health=200.0;peer.velocity=org.bukkit.util.Vector()
            peer.teleportAsync(Location(world,2.5,200.0,7.5))
        }}.thenCompose {it}.thenCompose {global {
            oldPvp=world.pvp;world.pvp=true
            hub.applySettings(oldHub.copy(world=world.key().asString()))
            hubRules.settings=oldRules.copy(pvp=false)
        }}.thenCompose {delayEntity(p,35) {
            p.closeInventory();p.gameMode=GameMode.CREATIVE;p.allowFlight=true;p.isFlying=true
            before=windLaunches.get();p.scoreboardTags.add("deny-wind-use");cast();p.scoreboardTags.remove("deny-wind-use")
            check(windLaunches.get()==before) {"Explicit interaction veto was bypassed"}
            cast();check(windLaunches.get()==before+1) {"Native right click with inert staff did not launch in creative lobby"}
            val door=world.getBlockAt(2,201,3);door.setType(Material.OAK_TRAPDOOR,false)
            lastWind!!.explode()
            check(!(door.blockData as org.bukkit.block.data.Openable).isOpen) {"Native wind burst toggled a block"}
        }}.thenCompose {delayEntity(p,35) {p.setRotation(0f,0f);cast()}}
            .thenCompose {delayEntity(p,8) {Unit}}.thenCompose {peerHealth()}.thenCompose {health->
                check(health==200.0) {"Protected lobby player took damage: $health"}
                global {hubRules.settings=oldRules.copy(pvp=true);world.pvp=false}
            }.thenCompose {delayEntity(p,30) {cast()}}.thenCompose {delayEntity(p,8) {Unit}}.thenCompose {peerHealth()}.thenCompose {health->
                check(health==200.0) {"World PvP=false was bypassed: $health"};global {world.pvp=true}
            }.thenCompose {delayEntity(p,30) {cast()}}.thenCompose {waitEntity(peer,60) {peer.health<200.0}}.thenCompose {peerHealth()}.thenCompose {health->
                check(health<200.0 && health>150) {"PvP-on impact was missing or duplicated: $health"};hp=health;cancellations=protectionCancels.get()
                entity(peer) {peer.scoreboardTags.add("guard-cancel");peer.noDamageTicks=0;peer.teleportAsync(Location(world,2.5,200.0,7.5))}
            }.thenCompose {it}.thenCompose {delayEntity(p,30) {cast()}}.thenCompose {delayEntity(p,8) {Unit}}.thenCompose {peerHealth()}.thenCompose {health->
                check(health==hp && protectionCancels.get()>cancellations) {"Protection cancellation failed: hp=$hp->$health, cancellations=$cancellations->${protectionCancels.get()}"}
                entity(peer) {
                    peer.scoreboardTags.remove("guard-cancel");peer.getAttribute(org.bukkit.attribute.Attribute.MAX_HEALTH)!!.baseValue=20.0;peer.health=20.0
                    check(islands.findForPlayer(peer.uniqueId)==null) {"Guest fixture unexpectedly owns a profile"}
                    peer.gameMode=GameMode.CREATIVE;peer.setRotation(90f,0f)
                    peer.inventory.setItemInMainHand(CombatItems.create(rules().items.getValue("rift_staff")))
                    before=windLaunches.get();nativeUse(peer);check(windLaunches.get()==before+1) {"Profileless lobby player could not cast"}
                    peer.inventory.setItemInMainHand(ItemStack(Material.STICK));Unit
                }
            }.thenCompose {global {world.pvp=oldPvp;hub.applySettings(oldHub);hubRules.settings=oldRules}}
            .thenCompose {entity(p) {
                p.gameMode=GameMode.SURVIVAL;p.allowFlight=true;p.isFlying=true;p.inventory.setItemInMainHand(oldItem)
                world.getBlockAt(2,201,3).setType(Material.AIR,false)
                stage("native right-click/lobby/creative/guest/PvP-on-off/cancellation/no-block-burst PASS");Unit
            }}
    }
    private fun anvilCase(p:Player):CompletableFuture<Unit> {
        stage("native helmet book enchantment, XP/input consumption and repeated pickup")
        var oldLevel=0;lateinit var view:org.bukkit.inventory.InventoryView
        return entity(p) {
            oldLevel=p.level;p.level=30;p.setItemOnCursor(null)
            view=p.openAnvil(p.location,true)?:error("Could not open native anvil")
            view.topInventory.setItem(0,CombatItems.create(rules().items.getValue("warden_helmet")))
            view.topInventory.setItem(1,ItemStack(Material.ENCHANTED_BOOK).apply {editMeta {(it as org.bukkit.inventory.meta.EnchantmentStorageMeta).addStoredEnchant(org.bukkit.enchantments.Enchantment.PROTECTION,4,true)}})
        }.thenCompose {delayEntity(p,3) {
            check(CombatItems.id(view.topInventory.getItem(2))=="warden_helmet") {"Native anvil did not prepare helmet"}
            val handle=p.javaClass.getMethod("getHandle").invoke(p);val container=handle.javaClass.getField("containerMenu").get(handle)
            val clicked=container.javaClass.methods.single {it.name=="clicked" && it.parameterCount==4}
            val pick=clicked.parameterTypes[2].enumConstants.first {it.toString()=="PICKUP"}
            clicked.invoke(container,2,0,pick,handle)
            check(p.level==22 && view.topInventory.getItem(0)==null && view.topInventory.getItem(1)==null) {"Anvil did not consume XP and inputs: level=${p.level}"}
            check(CombatItems.id(p.itemOnCursor)=="warden_helmet" && p.itemOnCursor.getEnchantmentLevel(org.bukkit.enchantments.Enchantment.PROTECTION)==4)
            clicked.invoke(container,2,0,pick,handle)
            check(p.level==22 && p.itemOnCursor.amount==1) {"Repeated native pickup duplicated helmet or XP charge"}
            p.setItemOnCursor(null);p.closeInventory();p.level=oldLevel
            stage("native anvil enchantment payment/pickup PASS");Unit
        }}
    }
    private fun nativeBalance(p:Player):CompletableFuture<Unit> {
        stage("native enchanted vanilla comparison")
        val report=linkedMapOf<String,Any>();lateinit var mob:Zombie;lateinit var saved:ItemStack;lateinit var savedArmor:Array<ItemStack?>
        val survival=linkedMapOf<String,List<Map<String,Double>>>()
        fun equip(set:String) {
            p.inventory.armorContents=listOf("boots","leggings","chestplate","helmet").map {part->
                (if(set in setOf("vanilla","netherite"))ItemStack(Material.valueOf("${if(set=="netherite")"NETHERITE" else "DIAMOND"}_${part.uppercase()}")) else CombatItems.create(rules().items.getValue("${set}_$part")))
                    .apply {addUnsafeEnchantment(org.bukkit.enchantments.Enchantment.PROTECTION,4);addUnsafeEnchantment(org.bukkit.enchantments.Enchantment.UNBREAKING,3)}
            }.toTypedArray()
        }
        fun prepare(name:String,bow:Boolean) {
            stage("native balance $name")
            equip(if(name.startsWith("vanilla"))"vanilla" else if(bow)"ranger" else "warden")
            val stack=when(name) {"vanilla_sword"->ItemStack(Material.NETHERITE_SWORD);"vanilla_bow"->ItemStack(Material.BOW);else->CombatItems.create(rules().items.getValue(if(bow)"ranger_bow" else "nether_edge"))}
            stack.addUnsafeEnchantment(if(bow)org.bukkit.enchantments.Enchantment.POWER else org.bukkit.enchantments.Enchantment.SHARPNESS,5)
            stack.addUnsafeEnchantment(org.bukkit.enchantments.Enchantment.UNBREAKING,3)
            p.inventory.setItemInMainHand(stack);p.inventory.setItem(10,ItemStack(Material.ARROW,64));balanceRows.clear()
        }
        fun samples(name:String,bow:Boolean,index:Int=0):CompletableFuture<Unit> {
            val count=if(name=="vanilla_bow")48 else 12
            if(index==count)return entity(p) {
                report[name]=balanceRows.map {mapOf("nativeBase" to it.first,"result" to it.second,"ratio" to it.second/it.first)}
                logger.info("Balance $name: base=${balanceRows.map {it.first}.average()}, result=${balanceRows.map {it.second}.average()}, ratio=${balanceRows.map {it.second/it.first}.average()}");Unit
            }.thenCompose {CompletableFuture.runAsync {dataFolder.resolve("native-balance-progress.json").writeText(com.google.gson.GsonBuilder().setPrettyPrinting().create().toJson(report))}.thenApply {Unit}}
            return delayEntity(p,if(bow)2 else 14) {
                mob.health=1000.0
                p.fallDistance=0f;p.isSprinting=false
                if(bow)p.startUsingItem(EquipmentSlot.HAND) else {p.attack(mob);mob.velocity=org.bukkit.util.Vector()}
            }.thenCompose {
                if(bow)delayEntity(p,22) {
                    val handle=p.javaClass.getMethod("getHandle").invoke(p)
                    handle.javaClass.getMethod("releaseUsingItem").invoke(handle);Unit
                } else CompletableFuture.completedFuture(Unit)
            }.thenCompose {waitEntity(p,40) {balanceRows.size>=index+1}}.thenCompose {samples(name,bow,index+1)}
        }
        fun incoming(set:String,index:Int=0,rows:List<Map<String,Double>> = emptyList()):CompletableFuture<Unit> {
            if(index==3){survival[set]=rows;return CompletableFuture.completedFuture(Unit)}
            return delayEntity(p,12) {
                val amount=listOf(10.0,20.0,40.0)[index]
                p.health=p.getAttribute(org.bukkit.attribute.Attribute.MAX_HEALTH)!!.value;p.noDamageTicks=0;p.lastDamage=0.0;p.damage(amount,mob)
                mapOf("incoming" to amount,"eventBase" to p.lastDamageCause!!.damage,"finalDamage" to p.lastDamageCause!!.finalDamage,
                    "armor" to p.getAttribute(org.bukkit.attribute.Attribute.ARMOR)!!.value,"toughness" to p.getAttribute(org.bukkit.attribute.Attribute.ARMOR_TOUGHNESS)!!.value,
                    "knockbackResistance" to p.getAttribute(org.bukkit.attribute.Attribute.KNOCKBACK_RESISTANCE)!!.value)
            }.thenCompose {row->incoming(set,index+1,rows+row)}
        }
        fun survivalCases(index:Int=0):CompletableFuture<Unit> {
            val sets=listOf("vanilla","netherite","warden","ranger","weaver")
            if(index==sets.size)return CompletableFuture.completedFuture(Unit)
            val set=sets[index]
            return entity(p) {equip(set);p.inventory.setItemInMainHand(ItemStack(Material.STICK));Unit}.thenCompose {incoming(set)}.thenCompose {survivalCases(index+1)}
        }
        return delayEntity(p,30) {saved=p.inventory.itemInMainHand.clone();savedArmor=p.inventory.armorContents;p.teleportAsync(Location(p.world,2.5,200.0,2.5,0f,0f))}.thenCompose {it}.thenCompose {ok->check(ok);entity(p) {
            p.isFlying=false
            mob=p.world.spawn(Location(p.world,2.5,200.0,5.0),Zombie::class.java) {it.setAI(false);it.setGravity(false);it.setShouldBurnInDay(false);it.getAttribute(org.bukkit.attribute.Attribute.MAX_HEALTH)!!.baseValue=1000.0;it.health=1000.0;it.getAttribute(org.bukkit.attribute.Attribute.ARMOR)!!.baseValue=0.0;it.getAttribute(org.bukkit.attribute.Attribute.KNOCKBACK_RESISTANCE)!!.baseValue=1.0}
            balanceTarget=mob.uniqueId;prepare("vanilla_sword",false)
        }}.thenCompose {samples("vanilla_sword",false)}.thenCompose {entity(p) {prepare("custom_sword",false)}}.thenCompose {samples("custom_sword",false)}
            .thenCompose {entity(p) {prepare("vanilla_bow",true)}}.thenCompose {samples("vanilla_bow",true)}
            .thenCompose {entity(p) {prepare("custom_bow",true)}}.thenCompose {samples("custom_bow",true)}
            .thenCompose {survivalCases()}
            .thenCompose {entity(p) {
                check(survival.getValue("netherite").all {it.getValue("toughness")==12.0}) {"Netherite toughness not applied: $survival"}
                check(listOf("warden","ranger","weaver").all {set->survival.getValue(set).all {it.getValue("armor")==24.0 && it.getValue("toughness")==16.0}}) {"Custom armor attributes must beat netherite: $survival"}
                check(survival.getValue("netherite").last().getValue("finalDamage")<survival.getValue("vanilla").last().getValue("finalDamage")) {"Native heavy-hit netherite benefit missing: $survival"}
                report["survival_after_armor_protection_iv"]=survival
                report["limits"]="48 native vanilla bow shots, 12 attacks for other weapons; native Sharpness V and Power V, Protection IV diamond baseline; no human playtest. Native RNG preserved. Ratios compare each event before/after custom modifiers."
                mob.remove();balanceTarget=null;p.inventory.armorContents=savedArmor;p.inventory.setItemInMainHand(saved);p.health=p.getAttribute(org.bukkit.attribute.Attribute.MAX_HEALTH)!!.value;p.isFlying=true
            }}.thenCompose {CompletableFuture.runAsync {dataFolder.resolve("native-balance.json").writeText(com.google.gson.GsonBuilder().setPrettyPrinting().create().toJson(report))}.thenApply {stage("native enchanted vanilla comparison PASS");Unit}}
    }
    private fun configuredReload(p:Player):CompletableFuture<Unit> {
        val gate=field(production,"packGate") as ResourcePackGate
        if(!gate.admission.settings.enabled) {
            val reloading=field(production,"reloadInProgress") as java.util.concurrent.atomic.AtomicBoolean
            fun reload()=entity(p) {check(p.performCommand("obadmin reload"));check(reloading.get());Unit}.thenCompose {await({!reloading.get()})}
            return entity(p) {p.inventory.storageContents.filterNotNull().count {CombatItems.id(it)=="rift_staff"}}.thenCompose {before->
                check(before>0)
                reload().thenCompose {reload()}.thenCompose {entity(p) {
                check(gate.allowed(p.uniqueId) && gate.admission.current(p.uniqueId)==null)
                check(p.inventory.storageContents.filterNotNull().count {CombatItems.id(it)=="rift_staff"}==before)
                stage("two reloads with disabled resource pack and preserved inventory PASS");Unit
            }}}
        }
        val source=production.dataFolder.resolve("items.yml")
        val previous=java.util.concurrent.atomic.AtomicReference<String>()
        val before=islands.releaseRules
        val request=gate.admission.current(p.uniqueId)!!.id
        val reloading=field(production,"reloadInProgress") as java.util.concurrent.atomic.AtomicBoolean
        fun reload()=entity(p) {check(p.performCommand("obadmin reload"));check(reloading.get());Unit}.thenCompose {await({!reloading.get()})}
        stage("invalid catalog reload retains entire previous snapshot")
        return CompletableFuture.runAsync {
            previous.set(source.readText());val y=org.bukkit.configuration.file.YamlConfiguration().apply {loadFromString(previous.get())}
            y.set("items.oneblock:warden_blade.stats.damage",Double.NaN);y.save(source)
        }.thenCompose {reload()}.thenCompose {entity(p) {check(islands.releaseRules===before && gate.admission.current(p.uniqueId)!!.id==request)}}
            .thenCompose {CompletableFuture.runAsync {
                val y=org.bukkit.configuration.file.YamlConfiguration().apply {loadFromString(previous.get())}
                val path="items.oneblock:warden_blade"
                y.set("$path.stats.mana-cost",1.0);y.set("$path.stats.cooldown-seconds",2.0);y.set("$path.stats.projectile-speed",2.5)
                y.set("$path.abilities",y.getMapList("$path.abilities")+mapOf("ability" to "oneblock:bolt","trigger" to "USE","hand" to "OFF_HAND","cooldown-group" to "native-example"))
                y.save(source)
                val pack=production.dataFolder.resolve("resource-pack.yml");val config=org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(pack)
                config.set("url",config.getString("url")+"?revision=2");config.save(pack)
            }}.thenCompose {reload()}.thenCompose {await({gate.allowed(p.uniqueId) && gate.admission.current(p.uniqueId)!!.id!=request})}
            .thenCompose {entity(p) {
                check(islands.releaseRules!==before)
                p.inventory.setItemInOffHand(CombatItems.create(rules().items.getValue("warden_blade")))
                val count=launches.get()
                repeat(2) {PlayerInteractEvent(p,Action.RIGHT_CLICK_AIR,p.inventory.itemInOffHand,null,org.bukkit.block.BlockFace.SELF,EquipmentSlot.OFF_HAND).callEvent()}
                check(launches.get()==count+1) {"Configured OFF_HAND ability or duplicate cooldown failed"}
                // Same item in MAIN_HAND is ineligible for this configured binding.
                p.inventory.setItemInMainHand(p.inventory.itemInOffHand.clone())
                PlayerInteractEvent(p,Action.RIGHT_CLICK_AIR,p.inventory.itemInMainHand,null,org.bukkit.block.BlockFace.SELF,EquipmentSlot.HAND).callEvent()
                check(launches.get()==count+1)
            }}.thenCompose {
                val peer=server.getPlayerExact("ItemPeer")!!
                entity(peer) {
                    val i=islands.createProfile(peer.uniqueId,"Independent mana")!!;players.update(peer.uniqueId) {it.copy(activeAccount=i.owner)}
                    peer.gameMode=GameMode.SURVIVAL;peer.allowFlight=true;peer.isFlying=true
                    peer.inventory.setItemInOffHand(CombatItems.create(rules().items.getValue("warden_blade")))
                    val count=launches.get();PlayerInteractEvent(peer,Action.RIGHT_CLICK_AIR,peer.inventory.itemInOffHand,null,org.bukkit.block.BlockFace.SELF,EquipmentSlot.OFF_HAND).callEvent()
                    check(launches.get()==count+1) {"Another player's cooldown leaked to peer"}
                }
            }.thenApply {stage("actual reload, cached pack re-application, config-combined offhand ability, duplicate events and independent player cooldown PASS");Unit}
    }
    private fun combat(p:Player):CompletableFuture<Unit> {
        check(Bukkit.isOwnedByCurrentRegion(p))
        p.gameMode=GameMode.SURVIVAL;p.allowFlight=true;p.isFlying=true;p.inventory.heldItemSlot=0
        val r=rules();val world=p.world
        fun dummy(z:Double,x:Double=2.5):Zombie=world.spawn(Location(world,x,200.0,z),Zombie::class.java).apply {
            setAI(false);isSilent=true;setGravity(false);setShouldBurnInDay(false)
            getAttribute(org.bukkit.attribute.Attribute.MAX_HEALTH)!!.baseValue=100.0;health=100.0
            getAttribute(org.bukkit.attribute.Attribute.ARMOR)!!.baseValue=0.0
            noDamageTicks=0;isInvulnerable=false
        }
        val melee=dummy(3.5);p.inventory.setItemInMainHand(CombatItems.create(r.items.getValue("warden_blade")))
        stage("melee")
        val strength=p.attackCooldown.toDouble();melee.damage(8.0,p)
        check(kotlin.math.abs((100-melee.health)-CombatMath.melee(8.0,r.items.getValue("warden_blade").damage,strength))<0.001) {"Native melee modifier failed: ${100-melee.health}; guard=${InventoryGuard.busy(p.uniqueId)}"}
        fun incoming():Double {
            p.noDamageTicks=0;p.damage(4.0,melee)
            return (p.lastDamageCause as EntityDamageByEntityEvent).damage
        }
        val baseline=incoming();check(baseline>0)
        melee.scoreboardTags.add("guard-cancel")
        melee.noDamageTicks=0;melee.damage(1.0,p)
        melee.scoreboardTags.remove("guard-cancel")
        melee.noDamageTicks=0;melee.damage(1.0,p)
        val beforeGuard=incoming()
        check(kotlin.math.abs(beforeGuard-baseline)<0.001) {"Cancelled hit incorrectly charged stance: $baseline -> $beforeGuard"}
        melee.noDamageTicks=0;melee.damage(1.0,p)
        val guarded=incoming()
        val reduction=r.items.getValue("warden_blade").definition!!.effects("charged_guard").single().number("reduction")
        check(kotlin.math.abs(guarded-baseline*(1-reduction))<0.001) {"Charged guard did not mitigate native mob damage: $baseline -> $guarded"}
        val blade=p.inventory.itemInMainHand.clone();p.inventory.setItemInMainHand(ItemStack(Material.STICK))
        check(kotlin.math.abs(incoming()-baseline)<0.001) {"Stance applied after switching weapon"}
        p.inventory.setItemInMainHand(blade);p.health=p.getAttribute(org.bukkit.attribute.Attribute.MAX_HEALTH)!!.value
        melee.remove();stage("charged guard, cancelled hit and weapon switch PASS")
        val ranged=dummy(7.5);ranged.scoreboardTags.add("combat-ranged-target")
        stage("ranged")
        check(p.location.distanceSquared(Location(world,2.5,200.0,2.5))<.01) {"Ranged fixture moved before launch: ${p.location}"}
        val arrow=p.launchProjectile(Arrow::class.java,ranged.eyeLocation.toVector().subtract(p.eyeLocation.toVector()).normalize().multiply(2.0))
        arrow.isCritical=false;arrow.damage=4.0;arrow.pickupStatus=AbstractArrow.PickupStatus.DISALLOWED
        val bow=CombatItems.create(r.items.getValue("ember_crossbow"))
        EntityShootBowEvent(p,bow,ItemStack(Material.ARROW),arrow,EquipmentSlot.HAND,1f,true).callEvent()
        check(arrow.persistentDataContainer.get(NamespacedKey("one-block","combat_shot"),org.bukkit.persistence.PersistentDataType.STRING)=="ranged") {"Native bow event did not capture the weapon"}
        return await({rangedHits.get()>0}).thenCompose {entity(p) {
        check(kotlin.math.abs(rangedDamage-rangedBase*r.items.getValue("ember_crossbow").multiplier)<0.001) {"Native ranged modifier failed: $rangedBase -> $rangedDamage"};ranged.remove();arrow.remove()
        p.inventory.setHelmet(CombatItems.create(r.items.getValue("weaver_helmet")));p.inventory.setChestplate(CombatItems.create(r.items.getValue("weaver_chestplate")))
        p.inventory.setLeggings(CombatItems.create(r.items.getValue("weaver_leggings")));p.inventory.setBoots(CombatItems.create(r.items.getValue("weaver_boots")))
        val stats=CombatMath.stats(r,mapOf("HEAD" to "weaver_helmet","CHEST" to "weaver_chestplate","LEGS" to "weaver_leggings","FEET" to "weaver_boots"))
        check(stats.maxMana==120.0 && production.combatStats!!.snapshot(p)!!.copy(weapon=null)==stats)
        Unit
        }}.thenCompose {delayEntity(p,30) {Unit}}.thenCompose {magicCase(p)}
    }
    private fun magicCase(p:Player):CompletableFuture<Unit> {
        lateinit var target:Zombie;lateinit var chain:Zombie;lateinit var blocked:Zombie;var before=0
        fun cast()=PlayerInteractEvent(p,Action.RIGHT_CLICK_AIR,p.inventory.itemInMainHand,null,org.bukkit.block.BlockFace.SELF,EquipmentSlot.HAND).callEvent()
        return entity(p) {
            stage("native visible wind carrier: wall, attributed impact, chain LOS and cooldown")
            fun dummy(x:Double,z:Double)=p.world.spawn(Location(p.world,x,200.0,z),Zombie::class.java).apply {setAI(false);setGravity(false);setShouldBurnInDay(false);fireTicks=0;scoreboardTags.add("combat-test-dummy");getAttribute(org.bukkit.attribute.Attribute.KNOCKBACK_RESISTANCE)!!.baseValue=1.0;getAttribute(org.bukkit.attribute.Attribute.MAX_HEALTH)!!.baseValue=100.0;health=100.0;getAttribute(org.bukkit.attribute.Attribute.ARMOR)!!.baseValue=0.0}
            target=dummy(2.5,7.5);target.scoreboardTags.add("combat-magic-target");chain=dummy(1.5,8.0);blocked=dummy(4.5,8.0)
            for(y in 200..202)for(z in 6..10)p.world.getBlockAt(3,y,z).setType(Material.STONE,false)
            p.inventory.setItemInMainHand(CombatItems.create(rules().items.getValue("rift_staff")));p.setRotation(0f,0f)
            p.world.getBlockAt(2,201,5).setType(Material.STONE,false);before=hits.get();cast()
        }.thenCompose {delayEntity(p,30) {
            check(hits.get()==before && target.health==100.0) {"Wind wall: hits=$before -> ${hits.get()}, hp=${target.health}, cause=${target.lastDamageCause?.cause}, source=${(target.lastDamageCause as? EntityDamageByEntityEvent)?.damager?.type}"}
            check(p.world.getBlockAt(2,201,5).type==Material.STONE)
            p.world.getBlockAt(2,201,5).setType(Material.AIR,false)
            target.scoreboardTags.add("guard-cancel");check(!(production.combatStats as CombatRuntime).techniques.hurt(p,target,5.0));target.scoreboardTags.remove("guard-cancel")
            val count=windLaunches.get();cast();cast();check(windLaunches.get()==count+1) {"Duplicate wind launch"}
        }}.thenCompose {waitEntity(p) {hits.get()>before}}.thenCompose {entity(p) {
            check(hits.get()==before+1)
            check(kotlin.math.abs(chain.health-(100-rules().items.getValue("rift_staff").damage*1.04*.5))<.001 && blocked.health==100.0) {"Wind chain/wall: ${chain.health}, ${blocked.health}"}
            target.remove();chain.remove();blocked.remove();for(y in 200..202)for(z in 6..10)p.world.getBlockAt(3,y,z).setType(Material.AIR,false)
            Unit
        }}
    }
    @EventHandler(priority=EventPriority.MONITOR,ignoreCancelled=true) fun launched(e:ProjectileLaunchEvent) {
        if(e.entity.shooter is Player) {
            if(e.entity is WindCharge && WindSpells.marked(e.entity)) {check(e.entity.isVisibleByDefault);lastWind=e.entity as WindCharge;windLaunches.incrementAndGet()}
            if(e.entity.persistentDataContainer.get(NamespacedKey("one-block","combat_shot"),org.bukkit.persistence.PersistentDataType.STRING)=="magic")
                check(!e.entity.isVisibleByDefault) {"Magic projectile is visible by default"}
            launches.incrementAndGet()
        }
    }
    @EventHandler fun dummyCombust(e:EntityCombustEvent) {if("combat-test-dummy" in e.entity.scoreboardTags)e.isCancelled=true}
    @EventHandler(priority=EventPriority.HIGH) fun denyWindUse(e:PlayerInteractEvent) {if("deny-wind-use" in e.player.scoreboardTags)e.setUseItemInHand(Event.Result.DENY)}
    @EventHandler(priority=EventPriority.MONITOR) fun joined(e:org.bukkit.event.player.PlayerJoinEvent) {if(e.player.name=="HubSmokeBot")joined.set(true)}
    @EventHandler fun visual(e:org.bukkit.event.player.PlayerCommandPreprocessEvent) {
        if(!java.lang.Boolean.getBoolean("oneblock.combat-visual") || e.player.name!="VisualReview" || !e.message.startsWith("/vcombat"))return
        e.isCancelled=true;val p=e.player
        entity(p) {
            when(e.message.substringAfter("/vcombat").trim()) {
                "setup"->{
                    p.isOp=true
                    if(islands.findForPlayer(p.uniqueId)==null) {val island=islands.createProfile(p.uniqueId,"VisualDemo")!!;players.update(p.uniqueId) {it.copy(activeAccount=island.owner)}}
                    p.gameMode=GameMode.SURVIVAL;p.allowFlight=true;p.isFlying=true
                    p.teleportAsync(Location(server.getWorld(net.kyori.adventure.key.Key.key("minecraft:overworld")),2.5,200.0,2.5,0f,0f))
                    val ids=listOf("warden_blade","rift_staff","ember_crossbow","rift_cleaver","nether_edge","wind_bow","ranger_bow","apprentice_wand","frost_wand")
                    ids.forEachIndexed {i,id->p.inventory.setItem(if(i<8)i else 9,CombatItems.create(rules().items.getValue(id)))}
                    p.inventory.setItem(10,ItemStack(Material.ARROW,64))
                }
                "fight"->(field(production,"combatDemo") as CombatDemo).start(p)
                "stop"->(field(production,"combatDemo") as CombatDemo).stop(p.uniqueId)
                "heal"->{p.health=p.getAttribute(org.bukkit.attribute.Attribute.MAX_HEALTH)!!.value;p.foodLevel=20}
                else->p.sendMessage("/vcombat setup | fight | stop | heal")
            }
        }
    }
    @EventHandler(priority=EventPriority.MONITOR,ignoreCancelled=true) fun hit(e:EntityDamageByEntityEvent) {
        if(e.entity.uniqueId==balanceTarget)balanceRaw.remove(e)?.let {balanceRows.add(it to e.damage)}
        if("combat-magic-target" in e.entity.scoreboardTags) {
            if(e.damager is Arrow) {
                val projectile=e.damager as Arrow
                check(!projectile.isVisibleByDefault) {"Magic arrow was visible by default at impact"}
                check(projectile.shooter is Player) {"Magic hit lost player attribution"}
            } else check(e.damager is Player) {"Lightning hit lost player attribution"}
            magicDamage=e.damage;hits.incrementAndGet()
        }
        if("combat-ranged-target" in e.entity.scoreboardTags) {rangedDamage=e.damage;rangedHits.incrementAndGet()}
    }
    @EventHandler(priority=EventPriority.LOWEST,ignoreCancelled=true) fun base(e:EntityDamageByEntityEvent) {
        if(e.entity.uniqueId==balanceTarget)balanceRaw[e]=e.damage
        if("guard-cancel" in e.entity.scoreboardTags) {protectionCancels.incrementAndGet();e.isCancelled=true;return}
        if("combat-ranged-target" in e.entity.scoreboardTags)rangedBase=e.damage
    }
}
