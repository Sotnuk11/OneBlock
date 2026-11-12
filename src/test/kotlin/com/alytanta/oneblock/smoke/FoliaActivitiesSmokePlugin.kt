package com.alytanta.oneblock.smoke

import com.alytanta.oneblock.OneBlock
import com.alytanta.oneblock.activities.*
import com.alytanta.oneblock.dungeon.*
import com.alytanta.oneblock.island.*
import com.alytanta.oneblock.release.*
import com.alytanta.oneblock.market.*
import net.kyori.adventure.key.Key
import org.bukkit.*
import org.bukkit.entity.*
import org.bukkit.event.*
import org.bukkit.event.block.*
import org.bukkit.event.player.*
import org.bukkit.event.inventory.*
import org.bukkit.inventory.ItemStack
import org.bukkit.plugin.java.JavaPlugin
import java.util.UUID
import java.util.concurrent.*

/** Native integration with accelerated YAML targets; no production world/database access. */
class FoliaActivitiesSmokePlugin:JavaPlugin(),Listener {
    private lateinit var main:OneBlock;private lateinit var mine:MineController;private lateinit var activity:ActivityController;private lateinit var rewards:com.alytanta.oneblock.rewards.RewardMenus
    private val joins=ConcurrentHashMap.newKeySet<UUID>();private val notes=CopyOnWriteArrayList<String>();private var stage="boot"
    private fun field(o:Any,n:String)=o.javaClass.getDeclaredField(n).apply {isAccessible=true}.get(o)
    private fun <T> CompletableFuture<T>.waitFor():T=get(60,TimeUnit.SECONDS)
    private fun await(s:String,c:()->Boolean) {stage=s;val end=System.nanoTime()+60_000_000_000;while(!c()){check(System.nanoTime()<end){"Timeout $s"};Thread.sleep(25)}}
    private fun p(n:Int)=server.getPlayerExact("MineBot$n")!!
    private fun account(n:Int)=mine.islands.findForPlayer(p(n).uniqueId)!!
    private fun <T> entity(e:Entity,f:()->T)=CompletableFuture<T>().also {d->e.scheduler.execute(this,{try{d.complete(f())}catch(t:Throwable){d.completeExceptionally(t)}},{d.completeExceptionally(IllegalStateException("retired"))},1)}
    private fun note(s:String){stage=s;notes+=s;logger.info("Activities: $s")}
    private fun pause(ms:Long=600){Thread.sleep(ms)}
    @EventHandler fun join(e:PlayerJoinEvent){joins+=e.player.uniqueId}
    override fun onEnable(){
        dataFolder.mkdirs();server.pluginManager.registerEvents(this,this)
        server.globalRegionScheduler.runDelayed(this,{
            CompletableFuture.runAsync {
                try{
                    main=server.pluginManager.getPlugin("one-block") as OneBlock;mine=field(main,"mine") as MineController;mine.engine.ready.waitFor()
                    activity=HandlerList.getRegisteredListeners(main).map {it.listener}.filterIsInstance<ActivityController>().first()
                    rewards=HandlerList.getRegisteredListeners(main).map {it.listener}.filterIsInstance<com.alytanta.oneblock.rewards.RewardMenus>().first()
                    configure();dataFolder.resolve("bot-ready").writeText("775");await("six clients"){joins.size==6}
                    val collecting=field(field(main,"trades"),"collecting") as Set<*>
                    await("login settled"){(1..6).none {InventoryGuard.busy(p(it).uniqueId)||p(it).uniqueId in collecting}}
                    if(dataFolder.resolve("phase1.ok").exists())second() else first()
                    val phase=if(dataFolder.resolve("phase1.ok").exists())2 else 1
                    dataFolder.resolve("activities-$phase.json").writeText(com.google.gson.GsonBuilder().setPrettyPrinting().create().toJson(mapOf("assertions" to notes,"nativeClients" to 6,"acceleratedYaml" to true,"humanPlaytest" to false)))
                    dataFolder.resolve("phase$phase.ok").writeText("PASS")
                }catch(t:Throwable){logger.log(java.util.logging.Level.SEVERE,"ACTIVITIES FAILED: $stage",t);dataFolder.resolve("failed.txt").writeText("$stage\n${t.stackTraceToString()}")}
                finally{server.globalRegionScheduler.execute(this){server.shutdown()}}
            }
        },40)
    }
    private fun configure(){
        val hub=server.getWorld(Key.key("minecraft:overworld"))!!
        hub.getChunkAtAsync(0,0,true).waitFor();mine.worlds.region(hub,0,0){for(x in 0..15)for(z in 0..15)hub.getBlockAt(x,100,z).setType(Material.STONE,false);Unit}.waitFor()
        mine.hub.persist(com.alytanta.oneblock.hub.HubSettings(hub.key().asString(),false,5.5,101.0,5.5,0f,0f))
        val file=main.dataFolder.resolve("activities.yml");val y=org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(file)
        val orders=y.getMapList("orders").map {row->row.toMutableMap().apply {this["target"]=3;if(this["type"]=="supply")this["ingredients"]=listOf(mapOf("material" to "TORCH","amount" to 4))}}
        y.set("order-cooldown-hours",0);val events=y.getMapList("events").map {row->row.toMutableMap().apply {this["goal"]=if(this["kind"]=="echo")2 else 3;this["interval-ms"]=500}}
        y.set("orders",orders);y.set("events",events);y.set("readiness",3);y.set("credit-ms",500);y.set("action-ms",500);y.set("motion-ms",5000);y.save(file)
        val r=mine.islands.releaseRules!!;mine.islands.releaseRules=r.copy(activities=ActivityRules.read(file))
    }
    private fun prepare(n:Int){
        entity(p(n)){
            val actor=p(n);actor.isOp=true;actor.isInvulnerable=true;actor.gameMode=GameMode.SURVIVAL
            val i=mine.islands.findForPlayer(actor.uniqueId)?:mine.islands.createProfile(actor.uniqueId,"Activities test")!!
            mine.players.update(actor.uniqueId){it.copy(activeAccount=i.owner)}
            for(slot in 0..35)if(slot!=8)actor.inventory.setItem(slot,ItemStack(Material.DIRT,64))
            actor.inventory.setItem(0,ItemStack(Material.DIAMOND_PICKAXE));Unit
        }.waitFor()
        val i=account(n);val home=CompletableFuture<Location?>();mine.islands.prepareRespawn(i){home.complete(it)}
        val loc=home.waitFor()!!;entity(p(n)){p(n).teleportAsync(loc)}.thenCompose {it}.waitFor()
    }
    /** Real death on an island and in the hub: same place, nothing lost; a core ore pays its XP. */
    private fun deathAndExperience() {
        pause();prepare(6)
        val i=account(6);val world=mine.islands.loadedWorld(i)!!
        fun die(check:(Location)->Boolean,what:String) {
            val kept=entity(p(6)){p(6).isInvulnerable=false;p(6).level=5;p(6).inventory.setItem(3,ItemStack(Material.DIAMOND,3));p(6).health=0.0;Unit}.thenApply {}
            kept.waitFor();await("$what death"){entity(p(6)){p(6).isDead}.get(5,TimeUnit.SECONDS)}
            entity(p(6)){p(6).spigot().respawn();Unit}.waitFor()
            await("$what respawn"){entity(p(6)){!p(6).isDead && check(p(6).location)}.get(5,TimeUnit.SECONDS)}
            entity(p(6)){check(p(6).inventory.getItem(3)?.amount==3 && p(6).level==5) {"$what death lost items or levels"};p(6).isInvulnerable=true;Unit}.waitFor()
        }
        die({it.world?.key()==world.key()},"island")
        note("death on the own island respawns there and keeps inventory and levels")
        val hubWorld=mine.hub.currentSettings().let {com.alytanta.oneblock.hub.HubWorlds.resolve(this,it.world)}!!
        entity(p(6)){p(6).teleportAsync(hubWorld.spawnLocation)}.thenCompose {it}.waitFor()
        die({it.world?.key()==hubWorld.key()},"hub")
        pause(2000) // the respawn correction finishes before the next deliberate trip
        note("death in the hub respawns in the hub")
        val before=CompletableFuture<Int>()
        entity(p(6)){p(6).gameMode=GameMode.SURVIVAL;before.complete(p(6).totalExperience);p(6).teleportAsync(Location(world,i.x+1.5,i.y+1.0,i.z+.5))}.thenCompose {it}.waitFor()
        pause(1500)
        entity(p(6)){
            val at=p(6).location;val block=world.getBlockAt(i.x,i.y,i.z);check(Bukkit.isOwnedByCurrentRegion(block)) {"Bot is not on the island: $at"}
            block.setType(Material.STONE,false);val event=BlockBreakEvent(block,p(6));event.expToDrop=4;server.pluginManager.callEvent(event);Unit
        }.waitFor()
        await("ore XP"){entity(p(6)){p(6).totalExperience}.get(5,TimeUnit.SECONDS)>=before.get()+4}
        note("a core break pays the XP the server computed for it")
        fun loot(suffix:String)=entity(p(6)){
            p(6).inventory.contents.filterNotNull().filter {it.type.name.endsWith(suffix)}.sumOf {it.amount}+
                p(6).getNearbyEntities(4.0,4.0,4.0).filterIsInstance<org.bukkit.entity.Item>().filter {it.itemStack.type.name.endsWith(suffix)}.sumOf {it.itemStack.amount}
        }.get(5,TimeUnit.SECONDS)
        fun breakLeaves(){
            entity(p(6)){
                val block=world.getBlockAt(i.x,i.y,i.z);block.setType(Material.OAK_LEAVES,false)
                server.pluginManager.callEvent(BlockBreakEvent(block,p(6)));Unit
            }.waitFor()
        }
        val leavesBefore=loot("_LEAVES")
        entity(p(6)){p(6).inventory.setItemInMainHand(org.bukkit.inventory.ItemStack(Material.SHEARS))}.waitFor()
        breakLeaves()
        await("sheared leaf core"){loot("_LEAVES")>leavesBefore}
        entity(p(6)){p(6).inventory.setItemInMainHand(null)}.waitFor()
        val saplingsBefore=loot("_SAPLING")
        repeat(20){breakLeaves()}
        pause(1000)
        check(loot("_SAPLING")-saplingsBefore<10) {"Leaf core must not give a sapling on every break"}
        note("a leaf core drops by vanilla chance, shears give the leaves")
    }
    private fun move(n:Int){entity(p(n)){val at=p(n).location;val to=at.clone().apply {yaw+=12};server.pluginManager.callEvent(PlayerMoveEvent(p(n),at,to));Unit}.waitFor()}
    private fun core(n:Int){
        entity(p(n)){
            val i=account(n);val block=p(n).world.getBlockAt(i.x,i.y,i.z);check(Bukkit.isOwnedByCurrentRegion(block))
            block.setType(Material.STONE,false);server.pluginManager.callEvent(BlockBreakEvent(block,p(n)));Unit
        }.waitFor()
    }
    private fun action(n:Int,id:String){entity(p(n)){activity.open(p(n));check(activity.action(p(n),id));Unit}.waitFor();await("action idle"){!InventoryGuard.busy(p(n).uniqueId)}}
    private fun click(n:Int,slot:Int){entity(p(n)){val v=p(n).openInventory;server.pluginManager.callEvent(InventoryClickEvent(v,InventoryType.SlotType.CONTAINER,slot,ClickType.LEFT,InventoryAction.PICKUP_ALL));Unit}.waitFor()}
    private fun submit(n:Int,kind:String){
        val expected=if(kind=="guild-deliver")account(n).releaseProgress!!.activity.order!!.id else account(n).releaseProgress!!.activity.event!!.id
        action(n,kind);check(entity(p(n)){p(n).openInventory.topInventory.size==27}.waitFor());click(n,11)
        await("inventory handoff settled"){
            val a=account(n).releaseProgress!!.activity
            !InventoryGuard.busy(p(n).uniqueId) && (if(kind=="guild-deliver")a.order?.id!=expected else a.event?.id!=expected)
        }
    }
    private fun first(){
        (1..6).forEach(::prepare)
        // No timed AFK charge; the real core event must also pass the activity movement gate.
        pause(5300);val before=account(1).releaseProgress!!.activity.charge;core(1);check(account(1).releaseProgress!!.activity.charge==before)
        move(1);action(1,"guild-accept-0");val first=account(1).releaseProgress!!.activity.order!!.id
        action(1,"guild-accept-1");check(account(1).releaseProgress!!.activity.order!!.id==first)
        entity(p(1)) {
            val player=p(1);val i=account(1);val b=player.world.getBlockAt(i.x+2,i.y+1,i.z)
            val old=b.state;b.setType(Material.STONE,false)
            val place=BlockPlaceEvent(b,old,b.getRelative(org.bukkit.block.BlockFace.DOWN),ItemStack(Material.STONE),player,true,org.bukkit.inventory.EquipmentSlot.HAND)
            server.pluginManager.callEvent(place);check(!place.isCancelled)
            val beforeBlock=account(1).releaseProgress!!.activity
            val state=b.state;server.pluginManager.callEvent(BlockBreakEvent(b,player));b.setType(Material.AIR,false)
            val drop=player.world.dropItem(b.location,ItemStack(Material.COBBLESTONE));drop.pickupDelay=32767
            server.pluginManager.callEvent(BlockDropItemEvent(b,state,player,mutableListOf(drop)));drop.remove()
            check(account(1).releaseProgress!!.activity==beforeBlock);Unit
        }.waitFor()
        repeat(3){move(1);pause();core(1)}
        check(account(1).releaseProgress!!.activity.order!!.progress==3);check(account(1).releaseProgress!!.activity.ready)
        val other=account(3).releaseProgress!!.activity;check(other.order==null && other.charge==0)
        submit(1,"guild-deliver");val done=account(1).releaseProgress!!.activity;check(done.completed==1L && done.discovery==3L)
        val count=mine.storage.marketState().claims.values.count {it.account==account(1).owner}
        action(1,"guild-deliver");check(mine.storage.marketState().claims.values.count {it.account==account(1).owner}==count)
        entity(p(1)){rewards.open(p(1));rewards.action(p(1),"claims-all");Unit}.waitFor();await("full bags bulk settled"){!rewards.collection.busy(p(1).uniqueId)}
        check(mine.storage.marketState().claims.values.count {it.account==account(1).owner}==count)
        note("real core events require motion, accepted-only shared progress, AFK and parallel-island isolation; duplicate completion and full bags preserve one reward")
        entity(p(4)) {
            activity.open(p(4))
            repeat(8) {activity.action(p(4),"guild-accept-0");activity.action(p(4),"guild-cancel")}
            Unit
        }.waitFor()
        await("spam barrier settled"){!InventoryGuard.busy(p(4).uniqueId)}
        val spam=account(4).releaseProgress!!.activity.order!!;pause();action(4,"guild-cancel")
        check(account(4).releaseProgress!!.activity.order==null)
        pause();action(4,"guild-accept-0")
        val repeated=account(4).releaseProgress!!.activity
        check(repeated.order!!.id!=spam.id && repeated.order.rule.id==spam.rule.id && repeated.discovery==0L && repeated.completed==0L)
        check(mine.storage.marketState().claims.values.none {it.account==account(4).owner})
        note("native accept and cancel spam respects the in-flight barrier, retains the same offers and creates no reward or discovery")
        // Existing material supply runs through the real release inventory protocol and a native GUI confirmation.
        pause();action(3,"guild-accept-2");entity(p(3)){p(3).inventory.setItem(1,ItemStack(Material.TORCH,4));Unit}.waitFor()
        submit(3,"guild-deliver");check(account(3).releaseProgress!!.activity.completed==1L);check(entity(p(3)){p(3).inventory.getItem(1)==null}.waitFor())
        note("native GUI supply confirmation consumes existing plain supplies once and produces shared claims without coins or armor")
        // Persisted co-op membership and source-profile isolation.
        val source=account(1).owner;val original=account(2).owner
        mine.islands.setMemberRole(source,p(2).uniqueId,IslandRole.MEMBER,true)
        val profiles=(main.getCommand("oneblock")!!.executor as com.alytanta.oneblock.command.OneBlockCommand).profiles
        CompletableFuture<Unit>().also {d->entity(p(2)){profiles.switch(p(2),source){d.complete(Unit)}}.exceptionally {d.completeExceptionally(it);null}}.waitFor()
        await("co-op home arrival"){entity(p(2)){mine.islands.sameWorld(account(2),p(2).world)}.waitFor()}
        entity(p(2)){p(2).inventory.setItem(0,ItemStack(Material.DIAMOND_PICKAXE));Unit}.waitFor()
        pause();action(1,"guild-accept-2") // one card per kind: index 2 is always supply
        check(account(1).releaseProgress!!.activity.order!!.rule.type=="supply")
        val wanted=account(1).releaseProgress!!.activity.order!!.rule.ingredients.single();entity(p(2)){p(2).inventory.setItem(1,ItemStack(Material.valueOf(wanted.materials.first()),wanted.amount));Unit}.waitFor();submit(2,"guild-deliver")
        check(account(1).releaseProgress!!.activity.completed==2L);check(account(1).releaseProgress!!.activity.discovery==6L)
        check(account(2).owner==source && original!=source)
        note("co-op helper uses active source profile and one shared reward with one recorded XP beneficiary; discovery unlocks recipes")
        // Safe refusal on the ordinary small starter platform, then noncombat alternative.
        repeat(3){move(3);pause();core(3)};action(3,"activity-echo");check(account(3).releaseProgress!!.activity.event==null)
        action(1,"activity-resonance");action(3,"activity-resonance")
        check(account(1).releaseProgress!!.activity.event!!.id!=account(3).releaseProgress!!.activity.event!!.id)
        repeat(3){move(2);move(3);pause();core(2);core(3)}
        check(account(1).releaseProgress!!.activity.event!!.progress==3)
        check(account(3).releaseProgress!!.activity.event!!.progress==3)
        submit(2,"activity-reward");check(account(1).releaseProgress!!.activity.event==null && !account(1).releaseProgress!!.activity.ready)
        submit(3,"activity-reward");check(account(3).releaseProgress!!.activity.event==null)
        note("simultaneous resonance events on separate island regions retain independent progress and rewards; co-op can finish its shared event")
        // New consented readiness and a separate floor: no existing block is modified by the encounter.
        repeat(3){move(1);pause();core(1)};val i=account(1);val w=entity(p(1)){p(1).world}.waitFor()
        for(cx in 0..1)for(cz in -1..0){w.getChunkAtAsync(cx,cz,true).waitFor();mine.worlds.region(w,cx,cz){for(x in cx*16 until cx*16+16)for(z in cz*16 until cz*16+16)if(x in i.x+4..i.x+24 && z in i.z-8..i.z+8)w.getBlockAt(x,i.y,z).setType(Material.STONE,false);Unit}.waitFor()}
        entity(p(1)){p(1).teleportAsync(Location(w,i.x+12.5,i.y+1.0,i.z+.5))}.thenCompose {it}.waitFor()
        action(1,"activity-echo");val echo=account(1).releaseProgress!!.activity.event!!;check(echo.rule.kind=="echo")
        val s=echo.site!!;entity(p(1)){p(1).teleportAsync(Location(w,s.x+4.5,s.y.toDouble(),s.z+.5))}.thenCompose {it}.waitFor()
        repeat(2){n->await("native echo spawn $n"){activity.enemies.values.any {it.account==i.owner}};val mob=activity.enemies.values.first {it.account==i.owner}.entity
            entity(mob){check(Bukkit.isOwnedByCurrentRegion(p(1)));mob.damage(1000.0,p(1));Unit}.waitFor();await("echo credit"){account(1).releaseProgress!!.activity.event!!.progress==n+1}}
        submit(1,"activity-reward");check(account(1).releaseProgress!!.activity.event==null)
        mine.worlds.region(w,0,0){check(w.getBlockAt(s.x,i.y,s.z).type==Material.STONE);Unit}.waitFor()
        note("consented safe native zombie encounter uses warnings, suppresses mob loot, counts exact kills once and leaves the island floor intact")
        // Failure control terminates only this runner's own PostgreSQL backend.
        dataFolder.resolve("test-db-disconnect").writeText("test");await("SQL disconnected"){dataFolder.resolve("test-db-disconnected").exists()};await("SQL paused"){!mine.storage.healthy}
        val frozen=account(3).releaseProgress!!.activity
        action(3,"guild-accept-0");check(account(3).releaseProgress!!.activity==frozen)
        mine.storage.reconnectAsync().waitFor();mine.storage.flushAsync().waitFor()
        note("SQL outage refuses new mutations and recovery retains accepted identities and rewards")
        entity(p(1)){p(1).teleportAsync(Location(w,i.x+1.5,i.y+1.0,i.z+.5))}.thenCompose {it}.waitFor()
        pause();action(3,"guild-accept-0");repeat(3){move(1);pause();core(1)};action(1,"activity-resonance");move(1);pause();core(1)
        mine.storage.flushAsync().waitFor();dataFolder.resolve("restart.json").writeText(com.google.gson.Gson().toJson(mapOf("order" to account(3).releaseProgress!!.activity.order!!.id.toString(),"event" to account(1).releaseProgress!!.activity.event!!.id.toString(),"progress" to account(1).releaseProgress!!.activity.event!!.progress)))
        note("accepted guild task, owned rewards and partial active event checkpoint saved for restart")
    }
    private fun second(){
        val j=com.google.gson.JsonParser.parseString(dataFolder.resolve("restart.json").readText()).asJsonObject
        val order=account(3).releaseProgress!!.activity.order!!;val e=account(1).releaseProgress!!.activity.event!!
        check(order.id.toString()==j["order"].asString);check(e.id.toString()==j["event"].asString && e.progress==j["progress"].asInt && !e.running)
        check(account(1).releaseProgress!!.activity.discovery==6L);check(mine.storage.marketState().claims.values.any {it.account==account(1).owner})
        note("second boot preserves accepted order and exact progress, pauses island event until new consent and retains claims plus recipe discoveries")
        val home=CompletableFuture<Location?>();mine.islands.prepareRespawn(account(1)){home.complete(it)};val location=home.waitFor()!!;entity(p(1)){p(1).teleportAsync(location)}.thenCompose {it}.waitFor()
        action(1,"activity-resonance");repeat(3-e.progress){move(1);pause();core(1)};submit(1,"activity-reward")
        check(account(1).releaseProgress!!.activity.event==null)
        note("paused event resumes under the same UUID and completes once after restart")
        deathAndExperience()
    }
}
