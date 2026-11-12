package com.alytanta.oneblock.storage

import com.alytanta.oneblock.island.Island
import com.alytanta.oneblock.player.PlayerProfile
import com.alytanta.oneblock.market.*
import com.alytanta.oneblock.trade.*
import com.google.gson.*
import org.bukkit.plugin.java.JavaPlugin
import java.io.File
import java.nio.file.Files
import java.sql.*
import java.util.*
import java.util.concurrent.*
import java.util.logging.Logger

/** Five purpose-specific tables in an isolated schema. One serialized writer commits wallets and escrow together. */
class PostgresStorageService(private val dataFolder: File, private val logger: Logger, @Volatile private var settings: DatabaseSettings,
    @Suppress("UNUSED_PARAMETER") legacyLoader: (() -> Pair<List<Island>, List<PlayerProfile>>)? = null) : StorageService {
    constructor(plugin: JavaPlugin, settings: DatabaseSettings) : this(plugin.dataFolder, plugin.logger, settings)
    private val lock = Any()
    private val worker = ThreadPoolExecutor(1,1,0L,TimeUnit.MILLISECONDS,LinkedBlockingQueue()) { runnable ->
        Thread(runnable,"oneblock-postgres").apply { isDaemon=true }
    }
    internal val queuedOperations: Int get() = worker.queue.size
    /** Every JDBC operation uses the existing serialized PostgreSQL worker, never a region/entity thread. */
    private fun <T> dungeonIo(action:()->T):CompletableFuture<T> = synchronized(lock) {
        if(!healthy || closing)return CompletableFuture.failedFuture(IllegalStateException("Database unavailable"))
        val done=CompletableFuture<T>()
        worker.execute {
            try {check(healthy && !connection.isClosed);done.complete(action())}
            catch(e:Exception) {if(e is SQLException)failed(e);done.completeExceptionally(e)}
        }
        done.copy()
    }
    fun saveDungeonRecord(record:com.alytanta.oneblock.dungeon.WaveRecord):CompletableFuture<Unit> = dungeonIo {
        require(record.size in 1..4 && record.participants.size==record.size && record.wave>=0 && record.elapsed>=0)
        connection.autoCommit=false
        try {
            val prior=connection.prepareStatement("SELECT payload FROM ob_dungeon_runs WHERE run_id=? FOR UPDATE").use {st->
                st.setObject(1,record.run);st.executeQuery().use {rs->if(rs.next())gson.fromJson(rs.getString(1),com.alytanta.oneblock.dungeon.WaveRecord::class.java) else null}
            }
            if(prior!=null) {
                require(prior.participants==record.participants && prior.size==record.size && prior.mode==record.mode && prior.version==record.version && prior.rulesHash==record.rulesHash && prior.created==record.created) {"Dungeon record identity mismatch"}
                require(prior.started==null || record.started==prior.started) {"Dungeon start mismatch"}
                if(prior.wave==record.wave)require(prior.elapsed==record.elapsed && prior.reached==record.reached) {"Completed result time is immutable"}
                if(prior.ended!=null) {
                    require(record.wave<=prior.wave && (record.ended==null || record.ended==prior.ended && record.reason==prior.reason)) {"Dungeon record already ended"}
                    connection.commit();return@dungeonIo Unit
                }
                if(record.wave<prior.wave) {connection.commit();return@dungeonIo Unit}
            }
            connection.prepareStatement("""INSERT INTO ob_dungeon_runs(run_id,group_size,mode,rules_version,rules_hash,completed_wave,elapsed_ms,reached_at,created_at,started_at,ended_at,end_reason,participants,payload)
                VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?::jsonb,?::jsonb) ON CONFLICT(run_id) DO UPDATE SET completed_wave=EXCLUDED.completed_wave,elapsed_ms=EXCLUDED.elapsed_ms,
                reached_at=EXCLUDED.reached_at,started_at=EXCLUDED.started_at,ended_at=EXCLUDED.ended_at,end_reason=EXCLUDED.end_reason,payload=EXCLUDED.payload""").use {st->
                st.setObject(1,record.run);st.setInt(2,record.size);st.setString(3,record.mode);st.setString(4,record.version);st.setString(5,record.rulesHash)
                st.setLong(6,record.wave);st.setLong(7,record.elapsed)
                fun time(index:Int,value:Long?) {if(value==null)st.setNull(index,Types.TIMESTAMP_WITH_TIMEZONE) else st.setTimestamp(index,Timestamp(value))}
                time(8,record.reached);time(9,record.created);time(10,record.started);time(11,record.ended);st.setString(12,record.reason)
                st.setString(13,gson.toJson(record.participants));st.setString(14,gson.toJson(record));st.executeUpdate()
            }
            connection.commit()
        } catch(e:Exception) {runCatching {connection.rollback()};throw e}
        finally {connection.autoCommit=true}
    }
    fun dungeonRecords(size:Int,mode:String="endless",limit:Int=20,offset:Int=0):CompletableFuture<List<com.alytanta.oneblock.dungeon.WaveRecord>> {
        require(size in 1..4 && limit in 1..1000 && offset in 0..100000 && mode.matches(Regex("[a-z0-9_.-]{1,48}")))
        return dungeonIo {
            connection.prepareStatement("SELECT payload FROM ob_dungeon_runs WHERE group_size=? AND mode=? AND completed_wave>0 ORDER BY completed_wave DESC,elapsed_ms ASC,run_id ASC LIMIT ? OFFSET ?").use {st->
                st.setInt(1,size);st.setString(2,mode);st.setInt(3,limit);st.setInt(4,offset)
                st.executeQuery().use {rs->buildList {while(rs.next())add(gson.fromJson(rs.getString(1),com.alytanta.oneblock.dungeon.WaveRecord::class.java))}}
            }
        }
    }
    private val gson = Gson()
    private lateinit var connection: Connection
    private val islands = linkedMapOf<UUID,Island>()
    private val profiles = linkedMapOf<UUID,PlayerProfile>()
    private var market = MarketState()
    private val pendingAudits = linkedMapOf<UUID,OperationLog>()
    @Volatile override var healthy = false; private set
    @Volatile private var closing = false
    private var revision = 0L
    @Volatile override var generation = 0L; private set
    private val reconnecting = java.util.concurrent.atomic.AtomicBoolean()
    @Volatile private var retryable = false
    @Volatile private var lastAttempt = 0L
    private var lastPing = 0L
    private var lastFailureLog = 0L
    private var lastFailureCode: String? = null
    private var committedToken: UUID? = null
    private data class Attempt(val snapshot: Snapshot, val token: UUID)
    private var pendingWrite: Attempt? = null
    @Volatile var onRecovered: (() -> Unit)? = null
    @Volatile var beforeRecoveryPublish: (() -> Unit)? = null
    @Volatile var profileNameLookup: (Island)->String = { it.profileName ?: "Island ${it.publicId.orEmpty()}" }
    fun readNameNumbers(): CompletableFuture<Map<UUID,Int>> = readProfileNaming().thenApply { values ->
        values.mapNotNull { (id,value) -> value.number?.let { id to it } }.toMap()
    }
    fun readProfileNaming(): CompletableFuture<Map<UUID,com.alytanta.oneblock.player.ProfileNaming>> = synchronized(lock) {
        if (!healthy || closing)return CompletableFuture.failedFuture(IllegalStateException("Database unavailable"))
        namingRead?.let { return it.thenApply { values -> values } }
        val done=CompletableFuture<Map<UUID,com.alytanta.oneblock.player.ProfileNaming>>();namingRead=done
        worker.execute {
            if(!healthy) {
                synchronized(lock) { namingRead=null }
                done.completeExceptionally(IllegalStateException("Database unavailable"));return@execute
            }
            try {
                val values=connection.createStatement().use { st -> st.executeQuery("SELECT profile_id,name_number,custom_name FROM ob_profiles").use { rs ->
                    buildMap {
                        while(rs.next()) put(rs.getObject(1,UUID::class.java),com.alytanta.oneblock.player.ProfileNaming((rs.getObject(2) as? Number)?.toInt(),rs.getString(3)))
                        synchronized(lock) {
                            islands.forEach { (id,current) ->
                                val prior=written?.islands?.get(id)
                                if(prior==null || prior.nameNumber!=current.nameNumber || prior.profileName!=current.profileName)
                                    put(id,com.alytanta.oneblock.player.ProfileNaming(current.nameNumber,current.profileName))
                            }
                        }
                    }
                } }
                done.complete(values)
            } catch(e:Exception) { failed(e);done.completeExceptionally(e) }
            finally { synchronized(lock) { namingRead=null } }
        }
        done.thenApply { it }
    }
    private data class Snapshot(val islands: Map<UUID,Island>, val players: Map<UUID,PlayerProfile>, val market: MarketState,
        val revision: Long, val events: List<OperationLog>)
    private var written: Snapshot? = null
    private var queuedFlush: CompletableFuture<Unit>? = null
    private var queuedBackup: CompletableFuture<File>? = null
    private var namingRead: CompletableFuture<Map<UUID, com.alytanta.oneblock.player.ProfileNaming>>? = null
    @Volatile var nameLookup: (UUID)->String? = { id -> synchronized(lock) { profiles[id]?.lastKnownName } }

    fun load() {
        try {
            connection = openConnection(settings)
            schema()
            connection.createStatement().use { st ->
                st.executeQuery("SELECT * FROM ob_profiles").use { rs -> while(rs.next()) {
                    val id = rs.getObject("profile_id",UUID::class.java)
                    val root = JsonParser.parseString(rs.getString("state")).asJsonObject
                    // Deprecated runtime fields get safe constructor defaults; they are never persisted.
                    val defaults = gson.toJsonTree(Island(id,com.alytanta.oneblock.island.IslandWorldIdentity.worldName(id),0,60,0,
                        com.alytanta.oneblock.oneblock.CoreState(com.alytanta.oneblock.oneblock.CoreMode.WOOD))).asJsonObject
                    root.entrySet().forEach { (key,value) -> defaults.add(key,value) }
                    val coreDefaults = gson.toJsonTree(com.alytanta.oneblock.oneblock.CoreState(com.alytanta.oneblock.oneblock.CoreMode.WOOD)).asJsonObject
                    require(root.getAsJsonObject("core") != null) { "Missing core state" }
                    root.getAsJsonObject("core").entrySet().forEach { (key,value) -> coreDefaults.add(key,value) }
                    coreDefaults.addProperty("breaks",rs.getLong("blocks_mined")); defaults.add("core",coreDefaults)
                    defaults.addProperty("owner",id.toString());defaults.addProperty("ownerPlayerId",rs.getString("owner_id"))
                    defaults.addProperty("profileName",rs.getString("custom_name"));
                    rs.getString("public_id")?.let { defaults.addProperty("publicId",it) }
                    (rs.getObject("name_number") as? Number)?.let { defaults.addProperty("nameNumber",it.toInt()) };defaults.addProperty("coins",rs.getLong("coins"))
                    defaults.addProperty("x",rs.getInt("core_x"));defaults.addProperty("y",rs.getInt("core_y"));defaults.addProperty("z",rs.getInt("core_z"))
                    defaults.addProperty("createdAt",rs.getTimestamp("created_at").time)
                    com.alytanta.oneblock.release.ReleaseStateCodec.normalize(defaults.get("releaseProgress"))?.let { defaults.add("releaseProgress",it) }
                    islands[id]=gson.fromJson(defaults,Island::class.java)
                } }
                st.executeQuery("SELECT * FROM ob_players").use { rs -> while(rs.next()) {
                    val root=JsonParser.parseString(rs.getString("state")).asJsonObject
                    root.addProperty("inventoryFull",rs.getBoolean("inventory_full"));
                    root.addProperty("uniqueId",rs.getString("player_id")); root.addProperty("lastKnownName",rs.getString("nickname"))
                    rs.getString("active_profile")?.let { root.addProperty("activeAccount",it) }
                    val profile=gson.fromJson(root,PlayerProfile::class.java);profiles[profile.uniqueId]=profile
                } }
                val seq=st.executeQuery("SELECT revision,market_sequence,commit_token FROM ob_system WHERE singleton").use { it.next();revision=it.getLong(1);committedToken=it.getObject(3,UUID::class.java);it.getLong(2) }
                market=MarketState(readRows("order",MarketOrder::class.java),readRows("claim",MarketClaim::class.java),
                    readRows("handoff",ItemTransfer::class.java),readRows("bazaar_trade",MarketTrade::class.java),emptyMap(),seq,
                    readRows("auction",AuctionListing::class.java),readRows("merchant_sale",MerchantSale::class.java),readRows("player_trade",PlayerTrade::class.java),
                    readRows("combat_receipt",com.alytanta.oneblock.combat.CombatReceipt::class.java),
                    readRows("room_chest",com.alytanta.oneblock.rewards.ChestOffer::class.java),readRows("chest_progress",com.alytanta.oneblock.rewards.ChestProgress::class.java))
                require(market.combatReceipts.all { (id,r) -> id==r.id && r.fingerprint.matches(Regex("[a-f0-9]{64}")) }) { "Invalid combat reward receipt" }
            }
            healthy=true;written=snapshot()
            audit(OperationLog(operation="STORAGE_READY",actor=null,account=null,success=true,status="SUCCESS",
                details=mapOf("schema_version" to 12,"schema" to settings.schema,"profiles" to islands.size,"players" to profiles.size)))
            logger.info("PostgreSQL schema12 ready: ${islands.size} profiles, ${profiles.size} players, ${market.orders.size} orders. Schema: ${settings.schema}")
        } catch(e:Exception) {
            healthy=false;if(::connection.isInitialized)runCatching { connection.close() };worker.shutdownNow()
            throw IllegalStateException("PostgreSQL startup failed. Check schema privileges, availability and configuration. SQLState=${(e as? SQLException)?.sqlState ?: "n/a"}",e)
        }
    }
    private fun exists(schema:String,table:String):Boolean = connection.prepareStatement("SELECT to_regclass(?) IS NOT NULL").use {
        it.setString(1,"$schema.$table");it.executeQuery().use { r -> r.next();r.getBoolean(1) }
    }
    private fun schema() {
        val marker=File(dataFolder,".rebuild-test-storage-v10")
        val rebuild=marker.isFile
        connection.autoCommit=false
        try {
            connection.createStatement().use { st ->
                val namespaceExists=connection.prepareStatement("SELECT EXISTS(SELECT 1 FROM pg_namespace WHERE nspname=?)").use {
                    it.setString(1,settings.schema);it.executeQuery().use { r->r.next();r.getBoolean(1) }
                }
                if(!namespaceExists)st.execute("CREATE SCHEMA ${settings.schema}")
                st.execute("SET search_path TO ${settings.schema}")
                if(rebuild) {
                    val scopes=listOf(settings.schema,"public").distinct()
                    val old=scopes.flatMap { scope -> OLD_TABLES.filter { exists(scope,it) }.map { "$scope.$it" } }
                    if(old.isNotEmpty()) exportTables(File(dataFolder,"backups/before-clean-v10-${System.currentTimeMillis()}.json"),old)
                    for(scope in scopes) {
                        for(view in VIEWS+listOf("ob_activity")) st.execute("DROP VIEW IF EXISTS $scope.$view")
                        // Reverse dependency order also supports rebuilding an existing v10 installation.
                        for(table in OLD_TABLES) st.execute("DROP TABLE IF EXISTS $scope.$table")
                    }
                } else {
                    check(!exists(settings.schema,"ob_meta")) { "Old storage found: use the authorized .rebuild-test-storage-v10 marker to rebuild test data" }
                    if(exists(settings.schema,"ob_system")) st.executeQuery("SELECT schema_version FROM ob_system WHERE singleton").use {
                        check(it.next() && it.getInt(1) in 10..12) { "Unsupported OneBlock schema" }
                    }
                }
                if(exists(settings.schema,"ob_system")) st.executeQuery("SELECT schema_version FROM ob_system WHERE singleton").use { version ->
                    if(version.next() && version.getInt(1)<12) exportTables(File(dataFolder,"backups/before-v12-${System.currentTimeMillis()}.json"),TABLES.filter {exists(settings.schema,it)}.map {"${settings.schema}.$it"})
                }
                val ddl=requireNotNull(javaClass.getResourceAsStream("/storage.sql")).reader(Charsets.UTF_8).use { it.readText() }
                ddl.split(';').map(String::trim).filter(String::isNotEmpty).forEach(st::execute)
            }
            connection.commit()
            if(rebuild) {
                val archive=File(dataFolder,"backups/rebuild-applied-${System.currentTimeMillis()}.txt")
                Files.createDirectories(archive.parentFile.toPath());Files.move(marker.toPath(),archive.toPath())
                logger.warning("Authorized OneBlock TEST data rebuilt in ${settings.schema}; unrelated schemas/tables were not reset")
            }
        } catch(e:Exception) { runCatching { connection.rollback() };throw e }
        finally { connection.autoCommit=true }
    }
    private fun <T> readRows(kind:String,type:Class<T>):Map<UUID,T> = connection.prepareStatement("SELECT * FROM ob_market WHERE record_type=?").use { st ->
        st.setString(1,kind);st.executeQuery().use { rs -> buildMap { while(rs.next()) {
            val id=rs.getObject("record_id",UUID::class.java)
            val fields=MarketRecordCodec.columns.mapNotNull { column ->
                val value:JsonElement?=when(column) {
                    "quantity","unit_price" -> rs.getObject(column)?.let { JsonPrimitive((it as Number).toLong()) }
                    "created_at","expires_at" -> rs.getTimestamp(column)?.let { JsonPrimitive(it.time) }
                    else -> rs.getString(column)?.let(::JsonPrimitive)
                };value?.let { column to it }
            }.toMap()
            put(id,gson.fromJson(MarketRecordCodec.decode(kind,id,rs.getString("payload"),fields),type))
        } } }
    }
    override fun loadIslands()=synchronized(lock) { islands.values.toList() }
    override fun loadProfiles()=synchronized(lock) { profiles.values.toList() }
    fun marketState()=synchronized(lock) { market }
    fun releaseChange(island: Island, operation: (MarketState) -> com.alytanta.oneblock.release.ReleaseChange): com.alytanta.oneblock.release.ReleaseChange = synchronized(lock) {
        check(healthy && !closing)
        val next = operation(market)
        require(next.island.owner == island.owner && next.island.ownerPlayer == island.ownerPlayer && next.island.worldName == island.worldName && next.island.coins >= 0)
        islands[island.owner] = next.island; market = next.state; revision++
        next
    }
    fun removeProfileMember(island: Island, player: PlayerProfile, requester: UUID, now: Long,
        decode: (String) -> org.bukkit.inventory.ItemStack): com.alytanta.oneblock.player.ProfileRemoval = synchronized(lock) {
        check(healthy && !closing)
        check(islands[island.owner] == island && profiles[player.uniqueId] == player) { "Profile changed during removal" }
        val plan = com.alytanta.oneblock.player.ProfileRemoval.plan(island, player, market, requester, now, decode = decode)
        islands[island.owner] = plan.island; profiles[player.uniqueId] = plan.player; market = plan.market; revision++
        queueAudit(OperationLog(operation = if (requester == player.uniqueId) "PROFILE_DELETE" else "COOP_KICK",
            actor = requester, account = island.owner, success = true, status = "SUCCESS",
            details = mapOf("member" to player.uniqueId, "salvaged_stacks" to plan.salvaged, "retired" to (plan.island.retiredAt != null))))
        plan
    }
    fun removeProfileMembers(island: Island, members: List<PlayerProfile>, requester: UUID, now: Long,
        administrative: Boolean, whole: Boolean, captures: Map<UUID, com.alytanta.oneblock.player.RemovalCapture>,
        decode: (String) -> org.bukkit.inventory.ItemStack = com.alytanta.oneblock.market.AuctionItems::decode): List<com.alytanta.oneblock.player.ProfileRemoval> = synchronized(lock) {
        check(healthy && !closing && islands[island.owner] == island)
        check(members.all { profiles[it.uniqueId] == it }) { "Profile changed during removal" }
        if (whole) require(administrative && members.map { it.uniqueId }.toSet() == com.alytanta.oneblock.player.ProfileRemoval.participants(island))
        var current = island; var book = market
        val plans = members.map { player ->
            val capture = requireNotNull(captures[player.uniqueId])
            com.alytanta.oneblock.player.ProfileRemoval.plan(current, player, book, requester, now,
                administrative, capture.inventory, capture.active, decode).also { current = it.island; book = it.market }
        }
        islands[island.owner] = current; market = book
        plans.forEach { profiles[it.player.uniqueId] = it.player }
        revision++
        queueAudit(OperationLog(operation = if (administrative) "ADMIN_PROFILE_REMOVE" else "PROFILE_DELETE", actor = requester,
            account = island.owner, success = true, status = "SUCCESS", details = mapOf("members" to members.map { it.uniqueId }, "whole" to whole)))
        plans
    }

    fun captureEviction(player: PlayerProfile, snapshot: com.alytanta.oneblock.player.ProfileInventory,
        decode: (String) -> org.bukkit.inventory.ItemStack): PlayerProfile = synchronized(lock) {
        check(healthy && !closing && profiles[player.uniqueId] == player)
        val eviction = requireNotNull(player.eviction); check(eviction.pendingCapture)
        val claims = market.claims.toMutableMap()
        if (islands[eviction.account]?.retiredAt == null && islands.containsKey(eviction.account))
            com.alytanta.oneblock.player.ProfileRemoval.salvage(snapshot, eviction.account, player.uniqueId, claims, decode)
        val next = player.copy(eviction = eviction.copy(beforeHash = snapshot.hash(), pendingCapture = false))
        profiles[player.uniqueId] = next; market = market.copy(claims = claims); revision++
        queueAudit(OperationLog(operation = "PROFILE_EVICTION_CAPTURE", actor = player.uniqueId, account = eviction.account,
            success = true, status = "SUCCESS", correlation = eviction.id))
        next
    }
    override fun saveIsland(island:Island)=synchronized(lock) {
        check(healthy&&!closing);val old=islands[island.owner];if(old==island)return@synchronized
        val context=OperationContext.current()
        if(context?.operation !in setOf("CORE_BREAK","COLLECTION_HARVEST")) queueAudit(OperationLog(operation=context?.operation?:"PROFILE_UPDATE",
            actor=context?.actor,account=island.owner,success=true,status="SUCCESS",correlation=context?.request,
            details=AuditChanges.between(old,island)+(context?.details?:emptyMap())))
        islands[island.owner]=island;revision++;Unit
    }
    override fun saveProfile(profile:PlayerProfile)=synchronized(lock) {
        check(healthy&&!closing);val old=profiles[profile.uniqueId];if(old==profile)return@synchronized
        if(old?.profileSwitch!=profile.profileSwitch)queueAudit(OperationLog(operation="PROFILE_SWITCH",actor=profile.uniqueId,account=profile.activeAccount,
            success=null,status=if(profile.profileSwitch?.review==true)"REVIEW" else if(profile.profileSwitch!=null)"PREPARED" else "SUCCESS",details=AuditChanges.between(old,profile)))
        if(old?.eviction!=profile.eviction)queueAudit(OperationLog(operation="PROFILE_EVICTION",actor=profile.uniqueId,account=profile.eviction?.account?:old?.eviction?.account,
            success=null,status=if(profile.eviction?.review==true)"REVIEW" else if(profile.eviction!=null)"PREPARED" else "SUCCESS",details=AuditChanges.between(old,profile)))
        profiles[profile.uniqueId]=profile;revision++;Unit
    }
    fun <T> changeMarket(island:Island,operation:(MarketState)->MarketChange<T>):T=synchronized(lock) {
        check(healthy&&!closing)
        val change=try { operation(market) } catch(e:Exception) {
            queueAudit((e as? MarketException)?.audit?:OperationLog(operation="MARKET_REJECTED",actor=null,account=island.owner,success=false,status="FAILED",reason=(e as? MarketException)?.reason?:e.javaClass.simpleName))
            revision++;flushAsync();throw e
        }
        require(change.coins>=0);islands[island.owner]=island.copy(coins=change.coins,releaseProgress=change.releaseProgress?:island.releaseProgress);market=change.state;revision++
        change.audit?.let(::queueAudit);change.result
    }
    fun <T> changeMany(current:Map<UUID,Island>,operation:(MarketState)->TradeChange<T>):T=synchronized(lock) {
        check(healthy&&!closing)
        val change=operation(market);require(change.coins.keys==current.keys&&change.coins.values.all { it>=0 })
        change.coins.forEach { (id,coins)->islands[id]=current.getValue(id).copy(coins=coins) };market=change.state;revision++
        queueAudit(change.audit);change.result
    }
    private fun queueAudit(event:OperationLog):Boolean {
        if(event.operation=="NPC_OPEN" || event.operation=="RESPONSE"&&event.success!=false)return false
        pendingAudits[event.id]=event.copy(nickname=event.nickname?:event.actor?.let(nameLookup)?:if(event.actor==null)"SERVER" else null)
        return true
    }
    override fun audit(event:OperationLog) { synchronized(lock) {
        if(!healthy||closing)return
        if(!queueAudit(event))return
        revision++
    };flushAsync() }
    private fun snapshot()=synchronized(lock) { Snapshot(islands.toMap(),profiles.toMap(),market,revision,pendingAudits.values.toList()) }
    private fun openConnection(candidate: DatabaseSettings): Connection {
        val props=Properties().apply {
            setProperty("user",candidate.user);setProperty("password",candidate.password);setProperty("sslmode",candidate.sslMode)
            setProperty("channelBinding",candidate.channelBinding);setProperty("connectTimeout",candidate.timeoutSeconds.toString())
            setProperty("socketTimeout",candidate.socketTimeoutSeconds.toString());setProperty("tcpKeepAlive","true")
            setProperty("ApplicationName","one-block")
        }
        val host=if(':' in candidate.host&&!candidate.host.startsWith('['))"[${candidate.host}]" else candidate.host
        val opened=requireNotNull(org.postgresql.Driver().connect("jdbc:postgresql://$host:${candidate.port}/${candidate.database}",props))
        try {
            opened.createStatement().use { st ->
                st.executeQuery("SELECT pg_try_advisory_lock(1682047186)").use { if(!it.next()||!it.getBoolean(1))throw SQLException("Another OneBlock backend owns this database","55P03") }
                st.execute("SET search_path TO ${candidate.schema}")
            }
            lastPing=System.nanoTime();return opened
        } catch(e:Exception) { runCatching { opened.close() };throw e }
    }
    private fun connectionFault(error: Throwable):Boolean {
        var cause:Throwable?=error
        while(cause!=null) {
            if(cause is SQLException&&(cause.sqlState?.startsWith("08")==true||cause.sqlState in setOf("57P01","57P02","57P03","55P03")))return true
            cause=cause.cause
        };return false
    }
    private fun failed(error:Exception) {
        synchronized(lock) { if(healthy)generation++;healthy=false;retryable=connectionFault(error) }
        val sql=error as? SQLException
        val server=generateSequence(sql) { it.nextException }.filterIsInstance<org.postgresql.util.PSQLException>().firstOrNull()?.serverErrorMessage
        val code=sql?.sqlState?:error.javaClass.simpleName
        if(code==lastFailureCode&&System.nanoTime()-lastFailureLog<TimeUnit.SECONDS.toNanos(30))return
        lastFailureCode=code;lastFailureLog=System.nanoTime()
        val message=server?.message?:if(sql==null)error.message?.take(300)?:error.javaClass.simpleName else error.javaClass.simpleName
        logger.severe("PostgreSQL write/heartbeat failed; game writes paused. SQLState=${sql?.sqlState?:"n/a"}; table=${server?.table?:"n/a"}; constraint=${server?.constraint?:"n/a"}; message=$message. Reconnect=${retryable}")
    }
    private fun heartbeat() {
        if(System.nanoTime()-lastPing < TimeUnit.SECONDS.toNanos(settings.heartbeatSeconds.toLong()))return
        if(connection.isClosed||!connection.isValid(minOf(5,settings.timeoutSeconds)))throw SQLException("Connection heartbeat failed","08003")
        lastPing=System.nanoTime()
    }
    override fun flushAsync():CompletableFuture<Unit> = synchronized(lock) {
        if(closing||!healthy)return CompletableFuture.failedFuture(IllegalStateException("Database unavailable"))
        queuedFlush?.let { return it.copy() }
        val done=CompletableFuture<Unit>();queuedFlush=done
        worker.execute {
            // Capture and detach together. Calls made after capture need a subsequent commit barrier.
            val next=synchronized(lock) {
                queuedFlush=null
                if(written?.revision!=revision)snapshot() else null
            }
            if(!healthy) { done.completeExceptionally(IllegalStateException("Database unavailable"));return@execute }
            try { if(next!=null)write(next) else heartbeat();done.complete(Unit) }
            catch(e:Exception) { failed(e);done.completeExceptionally(e) }
        }
        done.copy() // Cancelling one caller must not cancel the shared commit.
    }
    override fun saveNow() {
        if(healthy)flushAsync()
        else if(retryable&&!closing&&System.nanoTime()-lastAttempt>=TimeUnit.SECONDS.toNanos(settings.retrySeconds.toLong())) reconnectAsync()
    }
    /** Keeps live services and their immutable state; never reruns schema creation/reset or imports YAML. */
    fun reconnectAsync(candidate:DatabaseSettings=settings):CompletableFuture<Unit> = synchronized(lock) {
        if(closing||worker.isShutdown)return CompletableFuture.failedFuture(IllegalStateException("Storage is closed"))
        val old=settings
        if(listOf(candidate.host,candidate.port.toString(),candidate.database,candidate.schema)!=listOf(old.host,old.port.toString(),old.database,old.schema))
            return CompletableFuture.failedFuture(IllegalArgumentException("Changing the database target requires a server restart"))
        if(!reconnecting.compareAndSet(false,true))return CompletableFuture.failedFuture(IllegalStateException("Reconnect already running"))
        synchronized(lock) { healthy=false;generation++ }
        lastAttempt=System.nanoTime()
        return CompletableFuture.supplyAsync({
            try {
                check(!closing) { "Storage is closing" }
                reopenVerified(candidate)
                settings=candidate
                val next=snapshot()
                if(next.revision!=written?.revision)write(next)
                lastPing=System.nanoTime()
                check(!closing) { "Storage is closing" }
                beforeRecoveryPublish?.invoke()
                synchronized(lock) { check(!closing) { "Storage is closing" };healthy=true;retryable=false }
                audit(OperationLog(operation="STORAGE_RECONNECTED",actor=null,account=null,success=true,status="SUCCESS",details=mapOf("revision" to revision)))
                logger.info("PostgreSQL connection restored; queued state and operation journals saved")
                runCatching { onRecovered?.invoke() }.onFailure { logger.warning("Database restored; player recovery callback failed: ${it.javaClass.simpleName}") }
            } catch(e:Exception) {
                failed(e);runCatching { connection.close() }
                throw e
            } finally { reconnecting.set(false) }
        },worker)
    }
    /** Same commit-token verification for runtime recovery and the last shutdown attempt. Writer thread only. */
    private fun reopenVerified(candidate:DatabaseSettings) {
        if(::connection.isInitialized)runCatching { connection.close() }
        connection=openConnection(candidate)
        val disk=connection.createStatement().use { st -> st.executeQuery("SELECT schema_version,revision,commit_token FROM ob_system WHERE singleton").use {
            check(it.next()&&it.getInt(1)==12) { "Unexpected storage schema" };it.getLong(2) to it.getObject(3,UUID::class.java)
        } }
        val pending=pendingWrite
        if(pending!=null&&disk.first==pending.snapshot.revision&&disk.second==pending.token) acknowledge(pending)
        else check(disk.first==written?.revision&&disk.second==committedToken) { "Database changed outside this runtime; refusing to overwrite it" }
    }
    private fun acknowledge(attempt:Attempt) {
        written=attempt.snapshot;committedToken=attempt.token;pendingWrite=null
        synchronized(lock) { attempt.snapshot.events.forEach { pendingAudits.remove(it.id) } }
    }
    override fun flush()=runCatching { flushAsync().get(settings.timeoutSeconds.toLong()+5,TimeUnit.SECONDS);true }.getOrDefault(false)
    private fun write(next:Snapshot) {
        val attempt=Attempt(next,UUID.randomUUID());pendingWrite=attempt
        connection.autoCommit=false
        var failure:Exception?=null
        try {
            connection.createStatement().use { it.execute("SET LOCAL synchronous_commit=on") }
            connection.prepareStatement("UPDATE ob_system SET revision=?,market_sequence=?,commit_token=? WHERE singleton AND revision=? AND commit_token IS NOT DISTINCT FROM ?").use {
                it.setLong(1,next.revision);it.setLong(2,next.market.sequence);it.setObject(3,attempt.token)
                it.setLong(4,requireNotNull(written).revision);it.setObject(5,committedToken);check(it.executeUpdate()==1) { "Storage revision changed externally" }
            }
            connection.prepareStatement("INSERT INTO ob_players(player_id,nickname,active_profile,state,inventory_full) VALUES(?,?,?,?::jsonb,?) ON CONFLICT(player_id) DO UPDATE SET nickname=excluded.nickname,active_profile=excluded.active_profile,state=CASE WHEN excluded.state='{}'::jsonb THEN ob_players.state ELSE ob_players.state || excluded.state END,inventory_full=excluded.inventory_full").use { st ->
                next.players.values.filter { written?.players?.get(it.uniqueId)!=it }.forEach { p ->
                    val data=PlayerStateJson.patch(p,written?.players?.get(p.uniqueId))
                    st.setObject(1,p.uniqueId);st.setString(2,p.lastKnownName?:nameLookup(p.uniqueId));st.setObject(3,p.activeAccount);st.setString(4,data.toString());st.setBoolean(5,p.inventoryFull);st.addBatch()
                };st.executeBatch()
            }
            connection.prepareStatement("INSERT INTO ob_profiles(profile_id,name,owner_id,coins,blocks_mined,core_x,core_y,core_z,created_at,state,public_id,name_number,custom_name) VALUES(?,?,?,?,?,?,?,?,?,?::jsonb,?,?,?) ON CONFLICT(profile_id) DO UPDATE SET name=excluded.name,owner_id=excluded.owner_id,coins=excluded.coins,blocks_mined=excluded.blocks_mined,core_x=excluded.core_x,core_y=excluded.core_y,core_z=excluded.core_z,state=excluded.state,public_id=excluded.public_id,custom_name=CASE WHEN ? THEN excluded.custom_name ELSE ob_profiles.custom_name END,name_number=CASE WHEN ? THEN excluded.name_number ELSE ob_profiles.name_number END").use { st ->
                next.islands.values.filter { written?.islands?.get(it.owner)!=it }.forEach { p ->
                    val data=gson.toJsonTree(p).asJsonObject
                    (listOf("owner","ownerPlayerId","profileName","publicId","nameNumber","coins","worldName","x","y","z","createdAt")+RETIRED).forEach(data::remove)
                    listOf("breaks","tier","mutation","stability","infusion","mastery").forEach(data.getAsJsonObject("core")::remove)
                    st.setObject(1,p.owner);st.setString(2,profileNameLookup(p));st.setObject(3,p.ownerPlayer);st.setLong(4,p.coins);st.setLong(5,p.core.breaks)
                    st.setInt(6,p.x);st.setInt(7,p.y);st.setInt(8,p.z);st.setTimestamp(9,Timestamp(p.createdAt.takeIf { it>0 }?:System.currentTimeMillis()));st.setString(10,data.toString());st.setString(11,p.publicId)
                    if(p.nameNumber == null) st.setNull(12,Types.INTEGER) else st.setInt(12,p.nameNumber)
                    st.setString(13,p.profileName);st.setBoolean(14,written?.islands?.get(p.owner)?.profileName != p.profileName);st.setBoolean(15,written?.islands?.get(p.owner)?.nameNumber != p.nameNumber);st.addBatch()
                };st.executeBatch()
            }
            val prior=written?.market
            syncRows("order",next.market.orders,prior?.orders);syncRows("claim",next.market.claims,prior?.claims)
            syncRows("handoff",next.market.transfers,prior?.transfers);syncRows("bazaar_trade",next.market.trades,prior?.trades)
            syncRows("auction",next.market.auctions,prior?.auctions);syncRows("merchant_sale",next.market.merchantSales,prior?.merchantSales)
            syncRows("player_trade",next.market.playerTrades,prior?.playerTrades)
            syncRows("combat_receipt",next.market.combatReceipts,prior?.combatReceipts)
            syncRows("room_chest",next.market.chests,prior?.chests)
            syncRows("chest_progress",next.market.chestProgress,prior?.chestProgress)
            connection.prepareStatement("INSERT INTO ob_events(event_id,time,action,status,success,player_name,profile_name,reason,operation_id,player_id,profile_id,details) VALUES(?,?,?,?,?,?,?,?,?,?,?,?::jsonb) ON CONFLICT(event_id) DO NOTHING").use { st ->
                next.events.forEach { e ->
                    st.setObject(1,e.id);st.setTimestamp(2,Timestamp(e.time));st.setString(3,e.operation);st.setString(4,e.status)
                    if(e.success==null)st.setNull(5,Types.BOOLEAN) else st.setBoolean(5,e.success)
                    st.setString(6,e.nickname?:e.actor?.let(nameLookup));st.setString(7,e.account?.let { next.islands[it]?.profileName });st.setString(8,e.reason)
                    st.setObject(9,e.correlation);st.setObject(10,e.actor);st.setObject(11,e.account);st.setString(12,AuditPayload.json(e.details));st.addBatch()
                };st.executeBatch()
            }
            connection.commit();acknowledge(attempt);lastPing=System.nanoTime()
        } catch(e:Exception) { failure=e;runCatching { connection.rollback() }.onFailure(e::addSuppressed);throw e }
        finally {
            try { if(!connection.isClosed)connection.autoCommit=true }
            catch(reset:Exception) { if(failure!=null)failure.addSuppressed(reset) else throw reset }
        }
    }
    private fun <T:Any> syncRows(kind:String,records:Map<UUID,T>,old:Map<UUID,T>?) {
        if(records === old)return
        val columns=MarketRecordCodec.columns
        val placeholders=columns.map { if(it.endsWith("_id"))"?::uuid" else if(it.endsWith("_at"))"?::timestamptz" else if(it in setOf("quantity","unit_price"))"?::bigint" else "?" }
        val update=columns.joinToString(",") { "$it=excluded.$it" }+",payload=excluded.payload"
        connection.prepareStatement("INSERT INTO ob_market(record_type,record_id,${columns.joinToString(",")},payload) VALUES(?,?,${placeholders.joinToString(",")},?::jsonb) ON CONFLICT(record_type,record_id) DO UPDATE SET $update").use { st ->
            records.filter { (id,value)->old?.get(id)!=value }.forEach { (id,value) ->
                val encoded=MarketRecordCodec.encode(kind,value);st.setString(1,kind);st.setObject(2,id)
                columns.forEachIndexed { index,column ->
                    val data=encoded.fields[column]
                    st.setString(index+3,if(column.endsWith("_at"))data?.asLong?.let { java.time.Instant.ofEpochMilli(it).toString() } else data?.asString)
                }
                st.setString(columns.size+3,encoded.payload);st.addBatch()
            };st.executeBatch()
        }
        connection.prepareStatement("DELETE FROM ob_market WHERE record_type=? AND record_id=?").use { st ->
            old?.keys?.filter { it !in records }?.forEach { st.setString(1,kind);st.setObject(2,it);st.addBatch() };st.executeBatch()
        }
    }
    private fun exportTables(file:File,tables:List<String> = TABLES.map { "${settings.schema}.$it" }) {
        val partial=File(file.path+".partial")
        try { exportRows(partial,tables);publishExport(partial,file) }
        catch(e:Exception) { runCatching { Files.deleteIfExists(partial.toPath()) }.onFailure(e::addSuppressed);throw e }
    }
    private fun publishExport(partial:File,file:File) {
        java.nio.channels.FileChannel.open(partial.toPath(),java.nio.file.StandardOpenOption.WRITE).use { it.force(true) }
        Files.move(partial.toPath(),file.toPath(),java.nio.file.StandardCopyOption.ATOMIC_MOVE)
    }
    private fun exportRows(file:File,tables:List<String> = TABLES.map { "${settings.schema}.$it" }) {
        check(!connection.autoCommit) { "Cursor export requires a transaction" }
        Files.createDirectories(file.parentFile.toPath())
        file.writer(Charsets.UTF_8).use { out -> com.google.gson.stream.JsonWriter(out).use { json ->
            json.beginObject();tables.forEach { table -> json.name(table).beginArray()
                connection.createStatement(ResultSet.TYPE_FORWARD_ONLY,ResultSet.CONCUR_READ_ONLY).use { st ->
                    st.fetchSize=256
                    st.executeQuery("SELECT row_to_json(record)::text FROM $table record").use { rs ->while(rs.next())json.jsonValue(rs.getString(1)) }
                };json.endArray()
            };json.endObject()
        } }
    }
    fun backupAsync():CompletableFuture<File> = synchronized(lock) {
        if(!healthy||closing)return CompletableFuture.failedFuture(IllegalStateException("Database unavailable"))
        queuedBackup?.let { return it.thenApply { file -> file } }
        val done=CompletableFuture<File>();queuedBackup=done
        worker.execute {
            val next=synchronized(lock) { queuedBackup=null;snapshot() }
            val file=File(dataFolder,"backups/postgres-${System.currentTimeMillis()}-${UUID.randomUUID()}.json")
            val partial=File(file.path+".partial")
            var failure:Exception?=null
            try {
                check(healthy)
                if(written?.revision!=next.revision)try { write(next) } catch(e:Exception) { failed(e);throw e }
                connection.autoCommit=false
                connection.createStatement().use { it.execute("SET TRANSACTION ISOLATION LEVEL REPEATABLE READ READ ONLY") }
                exportRows(partial)
                connection.commit()
                publishExport(partial,file)
            } catch(e:Exception) {
                failure=e
                runCatching { connection.rollback() }.onFailure(e::addSuppressed)
                runCatching { Files.deleteIfExists(partial.toPath()) }.onFailure(e::addSuppressed)
                if(e is SQLException || connectionFault(e))failed(e)
            } finally {
                try { if(!connection.isClosed)connection.autoCommit=true }
                catch(e:Exception) { if(failure!=null)failure.addSuppressed(e) else { failure=e;failed(e) } }
            }
            if(failure==null)done.complete(file) else done.completeExceptionally(failure)
        }
        done.thenApply { it }
    }
    override fun backup():File = backupAsync().get()
    override fun close() {
        val end=synchronized(lock) {
            if(closing||worker.isShutdown)return
            closing=true;generation++
            worker.submit {
                try {
                    if(!healthy && retryable) { reopenVerified(settings);healthy=true }
                    val next=snapshot()
                    check(healthy || written?.revision==next.revision) { "Database unavailable with uncommitted state" }
                    if(healthy && written?.revision!=next.revision)write(next)
                } finally { healthy=false;if(::connection.isInitialized)connection.close() }
            }
        }
        try { end.get(settings.socketTimeoutSeconds.toLong()+settings.timeoutSeconds+10,TimeUnit.SECONDS) }
        catch(e:Exception) { logger.log(java.util.logging.Level.SEVERE,
            "PostgreSQL shutdown did not confirm all state durable; retain logs and reconcile journals before rollback",e) }
        finally { worker.shutdown() }
    }
    companion object {
        private val TABLES=listOf("ob_system","ob_players","ob_profiles","ob_market","ob_events","ob_dungeon_runs")
        private val OLD_TABLES=(listOf("ob_dungeon_runs","ob_market","ob_profiles","ob_players","ob_events","ob_system")+listOf("ob_meta","ob_islands","ob_market_entries","ob_operation_log","ob_orders","ob_claims","ob_transfers","ob_trades","ob_auctions","ob_merchant_sales","ob_stock","ob_market_state")).distinct()
        private val VIEWS=listOf("ob_profile_overview","ob_market_overview","ob_problems")
        private val RETIRED=listOf("collections","collectionContributions","skills","questProgress","claimedQuests","professionXp","workers","workerCollectedAt","seasonPoints","weeklyProgress","weeklyProgressWeek","weeklyClaimedWeek")
    }
}
