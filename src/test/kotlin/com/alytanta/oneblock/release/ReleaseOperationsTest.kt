package com.alytanta.oneblock.release

import com.alytanta.oneblock.config.PluginConfig
import com.alytanta.oneblock.island.*
import com.alytanta.oneblock.market.*
import com.alytanta.oneblock.menu.*
import com.alytanta.oneblock.oneblock.*
import com.alytanta.oneblock.player.*
import com.alytanta.oneblock.storage.*
import io.papermc.paper.threadedregions.scheduler.EntityScheduler
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres
import org.bukkit.*
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.entity.Player
import org.bukkit.inventory.*
import org.bukkit.persistence.PersistentDataContainer
import org.bukkit.plugin.Plugin
import org.junit.jupiter.api.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.TimeUnit
import java.util.logging.Logger

class ReleaseOperationsTest {
    @TempDir lateinit var folder: Path
    companion object {
        private lateinit var postgres: EmbeddedPostgres
        @JvmStatic @BeforeAll fun start() { postgres=EmbeddedPostgres.start() }
        @JvmStatic @AfterAll fun stop() { if(::postgres.isInitialized)postgres.close() }
    }
    private inner class Fixture : AutoCloseable {
        val actor=UUID.randomUUID();val account=UUID.randomUUID()
        val tasks=ConcurrentLinkedQueue<Runnable>();val slots=arrayOfNulls<ItemStack>(36)
        val messages=mutableListOf<String>();val marker=mutableMapOf<NamespacedKey,Any>()
        var dead=false;var online=true;var failSave=false;var saves=0;var inventoryWrites=0;var done=0
        var quote: ReleaseIntent?=null
        val r=folder.resolve("progression.yml").toFile().let { file -> file.writeText(javaClass.getResourceAsStream("/progression.yml")!!.reader().use { it.readText() });ReleaseRules.read(file,true).first!! }
        var activeRules=r
        private val inventory=MenuFixture.stub(PlayerInventory::class.java) { name,args -> when(name) {
            "getStorageContents" -> slots.copyOf()
            "setStorageContents" -> { inventoryWrites++;val contents=args!![0] as Array<*>;contents.forEachIndexed { index,item -> slots[index]=item as? ItemStack };null }
            else -> null
        } }
        private val pdc=MenuFixture.stub(PersistentDataContainer::class.java) { name,args -> when(name) {
            "set" -> { marker[args!![0] as NamespacedKey]=args[2]!!;null };"get" -> marker[args!![0]];else -> null
        } }
        private val scheduler=MenuFixture.stub(EntityScheduler::class.java) { name,args -> if(name=="execute") {
            if(online) { tasks.add(Runnable { if(online)(args!![1] as Runnable).run() else (args!![2] as? Runnable)?.run() });true } else false
        } else null }
        val player=MenuFixture.stub(Player::class.java) { name,_ -> when(name) {
            "getUniqueId" -> actor;"getName" -> "ReleaseTester";"isOnline" -> online;"isDead" -> dead;"getGameMode" -> GameMode.SURVIVAL
            "getInventory" -> inventory;"getItemOnCursor" -> Stack(Material.AIR,0);"getPersistentDataContainer" -> pdc;"getScheduler" -> scheduler
            "saveData" -> { saves++;if(failSave)error("playerdata fault");null };else -> null
        } }
        private val server=MenuFixture.stub(Server::class.java) { _,_ -> null }
        private val plugin=MenuFixture.stub(Plugin::class.java) { name,_ -> when(name) {
            "getName","namespace" -> "one-block";"getServer" -> server;"getLogger" -> Logger.getAnonymousLogger();else -> null
        } }
        val settings: DatabaseSettings
        val repo: PostgresStorageService
        val players: PlayerService
        val islands: IslandService
        val operations: ReleaseOperations
        init {
            val db="release_ops_"+UUID.randomUUID().toString().replace("-","")
            postgres.postgresDatabase.connection.use { c -> c.createStatement().use { it.execute("CREATE DATABASE $db") } }
            settings=DatabaseSettings("127.0.0.1",postgres.port,db,"postgres","test-only","disable","disable",5)
            repo=PostgresStorageService(folder.toFile(),Logger.getAnonymousLogger(),settings).also { it.load() }
            repo.saveProfile(PlayerProfile(actor,activeAccount=account))
            repo.saveIsland(Island(account,IslandWorldIdentity.worldName(account),0,60,0,CoreState(CoreMode.WOOD),ownerPlayerId=actor,
                coins=1000,releaseProgress=ReleaseProgress(eligibleCoreBreaks=100,grownLogs=16)))
            assertTrue(repo.flush())
            players=PlayerService(repo);islands=IslandService(plugin,PluginConfig.load(YamlConfiguration(),Logger.getAnonymousLogger()),repo,players)
            operations=ReleaseOperations(plugin,repo,islands,ReleaseTools(plugin,islands,{activeRules}),{activeRules}) { _,key -> messages+=key }
            slots[0]=Stack(Material.OAK_LOG,16);slots[1]=Stack(Material.COBBLESTONE,32);slots[8]=Stack(Material.NETHER_STAR,menu=true)
        }
        fun submit() {
            val island=islands.findByOwner(account)!!
            val intent=quote ?: ReleaseDomain(r).plan(island,repo.marketState(),actor,"chapter","living").also { quote=it }
            operations.submit(player,account,intent,r) { done++ }
        }
        fun drain() { var n=0;while(tasks.isNotEmpty()) { check(n++<50);tasks.poll()?.run() } }
        fun settle() { repeat(4) { repo.flushAsync().get(10,TimeUnit.SECONDS);drain() } }
        override fun close() { InventoryGuard.release(actor);repo.close() }
    }
    @Test fun `real entity handoff consumes exact ingredients once and full inventory leaves kit in shared claims`() {
        Fixture().use { f ->
            for(slot in 2..35)if(slot!=8)f.slots[slot]=Stack(Material.DIRT,64)
            f.submit();f.submit();f.settle()
            assertFalse(f.operations.busy(f.account))
            assertEquals(1,f.saves);assertEquals(1,f.inventoryWrites);assertEquals(1,f.done)
            assertEquals("living",f.islands.findByOwner(f.account)!!.releaseProgress!!.chapter)
            assertEquals(8,f.repo.marketState().claims.size);assertTrue(f.repo.marketState().claims.values.all { it.recipient==null })
            assertEquals(Material.NETHER_STAR,f.slots[8]!!.type)
            f.submit();f.settle();assertEquals(1,f.saves);assertEquals(8,f.repo.marketState().claims.size)
        }
    }
    @Test fun `ingredient in reserved slot or custom stack cannot fund chapter and no intent is created`() {
        Fixture().use { f ->
            f.slots[0]=Stack(Material.OAK_LOG,16,tag="named");f.slots[8]=Stack(Material.OAK_LOG,64)
            f.submit();assertTrue(f.repo.marketState().transfers.isEmpty());assertEquals(0,f.saves);assertTrue("items" in f.messages)
        }
    }
    @Test fun `inventory changed during prepare aborts without removing the new inventory or publishing kit`() {
        Fixture().use { f ->
            f.submit();f.slots[0]=Stack(Material.DIAMOND,7);f.settle()
            assertFalse(f.operations.busy(f.account))
            assertEquals(Material.DIAMOND,f.slots[0]!!.type);assertEquals(0,f.saves);assertTrue(f.repo.marketState().claims.isEmpty())
            assertEquals(TransferStatus.ABORTED,f.repo.marketState().transfers.values.single().status)
        }
    }
    @Test fun `death disconnect and profile switch before application preserve ingredients and abort old profile intent`() {
        for(mode in listOf("death","disconnect","switch"))Fixture().use { f ->
            f.submit()
            when(mode) { "death" -> f.dead=true;"disconnect" -> f.online=false
                else -> { val second=UUID.randomUUID();f.repo.saveIsland(f.islands.findByOwner(f.account)!!.copy(owner=second,worldName=IslandWorldIdentity.worldName(second)));f.players.update(f.actor) { it.copy(activeAccount=second) } }
            }
            f.settle();assertEquals(16,f.slots[0]!!.amount);assertEquals(0,f.saves);assertTrue(f.repo.marketState().claims.isEmpty())
            assertEquals(TransferStatus.ABORTED,f.repo.marketState().transfers.values.single().status)
        }
    }
    @Test fun `playerdata exception persists review and recovery never repeats inventory mutation or grants kit`() {
        Fixture().use { f ->
            f.failSave=true;f.submit();f.settle()
            assertEquals(TransferStatus.REVIEW,f.repo.marketState().transfers.values.single().status)
            assertTrue(f.repo.marketState().claims.isEmpty());assertEquals(1,f.inventoryWrites)
            f.operations.recover(f.player);f.settle();assertEquals(1,f.inventoryWrites);assertTrue(f.repo.marketState().claims.isEmpty())
        }
    }
    @Test fun `prepare commit failure never touches inventory and reconnect aborts using unchanged saved inventory`() {
        Fixture().use { f ->
            postgres.postgresDatabase.connection.use { c -> c.prepareStatement("SELECT pg_terminate_backend(pid) FROM pg_stat_activity WHERE application_name='one-block' AND datname=?").use { it.setString(1,f.settings.database);it.executeQuery().close() } }
            f.submit();assertFalse(f.repo.flush());f.drain();assertEquals(0,f.saves);assertEquals(0,f.inventoryWrites)
            f.repo.reconnectAsync().get(10,TimeUnit.SECONDS);f.operations.recover(f.player);f.settle()
            assertEquals(TransferStatus.ABORTED,f.repo.marketState().transfers.values.single().status);assertTrue(f.repo.marketState().claims.isEmpty())
        }
    }
    @Test fun `reload invalidates a prepared inventory callback and stale submission cannot use a new candidate`() {
        Fixture().use { f ->
            f.submit();assertTrue(f.operations.busy(f.account));f.activeRules=f.r.copy(enabled=false);f.settle()
            assertFalse(f.operations.busy(f.account))
            assertEquals(0,f.inventoryWrites);assertEquals(TransferStatus.ABORTED,f.repo.marketState().transfers.values.single().status)
            f.submit();assertTrue("stale" in f.messages);assertEquals(1,f.repo.marketState().transfers.size)
        }
    }
}
