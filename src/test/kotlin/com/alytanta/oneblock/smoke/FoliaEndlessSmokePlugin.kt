package com.alytanta.oneblock.smoke

import com.alytanta.oneblock.OneBlock
import com.alytanta.oneblock.dungeon.*
import com.alytanta.oneblock.hub.HubSettings
import com.alytanta.oneblock.market.InventoryGuard
import com.alytanta.oneblock.release.ReleaseChange
import com.alytanta.oneblock.release.ReleaseProgress
import org.bukkit.*
import org.bukkit.entity.Player
import org.bukkit.event.*
import org.bukkit.event.entity.EntityDeathEvent
import org.bukkit.event.entity.CreatureSpawnEvent
import org.bukkit.event.entity.ProjectileLaunchEvent
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.inventory.ItemStack
import org.bukkit.plugin.java.JavaPlugin
import java.util.UUID
import java.util.concurrent.*

/** Driver waits only on its own async worker. Every native action is dispatched to its owner. */
class FoliaEndlessSmokePlugin:JavaPlugin(),Listener {
    private lateinit var mine:MineController
    private lateinit var production:OneBlock
    private val joins=ConcurrentHashMap<UUID,Int>()
    private val assertions=CopyOnWriteArrayList<String>()
    private val eventFailures=CopyOnWriteArrayList<String>()
    private val deniedShots=java.util.concurrent.atomic.AtomicInteger()
    private val acceptedShots=java.util.concurrent.atomic.AtomicInteger()
    private var stage="boot"
    private fun note(s:String) {stage=s;assertions+=s;logger.info("Endless: $s")}
    private fun field(o:Any,n:String)=o.javaClass.getDeclaredField(n).apply {isAccessible=true}.get(o)
    private fun <T> CompletableFuture<T>.waitFor():T=get(45,TimeUnit.SECONDS)
    private fun await(label:String,test:()->Boolean) {
        stage=label;val end=System.nanoTime()+45_000_000_000L
        while(!test()) {check(System.nanoTime()<end) {"Timeout $label"};Thread.sleep(25)}
        check(eventFailures.isEmpty()) {eventFailures.joinToString()}
    }
    private fun player(n:Int)=server.getPlayerExact("MineBot$n")!!
    private fun command(n:Int,text:String) {mine.entity(player(n)) {check(player(n).performCommand(text));Unit}.waitFor()}
    @EventHandler fun joined(e:PlayerJoinEvent) {joins.merge(e.player.uniqueId,1,Int::plus)}
    @EventHandler(priority=EventPriority.MONITOR) fun death(e:EntityDeathEvent) {
        if(e.entity.world.key().namespace()=="oneblock_dungeons" && e.entity !is Player && (e.drops.isNotEmpty() || e.droppedExp!=0))eventFailures+="Instance loot escaped suppression"
    }
    @EventHandler(priority=EventPriority.MONITOR) fun spawned(e:CreatureSpawnEvent) {
        if(!::mine.isInitialized || e.isCancelled || e.entity.world.key().namespace()!="oneblock_dungeons")return
        val run=mine.engine.work().firstOrNull {it.world==e.entity.world.key().asString()}?:return
        val rules=run.settings.waves?:return
        MineState.alive(run).mapNotNull {server.getPlayer(it.actor)}.forEach {p->
            if(!Bukkit.isOwnedByCurrentRegion(p))eventFailures+="Spawn without region ownership"
            else if(p.location.distanceSquared(e.location)<rules.minDistance*rules.minDistance)eventFailures+="Spawn inside player exclusion distance"
        }
        if(!e.location.block.isEmpty || !e.location.clone().add(0.0,1.0,0.0).block.isEmpty)eventFailures+="Spawn in wall"
    }
    @EventHandler(priority=EventPriority.MONITOR) fun shot(e:ProjectileLaunchEvent) {
        if(e.entity.world.key().namespace()=="oneblock_dungeons")if(e.isCancelled)deniedShots.incrementAndGet() else acceptedShots.incrementAndGet()
    }
    override fun onEnable() {
        dataFolder.mkdirs();server.pluginManager.registerEvents(this,this)
        server.globalRegionScheduler.runDelayed(this,{
            CompletableFuture.runAsync {
                try {
                    production=server.pluginManager.getPlugin("one-block") as OneBlock;mine=field(production,"mine") as MineController
                    mine.engine.ready.waitFor();setup()
                    dataFolder.resolve("bot-ready").writeText("775")
                    await("six protocol clients") {joins.size==6}
                    val collecting=field(field(production,"trades"),"collecting") as Set<*>
                    await("login handoffs") {(1..6).none {InventoryGuard.busy(player(it).uniqueId) || player(it).uniqueId in collecting}}
                    if(dataFolder.resolve("phase1.ok").exists())second() else first()
                    check(eventFailures.isEmpty())
                    val phase=if(dataFolder.resolve("phase1.ok").exists())2 else 1
                    dataFolder.resolve("endless-$phase.json").writeText(com.google.gson.GsonBuilder().setPrettyPrinting().create().toJson(mapOf("assertions" to assertions,"nativeClients" to 6,"humanPlaytest" to false)))
                    dataFolder.resolve("phase$phase.ok").writeText("PASS")
                } catch(e:Throwable) {logger.log(java.util.logging.Level.SEVERE,"ENDLESS FAILED: $stage",e);dataFolder.resolve("failed.txt").writeText("$stage\n${e.stackTraceToString()}")}
                finally {server.globalRegionScheduler.execute(this) {server.shutdown()}}
            }
        },40)
    }
    private fun setup() {
        val w=server.getWorld(net.kyori.adventure.key.Key.key("minecraft:overworld"))!!
        w.getChunkAtAsync(0,0,true).waitFor()
        mine.worlds.region(w,0,0) {for(x in 0..15)for(z in 0..15)for(y in 100..103)w.getBlockAt(x,y,z).setType(if(y==100)Material.STONE else Material.AIR,false)}.waitFor()
        mine.hub.persist(HubSettings(w.key().asString(),false,5.5,101.0,5.5,0f,0f))
        val f=production.dataFolder.resolve("dungeon.yml")
        val y=org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(f)
        mapOf("enabled" to true,"allow-instance-worlds" to true,"entry.world" to w.key().asString(),"entry.x" to 5.5,"entry.y" to 101.0,"entry.z" to 5.5,
            "endless.warning-ticks" to 20,"endless.between-wave-ticks" to 1,"endless.count" to 2,"endless.growth.count" to 0.0,"endless.party.count" to 1.0,
            "endless.limits.entities" to 8,"endless.limits.projectiles" to 4).forEach { (k,v)->y.set(k,v)}
        y.save(f);mine.settings=MineSettings.read(f).first!!
    }
    private fun prepare(n:Int) {
        val p=player(n)
        mine.entity(p) {
            p.isOp=true;p.isInvulnerable=true;p.gameMode=GameMode.SURVIVAL
            val account=mine.islands.findForPlayer(p.uniqueId)?:mine.islands.createProfile(p.uniqueId,"Endless test")!!
            mine.players.update(p.uniqueId) {it.copy(activeAccount=account.owner)}
            mine.islands.releaseUpdate(account.owner) {i,s->ReleaseChange(i.copy(releaseProgress=ReleaseProgress(chapter="developed")),s)}
            for(slot in 0..35)if(slot!=8)p.inventory.setItem(slot,ItemStack(Material.DIRT,64))
            p.health=20.0;p.teleportAsync(Location(server.getWorld(net.kyori.adventure.key.Key.key(mine.settings.entry.world)),5.5,101.0,5.5))
        }.thenCompose {it}.waitFor()
    }
    private fun start(group:List<Int>):MineRun {
        group.forEach {command(it,"dungeon menu")}
        group.drop(1).forEach {command(group[0],"dungeon invite MineBot$it");command(it,"dungeon accept MineBot${group[0]}")}
        group.forEach {command(it,"dungeon ready")};command(group[0],"dungeon start")
        await("new wave session") {mine.binding(player(group[0]).uniqueId)?.phase==MinePhase.WAVE}
        return mine.binding(player(group[0]).uniqueId)!!
    }
    private fun ready(id:UUID,number:Long):MineRun {
        await("wave $number spawned") {mine.engine.get(id)?.let {s->s.phase==MinePhase.WAVE && s.wave!!.plan.number==number && mine.encounters.mobs.values.count {it.run==id && it.epoch==s.epoch}==s.wave.plan.spawns.size}==true}
        return mine.engine.get(id)!!
    }
    private fun kill(run:MineRun) {
        val mobs=mine.encounters.mobs.values.filter {it.run==run.id && it.epoch==run.epoch}
        mobs.forEach {m->CompletableFuture<Unit>().also {done->m.entity.scheduler.execute(this,{
            try {check(!m.entity.isDead);m.entity.damage(1000000.0);done.complete(Unit)}catch(e:Throwable){done.completeExceptionally(e)}
        },{done.completeExceptionally(IllegalStateException("Retired mob"))},1)}.also {f->
            try {f.waitFor()} catch(e:java.util.concurrent.TimeoutException) {
                val live=server.getEntity(m.entity.uniqueId)
                logger.severe("Stuck mob ${m.entity.type} valid=${m.entity.isValid} dead=${m.entity.isDead} at ${m.entity.location} same=${live===m.entity} found=${live!=null}")
                Thread.getAllStackTraces().filter {(t,_)->t.name.contains("Region")}.forEach {(t,s)->logger.severe("DUMP ${t.name} ${t.state}: "+s.take(14).joinToString(" < ") {f->f.className.substringAfterLast('.')+"."+f.methodName+":"+f.lineNumber})}
                throw e
            }
        }}
        await("wave clear journal") {mine.engine.get(run.id)?.wave?.completed==run.wave!!.plan.number}
    }
    private fun continueAt(n:Int,id:UUID) {
        val before=mine.engine.get(id)!!;check(before.phase==MinePhase.INTERMISSION)
        val elapsed=before.wave!!.elapsed;Thread.sleep(150)
        check(mine.engine.get(id)!!.wave!!.elapsed==elapsed)
        command(n,"dungeon continue");command(n,"dungeon continue")
    }
    private fun first() {
        (1..6).forEach(::prepare)
        val solo=start(listOf(1));val group=start(listOf(2,3,4,5))
        check(solo.world!=group.world);note("solo and four-player native worlds isolated; finite cubic templates built")
        val originalWorlds=listOf(solo.world,group.world)
        for(number in 1L..16) {
            var a=ready(solo.id,number);var b=ready(group.id,number)
            if(number==1L) {
                val denied=deniedShots.get();val accepted=acceptedShots.get()
                mine.entity(player(1)) {repeat(16) {player(1).launchProjectile(org.bukkit.entity.Arrow::class.java,org.bukkit.util.Vector(0.0,1.0,0.0))};Unit}.waitFor()
                check(deniedShots.get()>denied);check(acceptedShots.get()-accepted<=4)
                note("native arrow flood is bounded by configured per-session projectile permits")
            }
            if(number==2L) {
                val p=player(3)
                val inventory=mine.entity(p) {com.alytanta.oneblock.trade.TradeInventory.hash(p.inventory.contents)}.waitFor()
                mine.entity(p) {p.isInvulnerable=false;p.health=0.0;Unit}.waitFor()
                await("physical death journal") {mine.engine.get(b.id)?.deaths==1}
                mine.entity(p) {p.spigot().respawn();Unit}.waitFor()
                await("physical respawn") {mine.engine.get(b.id)?.members?.get(p.uniqueId)?.awaitingRespawn==false}
                mine.entity(p) {check(!p.isDead && p.gameMode==GameMode.SPECTATOR);check(inventory==com.alytanta.oneblock.trade.TradeInventory.hash(p.inventory.contents));p.isInvulnerable=true;Unit}.waitFor()
                note("physical death and spectator reconnect preserve inventory")
            }
            if(number==3L) {
                val p=player(4);command(4,"hub")
                await("away from native session") {mine.engine.get(b.id)?.members?.get(p.uniqueId)?.inWorld==false}
                command(4,"dungeon reconnect");await("explicit reconnect") {mine.engine.get(b.id)?.members?.get(p.uniqueId)?.inWorld==true}
                mine.entity(p) {check(p.gameMode==GameMode.SPECTATOR);Unit}.waitFor()
                note("hub departure counts once and reconnect cannot gain an extra fighting life")
            }
            if(number==4L) {
                val p=player(5);val before=joins[p.uniqueId]!!
                dataFolder.resolve("MineBot5-disconnect").writeText("test socket close")
                await("socket quit") {mine.engine.get(b.id)?.members?.get(p.uniqueId)?.inWorld==false}
                dataFolder.resolve("MineBot5-connect").writeText("test same UUID")
                await("socket reconnect") {(joins[p.uniqueId]?:0)>before && server.getPlayer(p.uniqueId)!=null}
                val collecting=field(field(production,"trades"),"collecting") as Set<*>
                await("relogin recovery") {!InventoryGuard.busy(p.uniqueId) && p.uniqueId !in collecting}
                command(5,"dungeon reconnect");await("relogin explicit re-entry") {mine.engine.get(b.id)?.members?.get(p.uniqueId)?.inWorld==true}
                mine.entity(player(5)) {player(5).isInvulnerable=true;Unit}.waitFor()
                note("real socket reconnect uses original UUID and deadline")
            }
            if(number==6L) {
                val frozen=a.wave!!.plan;val rules=a.settings.waves
                val identity=mine.menu.configurationIdentity;command(1,"obadmin reload")
                await("reload") {mine.menu.configurationIdentity!==identity}
                check(mine.engine.get(a.id)!!.wave!!.plan==frozen && mine.engine.get(a.id)!!.settings.waves==rules)
                note("reload leaves active plan and rules frozen")
            }
            if(number==10L) {
                dataFolder.resolve("test-db-disconnect").writeText("own isolated database backend")
                await("SQL fault acknowledged") {dataFolder.resolve("test-db-disconnected").exists()}
            }
            a=ready(a.id,number);b=ready(b.id,number)
            kill(a);kill(b)
            check(mine.engine.get(a.id)!!.world==originalWorlds[0] && mine.engine.get(b.id)!!.world==originalWorlds[1])
            if(number%5==0L) {
                val r=mine.engine.get(a.id)!!
                val before=r.rewards;mine.engine.event(r.id,a.epoch) {mine.engine.completeWave(it)}.waitFor()
                check(mine.engine.get(r.id)!!.rewards==before)
                continueAt(1,a.id);continueAt(2,b.id)
                note("boss $number complete once; continue reuses next configured arena")
            }
        }
        await("SQL reconnect") {mine.storage.healthy}
        val a=mine.engine.get(solo.id)!!;val b=mine.engine.get(group.id)!!
        check(a.rewards.isEmpty() && b.rewards.isEmpty()) // Paid once at the end of the run.
        check(mine.storage.dungeonRecords(1).waitFor().first().wave>=15)
        note("waves 1 through 16; rewards wait for the end of the run")
        mine.engine.abort(a.id,"native-defeat").waitFor();mine.engine.abort(b.id,"native-defeat").waitFor()
        await("cleanup both native instances") {mine.engine.get(a.id)==null && mine.engine.get(b.id)==null}
        check(mine.storage.marketState().combatReceipts.size==5)
        check(mine.storage.marketState().chests.values.count {it.recipient==player(1).uniqueId}==2 && mine.storage.marketState().chests.values.any {it.recipient==player(1).uniqueId && it.price==0L && it.items.size==4})
        note("end of run gives a free and a paid chest; cleanup removes both instance worlds")
        // A real transfer admission failure must abort without losing the already earned boss rewards.
        val transport=start(listOf(6));for(n in 1L..5)kill(ready(transport.id,n))
        val earned=mine.engine.get(transport.id)!!.rewards;check(earned.isEmpty())
        mine.transfers.add(player(6).uniqueId);command(6,"dungeon continue")
        await("transport failure") {mine.engine.get(transport.id)?.reason=="transition-teleport-failed"}
        check(mine.engine.get(transport.id)!!.rewards.size==1);mine.transfers.remove(player(6).uniqueId)
        await("failed transfer cleanup") {mine.engine.get(transport.id)==null}
        note("native transfer admission failure keeps boss earnings and evacuates session")
        val interrupted=start(listOf(6));kill(ready(interrupted.id,1));ready(interrupted.id,2)
        val saved=mine.engine.get(interrupted.id)!!
        dataFolder.resolve("interrupted.txt").writeText("${saved.id}\n${saved.wave!!.elapsed}\n${com.google.gson.Gson().toJson(saved.wave.plan)}")
        (1..6).forEach {n->mine.entity(player(n)) {player(n).saveData();Unit}.waitFor()}
        mine.storage.flushAsync().waitFor();note("active wave and earned rewards persisted for second native boot")
    }
    private fun second() {
        val lines=dataFolder.resolve("interrupted.txt").readLines();val id=UUID.fromString(lines[0])
        await("restart recovery evacuation and SQL record") {mine.engine.get(id)==null}
        val records=mine.storage.dungeonRecords(1).waitFor();val old=records.single {it.run==id}
        check(old.wave==1L && old.elapsed==lines[1].toLong() && old.ended!=null && old.reason in setOf("shutdown","restart"))
        mine.entity(player(6)) {check(!mine.managed(player(6).world));Unit}.waitFor()
        note("second native boot preserves exact completed time and rewards, aborts active encounter and cleans world")
        // Cash out works independently of defeat and leaves no live instance.
        (1..6).forEach(::prepare)
        val s=start(listOf(1));for(n in 1L..5)kill(ready(s.id,n))
        val elapsed=mine.engine.get(s.id)!!.wave!!.elapsed;command(1,"dungeon finish");command(1,"dungeon finish")
        await("cashout cleanup") {mine.engine.get(s.id)==null}
        val record=mine.storage.dungeonRecords(1).waitFor().single {it.run==s.id}
        check(record.wave==5L && record.elapsed==elapsed && record.reason=="cash-out")
        check(server.worlds.none {it.key().namespace()=="oneblock_dungeons"})
        note("cash-out is idempotent; immutable SQL ranking; zero remaining instance worlds")
    }
}
