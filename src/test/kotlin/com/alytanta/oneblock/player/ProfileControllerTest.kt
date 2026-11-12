package com.alytanta.oneblock.player

import com.alytanta.oneblock.config.PluginConfig
import com.alytanta.oneblock.hub.HubService
import com.alytanta.oneblock.hub.HubSettings
import com.alytanta.oneblock.island.Island
import com.alytanta.oneblock.island.IslandService
import com.alytanta.oneblock.island.IslandWorldIdentity
import com.alytanta.oneblock.locale.LocaleService
import com.alytanta.oneblock.market.InventoryGuard
import com.alytanta.oneblock.menu.MenuFixture.Companion.stub
import com.alytanta.oneblock.oneblock.CoreMode
import com.alytanta.oneblock.oneblock.CoreState
import com.alytanta.oneblock.storage.StorageService
import org.bukkit.NamespacedKey
import org.bukkit.Server
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.player.PlayerQuitEvent
import io.papermc.paper.threadedregions.scheduler.GlobalRegionScheduler
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.plugin.Plugin
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.logging.Logger

class ProfileControllerTest {
    private class Fixture(next: ProfileInventory = ProfileInventory(items = List(43) { null })) : AutoCloseable {
        val f = ProfilePlayer()
        val source = Island(f.id, IslandWorldIdentity.worldName(f.id), 0, 100, 0, CoreState(CoreMode.WOOD))
        private val targetId=UUID.randomUUID()
        val target = source.copy(owner = targetId, worldName = IslandWorldIdentity.worldName(targetId), ownerPlayerId = f.id)
        var stored = PlayerProfile(f.id, activeAccount = source.owner, savedInventories = mapOf(target.owner to next))
        var profileWrites = 0
        var flushes = 0
        var failFlush = false
        var homes = 0
        var onlineEntity: org.bukkit.entity.Player? = null
        val history = mutableListOf<PlayerProfile>()
        private val logger = Logger.getAnonymousLogger().apply { useParentHandlers = false }
        private val global = stub(GlobalRegionScheduler::class.java) { name, args -> if (name == "execute") (args!![1] as Runnable).run(); null }
        private val server = stub(Server::class.java) { name, args -> when (name) {
            "getGlobalRegionScheduler" -> global
            "getPlayer" -> onlineEntity
            "getWorlds", "getOnlinePlayers" -> emptyList<Any>()
            "createInventory" -> {
                val holder = args!![0]; val size = args[1] as Int
                val items = arrayOfNulls<org.bukkit.inventory.ItemStack>(size)
                stub(org.bukkit.inventory.Inventory::class.java) { method, values -> when (method) {
                    "getHolder" -> holder
                    "getSize" -> size
                    "setItem" -> { items[values!![0] as Int] = values[1] as? org.bukkit.inventory.ItemStack; null }
                    "getItem" -> items[values!![0] as Int]
                    else -> null
                } }
            }
            else -> null
        } }
        private val plugin = stub(Plugin::class.java) { name, _ -> when (name) {
            "getName" -> "OneBlock"
            "namespace" -> "oneblock"
            "getLogger" -> logger
            "getServer" -> server
            else -> null
        } }
        private val storage = stub(StorageService::class.java) { name, args -> when (name) {
            "loadProfiles" -> listOf(stored)
            "loadIslands" -> listOf(source, target)
            "saveProfile" -> { stored = args!![0] as PlayerProfile; history += stored; profileWrites++; null }
            "getHealthy" -> true
            "getGeneration" -> 0L
            "flushAsync" -> { flushes++; if (failFlush) CompletableFuture.failedFuture<Unit>(IllegalStateException("Injected SQL failure")) else CompletableFuture.completedFuture(Unit) }
            "flush" -> true
            else -> null
        } }
        val players = PlayerService(storage)
        val controller: ProfileController
        val islands: IslandService
        init {
            val config = PluginConfig.load(YamlConfiguration(), logger)
            islands = IslandService(plugin, config, storage, players)
            val locales = LocaleService(plugin, "en", players)
            val hub = HubService(plugin, HubSettings("hub", true, 0.0, 100.0, 0.0, 0f, 0f), islands, locales)
            controller = ProfileController(plugin, islands, players, storage, hub, locales) { _, _ -> homes++ }
        }
        fun prepare(from: UUID? = source.owner) {
            val token = requireNotNull(InventoryGuard.acquireToken(f.id))
            // Exercise the real journal boundary after the hub teleport has completed.
            ProfileController::class.java.declaredMethods.single { it.name == "prepare" }.apply { isAccessible = true }
                .invoke(controller, f.player, from, target, null, token)
        }
        fun menus() {
            controller.authorize = { _, _ -> true }
            controller.makeItem = { _, spec, _ -> com.alytanta.oneblock.menu.Stack(spec.material) }
            val spec = com.alytanta.oneblock.menu.MenuItemSpec(org.bukkit.Material.BOOK, "Profile", emptyList())
            islands.profileSettings = ProfileSettings(slots = listOf(11, 15).map { ProfileSlot(it, spec, spec, spec, spec) })
            players.update(f.id) { it.copy(profileOrder = listOf(source.owner, target.owner)) }
            controller.open(f.player)
        }
        fun click(slot: Int, right: Boolean = false) {
            controller.click(org.bukkit.event.inventory.InventoryClickEvent(f.view, org.bukkit.event.inventory.InventoryType.SlotType.CONTAINER,
                slot, if (right) org.bukkit.event.inventory.ClickType.RIGHT else org.bukkit.event.inventory.ClickType.LEFT,
                org.bukkit.event.inventory.InventoryAction.PICKUP_ALL))
        }
        override fun close() { InventoryGuard.release(f.id) }
    }
    @Test fun `right click opens explicit profile removal confirmation and cancel preserves state`() {
        Fixture().use { test ->
            test.menus()
            val before = test.stored
            test.click(11, right = true); test.click(11, right = true); test.f.drain()
            assertEquals(27, test.f.opened!!.size)
            assertEquals(org.bukkit.Material.RED_CONCRETE, test.f.opened!!.getItem(11)!!.type)
            assertEquals(2, test.f.opens)
            assertEquals(before, test.stored)
            test.click(15); test.f.drain()
            assertEquals(54, test.f.opened!!.size)
            assertEquals(before, test.stored)
        }
    }
    @Test fun `invalid delete index never falls back to deleting active profile`() {
        Fixture().use { test ->
            test.menus()
            val before = test.stored; val menu = test.f.opened
            test.controller.command(test.f.player, listOf("delete", "99")); test.f.drain()
            assertSame(menu, test.f.opened)
            assertEquals(before, test.stored)
        }
    }
    @Test fun `custom confirmation slots bind only moved controls and reload invalidates queued actions`() {
        Fixture().use { test ->
            test.controller.interfaces = com.alytanta.oneblock.menu.InterfaceMenus.defaults().let { base -> base.copy(windows = base.windows +
                ("profile-delete" to base.window("profile-delete", null).copy(rows = 1, buttons = mapOf(
                    11 to com.alytanta.oneblock.market.MarketMenuButton(enabled = false),
                    13 to com.alytanta.oneblock.market.MarketMenuButton(slot = 4),
                    15 to com.alytanta.oneblock.market.MarketMenuButton(slot = 6))))) }
            test.menus(); val before = test.stored
            test.click(11, true); test.f.drain()
            val confirmation = test.f.opened!!
            assertEquals(9, confirmation.size)
            assertNull(confirmation.getItem(2))
            assertEquals(org.bukkit.Material.ARROW, confirmation.getItem(6)!!.type)
            test.click(2); test.f.drain(); assertSame(confirmation, test.f.opened)
            test.click(6); test.f.drain(); assertEquals(54, test.f.opened!!.size)
            test.click(11, true); test.f.drain()
            val reopened = test.f.opened!!
            test.click(6)
            test.controller.interfaces = com.alytanta.oneblock.menu.InterfaceMenus.defaults()
            test.f.drain(); assertSame(reopened, test.f.opened)
            assertEquals(before, test.stored)
        }
    }
    @Test fun `replacement can reserve the retiring profile slot at the configured limit`() {
        Fixture().use { test ->
            test.islands.profileSettings = ProfileSettings(availableSlots = 2)
            assertNull(test.islands.createProfile(test.f.id, ""))
            assertNotNull(test.islands.createProfile(test.f.id, "", replacing = test.source.owner))
            assertThrows(IllegalArgumentException::class.java) { test.islands.createProfile(test.f.id, "", replacing = UUID.randomUUID()) }
        }
    }
    @Test fun `quit checkpoints playerdata and join invalidates checkpoint before unlocking inventory`() {
        Fixture().use { test ->
            test.controller.quit(PlayerQuitEvent(test.f.player, net.kyori.adventure.text.Component.empty()))
            assertEquals(1, test.f.saves)
            assertEquals(ProfileInventory.capture(test.f.player), test.stored.profileCheckpoints!![test.source.owner])
            test.controller.join(PlayerJoinEvent(test.f.player, net.kyori.adventure.text.Component.empty()))
            assertTrue(InventoryGuard.busy(test.f.id))
            assertFalse(test.stored.profileCheckpoints!!.containsKey(test.source.owner))
            test.f.drain()
            assertFalse(InventoryGuard.busy(test.f.id))
        }
    }
    @Test fun `failed checkpoint invalidation keeps join inventory locked`() {
        Fixture().use { test ->
            test.failFlush = true
            test.controller.join(PlayerJoinEvent(test.f.player, net.kyori.adventure.text.Component.empty()))
            test.f.drain()
            assertTrue(InventoryGuard.busy(test.f.id))
            assertEquals(0, test.f.writes)
        }
    }
    @Test fun `old callback does not unlock a new entity session after reconnect`() {
        Fixture().use { test ->
            val old = requireNotNull(InventoryGuard.acquireToken(test.f.id))
            InventoryGuard.release(test.f.id)
            val current = requireNotNull(InventoryGuard.acquireToken(test.f.id))
            InventoryGuard.release(test.f.id, old)
            assertTrue(InventoryGuard.busy(test.f.id))
            InventoryGuard.release(test.f.id, current)
            assertFalse(InventoryGuard.busy(test.f.id))
        }
    }
    @Test fun `profiles sharing owner or coop participants are related auction accounts`() {
        Fixture().use { test ->
            assertTrue(test.islands.relatedAuctionAccounts(test.source.owner, test.target.owner))
            assertFalse(test.islands.relatedAuctionAccounts(test.source.owner, UUID.randomUUID()))
        }
    }
    @Test fun `eviction removes matching old inventory once and leaves other saved profiles available`() {
        Fixture().use { test ->
            val eviction = ProfileEviction(UUID.randomUUID(), test.source.owner, ProfileInventory.capture(test.f.player).hash())
            test.players.update(test.f.id) { it.copy(activeAccount = null, eviction = eviction, inventoryDetached = true) }
            test.controller.recover(test.f.player); test.f.drain()
            assertEquals(1, test.f.saves)
            assertNull(test.stored.eviction)
            assertEquals(true, test.stored.inventoryDetached)
            assertNull(test.islands.findForPlayer(test.f.id))
            assertTrue(test.stored.savedInventories!!.containsKey(test.target.owner))
            val writes = test.f.writes
            test.controller.recover(test.f.player); test.f.drain()
            assertEquals(writes, test.f.writes)
            assertFalse(InventoryGuard.busy(test.f.id))
        }
    }
    @Test fun `eviction applied marker never clears inventory twice`() {
        Fixture().use { test ->
            val eviction = ProfileEviction(UUID.randomUUID(), test.source.owner, "different hash")
            test.players.update(test.f.id) { it.copy(eviction = eviction, activeAccount = null, inventoryDetached = true) }
            test.f.markers[NamespacedKey("oneblock", "profile_eviction")] = eviction.id.toString()
            test.controller.recover(test.f.player); test.f.drain()
            assertEquals(0, test.f.writes)
            assertEquals(0, test.f.saves)
            assertNull(test.stored.eviction)
        }
    }
    @Test fun `changed evicted playerdata remains blocked for review instead of losing unrelated items`() {
        Fixture().use { test ->
            val eviction = ProfileEviction(UUID.randomUUID(), test.source.owner, "different hash")
            test.players.update(test.f.id) { it.copy(eviction = eviction, activeAccount = null, inventoryDetached = true) }
            test.controller.recover(test.f.player); test.f.drain()
            assertEquals(0, test.f.writes)
            assertEquals(true, test.stored.eviction?.review)
            assertTrue(InventoryGuard.busy(test.f.id))
        }
    }
    @Test fun `modern inventory switches only after prepared commit and saves before finishing`() {
        Fixture().use { test ->
            test.f.onSave = {
                assertEquals(test.source.owner, test.stored.activeAccount)
                assertNotNull(test.stored.profileSwitch)
            }
            test.prepare()
            assertEquals(0, test.f.writes)
            assertEquals(0, test.f.saves)
            assertEquals(test.source.owner, test.stored.activeAccount)
            assertNotNull(test.stored.profileSwitch)
            assertEquals(43, test.stored.savedInventories!![test.source.owner]!!.items.size)
            assertTrue(InventoryGuard.busy(test.f.id))
            test.f.drain()
            assertEquals(1, test.f.saves)
            assertEquals(test.target.owner, test.stored.activeAccount)
            assertNull(test.stored.profileSwitch)
            assertFalse(test.stored.savedInventories!!.containsKey(test.target.owner))
            assertFalse(InventoryGuard.busy(test.f.id))
            assertEquals(1, test.homes)
        }
    }
    @Test fun `legacy saved target switches on modern server`() {
        Fixture(ProfileInventory()).use { test ->
            test.prepare(); test.f.drain()
            assertEquals(test.target.owner, test.stored.activeAccount)
            assertEquals(43, test.f.items.size)
            assertNull(test.stored.profileSwitch)
        }
    }
    @Test fun `first profile adoption accepts captured modern inventory`() {
        Fixture().use { test ->
            test.players.update(test.f.id) { it.copy(activeAccount = null, savedInventories = null) }
            val before = ProfileInventory.capture(test.f.player)
            test.prepare(from = null); test.f.drain()
            assertEquals(test.target.owner, test.stored.activeAccount)
            assertEquals(before, ProfileInventory.capture(test.f.player))
            assertTrue(test.stored.savedInventories.orEmpty().isEmpty())
        }
    }
    @Test fun `failed prepared flush never installs target and unchanged source can recover`() {
        Fixture().use { test ->
            test.failFlush = true
            test.prepare(); test.f.drain()
            assertEquals(0, test.f.writes)
            assertEquals(0, test.f.saves)
            assertEquals(test.source.owner, test.stored.activeAccount)
            assertNotNull(test.stored.profileSwitch)
            assertTrue(InventoryGuard.busy(test.f.id))
            test.failFlush = false
            InventoryGuard.release(test.f.id) // Reconnect/storage recovery retires the old session lock.
            test.controller.recover(test.f.player); test.f.drain()
            assertNull(test.stored.profileSwitch)
            assertEquals(0, test.f.writes)
            assertFalse(InventoryGuard.busy(test.f.id))
        }
    }
    @Test fun `invalid target is rejected before journal and source snapshot writes`() {
        listOf(ProfileInventory(items = List(42) { null }),
            ProfileInventory(items = List(43) { if (it == 42) "!invalid!" else null })).forEach { bad ->
            Fixture(bad).use { test ->
                val before = test.stored
                test.prepare(); test.f.drain()
                assertEquals(before, test.stored)
                assertEquals(0, test.profileWrites)
                assertEquals(0, test.flushes)
                assertEquals(0, test.f.writes)
                assertFalse(InventoryGuard.busy(test.f.id))
            }
        }
    }
    @Test fun `changed source aborts without applying target`() {
        Fixture().use { test ->
            test.prepare()
            test.f.values["Level"] = 8
            test.f.drain()
            assertEquals(0, test.f.writes)
            assertEquals(0, test.f.saves)
            assertNull(test.stored.profileSwitch)
            assertEquals(test.source.owner, test.stored.activeAccount)
            assertFalse(test.stored.savedInventories!!.containsKey(test.source.owner))
            assertTrue(test.stored.savedInventories!!.containsKey(test.target.owner))
            assertFalse(InventoryGuard.busy(test.f.id))
        }
    }
    @Test fun `saveData failure remains review and recovery never reapplies inventory`() {
        Fixture().use { test ->
            test.f.failSave = true
            test.prepare(); test.f.drain()
            assertEquals(true, test.stored.profileSwitch?.review)
            assertEquals(test.source.owner, test.stored.activeAccount)
            assertTrue(test.stored.savedInventories!!.containsKey(test.target.owner))
            val writes = test.f.writes
            test.controller.recover(test.f.player); test.f.drain()
            assertEquals(writes, test.f.writes)
            assertEquals(true, test.stored.profileSwitch?.review)
            assertTrue(InventoryGuard.busy(test.f.id))
        }
    }
    @Test fun `recovery of unchanged prepared source aborts without replay`() {
        Fixture().use { test ->
            val intent = ProfileSwitch(UUID.randomUUID(), test.source.owner, test.target.owner, ProfileInventory.capture(test.f.player).hash())
            test.players.update(test.f.id) { it.copy(profileSwitch = intent) }
            test.controller.recover(test.f.player); test.f.drain()
            assertNull(test.stored.profileSwitch)
            assertEquals(test.source.owner, test.stored.activeAccount)
            assertEquals(0, test.f.writes)
            assertFalse(InventoryGuard.busy(test.f.id))
        }
    }
    @Test fun `recovery of applied marker finishes pointer without replay`() {
        Fixture().use { test ->
            val intent = ProfileSwitch(UUID.randomUUID(), test.source.owner, test.target.owner, "different")
            test.players.update(test.f.id) { it.copy(profileSwitch = intent) }
            test.f.markers[NamespacedKey("oneblock", "profile_switch")] = intent.id.toString()
            test.controller.recover(test.f.player); test.f.drain()
            assertEquals(test.target.owner, test.stored.activeAccount)
            assertNull(test.stored.profileSwitch)
            assertEquals(0, test.f.writes)
            assertFalse(InventoryGuard.busy(test.f.id))
        }
    }
}
