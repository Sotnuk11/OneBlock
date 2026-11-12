package com.alytanta.oneblock.smoke

import com.alytanta.oneblock.OneBlock
import com.alytanta.oneblock.combat.*
import com.alytanta.oneblock.dungeon.*
import com.alytanta.oneblock.hub.HubSettings
import com.alytanta.oneblock.market.*
import com.alytanta.oneblock.release.ReleaseChange
import com.alytanta.oneblock.release.ReleaseProgress
import org.bukkit.*
import org.bukkit.block.BlockFace
import org.bukkit.entity.Player
import org.bukkit.event.*
import org.bukkit.event.block.*
import org.bukkit.event.player.*
import org.bukkit.event.entity.EntityDamageEvent
import org.bukkit.inventory.*
import org.bukkit.plugin.java.JavaPlugin
import java.util.UUID
import java.util.concurrent.*

/** Six real protocol clients. Trusted helper actions are explicitly synthetic, on owning schedulers. */
class FoliaMineSmokePlugin:JavaPlugin(),Listener {
    private lateinit var production:OneBlock
    private lateinit var mine:MineController
    private val joins=ConcurrentHashMap.newKeySet<UUID>()
    private val joinCounts=ConcurrentHashMap<UUID,java.util.concurrent.atomic.AtomicInteger>()
    @Volatile private var observing=false
    private val assertions=CopyOnWriteArrayList<String>()
    private val metrics=CopyOnWriteArrayList<Map<String,Any>>()
    @Volatile private var stage="boot"
    private val attacks=CopyOnWriteArrayList<Double>()
    private fun field(target:Any,name:String)=target.javaClass.getDeclaredField(name).apply {isAccessible=true}.get(target)
    private fun note(value:String) {assertions+=value;logger.info("Mine smoke: $value");stage=value}
    private fun await(test:()->Boolean):CompletableFuture<Unit> = CompletableFuture<Unit>().also {done->
        var left=4000
        server.globalRegionScheduler.runAtFixedRate(this,{task->
            try {if(done.isDone)task.cancel() else if(test()) {task.cancel();done.complete(Unit)} else if(--left==0) {task.cancel();done.completeExceptionally(IllegalStateException("Timeout: $stage"))}}
            catch(e:Throwable) {task.cancel();done.completeExceptionally(e)}
        },1,1)
    }
    private fun all(tasks:List<CompletableFuture<*>>)=CompletableFuture.allOf(*tasks.toTypedArray()).thenApply {Unit}
    private fun delay(ticks:Long)=CompletableFuture<Unit>().also {done->server.globalRegionScheduler.runDelayed(this,{done.complete(Unit)},ticks.coerceAtLeast(1))}
    @EventHandler fun join(e:PlayerJoinEvent) {joins+=e.player.uniqueId;joinCounts.computeIfAbsent(e.player.uniqueId) {java.util.concurrent.atomic.AtomicInteger()}.incrementAndGet();if(observing)observePlayer(e.player)}
    @EventHandler(priority=EventPriority.MONITOR,ignoreCancelled=true) fun damage(e:EntityDamageEvent) {
        val p=e.entity as? Player?:return
        if(p.name=="MineBot1" && mine.binding(p.uniqueId)?.phase==MinePhase.BOSS && e.damageSource.damageType.key().value()=="mob_attack") {
            val defense=production.combatStats!!.snapshot(p)!!.defense
            val config=mine.binding(p.uniqueId)!!.settings
            check(kotlin.math.abs(e.damage-config.cleaveDamage*(1-defense))<.001 || kotlin.math.abs(e.damage-config.areaDamage*(1-defense))<.001) {"Scripted damage applied armor bonus twice: ${e.damage}"}
            attacks+=e.damage
        }
    }
    override fun onEnable() {
        dataFolder.mkdirs();server.pluginManager.registerEvents(this,this)
        server.globalRegionScheduler.runDelayed(this,{
            runCatching {start()}.getOrElse {CompletableFuture.failedFuture(it)}.orTimeout(520,TimeUnit.SECONDS).whenComplete {_,error->
                CompletableFuture.runAsync {
                    if(error!=null) {logger.log(java.util.logging.Level.SEVERE,"MINE SMOKE FAILED at $stage",error);dataFolder.resolve("failed.txt").writeText(error.stackTraceToString())}
                    else {dataFolder.resolve(if(dataFolder.resolve("phase1.ok").exists())"phase2.ok" else "phase1.ok").writeText("PASS");dataFolder.resolve("mine-smoke-${if(dataFolder.resolve("phase2.ok").exists())2 else 1}.json").writeText(com.google.gson.GsonBuilder().setPrettyPrinting().create().toJson(mapOf("assertions" to assertions,"clients" to 6,"actions" to "synthetic helper on native owning entity/region schedulers","humanPlaytest" to false,"metrics" to metrics)))}
                }.whenComplete {_,_->server.globalRegionScheduler.execute(this) {server.shutdown()}}
            }
        },40)
    }
    private fun start():CompletableFuture<Unit> {
        production=server.pluginManager.getPlugin("one-block") as OneBlock
        mine=field(production,"mine") as MineController
        val second=dataFolder.resolve("phase1.ok").exists()
        return mine.engine.ready.thenCompose {prepareHub()}.thenCompose {
            CompletableFuture.runAsync {dataFolder.resolve("bot-ready").writeText("775")}
        }.thenCompose {await({joins.size==6})}.thenCompose {
            val players=(1..6).map {server.getPlayerExact("MineBot$it")!!}
            val collecting=field(field(production,"trades"),"collecting") as Set<*>
            await({players.none {InventoryGuard.busy(it.uniqueId) || it.uniqueId in collecting}}).thenCompose {
                val manager=de.oliver.fancynpcs.api.FancyNpcsPlugin.get().npcManager
                if(second)await({manager.allNpcs.count {it.data.name==mine.settings.npc.name}==1}).thenApply {
                    check(manager.allNpcs.single {it.data.name==mine.settings.npc.name}.isSaveToFile)
                    note("manually placed dungeon NPC survived restart without duplication")
                } else {
                    check(manager.allNpcs.none {it.data.name==mine.settings.npc.name}) {"Dungeon guide must not spawn automatically"}
                    CompletableFuture.completedFuture(Unit)
                }
            }.thenCompose {
                observe(players)
                if(second)second(players) else first(players)
            }
        }
    }
    private fun observe(players:List<Player>) {
        observing=true;players.forEach(::observePlayer)
        server.asyncScheduler.runAtFixedRate(this,{
            val memory=java.lang.management.ManagementFactory.getMemoryMXBean().heapMemoryUsage
            val os=java.lang.management.ManagementFactory.getOperatingSystemMXBean() as com.sun.management.OperatingSystemMXBean
            metrics+=mapOf("kind" to "runtime-sample","heapUsedBytes" to memory.used,"heapMaxBytes" to memory.max,"osFreeBytes" to os.freeMemorySize,"osTotalBytes" to os.totalMemorySize,
                "gcCount" to java.lang.management.ManagementFactory.getGarbageCollectorMXBeans().sumOf {it.collectionCount.coerceAtLeast(0)},"gcMillis" to java.lang.management.ManagementFactory.getGarbageCollectorMXBeans().sumOf {it.collectionTime.coerceAtLeast(0)},"saveQueue" to mine.storage.queuedOperations)
            val start=System.nanoTime();mine.storage.flushAsync().whenComplete {_,error->metrics+=mapOf("kind" to "flush-sample","millis" to (System.nanoTime()-start)/1e6,"success" to (error==null))}
        },1,1,TimeUnit.SECONDS)
    }
    private fun observePlayer(p:Player) {
        p.scheduler.runAtFixedRate(this,{
            metrics+=mapOf("kind" to "region-sample","player" to p.name,"world" to p.world.key().asString(),"meanMspt5s" to server.averageTickTime,"tps" to server.tps.toList(),"loadedChunksApi" to p.world.chunkCount,"entitiesApi" to p.world.entityCount)
        },{},1,20)
    }
    private fun prepareHub():CompletableFuture<Unit> {
        val w=server.getWorld(net.kyori.adventure.key.Key.key("minecraft:overworld"))!!
        return w.getChunkAtAsync(0,0,true).thenCompose {mine.worlds.region(w,0,0) {
            for(x in 0..15)for(z in 0..15) {w.getBlockAt(x,100,z).setType(Material.STONE,false);w.getBlockAt(x,101,z).setType(Material.AIR,false);w.getBlockAt(x,102,z).setType(Material.AIR,false)}
        }}.thenCompose {CompletableFuture.runAsync {
            mine.hub.persist(HubSettings(w.key().asString(),false,5.5,101.0,5.5,0f,0f))
            val source=production.dataFolder.resolve("dungeon.yml")
            val config=org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(source)
            config.set("enabled",true);config.set("allow-instance-worlds",true);config.set("entry.world",w.key().asString());config.set("entry.x",5.5);config.set("entry.y",101.0);config.set("entry.z",5.5)
            config.set("npc.enabled",true);config.set("npc.world",w.key().asString());config.set("npc.x",7.5);config.set("npc.y",101.0);config.set("npc.z",5.5)
            config.save(source);mine.settings=MineSettings.read(source).first!!
        }.thenApply {Unit}}
    }
    private fun prepare(p:Player)=mine.entity(p) {
        p.isOp=true
        val account=mine.islands.createProfile(p.uniqueId,"Mine test")!!
        mine.players.update(p.uniqueId) {it.copy(activeAccount=account.owner)}
        mine.islands.releaseUpdate(account.owner) {i,s->ReleaseChange(i.copy(releaseProgress=ReleaseProgress(chapter="developed")),s)}
        p.gameMode=GameMode.SURVIVAL;p.health=16.0;p.foodLevel=20
        for(slot in 0..35)if(slot!=8)p.inventory.setItem(slot,ItemStack(Material.DIRT,64))
        p.inventory.setItem(0,ItemStack(Material.DIAMOND_SWORD));p.inventory.setHelmet(ItemStack(Material.DIAMOND_HELMET));p.inventory.setChestplate(ItemStack(Material.DIAMOND_CHESTPLATE));p.inventory.setLeggings(ItemStack(Material.DIAMOND_LEGGINGS));p.inventory.setBoots(ItemStack(Material.DIAMOND_BOOTS))
        p.teleportAsync(Location(server.getWorld(net.kyori.adventure.key.Key.key(mine.settings.entry.world)),5.5,101.0,5.5))
    }.thenCompose {it}.thenApply {check(it);Unit}
    private fun command(p:Player,text:String)=mine.entity(p) {check(p.performCommand(text));Unit}
    private fun solo(p:Player):CompletableFuture<MineRun> {mine.settings=mine.settings.copy(waves=null);return command(p,"dungeon ready").thenCompose {command(p,"dungeon start")}.thenCompose {
        await({mine.binding(p.uniqueId)?.phase==MinePhase.ENTRY && mine.binding(p.uniqueId)?.members?.get(p.uniqueId)?.inWorld==true})
    }.thenApply {mine.binding(p.uniqueId)!!}}
    private fun party(group:List<Player>):CompletableFuture<MineRun> {
        mine.settings=mine.settings.copy(waves=null);val leader=group.first();var flow=CompletableFuture.completedFuture(Unit)
        group.drop(1).forEach {p->flow=flow.thenCompose {command(leader,"dungeon invite ${p.name}")}.thenCompose {command(p,"dungeon accept ${leader.name}")}}
        return flow.thenCompose {all(group.map {command(it,"dungeon ready")})}.thenCompose {command(leader,"dungeon start")}.thenCompose {
            await({mine.binding(leader.uniqueId)?.let {it.phase==MinePhase.ENTRY && it.members.values.all {m->m.inWorld}}==true})
        }.thenApply {mine.binding(leader.uniqueId)!!}
    }
    private fun kill(run:MineRun,boss:Boolean):CompletableFuture<Unit> = await({mine.encounters.mobs.values.count {it.run==run.id && it.boss==boss}==if(boss)1 else run.settings.mobCount+(run.members.size-1)*run.settings.mobsPerExtra}).thenCompose {
        all(mine.encounters.mobs.values.filter {it.run==run.id && it.boss==boss}.map {m->CompletableFuture<Unit>().also {done->
            m.entity.scheduler.execute(this,{try {m.entity.damage(100000.0);done.complete(Unit)}catch(e:Throwable){done.completeExceptionally(e)}},{done.completeExceptionally(IllegalStateException("Mob retired"))},1)
        }})
    }
    private fun interact(p:Player,x:Int,y:Int,z:Int)=mine.entity(p) {
        p.teleportAsync(Location(p.world,x+.5,65.0,z+.5))
    }.thenCompose {it}.thenCompose {mine.entity(p) {
        val block=p.world.getBlockAt(x,y,z)
        server.pluginManager.callEvent(PlayerInteractEvent(p,Action.RIGHT_CLICK_BLOCK,p.inventory.itemInMainHand,block,BlockFace.NORTH,EquipmentSlot.HAND));Unit
    }}
    private fun puzzle(p:Player,run:MineRun,secret:Boolean):CompletableFuture<Unit> {
        var flow=await({mine.engine.get(run.id)?.phase==MinePhase.PUZZLE})
        if(secret)flow=flow.thenCompose {interact(p,54,66,31)}.thenCompose {await({mine.engine.get(run.id)?.secretOpen==true})}.thenCompose {interact(p,55,65,40)}.thenCompose {await({mine.engine.get(run.id)?.secret==true})}
        // Wrong first input must reset rather than lock the session.
        flow=flow.thenCompose {interact(p,52,65,8)}.thenCompose {await({mine.engine.get(run.id)?.puzzleStep==0})}
        run.settings.puzzle.forEachIndexed {i,lever->flow=flow.thenCompose {interact(p,48+(lever-1)*4,65,8)}.thenCompose {await({mine.engine.get(run.id)?.let {it.phase==MinePhase.BOSS || it.puzzleStep==i+1}==true})}}
        return flow
    }
    private fun first(players:List<Player>):CompletableFuture<Unit> = all(players.map(::prepare)).thenCompose {
        command(players.first(),"obadmin market npc dungeon")
    }.thenCompose {await({de.oliver.fancynpcs.api.FancyNpcsPlugin.get().npcManager.allNpcs.count {it.data.name==mine.settings.npc.name}==1})}
        .thenCompose {verifyMenusAndNpc(players[0])}.thenCompose {all(players.map {command(it,"dungeon menu")})}.thenCompose {
        note("prepared six profiles, native full bags and diamond armor")
        mine.storage.flushAsync()
    }.thenCompose {
        val one=solo(players[0]);val group=party(players.subList(1,5))
        one.thenCombine(group) {a,b->a to b}
    }.thenCompose {(a,b)->
        check(a.world!=b.world);note("solo and four-player group coexist in distinct Worlds instances")
        command(players[5],"home").thenCompose {all(listOf(kill(a,false),kill(b,false),mine.storage.flushAsync()))}.thenCompose {
            all(listOf(puzzle(players[0],a,true),puzzle(players[1],b,false)))
        }.thenCompose {
            note("native intro kills, retryable switch events and secret entitlement; no reward before boss")
            check(mine.storage.marketState().combatReceipts.isEmpty())
            val p=players[0]
            mine.entity(p) {
                mine.transfers.add(p.uniqueId)
                p.teleportAsync(Location(server.getWorld(net.kyori.adventure.key.Key.key(mine.settings.entry.world)),5.5,101.0,5.5))
            }.thenCompose {it}.thenCompose {delay(a.settings.attackTicks+10)}.thenCompose {mine.entity(p) {
                p.health=minOf(p.health,a.members.getValue(p.uniqueId).entryHealth)
                p.teleportAsync(Location(mine.worlds.world(a),88.5,65.0,16.5))
            }}.thenCompose {it}.thenApply {mine.transfers.remove(p.uniqueId);note("controlled in-flight target realm mismatch does not stop the guardian attack loop");Unit}
        }.thenCompose {
            val p=players[2]
            // /hub uses the real command hook; raw return is /dungeon reconnect, as a dead spectator.
            command(p,"hub").thenCompose {await({mine.engine.get(b.id)?.deaths==1 && mine.engine.get(b.id)?.members?.get(p.uniqueId)?.inWorld==false})}.thenCompose {command(p,"dungeon reconnect")}.thenCompose {
                await({mine.engine.get(b.id)?.members?.get(p.uniqueId)?.inWorld==true})
            }.thenCompose {mine.entity(p) {check(p.gameMode==GameMode.SPECTATOR);check(mine.engine.get(b.id)!!.deaths==1);Unit}}
        }.thenCompose {
            note("hub departure counts one death; reconnect cannot regain a fighting life")
            val old=players[2];val count=joinCounts.getValue(old.uniqueId).get()
            CompletableFuture.runAsync {mine.hub.persist(HubSettings(mine.settings.entry.world,false,6.5,101.0,5.5,0f,0f))}.thenCompose {
                val w=server.getWorld(net.kyori.adventure.key.Key.key(mine.settings.entry.world))!!
                mine.worlds.region(w,0,0) {w.getBlockAt(5,100,5).setType(Material.AIR,false);Unit}
            }.thenCompose {CompletableFuture.runAsync {dataFolder.resolve("${old.name}-disconnect").writeText("close this loopback client")}}.thenCompose {
                await({mine.engine.get(b.id)?.members?.get(old.uniqueId)?.inWorld==false})
            }.thenCompose {CompletableFuture.runAsync {dataFolder.resolve("${old.name}-connect").writeText("reconnect same offline UUID")}}.thenCompose {
                val collecting=field(field(production,"trades"),"collecting") as Set<*>
                await({joinCounts.getValue(old.uniqueId).get()>count && server.getPlayer(old.uniqueId)!=null}).thenCompose {delay(4)}.thenCompose {
                    await({!InventoryGuard.busy(old.uniqueId) && old.uniqueId !in collecting})
                }
            }.thenCompose {mine.entity(server.getPlayer(old.uniqueId)!!) {
                val fresh=server.getPlayer(old.uniqueId)!!;check(fresh.world.key().asString()==mine.settings.entry.world && kotlin.math.abs(fresh.location.x-6.5)<.1) {"Login reused an unsafe old hub entry"};Unit
            }}.thenCompose {
                val w=server.getWorld(net.kyori.adventure.key.Key.key(mine.settings.entry.world))!!
                mine.worlds.region(w,0,0) {w.getBlockAt(5,100,5).setType(Material.STONE,false);Unit}
            }.thenCompose {command(server.getPlayer(old.uniqueId)!!,"dungeon reconnect")}.thenCompose {
                await({mine.engine.get(b.id)?.members?.get(old.uniqueId)?.inWorld==true})
            }.thenApply {check(mine.engine.get(b.id)!!.deaths==1);note("real socket quit and same UUID login reconnect without duplicate death or free life");Unit}
        }.thenCompose {
            val p=players[3]
            val kept=java.util.concurrent.atomic.AtomicReference<Pair<String,Triple<Int,Float,Int>>>()
            mine.entity(p) {p.giveExpLevels(7);p.giveExp(5);kept.set(com.alytanta.oneblock.trade.TradeInventory.hash(p.inventory.contents) to Triple(p.level,p.exp,p.totalExperience));p.health=0.0;Unit}.thenCompose {await({mine.engine.get(b.id)?.deaths==2})}.thenCompose {
                mine.entity(p) {p.spigot().respawn();Unit}
            }.thenCompose {await({mine.engine.get(b.id)?.members?.get(p.uniqueId)?.awaitingRespawn==false})}.thenCompose {mine.entity(p) {
                check(!p.isDead && p.gameMode==GameMode.SPECTATOR);check(mine.engine.get(b.id)!!.deaths==2)
                check(com.alytanta.oneblock.trade.TradeInventory.hash(p.inventory.contents)==kept.get().first) {"Physical respawn changed inventory/armor"}
                check(Triple(p.level,p.exp,p.totalExperience)==kept.get().second) {"Physical respawn changed XP"};Unit
            }}
        }.thenCompose {
            note("native physical death and respawn preserve inventory and count exactly once")
            mine.entity(players[0]) {
                val p=players[0];val rules=mine.islands.releaseRules!!.combat!!
                val armor=rules.items.values.filter {it.set=="warden"}
                armor.forEach {item->val stack=CombatItems.create(item);when(item.armorSlot) {"HEAD"->p.inventory.setHelmet(stack);"CHEST"->p.inventory.setChestplate(stack);"LEGS"->p.inventory.setLeggings(stack);"FEET"->p.inventory.setBoots(stack)}}
                p.teleportAsync(Location(p.world,88.5,65.0,16.5))
            }.thenCompose {it}.thenCompose {await({attacks.distinct().size>=2})}
        }.thenCompose {
            note("both native telegraphed attacks fire; Warden defense applied once")
            // Protection event and physical block stay intact, even for OP.
            mine.entity(players[0]) {players[0].teleportAsync(Location(players[0].world,80.5,65.0,16.5))}.thenCompose {it}.thenCompose {mine.entity(players[0]) {
                val block=players[0].world.getBlockAt(80,64,16);val event=BlockBreakEvent(block,players[0]);server.pluginManager.callEvent(event);check(event.isCancelled);check(block.type!=Material.AIR);Unit
            }}
        }.thenCompose {CompletableFuture.runAsync {
            dataFolder.resolve("test-db-disconnect").writeText("terminate only this isolated runner database connection")
            val end=System.nanoTime()+10_000_000_000L
            while(!dataFolder.resolve("test-db-disconnected").exists()) {check(System.nanoTime()<end) {"Isolated DB fault was not acknowledged"};Thread.sleep(20)}
        }}.thenCompose {all(listOf(kill(a,true),kill(b,true)))}.thenCompose {
            await({!mine.storage.healthy && mine.engine.hasPendingReward(players[0].uniqueId) && mine.engine.hasPendingReward(players[1].uniqueId)})
        }.thenCompose {
            check(mine.engine.hasPendingReward(players[0].uniqueId))
            check(mine.engine.hasPendingReward(players[1].uniqueId))
            note("native DB disconnect leaves frozen reward journals pending without acknowledging failed SQL")
            await({mine.storage.healthy}) // Production heartbeat reconnect may finish before world evacuation.
        }.thenCompose {
            await({mine.storage.marketState().combatReceipts.size==6 && mine.engine.get(a.id)==null && mine.engine.get(b.id)==null})
        }.thenCompose {
            check(mine.storage.marketState().claims.values.count {it.source.startsWith("combat:") && it.recipient==players[0].uniqueId}>=7)
            note("DB reconnect acknowledges exactly six boss/secret receipts; full bags preserve claims; both instances cleaned")
            CompletableFuture.runAsync {mine.storage.backup().copyTo(dataFolder.resolve("mine-backup.sql"),true)}
        }.thenCompose {solo(players[0])}.thenCompose {active->
            note("active session persisted for restart-abort verification")
            CompletableFuture.runAsync {dataFolder.resolve("interrupted.txt").writeText("${active.id}\n${active.world}")}.thenCompose {all(players.map {old->val live=server.getPlayer(old.uniqueId)!!;mine.entity(live) {live.saveData();Unit}})}.thenCompose {mine.storage.flushAsync()}.thenApply {Unit}
        }
    }
    /** Native inventories/permissions and FancyNpcs events, without a second renderer or business-action bypass. */
    private fun verifyMenusAndNpc(p:Player):CompletableFuture<Unit> {
        val menus=mine.menu
        val fancy=de.oliver.fancynpcs.api.FancyNpcsPlugin.get()
        fun guides()=fancy.npcManager.allNpcs.filter {it.data.name==mine.settings.npc.name}
        fun click(slot:Int)=mine.entity(p) {
            val e=org.bukkit.event.inventory.InventoryClickEvent(p.openInventory,org.bukkit.event.inventory.InventoryType.SlotType.CONTAINER,slot,
                org.bukkit.event.inventory.ClickType.LEFT,org.bukkit.event.inventory.InventoryAction.PICKUP_ALL)
            server.pluginManager.callEvent(e);check(e.isCancelled)
        }.thenCompose {delay(3)}
        fun button(command:String)=mine.entity(p) {
            menus.definition(menus.currentMenuId(p)!!)!!.slots.entries.single {it.value.command=="/obmenu $command"}.key
        }.thenCompose(::click)
        fun npcClick()=mine.entity(p) {
            val npc=guides().single()
            repeat(12) {
                val e=de.oliver.fancynpcs.api.events.NpcInteractEvent(npc,{},emptyList(),p,de.oliver.fancynpcs.api.actions.ActionTrigger.RIGHT_CLICK)
                server.pluginManager.callEvent(e);check(e.isCancelled)
            }
        }.thenCompose {delay(3)}
        var attachment:org.bukkit.permissions.PermissionAttachment?=null
        var inventory:List<String?> = emptyList()
        val staged=System.getenv("ONEBLOCK_CONFIG_AUDIT")?.takeIf {it.isNotBlank()}
        val materialCheck=if(staged==null)CompletableFuture.completedFuture(Unit) else CompletableFuture.supplyAsync {
            com.alytanta.oneblock.config.ConfigSnapshot.read(java.io.File(staged,"config.yml"),logger)
        }.thenCompose {candidate->mine.entity(p) {candidate.validateMaterials();note("actual staged server YAML and all menu materials validate against native Folia registry");Unit}}
        var flow=materialCheck.thenCompose {await({guides().size==1})}.thenCompose {mine.entity(p) {
            check(guides().single().isSaveToFile)
            p.isOp=false;attachment=p.addAttachment(this).also {
                it.setPermission("oneblock.command",true);it.setPermission("oneblock.admin",false);it.setPermission("oneblock.admin.inspect",false)
            }
            inventory=p.inventory.contents.map {it?.serializeAsBytes()?.let(java.util.Base64.getEncoder()::encodeToString)}
            val rules=mine.islands.releaseRules!!
            val expected=rules.recipes.values.flatMap {it.items}.mapNotNull {it.tool}.toSet()
            val seen=mutableSetOf<String>()
            val plain=net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText()
            val size=menus.definition("guide-page")!!.content.size
            repeat((rules.recipes.size+17)/size+1) {page->
                menus.guideAction!!(p,if(page==0)"topic workshop" else "next")
                check(menus.currentMenuId(p)=="guide-page")
                p.openInventory.topInventory.contents.filterNotNull().forEach {stack->
                    check(!plain.serialize(stack.itemMeta.displayName()?:net.kyori.adventure.text.Component.empty()).contains("release.name."))
                    com.alytanta.oneblock.items.CustomItems.resolve(stack)?.let {item->
                check(stack.itemMeta.itemModel?.namespace()!="oneblock") // No resource pack: a custom model would show the missing texture.
                        
                        seen+=item.legacyId
                    }
                }
            }
            check(seen.containsAll(expected)) {"Guide omitted custom appearances: ${expected-seen}"}
            val head=menus.makeItem(p,com.alytanta.oneblock.menu.MenuItemSpec(Material.PLAYER_HEAD,"Viewer",emptyList(),texture="{player}"),emptyMap())
            if(p.playerProfile.properties.none {it.name=="textures"})check((head.itemMeta as org.bukkit.inventory.meta.SkullMeta).ownerProfile==null)
            note("native guide pages use all recipe output PDC/models/equipped layers without raw locale keys; offline viewer head does not request a Mojang profile")
            menus.open(p)
        }}
        for((id,expected) in listOf("release-core" to "release-core","release-skills" to "release-skills","release-workshop" to "release-workshop",
            "combat-weapons" to "combat-weapons","release-goals" to "release-goals","release-claims" to "release-claims",
            "economy" to "economy","coop" to "coop","settings" to "settings","guide-index" to "guides-index","profiles" to "profiles","dungeons" to "mine-lobby")) {
            flow=flow.thenCompose {button(id)}.thenCompose {mine.entity(p) {
                if(expected=="profiles")check((field(field(production,"mainMenu"),"externalMenuId") as (Player)->String?).invoke(p)=="profiles")
                else check(menus.currentMenuId(p)==expected) {"$id opened ${menus.currentMenuId(p)}"}
                check(p.openInventory.topInventory.getItem(49)?.type==Material.BARRIER)
            }}.thenCompose {click(45)}.thenCompose {mine.entity(p) {check(menus.currentMenuId(p)=="main-menu")}}
        }
        return flow.thenCompose {mine.entity(p) {
            val shift=org.bukkit.event.inventory.InventoryClickEvent(p.openInventory,org.bukkit.event.inventory.InventoryType.SlotType.CONTAINER,20,
                org.bukkit.event.inventory.ClickType.SHIFT_LEFT,org.bukkit.event.inventory.InventoryAction.MOVE_TO_OTHER_INVENTORY)
            server.pluginManager.callEvent(shift);check(shift.isCancelled)
            val drag=org.bukkit.event.inventory.InventoryDragEvent(p.openInventory,ItemStack(Material.DIRT),ItemStack(Material.DIRT),false,mapOf(11 to ItemStack(Material.DIRT)))
            server.pluginManager.callEvent(drag);check(drag.isCancelled)
            check(inventory==p.inventory.contents.map {it?.serializeAsBytes()?.let(java.util.Base64.getEncoder()::encodeToString)})
            p.closeInventory();check(!p.performCommand("obadmin dungeon") || menus.currentMenuId(p)!="mine-admin")
        }}.thenCompose {await({guides().singleOrNull()?.isShownFor(p)==true})}.thenCompose {npcClick()}.thenCompose {mine.entity(p) {
            check(menus.currentMenuId(p)=="dungeon-select");check(mine.binding(p.uniqueId)==null)
        }}.thenCompose {button("mine-menu")}.thenCompose {mine.entity(p) {
            check(menus.currentMenuId(p)=="mine-lobby");mine.settings=mine.settings.copy(enabled=false)
        }}.thenCompose {button("mine-start")}.thenCompose {mine.entity(p) {
            check(mine.binding(p.uniqueId)==null);mine.settings=mine.settings.copy(enabled=true)
            attachment!!.setPermission("oneblock.command",false);p.closeInventory()
        }}.thenCompose {npcClick()}.thenCompose {mine.entity(p) {
            check(menus.currentMenuId(p)==null);p.removeAttachment(attachment!!);p.isOp=true
            check(p.performCommand("obadmin dungeon"));check(menus.currentMenuId(p)=="mine-admin")
            mine.command(p,listOf("leave"));p.closeInventory();Unit
        }}.thenCompose {
            val identity=menus.configurationIdentity
            command(p,"obadmin reload").thenCompose {await({menus.configurationIdentity!==identity && mine.storage.healthy})}
        }.thenCompose {await({guides().size==1})}.thenCompose {npcClick()}.thenCompose {mine.entity(p) {
            check(menus.currentMenuId(p)=="dungeon-select")
            val previous=guides().single();val location=previous.data.location.clone()
            mine.settings=mine.settings.copy(npc=mine.settings.npc.copy(displayName="changed"));previous to location
        }}.thenCompose {(previous,location)->delay(2).thenCompose {mine.entity(p) {check(guides().single()===previous && guides().single().data.location==location);Unit}}}.thenCompose {mine.entity(p) {
            mine.settings=mine.settings.copy(npc=mine.settings.npc.copy(enabled=false))
        }}.thenCompose {delay(2)}.thenCompose {mine.entity(p) {
            check(server.worlds.none {it.key().namespace()=="oneblock_dungeons"})
            check(guides().size==1) {"Changing role config must not remove a manually placed NPC"}
            mine.settings=mine.settings.copy(npc=mine.settings.npc.copy(enabled=true))
        }}.thenCompose {delay(2)}.thenCompose {mine.entity(p) {
            check(guides().size==1)
            p.closeInventory();Unit
        }}.thenApply {note("native menu/back paths, inventory protection, access checks, manually placed dungeon NPC selection, disabled start refusal, reload/restart persistence, and no automatic duplication or config-driven relocation");Unit}
    }
    private fun second(players:List<Player>):CompletableFuture<Unit> {
        val old=UUID.fromString(dataFolder.resolve("interrupted.txt").readLines().first())
        val kept=java.util.concurrent.atomic.AtomicReference<String>()
        return await({mine.engine.get(old)==null}).thenCompose {
            all(players.take(5).map {p->mine.entity(p) {check(!mine.managed(p.world));check(p.gameMode==GameMode.SURVIVAL);Unit}})
        }.thenCompose {
            check(mine.storage.marketState().combatReceipts.size==6)
            note("second native boot aborts interrupted run, returns players and preserves six receipts")
            solo(players[0])
        }.thenCompose {s->command(players[0],"hub").thenCompose {await({mine.engine.get(s.id)?.deaths==1})}.thenCompose {command(players[0],"dungeon reconnect")}.thenCompose {await({mine.engine.get(s.id)?.wipes==1})}.thenCompose {
            mine.entity(players[0]) {check(players[0].gameMode==GameMode.SURVIVAL);check(mine.engine.get(s.id)!!.deaths==1);Unit}
        }.thenCompose {mine.entity(players[0]) {kept.set(com.alytanta.oneblock.trade.TradeInventory.hash(players[0].inventory.contents));players[0].health=0.0;Unit}}.thenCompose {await({mine.engine.get(s.id)?.deaths==2})}
            .thenCompose {command(players[1],"ob admin dungeon stop ${s.id}")}.thenCompose {command(players[1],"ob admin dungeon stop ${s.id} CONFIRM")}.thenCompose {await({mine.engine.get(s.id)==null})}
            .thenCompose {mine.entity(players[0]) {check(!players[0].isDead && players[0].world.key().asString()==mine.settings.entry.world && kotlin.math.abs(players[0].location.x-5.5)<.1);check(com.alytanta.oneblock.trade.TradeInventory.hash(players[0].inventory.contents)==kept.get()) {"AFK dead evacuation lost inventory/PDC"};Unit}}}
            .thenCompose {note("confirmed admin stop evacuates player and removes only its instance");trade(players[0],players[5])}.thenCompose {profileRoundtrip(players[0])}.thenCompose {adminGrant(players[0])}
    }
    private fun adminGrant(p:Player):CompletableFuture<Unit> {
        val id=UUID.fromString("c03130d0-d3f8-4c82-9498-a69b2c05e15e")
        val grant="obadmin items give ${p.name} oneblock:warden_blade 1 $id"
        val before=mine.storage.marketState().combatReceipts.size
        fun issue()=CompletableFuture<Unit>().also {done->server.globalRegionScheduler.execute(this) {try {check(server.dispatchCommand(server.consoleSender,grant));done.complete(Unit)}catch(e:Throwable){done.completeExceptionally(e)}}}
        return issue().thenCompose {await({mine.storage.marketState().combatReceipts.size==before+1 && !InventoryGuard.busy(p.uniqueId)})}
            .thenCompose {issue()}.thenCompose {delay(20)}.thenCompose {mine.entity(p) {
                check(mine.storage.marketState().combatReceipts.size==before+1)
                val receipt=UUID.nameUUIDFromBytes("combat:admin_items:$id:${p.uniqueId}".toByteArray(Charsets.UTF_8))
                val claims=mine.storage.marketState().claims.values.filter {it.source=="combat:$receipt"}
                check(claims.size==1) {"Admin grant replay created ${claims.size} claims"}
                val stack=AuctionItems.decode(claims.single().itemData!!)
                check(com.alytanta.oneblock.items.CustomItems.id(stack)=="oneblock:warden_blade")
                check(stack.itemMeta.itemModel.toString()=="oneblock:warden_blade");Unit
            }}.thenApply {note("admin give uses existing personal exact-byte claims; duplicate request UUID creates one receipt and claim");Unit}
    }
    private fun trade(a:Player,b:Player):CompletableFuture<Unit> {
        val token=UUID.randomUUID().toString();val key=NamespacedKey(this,"trade_probe")
        val controller=field(production,"trades") as com.alytanta.oneblock.trade.TradeController
        val config=field(controller,"settings") as com.alytanta.oneblock.trade.TradeSettings
        fun view(p:Player)=CompletableFuture<Unit>().also {done->
            var left=100
            p.scheduler.runAtFixedRate(this,{task->
                if(p.openInventory.topInventory.holder?.javaClass?.name?.contains("TradeController")==true) {task.cancel();done.complete(Unit)}
                else if(--left==0) {task.cancel();done.completeExceptionally(IllegalStateException("Native trade view missing"))}
            },{done.completeExceptionally(IllegalStateException("Trade player retired"))},1,1)
        }
        fun click(p:Player,raw:Int)=mine.entity(p) {
            val event=org.bukkit.event.inventory.InventoryClickEvent(p.openInventory,org.bukkit.event.inventory.InventoryType.SlotType.CONTAINER,raw,
                org.bukkit.event.inventory.ClickType.LEFT,org.bukkit.event.inventory.InventoryAction.PICKUP_ALL)
            server.pluginManager.callEvent(event);Unit
        }
        return mine.entity(b) {b.inventory.setItem(4,null);b.teleportAsync(Location(server.getWorld(net.kyori.adventure.key.Key.key(mine.settings.entry.world)),6.5,101.0,5.5))}.thenCompose {it}.thenCompose {
            mine.entity(a) {
                val stack=CombatItems.create(mine.islands.releaseRules!!.combat!!.items.getValue("rift_staff"))
                stack.editMeta {it.persistentDataContainer.set(key,org.bukkit.persistence.PersistentDataType.STRING,token)}
                a.inventory.setItem(4,stack);a.saveData();Unit
            }
        }.thenCompose {command(a,"trade ${b.name}")}.thenCompose {await({(field(controller,"requests") as Map<*,*>).containsKey(b.uniqueId)})}.thenCompose {command(b,"trade accept ${a.name}")}.thenCompose {all(listOf(view(a),view(b)))}
            .thenCompose {click(a,config.rows*9+27+4)}.thenCompose {delay(config.changeCooldownSeconds*20L+2)}.thenCompose {click(a,config.ownReady)}.thenCompose {click(b,config.ownReady)}.thenCompose {
                note("native trade offers confirmed after configured cooldown")
                await({val ledger=mine.storage.marketState();val collecting=field(controller,"collecting") as Set<*>
                    ledger.playerTrades.values.any {it.status==com.alytanta.oneblock.trade.TradeStatus.DONE} &&
                        ledger.claims.values.none {it.recipient==b.uniqueId && it.source.startsWith("player-trade:")} &&
                        a.uniqueId !in collecting && b.uniqueId !in collecting && !InventoryGuard.busy(a.uniqueId) && !InventoryGuard.busy(b.uniqueId)})
            }.thenCompose {mine.entity(b) {
                val exact=b.inventory.storageContents.filterNotNull().filter {it.itemMeta?.persistentDataContainer?.get(key,org.bukkit.persistence.PersistentDataType.STRING)==token}
                check(exact.size==1 && CombatItems.id(exact.single())=="rift_staff") {"Native trade lost/duplicated combat PDC"};Unit
            }}.thenCompose {mine.entity(a) {
                val item=CombatItems.create(mine.islands.releaseRules!!.combat!!.items.getValue("warden_blade"))
                item.editMeta {(it as org.bukkit.inventory.meta.Damageable).damage=10}
                val view=a.openWorkbench(a.location,true)!!;val inv=view.topInventory as CraftingInventory
                inv.matrix=arrayOf(item,item.clone(),null,null,null,null,null,null,null)
                inv.result=ItemStack(Material.IRON_SWORD)
                server.pluginManager.callEvent(org.bukkit.event.inventory.PrepareItemCraftEvent(inv,view,true))
                check(inv.result==null) {"Vanilla repair stripped stable combat identity"}
                check(inv.matrix.count {CombatItems.id(it)=="warden_blade"}==2)
                inv.matrix=arrayOfNulls<ItemStack>(9);a.closeInventory();Unit
            }}.thenApply {note("native two-player trade preserves exact combat PDC; vanilla repair cannot consume or strip it");Unit}
    }
    private fun profileRoundtrip(p:Player):CompletableFuture<Unit> {
        val controller=(production.getCommand("oneblock")!!.executor as com.alytanta.oneblock.command.OneBlockCommand).profiles
        val key=NamespacedKey(this,"profile_probe");val token=UUID.randomUUID().toString()
        fun switch(account:UUID)=CompletableFuture<Unit>().also {done->mine.entity(p) {controller.switch(p,account) {done.complete(Unit)}}.exceptionally {done.completeExceptionally(it);null}}.orTimeout(40,TimeUnit.SECONDS)
        return mine.entity(p) {
            val old=mine.islands.findForPlayer(p.uniqueId)!!.owner
            val next=mine.islands.createProfile(p.uniqueId,"Other native")!!.owner
            val item=CombatItems.create(mine.islands.releaseRules!!.combat!!.items.getValue("frost_wand"))
            item.editMeta {it.persistentDataContainer.set(key,org.bukkit.persistence.PersistentDataType.STRING,token)}
            p.inventory.setItem(5,item);old to next
        }.thenCompose {(old,next)->switch(next).thenCompose {mine.entity(p) {
            check(mine.islands.findForPlayer(p.uniqueId)!!.owner==next)
            check(p.inventory.storageContents.filterNotNull().none {it.itemMeta?.persistentDataContainer?.get(key,org.bukkit.persistence.PersistentDataType.STRING)==token});Unit
        }}.thenCompose {switch(old)}.thenCompose {mine.entity(p) {
            val exact=p.inventory.storageContents.filterNotNull().filter {it.itemMeta?.persistentDataContainer?.get(key,org.bukkit.persistence.PersistentDataType.STRING)==token}
            check(exact.size==1 && CombatItems.id(exact.single())=="frost_wand");check(!InventoryGuard.busy(p.uniqueId));Unit
        }}}.thenApply {note("native profile switch out and back preserves one exact combat PDC item and keeps profiles isolated");Unit}
    }
}
