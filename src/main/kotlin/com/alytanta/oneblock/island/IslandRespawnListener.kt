package com.alytanta.oneblock.island

import com.alytanta.oneblock.util.FoliaTasks
import com.destroystokyo.paper.event.player.PlayerPostRespawnEvent
import org.bukkit.Location
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.entity.PlayerDeathEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.player.PlayerTeleportEvent
import org.bukkit.event.player.PlayerRespawnEvent
import org.bukkit.entity.Player
import org.bukkit.plugin.Plugin
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

class IslandRespawnListener(private val plugin: Plugin, private val islands: IslandRespawns,
    private val worldSpawn: (org.bukkit.World, (Location?) -> Unit) -> Unit = { world, done -> done(world.spawnLocation.clone()) },
    private val everyFewTicks: (Player, () -> Boolean) -> Unit = { player, step -> player.scheduler.runAtFixedRate(plugin, { task -> if (step()) task.cancel() }, null, 5L, 5L) }) : Listener {
    var externalRespawn:(Player)->Boolean={false}
    private class Pending(val owner: UUID?, val fallback: Location, val reserved: Boolean) {
        val destination = CompletableFuture<Location?>()
        val released = AtomicBoolean()
        @Volatile var respawned = false
    }
    private val pending = ConcurrentHashMap<UUID, Pending>()
    private fun release(plan: Pending) {
        if (plan.owner != null && plan.reserved && plan.released.compareAndSet(false, true)) islands.endTeleport(plan.owner)
    }
    private fun finish(player: UUID, plan: Pending) {
        pending.remove(player, plan)
        release(plan)
    }

    /** Death costs nothing here: inventory and levels stay with the player everywhere. */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun keepEverything(event: PlayerDeathEvent) {
        event.keepInventory = true; event.keepLevel = true
        event.drops.clear(); event.droppedExp = 0
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onDeath(event: PlayerDeathEvent) {
        prepare(event.entity)
    }

    @EventHandler fun onJoin(event: PlayerJoinEvent) {
        if (event.player.isDead) prepare(event.player)
    }

    internal fun prepare(player: Player) {
        if(externalRespawn(player))return
        // Respawn belongs to the world of death, regardless of the player's own/co-op island.
        val world = player.world
        val island = islands.findByWorld(world)
        val reserved = island?.let { islands.beginTeleport(it.owner) } ?: false
        val fallback = if (island == null) world.spawnLocation.clone()
            else Location(world, island.x + 0.5, island.y + 1.0, island.z + 0.5)
        val plan = Pending(island?.owner, fallback, reserved)
        pending.put(player.uniqueId, plan)?.let(::release)
        if (island == null) try {
            worldSpawn(world) { location ->
                // A configuration change must never redirect this death into another world.
                plan.destination.complete(location?.takeIf { it.world?.key() == world.key() }?.clone() ?: fallback)
            }
        } catch (e: Exception) {
            plugin.logger.log(java.util.logging.Level.WARNING, "Could not resolve world spawn in ${world.name}", e)
            plan.destination.complete(fallback)
        }
        else if (!reserved) plan.destination.complete(fallback)
        else try {
            islands.prepareRespawn(island) { location ->
                if (location == null) plugin.logger.warning("Could not prepare safe respawn for ${player.uniqueId}; retaining death world ${world.name}")
                plan.destination.complete(location ?: fallback)
            }
        } catch (e: Exception) {
            plugin.logger.log(java.util.logging.Level.WARNING, "Could not prepare respawn in ${world.name}", e)
            plan.destination.complete(fallback)
        }
        watch(player, plan)
    }

    /** Folia does not fire the respawn events for every respawn, so the living body is what ends the wait. */
    private fun watch(player: Player, plan: Pending) {
        everyFewTicks(player) {
            when {
                pending[player.uniqueId] !== plan || plan.respawned -> true
                player.isDead -> false
                else -> { arrive(player, plan); true }
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    fun onRespawn(event: PlayerRespawnEvent) {
        if(externalRespawn(event.player))return
        if (event.respawnReason != PlayerRespawnEvent.RespawnReason.DEATH) return
        val plan = pending[event.player.uniqueId] ?: run {
            prepare(event.player)
            pending[event.player.uniqueId]
        } ?: return
        // Never briefly send a player to the hub while asynchronous island preparation finishes.
        // Always use the main destination, including a hub configured with /ob admin sethub.
        // Same-world beds/anchors must not replace the shared spawn requested by the server.
        event.respawnLocation = plan.destination.getNow(null) ?: plan.fallback
    }

    @EventHandler fun onPostRespawn(event: PlayerPostRespawnEvent) {
        if (event.respawnReason != PlayerRespawnEvent.RespawnReason.DEATH) return
        val player = event.player
        arrive(player, pending[player.uniqueId] ?: return)
    }

    private fun arrive(player: Player, plan: Pending) {
        if (plan.respawned) return
        plan.respawned = true
        plan.destination.whenComplete { destination, _ ->
            FoliaTasks.entity(plugin, player, retired = { finish(player.uniqueId, plan) }) {
                if (pending[player.uniqueId] !== plan || destination == null || player.isDead) { finish(player.uniqueId, plan); return@entity }
                if (player.world.key() == destination.world?.key() && player.location.distanceSquared(destination) < 0.25) {
                    player.fallDistance = 0f
                    islands.applyPlayerBorder(player, islands.findByWorld(player.world))
                    finish(player.uniqueId, plan)
                } else try {
                    player.teleportAsync(destination).whenComplete { success, error ->
                        FoliaTasks.entity(plugin, player, retired = { finish(player.uniqueId, plan) }) {
                            if (error == null && success == true) {
                                player.fallDistance = 0f
                                islands.applyPlayerBorder(player, islands.findByWorld(player.world))
                            } else plugin.logger.warning("Island respawn teleport did not complete for ${player.uniqueId}")
                            finish(player.uniqueId, plan)
                        }
                    }
                } catch (e: Exception) { finish(player.uniqueId, plan); plugin.logger.warning("Island respawn teleport failed: ${e.message}") }
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onTeleport(event: PlayerTeleportEvent) {
        val plan = pending[event.player.uniqueId] ?: return
        if (!plan.respawned) return
        val expected = plan.destination.getNow(null)
        // A manual /hub or another intentional teleport supersedes an unfinished respawn correction.
        if (expected == null || expected.world?.key() != event.to.world?.key() || expected.distanceSquared(event.to) > 0.25) {
            finish(event.player.uniqueId, plan)
        }
    }

    @EventHandler fun onQuit(event: PlayerQuitEvent) { pending.remove(event.player.uniqueId)?.let(::release) }
}
