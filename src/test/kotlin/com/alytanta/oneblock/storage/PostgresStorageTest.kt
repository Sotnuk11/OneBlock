package com.alytanta.oneblock.storage

import com.alytanta.oneblock.island.Island
import com.alytanta.oneblock.market.*
import com.alytanta.oneblock.oneblock.CoreMode
import com.alytanta.oneblock.oneblock.CoreState
import com.alytanta.oneblock.player.PlayerProfile
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres
import org.junit.jupiter.api.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import java.util.logging.Logger

class PostgresStorageTest {
    @TempDir lateinit var folder: Path
    companion object {
        private lateinit var postgres: EmbeddedPostgres
        @JvmStatic @BeforeAll fun start() { postgres = EmbeddedPostgres.start() }
        @JvmStatic @AfterAll fun stop() { if (::postgres.isInitialized) postgres.close() }
    }
    private fun settings(): DatabaseSettings {
        val name = "ob_" + UUID.randomUUID().toString().replace("-", "")
        postgres.postgresDatabase.connection.use { connection -> connection.createStatement().use { it.execute("CREATE DATABASE $name") } }
        return DatabaseSettings("127.0.0.1", postgres.port, name, "postgres", "test-only", "disable", "disable", 5)
    }
    private fun repository(settings: DatabaseSettings) = PostgresStorageService(folder.toFile(), Logger.getLogger("pg-test"), settings)
    private fun sql(settings: DatabaseSettings)=java.sql.DriverManager.getConnection("jdbc:postgresql://127.0.0.1:${settings.port}/${settings.database}","postgres","test-only").apply { createStatement().use { it.execute("SET search_path TO ${settings.schema},public") } }

    @Test fun `dungeon history is idempotent ordered partitioned immutable and backed up across reopen`() {
        val config=settings();val repo=repository(config);repo.load()
        fun record(size:Int,wave:Long,elapsed:Long)=com.alytanta.oneblock.dungeon.WaveRecord(UUID.randomUUID(),wave,elapsed,1000+elapsed,100,1000,null,null,
            List(size) {com.alytanta.oneblock.dungeon.WaveParticipant(UUID.randomUUID(),UUID.randomUUID(),"Name$it")},size,"endless","endless.v1","a".repeat(64))
        val a=record(1,10,500);val b=record(1,10,400);val c=record(1,15,800);val party=record(4,500,999)
        try {
            listOf(a,b,c,party).map {repo.saveDungeonRecord(it)}.forEach {it.get()}
            val pool=java.util.concurrent.Executors.newFixedThreadPool(6)
            try {(1..12).map {pool.submit {repo.saveDungeonRecord(a).get()}}.forEach {it.get()}} finally {pool.shutdownNow()}
            assertEquals(listOf(c,b,a),repo.dungeonRecords(1).get());assertEquals(listOf(party),repo.dungeonRecords(4).get());assertTrue(repo.dungeonRecords(2).get().isEmpty())
            assertThrows(java.util.concurrent.ExecutionException::class.java) {repo.saveDungeonRecord(a.copy(elapsed=600)).get()}
            assertThrows(java.util.concurrent.ExecutionException::class.java) {repo.saveDungeonRecord(a.copy(participants=b.participants)).get()}
            assertTrue(repo.healthy)
            val advanced=a.copy(wave=20,elapsed=1000,reached=2000,ended=9000,reason="attempts")
            repo.saveDungeonRecord(advanced).get();repo.saveDungeonRecord(a).get()
            assertEquals(advanced,repo.dungeonRecords(1).get().first())
            assertThrows(java.util.concurrent.ExecutionException::class.java) {repo.saveDungeonRecord(advanced.copy(ended=10000)).get()}
            val backup=repo.backup().readText();assertTrue(backup.contains("ob_dungeon_runs"));assertTrue(backup.contains(advanced.run.toString()))
            sql(config).use {db->db.createStatement().use {st->st.executeQuery("SELECT completed_wave,elapsed_ms,end_reason,participants->0->>'nickname' FROM ob_dungeon_runs WHERE run_id='${a.run}'").use {r->assertTrue(r.next());assertEquals(20,r.getLong(1));assertEquals(1000,r.getLong(2));assertEquals("attempts",r.getString(3));assertEquals("Name0",r.getString(4))}}}
            repo.close();val reopened=repository(config);reopened.load()
            try {assertEquals(advanced,reopened.dungeonRecords(1).get().first());reopened.saveDungeonRecord(advanced).get()} finally {reopened.close()}
        } finally {repo.close()}
    }
    @Test fun `150 synthetic accounts concurrent progress flushes and backup preserve every update and profile inventory`() {
        val settings=settings();val repo=repository(settings);repo.load()
        val ids=List(150) {UUID.randomUUID()}
        val inventory=com.alytanta.oneblock.player.ProfileInventory(items=List(43) {"synthetic-item-$it-"+"x".repeat(1024)})
        ids.forEach {id->repo.saveProfile(PlayerProfile(id,activeAccount=id,savedInventories=mapOf(id to inventory)));repo.saveIsland(Island(id,"oneblock:test_$id",0,100,0,CoreState(CoreMode.WOOD),coins=1000))}
        assertTrue(repo.flush())
        val pool=java.util.concurrent.Executors.newFixedThreadPool(8)
        val ready=java.util.concurrent.CountDownLatch(8);val start=java.util.concurrent.CountDownLatch(1);val mutated=java.util.concurrent.CountDownLatch(8)
        val latencies=java.util.concurrent.CopyOnWriteArrayList<Double>();val barriers=java.util.concurrent.CopyOnWriteArrayList<java.util.concurrent.CompletableFuture<Unit>>()
        val peak=java.util.concurrent.atomic.AtomicInteger();val began=System.nanoTime()
        try {
            val calls=ids.map {id->pool.submit {
                ready.countDown();check(start.await(10,java.util.concurrent.TimeUnit.SECONDS))
                var island=Island(id,"oneblock:test_$id",0,100,0,CoreState(CoreMode.WOOD),coins=1000)
                repeat(10) {
                    island=island.copy(core=island.core.copy(breaks=island.core.breaks+1));repo.saveIsland(island)
                    if(it==0)mutated.countDown()
                    val before=System.nanoTime();val barrier=repo.flushAsync();barriers+=barrier
                    barrier.thenRun {latencies+=(System.nanoTime()-before)/1e6}
                    peak.accumulateAndGet(repo.queuedOperations,::maxOf)
                }
            }}
            assertTrue(ready.await(10,java.util.concurrent.TimeUnit.SECONDS));start.countDown();assertTrue(mutated.await(10,java.util.concurrent.TimeUnit.SECONDS))
            val backup=repo.backupAsync()
            calls.forEach {it.get(30,java.util.concurrent.TimeUnit.SECONDS)}
            java.util.concurrent.CompletableFuture.allOf(*barriers.toTypedArray()).get(30,java.util.concurrent.TimeUnit.SECONDS)
            assertTrue(backup.get(30,java.util.concurrent.TimeUnit.SECONDS).isFile);assertTrue(repo.flush())
            assertEquals(150,repo.loadIslands().size);assertTrue(repo.loadIslands().all {it.core.breaks==10L && it.coins==1000L})
            repo.close()
            val reopened=repository(settings);reopened.load()
            try {assertTrue(reopened.loadIslands().all {it.core.breaks==10L});assertTrue(reopened.loadProfiles().all {it.savedInventories?.get(it.uniqueId)==inventory})} finally {reopened.close()}
            val sorted=latencies.sorted();val report=Path.of("build/reports/mine-storage-synthetic-150.json");Files.createDirectories(report.parent)
            Files.writeString(report,com.google.gson.GsonBuilder().setPrettyPrinting().create().toJson(mapOf("kind" to "synthetic PostgreSQL only, not Minecraft clients","accounts" to 150,"workerThreads" to 8,"updates" to 1500,"flushRequests" to barriers.size,"elapsedMillis" to (System.nanoTime()-began)/1e6,"maxWriterQueueSample" to peak.get(),"barrierP50Millis" to sorted[sorted.size/2],"barrierP95Millis" to sorted[sorted.size*95/100],"barrierMaxMillis" to sorted.last(),"lostUpdates" to 0,"inventoryMismatches" to 0,"capacityClaim150Online" to false)))
        } finally {start.countDown();pool.shutdownNow();repo.close()}
    }

    @Test fun `islands profiles wallet and market escrow survive a real database restart of the repository`() {
        val settings = settings(); val owner = UUID.randomUUID(); val member = UUID.randomUUID()
        val island = Island(owner, "oneblock:test", 0, 100, 0, CoreState(CoreMode.WOOD, breaks = 101), coins = 1000,
            collections = mapOf("oak_log" to 101), members = mapOf(member to com.alytanta.oneblock.island.IslandRole.MEMBER),
            platformInitialized = true, platformWorldId = UUID.randomUUID())
        val profile = PlayerProfile(member, "uk", owner, 10000)
        val repo = repository(settings); repo.load()
        repo.saveProfile(PlayerProfile(owner));repo.saveIsland(island); repo.saveProfile(profile)
        val order = MarketOrder(UUID.randomUUID(), owner, member, "OAK_LOG", OrderSide.BUY, 5, 100, 100, 1, 4_102_444_800_000L)
        val claim = MarketClaim(UUID.randomUUID(), owner, "OAK_LOG", 64, "test")
        val transfer = ItemTransfer(UUID.randomUUID(), owner, member, "OAK_LOG", 10, TransferKind.WITHDRAW, 0, claim.id, "before", "after")
        val auctionDraft = AuctionDraft("exact-enchanted-item-bytes", "DIAMOND_SWORD", 1, "Sword", "WEAPONS", AuctionMode.BID, 100, 24, 1, false)
        val auction = AuctionListing(UUID.randomUUID(), owner, member, auctionDraft, 1, 20, 4_102_444_800_000L, 100,
            bids = listOf(AuctionBid(UUID.randomUUID(), member, 100, 21)))
        val uniqueClaim = MarketClaim(UUID.randomUUID(), owner, "DIAMOND_SWORD", 1, "auction-test", auctionDraft.itemData)
        val state = MarketState(orders = mapOf(order.id to order), claims = mapOf(claim.id to claim, uniqueClaim.id to uniqueClaim),
            auctions = mapOf(auction.id to auction),
            transfers = mapOf(transfer.id to transfer), stock = mapOf("OAK_LOG" to 123), sequence = 1)
        repo.changeMarket(island) { MarketChange(500, state, Unit) }
        assertTrue(repo.flush()); val backup = repo.backup(); assertTrue(backup.isFile)
        repo.close()
        val restarted = repository(settings)
        try {
            restarted.load()
            val loaded=restarted.loadIslands().single()
            assertEquals(500,loaded.coins);assertEquals(101,loaded.core.breaks);assertEquals(island.members,loaded.members)
            assertEquals(island.platformWorldId,loaded.platformWorldId);assertTrue(loaded.collections.isEmpty())
            assertEquals(profile, restarted.loadProfiles().single { it.uniqueId==member })
            assertEquals(order, restarted.marketState().orders[order.id])
            assertEquals(state.copy(stock=emptyMap()), restarted.marketState())
        } finally { restarted.close() }
    }
    @Test fun `one backend owns the cache and a second live writer is rejected`() {
        val settings = settings(); val first = repository(settings); first.load()
        try {
            val second = repository(settings)
            assertThrows(IllegalStateException::class.java) { second.load() }
            second.close()
        } finally { first.close() }
        val after = repository(settings); try { after.load(); assertTrue(after.healthy) } finally { after.close() }
    }
    @Test fun `schema eleven upgrades with backup and current schema gate rejects older jars`() {
        val settings = settings(); val owner = UUID.randomUUID()
        val island = Island(owner, "legacy-island", 0, 100, 0, CoreState(CoreMode.STONE), coins = 72)
        val first = repository(settings); first.load();first.saveProfile(PlayerProfile(owner)); first.saveIsland(island); assertTrue(first.flush()); first.close()
        sql(settings).use { c -> c.createStatement().use {
            it.execute("ALTER TABLE ob_system DROP CONSTRAINT ob_system_schema_version_check")
            it.execute("UPDATE ob_system SET schema_version=11")
            it.execute("ALTER TABLE ob_system ADD CONSTRAINT ob_system_schema_version_check CHECK(schema_version=11)")
        }
        }
        val upgraded = repository(settings)
        try {
            upgraded.load(); assertEquals(72, upgraded.loadIslands().single().coins)
            val backups = Files.list(folder.resolve("backups")).use { stream -> stream.filter { it.fileName.toString().startsWith("before-v12-") }.toList() }
            assertEquals(1, backups.size)
            val text = Files.readString(backups.single())
            assertTrue(text.contains("ob_profiles")); assertTrue(text.contains("72")); assertFalse(text.contains("test-only"))
        } finally { upgraded.close() }
        sql(settings).use { c ->
            c.createStatement().use { st -> st.executeQuery("SELECT schema_version FROM ob_system WHERE singleton").use { rs -> rs.next(); assertEquals(12, rs.getInt(1)) } }
            assertThrows(java.sql.SQLException::class.java) { c.createStatement().use { it.execute("UPDATE ob_system SET schema_version=11") } }
        }
    }
    @Test fun `audit commits with money and rejected insufficient funds stays false after repository restart`() {
        val settings = settings(); val owner = UUID.randomUUID()
        val island = Island(owner, "island", 0, 100, 0, CoreState(CoreMode.STONE), coins = 1000)
        val repo = repository(settings); repo.load();repo.saveProfile(PlayerProfile(owner)); repo.saveIsland(island)
        val engine = MarketEngine(org.bukkit.configuration.file.YamlConfiguration().let { yaml ->
            yaml.set("market.products.DIAMOND.sell-price", 100); MarketSettings.load(yaml)
        })
        repo.changeMarket(island) { state -> engine.transaction(owner, owner, 1000, state) { buyOrder("DIAMOND", 10, 5) } }
        val current = repo.loadIslands().single()
        assertThrows(MarketException::class.java) { repo.changeMarket(current) { state -> engine.transaction(owner, owner, current.coins, state) { buyOrder("DIAMOND", 1000, 5); Unit } } }
        repo.audit(OperationLog(operation = "TEST_PREPARED", actor = owner, account = owner, success = null, status = "PREPARED"))
        assertTrue(repo.flush()); assertTrue(repo.flush()); repo.close()
        val restarted = repository(settings)
        try { restarted.load(); assertEquals(950L, restarted.loadIslands().single().coins) } finally { restarted.close() }
        sql(settings).use { c ->
            c.createStatement().use { st ->
                st.executeQuery("SELECT success,reason,details->>'coins_before',details->>'coins_after',player_id,profile_id FROM ob_events WHERE action='BAZAAR_BUY_ORDER' ORDER BY success DESC").use { rs ->
                    assertTrue(rs.next()); assertTrue(rs.getBoolean(1)); assertEquals("1000", rs.getString(3)); assertEquals("950", rs.getString(4))
                    assertEquals(owner, rs.getObject(5, UUID::class.java)); assertEquals(owner, rs.getObject(6, UUID::class.java))
                    assertTrue(rs.next()); assertFalse(rs.getBoolean(1)); assertFalse(rs.wasNull()); assertEquals("coins", rs.getString(2))
                    assertEquals("950", rs.getString(3)); assertEquals("950", rs.getString(4)); assertFalse(rs.next())
                }
                st.executeQuery("SELECT success,status FROM ob_events WHERE action='TEST_PREPARED'").use { rs ->
                    assertTrue(rs.next()); assertFalse(rs.getBoolean(1)); assertTrue(rs.wasNull()); assertEquals("PREPARED", rs.getString(2)); assertFalse(rs.next())
                }
            }
        }
    }
    @Test fun `merchant receipt and prepared buyback survive restart and expiry deletes history not audit`() {
        val settings = settings(); val owner = UUID.randomUUID()
        val island = Island(owner, "island", 0, 100, 0, CoreState(CoreMode.STONE), coins = 0)
        var time = 1000L
        val engine = MarketEngine(MarketSettings.load(org.bukkit.configuration.file.YamlConfiguration().apply { set("market.products.DIAMOND.sell-price", 100) })) { time }
        val repo = repository(settings); repo.load();repo.saveProfile(PlayerProfile(owner)); repo.saveIsland(island)
        val saleTransfer = ItemTransfer(UUID.randomUUID(), owner, owner, "DIAMOND", 3, TransferKind.SHOP_SELL, 100, null, "b", "a")
        repo.changeMarket(island) { state -> engine.transaction(owner, owner, 0, state) { prepare(saleTransfer); finish(saleTransfer.id) } }
        assertTrue(repo.flush()); repo.close()
        val second = repository(settings); second.load()
        val sale = second.marketState().merchantSales.values.single()
        val buyback = ItemTransfer(UUID.randomUUID(), owner, owner, sale.product, sale.quantity, TransferKind.SHOP_BUYBACK, sale.unitPrice, null, "b", "a", buyback = sale)
        second.changeMarket(second.loadIslands().single()) { state -> engine.transaction(owner, owner, 300, state) { prepare(buyback) } }
        assertTrue(second.flush()); second.close()
        val third = repository(settings); third.load()
        assertEquals(0L, third.loadIslands().single().coins); assertTrue(third.marketState().merchantSales.isEmpty())
        time = sale.expires + 1
        third.changeMarket(third.loadIslands().single()) { state -> engine.transaction(owner, owner, 0, state) { finish(buyback.id); expire() } }
        assertTrue(third.flush()); third.close()
        sql(settings).use { c ->
            c.createStatement().use { st ->
                st.executeQuery("SELECT count(*) FROM ob_market WHERE record_type='merchant_sale'").use { it.next(); assertEquals(0, it.getInt(1)) }
                st.executeQuery("SELECT count(*) FROM ob_events WHERE action IN ('SHOP_SELL','SHOP_BUYBACK')").use { it.next(); assertTrue(it.getInt(1) >= 2) }
            }
        }
    }
    @Test fun `retired YAML loader is never called and never overwrites PostgreSQL balances`() {
        val settings = settings(); val owner = UUID.randomUUID()
        val island = Island(owner, "island", 0, 100, 0, CoreState(CoreMode.STONE), coins = 25)
        Files.writeString(folder.resolve("data.yml"), "legacy source retained")
        var imports = 0
        val first = PostgresStorageService(folder.toFile(), Logger.getLogger("pg-test"), settings) { imports++; listOf(island) to emptyList() }
        first.load(); assertEquals(0, imports)
        first.saveProfile(PlayerProfile(owner));first.saveIsland(island.copy(coins = 50)); assertTrue(first.flush()); first.close()
        assertEquals("legacy source retained", Files.readString(folder.resolve("data.yml")))
        assertFalse(folder.resolve("backups").toFile().exists())
        val second = PostgresStorageService(folder.toFile(), Logger.getLogger("pg-test"), settings) { error("Must not reimport") }
        try { second.load(); assertEquals(50, second.loadIslands().single().coins) } finally { second.close() }
    }
    @Test fun `database connection loss stops mutations instead of falling back to YAML`() {
        val settings = settings(); val repo = repository(settings); repo.load()
        try {
            postgres.postgresDatabase.connection.use { connection -> connection.prepareStatement(
                "SELECT pg_terminate_backend(pid) FROM pg_stat_activity WHERE application_name='one-block' AND datname=?"
            ).use { it.setString(1, settings.database); it.executeQuery().close() } }
            val island = Island(UUID.randomUUID(), "island", 0, 100, 0, CoreState(CoreMode.WOOD))
            repo.saveIsland(island)
            assertFalse(repo.flush()); assertFalse(repo.healthy)
            assertThrows(IllegalStateException::class.java) { repo.saveIsland(island) }
            assertFalse(Files.exists(folder.resolve("data.yml")))
        } finally { repo.close() }
    }
    @Test fun `SQL failure rolls back wallet and market escrow in the same transaction`() {
        val settings = settings(); val repo = repository(settings); repo.load()
        val island = Island(UUID.randomUUID(), "island", 0, 100, 0, CoreState(CoreMode.WOOD), coins = 100)
        repo.saveProfile(PlayerProfile(island.owner));repo.saveIsland(island); assertTrue(repo.flush())
        val props = java.util.Properties().apply { setProperty("user", "postgres"); setProperty("password", "test-only") }
        val connection = requireNotNull(org.postgresql.Driver().connect("jdbc:postgresql://127.0.0.1:${settings.port}/${settings.database}", props))
        try {
            connection.createStatement().use { st ->
                st.execute("SET search_path TO oneblock,public")
                st.execute("CREATE FUNCTION reject_market_write() RETURNS trigger LANGUAGE plpgsql AS 'BEGIN RAISE EXCEPTION ''test rollback''; END;'")
                st.execute("CREATE TRIGGER reject_market BEFORE UPDATE ON ob_profiles FOR EACH ROW EXECUTE FUNCTION reject_market_write()")
            }
            val claim=MarketClaim(UUID.randomUUID(),island.owner,"OAK_LOG",10,"rollback")
            repo.changeMarket(island) { MarketChange(50, it.copy(claims=mapOf(claim.id to claim)), Unit) }
            assertFalse(repo.flush())
            connection.createStatement().use { st ->
                st.executeQuery("SELECT coins, state ? 'coins' FROM ob_profiles").use { rs -> rs.next(); assertEquals(100, rs.getLong(1)); assertFalse(rs.getBoolean(2)) }
                st.executeQuery("SELECT count(*) FROM ob_market WHERE record_type='claim'").use { rs -> rs.next(); assertEquals(0, rs.getInt(1)) }
            }
        } finally { repo.close(); connection.close() }
    }
    @Test fun `unsupported legacy storage is rejected without modifying escrow exact items or authentication tables`() {
        val config = settings(); val owner = UUID.randomUUID()
        val island = Island(owner, "migration-world", 0, 100, 0, CoreState(CoreMode.WOOD, breaks = 123), coins = 12345)
        val item = "exact-item-payload-preserved"
        val claim = MarketClaim(UUID.randomUUID(), owner, "DIAMOND_SWORD", 1, "auction", item)
        val order = MarketOrder(UUID.randomUUID(), owner, owner, "OAK_LOG", OrderSide.BUY, 5, 10, 10, 42, 4_102_444_800_000L)
        val draft = AuctionDraft(item, "DIAMOND_SWORD", 1, "Test", "WEAPONS", AuctionMode.BIN, 50, 24, 1, false)
        val lot = AuctionListing(UUID.randomUUID(), owner, owner, draft, 1, 2, 4_102_444_800_000L, 100)
        val state = MarketState(orders = mapOf(order.id to order), claims = mapOf(claim.id to claim),
            auctions = mapOf(lot.id to lot), stock = mapOf("OAK_LOG" to 22), sequence = 42)
        repository(config).also { repo ->
            repo.load();repo.saveProfile(PlayerProfile(owner)); repo.saveIsland(island); repo.changeMarket(island) { MarketChange(island.coins, state, Unit) }
            assertTrue(repo.flush()); repo.close()
        }
        fun sql() = sql(config)
        sql().use { connection -> connection.createStatement().use { st ->
            st.execute("CREATE TABLE auth_users (name text PRIMARY KEY, sentinel text)")
            st.execute("INSERT INTO auth_users VALUES ('authentication','untouched')")
            st.execute("CREATE TABLE ob_market_state (id integer PRIMARY KEY, payload jsonb NOT NULL)")
            st.execute("INSERT INTO ob_market_state VALUES(1,'{\"sequence\":42}')")
            st.execute("CREATE TABLE ob_stock (product text PRIMARY KEY, amount bigint NOT NULL)")
            st.execute("INSERT INTO ob_stock VALUES('OAK_LOG',22)")
            st.execute("CREATE TABLE ob_meta(id integer PRIMARY KEY,version integer)")
            st.execute("INSERT INTO ob_meta VALUES(1,3)")
        } }
        repeat(2) {
            val migrated = repository(config)
            try {
                assertThrows(IllegalStateException::class.java) { migrated.load() }
            } finally { migrated.close() }
        }
        sql().use { connection -> connection.createStatement().use { st ->
            st.executeQuery("SELECT amount FROM ob_stock WHERE product='OAK_LOG'").use { rs -> assertTrue(rs.next());assertEquals(22,rs.getLong(1)) }
            st.executeQuery("SELECT version FROM ob_meta").use { rs -> rs.next(); assertEquals(3, rs.getInt(1)) }
            st.executeQuery("SELECT coins,state ? 'coins' FROM ob_profiles").use { rs -> rs.next();assertEquals(12345,rs.getLong(1)); assertFalse(rs.getBoolean(2)) }
            st.executeQuery("SELECT payload->>'itemData' FROM ob_market WHERE record_type='claim'").use { rs -> assertTrue(rs.next());assertEquals(item,rs.getString(1)) }
            st.executeQuery("SELECT market_sequence FROM ob_system").use { rs -> rs.next();assertEquals(42,rs.getLong(1)) }
            st.executeQuery("SELECT sentinel FROM auth_users").use { rs -> rs.next(); assertEquals("untouched", rs.getString(1)) }
        } }
        assertFalse(folder.resolve("backups").toFile().exists())
    }

    @Test fun `dedicated schema keeps plugin tables separate from an existing authentication table`() {
        val config = settings()
        val role = "ob_role_" + UUID.randomUUID().toString().replace("-", "")
        java.sql.DriverManager.getConnection("jdbc:postgresql://127.0.0.1:${postgres.port}/${config.database}", "postgres", "test-only").use { c ->
            c.createStatement().use { st ->
                st.execute("CREATE TABLE public.authentication (sentinel text)")
                st.execute("INSERT INTO public.authentication VALUES ('keep')")
                st.execute("CREATE ROLE $role LOGIN PASSWORD 'test-only'")
                st.execute("CREATE SCHEMA oneblock AUTHORIZATION $role")
                st.execute("GRANT CONNECT ON DATABASE ${config.database} TO $role")
                st.execute("ALTER ROLE $role IN DATABASE ${config.database} SET search_path=oneblock")
            }
        }
        val scoped = DatabaseSettings("127.0.0.1",postgres.port,config.database,role,"test-only","disable","disable",5)
        val repo = repository(scoped)
        try { repo.load(); assertTrue(repo.healthy) } finally { repo.close() }
        java.sql.DriverManager.getConnection("jdbc:postgresql://127.0.0.1:${postgres.port}/${config.database}", "postgres", "test-only").use { c -> c.createStatement().use { st ->
            st.executeQuery("SELECT count(*) FROM information_schema.tables WHERE table_schema='oneblock' AND table_type='BASE TABLE'").use { it.next(); assertEquals(6,it.getInt(1)) }
            st.executeQuery("SELECT count(*) FROM information_schema.views WHERE table_schema='oneblock'").use { it.next();assertEquals(3,it.getInt(1)) }
            st.executeQuery("SELECT to_regclass('public.ob_meta')").use { it.next(); assertTrue(it.getString(1) == null) }
            st.executeQuery("SELECT sentinel FROM public.authentication").use { it.next(); assertEquals("keep",it.getString(1)) }
        } }
    }

    @Test fun `legacy marker aborts schema migration preserving conflicting wallet evidence transactionally`() {
        val config = settings(); val owner = UUID.randomUUID()
        repository(config).also { it.load();it.saveProfile(PlayerProfile(owner)); it.saveIsland(Island(owner,"conflict",0,100,0,CoreState(CoreMode.STONE),coins=50)); it.close() }
        fun sql() = sql(config)
        sql().use { c -> c.createStatement().use { st ->
            st.execute("CREATE TABLE ob_market_state(id integer PRIMARY KEY,payload jsonb)")
            st.execute("INSERT INTO ob_market_state VALUES(1,'{\"sequence\":99}')")
            st.execute("UPDATE ob_profiles SET state=state || '{\"coins\":999}'::jsonb")
            st.execute("CREATE TABLE ob_meta(id integer PRIMARY KEY,version integer)")
            st.execute("INSERT INTO ob_meta VALUES(1,3)")
            st.execute("ALTER TABLE ob_system DROP CONSTRAINT ob_system_schema_version_check")
            st.execute("UPDATE ob_system SET schema_version=11")
        } }
        val failed = repository(config)
        try { assertThrows(IllegalStateException::class.java) { failed.load() } } finally { failed.close() }
        sql().use { c -> c.createStatement().use { st ->
            st.executeQuery("SELECT version FROM ob_meta").use { it.next(); assertEquals(3,it.getInt(1)) }
            st.executeQuery("SELECT payload->>'sequence' FROM ob_market_state").use { it.next(); assertEquals("99",it.getString(1)) }
            st.executeQuery("SELECT coins,state->>'coins' FROM ob_profiles").use { it.next(); assertEquals(50,it.getLong(1)); assertEquals("999",it.getString(2)) }
            st.executeQuery("SELECT schema_version FROM ob_system").use { it.next();assertEquals(11,it.getInt(1)) }
        } }
    }

}
