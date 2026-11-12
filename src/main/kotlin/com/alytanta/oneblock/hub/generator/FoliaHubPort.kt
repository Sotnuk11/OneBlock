package com.alytanta.oneblock.hub.generator

import com.alytanta.oneblock.hub.*
import net.thenextlvl.worlds.WorldsAccess
import org.bukkit.*
import org.bukkit.block.Sign
import org.bukkit.block.sign.Side
import org.bukkit.plugin.Plugin
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.atomic.AtomicInteger

class FoliaHubPort(private val plugin:Plugin,private val settings:()->HubGeneratorSettings,private val hub:HubService,
    private val npc:(HubPlan,UUID,Boolean)->CompletableFuture<HubServices>):HubBuildPort {
    fun <T> global(action:()->CompletableFuture<T>):CompletableFuture<T> = CompletableFuture<T>().also { result->
        try {plugin.server.globalRegionScheduler.execute(plugin) {
            try {action().whenComplete {value,error->if(error==null)result.complete(value) else result.completeExceptionally(error)}}
            catch(e:Throwable){result.completeExceptionally(e)}
        }}catch(e:Throwable){result.completeExceptionally(e)}
    }
    fun resolveStrict(value:String):World {
        require(value.isNotBlank()) {"An explicit world is required"}
        val matches=if(':' in value)plugin.server.worlds.filter {it.key().asString()==value} else plugin.server.worlds.filter {it.name==value}
        return requireNotNull(matches.singleOrNull()) {"Unknown, unloaded or ambiguous world: $value"}.also {
            require(!HubWorlds.island(it) && it.environment==World.Environment.NORMAL) {"Island/non-overworld target is forbidden"}
        }
    }
    private fun world(target:HubTarget)=resolveStrict(target.key).also {check(it.uid==target.worldId) {"World instance UUID changed"}}
    private fun current(w:World) {check(plugin.isEnabled && plugin.server.getWorld(w.key())===w) {"World unloaded/replaced or plugin stopped"}}
    private fun <T> region(w:World,cx:Int,cz:Int,delay:Long=0,action:()->T)=CompletableFuture<T>().also { result->
        val run={try {current(w);result.complete(action())}catch(e:Throwable){result.completeExceptionally(e)};Unit}
        try {if(delay==0L)plugin.server.regionScheduler.execute(plugin,w,cx,cz){run()}
            else plugin.server.regionScheduler.runDelayed(plugin,w,cx,cz,{run()},delay)
        }catch(e:Throwable){result.completeExceptionally(e)}
    }
    private fun <T> chunk(w:World,cx:Int,cz:Int,live:()->Boolean,action:()->CompletableFuture<T>):CompletableFuture<T> {
        val ticket=java.util.concurrent.atomic.AtomicBoolean();val done=CompletableFuture<T>()
        w.getChunkAtAsync(cx,cz,true).thenCompose {
            region(w,cx,cz) {check(live()) {"Paused"};check(w.addPluginChunkTicket(cx,cz,plugin)) {"Chunk is already owned by another hub operation"};ticket.set(true)}
        }.thenCompose {action()}.whenComplete {value,error->
            val released=if(ticket.get() && plugin.isEnabled)region(w,cx,cz) {w.removePluginChunkTicket(cx,cz,plugin);Unit} else CompletableFuture.completedFuture(Unit)
            released.whenComplete {_,cleanup->if(error!=null)done.completeExceptionally(error) else if(cleanup!=null)done.completeExceptionally(cleanup) else done.complete(value)}
        }
        return done
    }
    private fun <T> groups(items:List<T>,action:(T)->CompletableFuture<Unit>):CompletableFuture<Unit> {
        val next=AtomicInteger()
        fun worker():CompletableFuture<Unit> {val n=next.getAndIncrement();return if(n>=items.size)CompletableFuture.completedFuture(Unit) else action(items[n]).thenCompose {worker()}}
        return CompletableFuture.allOf(*List(settings().inFlight) {worker()}.toTypedArray()).thenApply {}
    }
    override fun validate(target:HubTarget)=global {
        require(net.kyori.adventure.key.Key.key(target.key).namespace()!="oneblock_dungeons") {"Temporary dungeon worlds cannot host a persistent hub"}
        val w=world(target);require(target.rotation in setOf(0,90,180,270))
        require(target.y.toLong()-17>=w.minHeight && target.y.toLong()+24<w.maxHeight) {"Hub exceeds world build height"}
        val center=Location(w,target.x.toDouble(),target.y.toDouble(),target.z.toDouble())
        // Border properties are region-owned, just like the later block accesses.
        region(w,Math.floorDiv(target.x,16),Math.floorDiv(target.z,16)) {
            listOf(-41,41).forEach {x->listOf(-41,41).forEach {z->require(w.worldBorder.isInside(center.clone().add(x.toDouble(),0.0,z.toDouble()))) {"Hub exceeds world border"}}};Unit
        }
    }
    private fun entities(w:World,cx:Int,cz:Int,t:HubTarget) {
        check(w.getChunkAt(cx,cz).entities.none {e->val p=e.location;t.contains(p.x,p.y,p.z,1)}) {"Entity/player occupies reserved hub area in chunk $cx/$cz"}
    }
    private fun expected(block:HubBlock)=Bukkit.createBlockData(block.data).asString
    private fun matches(w:World,b:HubBlock,data:String):Boolean {
        val p=b.point;val actual=w.getBlockAt(p.x,p.y,p.z)
        if(actual.blockData.asString!=data)return false
        if(b.lines!=null) {
            val sign=actual.state as? Sign ?: return false
            return sign.isWaxed && listOf(Side.FRONT,Side.BACK).all {side->
                val face=sign.getSide(side)
                face.color==DyeColor.BLACK && !face.isGlowingText && face.lines()==b.lines.map {Component.text(it)}
            }
        }
        return true
    }
    override fun scan(plan:HubPlan,existing:Boolean,rollback:Boolean,live:()->Boolean)=global {
        val w=world(plan.target);val t=plan.target;val byPoint=plan.blocks.associateBy {it.point}
        val canonical=plan.blocks.distinctBy {it.data}.associate {it.data to expected(it)}
        val chunks=(Math.floorDiv(t.x-41,16)..Math.floorDiv(t.x+41,16)).flatMap {x->(Math.floorDiv(t.z-41,16)..Math.floorDiv(t.z+41,16)).map {x to it}}
        groups(chunks) { (cx,cz)->chunk(w,cx,cz,live) {
            val points=if(rollback)plan.blocks.map {it.point}.filter {it.chunkX==cx && it.chunkZ==cz} else
                (maxOf(t.x-41,cx*16)..minOf(t.x+41,cx*16+15)).flatMap {x->(maxOf(t.z-41,cz*16)..minOf(t.z+41,cz*16+15)).flatMap {z->(t.y-17..t.y+23).map {y->HubPoint(x,y,z)}}}
            fun visit(offset:Int):CompletableFuture<Unit> = region(w,cx,cz,if(offset==0)0 else settings().delayTicks) {
                check(live()) {"Paused"};entities(w,cx,cz,t)
                val end=minOf(points.size,offset+settings().scanPerTick)
                for(i in offset until end) {val p=points[i];val block=w.getBlockAt(p.x,p.y,p.z);val intended=byPoint[p]
                    check(block.type.isAir || existing && intended!=null && matches(w,intended,canonical.getValue(intended.data))) {"Occupied/modified block at ${p.x}/${p.y}/${p.z}: ${block.type}"}
                };end
            }.thenCompose {end->if(end<points.size)visit(end) else CompletableFuture.completedFuture(Unit)}
            visit(0)
        }}
    }
    override fun edit(plan:HubPlan,rollback:Boolean,live:()->Boolean,intent:(Int)->CompletableFuture<Unit>,progress:(Int)->CompletableFuture<Unit>)=global {
        val w=world(plan.target);val canonical=plan.blocks.distinctBy {it.data}.associate {it.data to expected(it)};val count=AtomicInteger()
        val chunks=plan.blocks.groupBy {it.point.chunkX to it.point.chunkZ}.entries.toList()
        groups(chunks) { (key,blocks)->val (cx,cz)=key;chunk(w,cx,cz,live) {
            fun visit(offset:Int):CompletableFuture<Unit> {
                val end=minOf(blocks.size,offset+settings().batchSize)
                return intent(count.get()+end-offset).thenCompose {
                    region(w,cx,cz,settings().delayTicks) {
                        check(live()) {"Paused"};entities(w,cx,cz,plan.target)
                        // Validate the entire batch before any mutation; a later batch still rechecks every position.
                        for(i in offset until end) {val b=blocks[i];val p=b.point;val block=w.getBlockAt(p.x,p.y,p.z)
                            check(block.type.isAir || matches(w,b,canonical.getValue(b.data))) {"Conflict at ${p.x}/${p.y}/${p.z}"}
                        }
                        for(i in offset until end) {
                            check(live()) {"Paused"};val b=blocks[i];val p=b.point;val block=w.getBlockAt(p.x,p.y,p.z)
                            if(rollback) {if(!block.type.isAir)block.setType(Material.AIR,false)}
                            else if(block.type.isAir) {
                                block.setBlockData(Bukkit.createBlockData(b.data),false)
                                if(b.lines!=null) {val sign=block.state as Sign
                                    listOf(Side.FRONT,Side.BACK).forEach {side->val face=sign.getSide(side);face.color=DyeColor.BLACK;face.isGlowingText=false;b.lines.forEachIndexed {index,line->face.line(index,Component.text(line))}}
                                    sign.isWaxed=true;check(sign.update(true,false)) {"Could not save sign at $p"}
                                }
                            }
                        };count.addAndGet(end-offset)
                    }
                }.thenCompose {done->progress(done)}.thenCompose {if(end<blocks.size)visit(end) else CompletableFuture.completedFuture(Unit)}
            };visit(0)
        }}
    }
    override fun backup(target:HubTarget,id:UUID)=global {val w=world(target);WorldsAccess.access().save(w,true).thenCompose {saved->
        check(saved) {"World save failed before backup"};global {WorldsAccess.access().createBackup(w,"oneblock-hub-$id")}
    }.thenApply {it.name()} }
    override fun save(target:HubTarget)=global {WorldsAccess.access().save(world(target),true).thenApply {check(it) {"Final world save refused"};Unit}}
    override fun verifyBackup(target:HubTarget,name:String)=global {
        world(target);WorldsAccess.access().backupProvider.findBackup(net.kyori.adventure.key.Key.key(target.key),name).thenApply {
            check(it.isPresent) {"Job backup is missing; restore it before continuing"};Unit
        }
    }
    override fun services(plan:HubPlan,id:UUID,apply:Boolean)=global {world(plan.target);npc(plan,id,apply)}
    override fun verify(plan:HubPlan):CompletableFuture<Unit> = global {
        val w=world(plan.target)
        groups(plan.anchors.filter {it.role in setOf("spawn","merchant","bazaar","auction","guide","workshop")}) {anchor->
            val p=anchor.point;chunk(w,p.chunkX,p.chunkZ,{plugin.isEnabled}) {region(w,p.chunkX,p.chunkZ) {
                require(w.getBlockAt(p.x,p.y-1,p.z).type.isSolid && w.getBlockAt(p.x,p.y,p.z).type.isAir && w.getBlockAt(p.x,p.y+1,p.z).type.isAir) {"Unsafe service/spawn anchor: ${anchor.role}"};Unit
            }}
        }
    }
    override fun activate(plan:HubPlan)=verify(plan).thenCompose {CompletableFuture.runAsync {
        val p=plan.anchors.single {it.role=="spawn"};hub.persist(HubSettings(plan.target.key,false,p.point.x+0.5,p.point.y.toDouble(),p.point.z+0.5,p.yaw,0f))
    }.thenApply {}}
    override fun removeServices(plan:HubPlan,id:UUID,required:Boolean):CompletableFuture<Unit> {
        if(!required)return CompletableFuture.completedFuture(Unit)
        return global {
            check(plugin.server.pluginManager.isPluginEnabled("FancyNpcs")) {"Enable FancyNpcs to verify/remove this job's owned NPCs before rollback"}
            HubNpcServices.removeOwned(plugin,plan,id)
        }
    }
    override fun canRollback(target:HubTarget):CompletableFuture<Unit> = CompletableFuture<Unit>().also {done->
        hub.resolve {destination->
            if(destination?.world?.uid==target.worldId && target.contains(destination.x,destination.y,destination.z))
                done.completeExceptionally(IllegalStateException("Move /hub elsewhere with sethub before rollback"))
            else done.complete(Unit)
        }
    }
}
