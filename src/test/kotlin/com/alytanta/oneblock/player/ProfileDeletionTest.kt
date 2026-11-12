package com.alytanta.oneblock.player

import com.alytanta.oneblock.config.PluginConfig
import com.alytanta.oneblock.island.*
import com.alytanta.oneblock.market.InventoryGuard
import com.alytanta.oneblock.menu.MenuFixture.Companion.stub
import com.alytanta.oneblock.menu.Stack
import com.alytanta.oneblock.oneblock.*
import com.alytanta.oneblock.storage.*
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres
import org.bukkit.*
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.plugin.Plugin
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.logging.Logger

class ProfileDeletionTest {
    @TempDir lateinit var folder: Path
    companion object {
        private lateinit var postgres: EmbeddedPostgres
        @JvmStatic @BeforeAll fun start() { postgres = EmbeddedPostgres.start() }
        @JvmStatic @AfterAll fun stop() { if (::postgres.isInitialized) postgres.close() }
    }
    private inner class Fixture(coop: Boolean = false) : AutoCloseable {
        val actor = ProfilePlayer().apply { values["ItemOnCursor"] = Stack(Material.AIR, 0) }
        val member = UUID.randomUUID(); val account = UUID.randomUUID()
        val logger = Logger.getAnonymousLogger().apply { useParentHandlers = false }
        val config: DatabaseSettings
        val repo: PostgresStorageService
        val players: PlayerService
        val islands: IslandService
        var applied = 0; var retired = 0
        val pending = ConcurrentHashMap.newKeySet<UUID>()
        val coordinator: ProfileRemovalCoordinator
        val server = stub(Server::class.java) { name, args -> if (name == "getPlayer" && args!![0] == actor.id) actor.player else null }
        val plugin = stub(Plugin::class.java) { name, _ -> when (name) { "getServer" -> server; "getLogger" -> logger; else -> null } }
        init {
            val name = "delete_"+UUID.randomUUID().toString().replace("-", "")
            postgres.postgresDatabase.connection.use { it.createStatement().use { st -> st.execute("CREATE DATABASE $name") } }
            config = DatabaseSettings("127.0.0.1", postgres.port, name, "postgres", "test-only", "disable", "disable", 5)
            repo = PostgresStorageService(folder.toFile(), logger, config)
            repo.load(); repo.saveProfile(PlayerProfile(actor.id, activeAccount = account))
            if (coop) repo.saveProfile(PlayerProfile(member, activeAccount = account))
            repo.saveIsland(Island(account, IslandWorldIdentity.worldName(account), 0, 100, 0, CoreState(CoreMode.WOOD),
                ownerPlayerId = actor.id, members = if (coop) mapOf(member to IslandRole.MEMBER) else emptyMap()))
            assertTrue(repo.flush())
            players = PlayerService(repo); islands = IslandService(plugin, PluginConfig.load(YamlConfiguration(), logger), repo, players)
            coordinator = ProfileRemovalCoordinator(plugin, repo, islands, players, pending, { player, token ->
                assertTrue(InventoryGuard.owns(player.uniqueId, token)); assertNotNull(players.find(player.uniqueId)!!.eviction)
                applied++; InventoryGuard.release(player.uniqueId, token)
            }, { retired++ })
        }
        fun drain() { actor.drain() }
        override fun close() { InventoryGuard.release(actor.id); InventoryGuard.release(member); repo.close() }
    }
    @Test fun `player deletes their only active profile without creating a replacement and callback keeps token until journal`() {
        Fixture().use { f ->
            val before = ProfileInventory.capture(f.actor.player)
            val future = f.coordinator.remove(f.account, f.actor.id, f.actor.id, false)
            assertFalse(future.isDone); assertTrue(InventoryGuard.busy(f.actor.id)); assertEquals(f.account, f.players.find(f.actor.id)!!.activeAccount)
            f.drain(); future.get(10, TimeUnit.SECONDS); f.drain()
            assertEquals(1, f.applied); assertEquals(1, f.retired); assertFalse(InventoryGuard.busy(f.actor.id))
            assertTrue(f.islands.profilesFor(f.actor.id).isEmpty()); assertNull(f.players.find(f.actor.id)!!.activeAccount)
            assertEquals(before.hash(), f.players.find(f.actor.id)!!.eviction!!.beforeHash)
            assertEquals(1, f.repo.loadIslands().size); assertNotNull(f.repo.loadIslands().single().worldRetirement)
        }
    }
    @Test fun `admin removes an online active owner immediately and remaining coop keeps world`() {
        Fixture(coop = true).use { f ->
            val future = f.coordinator.remove(f.account, f.actor.id, UUID.randomUUID(), true)
            f.drain(); future.get(10, TimeUnit.SECONDS); f.drain()
            val island = f.islands.findByOwner(f.account)!!
            assertEquals(f.member, island.ownerPlayer); assertNull(island.retiredAt)
            assertEquals(0, f.retired); assertEquals(1, f.applied)
        }
    }
    @Test fun `admin deletes whole coop atomically including unknown offline active inventory and state survives reopen`() {
        Fixture(coop = true).use { f ->
            val future = f.coordinator.remove(f.account, null, UUID.randomUUID(), true)
            f.drain(); future.get(10, TimeUnit.SECONDS); f.drain()
            assertNotNull(f.repo.loadIslands().single().retiredAt)
            assertTrue(f.players.find(f.member)!!.eviction!!.pendingCapture)
            assertTrue(f.repo.loadProfiles().all { it.activeAccount == null && it.eviction != null })
            f.islands.update(f.account) { it.copy(worldRetirement = WorldRetirement("deletion.zip", 100, 172800100)) }
            assertTrue(f.repo.flush())
            f.repo.close()
            val reopened = PostgresStorageService(folder.toFile(), f.logger, f.config)
            try { reopened.load(); assertNotNull(reopened.loadIslands().single().worldRetirement)
                assertEquals(WorldRetirement("deletion.zip", 100, 172800100), reopened.loadIslands().single().worldRetirement)
                assertTrue(reopened.loadProfiles().all { it.eviction != null && it.activeAccount == null })
            } finally { reopened.close() }
        }
    }
    @Test fun `ordinary members cannot use admin authority or kick a recent member`() {
        Fixture(coop = true).use { f ->
            assertThrows(Exception::class.java) { f.coordinator.remove(f.account, f.member, f.actor.id, false).get() }
            assertThrows(Exception::class.java) { f.coordinator.remove(f.account, null, f.actor.id, false).get() }
            assertEquals(2, ProfileRemoval.participants(f.islands.findByOwner(f.account)!!).size)
            assertTrue(f.pending.isEmpty()); assertFalse(InventoryGuard.busy(f.member))
        }
    }
}
