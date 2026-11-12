package com.alytanta.oneblock.oneblock

import com.alytanta.oneblock.island.Island
import com.alytanta.oneblock.menu.MenuFixture.Companion.stub
import com.alytanta.oneblock.menu.Stack
import io.papermc.paper.threadedregions.scheduler.*
import org.bukkit.*
import org.bukkit.entity.Item
import org.bukkit.event.entity.ItemSpawnEvent
import org.bukkit.plugin.Plugin
import org.bukkit.util.BoundingBox
import org.bukkit.util.Vector
import org.bukkit.configuration.file.YamlConfiguration
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.function.Consumer
import java.util.logging.Logger

class CoreItemGatheringTest {
    private class Fixture {
        private val id=UUID.randomUUID()
        private val queue=ArrayDeque<Pair<String,Runnable>>()
        var thread="test"
        var chunkLoaded=true
        var valid=true
        var dead=false
        var queries=0
        var teleports=0
        var velocity=Vector(0.4,-0.8,0.2)
        var position: Location
        var worldOfItem: World
        var teleportResult=true
        val stack=Stack(Material.DIAMOND,7,tag="custom")
        val owner=UUID.randomUUID()
        private val repeat=mutableListOf<Pair<Consumer<ScheduledTask>,Task>>()
        private class Task {
            var cancelled=false
            val handle=stub(ScheduledTask::class.java) { name,_-> if(name=="cancel") { cancelled=true;null } else null }
        }
        val world: World=stub(World::class.java) { name,args -> when(name) {
            "getUID" -> id
            "key", "getKey" -> net.kyori.adventure.key.Key.key("oneblock","island_$id")
            "isChunkLoaded" -> chunkLoaded
            "getNearbyEntities" -> { check(thread=="region"); queries++; assertEquals(BoundingBox(-1.0,99.0,-1.0,2.0,102.0,2.0),args!![0]);listOf(item) }
            else -> error(name)
        } }
        val worlds=mutableListOf(world)
        private val entityScheduler=stub(EntityScheduler::class.java) { name,args ->
            check(name=="execute");queue.add("entity" to args!![1] as Runnable);true
        }
        val item: Item=stub(Item::class.java) { name,args ->
            if(name in setOf("getLocation","getWorld","isValid","isDead","setVelocity","teleportAsync")) check(thread=="entity") { "Item access from $thread: $name" }
            when(name) {
                "getUniqueId" -> id
                "getScheduler" -> entityScheduler
                "getWorld" -> worldOfItem
                "getLocation" -> position.clone()
                "isValid" -> valid
                "isDead" -> dead
                "getItemStack" -> stack
                "getOwner" -> owner
                "setVelocity" -> { velocity=(args!![0] as Vector).clone();null }
                "teleportAsync" -> { teleports++;if(teleportResult) position=(args!![0] as Location).clone();CompletableFuture.completedFuture(teleportResult) }
                else -> error("Unexpected item mutation/access: $name")
            }
        }
        private val regions=stub(RegionScheduler::class.java) { name,args ->
            check(name=="runAtFixedRate")
            @Suppress("UNCHECKED_CAST") val action=args!![2] as Consumer<ScheduledTask>
            val task=Task();repeat.add(action to task);task.handle
        }
        private val global=stub(GlobalRegionScheduler::class.java) { name,args ->
            check(name=="execute");queue.add("global" to args!![1] as Runnable);null
        }
        private val server=stub(Server::class.java) { name,_-> when(name) {
            "getWorlds" -> worlds
            "getRegionScheduler" -> regions
            "getGlobalRegionScheduler" -> global
            else -> error(name)
        } }
        private val plugin=stub(Plugin::class.java) { name,_->when(name) {
            "getServer" -> server
            "getLogger" -> Logger.getAnonymousLogger()
            else -> error(name)
        } }
        val island=Island(UUID.randomUUID(),"private",0,100,0,CoreState(CoreMode.WOOD))
        val gathering=CoreItemGathering(plugin,CoreItemGatheringSettings()) { candidate ->
            if(candidate in worlds) island else null
        }
        fun addWorldWithSameUid(): World {
            val key=net.kyori.adventure.key.Key.key("oneblock","island_${UUID.randomUUID()}")
            return stub(World::class.java) { name,_->when(name) {
                "getUID" -> id
                "key","getKey" -> key
                "isChunkLoaded" -> chunkLoaded
                "getNearbyEntities" -> { check(thread=="region");queries++;emptyList<org.bukkit.entity.Entity>() }
                else -> error(name)
            } }.also(worlds::add)
        }
        init { position=Location(world,1.5,99.6,-0.5);worldOfItem=world }
        fun drain() { var count=0;while(queue.isNotEmpty()) { check(count++<20);val (owner,action)=queue.removeFirst();thread=owner;action.run() };thread="test" }
        fun tick() { thread="region";repeat.toList().filterNot { it.second.cancelled }.forEach { it.first.accept(it.second.handle) };thread="test" }
        fun active()=repeat.count { !it.second.cancelled }
    }
    @Test fun `radius one covers neighboring block cells on all axes and rests exactly above the core`() {
        val area=CoreItemArea(0,100,0,1,1.1)
        for(x in listOf(-0.9,0.5,1.9)) for(y in listOf(99.1,100.5,101.9)) for(z in listOf(-0.9,0.5,1.9)) assertTrue(area.contains(Location(null,x,y,z)))
        assertFalse(area.contains(Location(null,2.0,100.0,0.0)))
        assertFalse(area.contains(Location(null,0.0,98.99,0.0)))
        assertTrue(area.settled(Location(null,0.5,101.0,0.5)))
        assertFalse(area.settled(Location(null,0.8,101.0,0.5)))
    }
    @Test fun `moving an actual item preserves its stack owner and stops outward velocity without duplicates`() {
        val f=Fixture();f.gathering.request(f.item,f.world,f.island);f.gathering.request(f.item,f.world,f.island);f.drain()
        assertEquals(1,f.teleports);assertEquals(Location(f.world,0.5,101.1,0.5),f.position)
        assertEquals(Vector(0,0,0),f.velocity)
        assertSame(f.stack,f.item.itemStack);assertEquals(7,f.stack.amount);assertEquals(f.owner,f.item.owner)
        f.gathering.request(f.item,f.world,f.island);f.drain();assertEquals(1,f.teleports)
    }
    @Test fun `items entering the region later are found and unloading or disabling stops queries`() {
        val f=Fixture();f.gathering.start();f.drain();assertEquals(1,f.active())
        f.position=Location(f.world,4.0,104.0,4.0);f.tick();f.drain();assertEquals(0,f.teleports)
        f.position=Location(f.world,-0.4,101.9,1.5);f.tick();f.drain();assertEquals(1,f.teleports)
        f.chunkLoaded=false;f.tick();assertEquals(0,f.active());assertEquals(2,f.queries)
        f.chunkLoaded=true;f.gathering.start();f.drain();assertEquals(1,f.active())
        f.gathering.apply(CoreItemGatheringSettings(enabled=false));f.drain();assertEquals(0,f.active())
        f.tick();assertEquals(2,f.queries);f.gathering.close()
    }
    @Test fun `departed dead or removed items are not moved and failed teleports can retry`() {
        val f=Fixture();f.gathering.request(f.item,f.world,f.island);f.valid=false;f.drain();assertEquals(0,f.teleports)
        f.valid=true;f.position=Location(f.world,10.0,100.0,0.0);f.gathering.request(f.item,f.world,f.island);f.drain();assertEquals(0,f.teleports)
        f.position=Location(f.world,1.2,100.5,0.5);f.dead=true;f.gathering.request(f.item,f.world,f.island);f.drain();assertEquals(0,f.teleports)
        f.dead=false;f.teleportResult=false;f.gathering.request(f.item,f.world,f.island);f.drain();assertEquals(1,f.teleports)
        f.teleportResult=true;f.gathering.request(f.item,f.world,f.island);f.drain();assertEquals(2,f.teleports)
    }
    @Test fun `different dimension keys with the same world UUID have independent gatherers`() {
        val f=Fixture();val other=f.addWorldWithSameUid()
        assertEquals(f.world.uid,other.uid);assertNotEquals(f.world.key(),other.key())
        f.gathering.start();f.drain();assertEquals(2,f.active())
        f.tick();f.drain();assertEquals(2,f.queries);assertEquals(1,f.teleports)
        f.gathering.close();assertEquals(0,f.active())
    }
    @Test fun `settings support older configs and reject unsafe bounds or malformed values`() {
        assertEquals(CoreItemGatheringSettings(),CoreItemGatheringSettings.load(YamlConfiguration()))
        for((path,value) in listOf("radius" to -1,"radius" to 9,"interval-ticks" to 0,"height" to 0.5,"height" to Double.NaN,"enabled" to "yes")) {
            assertThrows(IllegalArgumentException::class.java) { CoreItemGatheringSettings.load(YamlConfiguration().apply { set("core.item-gathering.$path",value) }) }
        }
    }
}
