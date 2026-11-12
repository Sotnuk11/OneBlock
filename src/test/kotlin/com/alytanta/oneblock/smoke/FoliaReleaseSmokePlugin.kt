package com.alytanta.oneblock.smoke

import com.alytanta.oneblock.OneBlock
import com.alytanta.oneblock.activities.*
import com.alytanta.oneblock.dungeon.*
import com.alytanta.oneblock.combat.*
import com.alytanta.oneblock.items.CustomItems
import com.alytanta.oneblock.market.InventoryGuard
import com.alytanta.oneblock.release.*
import net.kyori.adventure.key.Key
import org.bukkit.*
import org.bukkit.entity.*
import org.bukkit.event.*
import org.bukkit.event.block.*
import org.bukkit.event.entity.*
import org.bukkit.event.player.*
import org.bukkit.inventory.EquipmentSlot
import org.bukkit.inventory.ItemStack
import org.bukkit.plugin.java.JavaPlugin
import java.util.UUID
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicBoolean

/** 150 protocol bots, eight scripted game actors, separately counted synthetic profile writes. */
class FoliaReleaseSmokePlugin:JavaPlugin(),Listener {
    private lateinit var main:OneBlock;private lateinit var mine:MineController;private lateinit var activity:ActivityController
    private val joined=ConcurrentHashMap.newKeySet<UUID>();private val notes=CopyOnWriteArrayList<String>()
    private val samples=CopyOnWriteArrayList<Map<String,Any>>();private val running=AtomicBoolean();private val faults=CopyOnWriteArrayList<String>()
    private val targets=CopyOnWriteArrayList<Zombie>();private val clientTarget=Integer.getInteger("oneblock.smoke-clients",150)
    private var stage="boot";private var synthetic=0L;private var casts=0L
    private val windCasts=java.util.concurrent.atomic.AtomicInteger();private val spellHits=java.util.concurrent.atomic.AtomicInteger()
    private fun field(o:Any,name:String)=o.javaClass.getDeclaredField(name).apply {isAccessible=true}.get(o)
    private fun <T> CompletableFuture<T>.waitFor():T=get(90,TimeUnit.SECONDS)
    private fun await(label:String,condition:()->Boolean) {stage=label;val until=System.nanoTime()+(if(label.contains("protocol bots"))180 else 90)*1_000_000_000L;while(!condition()){check(System.nanoTime()<until){"Timeout $label"};Thread.sleep(25)}}
    private fun p(n:Int)=server.getPlayerExact("MineBot$n")!!
    private fun account(n:Int)=mine.islands.findForPlayer(p(n).uniqueId)!!
    private fun <T> entity(e:Entity,action:()->T)=CompletableFuture<T>().also {d->if(!e.scheduler.execute(this,{try {d.complete(action())}catch(t:Throwable){d.completeExceptionally(t)}},{d.completeExceptionally(IllegalStateException("retired"))},1))d.completeExceptionally(IllegalStateException("already retired"))}
    private fun note(s:String){notes+=s;logger.info("Release: $s")}
    @EventHandler fun join(e:PlayerJoinEvent){joined+=e.player.uniqueId}
    @EventHandler(priority=EventPriority.MONITOR,ignoreCancelled=true) fun launched(e:ProjectileLaunchEvent) {
        if(e.entity is WindCharge && WindSpells.marked(e.entity))windCasts.incrementAndGet()
    }
    @EventHandler(priority=EventPriority.MONITOR,ignoreCancelled=true) fun spellDamage(e:EntityDamageByEntityEvent) {
        if(e.finalDamage>0 && targets.any {it.uniqueId==e.entity.uniqueId})spellHits.incrementAndGet()
    }
    @EventHandler(priority=EventPriority.MONITOR) fun death(e:EntityDeathEvent) {
        if(e.entity.world.key().namespace()=="oneblock_dungeons" && e.entity !is Player && (e.drops.isNotEmpty() || e.droppedExp!=0))faults+="Dungeon loot escaped"
    }
    override fun onEnable(){
        dataFolder.mkdirs();server.pluginManager.registerEvents(this,this)
        server.globalRegionScheduler.runDelayed(this,{
            CompletableFuture.runAsync {
                try{
                    main=server.pluginManager.getPlugin("one-block") as OneBlock;mine=field(main,"mine") as MineController;mine.engine.ready.waitFor()
                    activity=HandlerList.getRegisteredListeners(main).map {it.listener}.filterIsInstance<ActivityController>().first()
                    setup();dataFolder.resolve("bot-ready").writeText("775");await("$clientTarget protocol bots"){joined.size==clientTarget && server.onlinePlayers.size==clientTarget}
                    val collecting=field(field(main,"trades"),"collecting") as Set<*>
                    await("login handoffs"){(1..8).none {InventoryGuard.busy(p(it).uniqueId)||p(it).uniqueId in collecting}}
                    if(dataFolder.resolve("phase1.ok").exists())second() else first()
                    val phase=if(dataFolder.resolve("phase1.ok").exists())2 else 1
                    dataFolder.resolve("release-$phase.json").writeText(com.google.gson.GsonBuilder().setPrettyPrinting().create().toJson(mapOf("assertions" to notes,"protocolBots" to joined.size,"humanClients" to 0,"graphicalClients" to 0,"scriptedGameActors" to 8,"syntheticProfiles" to 150,"targetProtocolBots" to clientTarget,"syntheticProfileMutations" to synthetic,"spellInputs" to casts,"nativeWindCasts" to windCasts.get(),"nativeSpellHits" to spellHits.get(),"samples" to samples,"faults" to faults)))
                    check(faults.isEmpty());dataFolder.resolve("phase$phase.ok").writeText("PASS")
                }catch(t:Throwable){logger.log(java.util.logging.Level.SEVERE,"RELEASE FAILED: $stage",t);dataFolder.resolve("failed.txt").writeText("$stage\n${t.stackTraceToString()}");dataFolder.resolve("partial-load.json").writeText(com.google.gson.GsonBuilder().setPrettyPrinting().create().toJson(mapOf("status" to "FAIL","stage" to stage,"samples" to samples,"spellInputs" to casts,"syntheticProfileMutations" to synthetic)))}
                finally{running.set(false);server.globalRegionScheduler.execute(this){server.shutdown()}}
            }
        },40)
    }
    private fun setup(){
        val hub=server.getWorld(Key.key("minecraft:overworld"))!!;hub.getChunkAtAsync(0,0,true).waitFor()
        mine.worlds.region(hub,0,0){for(x in 0..15)for(z in 0..15)hub.getBlockAt(x,100,z).setType(Material.STONE,false);Unit}.waitFor()
        mine.hub.persist(com.alytanta.oneblock.hub.HubSettings(hub.key().asString(),false,5.5,101.0,5.5,0f,0f))
        val f=main.dataFolder.resolve("dungeon.yml");val y=org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(f)
        mapOf("enabled" to true,"allow-instance-worlds" to true,"entry.world" to hub.key().asString(),"entry.x" to 5.5,"entry.y" to 101.0,"entry.z" to 5.5,"endless.warning-ticks" to 20,"endless.between-wave-ticks" to 1).forEach {(k,v)->y.set(k,v)}
        y.save(f);mine.settings=MineSettings.read(f).first!!
    }
    private fun prepare(n:Int,home:Boolean){
        entity(p(n)){
            val player=p(n);player.isOp=true;player.gameMode=GameMode.SURVIVAL;player.isInvulnerable=true
            val i=mine.islands.findForPlayer(player.uniqueId)?:mine.islands.createProfile(player.uniqueId,"Release scenario")!!
            mine.players.update(player.uniqueId){it.copy(activeAccount=i.owner)}
            mine.islands.releaseUpdate(i.owner){live,state->ReleaseChange(live.copy(releaseProgress=live.releaseProgress?:ReleaseProgress()),state)}
            player.closeInventory();player.inventory.setItem(0,ItemStack(Material.DIAMOND_PICKAXE));player.inventory.heldItemSlot=0
            player.teleportAsync(Location(server.getWorld(Key.key(mine.settings.entry.world)),5.5,101.0,5.5))
        }.thenCompose {it}.waitFor()
        if(home){val at=CompletableFuture<Location?>();mine.islands.prepareRespawn(account(n)){at.complete(it)};val destination=at.waitFor()!!;entity(p(n)){p(n).teleportAsync(destination)}.thenCompose {it}.waitFor()}
    }
    private fun command(n:Int,text:String){entity(p(n)){check(p(n).performCommand(text));Unit}.waitFor()}
    private fun islandAction(n:Int,action:String){entity(p(n)){activity.open(p(n));check(activity.action(p(n),action));Unit}.waitFor();await("activity mutation"){!InventoryGuard.busy(p(n).uniqueId)}}
    private fun start(group:List<Int>):UUID {
        // Admission/party commands are covered by foliaEndlessSmoke. Seed the representative
        // workload through the same production engine, without racing GUI callbacks during setup.
        val members=group.map {n->entity(p(n)){val player=p(n);player.closeInventory();MineMember(player.uniqueId,account(n).owner,player.name,player.health,player.health,player.gameMode.name)}.waitFor()}
        val run=mine.engine.create(mine.settings,members,field(main,"combatCatalog") as CombatRewardCatalog).waitFor()
        await("wave world"){mine.engine.get(run.id)?.let {it.phase==MinePhase.WAVE || it.terminal}==true}
        check(mine.engine.get(run.id)?.phase==MinePhase.WAVE) {"Seeded session aborted: ${mine.engine.get(run.id)?.reason}"};return run.id
    }
    private fun waves(id:UUID,leader:Int){
        for(n in 1L..16){
            await("wave $n"){mine.engine.get(id)?.let {r->r.phase==MinePhase.WAVE && r.wave!!.plan.number==n}==true}
            Thread.sleep(5000) // native AI, projectiles and concurrent island work run during the hold
            val deadline=System.nanoTime()+90_000_000_000L
            while((mine.engine.get(id)?.wave?.completed?:0)<n) {
              if(System.nanoTime()>=deadline) {
                val s=mine.engine.get(id)
                mine.encounters.mobs.values.filter {it.run==id}.forEach {m->logger.severe("MOB ${m.entity.type} epoch=${m.epoch} runEpoch=${s?.epoch} valid=${m.entity.isValid} dead=${m.entity.isDead} at ${m.entity.location}")}
                logger.severe("STATE phase=${s?.phase} completed=${s?.wave?.completed} plan=${s?.wave?.plan?.number} spawns=${s?.wave?.plan?.spawns?.size}")
                Thread.getAllStackTraces().filter {(t,_)->t.name.contains("Region")}.forEach {(t,s)->logger.severe("DUMP ${t.name} ${t.state}: "+s.take(14).joinToString(" < ") {f->f.className.substringAfterLast('.')+"."+f.methodName+":"+f.lineNumber})}
              }
              check(System.nanoTime()<deadline) {"Wave $n did not clear"}
              check(mine.engine.get(id)?.terminal!=true) {"Wave session aborted: ${mine.engine.get(id)?.reason}"}
              mine.encounters.mobs.values.filter {it.run==id}.forEach {m->
                // Native AI can kill a snapshot member before this cleanup runs. A retired entity is already cleared.
                CompletableFuture<Unit>().also {d->if(!m.entity.scheduler.execute(this,{
                    try {if(!m.entity.isDead)m.entity.damage(1000000.0);d.complete(Unit)}catch(t:Throwable){d.completeExceptionally(t)}
                },{d.complete(Unit)},1))d.complete(Unit)}.waitFor()
              }
              Thread.sleep(100) // Clear new batches too; natural AI can kill members before all spawns coexist.
            }
            await("durable completed wave"){mine.engine.get(id)?.wave?.completed==n}
            note("session $id durably completed wave $n")
            if(n%5==0L)command(leader,"dungeon continue")
        }
        mine.engine.abort(id,"release-load-after-16").waitFor();await("cleaned wave world"){mine.engine.get(id)==null}
    }
    private fun pad(n:Int){
        val i=account(n);val w=entity(p(n)){p(n).world}.waitFor()
        for(cx in 0..1)for(cz in -1..0){w.getChunkAtAsync(cx,cz,true).waitFor();mine.worlds.region(w,cx,cz){for(x in cx*16 until cx*16+16)for(z in cz*16 until cz*16+16)if(x in i.x+4..i.x+24 && z in i.z-8..i.z+8)w.getBlockAt(x,i.y,z).setType(Material.STONE,false);Unit}.waitFor()}
        entity(p(n)){p(n).teleportAsync(Location(w,i.x+12.5,i.y+1.0,i.z+.5))}.thenCompose {it}.waitFor()
    }
    private fun first(){
        (1..8).forEach {prepare(it,it<=3)}
        val ar=mine.islands.releaseRules!!.activities!!
        // Readiness is seeded for the concurrent load; its earned path is covered by the dedicated activity fixture.
        for(n in 1..2)mine.islands.update(account(n).owner){i->i.copy(releaseProgress=i.releaseProgress!!.copy(activity=ActivityProgress(ready=true,charge=ar.readiness)))}
        pad(2);islandAction(1,"activity-resonance");islandAction(2,"activity-echo")
        val site=account(2).releaseProgress!!.activity.event!!.site!!;val w2=entity(p(2)){p(2).world}.waitFor()
        entity(p(2)){p(2).teleportAsync(Location(w2,site.x+4.5,site.y.toDouble(),site.z+.5))}.thenCompose {it}.waitFor()
        islandAction(1,"guild-accept-0")
        pad(3);entity(p(3)){
            val player=p(3);val i=account(3);player.teleportAsync(Location(player.world,i.x+12.5,i.y+1.0,i.z+.5,0f,0f))
        }.thenCompose {it}.waitFor()
        entity(p(3)){
            val player=p(3);val catalog=mine.islands.releaseRules!!.customItems!!;player.inventory.setItemInMainHand(CustomItems.create(catalog.require("rift_staff")))
            val i=account(3)
            repeat(3){index->targets+=player.world.spawn(Location(player.world,i.x+12.5+index*.8,i.y+1.0,i.z+5.5),Zombie::class.java){m->m.setAI(false);m.setGravity(false);m.setShouldBurnInDay(false);m.canPickupItems=false;m.getAttribute(org.bukkit.attribute.Attribute.MAX_HEALTH)!!.baseValue=1024.0;m.health=1024.0}}
            Unit
        }.waitFor()
        val solo=start(listOf(4));val group=start(listOf(5,6,7,8));check(mine.engine.get(solo)!!.world!=mine.engine.get(group)!!.world)
        note("$clientTarget loopback protocol bots connected; eight active scripted actors, two islands/events, a separate caster and solo plus four-player wave sessions")
        running.set(true)
        // Own threads: a common-pool task could be run inline by this loop's own waits, which hides the whole session from sampling.
        val pool=java.util.concurrent.Executors.newCachedThreadPool {r->Thread(r,"release-waves").apply {isDaemon=true}}
        val a=CompletableFuture.runAsync({waves(solo,4)},pool);val b=CompletableFuture.runAsync({waves(group,5)},pool)
        val started=System.nanoTime();var turn=0
        while(System.nanoTime()-started<180_000_000_000L){
            entity(p(1)){
                val player=p(1);val from=player.location;val to=from.clone().apply {yaw+=10};server.pluginManager.callEvent(PlayerMoveEvent(player,from,to))
                val i=account(1);val block=player.world.getBlockAt(i.x,i.y,i.z);server.pluginManager.callEvent(BlockBreakEvent(block,player));Unit
            }.waitFor()
            entity(p(3)){
                targets.forEach {if(it.isValid && !it.isDead && it.health<300)it.health=1024.0}
                server.pluginManager.callEvent(PlayerInteractEvent(p(3),Action.RIGHT_CLICK_AIR,p(3).inventory.itemInMainHand,null,org.bukkit.block.BlockFace.SELF,EquipmentSlot.HAND));casts++;Unit
            }.waitFor()
            if(turn%32==31)activity.enemies.values.filter {it.account==account(2).owner}.forEach {e->entity(e.entity){check(Bukkit.isOwnedByCurrentRegion(p(2)));e.entity.damage(1000.0,p(2));Unit}.waitFor()}
            if(turn%2==0){
                // Synthetic legacy counter writes are explicitly separate from client input and economic producers.
                (1..150).forEach {n->val id=if(n<=clientTarget)p(n).uniqueId else UUID.nameUUIDFromBytes("release-synthetic-$n".toByteArray());com.alytanta.oneblock.storage.OperationContext.with(com.alytanta.oneblock.storage.OperationContext.Context(id,"COLLECTION_HARVEST")) {mine.players.update(id){it.copy(harvested=mapOf("release_synthetic" to turn.toLong()))}};synthetic++}
            }
            if(turn%5==0){
                val samplingStarted=System.nanoTime()
                val captures=(listOf(1,2,3,4,5,6,7,8,clientTarget)).map {n->entity(p(n)){ReleaseTelemetry.capture(p(n))}}
                val rows=captures.map {it.get(5,TimeUnit.SECONDS).invoke()}.distinctBy {it["world"] to it["regionId"]}
                val beforeFlush=ReleaseTelemetry.process(mine.storage);val flush=System.nanoTime();mine.storage.flushAsync().waitFor()
                samples+=ReleaseTelemetry.process(mine.storage)+mapOf("onlineProtocolBots" to server.onlinePlayers.size,"regions" to rows,"sampleDurationMs" to (System.nanoTime()-samplingStarted)/1e6,"queueBeforeFlush" to beforeFlush.getValue("storageQueuedTasks"),"flushBarrierMs" to (System.nanoTime()-flush)/1e6,"dungeonMobs" to mine.encounters.mobs.size,"islandEventMobs" to activity.enemies.size,"activeWaveSessions" to mine.engine.work().count {it.wave!=null && !it.terminal})
            }
            if(a.isCompletedExceptionally)a.join();if(b.isCompletedExceptionally)b.join()
            turn++;Thread.sleep(900)
        }
        a.get(300,TimeUnit.SECONDS);b.get(300,TimeUnit.SECONDS);check(server.onlinePlayers.size==clientTarget)
        check(windCasts.get()>0 && spellHits.get()>0) {"Native concurrent spells did not launch and hit: ${windCasts.get()}/${spellHits.get()}"}
        check(samples.any {it["activeWaveSessions"]==2}) {"Wave sessions seen: ${samples.map {it["activeWaveSessions"]}}"};check(samples.any {(it["islandEventMobs"] as Int)>0}) {"Event mobs seen: ${samples.map {it["islandEventMobs"]}}; event=${account(2).releaseProgress!!.activity.event}"}
        for(n in 4..8){check(mine.storage.marketState().combatReceipts.values.count {it.recipient==p(n).uniqueId}==1);check("arena:wave:16" in account(n).releaseProgress!!.facts)}
        val soloRows=mine.storage.dungeonRecords(1).waitFor();val groupRows=mine.storage.dungeonRecords(4).waitFor()
        check(soloRows.any {it.run==solo && it.wave==16L});check(groupRows.any {it.run==group && it.wave==16L})
        note("180 seconds measured load: real native AI/spells/core concurrently; separately counted synthetic profile writes and durable barriers; region TickData, heap/GC/entity and save-queue samples captured")
        note("both sessions reached wave 16 and records survived; each participant has one end-of-run receipt and the reached wave recorded; both worlds cleaned")
        targets.forEach {entity(it){it.remove();Unit}.waitFor()}
        for(n in 1..2)mine.islands.update(account(n).owner){ActivityDomain.pause(it)}
        mine.storage.flushAsync().waitFor();dataFolder.resolve("restart.txt").writeText("$solo\n$group\n${account(1).releaseProgress!!.activity.order!!.id}")
    }
    private fun second(){
        val old=dataFolder.resolve("restart.txt").readLines();check(mine.engine.work().none {!it.terminal})
        check(mine.storage.dungeonRecords(1).waitFor().any {it.run.toString()==old[0] && it.wave==16L})
        check(mine.storage.dungeonRecords(4).waitFor().any {it.run.toString()==old[1] && it.wave==16L})
        check(account(1).releaseProgress!!.activity.order!!.id.toString()==old[2])
        for(n in 4..8)check(mine.storage.marketState().combatReceipts.values.count {it.recipient==p(n).uniqueId}==1)
        mine.storage.flushAsync().waitFor();samples+=ReleaseTelemetry.process(mine.storage)
        note("second boot reconnected $clientTarget protocol bots, retained island order and exact receipts/records, and has no surviving active wave worlds")
    }
}
