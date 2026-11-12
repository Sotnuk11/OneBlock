package com.alytanta.oneblock.release

import com.alytanta.oneblock.island.*
import com.alytanta.oneblock.oneblock.*
import com.alytanta.oneblock.market.*
import com.alytanta.oneblock.player.PlayerProfile
import com.alytanta.oneblock.storage.*
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres
import org.junit.jupiter.api.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.UUID
import java.util.logging.Logger

class ReleaseStorageTest {
    @TempDir lateinit var folder: Path
    companion object {
        private lateinit var postgres: EmbeddedPostgres
        @JvmStatic @BeforeAll fun start() { postgres=EmbeddedPostgres.start() }
        @JvmStatic @AfterAll fun stop() { if(::postgres.isInitialized)postgres.close() }
    }
    private fun settings(): DatabaseSettings {
        val db="release_"+UUID.randomUUID().toString().replace("-","")
        postgres.postgresDatabase.connection.use { c -> c.createStatement().use { it.execute("CREATE DATABASE $db") } }
        return DatabaseSettings("127.0.0.1",postgres.port,db,"postgres","test-only","disable","disable",5)
    }
    private fun open(s: DatabaseSettings)=PostgresStorageService(folder.toFile(),Logger.getAnonymousLogger(),s).also { it.load() }
    private fun rules(): ReleaseRules = folder.resolve("progression.yml").toFile().let { file ->
        file.writeText(javaClass.getResourceAsStream("/progression.yml")!!.reader().use { it.readText() });ReleaseRules.read(file,true).first!! }
    private fun connection(s: DatabaseSettings)=java.sql.DriverManager.getConnection("jdbc:postgresql://127.0.0.1:${s.port}/${s.database}","postgres","test-only").apply { createStatement().use { it.execute("SET search_path TO ${s.schema}") } }
    private fun seed(repo: PostgresStorageService): Island {
        val id=UUID.randomUUID();val actor=UUID.randomUUID()
        repo.saveProfile(PlayerProfile(actor));repo.saveProfile(PlayerProfile(id))
        val island=Island(id,"test",0,60,0,CoreState(CoreMode.WOOD,breaks=90000),coins=50000,members=mapOf(actor to IslandRole.MEMBER),
            skills=mapOf("retired" to 99),releaseProgress=ReleaseProgress(chapter="workshop",charge=3000,members=mapOf(actor to MemberProgress(mapOf("mining" to 2700)))))
        repo.saveIsland(island);assertTrue(repo.flush());return island
    }
    private fun transfer(island: Island, rules: ReleaseRules, action: String="recipe", value: String="pickaxe_1"): ItemTransfer {
        val actor=island.members.keys.single();val intent=ReleaseDomain(rules).plan(island,MarketState(),actor,action,value)
        return ItemTransfer(UUID.randomUUID(),island.owner,actor,"STONE",1,TransferKind.RELEASE,intent.coins,null,"before","after",release=intent,
            releaseItems=intent.items.map { if(it.tool!=null)"preserved-native-tool-data" else null })
    }
    @Test fun `state roundtrip schema twelve legacy defaults and retired filter preserve independent personal XP`() {
        val s=settings();var repo=open(s);val island=seed(repo);val actor=island.members.keys.single()
        val legacy=island.copy(owner=UUID.randomUUID(),ownerPlayerId=island.owner,releaseProgress=null,skills=mapOf("mining" to 30))
        repo.saveIsland(legacy);assertTrue(repo.flush());val export=repo.backup();assertTrue(export.readText().contains("releaseProgress"));repo.close()
        connection(s).use { c -> c.createStatement().use { st ->
            st.executeQuery("SELECT schema_version FROM ob_system").use { assertTrue(it.next());assertEquals(12,it.getInt(1)) }
            st.executeQuery("SELECT state ? 'skills',state->'releaseProgress' FROM ob_profiles WHERE profile_id='${island.owner}'").use { assertTrue(it.next());assertFalse(it.getBoolean(1));assertTrue(it.getString(2).contains("2700")) }
        } }
        repo=open(s)
        try {
            val loaded=repo.loadIslands().single { it.owner==island.owner }
            assertEquals(2700,loaded.releaseProgress!!.member(actor).xp["mining"]);assertEquals(90000,loaded.core.breaks)
            org.junit.jupiter.api.Assertions.assertNull(repo.loadIslands().single { it.owner==legacy.owner }.releaseProgress)
            assertTrue(loaded.skills.isEmpty());assertTrue(loaded.releaseProgress.member(island.owner).xp.isEmpty())
        } finally { repo.close() }
    }
    @Test fun `prepared crafting reopens then commits exact personal item once even after member removal`() {
        val r=rules();val d=ReleaseDomain(r);val s=settings();var repo=open(s);val island=seed(repo);val t=transfer(island,r)
        repo.releaseChange(island) { state -> d.prepare(island,state,t) };assertTrue(repo.flush());repo.close();repo=open(s)
        try {
            val current=repo.loadIslands().single();assertEquals(48000,current.coins);assertTrue(repo.marketState().claims.isEmpty())
            val departed=current.copy(members=emptyMap());repo.saveIsland(departed)
            repo.releaseChange(departed) { state -> d.settle(departed,state,t.id,TransferStatus.DONE) };assertTrue(repo.flush())
            val settled=repo.loadIslands().single();repo.releaseChange(settled) { state -> d.settle(settled,state,t.id,TransferStatus.DONE) };assertTrue(repo.flush())
        } finally { repo.close() }
        repo=open(s)
        try {
            val claim=repo.marketState().claims.values.single();assertEquals(t.actor,claim.recipient);assertEquals("preserved-native-tool-data",claim.itemData)
            assertEquals(48000,repo.loadIslands().single().coins);assertEquals(TransferStatus.DONE,repo.marketState().transfers[t.id]!!.status)
        } finally { repo.close() }
    }
    @Test fun `review survives reopen blocks other coop operations and does not publish claims`() {
        val r=rules();val d=ReleaseDomain(r);val s=settings();var repo=open(s);val island=seed(repo);val t=transfer(island,r)
        val prepared=repo.releaseChange(island) { d.prepare(island,it,t) }
        repo.releaseChange(prepared.island) { d.settle(prepared.island,it,t.id,TransferStatus.REVIEW) };assertTrue(repo.flush());repo.close();repo=open(s)
        try {
            val current=repo.loadIslands().single();assertTrue(repo.marketState().claims.isEmpty())
            assertThrows(IllegalArgumentException::class.java) { d.plan(current,repo.marketState(),island.owner,"pack","building") }
            val aborted=repo.releaseChange(current) { d.settle(current,it,t.id,TransferStatus.ABORTED) };assertTrue(repo.flush())
            assertEquals(50000,aborted.island.coins+aborted.state.claims.values.sumOf { it.amount })
            assertTrue(aborted.state.claims.values.all { it.product==null })
        } finally { repo.close() }
    }
    @Test fun `claim insert failure rolls back results and wallet together then reconnect publishes one recorded commit`() {
        val r=rules();val d=ReleaseDomain(r);val s=settings();val repo=open(s);val island=seed(repo);val t=transfer(island,r)
        val prepared=repo.releaseChange(island) { d.prepare(island,it,t) };assertTrue(repo.flush())
        connection(s).use { c -> c.createStatement().use { st ->
            st.execute("CREATE FUNCTION reject_release_claim() RETURNS trigger LANGUAGE plpgsql AS 'BEGIN RAISE EXCEPTION ''isolated fault''; END;'")
            st.execute("CREATE TRIGGER reject_release_claim BEFORE INSERT ON ob_market FOR EACH ROW WHEN (NEW.record_type='claim') EXECUTE FUNCTION reject_release_claim()")
        } }
        try {
            repo.releaseChange(prepared.island) { d.settle(prepared.island,it,t.id,TransferStatus.DONE) }
            assertFalse(repo.flush());assertFalse(repo.healthy)
            assertThrows(IllegalStateException::class.java) { repo.releaseChange(island) { ReleaseChange(island,it) } }
            connection(s).use { c -> c.createStatement().use { st ->
                st.executeQuery("SELECT status FROM ob_market WHERE record_id='${t.id}'").use { assertTrue(it.next());assertEquals("PREPARED",it.getString(1)) }
                st.executeQuery("SELECT count(*) FROM ob_market WHERE record_type='claim'").use { it.next();assertEquals(0,it.getInt(1)) }
                st.execute("DROP TRIGGER reject_release_claim ON ob_market")
            } }
            repo.reconnectAsync().get(10,java.util.concurrent.TimeUnit.SECONDS)
            assertTrue(repo.healthy);assertEquals(1,repo.marketState().claims.size);assertTrue(repo.flush())
        } finally { repo.close() }
        val reopened=open(s)
        try { assertEquals(1,reopened.marketState().claims.size);assertEquals(48000,reopened.loadIslands().single().coins) } finally { reopened.close() }
    }
}
