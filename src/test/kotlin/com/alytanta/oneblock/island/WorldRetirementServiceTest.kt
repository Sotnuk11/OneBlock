package com.alytanta.oneblock.island

import com.alytanta.oneblock.config.PluginConfig
import com.alytanta.oneblock.hub.*
import com.alytanta.oneblock.locale.LocaleService
import com.alytanta.oneblock.menu.MenuFixture.Companion.stub
import com.alytanta.oneblock.oneblock.*
import com.alytanta.oneblock.player.PlayerService
import com.alytanta.oneblock.storage.StorageService
import io.papermc.paper.threadedregions.scheduler.GlobalRegionScheduler
import net.thenextlvl.worlds.*
import org.bukkit.*
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.plugin.Plugin
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.*
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.logging.Logger

class WorldRetirementServiceTest {
    @TempDir lateinit var folder: Path
    private inner class Fixture {
        val id = UUID.randomUUID()
        var stored = Island(id, IslandWorldIdentity.worldName(id), 0, 100, 0, CoreState(CoreMode.WOOD), retiredAt = 100)
        var time = 1000L; var exists = true; var backupFails = false; var deleteFails = false; var flushFails = false
        var generation = 0L
        val events = Collections.synchronizedList(mutableListOf<String>())
        val backups = linkedMapOf<String, Backup>()
        private val globalThread = ThreadLocal.withInitial { false }
        private val world = stub(World::class.java) { name, _ -> when (name) {
            "key", "getKey" -> NamespacedKey("oneblock", "island_$id"); else -> null
        } }
        private val global = stub(GlobalRegionScheduler::class.java) { name, args ->
            if (name == "execute") { val before = globalThread.get(); globalThread.set(true)
                try { (args!![1] as Runnable).run() } finally { globalThread.set(before) }
            }; null
        }
        private val server = stub(Server::class.java) { name, _ -> when (name) {
            "getGlobalRegionScheduler" -> global; "getOnlinePlayers" -> emptyList<Any>()
            "getWorld" -> if (exists) world else null; else -> null
        } }
        private val logger = Logger.getAnonymousLogger().apply { useParentHandlers = false }
        private val plugin = stub(Plugin::class.java) { name, _ -> when (name) {
            "getServer" -> server; "getLogger" -> logger; else -> null
        } }
        private val storage = stub(StorageService::class.java) { name, args -> when (name) {
            "loadIslands" -> listOf(stored); "loadProfiles" -> emptyList<Any>(); "getHealthy" -> true; "getGeneration" -> generation
            "saveIsland" -> { stored = args!![0] as Island; events += "persist"; null }
            "flushAsync" -> if (flushFails) CompletableFuture.failedFuture<Unit>(IllegalStateException("flush")) else CompletableFuture.completedFuture(Unit)
            else -> null
        } }
        val islands = IslandService(plugin, PluginConfig.load(YamlConfiguration(), logger), storage)
        private val locales = LocaleService(plugin, "en", PlayerService(storage))
        private val hub = HubService(plugin, HubSettings("hub", true, 0.0, 100.0, 0.0, 0f, 0f), islands, locales)
        private val provider = stub(BackupProvider::class.java) { name, args -> when (name) {
            "listBackups" -> CompletableFuture.completedFuture(backups.values.stream())
            "findBackup" -> CompletableFuture.completedFuture(Optional.ofNullable(backups[args!!.last()]))
            "delete" -> { val backup = args!![0] as Backup; backups.remove(backup.name()); events += "purge"; CompletableFuture.completedFuture(true) }
            else -> null
        } }
        private val registry = stub(WorldRegistry::class.java) { name, _ -> when (name) {
            "isRegistered" -> false
            "register", "unregister" -> { check(globalThread.get()); events += name; null }
            else -> null
        } }
        private val api = stub(WorldsAccess::class.java) { name, args -> when (name) {
            "getDimensionsRoot" -> folder
            "resolveLevelDirectory" -> folder.resolve("island_$id")
            "getBackupProvider" -> provider; "getWorldRegistry" -> registry
            "load" -> { check(globalThread.get()); events += "load"; exists = true; CompletableFuture.completedFuture(world) }
            "save" -> { check(globalThread.get()); events += "save"; CompletableFuture.completedFuture(true) }
            "createBackup" -> {
                check(globalThread.get()); events += "backup"
                if (backupFails) CompletableFuture.failedFuture<Backup>(IllegalStateException("backup")) else {
                    val title = args!![1].toString()+".zip"
                    CompletableFuture.completedFuture(addBackup(title))
                }
            }
            "delete" -> {
                check(globalThread.get()); check(stored.worldRetirement?.backup in backups)
                events += "delete"
                if (!deleteFails) exists = false
                CompletableFuture.completedFuture(!deleteFails)
            }
            else -> null
        } }
        fun addBackup(title: String): Backup = stub(Backup::class.java) { name, _ -> if (name == "name") title else null }.also { backups[title] = it }
        fun service() = WorldRetirementService(plugin, islands, storage, hub, { api }, { time },
            registerExisting = { api, key -> api.worldRegistry.register(key, Dimension.OVERWORLD, true, null) })
        fun run(service: WorldRetirementService = service()) = service.process(islands.findByOwner(id)!!).get(5, TimeUnit.SECONDS)
    }

    @Test fun `save backup committed marker and delete precede exact forty eight hour expiry across restart`() {
        val f = Fixture(); f.addBackup("manual.zip"); f.run()
        assertFalse(f.exists); assertEquals(listOf("save", "backup", "persist", "delete", "unregister", "persist"), f.events)
        val name = f.stored.worldRetirement!!.backup!!
        f.time += WorldRetirementService.RETENTION_MILLIS - 1; f.run(f.service())
        assertTrue(name in f.backups)
        f.time++; f.run(f.service())
        assertFalse(name in f.backups); assertTrue("manual.zip" in f.backups); assertNotNull(f.stored.worldRetirement!!.purgedAt)
        f.run(); assertEquals(1, f.events.count { it == "delete" }); assertEquals(1, f.events.count { it == "purge" })
    }
    @Test fun `backup or database failure retains the world and deletion failure retains backup for retry`() {
        for (failure in listOf("backup", "flush", "delete")) {
            val f = Fixture(); f.backupFails = failure == "backup"; f.flushFails = failure == "flush"; f.deleteFails = failure == "delete"
            assertThrows(Exception::class.java) { f.run() }; assertTrue(f.exists)
            if (failure == "delete") {
                assertEquals(1, f.backups.size); assertNull(f.stored.worldRetirement!!.expiresAt)
                f.deleteFails = false; f.run(); assertFalse(f.exists); assertEquals(1, f.events.count { it == "backup" })
            } else assertFalse("delete" in f.events)
        }
    }
    @Test fun `co-op departures never delete a live shared world and missing worlds are not recreated`() {
        val f = Fixture()
        f.islands.update(f.id) { it.copy(retiredAt = null) }; f.run(); assertTrue(f.events.none { it == "delete" })
        f.islands.update(f.id) { it.copy(retiredAt = 100) }; f.exists = false; f.run()
        assertNotNull(f.stored.worldRetirement!!.purgedAt)
        assertTrue(f.events.none { it in setOf("save", "backup", "delete") })
    }
    @Test fun `crash after world deletion resumes retention without creating a second backup or world`() {
        val f = Fixture(); f.run()
        val state = f.stored.worldRetirement!!
        f.islands.update(f.id) { it.copy(worldRetirement = state.copy(deletedAt = null, expiresAt = null)) }
        f.run(f.service())
        assertEquals(1, f.events.count { it == "backup" }); assertEquals(1, f.events.count { it == "delete" })
        assertNotNull(f.stored.worldRetirement!!.expiresAt)
    }
    @Test fun `unregistered existing disk world is loaded for backup and deletion without creating a new world`() {
        val f = Fixture(); f.exists = false
        java.nio.file.Files.createDirectories(folder.resolve("island_${f.id}"))
        f.run()
        assertEquals(1, f.events.count { it == "load" }); assertEquals(1, f.events.count { it == "register" })
        assertFalse(f.exists); assertNotNull(f.stored.worldRetirement!!.backup)
    }
}
