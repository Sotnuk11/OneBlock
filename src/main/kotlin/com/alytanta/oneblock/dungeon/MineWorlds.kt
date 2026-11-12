package com.alytanta.oneblock.dungeon

import com.alytanta.oneblock.island.IslandGeneration
import net.kyori.adventure.key.Key
import net.thenextlvl.worlds.WorldsAccess
import net.thenextlvl.worlds.Level
import net.thenextlvl.worlds.generator.GeneratorType
import org.bukkit.*
import org.bukkit.plugin.Plugin
import java.nio.file.Files
import java.util.UUID
import java.util.concurrent.*

data class MineBlock(val x:Int,val y:Int,val z:Int,val data:String)
/** One fixed three-room scene. Geometry is not an extensible dungeon builder. */
object MineLayout {
    fun checkpoint(run:MineRun):MinePoint=run.wave?.let {p->run.settings.waves!!.arena(p.plan).let {it.point(it.entry,p.plan.arena)}}?:checkpoint(run.phase)
    fun inside(run:MineRun,x:Double,y:Double,z:Double)=run.wave?.let {p->run.settings.waves!!.arena(p.plan).inside(x,y,z,p.plan.arena)}?:inside(x,y,z)
    fun arenas(rules:WaveRules):List<MineBlock> = rules.arenas.flatMapIndexed {slot,a->ArenaThemes.blocks(a,slot)}
    val spawn=MinePoint(5.5,65.0,16.5,-90f)
    fun checkpoint(phase:MinePhase)=when(phase) {MinePhase.BOSS->MinePoint(77.5,65.0,16.5,-90f);MinePhase.PUZZLE->MinePoint(43.5,65.0,16.5,-90f);else->spawn}
    fun inside(x:Double,y:Double,z:Double)=x in 1.0..103.0 && y in 64.0..74.0 && z in 1.0..43.0
    fun blocks(presentation:MinePresentation=MinePresentation()):List<MineBlock> {
        val all=linkedMapOf<Triple<Int,Int,Int>,String>()
        fun put(x:Int,y:Int,z:Int,data:String) {val value=presentation.palette[data]?:data;all[Triple(x,y,z)]=if(':' in value)value else "minecraft:$value"}
        for(x in 0..104)for(z in 0..32) {
            put(x,64,z,if((x+z)%19==0)"cracked" else if(x<32)"entry-floor" else if(x<72)"puzzle-floor" else "boss-floor")
            put(x,73,z,"roof")
        }
        for(y in 65..72) {
            for(x in 0..104) {put(x,y,0,"wall");put(x,y,32,"wall")}
            for(z in 0..32)for(x in listOf(0,32,72,104))put(x,y,z,"wall")
        }
        for(x in listOf(8,24,44,60,80,96)) {
            for(y in 65..72)for(z in listOf(1,31))put(x,y,z,"log")
            for(z in 1..31)put(x,72,z,"log")
            for(z in listOf(5,27))put(x,71,z,"lantern[hanging=true]")
        }
        for(x in 5..28)put(x,65,16,"rail[shape=east_west]")
        for(x in listOf(12,20,28,48,56,84,92)) {put(x,66,1,"deepslate_iron_ore");put(x,66,31,"deepslate_gold_ore")}
        for(x in listOf(32,72))for(z in 14..18)for(y in 65..68)put(x,y,z,"iron_bars")
        // Puzzle markers are physical materials, not a color-only clue.
        for((i,x) in listOf(48,52,56).withIndex()) {
            put(x,65,8,"lever[face=floor,facing=south,powered=false]")
            put(x,64,8,listOf("rail","torch","anvil")[i].let {if(it=="rail")"iron_block" else if(it=="torch")"gold_block" else "anvil"})
            put(x,66,1,"spruce_wall_sign[facing=south,waterlogged=false]")
        }
        put(43,66,1,"spruce_wall_sign[facing=south,waterlogged=false]")
        // Secret alcove with a cracked supporting wall; opening requires its explicit interaction.
        for(x in 48..62)for(z in 33..43) {put(x,64,z,"deepslate_bricks");put(x,72,z,"deepslate_tiles")}
        for(y in 65..71) {
            for(z in 32..44)for(x in listOf(47,63))put(x,y,z,"deepslate_bricks")
            for(x in 47..63)put(x,y,44,"deepslate_bricks")
        }
        for(x in 53..55)for(y in 65..68)put(x,y,32,"cracked_deepslate_bricks")
        put(54,66,31,"stone_button[face=wall,facing=north,powered=false]")
        put(52,66,31,"spruce_wall_sign[facing=north,waterlogged=false]")
        put(55,65,40,"barrel[facing=up]");put(55,70,40,"lantern[hanging=true]")
        // Entry: loading bays and broken rails. Keep the central five-block travel lane free.
        for(x in listOf(10,18,26)) {
            for(dx in -2..2)for(z in 3..6)put(x+dx,64,z,"wood")
            for(dx in -1..1)put(x+dx,65,4,"wood")
            put(x,66,4,"iron_ore");put(x,70,3,"light")
        }
        for(x in listOf(8,24,44,60,80,96))for(z in listOf(2,3,29,30))put(x,70+if(z==3||z==29)1 else 0,z,"log")
        for((x,z) in listOf(14 to 27,17 to 26,21 to 28,26 to 25)) {
            put(x,65,z,"cracked");put(x+1,65,z,"wall");put(x,66,z,"cracked")
        }
        // Puzzle: a bright maintenance bench, physical rail/torch/anvil exhibits and guide inlays.
        for(x in 38..66)for(z in 5..10)put(x,64,z,"wood")
        for(x in 38..68 step 6) {put(x,64,13,"trim");put(x,64,19,"trim")}
        put(48,65,5,"rail[shape=east_west]");put(52,65,5,"torch");put(56,65,5,"anvil")
        for(x in listOf(48,52,56)) {
            for(y in 65..67)put(x,y,3,"wood")
            put(x,66,4,"spruce_wall_sign[facing=south,waterlogged=false]")
        }
        for(x in listOf(46,58))put(x,69,4,"light")
        // The secret's warm seam rewards observation without revealing the button from the entry.
        put(54,69,32,"light");put(51,65,31,"cracked");put(57,65,31,"cracked")
        // Boss: an open, legible seal. All detailing is in the floor or outside the combat lane.
        for(x in 75..101)for(z in 3..29) {
            val radius=(x-91)*(x-91)+(z-16)*(z-16)
            if(radius in 91..111)put(x,64,z,"trim")
        }
        for(x in listOf(76,84,98,102))for(z in listOf(2,30)) {
            for(y in 65..69)put(x,y,z,"trim");put(x,70,z,"light")
        }
        for(z in 8..24)for(y in 67..71)if(y==71 || z in listOf(8,24))put(104,y,z,"trim")
        for(z in listOf(12,20))put(104,69,z,"light")
        for(x in listOf(31,71))for(z in listOf(12,20)) {put(x,67,z,"trim");put(x,68,z,"light")}
        // Low inlays light the walking/combat plane; high wall lamps leave its centre dark.
        for(x in 8..100 step 8)for(z in listOf(12,20))put(x,64,z,"light")
        return all.map {(p,d)->MineBlock(p.first,p.second,p.third,d)}
    }
}

class MineWorlds(private val plugin:Plugin) {
    private val tickets=ConcurrentHashMap<String,MutableSet<Pair<Int,Int>>>()
    fun <T> global(action:()->CompletableFuture<T>)=CompletableFuture<T>().also {done->
        plugin.server.globalRegionScheduler.execute(plugin) {try {action().whenComplete {v,e->if(e==null)done.complete(v) else done.completeExceptionally(e)}}catch(e:Throwable){done.completeExceptionally(e)}}
    }
    fun <T> region(world:World,cx:Int,cz:Int,delay:Long=0,action:()->T)=CompletableFuture<T>().also {done->
        val task={try {check(Bukkit.isOwnedByCurrentRegion(world,cx,cz));done.complete(action())}catch(e:Throwable){done.completeExceptionally(e)}}
        if(delay==0L)plugin.server.regionScheduler.execute(plugin,world,cx,cz) {task()}
        else plugin.server.regionScheduler.runDelayed(plugin,world,cx,cz,{task()},delay)
    }
    fun world(run:MineRun):World?=plugin.server.getWorld(Key.key(run.world))
    fun prepare(run:MineRun,live:()->Boolean):CompletableFuture<World> = global {
        require(run.world==MineJournal.world(run.id) && run.settings.enabled && run.settings.allowWorlds)
        val api=WorldsAccess.access();val key=Key.key(run.world)
        check(plugin.server.getWorld(key)==null && !api.worldRegistry.isRegistered(key)) {"World identity already exists"}
        val directory=api.resolveLevelDirectory(key)
        CompletableFuture.supplyAsync {Files.exists(directory)}.thenCompose {exists->check(!exists) {"Refusing to replace existing world"}
            global {
                check(live())
                val level=Level.builder(key).generatorType(GeneratorType.FLAT.with(IslandGeneration.voidPreset())).structures(false).bonusChest(false)
                    .forcedSpawnPosition(io.papermc.paper.math.Position.block(5,65,16),null).build()
                api.create(level).thenCompose {w->global {
                    if(!api.worldRegistry.isRegistered(key))api.worldRegistry.register(level,true)
                    CompletableFuture.completedFuture(w)
                }}
            }
        }
    }.thenCompose {w->
        // Folia explicitly requires the GLOBAL region for gamerules/time/world settings.
        // Only block/chunk edits below run on their owning region schedulers.
        global {
            check(live());w.setGameRule(GameRule.DO_MOB_SPAWNING,false);w.setGameRule(GameRule.MOB_GRIEFING,false)
            w.setGameRule(GameRule.DO_MOB_LOOT,false);w.setGameRule(GameRule.DO_TILE_DROPS,false);w.setGameRule(GameRule.KEEP_INVENTORY,true)
            w.setGameRule(GameRule.DO_FIRE_TICK,false);w.setGameRule(GameRule.DO_DAYLIGHT_CYCLE,false)
            w.time=18000;w.difficulty=Difficulty.NORMAL;w.pvp=false;CompletableFuture.completedFuture(Unit)
        }.thenCompose {
            var flow=CompletableFuture.completedFuture(Unit)
            (run.settings.waves?.let(MineLayout::arenas)?:MineLayout.blocks(run.settings.appearance)).groupBy {Math.floorDiv(it.x,16) to Math.floorDiv(it.z,16)}.forEach {(chunk,blocks)->
                flow=flow.thenCompose {
                    check(live());w.getChunkAtAsync(chunk.first,chunk.second,true).thenCompose {
                        region(w,chunk.first,chunk.second) {
                            check(live());if(w.addPluginChunkTicket(chunk.first,chunk.second,plugin))tickets.computeIfAbsent(run.world) {ConcurrentHashMap.newKeySet()}.add(chunk)
                        }
                    }.thenCompose {batch(w,blocks,0,run.settings.blocksPerBatch,live)}
                }
            }
            flow.thenCompose {
                if(run.wave!=null)return@thenCompose CompletableFuture.completedFuture(w)
                val hints=mapOf(Triple(43,66,1) to run.settings.text("puzzle-clue"),Triple(48,66,1) to run.settings.text("lever-1"),
                    Triple(52,66,1) to run.settings.text("lever-2"),Triple(56,66,1) to run.settings.text("lever-3"),Triple(52,66,31) to run.settings.text("clue"),
                    Triple(48,66,4) to run.settings.text("lever-1"),Triple(52,66,4) to run.settings.text("lever-2"),Triple(56,66,4) to run.settings.text("lever-3"))
                var signs=CompletableFuture.completedFuture(Unit)
                hints.forEach {(p,text)->signs=signs.thenCompose {region(w,p.first shr 4,p.third shr 4) {
                    check(live());val sign=w.getBlockAt(p.first,p.second,p.third).state as org.bukkit.block.Sign
                    val lines=text.split('\n');listOf(org.bukkit.block.sign.Side.FRONT,org.bukkit.block.sign.Side.BACK).forEach {side->
                        lines.take(4).forEachIndexed {i,line->sign.getSide(side).line(i,com.alytanta.oneblock.util.Colors.colorize(line))}
                    };sign.isWaxed=true;check(sign.update(true,false));Unit
                }}}
                signs.thenApply {w}
            }
        }
    }
    private fun batch(w:World,blocks:List<MineBlock>,offset:Int,budget:Int,live:()->Boolean):CompletableFuture<Unit> {
        if(offset>=blocks.size)return CompletableFuture.completedFuture(Unit)
        val p=blocks[offset]
        return region(w,p.x shr 4,p.z shr 4,1) {
            check(live());blocks.drop(offset).take(budget).forEach {b->w.getBlockAt(b.x,b.y,b.z).setBlockData(Bukkit.createBlockData(b.data),false)}
        }.thenCompose {batch(w,blocks,offset+budget,budget,live)}
    }
    fun gates(run:MineRun,live:()->Boolean):CompletableFuture<Unit> {
        if(run.wave!=null)return CompletableFuture.completedFuture(Unit)
        val w=world(run)?:return CompletableFuture.completedFuture(Unit)
        var flow=CompletableFuture.completedFuture(Unit)
        for(x in listOf(32,72))for(cz in 0..1)flow=flow.thenCompose {region(w,x shr 4,cz) {
            if(!live())return@region
            val open=if(x==32)run.phase!=MinePhase.ENTRY else run.phase in setOf(MinePhase.BOSS,MinePhase.WON)
            for(z in 14..18)if(z shr 4==cz)for(y in 65..68)w.getBlockAt(x,y,z).setType(if(open)Material.AIR else Material.IRON_BARS,false)
        }}
        if(run.secretOpen)flow=flow.thenCompose {region(w,3,2) {if(live())for(x in 53..55)for(y in 65..68)w.getBlockAt(x,y,32).setType(Material.AIR,false)}}
        return flow
    }
    /** Only this session's finite, ticketed arena chunks. Removes projectiles/drops before reuse. */
    fun sweep(run:MineRun,live:()->Boolean):CompletableFuture<Unit> {
        val w=world(run)?:return CompletableFuture.failedFuture(IllegalStateException("Instance missing"))
        val tasks=tickets[run.world].orEmpty().map {(cx,cz)->region(w,cx,cz) {
            check(live());w.getChunkAt(cx,cz).entities.filter {it !is org.bukkit.entity.Player}.forEach {it.remove()};Unit
        }}
        return CompletableFuture.allOf(*tasks.toTypedArray()).thenApply {Unit}
    }
    /** Check each owning chunk; never read adjacent-region blocks from the player's scheduler. */
    fun safe(destination:Location):CompletableFuture<Boolean> {
        val w=destination.world?:return CompletableFuture.completedFuture(false)
        if(w.key().namespace()=="oneblock_dungeons" || !destination.x.isFinite() || !destination.y.isFinite() || !destination.z.isFinite())return CompletableFuture.completedFuture(false)
        val x=destination.blockX;val y=destination.blockY;val z=destination.blockZ
        if(y<=w.minHeight || y+1>=w.maxHeight)return CompletableFuture.completedFuture(false)
        return w.getChunkAtAsync(x shr 4,z shr 4,false).thenCompose {chunk->
            if(chunk==null)CompletableFuture.completedFuture(false) else region(w,x shr 4,z shr 4) {
                val floor=w.getBlockAt(x,y-1,z);val feet=w.getBlockAt(x,y,z);val head=w.getBlockAt(x,y+1,z)
                val hazards=setOf(Material.FIRE,Material.SOUL_FIRE,Material.POWDER_SNOW,Material.NETHER_PORTAL,Material.END_PORTAL,Material.END_GATEWAY,Material.WITHER_ROSE,Material.SWEET_BERRY_BUSH)
                w.worldBorder.isInside(destination) && floor.type.isSolid && floor.type !in setOf(Material.MAGMA_BLOCK,Material.CAMPFIRE,Material.SOUL_CAMPFIRE,Material.CACTUS) &&
                    feet.isPassable && head.isPassable && !feet.isLiquid && !head.isLiquid && feet.type !in hazards && head.type !in hazards
            }
        }
    }
    fun cleanup(run:MineRun):CompletableFuture<Unit> = global {
        require(run.world==MineJournal.world(run.id));val api=WorldsAccess.access();val key=Key.key(run.world)
        val loaded=plugin.server.getWorld(key);val directory=api.resolveLevelDirectory(key)
        CompletableFuture.supplyAsync {Files.isDirectory(directory)}.thenCompose {exists->
            if(loaded==null && !exists)global {api.worldRegistry.unregister(key);CompletableFuture.completedFuture(Unit)}
            else {
                val target=if(loaded!=null)CompletableFuture.completedFuture(loaded) else global {
                    if(!api.worldRegistry.isRegistered(key))api.worldRegistry.register(Level.builder(key).generatorType(GeneratorType.FLAT.with(IslandGeneration.voidPreset())).build(),true)
                    api.load(key)
                }
                target.thenCompose {w->
                    check(w.players.isEmpty()) {"Players still in instance"}
                    var release=CompletableFuture.completedFuture(Unit)
                    tickets.remove(run.world).orEmpty().forEach {(cx,cz)->release=release.thenCompose {region(w,cx,cz) {w.removePluginChunkTicket(cx,cz,plugin);Unit}}}
                    release.thenCompose {global {check(w.players.isEmpty());api.delete(w)}}.thenCompose {deleted->
                        check(deleted) {"Worlds refused instance deletion"};global {api.worldRegistry.unregister(key);CompletableFuture.completedFuture(Unit)}
                    }
                }
            }
        }
    }
}
