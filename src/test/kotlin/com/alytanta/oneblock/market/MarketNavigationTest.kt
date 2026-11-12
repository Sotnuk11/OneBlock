package com.alytanta.oneblock.market

import com.alytanta.oneblock.config.PluginConfig
import com.alytanta.oneblock.hub.HubService
import com.alytanta.oneblock.hub.HubSettings
import com.alytanta.oneblock.island.*
import com.alytanta.oneblock.locale.LocaleService
import com.alytanta.oneblock.menu.MenuFixture.Companion.stub
import com.alytanta.oneblock.oneblock.CoreMode
import com.alytanta.oneblock.oneblock.CoreState
import com.alytanta.oneblock.player.PlayerService
import com.alytanta.oneblock.storage.DatabaseSettings
import com.alytanta.oneblock.storage.PostgresStorageService
import io.papermc.paper.event.packet.UncheckedSignChangeEvent
import io.papermc.paper.math.Position
import io.papermc.paper.threadedregions.scheduler.EntityScheduler
import io.papermc.paper.threadedregions.scheduler.RegionScheduler
import io.papermc.paper.threadedregions.scheduler.ScheduledTask
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import org.bukkit.*
import org.bukkit.block.Sign
import org.bukkit.block.data.BlockData
import org.bukkit.block.sign.Side
import org.bukkit.block.sign.SignSide
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.entity.Player
import org.bukkit.event.inventory.*
import org.bukkit.inventory.*
import org.bukkit.plugin.Plugin
import org.bukkit.persistence.PersistentDataContainer
import com.alytanta.oneblock.menu.Stack
import org.junit.jupiter.api.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedDeque
import java.util.concurrent.TimeUnit
import java.util.logging.Logger

class MarketNavigationTest {
    @TempDir lateinit var folder: Path
    companion object {
        private lateinit var postgres: EmbeddedPostgres
        @JvmStatic @BeforeAll fun start() { postgres = EmbeddedPostgres.start() }
        @JvmStatic @AfterAll fun stop() { if (::postgres.isInitialized) postgres.close() }
    }
    private class Icon(private val material: Material, val lines: List<String>) : ItemStack() {
        override fun getType() = material
        override fun clone():ItemStack=Icon(material,lines.toList())
    }

    private inner class Fixture(rules: AuctionSettings = AuctionSettings(), sharedWallet: Boolean = false, multiplayer: Boolean = false) : AutoCloseable {
        private val owner = UUID.randomUUID()
        private val guestId = UUID.randomUUID()
        val island = Island(owner, IslandWorldIdentity.worldName(owner), 0, 100, 0, CoreState(CoreMode.WOOD), coins = 1000,
            members = if (sharedWallet) mapOf(guestId to IslandRole.MEMBER) else emptyMap())
        private val logger = Logger.getAnonymousLogger().apply { useParentHandlers = false }
        val repo: PostgresStorageService
        private val work = ConcurrentLinkedDeque<Runnable>()
        private val worldId = UUID.randomUUID()
        private val world = stub(World::class.java) { name, _ -> when (name) {
            "getName" -> "hub"; "getUID" -> worldId; "key", "getKey" -> NamespacedKey.minecraft("overworld"); "getMaxHeight" -> 320; else -> null
        } }
        val messages = mutableListOf<String>()
        var failSave = false
        private val inventories = mutableMapOf<Inventory, Screen>()
        private inner class Screen(val owner: InventoryHolder?, val title: String, val size: Int = 54) {
            val items = arrayOfNulls<ItemStack>(size)
            val inventory: Inventory = stub(Inventory::class.java) { name, args -> when (name) {
                "getHolder" -> owner
                "getSize" -> size
                "setItem" -> { items[args!![0] as Int] = args[1] as? ItemStack; null }
                "getItem" -> items[args!![0] as Int]
                else -> null
            } }
        }
        private var screen: Screen? = null
        private val empty = Screen(null, "Player")
        val title get() = screen?.title.orEmpty()
        var items = arrayOfNulls<ItemStack>(36)
        private val markers = mutableMapOf<NamespacedKey, Any>()
        private val persistent = stub(PersistentDataContainer::class.java) { name, args -> when (name) {
            "set" -> { markers[args!![0] as NamespacedKey] = args[2]!!; null }
            "get" -> markers[args!![0] as NamespacedKey]
            else -> null
        } }
        private val inventory = stub(PlayerInventory::class.java) { name, args -> when (name) {
            "getItem" -> items.getOrNull(args!![0] as Int)
            "getStorageContents" -> items.copyOf()
            "setStorageContents" -> { @Suppress("UNCHECKED_CAST") val next = args!![0] as Array<ItemStack?>; items = next; null }
            "getMaxStackSize" -> 64
            "getSize" -> 41
            else -> null
        } }
        private val scheduler = stub(EntityScheduler::class.java) { name, args -> when (name) {
            "execute" -> { work += args!![1] as Runnable; true }
            "runDelayed" -> stub(ScheduledTask::class.java) { _, _ -> null }
            else -> null
        } }
        private val signSide = stub(SignSide::class.java) { _, _ -> null }
        private val sign = stub(Sign::class.java) { name, _ -> if (name == "getSide") signSide else null }
        private val data = stub(BlockData::class.java) { name, _ -> if (name == "createBlockState") sign else null }
        private val regions = stub(RegionScheduler::class.java) { _, _ -> null }
        private val server = stub(Server::class.java) { name, args -> when (name) {
            "createInventory" -> Screen(args!![0] as InventoryHolder, plain(args[2] as Component), args[1] as Int).also { inventories[it.inventory] = it }.inventory
            "createBlockData" -> data
            "getRegionScheduler" -> regions
            "getWorlds" -> listOf(world)
            "getOnlinePlayers" -> if (multiplayer || sharedWallet) listOf(player, guest.player) else listOf(player)
            "getWorld" -> world
            "getPlayer" -> when (args!![0]) { owner -> player; guestId -> guest.player; else -> null }
            "isPrimaryThread" -> true
            "getOfflinePlayer" -> stub(OfflinePlayer::class.java) { method, _ -> if (method == "getName") "OtherPlayer" else null }
            else -> null
        } }
        private val yaml = YamlConfiguration().apply {
            set("hub.world", "hub"); set("market.products.OAK_LOG.sell-price", 4); set("market.products.DIAMOND.sell-price", 100)
        }
        private val plugin = stub(Plugin::class.java) { name, args -> when (name) {
            "getName", "namespace" -> "one-block"
            "getServer" -> server
            "getLogger" -> logger
            "getConfig" -> yaml
            "getDataFolder" -> folder.toFile()
            "getResource" -> javaClass.getResourceAsStream("/${args!![0]}")
            else -> null
        } }
        val player = stub(Player::class.java) { name, args -> when (name) {
            "getUniqueId" -> owner
            "getName" -> "TestPlayer"
            "getWorld" -> world
            "getLocation" -> Location(world, 0.5, 100.0, 0.5)
            "getGameMode" -> GameMode.CREATIVE
            "isOnline", "hasPermission" -> true
            "isDead" -> false
            "getInventory" -> inventory
            "getItemOnCursor" -> Stack(Material.AIR, 0)
            "getPersistentDataContainer" -> persistent
            "saveData" -> { check(!failSave) { "Simulated playerdata failure" }; null }
            "getScheduler" -> scheduler
            "locale" -> Locale.ENGLISH
            "getOpenInventory" -> view(screen ?: empty)
            "openInventory" -> { switch(inventories[args!![0]]); view(screen ?: empty) }
            "closeInventory" -> { switch(null); null }
            "sendBlockChange", "sendBlockUpdate", "openVirtualSign" -> null
            "sendMessage" -> { (args?.lastOrNull() as? Component)?.let { messages += plain(it) }; null }
            else -> null
        } }
        val controller: MarketController
        val guest = Guest()
        inner class Guest {
            val queue = ConcurrentLinkedDeque<Runnable>()
            var opened: Inventory? = null
            private val scheduler = stub(EntityScheduler::class.java) { name, args -> if (name == "execute") { queue += args!![1] as Runnable; true } else null }
            private val inventory = stub(PlayerInventory::class.java) { name, _ -> when (name) {
                "getStorageContents" -> arrayOfNulls<ItemStack>(36); "getSize" -> 41; "getMaxStackSize" -> 64; else -> null
            } }
            private val pdc = stub(PersistentDataContainer::class.java) { _, _ -> null }
            val player: Player = stub(Player::class.java) { name, args -> when (name) {
                "getUniqueId" -> guestId; "getName" -> "SecondPlayer"; "getWorld" -> world
                "getLocation" -> Location(world, 0.5, 100.0, 0.5); "getGameMode" -> GameMode.CREATIVE
                "isOnline", "hasPermission" -> true; "isDead" -> false; "locale" -> Locale.ENGLISH
                "getInventory" -> inventory; "getPersistentDataContainer" -> pdc; "getScheduler" -> scheduler
                "getOpenInventory" -> stub(InventoryView::class.java) { method, _ -> when (method) {
                    "getPlayer" -> player; "getTopInventory" -> opened ?: empty.inventory; "getBottomInventory" -> inventory; else -> null
                } }
                "openInventory" -> { opened = args!![0] as Inventory; null }
                "closeInventory" -> { opened = null; null }
                else -> null
            } }
            fun open(kind: String) { controller.interact(player, kind, { Location(world, 0.5, 100.0, 0.5) }, { true }); drain() }
            fun drain() { var count = 0; while (queue.isNotEmpty()) { check(count++ < 100); queue.pollFirst()?.run() } }
        }
        private val serverField = Bukkit::class.java.getDeclaredField("server").apply { isAccessible = true }
        private val previousServer = serverField.get(null)
        init {
            val db = "nav_" + UUID.randomUUID().toString().replace("-", "")
            postgres.postgresDatabase.connection.use { c -> c.createStatement().use { it.execute("CREATE DATABASE $db") } }
            repo = PostgresStorageService(folder.toFile(), logger, DatabaseSettings("127.0.0.1", postgres.port, db, "postgres", "test-only", "disable", "disable", 5))
            repo.load(); repo.saveProfile(com.alytanta.oneblock.player.PlayerProfile(owner, activeAccount = owner)); repo.saveIsland(island)
            if (multiplayer || sharedWallet) {
                repo.saveProfile(com.alytanta.oneblock.player.PlayerProfile(guestId, activeAccount = if (sharedWallet) owner else guestId))
                if (!sharedWallet) repo.saveIsland(island.copy(owner = guestId, worldName = IslandWorldIdentity.worldName(guestId)))
            }
            val islands = IslandService(plugin, PluginConfig.load(yaml, logger), repo)
            val locales = LocaleService(plugin, "en", PlayerService(repo))
            val table = javaClass.getResourceAsStream("/lang/en.yml")!!.reader().use { YamlConfiguration.loadConfiguration(it) }
            locales.applyMessages("en", mapOf("en" to LocaleService.messageTable(table, YamlConfiguration())))
            val hub = HubService(plugin, HubSettings.load(yaml), islands, locales)
            controller = MarketController(plugin, MarketSettings.load(yaml).copy(auction = rules), repo, islands, hub, locales,
                rarityComponent = { ItemRarity.COMMON },
                plainStack = { material, amount -> Stack(material, amount) },
                auctionRenderer = { _, lines -> Icon(Material.DIAMOND_SWORD, listOf("Preview sword") + lines.map(::plain)) },
                auctionDecoder = { AuctionTestStack() }, displayCopy={_,item,style,values->
                    val old=item as Icon
                    fun expand(s:String)=values.entries.fold(s) {text,(key,value)->text.replace("{$key}",value)}
                    Icon(style?.icon?:old.type,listOf(style?.name?.let(::expand)?:old.lines.first())+(style?.lore?.map(::expand)?:old.lines.drop(1)))
                }) { material, lines -> Icon(material, lines.map(::plain)) }
            serverField.set(null, server)
        }
        private fun plain(component: Component) = PlainTextComponentSerializer.plainText().serialize(component)
        private fun view(top: Screen): InventoryView = stub(InventoryView::class.java) { name, args -> when (name) {
            "getPlayer" -> player
            "getTopInventory" -> top.inventory
            "getBottomInventory" -> inventory
            "convertSlot" -> (args!![0] as Int).let { if (it < top.size) it else (it - top.size).let { slot -> if (slot < 27) slot + 9 else slot - 27 } }
            "getInventory" -> if ((args!![0] as Int) < top.size) top.inventory else inventory
            "getItem" -> (args!![0] as Int).let { raw -> if (raw < top.size) top.items.getOrNull(raw) else (raw - top.size).let { slot -> items.getOrNull(if(slot < 27) slot + 9 else slot - 27) } }
            else -> null
        } }
        private fun switch(next: Screen?) {
            screen?.let { controller.onClose(InventoryCloseEvent(view(it))) }
            screen = next
        }
        fun open(kind: String = "bazaar") { controller.interact(player, kind, { Location(world, 0.5, 100.0, 0.5) }, { true }); drain() }
        fun wood() { click("Woods & Fishes");click("Wood") }
        fun has(text: String) = screen?.items?.any { (it as? Icon)?.lines?.firstOrNull()?.startsWith(text) == true } == true
        fun raw(slot: Int, click: ClickType = ClickType.LEFT) {
            controller.onClick(InventoryClickEvent(view(screen ?: empty), InventoryType.SlotType.CONTAINER, slot, click, InventoryAction.PICKUP_ALL))
        }
        fun inventoryClick(slot: Int, click: ClickType = ClickType.LEFT) { raw(screen!!.size + if (slot < 9) slot + 27 else slot - 9, click) }
        fun material(slot: Int) = screen!!.items[slot]?.type
        fun previewCount() = screen!!.items.count { (it as? Icon)?.lines?.firstOrNull() == "Preview sword" }
        val size get() = screen!!.size
        fun click(text: String, click: ClickType = ClickType.LEFT) { raw(find(text), click); drain() }
        fun find(text: String) = screen!!.items.indexOfFirst { (it as? Icon)?.lines?.firstOrNull() == text }.takeIf { it>=0 }
            ?: screen!!.items.indexOfFirst { (it as? Icon)?.lines?.firstOrNull()?.startsWith(text) == true }
            .also { assertTrue(it >= 0, "Missing '$text' on $title: ${screen!!.items.mapNotNull { (it as? Icon)?.lines?.firstOrNull() }}") }
        fun lore(text: String) = (screen!!.items[find(text)] as Icon).lines.joinToString("\n")
        fun drain() { var count = 0; while (work.isNotEmpty()) { check(count++ < 100); work.pollFirst()?.run() } }
        fun nextTask() { requireNotNull(work.pollFirst()).run() }
        fun flush() {
            repo.flushAsync().get(10, TimeUnit.SECONDS)
            // Coalesced commit barriers may wake this waiter before other callers' completion callbacks run.
            val writer=repo.javaClass.getDeclaredField("worker").apply { isAccessible=true }.get(repo) as java.util.concurrent.ExecutorService
            writer.submit {}.get(10,TimeUnit.SECONDS)
            drain()
        }
        fun settle() { repeat(4) { flush() } }
        fun submit(text: String) {
            controller.signInput.onSign(UncheckedSignChangeEvent(player, Position.block(0, 101, 0), Side.FRONT,
                listOf(Component.text(text), Component.empty(), Component.empty(), Component.empty())))
            drain()
        }
        fun seed(orders: List<MarketOrder>) {
            orders.distinctBy { it.account }.forEach { order ->
                if(repo.loadProfiles().none { it.uniqueId==order.actor })repo.saveProfile(com.alytanta.oneblock.player.PlayerProfile(order.actor))
                if(repo.loadIslands().none { it.owner==order.account })repo.saveIsland(island.copy(owner=order.account,ownerPlayerId=order.actor,worldName=IslandWorldIdentity.worldName(order.account)))
            }
            repo.changeMarket(island) { MarketChange(island.coins, it.copy(orders = orders.associateBy { order -> order.id }), Unit) }
        }
        fun seedAuctions(auctions: List<AuctionListing>) {
            val accounts = auctions.flatMap { listing -> listOf(listing.account to listing.actor) + listing.bids.map { it.account to it.actor } }
            accounts.distinctBy { it.first }.filter { (id, _) -> repo.loadIslands().none { it.owner == id } }.forEach { (id, actor) ->
                if (repo.loadProfiles().none { it.uniqueId == actor }) repo.saveProfile(com.alytanta.oneblock.player.PlayerProfile(actor))
                repo.saveIsland(island.copy(owner = id, ownerPlayerId = actor, worldName = IslandWorldIdentity.worldName(id)))
            }
            repo.changeMarket(repo.loadIslands().single { it.owner == owner }) { MarketChange(repo.loadIslands().single { it.owner == owner }.coins, it.copy(auctions = auctions.associateBy { listing -> listing.id }), Unit) }
        }
        fun listing(mode: AuctionMode = AuctionMode.BIN, mine: Boolean = false, price: Long = 50): AuctionListing {
            val seller = if (mine) owner else UUID.randomUUID(); val now = System.currentTimeMillis()
            val draft = AuctionDraft("preview", "DIAMOND_SWORD", 1, "Sword", "WEAPONS", mode, price, 1, 0, false)
            return AuctionListing(UUID.randomUUID(), seller, seller, draft, now - 100000, now - 80000, now + 3600000, 100)
        }
        fun seedClaims(claims: List<MarketClaim>) {
            val current = repo.loadIslands().single { it.owner == owner }
            repo.changeMarket(current) { MarketChange(current.coins, it.copy(claims = claims.associateBy { claim -> claim.id }), Unit) }
        }
        fun publishing(mode: AuctionMode = AuctionMode.BIN) {
            open("auction"); inventoryClick(0); drain()
            if (mode == AuctionMode.BID) click("Mode:")
            click("Whole lot price"); submit("75"); click("Publish auction")
        }
        override fun close() { repo.close(); InventoryGuard.release(owner); InventoryGuard.release(guestId); serverField.set(null, previousServer) }
    }

    @Test fun `same NPC gives two players independent auction menus and closing one keeps the other usable`() {
        Fixture(multiplayer = true).use { f ->
            for (kind in listOf("shop", "bazaar", "auction")) {
                f.open(kind); f.guest.open(kind)
                assertNotSame(f.player.openInventory.topInventory.holder, f.guest.opened!!.holder)
                assertEquals(kind, f.controller.session(f.guest.player)!!.kind)
            }
            val second = f.guest.opened!!
            val secondSession = f.controller.session(f.guest.player)!!
            assertNotEquals(f.controller.session(f.player)!!.id, secondSession.id)
            assertNotSame(f.player.openInventory.topInventory.holder, second.holder)
            f.click("Auction browser")
            assertSame(second, f.guest.opened)
            f.player.closeInventory(); f.drain()
            assertEquals(secondSession.id, f.controller.session(f.guest.player)!!.id)
            f.controller.auctionBrowse(f.guest.player); f.guest.drain()
            assertNotSame(second, f.guest.opened)
            assertEquals(secondSession.id, (f.guest.opened!!.holder as MarketController.Holder).session)
        }
    }

    @Test fun `simultaneous auction buyers at same NPC debit once and create exactly one item and proceeds claim`() {
        Fixture(multiplayer = true).use { f ->
            val lot = f.listing(price = 750)
            f.seedAuctions(listOf(lot)); f.open("auction"); f.guest.open("auction")
            val start = java.util.concurrent.CountDownLatch(1)
            val attempts = listOf(f.player, f.guest.player).map { actor -> java.util.concurrent.CompletableFuture.runAsync {
                check(start.await(5, TimeUnit.SECONDS))
                f.controller.operation(actor, { buyAuction(lot.id, 750) }) { }
            } }
            start.countDown(); attempts.forEach { it.get(10, TimeUnit.SECONDS) }; f.flush(); f.guest.drain()
            val state = f.repo.marketState()
            assertEquals(AuctionStatus.SOLD, state.auctions.getValue(lot.id).status)
            assertEquals(1, state.claims.values.count { it.source == "auction-purchase:${lot.id}" })
            assertEquals(1, state.claims.values.count { it.source == "auction-proceeds:${lot.id}" })
            assertEquals(1250L, f.repo.loadIslands().filter { it.owner != lot.account }.sumOf { it.coins })
        }
    }

    @Test fun `two co-op members using the same NPC cannot overspend their shared wallet`() {
        Fixture(sharedWallet = true).use { f ->
            val lots = listOf(f.listing(price = 750), f.listing(price = 750))
            f.seedAuctions(lots); f.open("auction"); f.guest.open("auction")
            val start = java.util.concurrent.CountDownLatch(1)
            val attempts = listOf(f.player, f.guest.player).zip(lots).map { (actor, lot) -> java.util.concurrent.CompletableFuture.runAsync {
                check(start.await(5, TimeUnit.SECONDS)); f.controller.operation(actor, { buyAuction(lot.id, 750) }) { }
            } }
            start.countDown(); attempts.forEach { it.get(10, TimeUnit.SECONDS) }; f.flush(); f.guest.drain()
            assertEquals(1, f.repo.marketState().auctions.values.count { it.status == AuctionStatus.SOLD })
            assertEquals(250L, f.repo.loadIslands().single { it.owner == f.island.owner }.coins)
        }
    }

    @Test fun `configured auction menu moves and hides controls without leaving old slots actionable`() {
        Fixture().use { f ->
            val file = folder.resolve("custom-market-menus.yml").toFile().apply {
                writeText("auction-layout-version: 3\nwindows:\n  auction-main:\n    title: Custom {player}\n    buttons:\n      '11': {slot: 12, name: Configured browser}\n      '15': {enabled: false}\n")
            }
            f.controller.applySettings(f.controller.settings.copy(menus = MarketMenuLayouts.read(file).first)); f.drain()
            f.open("auction"); assertEquals("Custom TestPlayer", f.title)
            assertEquals(12, f.find("Configured browser")); assertFalse(f.has("Manage auctions"))
            val before = f.player.openInventory.topInventory
            f.raw(11); f.raw(15); f.drain()
            assertSame(before, f.player.openInventory.topInventory)
            f.click("Configured browser"); assertNotSame(before, f.player.openInventory.topInventory)
            assertTrue(f.has("Type:"))
        }
    }

    @Test fun `information empty inventory clicks and no-liquidity choice leave back and trade buttons usable`() {
        Fixture().use { f ->
            f.open(); f.raw(4); f.drain(); f.raw(90); f.drain();f.wood()
            f.click("Oak Log"); f.click("Buy instantly"); f.click("1 items")
            assertEquals("Choose amount", f.title)
            assertTrue(f.messages.any { "opposing" in it || "liquidity" in it || "orders" in it })
            f.click("Back"); f.click("Create buy order")
            assertEquals("Choose amount", f.title)
        }
    }

    @Test fun `disabled suggestion custom sign invalid value and cancel return to editable menus`() {
        Fixture().use { f ->
            f.open(); f.wood();f.click("Oak Log"); f.click("Create buy order"); f.click("1 items")
            assertEquals("Choose unit price", f.title)
            f.click("Match best order") // No orders: no action, and no permanent lock.
            f.click("Enter exact price"); f.submit("oops")
            assertEquals("Choose unit price", f.title)
            f.click("Enter exact price"); f.submit("")
            assertEquals("Choose unit price", f.title)
            f.click("Enter exact price"); f.submit("12")
            assertEquals("New order", f.title)
            assertTrue(f.lore("Oak Log").contains("12"))
            f.click("Enter exact quantity"); f.click("16 items")
            assertEquals("New order", f.title)
            assertTrue(f.lore("Oak Log").contains("16"))
        }
    }

    @Test fun `duplicate confirmation packets create one persisted order and navigation survives`() {
        Fixture().use { f ->
            f.open(); f.wood();f.click("Oak Log"); f.click("Create buy order"); f.click("1 items")
            f.click("Enter exact price"); f.submit("4")
            val slot = f.find("Confirm")
            f.raw(slot); f.raw(slot); f.drain(); f.flush()
            assertEquals(1, f.repo.marketState().orders.size)
            assertEquals(996, f.repo.loadIslands().single().coins)
            f.click("Back")
            assertEquals("Bazaar", f.title)
        }
    }

    @Test fun `hover exposes both sides and full book paginates every order without disabling headers`() {
        Fixture().use { f ->
            val other = UUID.randomUUID()
            val orders = (1..17).flatMap { n -> OrderSide.entries.map { side ->
                MarketOrder(UUID.randomUUID(), other, other, "OAK_LOG", side, if (side == OrderSide.BUY) n.toLong() else 100L + n,
                    10, 10, (n * 2L) + side.ordinal, 4_102_444_800_000L)
            } }
            f.seed(orders); f.open();f.wood()
            val tooltip = f.lore("Oak Log")
            assertTrue(tooltip.contains("Buy: 17")); assertTrue(tooltip.contains("Sell: 17"))
            f.click("Oak Log", ClickType.RIGHT)
            assertEquals("Buy and sell order book", f.title)
            f.click("Buy: 17") // Informational header.
            f.click("Next"); f.click("Previous"); f.click("Back")
            assertEquals("Bazaar", f.title)
        }
    }

    @Test fun `insufficient funds confirmation can go back edit price and submit without reopening NPC`() {
        Fixture().use { f ->
            f.open(); f.wood();f.click("Oak Log"); f.click("Create buy order"); f.click("1 items")
            f.click("Enter exact price"); f.submit("2000"); f.click("Confirm")
            assertTrue(f.repo.marketState().orders.isEmpty())
            f.click("Enter exact price"); f.click("Enter exact price"); f.submit("4")
            f.click("Confirm"); f.flush()
            assertEquals(1, f.repo.marketState().orders.size)
            assertEquals(996, f.repo.loadIslands().single().coins)
        }
    }

    @Test fun `merchant stays sell only and an unavailable sell-all button does not block Back`() {
        Fixture().use { f ->
            f.open("shop");f.wood(); f.click("Oak Log")
            assertFalse(f.has("Buy instantly")); assertFalse(f.has("Create buy order"))
            f.click("Sell resources to merchant"); f.click("All available: 0")
            f.click("Back")
            assertTrue(f.has("Sell resources to merchant"))
        }
    }
    @Test fun `auction root search sort mode and category filters remain navigable`() {
        Fixture().use { f ->
            f.open("auction"); assertEquals("Auction House", f.title)
            f.click("Auction browser"); f.click("Sort:"); f.click("Lowest price"); f.click("Type:")
            f.click("Search:"); f.submit("sword"); f.click("✔ Weapons"); f.click("Clear filters")
            f.click("Back"); assertEquals("Auction House", f.title)
            f.click("View bids"); f.click("Back"); f.click("Manage auctions"); f.click("Back")
            f.click("Manage auctions"); f.click("Back"); assertEquals("Auction House", f.title)
        }
    }
    @Test fun `stale BIN repeated confirmation sells once and still allows navigation`() {
        Fixture().use { f ->
            val seller = UUID.randomUUID()
            val now = System.currentTimeMillis()
            val draft = AuctionDraft("test-preview", "DIAMOND_SWORD", 1, "Sword", "WEAPONS", AuctionMode.BIN, 50, 1, 0, false)
            val lot = AuctionListing(UUID.randomUUID(), seller, seller, draft, now - 100000, now - 80000, now + 3600000, 100)
            f.seedAuctions(listOf(lot)); f.open("auction"); f.click("Auction browser"); f.click("Preview sword")
            f.click("Buy for 50"); val slot = f.find("Confirm"); f.raw(slot); f.raw(slot); f.drain(); f.flush()
            assertEquals(AuctionStatus.SOLD, f.repo.marketState().auctions.getValue(lot.id).status)
            assertEquals(950L, f.repo.loadIslands().single { it.owner == f.island.owner }.coins)
            assertEquals(1, f.repo.marketState().claims.values.count { it.itemData != null })
            f.click("Back"); assertEquals("Auction House", f.title)
        }
    }
    @Test fun `Bazaar category search clears and variants remain navigable without retired sort`() {
        Fixture().use { f ->
            f.open();f.click("Woods & Fishes"); f.click("Search:"); f.submit("missing"); assertTrue(f.has("No matching products"))
            f.click("Search:"); f.submit(""); assertTrue(f.has("Wood"));assertFalse(f.has("Sort:"))
            f.click("Wood"); assertTrue(f.has("Oak Log"))
        }
    }
    @Test fun `merchant product sell all only sells that resource and buyback returns it at the same price`() {
        Fixture().use { f ->
            f.items[0] = Stack(Material.DIAMOND, 2); f.items[1] = Stack(Material.OAK_LOG, 3)
            f.items[2] = Stack(Material.DIAMOND, 1, tag = "custom"); f.items[8] = Stack(Material.NETHER_STAR, menu = true)
            f.open("shop");f.click("Mining"); f.click("Diamond");f.click("Diamond"); f.click("Sell all Diamond"); f.click("Confirm"); f.settle()
            assertEquals(1200L, f.repo.loadIslands().single().coins)
            assertTrue(f.items[0] == null); assertEquals(3, f.items[1]!!.amount); assertTrue(f.items[2]!!.hasItemMeta())
            assertEquals(1, f.repo.marketState().merchantSales.size)
            f.click("Sale history / Buyback"); f.click("DIAMOND ×2")
            val slot = f.find("Confirm"); f.raw(slot); f.raw(slot); f.drain(); f.settle()
            assertEquals(1000L, f.repo.loadIslands().single().coins)
            assertEquals(2, f.items[0]!!.amount); assertTrue(f.repo.marketState().merchantSales.isEmpty())
            assertEquals(Material.NETHER_STAR, f.items[8]!!.type)
        }
    }
    @Test fun `merchant global sell all sells every allowed resource atomically`() {
        Fixture().use { f ->
            f.items[0] = Stack(Material.DIAMOND, 2); f.items[1] = Stack(Material.OAK_LOG, 3)
            f.items[2] = Stack(Material.DIAMOND, 1, tag = "named"); f.items[3] = Stack(Material.EMERALD, 4)
            f.open("shop"); f.click("Sell all resources"); f.click("Confirm"); f.settle()
            assertEquals(1212L, f.repo.loadIslands().single().coins)
            assertTrue(f.items[0] == null); assertTrue(f.items[1] == null); assertEquals(2, f.repo.marketState().merchantSales.size)
            assertTrue(f.items[2] != null); assertEquals(4, f.items[3]!!.amount)
            f.click("Sale history / Buyback"); assertTrue(f.has("DIAMOND ×2")); assertTrue(f.has("OAK_LOG ×3"))
        }
    }
    @Test fun `ordinary item with no explicit rarity opens auction draft price editor and final review`() {
        Fixture().use { f ->
            f.items[0] = AuctionTestStack()
            f.open("auction"); f.inventoryClick(0); f.drain()
            assertTrue(f.has("Publish auction"))
            f.click("Publish auction") // A price must still be entered explicitly.
            assertTrue(f.has("Publish auction"))
            f.click("Whole lot price"); f.submit("75")
            assertTrue(f.lore("Preview sword").contains("75"))
            f.click("Publish auction"); assertTrue(f.has("Confirm"))
            f.click("Cancel"); assertTrue(f.has("Publish auction"))
            assertTrue(f.lore("Preview sword").contains("75"))
            assertTrue(f.repo.marketState().auctions.isEmpty())
            assertEquals(1000L, f.repo.loadIslands().single().coins)
        }
    }
    @Test fun `auction publication escrows the exact item before either BIN or BID becomes visible`() {
        for (mode in AuctionMode.entries) Fixture().use { f ->
            f.items[0] = AuctionTestStack(); f.items[1] = AuctionTestStack()
            f.publishing(mode)
            f.raw(f.find("Confirm")); f.nextTask()
            assertTrue(f.items[0] != null); assertTrue(InventoryGuard.busy(f.player.uniqueId))
            assertTrue(f.repo.marketState().auctions.isEmpty())
            assertEquals(TransferStatus.PREPARED, f.repo.marketState().transfers.values.single().status)
            f.settle()
            assertTrue(f.items[0] == null); assertTrue(f.items[1] != null)
            val lot = f.repo.marketState().auctions.values.single()
            assertEquals(mode, lot.item.mode); assertEquals(75, lot.item.price)
            assertEquals(TransferStatus.DONE, f.repo.marketState().transfers.getValue(lot.id).status)
            assertFalse(InventoryGuard.busy(f.player.uniqueId)); assertTrue(f.repo.healthy)
        }
    }
    @Test fun `auction root is compact and inventory selection uses actual hotbar and storage slots without moving items`() {
        for (slot in listOf(0, 9, 35)) Fixture().use { f ->
            f.items[slot] = AuctionTestStack(); f.open("auction")
            assertEquals(36, f.size); assertEquals(Material.BARRIER, f.material(31)); assertEquals(Material.MAP, f.material(32))
            assertFalse(f.has("Buy It Now")); assertFalse(f.has("Ending soon")); assertFalse(f.has("Shared island balance"))
            f.inventoryClick(slot, ClickType.RIGHT); f.drain()
            assertTrue(f.has("Publish auction")); assertEquals(AuctionTestStack(), f.items[slot])
            assertEquals(Material.LIME_TERRACOTTA, f.material(29)); assertEquals(Material.GOLD_INGOT, f.material(31))
            assertEquals(Material.CLOCK, f.material(33)); assertEquals(Material.ARROW, f.material(49))
            assertTrue(f.repo.marketState().auctions.isEmpty()); assertTrue(f.repo.marketState().transfers.isEmpty())
            f.click("Duration:"); assertFalse(f.has("Shared island balance")); f.click("6 hours")
            assertTrue(f.has("Duration: 6 hours"))
            f.click("Publish auction"); assertTrue(f.has("Publish auction")) // Explicit price still required.
            f.click("Preview sword"); assertTrue(f.has("Choose an item")); assertEquals(AuctionTestStack(), f.items[slot])
            f.inventoryClick(slot); f.drain(); assertTrue(f.has("Duration: 6 hours"))
        }
    }
    @Test fun `auction inventory shortcuts reject reserved invalid shifted and changed stacks`() {
        Fixture(AuctionSettings(allowedItems = setOf("DIAMOND_SWORD"))).use { f ->
            f.items[8] = AuctionTestStack(); f.items[0] = Stack(Material.DIRT); f.items[1] = AuctionTestStack()
            f.open("auction"); f.inventoryClick(8); f.drain(); assertEquals(36, f.size)
            f.inventoryClick(0); f.drain(); assertEquals(36, f.size)
            f.inventoryClick(1, ClickType.SHIFT_LEFT); f.drain(); assertEquals(36, f.size)
            f.inventoryClick(1); f.items[1] = Stack(Material.STONE); f.drain(); assertEquals(36, f.size)
            assertTrue(f.repo.marketState().transfers.isEmpty()); assertTrue(f.repo.marketState().auctions.isEmpty())
            f.items[1] = AuctionTestStack(); f.inventoryClick(1); f.inventoryClick(1); f.drain()
            assertTrue(f.has("Publish auction")); assertEquals(AuctionTestStack(), f.items[1])
        }
    }
    @Test fun `auction browser has direct bottom filters no wallet and preserves BIN choice through Back`() {
        Fixture().use { f ->
            val bin = f.listing(); val bid = f.listing(AuctionMode.BID, price = 80)
            f.seedAuctions(listOf(bin, bid)); f.items[0] = AuctionTestStack()
            f.open("auction"); f.click("Auction browser")
            assertEquals(Material.OAK_SIGN, f.material(48)); assertEquals(Material.ARROW, f.material(49))
            assertEquals(Material.HOPPER, f.material(50)); assertEquals(Material.ENDER_EYE, f.material(51)); assertEquals(Material.POWERED_RAIL, f.material(52))
            assertEquals(2, f.previewCount()); assertFalse(f.has("Shared island balance")); assertFalse(f.has("Auction filters"))
            f.inventoryClick(0); f.drain(); assertEquals(2, f.previewCount()); assertEquals(AuctionTestStack(), f.items[0])
            f.click("Type:"); assertEquals(Material.GOLD_INGOT, f.material(52)); assertEquals(1, f.previewCount())
            f.click("Back"); f.click("Auction browser"); assertEquals(Material.GOLD_INGOT, f.material(52)); assertEquals(1, f.previewCount())
            f.click("Type:"); assertEquals(Material.GOLD_BLOCK, f.material(52)); assertEquals(1, f.previewCount())
            f.click("Type:"); assertEquals(Material.POWERED_RAIL, f.material(52)); assertEquals(2, f.previewCount())
            f.click("Sort:"); assertFalse(f.has("Shared island balance")); f.click("Lowest price")
            f.click("Item tier:"); f.click("Rare"); assertTrue(f.has("No matching auctions"))
            f.click("Item tier:"); f.click("All tiers"); assertEquals(2, f.previewCount())
            f.click("Search:"); f.submit("missing"); assertTrue(f.has("No matching auctions"))
            f.click("Clear filters"); assertEquals(2, f.previewCount()); assertEquals(Material.POWERED_RAIL, f.material(52))
        }
    }
    @Test fun `auction confirmation has confirm item and cancel only and never consumes on cancel`() {
        Fixture().use { f ->
            f.items[0] = AuctionTestStack(); f.publishing()
            assertEquals(27, f.size); assertEquals(Material.LIME_TERRACOTTA, f.material(11)); assertEquals(Material.RED_TERRACOTTA, f.material(15))
            assertFalse(f.has("Shared island balance")); assertFalse(f.has("Refresh prices")); assertFalse(f.has("Manage auctions"))
            f.click("Cancel"); assertTrue(f.has("Publish auction")); assertEquals(AuctionTestStack(), f.items[0])
            assertTrue(f.repo.marketState().auctions.isEmpty()); assertTrue(f.repo.marketState().transfers.isEmpty())
        }
    }
    @Test fun `auction cancel and immediate item withdrawal remove the lot from active personal listings`() {
        Fixture().use { f ->
            f.items[0] = AuctionTestStack(); f.publishing(); f.click("Confirm"); f.settle()
            val id = f.repo.marketState().auctions.keys.single()
            f.click("Preview sword"); f.click("Cancel order"); f.click("Confirm"); f.settle()
            assertTrue(f.items[0] == null); assertEquals(AuctionStatus.CANCELLED, f.repo.marketState().auctions.getValue(id).status)
            f.click("Back"); f.click("Manage auctions")
            assertTrue(f.has("Collect items and coins")); assertFalse(f.has("Preview sword"))
            f.click("Collect items and coins")
            f.click("Preview sword"); f.settle()
            assertEquals(AuctionTestStack(), f.items[0]); assertTrue(f.repo.marketState().claims.isEmpty())
            f.click("Back"); f.click("Manage auctions")
            assertTrue(f.has("Choose an item")); assertFalse(f.has("Preview sword"))
            assertEquals(AuctionStatus.CANCELLED, f.repo.marketState().auctions.getValue(id).status)
        }
    }
    @Test fun `auction collections filter coins and items and credit this profile exactly once`() {
        Fixture().use { f ->
            val money = MarketClaim(UUID.randomUUID(), f.island.owner, null, 57, "auction-proceeds:test")
            val item = MarketClaim(UUID.randomUUID(), f.island.owner, "DIAMOND_SWORD", 1, "auction-purchase:test", "preview")
            f.seedClaims(listOf(money, item)); f.open("auction"); f.controller.claims(f.player)
            assertEquals("Auction Collections", f.title)
            assertTrue(f.has("Collect 57 coins")); assertTrue(f.has("Preview sword"))
            f.click("Show:")
            assertFalse(f.has("Collect 57 coins")); assertTrue(f.has("Preview sword"))
            f.click("Show:")
            assertTrue(f.has("Collect 57 coins")); assertFalse(f.has("Preview sword"))
            f.click("Collect 57 coins"); f.settle()
            assertEquals(1057, f.repo.loadIslands().single { it.owner == f.island.owner }.coins)
            assertFalse(f.repo.marketState().claims.containsKey(money.id))
            assertTrue(f.repo.marketState().claims.containsKey(item.id))
            assertFalse(f.has("Collect 57 coins"))
        }
    }
    @Test fun `auction viewing own other profile listing offers no BIN buy or bid controls`() {
        for (mode in AuctionMode.entries) Fixture().use { f ->
            val lot = f.listing(mode).copy(actor = f.island.owner)
            f.seedAuctions(listOf(lot)); f.open("auction"); f.click("Auction browser"); f.click("Preview sword")
            assertEquals(Material.BARRIER, f.material(29))
            assertFalse(f.has("Buy item")); assertFalse(f.has("Submit bid")); assertFalse(f.has("Custom bid"))
        }
    }
    @Test fun `auction view refresh hides sold lots and keeps drafts and confirmations stable`() {
        Fixture().use { f ->
            val lot = f.listing(); f.seedAuctions(listOf(lot)); f.open("auction"); f.click("Auction browser")
            assertTrue(f.has("Preview sword"))
            f.seedAuctions(listOf(lot.copy(status = AuctionStatus.SOLD, buyer = f.island.owner, paid = 50)))
            f.controller.refreshAuctionMenus(); f.drain()
            assertTrue(f.has("No matching auctions")); assertFalse(f.has("Preview sword"))
            f.items[0] = AuctionTestStack(); f.publishing()
            f.seedAuctions(listOf(lot.copy(status = AuctionStatus.CANCELLED)))
            f.controller.refreshAuctionMenus(); f.drain()
            assertTrue(f.has("Confirm")); assertTrue(f.lore("Preview sword").contains("75"))
        }
    }
    @Test fun `auction listing loses buy controls immediately after external sale or cancellation`() {
        for (status in listOf(AuctionStatus.SOLD, AuctionStatus.CANCELLED)) Fixture().use { f ->
            val lot = f.listing(); f.seedAuctions(listOf(lot)); f.open("auction"); f.click("Auction browser"); f.click("Preview sword")
            assertTrue(f.has("Buy for"))
            f.seedAuctions(listOf(lot.copy(status = status))); f.controller.refreshAuctionMenus(); f.drain()
            assertFalse(f.has("Buy for")); assertFalse(f.has("Submit bid"))
            assertTrue(f.lore("Preview sword").contains(if (status == AuctionStatus.SOLD) "Sold" else "Cancelled"))
        }
    }
    @Test fun `auction stale bid review cannot silently increase the charge and returns to the current lot`() {
        Fixture().use { f ->
            val lot = f.listing(AuctionMode.BID, price = 100).copy(bids = listOf(AuctionBid(f.island.owner, f.player.uniqueId, 100, 1)))
            f.seedAuctions(listOf(lot)); f.open("auction"); f.click("Auction browser"); f.click("Preview sword")
            assertFalse(f.has("Buy for"))
            f.click("Whole lot price"); f.submit("300"); assertTrue(f.lore("Preview sword").contains("Charge now: 200"))
            val other = UUID.randomUUID()
            f.seedAuctions(listOf(lot.copy(bids = lot.bids + AuctionBid(other, other, 180, 2))))
            f.click("Confirm"); f.settle()
            assertEquals(1000, f.repo.loadIslands().single { it.owner == f.island.owner }.coins)
            assertEquals(2, f.repo.marketState().auctions.getValue(lot.id).bids.size)
            assertTrue(f.has("Submit bid: 207")); assertTrue(f.messages.any { "charge changed" in it })
        }
    }
    @Test fun `auction capped BID has no clickable bid controls and BIN never has bid controls`() {
        Fixture(AuctionSettings(maxBids = 1)).use { f ->
            val lot = f.listing(AuctionMode.BID).copy(bids = listOf(AuctionBid(f.island.owner, f.player.uniqueId, 50, 1)))
            f.seedAuctions(listOf(lot)); f.open("auction"); f.click("Auction browser"); f.click("Preview sword")
            assertTrue(f.has("No further bids")); assertFalse(f.has("Submit bid")); assertFalse(f.has("Whole lot price")); assertFalse(f.has("Buy for"))
        }
        Fixture().use { f ->
            f.seedAuctions(listOf(f.listing())); f.open("auction"); f.click("Auction browser"); f.click("Preview sword")
            assertTrue(f.has("Buy for")); assertFalse(f.has("Submit bid"))
        }
    }
    @Test fun `auction playerdata failure never publishes an item and quarantines the applied handoff`() {
        Fixture().use { f ->
            f.items[0] = AuctionTestStack(); f.publishing(); f.failSave = true
            f.click("Confirm"); f.settle()
            assertTrue(f.items[0] == null); assertTrue(f.repo.marketState().auctions.isEmpty())
            assertEquals(TransferStatus.REVIEW, f.repo.marketState().transfers.values.single().status)
            f.failSave = false; f.controller.recoverPlayer(f.player); f.settle()
            assertTrue(f.repo.marketState().auctions.isEmpty()); assertTrue(f.repo.marketState().claims.isEmpty())
        }
    }
    @Test fun `auction inventory change during prepared handoff aborts without listing or issuing a second item`() {
        Fixture().use { f ->
            f.items[0] = AuctionTestStack(); f.publishing(); f.raw(f.find("Confirm")); f.nextTask()
            f.items[0] = Stack(Material.STONE, 64) // Simulate another plugin changing inventory during the DB round trip.
            f.settle()
            assertEquals(Material.STONE, f.items[0]!!.type); assertTrue(f.repo.marketState().auctions.isEmpty())
            assertEquals(TransferStatus.ABORTED, f.repo.marketState().transfers.values.single().status)
            assertTrue(f.repo.marketState().claims.values.all { it.product == null }); assertFalse(InventoryGuard.busy(f.player.uniqueId))
        }
    }
    @Test fun `auction Back retains search mode category and page without leaking unavailable purchase actions`() {
        Fixture().use { f ->
            val seller = UUID.randomUUID(); val now = System.currentTimeMillis()
            val lots = (1..31).map { index ->
                val draft = AuctionDraft("preview", "DIAMOND_SWORD", 1, "Sword $index", "WEAPONS", AuctionMode.BIN, index.toLong(), 1, 0, false)
                AuctionListing(UUID.randomUUID(), seller, seller, draft, now, now + 60000, now + 3600000, 100)
            }
            f.seedAuctions(lots); f.open("auction"); f.click("Auction browser"); f.click("Type:"); f.click("Search:"); f.submit("Sword")
            f.click("Next"); assertTrue(f.has("Page 2/2"))
            f.click("Preview sword"); assertFalse(f.has("Buy for")); assertTrue(f.has("Available in"))
            f.click("Back"); assertTrue(f.has("Page 2/2")); assertTrue(f.has("Search: Sword"))
            f.click("Clear filters"); assertTrue(f.has("Page 1/2"))
        }
    }
    @Test fun `fill inventory uses real capacity and skips the reserved utility slot`() {
        Fixture().use { f ->
            for (slot in f.items.indices) f.items[slot] = Stack(Material.DIRT,64)
            f.items[0] = null; f.items[1] = Stack(Material.OAK_LOG,60)
            f.items[8] = Stack(Material.NETHER_STAR,menu=true)
            val other = UUID.randomUUID()
            f.seed(listOf(MarketOrder(UUID.randomUUID(),other,other,"OAK_LOG",OrderSide.SELL,1,500,500,1,4_102_444_800_000L)))
            f.open();f.wood(); f.click("Oak Log"); f.click("Buy instantly")
            assertTrue(f.has("Fill inventory: 68 items"))
            f.click("Fill inventory:"); f.click("Confirm"); f.settle()
            assertEquals(932L,f.repo.loadIslands().single { it.owner==f.island.owner }.coins)
            assertEquals(68L,f.repo.marketState().claims.values.single { it.account == f.island.owner && it.product == "OAK_LOG" }.amount)
            assertEquals(Material.NETHER_STAR,f.items[8]!!.type)
        }
    }

    @Test fun `named buttons and queued old clicks cannot execute after reload`() {
        Fixture().use { f ->
            val file=folder.resolve("named.yml").toFile().apply {writeText("auction-layout-version: 3\nwindows: {auction-main: {buttons: {browse: {slot: 12, name: Browse moved}, manage: {enabled: false}}}}")}
            val next=f.controller.settings.copy(menus=MarketMenuLayouts.read(file).first)
            f.controller.applySettings(next);f.drain();f.open("auction")
            assertEquals(12,f.find("Browse moved"));assertFalse(f.has("Manage auctions"))
            val before=f.player.openInventory.topInventory;f.raw(11);f.drain();assertSame(before,f.player.openInventory.topInventory)
            f.raw(12);f.controller.applySettings(next);f.drain()
            assertFalse(f.has("Type:"));assertTrue(f.repo.marketState().auctions.isEmpty())
            f.open("auction");f.click("Browse moved");assertTrue(f.has("Type:"))
        }
    }
    @Test fun `auction option lists paginate with a two cell configurable grid and preserve return navigation`() {
        Fixture().use { f ->
            val file=folder.resolve("pages.yml").toFile().apply {writeText("auction-layout-version: 3\nwindows: {auction-sort: {grids: {content: [10,12]}}}")}
            f.controller.applySettings(f.controller.settings.copy(menus=MarketMenuLayouts.read(file).first));f.drain()
            f.open("auction");f.click("Auction browser");f.click("Sort:")
            assertTrue(f.has("✔ Highest price"));assertTrue(f.has("Lowest price"));assertFalse(f.has("Ending soon"))
            f.click("Next");assertFalse(f.has("✔ Highest price"));f.click("Previous");assertTrue(f.has("✔ Highest price"))
            f.click("Back");assertTrue(f.has("Type:"))
        }
    }
    @Test fun `Bazaar back retains search and shows a useful empty state`() {
        Fixture().use { f ->
            f.open();f.click("Woods & Fishes"); f.click("Search:"); f.submit("Oak"); f.click("Wood");f.click("Oak Log"); f.click("Back");f.click("Back")
            assertTrue(f.has("Search: Oak")); assertFalse(f.has("Diamond"))
            f.click("Search:"); f.submit("missing"); assertTrue(f.has("No matching products"))
            f.click("Search:");f.submit("");assertTrue(f.has("Wood"));f.click("Mining"); assertTrue(f.has("Diamond"))
        }
    }

}
