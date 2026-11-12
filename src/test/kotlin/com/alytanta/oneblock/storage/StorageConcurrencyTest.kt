package com.alytanta.oneblock.storage

import com.alytanta.oneblock.island.Island
import com.alytanta.oneblock.market.*
import com.alytanta.oneblock.oneblock.*
import com.alytanta.oneblock.player.*
import com.google.gson.JsonParser
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.io.TempDir
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import java.nio.file.Path
import java.sql.*
import java.util.UUID
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicBoolean
import java.util.logging.Logger

class StorageConcurrencyTest {
    @TempDir lateinit var folder: Path
    companion object {
        private lateinit var postgres: EmbeddedPostgres
        @JvmStatic @BeforeAll fun start() { postgres=EmbeddedPostgres.start() }
        @JvmStatic @AfterAll fun stop() { if(::postgres.isInitialized)postgres.close() }
    }
    private fun settings(): DatabaseSettings {
        val name="concurrency_"+UUID.randomUUID().toString().replace("-","")
        postgres.postgresDatabase.connection.use { it.createStatement().use { st -> st.execute("CREATE DATABASE $name") } }
        return DatabaseSettings("127.0.0.1",postgres.port,name,"postgres","test-only","disable","disable",5)
    }
    private fun sql(s:DatabaseSettings)=DriverManager.getConnection("jdbc:postgresql://127.0.0.1:${s.port}/${s.database}","postgres","test-only")
        .apply { createStatement().use { it.execute("SET search_path TO ${s.schema},public") } }
    private fun repo(s:DatabaseSettings)=PostgresStorageService(folder.toFile(),Logger.getAnonymousLogger(),s).apply { load();assertTrue(flush()) }
    private fun await(condition:()->Boolean) {
        val deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(10)
        while(!condition()) { check(System.nanoTime()<deadline) { "Timed out awaiting test condition" };Thread.sleep(10) }
    }
    private fun awaitWriterLock(s:DatabaseSettings) = await {
        sql(s).use { c -> c.createStatement().use { st ->
            st.executeQuery("SELECT EXISTS(SELECT 1 FROM pg_stat_activity WHERE application_name='one-block' AND wait_event_type='Lock')").use { it.next();it.getBoolean(1) }
        } }
    }
    private fun lockWriter(s:DatabaseSettings)=sql(s).apply {
        autoCommit=false;createStatement().use { it.executeQuery("SELECT revision FROM ob_system FOR UPDATE").close() }
    }
    private fun island(id:UUID)=Island(id,"test",0,100,0,CoreState(CoreMode.WOOD),coins=100)

    @Test fun `guild reward concurrent replay has one shared claim XP and discovery and durable task state`() {
        val settings=settings();val repo=repo(settings);val id=UUID.randomUUID()
        val file=folder.resolve("progression.yml").toFile();file.writeText(javaClass.getResourceAsStream("/progression.yml")!!.reader().readText())
        val rules=com.alytanta.oneblock.release.ReleaseRules.read(file,true).first!!
        val activity=com.alytanta.oneblock.activities.ActivityRules.read(folder.resolve("activities.yml").toFile())
        val initial=com.alytanta.oneblock.activities.ActivityDomain.accept(island(id).copy(releaseProgress=com.alytanta.oneblock.release.ReleaseProgress()),MarketState(),id,"start_supply",10000,activity)
        repo.saveProfile(PlayerProfile(id));repo.saveIsland(initial);assertTrue(repo.flush());repo.close()
        val again=repo(settings)
        try {
            val loaded=again.loadIslands().single();assertEquals(initial.releaseProgress,loaded.releaseProgress)
            val domain=com.alytanta.oneblock.release.ReleaseDomain(rules)
            val q=domain.plan(loaded,again.marketState(),id,"guild-deliver",loaded.releaseProgress!!.activity.order!!.id.toString())
            val t=ItemTransfer(UUID.randomUUID(),id,id,"STONE",1,TransferKind.RELEASE,0,null,"before","after",release=q,releaseItems=listOf("frozen-fragment"))
            again.releaseChange(loaded) {domain.prepare(loaded,it,t)};assertTrue(again.flush())
            val pool=Executors.newFixedThreadPool(8)
            try {(1..8).map {pool.submit {again.releaseChange(loaded) {book->domain.settle(again.loadIslands().single(),book,t.id,TransferStatus.DONE)};again.flushAsync().get(10,TimeUnit.SECONDS)}}.forEach {it.get(20,TimeUnit.SECONDS)}}finally {pool.shutdownNow()}
            val done=again.loadIslands().single();assertEquals(3,done.releaseProgress!!.activity.discovery);assertEquals(1,done.releaseProgress!!.activity.completed)
            assertEquals(100,done.releaseProgress!!.member(id).xp["mining"]);assertEquals(1,again.marketState().claims.size);assertNull(again.marketState().claims.values.single().recipient)
        } finally {again.close()}
    }
    @Test fun `guild completion unknown COMMIT reconciles progress transfer and exact shared reward`() {
        val settings=settings();val repo=repo(settings);val id=UUID.randomUUID()
        val file=folder.resolve("progression.yml").toFile();file.writeText(javaClass.getResourceAsStream("/progression.yml")!!.reader().readText())
        val rules=com.alytanta.oneblock.release.ReleaseRules.read(file,true).first!!;val d=com.alytanta.oneblock.release.ReleaseDomain(rules)
        val a=com.alytanta.oneblock.activities.ActivityRules.read(folder.resolve("activities.yml").toFile())
        val initial=com.alytanta.oneblock.activities.ActivityDomain.accept(island(id).copy(releaseProgress=com.alytanta.oneblock.release.ReleaseProgress()),MarketState(),id,"start_supply",10000,a)
        repo.saveProfile(PlayerProfile(id));repo.saveIsland(initial);assertTrue(repo.flush())
        val q=d.plan(initial,MarketState(),id,"guild-deliver",initial.releaseProgress!!.activity.order!!.id.toString())
        val t=ItemTransfer(UUID.randomUUID(),id,id,"STONE",1,TransferKind.RELEASE,0,null,"before","after",release=q,releaseItems=listOf("exact"))
        repo.releaseChange(initial) {d.prepare(initial,it,t)};assertTrue(repo.flush())
        val field=PostgresStorageService::class.java.getDeclaredField("connection").apply {isAccessible=true}
        val connection=field.get(repo) as Connection;val lost=AtomicBoolean()
        field.set(repo,Proxy.newProxyInstance(Connection::class.java.classLoader,arrayOf(Connection::class.java)) { _,method,args ->
            val result=try {method.invoke(connection,*(args?:emptyArray()))}catch(e:InvocationTargetException){throw e.cause!!}
            if(method.name=="commit" && lost.compareAndSet(false,true))throw SQLException("simulated lost guild acknowledgement","08006")
            result
        } as Connection)
        try {
            repo.releaseChange(initial) {d.settle(repo.loadIslands().single(),it,t.id,TransferStatus.DONE)}
            assertThrows(ExecutionException::class.java) {repo.flushAsync().get(10,TimeUnit.SECONDS)}
            repo.reconnectAsync().get(10,TimeUnit.SECONDS);assertTrue(repo.flush())
            val loaded=repo.loadIslands().single();assertEquals(1,loaded.releaseProgress!!.activity.completed);assertEquals(1,repo.marketState().claims.size)
            assertEquals(repo.marketState(),d.settle(loaded,repo.marketState(),t.id,TransferStatus.DONE).state)
        } finally {repo.close()}
    }

    @Test fun `concurrent chest confirmations debit once and exact personal contents survive database reopen`() {
        val settings=settings();val repo=repo(settings);val id=UUID.randomUUID();val initial=island(id).copy(coins=1000,releaseProgress=com.alytanta.oneblock.release.ReleaseProgress(chapter="developed"))
        repo.saveProfile(PlayerProfile(id));repo.saveIsland(initial)
        val offer=com.alytanta.oneblock.rewards.ChestOffer(UUID.randomUUID(),id,id,"test",500,listOf(com.alytanta.oneblock.rewards.LootItem("oneblock:warden_helmet","PLAYER_HEAD","exact-helmet")),1000,1000+com.alytanta.oneblock.rewards.ChestDomain.TTL)
        val progress=com.alytanta.oneblock.rewards.ChestProgress(com.alytanta.oneblock.rewards.ChestDomain.progressId(id,id),id,id,counters=mapOf("warden" to com.alytanta.oneblock.rewards.PityCounter(2)))
        repo.changeMarket(initial) {MarketChange(initial.coins,it.copy(chests=mapOf(offer.id to offer),chestProgress=mapOf(progress.id to progress)),Unit)};assertTrue(repo.flush())
        val pool=Executors.newFixedThreadPool(8);val start=CyclicBarrier(8)
        try {
            (1..8).map {pool.submit {start.await();repo.releaseChange(initial) {book->
                val live=repo.loadIslands().single();val opened=com.alytanta.oneblock.rewards.ChestDomain.open(live,book,id,offer,2000)
                com.alytanta.oneblock.release.ReleaseChange(live.copy(coins=opened.coins),opened.state)
            };repo.flushAsync().get(10,TimeUnit.SECONDS)}}.forEach {it.get(20,TimeUnit.SECONDS)}
            assertEquals(500,repo.loadIslands().single().coins);assertEquals(1,repo.marketState().claims.size)
            assertEquals(progress,repo.marketState().chestProgress[progress.id])
            val backup=repo.backup().readText();assertTrue(backup.contains("room_chest"));assertTrue(backup.contains("exact-helmet"))
            repo.close();val again=repo(settings)
            try {assertEquals(500,again.loadIslands().single().coins);assertEquals("OPENED",again.marketState().chests[offer.id]!!.status);assertEquals("exact-helmet",again.marketState().claims.values.single().itemData)} finally {again.close()}
        } finally {pool.shutdownNow();repo.close()}
    }
    @Test fun `lost chest commit acknowledgement reconciles wallet offer and contents without another charge`() {
        val settings=settings();val repo=repo(settings);val id=UUID.randomUUID();val initial=island(id).copy(coins=1000,releaseProgress=com.alytanta.oneblock.release.ReleaseProgress(chapter="developed"))
        repo.saveProfile(PlayerProfile(id));repo.saveIsland(initial)
        val offer=com.alytanta.oneblock.rewards.ChestOffer(UUID.randomUUID(),id,id,"test",500,listOf(com.alytanta.oneblock.rewards.LootItem("oneblock:ranger_boots","LEATHER_BOOTS","exact-boots")),1000,1000+com.alytanta.oneblock.rewards.ChestDomain.TTL)
        repo.changeMarket(initial) {MarketChange(initial.coins,it.copy(chests=mapOf(offer.id to offer)),Unit)};assertTrue(repo.flush())
        val field=PostgresStorageService::class.java.getDeclaredField("connection").apply {isAccessible=true};val connection=field.get(repo) as Connection;val lost=AtomicBoolean()
        field.set(repo,Proxy.newProxyInstance(Connection::class.java.classLoader,arrayOf(Connection::class.java)) {_,method,args->
            val result=try {method.invoke(connection,*(args?:emptyArray()))}catch(e:InvocationTargetException){throw e.cause!!}
            if(method.name=="commit" && lost.compareAndSet(false,true))throw SQLException("lost chest COMMIT acknowledgement","08006")
            result
        } as Connection)
        try {
            repo.changeMarket(initial) {com.alytanta.oneblock.rewards.ChestDomain.open(initial,it,id,offer,2000)}
            assertThrows(ExecutionException::class.java) {repo.flushAsync().get(10,TimeUnit.SECONDS)}
            repo.reconnectAsync().get(10,TimeUnit.SECONDS);assertTrue(repo.flush())
            val live=repo.loadIslands().single();repo.changeMarket(live) {com.alytanta.oneblock.rewards.ChestDomain.open(live,it,id,offer,offer.expires!!+1000)}
            assertTrue(repo.flush());assertEquals(500,repo.loadIslands().single().coins);assertEquals(1,repo.marketState().claims.size)
            sql(settings).use {c->c.createStatement().use {st->st.executeQuery("SELECT count(*) FROM ob_market WHERE record_type='room_chest' AND status='OPENED'").use {r->assertTrue(r.next());assertEquals(1,r.getInt(1))}}}
        } finally {repo.close()}
    }
    @Test fun `room publication SQL failure rolls back receipt claims offers and pity then retries the frozen request`() {
        val settings=settings();val repo=repo(settings);val id=UUID.randomUUID();val initial=island(id).copy(releaseProgress=com.alytanta.oneblock.release.ReleaseProgress(chapter="developed"))
        repo.saveProfile(PlayerProfile(id));repo.saveIsland(initial);assertTrue(repo.flush())
        val parts=listOf("warden","ranger","weaver").associateWith {set->List(4) {n->com.alytanta.oneblock.rewards.LootItem("oneblock:${set}_$n","PLAYER_HEAD","exact:$set:$n")}}
        val room=com.alytanta.oneblock.rewards.PreparedRoomLoot(listOf(com.alytanta.oneblock.rewards.PreparedChest("Native chest",500,listOf(parts.getValue("ranger").first()))),com.alytanta.oneblock.rewards.PreparedPity(1,250,parts),1000)
        val items=listOf(com.alytanta.oneblock.combat.FrozenCombatItem("IRON_NUGGET","exact-fragment"))
        val request=com.alytanta.oneblock.combat.CombatRewardRequest("room.atomic",UUID.randomUUID(),id,id,"room",com.alytanta.oneblock.combat.PreparedCombatReward.payloadHash(items,room))
        val prepared=com.alytanta.oneblock.combat.PreparedCombatReward(request,items,room)
        sql(settings).use {c->c.createStatement().use {it.execute("ALTER TABLE ob_market ADD CONSTRAINT test_no_room_chest CHECK(record_type<>'room_chest')")}}
        try {
            repo.releaseChange(initial) {com.alytanta.oneblock.combat.CombatRewardDomain.grantPrepared(initial,it,prepared,100000)}
            assertThrows(ExecutionException::class.java) {repo.flushAsync().get(10,TimeUnit.SECONDS)}
            sql(settings).use {c->c.createStatement().use {st->st.executeQuery("SELECT count(*) FROM ob_market").use {r->r.next();assertEquals(0,r.getInt(1))};st.execute("ALTER TABLE ob_market DROP CONSTRAINT test_no_room_chest")}}
            repo.reconnectAsync().get(10,TimeUnit.SECONDS);assertTrue(repo.flush())
            val book=repo.marketState();assertEquals(1,book.combatReceipts.size);assertEquals(1,book.claims.size);assertEquals(2,book.chests.size);assertEquals(1,book.chestProgress.size)
            assertEquals(book,com.alytanta.oneblock.combat.CombatRewardDomain.grantPrepared(repo.loadIslands().single(),book,prepared,100000).state)
            assertEquals(0,book.chestProgress.values.single().counters.getValue("warden").misses)
        } finally {runCatching {sql(settings).use {c->c.createStatement().use {it.execute("ALTER TABLE ob_market DROP CONSTRAINT IF EXISTS test_no_room_chest")}}};repo.close()}
    }

    @Test fun `slow commit coalesces two thousand barriers without completing a later revision early`() {
        val s=settings();val repo=repo(s);val id=UUID.randomUUID();val initial=island(id)
        repo.saveProfile(PlayerProfile(id));repo.saveIsland(initial);assertTrue(repo.flush())
        try {
            lockWriter(s).use { blocker ->
                repo.saveIsland(initial.copy(coins=101));val first=repo.flushAsync();awaitWriterLock(s)
                repo.saveIsland(initial.copy(coins=202));val later=repo.flushAsync()
                val barriers=List(2000) { repo.flushAsync() }
                assertTrue(repo.queuedOperations<=1,"Each caller must share the queued snapshot")
                assertFalse(later.isDone);assertFalse(first.isDone)
                assertTrue(barriers.first().cancel(false))
                blocker.commit()
                first.get(10,TimeUnit.SECONDS);later.get(10,TimeUnit.SECONDS)
                barriers.drop(1).forEach { it.get(10,TimeUnit.SECONDS) }
            }
            sql(s).use { c -> c.createStatement().use { st -> st.executeQuery("SELECT coins FROM ob_profiles").use { it.next();assertEquals(202,it.getLong(1)) } } }
        } finally { repo.close() }
    }

    @Test fun `concurrent player changes retain large inventories clear journals and survive restart`() {
        val s=settings();val repo=repo(s);val players=PlayerService(repo)
        val ids=List(8) { UUID.randomUUID() };val account=UUID.randomUUID()
        val inventory=ProfileInventory(items=List(43) { "exact-$it-"+"x".repeat(4096) })
        ids.forEach { id -> players.update(id) { it.copy(savedInventories=mapOf(account to inventory),
            profileCheckpoints=mapOf(account to inventory),profileSwitch=ProfileSwitch(UUID.randomUUID(),null,account,"hash")) } }
        assertTrue(repo.flush())
        val pool=Executors.newFixedThreadPool(8)
        try {
            ids.map { id -> pool.submit {
                repeat(50) {
                    players.update(id) { p -> p.copy(harvested=mapOf("oak_log" to (p.harvested?.get("oak_log")?:0)+1),inventoryFull=it%2==0) }
                    repo.flushAsync()
                }
                players.update(id) { it.copy(profileSwitch=null,profileCheckpoints=null,language=null) }
            } }.forEach { it.get(20,TimeUnit.SECONDS) }
            assertTrue(repo.flush())
        } finally { pool.shutdownNow();repo.close() }
        val reopened=repo(s)
        try { reopened.loadProfiles().forEach {
            assertEquals(50L,it.harvested?.get("oak_log"));assertEquals(inventory,it.savedInventories?.get(account))
            assertNull(it.profileSwitch);assertNull(it.profileCheckpoints);assertNull(it.language)
        };assertEquals(ids.size,reopened.loadProfiles().size) } finally { reopened.close() }
    }

    @Test fun `lost commit acknowledgement reconnects by token without replaying escrow or audit`() {
        val s=settings();val repo=repo(s);val id=UUID.randomUUID();val initial=island(id)
        repo.saveProfile(PlayerProfile(id));repo.saveIsland(initial);assertTrue(repo.flush())
        val field=PostgresStorageService::class.java.getDeclaredField("connection").apply { isAccessible=true }
        val connection=field.get(repo) as Connection;val lost=AtomicBoolean()
        val proxy=Proxy.newProxyInstance(Connection::class.java.classLoader,arrayOf(Connection::class.java)) { _, method, args ->
            val result=try { method.invoke(connection,*(args?:emptyArray())) } catch(e:InvocationTargetException) { throw e.cause!! }
            if(method.name=="commit" && lost.compareAndSet(false,true))throw SQLException("simulated lost acknowledgement","08006")
            result
        } as Connection
        field.set(repo,proxy)
        val claim=MarketClaim(UUID.randomUUID(),id,"DIAMOND",1,"test","exact-item")
        repo.changeMarket(initial) { MarketChange(50,MarketState(claims=mapOf(claim.id to claim)),Unit,
            OperationLog(operation="TOKEN_TEST",actor=id,account=id,success=true,status="SUCCESS")) }
        try {
            assertThrows(ExecutionException::class.java) { repo.flushAsync().get(10,TimeUnit.SECONDS) }
            assertFalse(repo.healthy);assertThrows(IllegalStateException::class.java) { repo.saveProfile(PlayerProfile(UUID.randomUUID())) }
            repo.reconnectAsync().get(10,TimeUnit.SECONDS);assertTrue(repo.healthy);assertTrue(repo.flush())
            sql(s).use { c -> c.createStatement().use { st ->
                st.executeQuery("SELECT coins FROM ob_profiles").use { it.next();assertEquals(50,it.getLong(1)) }
                st.executeQuery("SELECT count(*) FROM ob_events WHERE action='TOKEN_TEST'").use { it.next();assertEquals(1,it.getInt(1)) }
            } }
            assertEquals(claim,repo.marketState().claims[claim.id])
        } finally { repo.close() }
    }

    @Test fun `backup exports multiple cursor batches without a redundant commit and leaves no partial file`() {
        val s=settings();val repo=repo(s)
        try {
            repeat(800) { repo.audit(OperationLog(operation="BACKUP_TEST",actor=null,account=null,success=true,status="SUCCESS",details=mapOf("row" to it))) }
            assertTrue(repo.flush())
            fun revision()=sql(s).use { c -> c.createStatement().use { st -> st.executeQuery("SELECT revision FROM ob_system").use { it.next();it.getLong(1) } } }
            val before=revision();val file=repo.backup()
            val root=JsonParser.parseString(file.readText()).asJsonObject
            assertEquals(800,root.getAsJsonArray("${s.schema}.ob_events").count { it.asJsonObject.get("action").asString=="BACKUP_TEST" })
            assertEquals(6,root.size());assertEquals(before,revision())
            assertTrue(file.parentFile.listFiles()!!.none { it.name.endsWith(".partial") })
        } finally { repo.close() }
    }

    @Test fun `shutdown stops admission before final snapshot and drains an accepted late revision`() {
        val s=settings();val repo=repo(s);val id=UUID.randomUUID();val initial=island(id)
        repo.saveProfile(PlayerProfile(id));repo.saveIsland(initial);assertTrue(repo.flush())
        val pool=Executors.newSingleThreadExecutor()
        try {
            lockWriter(s).use { blocker ->
                repo.saveIsland(initial.copy(coins=111));val pending=repo.flushAsync();awaitWriterLock(s)
                repo.saveIsland(initial.copy(coins=222))
                val closed=pool.submit { repo.close() }
                await { repo.flushAsync().isCompletedExceptionally }
                assertThrows(IllegalStateException::class.java) { repo.saveIsland(initial.copy(coins=333)) }
                blocker.commit();pending.get(10,TimeUnit.SECONDS);closed.get(10,TimeUnit.SECONDS)
            }
        } finally { pool.shutdownNow();repo.close() }
        val reopened=repo(s)
        try { assertEquals(222,reopened.loadIslands().single().coins) } finally { reopened.close() }
    }

    @Test fun `external names survive unrelated writes and uncommitted local renames win an old read`() {
        val s=settings();val repo=repo(s);val id=UUID.randomUUID();val initial=island(id).copy(profileName="Original",nameNumber=1)
        repo.saveProfile(PlayerProfile(id));repo.saveIsland(initial);assertTrue(repo.flush())
        try {
            sql(s).use { c -> c.prepareStatement("UPDATE ob_profiles SET custom_name='External',name_number=2 WHERE profile_id=?").use {
                it.setObject(1,id);it.executeUpdate()
            } }
            repo.saveIsland(initial.copy(coins=101));assertTrue(repo.flush())
            assertEquals(ProfileNaming(2,"External"),repo.readProfileNaming().get(10,TimeUnit.SECONDS)[id])
            repo.saveIsland(initial.copy(coins=102,profileName="Local"))
            assertEquals(ProfileNaming(1,"Local"),repo.readProfileNaming().get(10,TimeUnit.SECONDS)[id])
            assertTrue(repo.flush())
            assertEquals(ProfileNaming(2,"Local"),repo.readProfileNaming().get(10,TimeUnit.SECONDS)[id])
        } finally { repo.close() }
    }

    @Test fun `failed cursor export cleans partial files and reconnect retains durable state`() {
        val s=settings();val repo=repo(s);val id=UUID.randomUUID()
        repo.saveProfile(PlayerProfile(id));repo.saveIsland(island(id));assertTrue(repo.flush())
        val field=PostgresStorageService::class.java.getDeclaredField("connection").apply { isAccessible=true }
        val connection=field.get(repo) as Connection;var exported=0
        field.set(repo,Proxy.newProxyInstance(Connection::class.java.classLoader,arrayOf(Connection::class.java)) { _,method,args ->
            val result=try { method.invoke(connection,*(args?:emptyArray())) } catch(e:InvocationTargetException) { throw e.cause!! }
            if(method.name=="createStatement") {
                val statement=result as Statement
                Proxy.newProxyInstance(Statement::class.java.classLoader,arrayOf(Statement::class.java)) { _,call,values ->
                    if(call.name=="executeQuery" && values!![0].toString().contains("row_to_json") && ++exported==2)
                        throw SQLException("simulated export disconnection","08006")
                    try { call.invoke(statement,*(values?:emptyArray())) } catch(e:InvocationTargetException) { throw e.cause!! }
                }
            } else result
        } as Connection)
        try {
            assertThrows(ExecutionException::class.java) { repo.backupAsync().get(10,TimeUnit.SECONDS) }
            assertFalse(repo.healthy);assertTrue(folder.resolve("backups").toFile().listFiles().orEmpty().isEmpty())
            repo.reconnectAsync().get(10,TimeUnit.SECONDS);assertTrue(repo.healthy)
            assertEquals(100,repo.loadIslands().single().coins)
            assertTrue(repo.backup().isFile)
        } finally { repo.close() }
    }

    @Test fun `reconnect refuses an unexpected database revision instead of overwriting it`() {
        val s=settings();val repo=repo(s)
        try {
            sql(s).use { c -> c.createStatement().use { it.executeUpdate("UPDATE ob_system SET revision=revision+99") } }
            assertThrows(ExecutionException::class.java) { repo.reconnectAsync().get(10,TimeUnit.SECONDS) }
            assertFalse(repo.healthy)
            assertTrue(repo.flushAsync().isCompletedExceptionally)
        } finally { repo.close() }
    }

    @Test fun `filtered audit events do not advance revision or queue empty flushes`() {
        val s=settings();val repo=repo(s)
        fun revision()=sql(s).use { c -> c.createStatement().use { st -> st.executeQuery("SELECT revision FROM ob_system").use { it.next();it.getLong(1) } } }
        try {
            val before=revision()
            repeat(100) {
                repo.audit(OperationLog(operation="NPC_OPEN",actor=null,account=null,success=true,status="SUCCESS"))
                repo.audit(OperationLog(operation="RESPONSE",actor=null,account=null,success=true,status="SUCCESS"))
            }
            assertTrue(repo.flush());assertEquals(before,revision())
        } finally { repo.close() }
    }

    @Test fun `shutdown reconnects a broken session and commits pending state before releasing ownership`() {
        val s=settings();val repo=repo(s);val id=UUID.randomUUID();val initial=island(id)
        repo.saveProfile(PlayerProfile(id));repo.saveIsland(initial);assertTrue(repo.flush())
        repo.saveIsland(initial.copy(coins=321))
        sql(s).use { c -> c.createStatement().use { st ->
            st.executeQuery("SELECT pg_terminate_backend(pid) FROM pg_stat_activity WHERE application_name='one-block' AND datname=current_database()").close()
        } }
        assertFalse(repo.flush());assertFalse(repo.healthy)
        repo.close()
        val reopened=repo(s)
        try { assertEquals(321,reopened.loadIslands().single().coins) } finally { reopened.close() }
    }

    @Test fun `queued barriers after a connection failure do not disable automatic reconnect`() {
        val s=settings();val repo=repo(s);val id=UUID.randomUUID();val initial=island(id)
        repo.saveProfile(PlayerProfile(id));repo.saveIsland(initial);assertTrue(repo.flush())
        try {
            lockWriter(s).use { blocker ->
                repo.saveIsland(initial.copy(coins=111));val first=repo.flushAsync();awaitWriterLock(s)
                repo.saveIsland(initial.copy(coins=222));val second=repo.flushAsync()
                sql(s).use { c -> c.createStatement().use { st ->
                    st.executeQuery("SELECT pg_terminate_backend(pid) FROM pg_stat_activity WHERE application_name='one-block' AND datname=current_database()").close()
                } }
                assertThrows(ExecutionException::class.java) { first.get(10,TimeUnit.SECONDS) }
                assertThrows(ExecutionException::class.java) { second.get(10,TimeUnit.SECONDS) }
                blocker.commit()
            }
            repo.saveNow();await { repo.healthy };assertTrue(repo.flush())
            sql(s).use { c -> c.createStatement().use { st -> st.executeQuery("SELECT coins FROM ob_profiles").use { it.next();assertEquals(222,it.getLong(1)) } } }
        } finally { repo.close() }
    }

    @Test fun `backup keeps one repeatable snapshot when external rows change between exported tables`() {
        val s=settings();val repo=repo(s);val id=UUID.randomUUID()
        repo.saveProfile(PlayerProfile(id,lastKnownName="Before"));repo.saveIsland(island(id));assertTrue(repo.flush())
        val field=PostgresStorageService::class.java.getDeclaredField("connection").apply { isAccessible=true }
        val connection=field.get(repo) as Connection
        field.set(repo,Proxy.newProxyInstance(Connection::class.java.classLoader,arrayOf(Connection::class.java)) { _,method,args ->
            val result=try { method.invoke(connection,*(args?:emptyArray())) } catch(e:InvocationTargetException) { throw e.cause!! }
            if(method.name=="createStatement") {
                val statement=result as Statement
                Proxy.newProxyInstance(Statement::class.java.classLoader,arrayOf(Statement::class.java)) { _,call,values ->
                    if(call.name=="executeQuery" && values!![0].toString().contains("row_to_json(record)::text FROM ${s.schema}.ob_players")) {
                        sql(s).use { c -> c.autoCommit=false;c.createStatement().use {
                            it.executeUpdate("UPDATE ob_players SET nickname='After'");it.executeUpdate("UPDATE ob_profiles SET coins=999")
                        };c.commit() }
                    }
                    try { call.invoke(statement,*(values?:emptyArray())) } catch(e:InvocationTargetException) { throw e.cause!! }
                }
            } else result
        } as Connection)
        try {
            val root=JsonParser.parseString(repo.backup().readText()).asJsonObject
            assertEquals("Before",root.getAsJsonArray("${s.schema}.ob_players").single().asJsonObject.get("nickname").asString)
            assertEquals(100,root.getAsJsonArray("${s.schema}.ob_profiles").single().asJsonObject.get("coins").asLong)
            sql(s).use { c -> c.createStatement().use { st -> st.executeQuery("SELECT coins FROM ob_profiles").use { it.next();assertEquals(999,it.getLong(1)) } } }
        } finally { repo.close() }
    }

    @Test fun `unavailable database during shutdown reports unconfirmed durability and closes admission`() {
        val isolated=EmbeddedPostgres.start()
        val s=DatabaseSettings("127.0.0.1",isolated.port,"postgres","postgres","test-only","disable","disable",1,socketTimeoutSeconds=5)
        val messages=CopyOnWriteArrayList<String>()
        val logger=Logger.getAnonymousLogger().apply {
            useParentHandlers=false
            addHandler(object:java.util.logging.Handler() {
                override fun publish(record:java.util.logging.LogRecord) { messages+=record.message }
                override fun flush() {}
                override fun close() {}
            })
        }
        val repo=PostgresStorageService(folder.toFile(),logger,s)
        try {
            repo.load();assertTrue(repo.flush());repo.saveProfile(PlayerProfile(UUID.randomUUID()))
            isolated.close()
            assertFalse(repo.flush());repo.close()
            assertFalse(repo.healthy);assertTrue(repo.flushAsync().isCompletedExceptionally)
            assertTrue(messages.any { it.contains("shutdown did not confirm all state durable") })
        } finally { repo.close();isolated.close() }
    }
}
