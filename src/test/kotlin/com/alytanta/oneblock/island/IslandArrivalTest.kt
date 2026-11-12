package com.alytanta.oneblock.island

import com.alytanta.oneblock.config.PluginConfig
import com.alytanta.oneblock.menu.MenuFixture.Companion.stub
import com.alytanta.oneblock.oneblock.CoreMode
import com.alytanta.oneblock.oneblock.CoreState
import com.alytanta.oneblock.storage.StorageService
import io.papermc.paper.threadedregions.scheduler.GlobalRegionScheduler
import io.papermc.paper.threadedregions.scheduler.RegionScheduler
import org.bukkit.*
import org.bukkit.block.Block
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.plugin.Plugin
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.util.UUID
import java.util.logging.Logger

class IslandArrivalTest {
    private class Fixture(initialized: Boolean = false, marker: UUID? = null, val worldId: UUID = UUID.randomUUID()) {
        private val owner = UUID.randomUUID()
        val seed = Island(owner, IslandWorldIdentity.worldName(owner), 0, 100, 0, CoreState(CoreMode.WOOD),
            platformInitialized = initialized, platformWorldId = marker)
        var saved = seed
        var saves = 0
        var failWrites = false
        val blocks = mutableMapOf<Triple<Int, Int, Int>, Material>()
        private val work = ArrayDeque<() -> Unit>()
        private var region: Pair<Int, Int>? = null
        private val border = stub(WorldBorder::class.java) { _, _ -> null }
        val world: World = stub(World::class.java) { name, args -> when (name) {
            "getName" -> seed.worldName
            "getUID" -> worldId
            "key", "getKey" -> IslandWorldIdentity.key(seed.owner)
            "getMinHeight" -> -64
            "getMaxHeight" -> 320
            "getWorldBorder" -> border
            "getBlockAt" -> {
                val point = Triple(args!![0] as Int, args[1] as Int, args[2] as Int)
                check(region == (Math.floorDiv(point.first, 16) to Math.floorDiv(point.third, 16))) { "Wrong region for $point: $region" }
                stub(Block::class.java) { method, values ->
                    val type = blocks[point] ?: Material.AIR
                    when (method) {
                        "getType" -> type
                        "isEmpty" -> type == Material.AIR
                        "isSolid" -> type !in setOf(Material.AIR, Material.WATER, Material.LAVA)
                        "setType" -> { check(!failWrites) { "Simulated write failure" }; blocks[point] = values!![0] as Material; null }
                        else -> error(method)
                    }
                }
            }
            else -> error(name)
        } }
        private val global = stub(GlobalRegionScheduler::class.java) { name, args ->
            check(name == "execute")
            work += { region = null; (args!![1] as Runnable).run() }
            null
        }
        private val regions = stub(RegionScheduler::class.java) { name, args ->
            check(name == "execute")
            val location = args!![1] as Location
            work += { region = Math.floorDiv(location.blockX, 16) to Math.floorDiv(location.blockZ, 16); (args[2] as Runnable).run() }
            null
        }
        private val logger = Logger.getAnonymousLogger().apply { useParentHandlers = false }
        private val server = stub(Server::class.java) { name, _ -> when (name) {
            "getGlobalRegionScheduler" -> global
            "getRegionScheduler" -> regions
            "getWorld" -> world
            else -> error(name)
        } }
        private val plugin = stub(Plugin::class.java) { name, _ -> when (name) {
            "getServer" -> server
            "getLogger" -> logger
            else -> error(name)
        } }
        private val storage = stub(StorageService::class.java) { name, args -> when (name) {
            "loadIslands" -> listOf(seed)
            "getHealthy" -> true
            "saveIsland" -> { saved = args!![0] as Island; saves++; null }
            else -> error(name)
        } }
        val service = IslandService(plugin, PluginConfig.load(YamlConfiguration(), logger).copy(islandSize = 5), storage)
        fun drain() { var count = 0; while (work.isNotEmpty()) { check(count++ < 200); work.removeFirst().invoke() }; region = null }
    }

    @Test fun `new island landing waits for every platform chunk and preserves existing blocks`() {
        val f = Fixture()
        f.blocks[Triple(1, 99, 0)] = Material.CHEST
        f.blocks[Triple(0, 100, 0)] = Material.DIAMOND_ORE
        val arrivals = mutableListOf<Location?>()
        f.service.prepareRespawn(f.seed, arrivals::add)
        f.service.prepareRespawn(f.seed, arrivals::add)
        assertTrue(arrivals.isEmpty())
        assertFalse(f.saved.platformInitialized)
        f.drain()
        assertEquals(2, arrivals.size)
        assertTrue(arrivals.all { it?.world == f.world && it.y == 100.0 && it.x == 1.5 })
        assertEquals(120, f.blocks.values.count { it == Material.DIRT })
        assertEquals(Material.CHEST, f.blocks[Triple(1, 99, 0)])
        assertEquals(Material.DIAMOND_ORE, f.blocks[Triple(0, 100, 0)])
        assertEquals(1, f.saves)
        assertTrue(f.saved.hasPlatformIn(f.worldId))
    }

    @Test fun `legacy flags and a recreated world UUID cannot skip the starter platform`() {
        for (marker in listOf(null, UUID.randomUUID())) {
            val f = Fixture(initialized = true, marker = marker)
            var arrived: Location? = null
            f.service.prepareRespawn(f.seed) { arrived = it }
            f.drain()
            assertEquals(121, f.blocks.values.count { it == Material.DIRT })
            assertEquals(100.0, arrived!!.y)
            assertTrue(f.saved.hasPlatformIn(f.worldId))
            assertEquals(1, f.saves)
            // Even an old caller's snapshot must use the freshly persisted UUID, without rebuilding.
            f.blocks.remove(Triple(1, 99, 0))
            f.service.prepareRespawn(f.seed) { arrived = it }
            f.drain()
            assertNull(f.blocks[Triple(1, 99, 0)])
            assertEquals(101.0, arrived!!.y)
            assertEquals(0.5, arrived!!.x)
            assertEquals(1, f.saves)
        }
    }

    @Test fun `missing mined floor uses the core and a tall building no longer forces hub fallback`() {
        val id = UUID.randomUUID()
        val f = Fixture(initialized = true, marker = id, worldId = id)
        f.blocks[Triple(0, 100, 0)] = Material.MAGMA_BLOCK
        for (y in 101..145) f.blocks[Triple(0, y, 0)] = Material.STONE
        val before = f.blocks.toMap()
        var destination: Location? = null
        f.service.prepareRespawn(f.seed) { destination = it }
        f.drain()
        assertEquals(f.world, destination!!.world)
        assertEquals(146.0, destination!!.y)
        assertEquals(Material.OAK_LOG, f.blocks[Triple(0, 100, 0)])
        before.filterKeys { it.second != 100 }.forEach { (point, material) -> assertEquals(material, f.blocks[point]) }
        assertTrue(f.blocks.values.none { it == Material.DIRT })
        assertEquals(0, f.saves)
    }

    @Test fun `failed platform work never marks initialization complete or returns an unsafe landing`() {
        val f = Fixture().apply { failWrites = true }
        val callbacks = mutableListOf<Location?>()
        f.service.prepareRespawn(f.seed, callbacks::add)
        f.drain()
        assertEquals(listOf<Location?>(null), callbacks)
        assertFalse(f.saved.platformInitialized)
        assertNull(f.saved.platformWorldId)
    }
}
