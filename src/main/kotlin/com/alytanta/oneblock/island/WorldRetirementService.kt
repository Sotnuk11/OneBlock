package com.alytanta.oneblock.island

import com.alytanta.oneblock.hub.HubService
import com.alytanta.oneblock.storage.StorageService
import com.alytanta.oneblock.util.FoliaTasks
import net.thenextlvl.worlds.WorldsAccess
import org.bukkit.entity.Player
import org.bukkit.plugin.Plugin
import java.nio.file.Files
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.logging.Level

/** Durable, retryable world deletion. Only this deletion's named backup expires; manual backups are untouched. */
class WorldRetirementService(private val plugin: Plugin, private val islands: IslandService, private val storage: StorageService,
    private val hub: HubService, private val access: () -> WorldsAccess = WorldsAccess::access,
    private val now: () -> Long = System::currentTimeMillis,
    private val registerExisting: (WorldsAccess, net.kyori.adventure.key.Key) -> Unit = { api, key ->
        val level = net.thenextlvl.worlds.Level.builder(key)
            .generatorType(net.thenextlvl.worlds.generator.GeneratorType.FLAT.with(IslandGeneration.voidPreset()))
            .structures(false).bonusChest(false).build()
        api.worldRegistry.register(level, true)
    }) {
    companion object { const val RETENTION_MILLIS = 48L * 60 * 60 * 1000 }
    private val running = ConcurrentHashMap.newKeySet<UUID>()
    fun sweep() { if (storage.healthy) islands.retired().filter { it.worldRetirement?.purgedAt == null }.forEach { process(it) } }

    fun process(island: Island): CompletableFuture<Unit> {
        if (island.retiredAt == null || island.worldRetirement?.purgedAt != null || !storage.healthy || !running.add(island.owner))
            return CompletableFuture.completedFuture(Unit)
        val generation = storage.generation
        fun checkCurrent() {
            check(storage.healthy && storage.generation == generation)
            check(islands.findByOwner(island.owner)?.retiredAt == island.retiredAt)
            check(!islands.worldOperationsPending(island.owner)) { "Waiting for in-flight world operations" }
        }
        fun persist(state: WorldRetirement): CompletableFuture<Unit> {
            checkCurrent()
            islands.update(island.owner) { it.copy(worldRetirement = state) }
            return storage.flushAsync().thenApply {
                checkCurrent()
            }
        }
        val result = global { CompletableFuture.completedFuture(access()) }.thenCompose { api ->
            checkCurrent()
            val key = IslandWorldIdentity.key(island.owner)
            val state = island.worldRetirement ?: WorldRetirement()
            if (state.deletedAt != null) {
                if (state.expiresAt == null || now() < state.expiresAt) CompletableFuture.completedFuture(Unit)
                else if (state.backup == null) persist(state.copy(purgedAt = now()))
                else { checkCurrent(); api.backupProvider.findBackup(key, state.backup).thenCompose { backup ->
                    checkCurrent()
                    if (backup.isEmpty) CompletableFuture.completedFuture(true) else api.backupProvider.delete(backup.get())
                }.thenCompose { deleted -> check(deleted) { "Backup deletion was refused" }; persist(state.copy(purgedAt = now())) } }
            } else {
                evacuate(island).thenCompose {
                    global {
                        val loaded = islands.loadedWorld(island)
                        val directory = api.resolveLevelDirectory(key).toAbsolutePath().normalize()
                        check(directory.startsWith(api.dimensionsRoot.toAbsolutePath().normalize()) && directory != api.dimensionsRoot.toAbsolutePath().normalize())
                        CompletableFuture.completedFuture(loaded to directory)
                    }
                }.thenCompose { (loaded, directory) ->
                    CompletableFuture.supplyAsync { loaded != null || Files.isDirectory(directory) }.thenCompose { exists ->
                        if (!exists) {
                            global { api.worldRegistry.unregister(key); CompletableFuture.completedFuture(Unit) }.thenCompose {
                                persist(state.copy(deletedAt = now(), expiresAt = now() + RETENTION_MILLIS,
                                    purgedAt = if (state.backup == null) now() else null))
                            }
                        } else {
                            val world = if (loaded != null) CompletableFuture.completedFuture(loaded) else global {
                                if (!api.worldRegistry.isRegistered(key)) {
                                    registerExisting(api, key)
                                }
                                api.load(key)
                            }
                            world.thenCompose { target ->
                                check(target.key().asString() == key.asString()) { "World identity mismatch" }
                                val backup = if (state.backup != null) api.backupProvider.findBackup(key, state.backup).thenApply {
                                    check(it.isPresent) { "Deletion backup is missing; retaining world" }; it.get()
                                } else {
                                    val name = "oneblock-deleted-${island.owner}-${island.retiredAt}"
                                    api.backupProvider.listBackups(key).thenCompose { entries ->
                                        val existing = entries.use { stream -> stream.filter { it.name() == name || it.name() == "$name.zip" }.findFirst() }
                                        if (existing.isPresent) CompletableFuture.completedFuture(existing.get()) else
                                            global { api.save(target, true) }.thenCompose { saved ->
                                                check(saved) { "World flush failed; retaining world" }
                                                global { api.createBackup(target, name) }
                                            }
                                    }
                                }
                                backup.thenCompose { saved -> persist(state.copy(backup = saved.name())).thenApply { saved } }
                                    .thenCompose { saved ->
                                        evacuate(island).thenCompose { global { checkCurrent(); api.delete(target) } }.thenCompose { deleted ->
                                            check(deleted) { "World deletion was refused; retaining backup" }
                                            global { api.worldRegistry.unregister(key); CompletableFuture.completedFuture(Unit) }
                                        }.thenCompose { persist(state.copy(backup = saved.name(), deletedAt = now(), expiresAt = now() + RETENTION_MILLIS)) }
                                    }
                            }
                        }
                    }
                }
            }
        }
        result.whenComplete { _, error ->
            running.remove(island.owner)
            if (error != null) plugin.logger.log(Level.WARNING, "Profile world cleanup will retry: ${island.owner}", error)
        }
        return result
    }

    private fun evacuate(island: Island): CompletableFuture<Unit> = global { CompletableFuture.completedFuture(plugin.server.onlinePlayers.toList()) }
        .thenCompose { online ->
            val checks = online.map { player ->
                val result = CompletableFuture<Player?>()
                FoliaTasks.entity(plugin, player, retired = { result.complete(null) }) {
                    result.complete(player.takeIf { islands.sameWorld(island, it.world) })
                }
                result
            }
            CompletableFuture.allOf(*checks.toTypedArray()).thenCompose {
                val occupants = checks.mapNotNull { it.join() }
                if (occupants.isEmpty()) CompletableFuture.completedFuture(Unit) else {
                    val done = CompletableFuture<Unit>()
                    hub.resolve { spawn ->
                        if (spawn == null) done.completeExceptionally(IllegalStateException("Hub unavailable")) else {
                            val teleports = occupants.map { player ->
                                val moved = CompletableFuture<Unit>()
                                FoliaTasks.entity(plugin, player, retired = { moved.complete(Unit) }) {
                                    try { player.closeInventory(); player.teleportAsync(spawn).whenComplete { ok, error ->
                                        if (error != null || ok != true) moved.completeExceptionally(error ?: IllegalStateException("Evacuation failed")) else moved.complete(Unit)
                                    } } catch (e: Exception) { moved.completeExceptionally(e) }
                                }
                                moved
                            }
                            CompletableFuture.allOf(*teleports.toTypedArray()).whenComplete { _, error ->
                                if (error != null) done.completeExceptionally(error) else done.complete(Unit)
                            }
                        }
                    }
                    done
                }
            }
        }

    private fun <T> global(action: () -> CompletableFuture<T>): CompletableFuture<T> {
        val result = CompletableFuture<T>()
        try { plugin.server.globalRegionScheduler.execute(plugin) {
            try { action().whenComplete { value, error -> if (error != null) result.completeExceptionally(error) else result.complete(value) } }
            catch (e: Exception) { result.completeExceptionally(e) }
        } } catch (e: Exception) { result.completeExceptionally(e) }
        return result
    }
}
