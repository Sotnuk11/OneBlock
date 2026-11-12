package com.alytanta.oneblock.smoke

import com.alytanta.oneblock.hub.*
import com.alytanta.oneblock.hub.generator.*
import com.alytanta.oneblock.util.FoliaTasks
import com.google.gson.Gson
import de.oliver.fancynpcs.api.FancyNpcsPlugin
import de.oliver.fancynpcs.api.NpcData
import org.bukkit.*
import org.bukkit.entity.Player
import org.bukkit.event.*
import org.bukkit.event.block.BlockBreakEvent
import org.bukkit.event.player.PlayerCommandPreprocessEvent
import org.bukkit.plugin.java.JavaPlugin
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.atomic.AtomicInteger

/** Real server, world backups and NPCs plus one real loopback protocol client. Test-only plugin. */
class FoliaHubSmokePlugin:JavaPlugin(),Listener {
    private lateinit var controller:HubGeneratorController
    private lateinit var hub:HubService
    private val breaks=AtomicInteger()
    private val buildingBreaks=AtomicInteger()
    private val sequence=AtomicInteger()
    private val key=net.kyori.adventure.key.Key.key("oneblock_hubs:smoke")
    override fun onEnable() {
        dataFolder.mkdirs();server.pluginManager.registerEvents(this,this)
        server.globalRegionScheduler.runDelayed(this,{
            val flow=runCatching {start()}.getOrElse {CompletableFuture.failedFuture(it)}
            flow.whenComplete {_,error->CompletableFuture.runAsync {
                if(error!=null) {logger.log(java.util.logging.Level.SEVERE,"HUB SMOKE FAILED",error);dataFolder.resolve("failed.txt").writeText(error.stackTraceToString())}
                else dataFolder.resolve(if(dataFolder.resolve("phase1.ok").exists())"phase2.ok" else "phase1.ok").writeText("PASS")
            }.whenComplete {_,_->
                if(error==null && java.lang.Boolean.getBoolean("oneblock.visual-hold") && dataFolder.resolve("phase2.ok").exists()) {
                    dataFolder.resolve("visual-ready.json").writeText(Gson().toJson(mapOf("port" to server.port,"hub" to "500/271/500","mine" to "650/270/500","local-only" to true)))
                    val deadline=System.currentTimeMillis()+60*60*1000
                    server.asyncScheduler.runAtFixedRate(this,{task->
                        if(dataFolder.resolve("visual-finish").exists() || System.currentTimeMillis()>deadline) {
                            task.cancel();server.globalRegionScheduler.execute(this) {server.shutdown()}
                        }
                    },1,1,java.util.concurrent.TimeUnit.SECONDS)
                } else server.globalRegionScheduler.execute(this) {server.shutdown()}
            }}
        },40)
    }
    /** Only an explicit loopback visual run enables these test controls; never packaged in production. */
    @EventHandler fun visual(e:PlayerCommandPreprocessEvent) {
        if(!java.lang.Boolean.getBoolean("oneblock.visual-hold") || e.player.name!="VisualReview" || !e.message.startsWith("/vshow "))return
        e.isCancelled=true;val p=e.player
        val mode=e.message.removePrefix("/vshow ")
        entity(p) {
            p.isOp=true
            when(mode) {
                "setup"->{
                    CompletableFuture.runAsync {
                        val file=server.pluginManager.getPlugin("one-block")!!.dataFolder.resolve("dungeon.yml")
                        val y=org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(file)
                        y.set("enabled",true);y.set("allow-instance-worlds",true)
                        y.set("entry.world","minecraft:overworld");y.set("entry.x",500.5);y.set("entry.y",271.0);y.set("entry.z",500.5)
                        y.set("npc.enabled",true);y.set("npc.world","minecraft:overworld");y.set("npc.x",502.5);y.set("npc.y",271.0);y.set("npc.z",500.5)
                        y.save(file)
                    }.thenCompose {entity(p) {p.performCommand("obadmin reload")}}
                }
                "day","night"->global {server.getWorld(net.kyori.adventure.key.Key.key("minecraft:overworld"))!!.time=if(mode=="day")6000 else 18000;CompletableFuture.completedFuture(Unit)}
                "encounter","boss-live","fight-view","heal"->{
                    val production=server.pluginManager.getPlugin("one-block")!!
                    val mine=production.javaClass.getDeclaredField("mine").apply {isAccessible=true}.get(production) as com.alytanta.oneblock.dungeon.MineController
                    val run=mine.binding(p.uniqueId)?:return@entity
                    when(mode) {
                        "encounter"->mine.encounters.mobs.values.filter {it.run==run.id && !it.boss}.forEach {mob->FoliaTasks.entity(this,mob.entity) {mob.entity.damage(100000.0)}}
                        "boss-live"->{
                            var flow=CompletableFuture.completedFuture(run)
                            run.settings.puzzle.forEach {lever->flow=flow.thenCompose {current->mine.engine.event(current.id,current.epoch) {com.alytanta.oneblock.dungeon.MineState.lever(it,p.uniqueId,lever)}}}
                        }
                        "fight-view"->{
                            val at=Location(p.world,86.5,65.0,16.5,-90f,25f)
                            p.teleportAsync(at);p.isInvulnerable=true
                        }
                        else->p.health=p.getAttribute(org.bukkit.attribute.Attribute.MAX_HEALTH)!!.value
                    }
                }
                "menu"->p.performCommand("menu")
                "home"->p.performCommand("home")
                "warden","weaver"->{
                    val catalog=requireNotNull(com.alytanta.oneblock.items.CustomItems.current())
                    p.inventory.armorContents=listOf("boots","leggings","chestplate","helmet").map {part->com.alytanta.oneblock.items.CustomItems.create(catalog.find("${mode}_$part")!!)}.toTypedArray()
                    listOf("warden_blade","ranger_bow","wind_bow","frost_wand","rift_staff","pickaxe_3","axe_3","hoe_3").forEachIndexed {slot,id->p.inventory.setItem(slot,com.alytanta.oneblock.items.CustomItems.create(catalog.find(id)!!))}
                    p.gameMode=GameMode.CREATIVE
                }
                "atlas"->{
                    val view=server.createInventory(null,36,net.kyori.adventure.text.Component.text("OneBlock · Огляд моделей"))
                    com.alytanta.oneblock.items.CustomItems.current()!!.items.values.forEachIndexed {i,item->view.setItem(i,com.alytanta.oneblock.items.CustomItems.create(item))}
                    p.openInventory(view)
                }
                else->{
                    val w=controller.port.resolveStrict("minecraft:overworld")
                    val (at,aim)=when(mode) {
                        "hub"->Triple(535.0,295.0,540.0) to Triple(500.0,272.0,500.0)
                        "plaza"->Triple(500.5,271.0,506.5) to Triple(500.5,274.0,491.5)
                        "npc"->Triple(502.5,271.0,503.5) to Triple(502.5,272.5,500.5)
                        "entry"->Triple(655.5,270.0,516.5) to Triple(676.0,271.0,511.0)
                        "puzzle"->Triple(694.5,270.0,513.5) to Triple(703.0,270.5,505.5)
                        "boss"->Triple(727.5,270.0,516.5) to Triple(741.5,271.0,516.5)
                        else->return@entity
                    }
                    val location=Location(w,at.first,at.second,at.third)
                    location.direction=org.bukkit.util.Vector(aim.first-at.first,aim.second-at.second-1.62,aim.third-at.third)
                    p.gameMode=GameMode.CREATIVE;p.allowFlight=true;p.isFlying=true;p.teleportAsync(location)
                }
            }
        }
    }
    private fun <T> global(action:()->CompletableFuture<T>):CompletableFuture<T> = CompletableFuture<T>().also {done->
        server.globalRegionScheduler.execute(this) {try {action().whenComplete {value,error->if(error==null)done.complete(value) else done.completeExceptionally(error)}}catch(e:Throwable){done.completeExceptionally(e)}}
    }
    private fun <T> region(w:World,p:HubPoint,action:()->T)=CompletableFuture<T>().also {done->
        server.regionScheduler.execute(this,w,p.chunkX,p.chunkZ) {try {check(Bukkit.isOwnedByCurrentRegion(w,p.chunkX,p.chunkZ));done.complete(action())}catch(e:Throwable){done.completeExceptionally(e)}}
    }
    private fun <T> entity(p:Player,action:()->T)=CompletableFuture<T>().also {done->
        if(!p.scheduler.execute(this,{try {done.complete(action())}catch(e:Throwable){done.completeExceptionally(e)}},{done.completeExceptionally(IllegalStateException("bot retired"))},1))done.completeExceptionally(IllegalStateException("bot unavailable"))
    }
    private fun await(test:()->Boolean,remaining:Int=1200):CompletableFuture<Unit> = global {
        if(test())CompletableFuture.completedFuture(Unit) else CompletableFuture<Unit>().also {done->
            check(remaining>0) {"Native smoke condition timed out"}
            server.globalRegionScheduler.runDelayed(this,{await(test,remaining-1).whenComplete {value,error->if(error==null)done.complete(value) else done.completeExceptionally(error)}},1)
        }
    }
    @EventHandler(priority=EventPriority.MONITOR,ignoreCancelled=true) fun broken(e:BlockBreakEvent) {
        if(e.player.name=="HubSmokeBot" && e.block.x==3 && e.block.y==200 && e.block.z==2) {
            breaks.incrementAndGet()
            if(::controller.isInitialized && controller.engine.all().any {it.state==HubJobState.BUILDING})buildingBreaks.incrementAndGet()
        }
    }
    private fun firstJoin(player:Player,tries:Int):CompletableFuture<Unit> = entity(player) {
        check(!player.isInvulnerable) {"First join left the player invulnerable"};hub.currentSettings().let {s->player.location.let {it.world.key().asString()==s.world && kotlin.math.abs(it.y-s.y)<1 && kotlin.math.abs(it.x-s.x)<1 && kotlin.math.abs(it.z-s.z)<1}}
    }.thenCompose {arrived->
        if(arrived)CompletableFuture.completedFuture(Unit) else {
            check(tries>0) {"First join did not reach the hub ${hub.currentSettings()}: ${player.location}"}
            val until=System.nanoTime()+500_000_000
            await({System.nanoTime()>=until}).thenCompose {firstJoin(player,tries-1)}
        }
    }
    private fun setupBot():CompletableFuture<Player> {
        return global {
            val arena=requireNotNull(server.getWorld(net.kyori.adventure.key.Key.key("minecraft:overworld")))
            region(arena,HubPoint(2,199,2)) {for(x in 0..4)for(z in 0..4)arena.getBlockAt(x,199,z).setType(Material.STONE,false);Unit}
                .thenCompose {CompletableFuture.runAsync {hub.persist(HubSettings("minecraft:overworld",false,2.5,200.0,2.5,0f,0f));dataFolder.resolve("bot-ready").writeText("775")}.thenApply {}}
                .thenCompose {await({server.getPlayerExact("HubSmokeBot")!=null})}.thenCompose {
                    val first=requireNotNull(server.getPlayerExact("HubSmokeBot"))
                    firstJoin(first,40)
                }.thenCompose {
                    val player=requireNotNull(server.getPlayerExact("HubSmokeBot"))
                    entity(player) {player.gameMode=GameMode.CREATIVE;player.allowFlight=true;player.isFlying=true;player.teleportAsync(Location(arena,2.5,200.0,2.5))}
                        .thenCompose {it}.thenCompose {ok->check(ok);entity(player) {
                            player.scheduler.runAtFixedRate(this,{
                                if(player.world===arena && player.location.distanceSquared(Location(arena,2.5,200.0,2.5))<9) {
                                    check(Bukkit.isOwnedByCurrentRegion(arena,0,0))
                                    arena.getBlockAt(3,200,2).setType(Material.STONE,false)
                                    val n=sequence.incrementAndGet()
                                    CompletableFuture.runAsync {
                                        val tmp=dataFolder.resolve("bot-mine.tmp").toPath();Files.writeString(tmp,"{\"x\":3,\"y\":200,\"z\":2,\"sequence\":$n}")
                                        Files.move(tmp,dataFolder.resolve("bot-mine.json").toPath(),StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING)
                                    }
                                }
                            },{},10,5);player
                        }}.thenCompose {p->await({breaks.get()>=2}).thenApply {p}}
                }
        }
    }
    private fun start():CompletableFuture<Unit> {
        val plugin=requireNotNull(server.pluginManager.getPlugin("one-block"))
        fun field(name:String)=plugin.javaClass.getDeclaredField(name).apply {isAccessible=true}.get(plugin)
        controller=field("hubGenerator") as HubGeneratorController;hub=field("activeHub") as HubService
        return controller.engine.ready.thenCompose {setupBot()}.thenCompose {bot->verifyCommands(bot).thenCompose {manualNpc(bot)}.thenApply {bot}}.thenCompose {bot->
            if(dataFolder.resolve("phase1.ok").exists())second(bot) else first(bot)
        }
    }
    private fun manualNpc(bot:Player):CompletableFuture<Unit> {
        val main=requireNotNull(server.pluginManager.getPlugin("one-block"))
        val fancy=FancyNpcsPlugin.get()
        val name=main.config.getString("market.npcs.shop")!!
        val second=dataFolder.resolve("phase1.ok").exists()
        fun matches()=fancy.npcManager.allNpcs.filter {it.data.name==name || it.data.id==name}
        return CompletableFuture.runAsync {hub.persist(HubSettings("minecraft:overworld",false,2.5,200.0,2.5,0f,0f))}.thenCompose {
            await({fancy.npcManager.isLoaded && (!second || matches().size==1)})
        }.thenCompose {entity(bot) {
            bot.isOp=true
            check(bot.performCommand("obadmin market npc shop"))
            check(bot.performCommand("obadmin market npc shop"))
        }}.thenCompose {await({matches().size==1})}.thenCompose {
            val npc=matches().single();val location=npc.data.location.clone()
            entity(bot) {check(bot.performCommand("obadmin market npc shop"))}.thenCompose {
                CompletableFuture<Unit>().also {done->server.globalRegionScheduler.runDelayed(this,{done.complete(Unit)},40)}
            }.thenCompose {
                global {check(matches().size==1 && matches().single().data.location==location);CompletableFuture.completedFuture(Unit)}
            }.thenCompose {
                if(!second) CompletableFuture.completedFuture(Unit) else region(location.world,HubPoint(location.blockX,location.blockY,location.blockZ)) {
                    npc.removeForAll();fancy.npcManager.removeNpc(npc);Unit
                }.thenCompose {CompletableFuture.runAsync {fancy.npcManager.saveNpcs(true)}.thenApply {Unit}}
            }
        }.thenApply {logger.info("Manual NPC command and duplicate refusal${if(second) " after restart" else ""} PASS");Unit}
    }
    private fun verifyCommands(bot:Player):CompletableFuture<Unit> = entity(bot) {
        val admin=requireNotNull(Bukkit.getPluginCommand("obadmin"))
        val player=requireNotNull(Bukkit.getPluginCommand("oneblock"))
        val wasOp=bot.isOp
        val attachment=bot.addAttachment(this)
        try {
            bot.isOp=false
            attachment.setPermission("oneblock.admin",false)
            attachment.setPermission("oneblock.command",true)
            attachment.setPermission("oneblock.trade",false)
            check(admin.tabComplete(bot,"obadmin",arrayOf("")).isEmpty())
            check(player.tabComplete(bot,"ob",arrayOf("")).none {it in setOf("admin","reload","hubgen","trade")})
            check(player.tabComplete(bot,"ob",arrayOf("admin","")).isEmpty())
            val exposed=linkedSetOf("ob","obadmin","oba","one-block:obadmin","obreload","other:help")
            server.pluginManager.callEvent(org.bukkit.event.player.PlayerCommandSendEvent(bot,exposed))
            check("ob" in exposed && "other:help" in exposed && exposed.none {it in setOf("obadmin","oba","one-block:obadmin","obreload")})
            val jobs=controller.engine.all().size
            check(player.execute(bot,"ob",arrayOf("admin","hubgen","world","create","oneblock_hubs:unauthorized_smoke")))
            check(!com.alytanta.oneblock.command.CommandAccess.allowed(bot,listOf("hub","create-world")))
            check(controller.engine.all().size==jobs)
            attachment.setPermission("oneblock.admin",true)
            attachment.setPermission("oneblock.admin.manage",true)
            check(admin.tabComplete(bot,"obadmin",arrayOf("")).isEmpty())
            check(!com.alytanta.oneblock.command.CommandAccess.allowed(bot,listOf("hub","build")))
            bot.isOp=true
            for(name in listOf("manage","reset","reload","save","teleport"))attachment.setPermission("oneblock.admin.$name",false)
            attachment.setPermission("oneblock.admin.inspect",true)
            val roots=admin.tabComplete(bot,"obadmin",arrayOf(""))
            check("players" in roots && "island" in roots && "hub" !in roots && "reload" !in roots)
            val actions=admin.tabComplete(bot,"obadmin",arrayOf("island",""))
            check("info" in actions && "set" !in actions && "tp" !in actions && "reset-world" !in actions)
            for(route in listOf("market","delete","kick","profiles","trades","hub"))check(admin.tabComplete(bot,"obadmin",arrayOf(route,"")).isEmpty())
            check(admin.execute(bot,"obadmin",arrayOf("help")))
            check(player.execute(bot,"ob",arrayOf("help")))
        } finally {bot.removeAttachment(attachment);bot.isOp=wasOp}
        logger.info("Native command access: public/admin separation, read-only permissions, legacy-route denial and dedicated root help PASS")
        Unit
    }
    private fun first(bot:Player):CompletableFuture<Unit> {
        check(runCatching {controller.port.resolveStrict("missing_world_no_fallback")}.isFailure)
        return controller.namedWorld(key.asString(),true).thenCompose {world->
            val target=HubTarget(key.asString(),world.uid,0,100,0)
            controller.engine.preview("smoke",target).thenCompose {preview->
                val before=hub.currentSettings();buildingBreaks.set(0)
                val work=controller.engine.build("smoke")
                val id=controller.engine.all().single().id
                await({controller.engine.job(id).placed>=1024 && buildingBreaks.get()>=2}).thenCompose {controller.engine.cancel(id)}.thenCompose {job->
                    check(job.state==HubJobState.PAUSED && job.placed<job.total && job.backup!=null)
                    check(hub.currentSettings()==before)
                    check(runCatching {controller.engine.build("smoke",preview.id)}.isFailure)
                    entity(bot) {bot.teleportAsync(Location(world,0.5,101.0,0.5))}.thenCompose {it}.thenCompose {moved->
                        outside(bot,world.uid).thenCompose {
                            CompletableFuture.runAsync {dataFolder.resolve("job-id").writeText(id.toString());dataFolder.resolve("phase1-details.json").writeText(Gson().toJson(mapOf("placed" to job.placed,"total" to job.total,"realBlockBreaksWhileBuilding" to buildingBreaks.get(),"asyncTeleportCancelledByEvent" to !moved,"intrusionReturned" to true)))}.thenApply {}
                        }
                    }
                }.thenCompose {work.thenApply {check(it.state==HubJobState.PAUSED);Unit}}
            }
        }
    }
    private fun second(bot:Player):CompletableFuture<Unit> {
        val id=UUID.fromString(dataFolder.resolve("job-id").readText());val job=controller.engine.job(id)
        check(job.state==HubJobState.PAUSED)
        return global {
            val loaded=server.getWorld(key)
            if(loaded!=null)CompletableFuture.completedFuture(loaded) else controller.namedWorld(key.asString(),false)
        }.thenCompose {world->
            val plan=controller.engine.plan(id);val point=plan.blocks.first().point
            region(world,point) {val block=world.getBlockAt(point.x,point.y,point.z);val old=block.blockData.asString;block.setType(Material.GOLD_BLOCK,false);old}.thenCompose {old->
                controller.engine.resume(id).thenCompose {conflicted->
                    check(conflicted.state==HubJobState.CONFLICT)
                    region(world,point) {val block=world.getBlockAt(point.x,point.y,point.z);check(block.type==Material.GOLD_BLOCK);block.setBlockData(Bukkit.createBlockData(old),false)}
                }
            }.thenCompose {
                buildingBreaks.set(0);controller.engine.resume(id)
            }.thenCompose {built->
                check(built.state==HubJobState.BUILT && buildingBreaks.get()>0)
                verifyAndCapture(plan,world).thenCompose {controller.engine.services(id,false)}
            }.thenCompose {check(it.ready);foreignNpcConflict(id)}.thenCompose {controller.engine.services(id,true)}.thenCompose {check(it.ready);controller.engine.services(id,true)}.thenCompose {
                check(it.ready)
                val fancy=FancyNpcsPlugin.get();check(fancy.npcManager.allNpcs.none {it.data.creator==id})
                controller.engine.activate(id)
            }.thenCompose {activated->
                check(activated.state==HubJobState.ACTIVE && hub.currentSettings().world==key.asString() && hub.currentSettings().y==101.0)
                controller.engine.rollback("smoke",id,false).handle {_,failure->check(failure!=null);Unit}
            }.thenCompose {
                // Leave an unrelated block outside the job, then move /hub explicitly before targeted rollback.
                region(world,HubPoint(60,100,0)) {world.getBlockAt(60,100,0).setType(Material.GOLD_BLOCK,false)}
            }.thenCompose {CompletableFuture.runAsync {hub.persist(HubSettings("minecraft:overworld",false,2.5,200.0,2.5,0f,0f))}.thenApply {}}
                .thenCompose {controller.engine.rollback("smoke",id,false)}.thenCompose {controller.engine.rollback("smoke",id,true)}.thenCompose {rolled->
                    check(rolled.state==HubJobState.ROLLED_BACK)
                    check(FancyNpcsPlugin.get().npcManager.allNpcs.none {it.data.creator==id})
                    region(world,HubPoint(60,100,0)) {check(world.getBlockAt(60,100,0).type==Material.GOLD_BLOCK)}
                }.thenCompose {verifyAir(plan,world)}.thenCompose {existingWorldBuild()}.thenCompose {
                    entity(bot) {check(bot.isOnline);Unit}
                }.thenCompose {CompletableFuture.runAsync {dataFolder.resolve("phase2-details.json").writeText(Gson().toJson(mapOf("realBlockBreaksWhileBuilding" to buildingBreaks.get(),"blocks" to plan.blocks.size,"automaticServiceNpcs" to 0,"rollback" to true)))}.thenApply {}}
        }
    }
    private fun foreignNpcConflict(id:UUID):CompletableFuture<Unit> = global {
        val main=requireNotNull(server.pluginManager.getPlugin("one-block"))
        val name=main.config.getString("market.npcs.shop")!!
        val arena=requireNotNull(server.getWorld(net.kyori.adventure.key.Key.key("minecraft:overworld")))
        val fancy=FancyNpcsPlugin.get();val original=Location(arena,4.5,200.0,4.5)
        region(arena,HubPoint(4,200,4)) {
            fancy.npcAdapter.apply(NpcData(name,UUID.randomUUID(),original)).also {it.isSaveToFile=false;it.create();fancy.npcManager.registerNpc(it);it.spawnForAll()}
        }.thenCompose {foreign->controller.engine.services(id,true).thenCompose {result->
            check(result.ready && result.details.any {it.contains("exists")})
            check(foreign.data.location==original) {"Existing foreign NPC was moved"}
            check(fancy.npcManager.allNpcs.none {it.data.creator==id}) {"Hub services created an NPC despite manual-placement policy"}
            region(arena,HubPoint(4,200,4)) {foreign.removeForAll();fancy.npcManager.removeNpc(foreign);Unit}
        }}
    }
    private fun existingWorldBuild():CompletableFuture<Unit> = global {
        val w=controller.port.resolveStrict("minecraft:overworld");val original=hub.currentSettings()
        controller.engine.preview("smoke",HubTarget(w.key().asString(),w.uid,500,270,500,90)).thenCompose {preview->
            controller.engine.build("smoke",preview.id).thenCompose {built->
                check(built.state==HubJobState.BUILT);check(hub.currentSettings()==original)
                verifyAndCapture(preview.plan,w,"built-existing-blueprint.json").thenCompose {captureMineScene(w)}.thenCompose {
                    if(java.lang.Boolean.getBoolean("oneblock.visual-hold"))controller.engine.services(built.id,true).thenCompose {controller.engine.activate(built.id)}.thenApply {Unit}
                    else CompletableFuture.completedFuture(Unit)
                }
            }
        }
    }
    /** A test-only copy of the mine, outside the hub bounds, for subsequent offline client review. */
    private fun captureMineScene(world:World):CompletableFuture<Unit> {
        val blocks=com.alytanta.oneblock.dungeon.MineLayout.blocks().map {b->
            HubBlock(HubPoint(650+b.x,205+b.y,500+b.z),b.data)
        }
        var flow=CompletableFuture.completedFuture(Unit)
        blocks.groupBy {it.point.chunkX to it.point.chunkZ}.forEach {(chunk,rows)->
            flow=flow.thenCompose {world.getChunkAtAsync(chunk.first,chunk.second,true).thenApply {Unit}}
            rows.chunked(256).forEach {batch->flow=flow.thenCompose {region(world,batch.first().point) {
                batch.forEach {b->val p=b.point;world.getBlockAt(p.x,p.y,p.z).setBlockData(Bukkit.createBlockData(b.data),false)}
            }}}
        }
        val config=com.alytanta.oneblock.dungeon.MineSettings.read(server.pluginManager.getPlugin("one-block")!!.dataFolder.resolve("dungeon.yml")).first!!
        val hints=mapOf(Triple(43,66,1) to "puzzle-clue",Triple(48,66,1) to "lever-1",Triple(52,66,1) to "lever-2",Triple(56,66,1) to "lever-3",
            Triple(48,66,4) to "lever-1",Triple(52,66,4) to "lever-2",Triple(56,66,4) to "lever-3",Triple(52,66,31) to "clue")
        hints.forEach {(p,key)->val point=HubPoint(650+p.first,205+p.second,500+p.third)
            flow=flow.thenCompose {region(world,point) {
                val sign=world.getBlockAt(point.x,point.y,point.z).state as org.bukkit.block.Sign
                for(side in org.bukkit.block.sign.Side.entries)config.text(key).split('\n').take(4).forEachIndexed {i,line->sign.getSide(side).line(i,com.alytanta.oneblock.util.Colors.colorize(line))}
                sign.isWaxed=true;check(sign.update(true,false))
            }.thenApply {Unit}}
        }
        return flow.thenCompose {CompletableFuture.runAsync {
            dataFolder.resolve("mine-visual-scene.json").writeText(Gson().toJson(blocks))
        }.thenApply {}}
    }
    private fun verifyAndCapture(plan:HubPlan,world:World,name:String="built-blueprint.json"):CompletableFuture<Unit> {
        var flow=CompletableFuture.completedFuture(Unit)
        plan.blocks.groupBy {it.point.chunkX to it.point.chunkZ}.forEach {(_,blocks)->flow=flow.thenCompose {
            region(world,blocks.first().point) {
                blocks.forEach {b->val p=b.point;check(world.getBlockAt(p.x,p.y,p.z).blockData.asString==Bukkit.createBlockData(b.data).asString) {"Incomplete/changed planned block: $p"}}
            }
        }}
        return flow.thenCompose {CompletableFuture.runAsync {dataFolder.resolve(name).writeText(Gson().toJson(plan))}.thenApply {}}
    }
    private fun outside(player:Player,world:UUID,left:Int=100):CompletableFuture<Unit> = entity(player) {player.world.uid!=world}.thenCompose {clear->
        if(clear)CompletableFuture.completedFuture(Unit) else {check(left>0) {"Intruding bot was not returned outside the reservation"};outside(player,world,left-1)}
    }
    private fun verifyAir(plan:HubPlan,world:World):CompletableFuture<Unit> {
        var flow=CompletableFuture.completedFuture(Unit)
        plan.blocks.groupBy {it.point.chunkX to it.point.chunkZ}.forEach {(_,blocks)->flow=flow.thenCompose {region(world,blocks.first().point) {
            blocks.forEach {b->val p=b.point;check(world.getBlockAt(p.x,p.y,p.z).type.isAir)}
        }}}
        return flow
    }
}
