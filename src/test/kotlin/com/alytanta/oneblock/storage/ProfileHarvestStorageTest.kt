package com.alytanta.oneblock.storage

import com.alytanta.oneblock.island.Island
import com.alytanta.oneblock.oneblock.*
import com.alytanta.oneblock.player.PlayerProfile
import com.alytanta.oneblock.player.ProfileInventory
import com.alytanta.oneblock.player.ProfileSwitch
import com.alytanta.oneblock.player.ProfileRemoval
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.UUID
import java.util.logging.Logger

class ProfileHarvestStorageTest {
    @TempDir lateinit var folder: Path
    companion object {
        private lateinit var postgres: EmbeddedPostgres
        @JvmStatic @BeforeAll fun start() { postgres = EmbeddedPostgres.start() }
        @JvmStatic @AfterAll fun stop() { if (::postgres.isInitialized) postgres.close() }
    }
    private fun settings(): DatabaseSettings {
        val name = "harvest_" + UUID.randomUUID().toString().replace("-", "")
        postgres.postgresDatabase.connection.use { c -> c.createStatement().use { it.execute("CREATE DATABASE $name") } }
        return DatabaseSettings("127.0.0.1",postgres.port,name,"postgres","test-only","disable","disable",5)
    }
    private fun repository(s: DatabaseSettings) = PostgresStorageService(folder.toFile(),Logger.getAnonymousLogger(),s)
    @Test fun `coop removal commits membership escrow and offline eviction together across restart`() {
        val s = settings(); val owner = UUID.randomUUID(); val member = UUID.randomUUID()
        val island = Island(owner, "coop", 0, 100, 0, CoreState(CoreMode.WOOD),
            members = mapOf(member to com.alytanta.oneblock.island.IslandRole.MEMBER), coins = 999)
        val snapshot = ProfileInventory(items = List(43) { if (it == 42) "exact-item-payload" else null })
        val player = PlayerProfile(member, activeAccount = owner, profileCheckpoints = mapOf(owner to snapshot),
            profileActivity = mapOf(owner to System.currentTimeMillis() - ProfileRemoval.INACTIVE_MILLIS - 1000))
        val repo = repository(s)
        try {
            repo.load(); repo.saveProfile(PlayerProfile(owner)); repo.saveProfile(player); repo.saveIsland(island); assertTrue(repo.flush())
            assertThrows(IllegalStateException::class.java) { repo.removeProfileMember(island, player, owner, System.currentTimeMillis()) { error("invalid bytes") } }
            assertEquals(player, repo.loadProfiles().single { it.uniqueId == member })
            assertEquals(island, repo.loadIslands().single())
            assertTrue(repo.marketState().claims.isEmpty())
            val plan = repo.removeProfileMember(island, player, owner, System.currentTimeMillis()) { com.alytanta.oneblock.menu.Stack(org.bukkit.Material.DIAMOND, 5) }
            assertEquals(1, plan.salvaged)
            assertThrows(IllegalStateException::class.java) { repo.removeProfileMember(island, player, owner, System.currentTimeMillis()) { error("must not decode twice") } }
            assertTrue(repo.flush())
        } finally { repo.close() }
        val restarted = repository(s)
        try {
            restarted.load()
            val loaded = restarted.loadProfiles().single { it.uniqueId == member }
            assertEquals(snapshot.hash(), loaded.eviction!!.beforeHash)
            assertEquals(true, loaded.inventoryDetached)
            assertNull(loaded.activeAccount)
            assertTrue(restarted.loadIslands().single().members.isEmpty())
            assertEquals(999, restarted.loadIslands().single().coins)
            val claim = restarted.marketState().claims.values.single()
            assertEquals(owner, claim.account); assertEquals(5, claim.amount); assertEquals("exact-item-payload", claim.itemData)
        } finally { restarted.close() }
    }
    @Test fun `legacy and current inventory snapshots and switch journal survive database restart`() {
        val s = settings()
        val actor = UUID.randomUUID(); val legacyAccount = UUID.randomUUID(); val currentAccount = UUID.randomUUID()
        val legacy = ProfileInventory(items = List(41) { if (it == 40) "offhand-bytes" else null })
        val current = ProfileInventory(items = List(43) { if (it >= 41) "equipment-$it-bytes" else null },
            enderItems = List(27) { if (it == 26) "ender-bytes" else null }, level = 12, experience = 0.75f, totalExperience = 300)
        val profile = PlayerProfile(actor, activeAccount = legacyAccount, savedInventories = mapOf(legacyAccount to legacy, currentAccount to current),
            profileSwitch = ProfileSwitch(UUID.randomUUID(), legacyAccount, currentAccount, current.hash(), review = true))
        val repo = repository(s)
        try { repo.load(); repo.saveProfile(profile); assertTrue(repo.flush()) } finally { repo.close() }
        val restarted = repository(s)
        try {
            restarted.load()
            val loaded = restarted.loadProfiles().single()
            assertEquals(profile, loaded)
            assertEquals(41, loaded.savedInventories!![legacyAccount]!!.items.size)
            assertEquals(43, loaded.savedInventories[currentAccount]!!.items.size)
            assertEquals(current.hash(), loaded.profileSwitch!!.beforeHash)
            assertTrue(loaded.profileSwitch.review)
        } finally { restarted.close() }
    }
    private fun connection(s: DatabaseSettings) = java.sql.DriverManager.getConnection("jdbc:postgresql://127.0.0.1:${postgres.port}/${s.database}","postgres","test-only").apply {
        createStatement().use { it.execute("SET search_path TO ${s.schema}") }
    }
    @Test fun `whole profile deletion plans every member before publishing any membership or escrow changes`() {
        val s = settings(); val ids = List(3) { UUID.randomUUID() }; val account = UUID.randomUUID()
        val snapshot = { data: String -> ProfileInventory(items = List(43) { if (it == 0) data else null }) }
        val members = ids.mapIndexed { index, id -> PlayerProfile(id, activeAccount = UUID.randomUUID(),
            savedInventories = mapOf(account to snapshot(if (index == 1) "bad" else "good"))) }
        val island = Island(account, "coop", 0, 100, 0, CoreState(CoreMode.WOOD), ownerPlayerId = ids[0],
            members = ids.drop(1).associateWith { com.alytanta.oneblock.island.IslandRole.MEMBER })
        val repo = repository(s)
        try {
            repo.load(); members.forEach(repo::saveProfile); repo.saveIsland(island); assertTrue(repo.flush())
            val captures = ids.associateWith { com.alytanta.oneblock.player.RemovalCapture(false) }
            assertThrows(IllegalStateException::class.java) { repo.removeProfileMembers(island, members, UUID.randomUUID(), 100,
                true, true, captures) { data -> if (data == "bad") error("decode") else com.alytanta.oneblock.menu.Stack(org.bukkit.Material.DIAMOND) } }
            assertEquals(island, repo.loadIslands().single()); assertEquals(members.toSet(), repo.loadProfiles().toSet())
            assertTrue(repo.marketState().claims.isEmpty())
            repo.removeProfileMembers(island, members, UUID.randomUUID(), 100, true, true, captures) {
                com.alytanta.oneblock.menu.Stack(org.bukkit.Material.DIAMOND)
            }
            assertEquals(100, repo.loadIslands().single().retiredAt); assertTrue(repo.flush())
        } finally { repo.close() }
    }
    @Test fun `unknown offline admin eviction captures salvage once before clearing and persists the hash`() {
        val s = settings(); val owner = UUID.randomUUID(); val member = UUID.randomUUID()
        val island = Island(owner, "coop", 0, 100, 0, CoreState(CoreMode.WOOD), members = mapOf(member to com.alytanta.oneblock.island.IslandRole.MEMBER))
        val player = PlayerProfile(member, activeAccount = owner)
        val snapshot = ProfileInventory(items = List(43) { if (it == 0) "item-bytes" else null })
        val repo = repository(s)
        try {
            repo.load(); repo.saveProfile(PlayerProfile(owner)); repo.saveProfile(player); repo.saveIsland(island); assertTrue(repo.flush())
            val plan = repo.removeProfileMembers(island, listOf(player), UUID.randomUUID(), 100, true, false,
                mapOf(member to com.alytanta.oneblock.player.RemovalCapture(true))).single()
            assertTrue(plan.player.eviction!!.pendingCapture)
            val updated = repo.captureEviction(plan.player, snapshot) { com.alytanta.oneblock.menu.Stack(org.bukkit.Material.DIAMOND, 3) }
            assertFalse(updated.eviction!!.pendingCapture); assertEquals(snapshot.hash(), updated.eviction.beforeHash)
            assertEquals(3, repo.marketState().claims.values.single().amount)
            assertThrows(IllegalStateException::class.java) { repo.captureEviction(updated, snapshot) { error("Must not decode twice") } }
            assertTrue(repo.flush())
        } finally { repo.close() }
        val reopened = repository(s)
        try { reopened.load(); assertEquals(1, reopened.marketState().claims.size)
            assertEquals(snapshot.hash(), reopened.loadProfiles().single { it.uniqueId == member }.eviction!!.beforeHash)
        } finally { reopened.close() }
    }
    @Test fun `harvest and full status persist and external name number survives later mining writes`() {
        val s = settings(); val actor = UUID.randomUUID(); val id = UUID.randomUUID()
        val profile = PlayerProfile(actor,harvested=mapOf("iron_ore" to 7L),inventoryFull=true,inventoryNotifications=false)
        val island = Island(id,"oneblock:island_$id",0,60,0,CoreState(CoreMode.WOOD),ownerPlayerId=actor,publicId="0002",nameNumber=1,profileName="Custom",createdAt=1234)
        val repo = repository(s)
        try {
            repo.load(); repo.saveProfile(profile); repo.saveIsland(island); assertTrue(repo.flush())
            connection(s).use { c -> c.createStatement().use { st ->
                st.executeQuery("SELECT inventory_full,state->'harvested'->>'iron_ore' FROM ob_players").use { r -> r.next(); assertTrue(r.getBoolean(1)); assertEquals("7",r.getString(2)) }
                st.execute("UPDATE ob_profiles SET name_number=2,custom_name=NULL WHERE public_id='0002'")
            } }
            assertEquals(2,repo.readNameNumbers().get()[id])
            assertNull(repo.readProfileNaming().get()[id]?.custom)
            repo.saveIsland(island.copy(core=island.core.copy(breaks=1))); assertTrue(repo.flush())
        } finally { repo.close() }
        val restarted=repository(s)
        try {
            restarted.load()
            assertEquals(profile,restarted.loadProfiles().single())
            assertEquals(2,restarted.loadIslands().single().nameNumber)
            assertEquals("0002",restarted.loadIslands().single().publicId)
            assertNull(restarted.loadIslands().single().profileName)
        } finally { restarted.close() }
    }
    @Test fun `schema ten upgrades with backup and preserves accounts and wallets`() {
        val s=settings(); val actor=UUID.randomUUID()
        repository(s).also { repo -> repo.load(); repo.saveProfile(PlayerProfile(actor)); repo.saveIsland(Island(actor,"private",0,60,0,CoreState(CoreMode.WOOD),coins=42)); assertTrue(repo.flush()); repo.close() }
        connection(s).use { c -> c.createStatement().use { st ->
            st.execute("ALTER TABLE ob_system DROP CONSTRAINT ob_system_schema_version_check")
            st.execute("UPDATE ob_system SET schema_version=10")
            st.execute("DROP VIEW ob_profile_overview")
            st.execute("ALTER TABLE ob_profiles DROP COLUMN public_id, DROP COLUMN name_number, DROP COLUMN custom_name")
            st.execute("ALTER TABLE ob_players DROP COLUMN inventory_full")
        } }
        val upgraded=repository(s)
        try { upgraded.load(); assertEquals(42L,upgraded.loadIslands().single().coins); assertTrue(folder.resolve("backups").toFile().listFiles()!!.any { it.name.startsWith("before-v12-") }) }
        finally { upgraded.close() }
        connection(s).use { c -> c.createStatement().use { st -> st.executeQuery("SELECT schema_version FROM ob_system").use { r -> r.next();assertEquals(12,r.getInt(1)) } } }
    }
}

