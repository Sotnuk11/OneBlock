package com.alytanta.oneblock.island

import com.alytanta.oneblock.menu.MenuFixture.Companion.stub
import com.alytanta.oneblock.oneblock.CoreMode
import com.alytanta.oneblock.oneblock.CoreState
import com.destroystokyo.paper.event.player.PlayerPostRespawnEvent
import io.papermc.paper.threadedregions.scheduler.EntityScheduler
import net.kyori.adventure.text.Component
import org.bukkit.Location
import org.bukkit.World
import org.bukkit.entity.Player
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.event.player.PlayerRespawnEvent
import org.bukkit.event.player.PlayerTeleportEvent
import org.bukkit.plugin.Plugin
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.logging.Logger

class IslandRespawnListenerTest {
    private class Fixture(guest: Boolean = false, val customHub: Boolean = false, val delayedHub: Boolean = false) {
        val actor = UUID.randomUUID()
        val island = Island(if (guest) UUID.randomUUID() else actor, "private_island", 0, 100, 0, CoreState(CoreMode.WOOD))
        fun world(name: String): World {
            lateinit var world: World
            val id = UUID.randomUUID()
            world = stub(World::class.java) { method, _ -> when (method) {
                "getName" -> name
                "key", "getKey" -> net.kyori.adventure.key.Key.key("test",name)
                "getUID" -> id
                "getSpawnLocation" -> Location(world, 4.5, 80.0, 4.5)
                else -> error(method)
            } }
            return world
        }
        val privateWorld = world(island.worldName)
        val hub = world("hub")
        var currentWorld = privateWorld
        var position = Location(privateWorld, 0.5, -70.0, 0.5)
        var dead = true
        var released = 0
        var reserved = 0
        var borders = 0
        val prepared = mutableListOf<UUID>()
        val requests = mutableListOf<(Location?) -> Unit>()
        val teleports = mutableListOf<Location>()
        private val actions = ArrayDeque<Runnable>()
        private val scheduler = stub(EntityScheduler::class.java) { method, args ->
            check(method == "execute"); actions += args!![1] as Runnable; true
        }
        val player = stub(Player::class.java) { method, args -> when (method) {
            "getUniqueId" -> actor
            "getWorld" -> currentWorld
            "getLocation" -> position.clone()
            "isDead" -> dead
            "getScheduler" -> scheduler
            "setFallDistance" -> null
            "teleportAsync" -> {
                val destination = (args!![0] as Location).clone()
                teleports += destination; currentWorld = destination.world!!; position = destination
                CompletableFuture.completedFuture(true)
            }
            else -> error(method)
        } }
        private val logger = Logger.getAnonymousLogger().apply { useParentHandlers = false }
        private val plugin = stub(Plugin::class.java) { method, _ -> if (method == "getLogger") logger else error(method) }
        private val access = object : IslandRespawns {
            override fun findByWorld(worldName: String) = island.takeIf { worldName in setOf(it.worldName,"test:${it.worldName}") }
            override fun beginTeleport(owner: UUID): Boolean { assertEquals(island.owner, owner); reserved++; return true }
            override fun endTeleport(owner: UUID) { assertEquals(island.owner, owner); released++ }
            override fun prepareRespawn(island: Island, callback: (Location?) -> Unit) { prepared += island.owner; requests += callback }
            override fun applyPlayerBorder(player: Player, island: Island?) { assertEquals(if(currentWorld == privateWorld) this@Fixture.island else null, island); borders++ }
        }
        var resolveSpawn: ((Location?) -> Unit)? = null
        val hubSpawn get() = Location(hub,30.5,85.0,-12.5,90f,-10f)
        val listener = IslandRespawnListener(plugin, access, { world,done ->
            if (delayedHub) resolveSpawn=done
            else done(if(customHub && world == hub) hubSpawn else world.spawnLocation)
        }, { _,_ -> })
        fun ready(location: Location? = Location(privateWorld, 1.5, 100.0, 0.5)) { requests.toList().forEach { it(location) } }
        fun event(location: Location = Location(hub, 0.5, 80.0, 0.5), reason: PlayerRespawnEvent.RespawnReason = PlayerRespawnEvent.RespawnReason.DEATH) =
            PlayerRespawnEvent(player, location, false, false, false, reason)
        fun post(event: PlayerRespawnEvent) {
            dead = false; currentWorld = event.respawnLocation.world!!; position = event.respawnLocation.clone()
            listener.onPostRespawn(PlayerPostRespawnEvent(player, position, false, false, false, event.respawnReason))
        }
        fun drain() { var count = 0; while (actions.isNotEmpty()) { check(count++ < 50); actions.removeFirst().run() } }
    }

    @Test fun `owner co-op and guest all respawn on the island of death without a hub detour`() {
        for (guest in listOf(false, true)) {
            val f = Fixture(guest)
            f.listener.prepare(f.player)
            val event = f.event()
            f.listener.onRespawn(event)
            assertEquals(f.privateWorld, event.respawnLocation.world) // Safe world fallback while preparation is pending.
            assertEquals(101.0, event.respawnLocation.y)
            assertEquals(listOf(f.island.owner), f.prepared)
            f.post(event)
            assertEquals(0, f.released)
            f.ready(); f.drain()
            assertEquals(f.privateWorld, f.currentWorld)
            assertEquals(100.0, f.position.y)
            assertTrue(f.teleports.all { it.world == f.privateWorld })
            assertEquals(1, f.released)
            assertEquals(1, f.borders)
        }
    }

    @Test fun `ready landing is used directly and asynchronous preparation failure never selects hub`() {
        for (failed in listOf(false, true)) {
            val f = Fixture()
            f.listener.prepare(f.player)
            f.ready(if (failed) null else Location(f.privateWorld, 1.5, 100.0, 0.5))
            val event = f.event()
            f.listener.onRespawn(event)
            assertEquals(f.privateWorld, event.respawnLocation.world)
            f.post(event); f.drain()
            assertTrue(f.teleports.isEmpty())
            assertEquals(1, f.released)
        }
    }

    @Test fun `quit manual travel and repeated death release only their own pending reservation`() {
        val quit = Fixture().apply { listener.prepare(player); listener.onQuit(PlayerQuitEvent(player, null as Component?)); ready(); drain() }
        assertEquals(1, quit.released); assertTrue(quit.teleports.isEmpty())
        val travel = Fixture()
        travel.listener.prepare(travel.player)
        val event = travel.event(); travel.listener.onRespawn(event); travel.post(event)
        travel.listener.onTeleport(PlayerTeleportEvent(travel.player, travel.position, Location(travel.hub, 0.5, 80.0, 0.5), PlayerTeleportEvent.TeleportCause.COMMAND))
        travel.ready(); travel.drain()
        assertEquals(1, travel.released); assertTrue(travel.teleports.isEmpty())
        val twice = Fixture()
        twice.listener.prepare(twice.player); twice.listener.prepare(twice.player)
        assertEquals(2, twice.reserved); assertEquals(1, twice.released)
        twice.ready()
        val respawn = twice.event(); twice.listener.onRespawn(respawn); twice.post(respawn); twice.drain()
        assertEquals(2, twice.released)
    }

    @Test fun `hub death uses the configured shared hub spawn instead of a bed or world default`() {
        val f=Fixture(customHub=true).apply { currentWorld=hub;position=Location(hub,200.0,64.0,200.0) }
        f.listener.prepare(f.player)
        val respawn=f.event(Location(f.hub,21.5,81.0,22.5))
        f.listener.onRespawn(respawn)
        assertEquals(f.hubSpawn,respawn.respawnLocation)
        f.post(respawn);f.drain()
        assertTrue(f.teleports.isEmpty())
        assertEquals(1,f.borders)
    }
    @Test fun `delayed hub resolution corrects within the death world and ignores a different world answer`() {
        for(wrongWorld in listOf(false,true)) {
            val f=Fixture(customHub=true,delayedHub=true).apply { currentWorld=hub;position=Location(hub,200.0,64.0,200.0) }
            f.listener.prepare(f.player)
            val respawn=f.event(Location(f.privateWorld,0.0,100.0,0.0))
            f.listener.onRespawn(respawn)
            assertEquals(f.hub,respawn.respawnLocation.world)
            assertEquals(4.5,respawn.respawnLocation.x)
            f.post(respawn)
            f.resolveSpawn!!.invoke(if(wrongWorld) Location(f.privateWorld,0.0,101.0,0.0) else f.hubSpawn)
            f.drain()
            assertEquals(f.hub,f.currentWorld)
            assertEquals(if(wrongWorld) 4.5 else 30.5,f.position.x)
        }
    }
    @Test fun `death respawn without an earlier death callback still resolves the correct world spawn`() {
        val f=Fixture(customHub=true).apply { currentWorld=hub;position=Location(hub,200.0,64.0,200.0) }
        val event=f.event(Location(f.privateWorld,0.0,101.0,0.0))
        f.listener.onRespawn(event)
        assertEquals(f.hubSpawn,event.respawnLocation)
        f.post(event);f.drain();assertTrue(f.teleports.isEmpty())
    }
    @Test fun `non death respawns remain vanilla`() {
        val f=Fixture()
        f.listener.prepare(f.player)
        val event=f.event(Location(f.hub,19.0,80.0,19.0),PlayerRespawnEvent.RespawnReason.END_PORTAL)
        f.listener.onRespawn(event)
        assertEquals(19.0,event.respawnLocation.x)
    }
    @Test fun `ordinary world death always uses the main world spawn even with a same world bed`() {
        val f = Fixture().apply { currentWorld = hub; position = Location(hub, 20.5, 80.0, 20.5) }
        f.listener.prepare(f.player)
        val bed = f.event(Location(f.hub, 21.5, 81.0, 22.5))
        f.listener.onRespawn(bed)
        assertEquals(4.5, bed.respawnLocation.x)
        assertEquals(80.0,bed.respawnLocation.y)
        val elsewhere = f.event(Location(f.privateWorld, 0.5, 101.0, 0.5))
        f.listener.onRespawn(elsewhere)
        assertEquals(f.hub, elsewhere.respawnLocation.world)
        assertEquals(4.5, elsewhere.respawnLocation.x)
        assertEquals(0, f.reserved)
        f.post(elsewhere); f.drain()
        assertTrue(f.teleports.isEmpty())
    }
}
