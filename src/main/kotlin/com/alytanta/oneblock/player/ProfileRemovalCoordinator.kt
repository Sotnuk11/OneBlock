package com.alytanta.oneblock.player

import com.alytanta.oneblock.island.Island
import com.alytanta.oneblock.island.IslandService
import com.alytanta.oneblock.market.InventoryGuard
import com.alytanta.oneblock.storage.StorageService
import com.alytanta.oneblock.util.FoliaTasks
import org.bukkit.entity.Player
import org.bukkit.plugin.Plugin
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap

/** Capture on each entity, plan the entire removal atomically, commit, then clear via eviction journals. */
internal class ProfileRemovalCoordinator(private val plugin: Plugin, private val storage: StorageService,
    private val islands: IslandService, private val players: PlayerService, private val resolving: MutableSet<UUID>,
    private val applied: (Player, UUID) -> Unit, private val retired: (Island) -> Unit) {
    private val accounts = ConcurrentHashMap.newKeySet<UUID>()
    fun remove(account: UUID, who: UUID?, requester: UUID, administrative: Boolean): CompletableFuture<List<ProfileRemoval>> {
        val result = CompletableFuture<List<ProfileRemoval>>()
        val tokens = linkedMapOf<UUID, UUID>(); val locked = mutableSetOf<UUID>()
        val online = linkedMapOf<UUID, Player?>(); val generation = storage.generation
        var changed = false
        if (!accounts.add(account)) return CompletableFuture.failedFuture(IllegalStateException("busy"))
        fun cleanup(release: Boolean) {
            if (release) tokens.forEach { (id, token) -> InventoryGuard.release(id, token) }
            locked.forEach(resolving::remove); accounts.remove(account)
        }
        try {
            val island = requireNotNull(islands.findByOwner(account))
            val ids = if (who == null) ProfileRemoval.participants(island) else setOf(who)
            require(who != null || administrative)
            require(ids.all { it in ProfileRemoval.participants(island) }) { "membership" }
            ids.sortedBy(UUID::toString).forEach { id ->
                check(resolving.add(id)) { "busy" }; locked += id
                tokens[id] = InventoryGuard.acquireToken(id) ?: error("busy")
                val profile = players.getOrCreate(id)
                ProfileRemoval.validate(island, profile, (storage as com.alytanta.oneblock.storage.PostgresStorageService).marketState(),
                    requester, System.currentTimeMillis(), administrative)
                online[id] = plugin.server.getPlayer(id)
            }
            val captures = ids.associateWith { id ->
                val future = CompletableFuture<RemovalCapture>()
                val actor = online[id]
                fun active(): Boolean = players.find(id)?.let { it.activeAccount == account || it.activeAccount == null &&
                    it.inventoryDetached != true && islands.findForPlayer(id)?.owner == account } == true
                if (actor == null) future.complete(RemovalCapture(active())) else
                    FoliaTasks.entity(plugin, actor, retired = { future.completeExceptionally(IllegalStateException("Player disconnected")) }) {
                        try {
                            check(InventoryGuard.owns(id, tokens.getValue(id))) { "Inventory session changed" }
                            val isActive = active()
                            if (isActive) check(!actor.isDead && actor.itemOnCursor.isEmpty) { "Inventory is not ready" }
                            actor.closeInventory()
                            val snapshot = if (isActive) ProfileInventory.capture(actor).also { actor.saveData() } else null
                            future.complete(RemovalCapture(isActive, snapshot))
                        } catch (e: Exception) { future.completeExceptionally(e) }
                    }
                future
            }
            CompletableFuture.allOf(*captures.values.toTypedArray()).whenComplete { _, captureError ->
                try {
                    if (captureError != null) throw captureError
                    check(storage.healthy && storage.generation == generation)
                    check(islands.findByOwner(account) == island) { "Membership changed" }
                    check(ids.all { InventoryGuard.owns(it, tokens.getValue(it)) && plugin.server.getPlayer(it) === online[it] }) { "Player session changed" }
                    if (!administrative && who != requester && online[who]?.let { captures[who]!!.join().active } == true) error("active-member")
                    val plans = islands.removeProfileMembers(account, ids, requester, administrative, who == null,
                        captures.mapValues { it.value.join() })
                    changed = true
                    storage.flushAsync().whenComplete { _, commitError ->
                        if (commitError != null || storage.generation != generation) {
                            cleanup(false); result.completeExceptionally(commitError ?: IllegalStateException("Storage generation changed"))
                        } else {
                            plans.forEach { plan ->
                                val id = plan.player.uniqueId; val actor = online[id]; val token = tokens.getValue(id)
                                if (actor != null && plan.player.eviction != null) FoliaTasks.entity(plugin, actor,
                                    retired = { InventoryGuard.release(id, token) }) { applied(actor, token) }
                                else InventoryGuard.release(id, token)
                            }
                            cleanup(false)
                            plans.last().island.takeIf { it.retiredAt != null }?.let {
                                runCatching { retired(it) }.onFailure { error -> plugin.logger.warning("World cleanup deferred to the periodic retry: ${error.message}") }
                            }
                            result.complete(plans)
                        }
                    }
                } catch (e: Throwable) { cleanup(!changed); result.completeExceptionally(e) }
            }
        } catch (e: Exception) { cleanup(!changed); result.completeExceptionally(e) }
        return result
    }
}
