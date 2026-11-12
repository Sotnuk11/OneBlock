package com.alytanta.oneblock.combat

import com.alytanta.oneblock.config.PluginConfig
import com.alytanta.oneblock.island.*
import com.alytanta.oneblock.oneblock.*
import com.alytanta.oneblock.market.*
import com.alytanta.oneblock.menu.MenuFixture
import com.alytanta.oneblock.player.*
import com.alytanta.oneblock.release.*
import com.alytanta.oneblock.storage.*
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.plugin.Plugin
import org.junit.jupiter.api.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.*
import java.util.logging.Logger

class CombatStorageTest {
    @TempDir lateinit var folder:Path
    companion object {
        private lateinit var postgres:EmbeddedPostgres
        @JvmStatic @BeforeAll fun start() { postgres=EmbeddedPostgres.start() }
        @JvmStatic @AfterAll fun stop() { if(::postgres.isInitialized)postgres.close() }
    }
    private val workers=Executors.newFixedThreadPool(4) { runnable -> Thread(runnable,"combat-test-async") }
    @AfterEach fun stopWorkers() { workers.shutdownNow() }
    private val scheduler=MenuFixture.stub(io.papermc.paper.threadedregions.scheduler.AsyncScheduler::class.java) { name,args ->
        if(name=="runNow")workers.execute { @Suppress("UNCHECKED_CAST") (args!![1] as java.util.function.Consumer<Any?>).accept(null) }
        null
    }
    private val server=MenuFixture.stub(org.bukkit.Server::class.java) {name,_ -> if(name=="getAsyncScheduler")scheduler else null}
    private val plugin=MenuFixture.stub(Plugin::class.java) { name,_ -> when(name) {"getLogger"->Logger.getAnonymousLogger();"getServer"->server;else->null} }
    private fun settings():DatabaseSettings {
        val db="combat_"+UUID.randomUUID().toString().replace("-","")
        postgres.postgresDatabase.connection.use { c -> c.createStatement().use { it.execute("CREATE DATABASE $db") } }
        return DatabaseSettings("127.0.0.1",postgres.port,db,"postgres","test-only","disable","disable",5)
    }
    private fun open(s:DatabaseSettings)=PostgresStorageService(folder.toFile(),Logger.getAnonymousLogger(),s).also { it.load() }
    private fun connection(s:DatabaseSettings)=java.sql.DriverManager.getConnection("jdbc:postgresql://127.0.0.1:${s.port}/${s.database}","postgres","test-only").apply { createStatement().use { it.execute("SET search_path TO ${s.schema}") } }
    private fun rules():ReleaseRules {
        fun resource(name:String)=folder.resolve(name).toFile().also { it.writeText(javaClass.getResourceAsStream("/$name")!!.reader().readText()) }
        val r=ReleaseRules.read(resource("progression.yml"),true).first!!
        val items=com.alytanta.oneblock.items.ItemCatalog.read(resource("items.yml")).first!!
        val tools=r.recipes.values.flatMap {it.items}.mapNotNull {it.tool}.toSet()
        val c=CombatRules.read(resource("combat.yml"),items,tools).first!!
        return r.copy(recipes=r.recipes+c.recipes,combat=c)
    }
    private fun seed(repo:PostgresStorageService):Island {
        val id=UUID.randomUUID();repo.saveProfile(PlayerProfile(id,activeAccount=id))
        val i=Island(id,IslandWorldIdentity.worldName(id),0,60,0,CoreState(CoreMode.WOOD),coins=100000,releaseProgress=ReleaseProgress(chapter="developed"))
        repo.saveIsland(i);assertTrue(repo.flush());return i
    }
    private fun islands(repo:PostgresStorageService)=IslandService(plugin,PluginConfig.load(YamlConfiguration(),Logger.getAnonymousLogger()),repo,PlayerService(repo))
    private fun catalog(r:ReleaseRules)=CombatRewardCatalog(r.combat!!,r.combat.items.mapValues { "native-encoded:${it.key}" })
    private fun request(i:Island)=CombatRewardRequest("test.encounter",UUID.randomUUID(),i.owner,i.ownerPlayer,"elite_encounter")
    @Test fun `concurrent duplicate award has one receipt exact claims survive reopen backup and claim collection`() {
        val s=settings();var repo=open(s);val i=seed(repo);val q=request(i);val catalog=catalog(rules());val islands=islands(repo)
        val pool=Executors.newFixedThreadPool(8)
        try {
            val start=CountDownLatch(1)
            val calls=(1..32).map { pool.submit { start.await();islands.releaseUpdate(i.owner) { live,state -> CombatRewardDomain.grant(live,state,q,catalog) };repo.flushAsync().get(10,TimeUnit.SECONDS) } }
            start.countDown();calls.forEach { it.get(15,TimeUnit.SECONDS) }
            assertEquals(1,repo.marketState().combatReceipts.size)
            assertEquals(CombatRewardDomain.selected(q,catalog.rules.rewards.getValue(q.reward)).sumOf { it.amount },repo.marketState().claims.size)
            val exported=repo.backup().readText();assertTrue(exported.contains(q.fingerprint));assertTrue(exported.contains("native-encoded:battle_fragment"))
        } finally { pool.shutdownNow();repo.close() }
        repo=open(s)
        try {
            val live=repo.loadIslands().single();assertTrue(CombatRewardDomain.replay(live,repo.marketState(),q))
            val collected=repo.marketState().copy(claims=emptyMap(),transfers=emptyMap())
            assertTrue(CombatRewardDomain.grant(live,collected,q,catalog).state.claims.isEmpty())
        } finally { repo.close() }
    }
    @Test fun `reward claim insert failure rolls back receipt too reconnect and retry cannot duplicate`() {
        val s=settings();val repo=open(s);val i=seed(repo);val q=request(i);val catalog=catalog(rules());val islands=islands(repo)
        connection(s).use { c -> c.createStatement().use { st ->
            st.execute("CREATE FUNCTION reject_combat_claim() RETURNS trigger LANGUAGE plpgsql AS 'BEGIN RAISE EXCEPTION ''isolated combat fault''; END;'")
            st.execute("CREATE TRIGGER reject_combat_claim BEFORE INSERT ON ob_market FOR EACH ROW WHEN (NEW.record_type='claim') EXECUTE FUNCTION reject_combat_claim()")
        } }
        try {
            islands.releaseUpdate(i.owner) { live,state -> CombatRewardDomain.grant(live,state,q,catalog) }
            assertFalse(repo.flush());assertFalse(repo.healthy)
            connection(s).use { c -> c.createStatement().use { st ->
                st.executeQuery("SELECT count(*) FROM ob_market WHERE record_type='combat_receipt'").use { assertTrue(it.next());assertEquals(0,it.getInt(1)) }
                st.executeQuery("SELECT count(*) FROM ob_market WHERE record_type='claim'").use { it.next();assertEquals(0,it.getInt(1)) }
                st.execute("DROP TRIGGER reject_combat_claim ON ob_market")
            } }
            repo.reconnectAsync().get(10,TimeUnit.SECONDS)
            val replay=repo.releaseChange(repo.loadIslands().single()) { state -> CombatRewardDomain.grant(repo.loadIslands().single(),state,q,catalog) }
            assertEquals(1,replay.state.combatReceipts.size);assertTrue(repo.flush())
            assertEquals(CombatRewardDomain.selected(q,catalog.rules.rewards.getValue(q.reward)).sumOf { it.amount },repo.marketState().claims.size)
        } finally { repo.close() }
    }
    @Test fun `craft exact combat output remains escrowed through restart and review never repeats ingredients`() {
        val s=settings();var repo=open(s);val i=seed(repo);val r=rules();val d=ReleaseDomain(r)
        val wanted=d.plan(i,repo.marketState(),i.ownerPlayer,"recipe","rift_staff")
        val transfer=ItemTransfer(UUID.randomUUID(),i.owner,i.ownerPlayer,"STONE",1,TransferKind.RELEASE,0,null,"inventory-before","inventory-after",release=wanted,releaseItems=listOf("native-rift-staff-schema-1"))
        repo.releaseChange(i) { d.prepare(i,it,transfer) };assertTrue(repo.flush());repo.close();repo=open(s)
        try {
            var live=repo.loadIslands().single();assertTrue(repo.marketState().claims.isEmpty())
            live=repo.releaseChange(live) { d.settle(live,it,transfer.id,TransferStatus.REVIEW) }.island;assertTrue(repo.flush())
            assertTrue(repo.marketState().claims.isEmpty())
            val finished=repo.releaseChange(live) { d.settle(live,it,transfer.id,TransferStatus.DONE) }
            repo.releaseChange(finished.island) { d.settle(finished.island,it,transfer.id,TransferStatus.DONE) };assertTrue(repo.flush())
            val claim=repo.marketState().claims.values.single();assertEquals("native-rift-staff-schema-1",claim.itemData);assertEquals(i.ownerPlayer,claim.recipient)
        } finally { repo.close() }
    }
    @Test fun `slow database does not release durability barrier while concurrent distinct awards are preserved`() {
        val s=settings();val repo=open(s);val i=seed(repo);val catalog=catalog(rules());val islands=islands(repo)
        connection(s).use { c -> c.createStatement().use { it.execute("CREATE FUNCTION slow_combat_claim() RETURNS trigger LANGUAGE plpgsql AS 'BEGIN PERFORM pg_sleep(0.05); RETURN NEW; END;'");it.execute("CREATE TRIGGER slow_combat_claim BEFORE INSERT ON ob_market FOR EACH ROW WHEN (NEW.record_type='claim') EXECUTE FUNCTION slow_combat_claim()") } }
        try {
            val first=request(i);islands.releaseUpdate(i.owner) { live,state -> CombatRewardDomain.grant(live,state,first,catalog) }
            val barrier=repo.flushAsync();Thread.sleep(40);assertFalse(barrier.isDone)
            val second=request(i);islands.releaseUpdate(i.owner) { live,state -> CombatRewardDomain.grant(live,state,second,catalog) }
            val secondBarrier=repo.flushAsync();secondBarrier.get(15,TimeUnit.SECONDS);barrier.get(15,TimeUnit.SECONDS)
            assertEquals(2,repo.marketState().combatReceipts.size)
        } finally { repo.close() }
        val loaded=open(s)
        try { assertEquals(2,loaded.marketState().combatReceipts.size) } finally { loaded.close() }
    }
    @Test fun `public reward adapter persists on async worker ignores caller cancellation and closes admission`() {
        val s=settings();val repo=open(s);val i=seed(repo);val q=request(i);val catalog=catalog(rules())
        val service=CombatRewardService(plugin,repo,islands(repo)) {catalog}
        try {
            val cancelled=service.award(q);cancelled.cancel(false)
            service.award(q).get(10,TimeUnit.SECONDS)
            assertEquals(1,repo.marketState().combatReceipts.size)
            service.close()
            assertThrows(ExecutionException::class.java) {service.award(q.copy(event=UUID.randomUUID())).get(5,TimeUnit.SECONDS)}
            assertEquals(1,repo.marketState().combatReceipts.size)
        } finally {service.close();repo.close()}
    }
    @Test fun `adapter failure during SQL disconnect returns no acknowledgement and retry after reconnect publishes once`() {
        val s=settings();val repo=open(s);val i=seed(repo);val q=request(i);val catalog=catalog(rules())
        val service=CombatRewardService(plugin,repo,islands(repo)) {catalog}
        try {
            postgres.postgresDatabase.connection.use {c -> c.prepareStatement("SELECT pg_terminate_backend(pid) FROM pg_stat_activity WHERE application_name='one-block' AND datname=?").use {it.setString(1,s.database);it.executeQuery().close()} }
            assertThrows(ExecutionException::class.java) {service.award(q).get(10,TimeUnit.SECONDS)}
            assertFalse(repo.healthy)
            repo.reconnectAsync().get(10,TimeUnit.SECONDS)
            service.award(q).get(10,TimeUnit.SECONDS)
            assertEquals(1,repo.marketState().combatReceipts.size)
            assertEquals(CombatRewardDomain.selected(q,catalog.rules.rewards.getValue(q.reward)).sumOf {it.amount},repo.marketState().claims.size)
        } finally {service.close();repo.close()}
    }
    @Test fun `existing schema12 gains receipt kind without losing profile or old market rows`() {
        val s=settings();var repo=open(s);val i=seed(repo)
        val oldClaim=MarketClaim(UUID.randomUUID(),i.owner,"DIRT",2,"before-combat-upgrade")
        repo.releaseChange(i) {state -> ReleaseChange(i,state.copy(claims=state.claims+(oldClaim.id to oldClaim)))}
        assertTrue(repo.flush());repo.close()
        connection(s).use {c -> c.createStatement().use {st ->
            st.execute("ALTER TABLE ob_market DROP CONSTRAINT ob_market_record_type_check")
            st.execute("ALTER TABLE ob_market ADD CONSTRAINT ob_market_record_type_check CHECK(record_type IN ('order','claim','handoff','bazaar_trade','auction','merchant_sale','player_trade'))")
        } }
        repo=open(s)
        try {
            val live=repo.loadIslands().single()
            assertEquals(i.owner,live.owner);assertEquals(i.worldName,live.worldName);assertEquals(i.coins,live.coins);assertEquals(i.releaseProgress,live.releaseProgress)
            assertEquals(oldClaim,repo.marketState().claims[oldClaim.id])
            val q=request(i);repo.releaseChange(live) {state -> CombatRewardDomain.grant(live,state,q,catalog(rules()))};assertTrue(repo.flush())
        } finally {repo.close()}
        repo=open(s)
        try {assertEquals(1,repo.marketState().combatReceipts.size);assertEquals(oldClaim,repo.marketState().claims[oldClaim.id])} finally {repo.close()}
    }
    @Test fun `frozen producer payload survives config removal SQL failure reopen and concurrent replay without changing output`() {
        val s=settings();var repo=open(s);val i=seed(repo);val frozen=PreparedCombatReward.prepare(request(i),catalog(rules()))
        val saved=com.google.gson.Gson().toJson(frozen)
        val actorPool=Executors.newFixedThreadPool(8)
        try {
            val service=CombatRewardService(plugin,repo,islands(repo)) {null} // Config removed after entitlement was journaled.
            postgres.postgresDatabase.connection.use {c->c.prepareStatement("SELECT pg_terminate_backend(pid) FROM pg_stat_activity WHERE application_name='one-block' AND datname=?").use {it.setString(1,s.database);it.executeQuery().close()}}
            assertThrows(ExecutionException::class.java) {service.awardPrepared(frozen).get(10,TimeUnit.SECONDS)}
            repo.reconnectAsync().get(10,TimeUnit.SECONDS)
            val barrier=CyclicBarrier(8)
            val calls=(1..8).map {actorPool.submit {barrier.await(5,TimeUnit.SECONDS);service.awardPrepared(frozen).get(10,TimeUnit.SECONDS)}}
            calls.forEach {it.get(15,TimeUnit.SECONDS)}
            assertEquals(1,repo.marketState().combatReceipts.size)
            val exact=repo.marketState().claims.values.map {it.product to it.itemData}.groupingBy {it}.eachCount()
            assertEquals(frozen.items.map {it.product to it.itemData}.groupingBy {it}.eachCount(),exact)
            service.close();repo.close();repo=open(s)
            val restarted=CombatRewardService(plugin,repo,islands(repo)) {null}
            val restored=com.google.gson.Gson().fromJson(saved,PreparedCombatReward::class.java)
            restarted.awardPrepared(restored).get(10,TimeUnit.SECONDS)
            assertEquals(exact,repo.marketState().claims.values.map {it.product to it.itemData}.groupingBy {it}.eachCount())
            val changed=restored.items.toMutableList().also {it[0]=it[0].copy(itemData="different-exact-bytes")}
            val conflict=PreparedCombatReward(restored.request.copy(outputHash=PreparedCombatReward.hash(changed)),changed)
            assertThrows(ExecutionException::class.java) {restarted.awardPrepared(conflict).get(10,TimeUnit.SECONDS)}
            assertEquals(1,repo.marketState().combatReceipts.size);restarted.close()
        } finally {actorPool.shutdownNow();repo.close()}
    }
}
