package com.alytanta.oneblock.island

import com.alytanta.oneblock.config.PluginConfig
import com.alytanta.oneblock.menu.MenuFixture.Companion.stub
import com.alytanta.oneblock.oneblock.*
import com.alytanta.oneblock.storage.StorageService
import io.papermc.paper.threadedregions.scheduler.*
import net.thenextlvl.worlds.*
import org.bukkit.*
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.plugin.Plugin
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.logging.Logger

class IslandWorldLoadingTest {
    @TempDir lateinit var folder: Path
    private inner class Fixture(pending:Boolean=false, initialized:Boolean=true) {
        val id=UUID.randomUUID()
        @Volatile var stored=Island(id,IslandWorldIdentity.worldName(id),0,100,0,CoreState(CoreMode.WOOD),
            platformInitialized=initialized,worldCreationPending=pending)
        var generation=0L;@Volatile var saves=0
        val flushed=CompletableFuture<Unit>()
        val loading=CompletableFuture<World>()
        val loads=AtomicInteger();val creates=AtomicInteger()
        val directory=folder.resolve(id.toString())
        private val globalThread=ThreadLocal.withInitial { false }
        private val global=stub(GlobalRegionScheduler::class.java) { name,args ->
            check(name=="execute");val before=globalThread.get();globalThread.set(true)
            try { (args!![1] as Runnable).run() } finally { globalThread.set(before) };null
        }
        private val region=stub(RegionScheduler::class.java) { name,args -> check(name=="execute");(args!![2] as Runnable).run();null }
        private val border=stub(WorldBorder::class.java) { _,_ -> null }
        val world=stub(World::class.java) { name,_ -> when(name) {
            "key","getKey" -> IslandWorldIdentity.key(id);"getWorldBorder" -> border;else -> null
        } }
        private val server=stub(Server::class.java) { name,_ -> when(name) {
            "getGlobalRegionScheduler" -> global;"getRegionScheduler" -> region;"getWorld" -> null;else -> null
        } }
        private val logger=Logger.getAnonymousLogger().apply { useParentHandlers=false }
        private val plugin=stub(Plugin::class.java) { name,_ -> when(name) { "getServer" -> server;"getLogger" -> logger;else -> null } }
        private val storage=stub(StorageService::class.java) { name,args -> when(name) {
            "loadIslands" -> listOf(stored);"getHealthy" -> true;"getGeneration" -> generation
            "saveIsland" -> { stored=args!![0] as Island;saves++;null };"flushAsync" -> flushed;else -> null
        } }
        private val registry=stub(WorldRegistry::class.java) { name,_ -> when(name) {
            "isRegistered" -> { check(globalThread.get());true };else -> error("Unexpected registry $name")
        } }
        private val api=stub(WorldsAccess::class.java) { name,_ -> when(name) {
            "resolveLevelDirectory" -> directory;"getWorldRegistry" -> registry
            "load" -> { check(globalThread.get());loads.incrementAndGet();loading }
            "create" -> { creates.incrementAndGet();error("Must not create a replacement") };else -> null
        } }
        val service=IslandService(plugin,PluginConfig.load(YamlConfiguration(),logger),storage,worlds={api})
        fun request():CompletableFuture<World?> = CompletableFuture<World?>().also { result -> service.ensureWorld(stored) { result.complete(it) } }
    }

    @Test fun `registered but missing persisted world is refused without creating or changing progress`() {
        val f=Fixture();val before=f.stored
        assertNull(f.request().get(5,TimeUnit.SECONDS))
        assertEquals(0,f.loads.get());assertEquals(0,f.creates.get());assertEquals(0,f.saves);assertEquals(before,f.stored)
        assertFalse(Files.exists(f.directory))
    }

    @Test fun `legacy uninitialized profile has no implicit permission to create an empty world`() {
        val f=Fixture(initialized=false)
        assertNull(f.request().get(5,TimeUnit.SECONDS));assertEquals(0,f.creates.get());assertEquals(0,f.saves)
    }

    @Test fun `new world permission is consumed before commit and a failed commit never calls Worlds`() {
        val f=Fixture(pending=true,initialized=false);val result=f.request()
        val deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5)
        while(f.saves==0 && System.nanoTime()<deadline)Thread.sleep(10)
        assertEquals(1,f.saves);assertFalse(f.stored.worldCreationPending);assertFalse(result.isDone)
        f.flushed.completeExceptionally(IllegalStateException("database down"))
        assertNull(result.get(5,TimeUnit.SECONDS));assertEquals(0,f.loads.get());assertEquals(0,f.creates.get())
        assertNull(f.request().get(5,TimeUnit.SECONDS));assertEquals(1,f.saves)
    }

    @Test fun `concurrent existing world requests share a load and preserve saved files`() {
        val f=Fixture();Files.createDirectories(f.directory);val file=f.directory.resolve("saved-chunks")
        Files.writeString(file,"existing-data")
        val first=f.request();val second=f.request()
        f.loading.complete(f.world)
        assertSame(f.world,first.get(5,TimeUnit.SECONDS));assertSame(f.world,second.get(5,TimeUnit.SECONDS))
        assertEquals(1,f.loads.get());assertEquals(0,f.creates.get());assertEquals("existing-data",Files.readString(file))
    }

    @Test fun `a naming response captured before a local rename cannot replace that rename`() {
        val f=Fixture();val version=f.service.namingVersion
        f.service.update(f.id) { it.copy(profileName="Local") }
        f.service.applyProfileNaming(version,mapOf(f.id to com.alytanta.oneblock.player.ProfileNaming(1,"Old")))
        assertEquals("Local",f.service.profileName(f.stored))
    }

    @Test fun `legacy world identity is refused without resetting coordinates or progress`() {
        val id=UUID.randomUUID();var writes=0
        val old=Island(id,"shared_legacy",123,80,456,CoreState(CoreMode.WOOD),platformInitialized=true)
        val storage=stub(StorageService::class.java) { name,_ -> when(name) {
            "loadIslands" -> listOf(old);"saveIsland" -> { writes++;null };else -> null
        } }
        val logger=Logger.getAnonymousLogger()
        val plugin=stub(Plugin::class.java) { _,_ -> null }
        val error=assertThrows(IllegalStateException::class.java) { IslandService(plugin,PluginConfig.load(YamlConfiguration(),logger),storage) }
        assertTrue(error.message!!.contains("explicit backed-up migration"));assertEquals(0,writes)
        assertEquals(123,old.x);assertEquals(456,old.z);assertTrue(old.platformInitialized)
    }
}
