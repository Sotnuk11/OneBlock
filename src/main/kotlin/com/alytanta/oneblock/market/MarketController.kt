package com.alytanta.oneblock.market

import com.alytanta.oneblock.hub.HubService
import com.alytanta.oneblock.island.IslandService
import com.alytanta.oneblock.locale.Lang
import com.alytanta.oneblock.locale.LocaleService
import com.alytanta.oneblock.storage.PostgresStorageService
import com.alytanta.oneblock.util.Colors
import com.alytanta.oneblock.util.FoliaTasks
import com.alytanta.oneblock.menu.guiStyle
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.inventory.*
import org.bukkit.event.player.*
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.InventoryHolder
import org.bukkit.inventory.ItemStack
import org.bukkit.plugin.Plugin
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** No public trading command. Only a trusted FancyNpcs interaction can create a session. */
class MarketController(internal val plugin: Plugin, initial: MarketSettings, internal val storage: PostgresStorageService,
    internal val islands: IslandService, private val hub: HubService, internal val locales: LocaleService,
    internal val rarityComponent: (ItemStack) -> org.bukkit.inventory.ItemRarity? = AuctionItemRarity::component,
    private val plainStack: (Material, Int) -> ItemStack = { material, amount -> ItemStack(material, amount) },
    internal val auctionRenderer: (String, List<net.kyori.adventure.text.Component>) -> ItemStack = { data, lines ->
        AuctionItems.decode(data).apply { editMeta { meta ->
            meta.displayName()?.let { meta.displayName(Colors.normal(it)) }
            meta.lore((meta.lore().orEmpty() + lines).map(Colors::normal))
        } }
    },
    private val auctionDecoder: (String) -> ItemStack = AuctionItems::decode,
    internal val displayCopy:(Player,ItemStack,MarketMenuButton?,Map<String,String>)->ItemStack = {p,item,style,values->com.alytanta.oneblock.menu.GuiItems.copy(plugin,p,item,style,values)},
    private val icon: (Material, List<net.kyori.adventure.text.Component>) -> ItemStack = { material, lines ->
        ItemStack(material).apply { editMeta { it.displayName(lines.first()); it.lore(lines.drop(1)) } }.let(com.alytanta.oneblock.menu.GuiItems::quiet)
    }) : Listener {
    private fun ledger(settings: MarketSettings) = MarketEngine(settings).also { it.relatedAuctionAccounts = islands::relatedAuctionAccounts }
    @Volatile private var engine = ledger(initial)
    internal val settings get() = engine.settings
    internal data class Session(val id: UUID, val kind: String, val account: UUID, @Volatile var expires: Long,
        val position: () -> Location, val visible: () -> Boolean, val browseOnly: Boolean = false) {
        var auctionReturn: ((Player) -> Unit)? = null
        var auctionMode: AuctionMode? = null
        var catalogReturn: ((Player) -> Unit)? = null
    }
    private val sessions = ConcurrentHashMap<UUID, Session>()
    private data class Prompt(val session: UUID, val token: UUID = UUID.randomUUID())
    private val prompts = ConcurrentHashMap<UUID, Prompt>()
    val signInput = VirtualSignInput(plugin)
    internal class Holder(val viewer: UUID, val session: UUID, val layout: MarketWindow = MarketWindow()) : InventoryHolder {
        lateinit var contents: Inventory
        private val hidden=mutableSetOf<Int>()
        private val positions=mutableMapOf<Int,Int>()
        fun target(source:Int)=if(source in hidden)null else positions[source]?:layout.target(source)
        fun position(source:Int,slot:Int?) {if(slot!=null)positions[source]=slot}
        fun visible(source:Int,value:Boolean) {if(value)hidden.remove(source) else hidden.add(source)}
        val actions = MarketActions<Pair<Player, ClickType>>(::target,layout::identity)
        private val rendered = mutableMapOf<Int, Int>()
        fun place(source: Int, item: ItemStack) {
            val target = target(source) ?: return
            require(rendered[target] == null || rendered[target] == source) {
                "${layout.id}: buttons $source and ${rendered[target]} overlap at $target"
            }
            rendered[target] = source; contents.setItem(target, item)
        }
        fun grid(fallback: List<Int>, name: String = "content") = layout.grid(name, fallback)
        var submitted = false
        var auctionSnapshot: Map<UUID, AuctionListing>? = null
        var auctionClaimSnapshot: Map<UUID, MarketClaim>? = null
        var auctionRefreshAt = Long.MAX_VALUE
        var auctionRefresh: ((Player) -> Unit)? = null
        var auctionItemClick: ((Player, Int, ItemStack) -> Unit)? = null
        fun hasEntries()=rendered.values.any {source->layout.grids.filterKeys {it !in setOf("categories","separators")}.values.any {source in it}}
        override fun getInventory() = contents
    }
    internal val handoffs = ItemHandoffs(plugin, storage, islands, { engine }, locales, plainStack, auctionDecoder, ::refreshAuctionMenus)
    var releaseRecovery: ((Player) -> Unit)? = null
    var onCatalog: ((Player,String) -> Unit)? = null
    var releaseResolution: ((CommandSender,ItemTransfer,Boolean) -> Unit)? = null
    private val refreshPending = ConcurrentHashMap.newKeySet<UUID>()

    internal fun watchAuctions(holder: Holder, state: MarketState, refresh: (Player) -> Unit) {
        holder.auctionSnapshot = state.auctions
        holder.auctionClaimSnapshot = state.claims
        holder.auctionRefresh = refresh
        val now = System.currentTimeMillis()
        holder.auctionRefreshAt = state.auctions.values.asSequence().filter { it.status == AuctionStatus.OPEN }
            .flatMap { sequenceOf(it.available, it.expires) }.filter { it > now }.minOrNull() ?: Long.MAX_VALUE
    }

    /** Refresh changed views on their owner's scheduler; leave drafts/signs/confirmations untouched. */
    internal fun refreshAuctionMenus() {
        if (!storage.healthy) return
        sessions.forEach { (id, active) ->
            if (active.kind != "auction" || !refreshPending.add(id)) return@forEach
            val player = plugin.server.getPlayer(id)
            if (player == null) { refreshPending.remove(id); return@forEach }
            FoliaTasks.entity(plugin, player, retired = { refreshPending.remove(id) }) {
                try {
                    if (InventoryGuard.busy(id) || session(player)?.id != active.id) return@entity
                    val holder = player.openInventory.topInventory.holder as? Holder ?: return@entity
                    if (holder.session != active.id) return@entity
                    val before = holder.auctionSnapshot ?: return@entity
                    val next = storage.marketState()
                    if (before != next.auctions || holder.auctionClaimSnapshot != next.claims || System.currentTimeMillis() >= holder.auctionRefreshAt)
                        holder.auctionRefresh?.invoke(player)
                } catch (e: Exception) {
                    plugin.logger.log(java.util.logging.Level.WARNING, "Auction view refresh failed", e)
                } finally { refreshPending.remove(id) }
            }
        }
    }
    /** Async expiry mutates the ledger; any view work is dispatched to entity schedulers. */
    fun startSettlement() {
        plugin.server.asyncScheduler.runAtFixedRate(plugin, {
            if (!storage.healthy) return@runAtFixedRate
            refreshAuctionMenus()
            val state = storage.marketState()
            val time = System.currentTimeMillis()
            if (state.auctions.values.none { it.status == AuctionStatus.OPEN && it.expires <= time } &&
                state.orders.values.none { it.status == OrderStatus.OPEN && it.expires <= time } &&
                state.merchantSales.values.none { it.expires <= time }) return@runAtFixedRate
            val island = islands.all().firstOrNull { !islands.underMaintenance(it.owner) } ?: return@runAtFixedRate
            runCatching {
                islands.marketUpdate(island.owner) { current, book -> engine.transaction(current.owner, current.owner, current.coins, book) { expire() } }
                storage.flushAsync()
            }.onFailure { plugin.logger.log(java.util.logging.Level.WARNING, "Market expiry settlement failed", it) }
        }, 1, 1, java.util.concurrent.TimeUnit.SECONDS)
    }

    fun npcKind(id: String, name: String): String? = when {
        settings.bazaarNpc in setOf(id, name) -> "bazaar"
        settings.shopNpc in setOf(id, name) -> "shop"
        settings.auction.npc in setOf(id, name) -> "auction"
        else -> null
    }
    fun interact(player: Player, kind: String, position: () -> Location, visible: () -> Boolean) {
        if (!settings.enabled || !storage.healthy || kind == "auction" && !settings.auction.enabled) { locales.send(player, Lang.MARKET_UNAVAILABLE); return }
        prompts.remove(player.uniqueId)
        signInput.cancel(player)
        releaseRecovery?.invoke(player)
        handoffs.recover(player)
        if (InventoryGuard.busy(player.uniqueId)) { locales.send(player, Lang.MARKET_BUSY); return }
        val island = islands.findForPlayer(player.uniqueId)
        val session = Session(UUID.randomUUID(), kind, island?.owner ?: player.uniqueId, System.currentTimeMillis() + settings.sessionSeconds * 1000, position, visible, browseOnly = island == null)
        sessions[player.uniqueId] = session
        if (!allowed(player, session)) { sessions.remove(player.uniqueId, session); locales.send(player, Lang.MARKET_ACCESS); return }
        if (storage.marketState().transfers.values.any { it.actor == player.uniqueId && it.status in setOf(TransferStatus.REVIEW, TransferStatus.PREPARED) }) {
            locales.send(player, Lang.MARKET_REVIEW); return
        }
        storage.audit(com.alytanta.oneblock.storage.OperationLog(operation = "NPC_OPEN", actor = player.uniqueId, account = island?.owner,
            success = true, status = "SUCCESS", correlation = session.id, details = mapOf("kind" to kind)))
        onCatalog?.invoke(player,if(kind=="shop")"merchant" else kind)
        // Merely browsing the catalog must not wait for a PostgreSQL write/round trip.
        if (!session.browseOnly && (storage.marketState().orders.values.any { it.status == OrderStatus.OPEN && it.expires <= System.currentTimeMillis() } ||
            storage.marketState().auctions.values.any { it.status == AuctionStatus.OPEN && it.expires <= System.currentTimeMillis() })) {
            operation(player, { expire() }) { main(it) }
        } else main(player)
    }
    private fun allowed(player: Player, session: Session): Boolean = runCatching {
        val npc = session.position()
        settings.enabled && storage.healthy && player.isOnline && !player.isDead &&
            player.gameMode in setOf(org.bukkit.GameMode.SURVIVAL, org.bukkit.GameMode.ADVENTURE, org.bukkit.GameMode.CREATIVE) && player.hasPermission("oneblock.market.use") &&
            session.expires > System.currentTimeMillis() && (if(session.browseOnly) islands.findForPlayer(player.uniqueId) == null else islands.findForPlayer(player.uniqueId)?.owner == session.account) &&
            !islands.underMaintenance(session.account) && hub.isHubWorld(player.world) && npc.world == player.world &&
            session.visible() && npc.distanceSquared(player.location) <= settings.distance * settings.distance
    }.getOrDefault(false)
    internal fun session(player: Player, touch: Boolean = false): Session? = sessions[player.uniqueId]?.takeIf { allowed(player, it) }?.also {
        if (touch) it.expires = System.currentTimeMillis() + settings.sessionSeconds * 1000
    }
    internal fun error(player: Player, e: Throwable) {
        val reason = (e as? MarketException)?.reason
        val key = when (reason) {
            "coins" -> Lang.TOO_POOR
            "inventory-full" -> Lang.MARKET_NO_SPACE
            "items" -> Lang.MARKET_NO_ITEMS
            "liquidity" -> Lang.MARKET_LIQUIDITY
            "stock", "stock-full" -> Lang.MARKET_STOCK
            "order-limit" -> Lang.MARKET_ORDER_LIMIT
            "busy" -> Lang.MARKET_BUSY
            "auction-ended" -> Lang.AUCTION_ENDED
            "auction-bid" -> Lang.AUCTION_BID_CHANGED
            "auction-bid-limit" -> Lang.AUCTION_BID_LIMIT
            "auction-bids" -> Lang.AUCTION_HAS_BIDS
            "auction-item" -> Lang.AUCTION_INVALID_ITEM
            "auction-access" -> Lang.AUCTION_WAIT
            "auction-self" -> Lang.AUCTION_SELF
            "buyback" -> Lang.MERCHANT_BUYBACK_EXPIRED
            else -> if (!storage.healthy) Lang.MARKET_UNAVAILABLE else Lang.MARKET_ERROR
        }
        locales.send(player, key)
    }
    internal fun operation(player: Player, action: MarketEngine.Transaction.() -> Unit, failed: (() -> Unit)? = null, done: (Player) -> Unit) {
        val session = session(player) ?: run { locales.send(player, Lang.MARKET_ACCESS); return }
        if (session.browseOnly) { failed?.invoke(); locales.send(player,Lang.ISLAND_NOT_FOUND); return }
        val token = InventoryGuard.acquireToken(player.uniqueId) ?: run { failed?.invoke(); locales.send(player, Lang.MARKET_BUSY); return }
        try {
            islands.marketUpdate(session.account) { island, state -> engine.transaction(session.account, player.uniqueId, island.coins, state, action) }
            storage.flushAsync().whenComplete { _, e ->
                InventoryGuard.release(player.uniqueId, token)
                FoliaTasks.entity(plugin, player) {
                    if (e != null) { failed?.invoke(); locales.send(player, Lang.MARKET_UNAVAILABLE) }
                    else {
                        if (session(player)?.id == session.id) done(player)
                        refreshAuctionMenus()
                    }
                }
            }
        } catch (e: Exception) { InventoryGuard.release(player.uniqueId, token); failed?.invoke(); error(player, e) }
    }
    internal fun menu(player: Player, title: Lang, view: String = title.name.lowercase().replace('_', '-'), context: String? = null): Holder? {
        val session = session(player) ?: return null
        val layout = settings.menus.window("${session.kind}-$view", context)
        return Holder(player.uniqueId, session.id, layout).apply {
            val heading = (layout.title ?: locales.text(player, title)).replace("{player}", player.name)
                .replace("{context}", context.orEmpty()).replace("{kind}", session.kind).lineSequence().first()
            contents = plugin.server.createInventory(this, layout.rows * 9, Colors.title(heading))
            com.alytanta.oneblock.menu.GuiItems.background(plugin,player,contents,layout,settings.appearance.filler,
                factory={material->icon(material,listOf(Colors.colorize(" ")))},styled={item,style->displayCopy(player,item,style,emptyMap())})
            button(player,this,1000,Material.BARRIER,Lang.MARKET_CLOSE) {it.closeInventory()}
        }
    }
    internal fun label(key: String): String = settings.products[key]?.displayName
        ?: key.lowercase().split('_').joinToString(" ") { it.replaceFirstChar(Char::uppercase) }

    internal fun show(player:Player,holder:Holder) {
        if(!holder.hasEntries())holder.layout.grids.entries.firstOrNull {it.key !in setOf("categories","separators") && it.value.isNotEmpty()}?.value?.firstOrNull()?.let {source->
            button(player,holder,source,Material.PAPER,Lang.MARKET_BOOK_EMPTY)
        }
        player.openInventory(holder.contents)
    }

    internal fun button(player: Player, holder: Holder, slot: Int, material: Material, text: Lang,
        vararg args: Pair<String, Any>, action: ((Player) -> Unit)? = null) {
        val role=if("empty" in text.key)"empty" else holder.layout.grids.entries.firstOrNull {slot in it.value}?.key?:"control"
        val base=holder.layout.buttons[slot]?:holder.layout.templates[role]
        val state=when {base?.states?.containsKey(text.key)==true->text.key;args.any {it.first=="selected" && it.second.toString().isNotBlank()}->"selected";action==null && role=="control"->"unavailable";else->"available"}
        val style=holder.layout.guiStyle(slot,role,state)
        if(role=="empty")holder.position(slot,style?.slot)
        holder.visible(slot,style?.enabled!=false)
        if (holder.target(slot) == null) return
        val lines=locales.text(player,text,*MarketText.arguments(args)).lines()
        val values=MarketText.arguments(args).associate {it.first to it.second.toString()}+mapOf("label" to lines.first(),"body" to lines.drop(1).joinToString("\n"))
        val item = displayCopy(player,icon(settings.appearance.icons[text.key] ?: material,lines.map(Colors::colorize)),style,values)
        holder.place(slot, item)
        holder.actions.set(slot, action?.let { callback -> { click -> callback(click.first) } })
    }

    internal fun styledLines(holder: Holder, slot: Int, original: List<String>, args: Array<out Pair<String, Any>>): List<String> {
        fun expand(text: String): String {
            var result = text
            MarketText.arguments(args).forEach { (key, value) -> result = result.replace("{$key}", value.toString()) }
            return result
        }
        val style = holder.layout.guiStyle(slot,if(holder.layout.grids["content"]?.contains(slot)==true)"content" else "preview")
        return listOf(style?.name?.let(::expand) ?: original.first()) + (style?.lore?.map(::expand) ?: original.drop(1))
    }

    internal fun navigation(player: Player, holder: Holder, back: (Player) -> Unit,
        refresh: ((Player) -> Unit)? = null, root: Boolean = false) {
        if (session(player)?.kind == "auction") {
            val view = holder.layout.id.substringBefore('/')
            val confirm = view == "auction-confirm"
            button(player, holder, 45, when { root -> Material.BARRIER; confirm -> Material.RED_TERRACOTTA; else -> Material.ARROW },
                when { root -> Lang.MARKET_CLOSE; confirm -> Lang.AUCTION_CANCEL_CONFIRM; else -> Lang.MARKET_NAV_BACK }, action = back)
            if (view == "auction-browse-mine") button(player, holder, 48, Material.GOLDEN_HORSE_ARMOR, Lang.AUCTION_CREATE) { auctionSelect(it) }
            if (view in setOf("auction-browse-mine", "auction-browse-bids"))
                button(player, holder, 50, Material.CHEST, Lang.MARKET_MY_CLAIMS) { claims(it) }
            return
        }
        button(player, holder, 45, if (root) Material.BARRIER else Material.ARROW,
            if (root) Lang.MARKET_CLOSE else Lang.MARKET_NAV_BACK, action = back)
        if (session(player)?.kind == "bazaar") {
            button(player, holder, 48, Material.BOOK, Lang.MARKET_MY_ORDERS) { orders(it) }
            button(player, holder, 50, Material.CHEST, Lang.MARKET_MY_CLAIMS) { claims(it) }
        }
        if (session(player)?.kind == "shop") {
            button(player, holder, 51, Material.CLOCK, Lang.MERCHANT_HISTORY) { merchantHistory(it) }
            button(player, holder, 50, Material.CHEST, Lang.MARKET_MY_CLAIMS) { claims(it) }
        }
        button(player, holder, 49, Material.GOLD_NUGGET, Lang.MARKET_BALANCE,
            "coins" to (session(player)?.account?.let(islands::findByOwner)?.coins ?: 0))
        if (refresh != null) button(player, holder, 53, Material.CLOCK, Lang.MARKET_REFRESH, action = refresh)
    }

    private fun bookText(player: Player, key: String, book: MarketBook): String {
        fun side(side: OrderSide): List<String> {
            val entries = book.orders(key, side)
            val lines = mutableListOf(locales.text(player, if (side == OrderSide.BUY) Lang.MARKET_BOOK_BUYS else Lang.MARKET_BOOK_SELLS,
                "orders" to entries.size, "amount" to entries.sumOf { it.remaining.toLong() }))
            if (entries.isEmpty()) lines += locales.text(player, Lang.MARKET_BOOK_EMPTY)
            entries.take(settings.ui.hoverOrders).forEach { order -> lines += locales.text(player, Lang.MARKET_BOOK_LINE,
                "amount" to order.remaining, "price" to order.price) }
            if (entries.size > settings.ui.hoverOrders) lines += locales.text(player, Lang.MARKET_BOOK_MORE,
                "orders" to (entries.size - settings.ui.hoverOrders))
            return lines
        }
        return (side(OrderSide.BUY) + "" + side(OrderSide.SELL)).joinToString("\n")
    }

    internal fun product(player: Player, holder: Holder, slot: Int, key: String, book: MarketBook) {
        val item = settings.products[key] ?: return
        val account = session(player)?.account
        button(player, holder, slot, item.icon ?: item.material, Lang.MARKET_PRODUCT_BOOK, "item" to label(key),
            "bid" to (book.best(key, OrderSide.BUY, account)?.toString() ?: "—"),
            "ask" to (book.best(key, OrderSide.SELL, account)?.toString() ?: "—"), "book" to bookText(player, key, book))
        catalogDescription(holder, slot, item.lore)
        holder.actions.set(slot) { (viewer, click) -> if (click == ClickType.RIGHT) orderBook(viewer, key) else detail(viewer, key) }
    }

    private fun categoryName(player: Player, category: MarketCategory): String = locales.text(player, when (category) {
        MarketCategory.FARMING -> Lang.MARKET_CATEGORY_FARMING
        MarketCategory.MINING -> Lang.MARKET_CATEGORY_MINING
        MarketCategory.COMBAT -> Lang.MARKET_CATEGORY_COMBAT
        MarketCategory.WOODS -> Lang.MARKET_CATEGORY_WOODS
        MarketCategory.FISHING -> Lang.MARKET_CATEGORY_FISHING
        MarketCategory.OTHER -> Lang.MARKET_CATEGORY_OTHER
    })

    internal fun returnCatalog(player: Player) {
        session(player)?.catalogReturn?.invoke(player) ?: main(player)
    }

    internal fun pageInfo(player: Player, holder: Holder, page: Int, size: Int, perPage: Int, slot: Int = 18) {
        button(player, holder, slot, Material.MAP, Lang.UI_PAGE, "page" to page + 1,
            "pages" to ((size - 1).coerceAtLeast(0) / perPage + 1), "count" to size)
    }

    internal fun main(player: Player, page: Int = 0, category: MarketCategory? = null, query: String = "", sort: Int = 0) {
        val active = session(player) ?: return
        if (active.kind == "auction") { auctionMain(player); return }
        bazaar(player, category?.name?.lowercase(), page, query, sort)
    }

    internal fun detail(player: Player, key: String) {
        val active = session(player) ?: return
        val item = settings.products[key] ?: return
        if (active.kind == "shop" && !item.shop || active.kind == "bazaar" && !item.bazaar) return
        val holder = menu(player, if (active.kind == "shop") Lang.MARKET_SHOP_TITLE else Lang.MARKET_TITLE, view = "product", context = key.lowercase()) ?: return
        if (active.kind == "shop") {
            button(player, holder, 13, item.material, Lang.MARKET_SHOP_DETAILS, "item" to label(key), "sell" to item.sellPrice)
            button(player, holder, 31, Material.GOLD_INGOT, Lang.MARKET_MERCHANT_SELL) { amountMenu(it, key, Action.MERCHANT_SELL) }
            button(player, holder, 48, Material.HOPPER, Lang.MERCHANT_SELL_PRODUCT, "item" to label(key)) { merchantSellAll(it, key) }
        } else {
            product(player, holder, 13, key, MarketBook(storage.marketState()))
            // Product overview is information / right-click book; it never disables the four actions.
            holder.actions.set(13) { (viewer, _) -> orderBook(viewer, key) }
            button(player, holder, 10, Material.GOLDEN_HORSE_ARMOR, Lang.MARKET_INSTANT_BUY) { amountMenu(it, key, Action.INSTANT_BUY) }
            button(player, holder, 16, Material.HOPPER, Lang.MARKET_INSTANT_SELL) { amountMenu(it, key, Action.INSTANT_SELL) }
            button(player, holder, 28, Material.PAPER, Lang.MARKET_BUY_ORDER) { amountMenu(it, key, Action.BUY_ORDER) }
            button(player, holder, 34, Material.MAP, Lang.MARKET_SELL_ORDER) { amountMenu(it, key, Action.SELL_ORDER) }
            button(player, holder, 31, Material.BOOK, Lang.MARKET_BOOK_TITLE) { orderBook(it, key) }
        }
        navigation(player, holder, { returnCatalog(it) }, { detail(it, key) })
        show(player,holder)
    }

    private enum class Action(val side: OrderSide, val order: Boolean) {
        INSTANT_BUY(OrderSide.BUY, false), INSTANT_SELL(OrderSide.SELL, false),
        BUY_ORDER(OrderSide.BUY, true), SELL_ORDER(OrderSide.SELL, true), MERCHANT_SELL(OrderSide.SELL, false)
    }

    private fun ownedAmount(player: Player, key: String): Int = player.inventory.storageContents.withIndex()
        .filter { it.index != com.alytanta.oneblock.menu.MenuInventory.SLOT }
        .mapNotNull { it.value }.filter { sameProduct(key, it) }.sumOf { it.amount }
    private fun sameProduct(key: String, stack: ItemStack) = if (com.alytanta.oneblock.items.CustomProducts.custom(key)) com.alytanta.oneblock.items.CustomProducts.matches(key, stack)
        else stack.type.name == key && !stack.hasItemMeta()

    private fun input(player: Player, key: String, isPrice: Boolean, value: Long, done: (Player, Long?) -> Unit) {
        val active = session(player) ?: return
        val item = settings.products[key] ?: return
        val request = Prompt(active.id)
        prompts[player.uniqueId] = request
        val min = if (isPrice) item.minOrderPrice else 1L
        val max = if (isPrice) item.maxOrderPrice else settings.maxQuantity.toLong()
        val label = locales.text(player, if (isPrice) Lang.MARKET_SIGN_PRICE_SHORT else Lang.MARKET_SIGN_AMOUNT_SHORT)
        val templates = if (isPrice) settings.ui.priceLines else settings.ui.amountLines
        val lines = templates.map { Colors.colorize(it.replace("{value}", value.toString()).replace("{label}", label)
            .replace("{min}", min.toString()).replace("{max}", max.toString())) }
        try {
            signInput.openLines(player, lines, settings.ui.inputLine, settings.inputSeconds * 20) { text ->
                if (!prompts.remove(player.uniqueId, request)) return@openLines
                if (session(player, touch = true)?.id != active.id) { locales.send(player, Lang.MARKET_ACCESS); return@openLines }
                val number = VirtualSignInput.number(text, min, max)
                if (text != null && number == null) locales.send(player, Lang.MARKET_INPUT_INVALID, "min" to min, "max" to max)
                done(player, number)
            }
        } catch (e: Exception) {
            prompts.remove(player.uniqueId, request)
            plugin.logger.log(java.util.logging.Level.WARNING, "Could not open market sign input", e)
            locales.send(player, Lang.MARKET_ERROR); done(player, null)
        }
    }

    internal fun textInput(player: Player, value: String, label: Lang, done: (Player, String?) -> Unit) {
        val active = session(player) ?: return
        val request = Prompt(active.id)
        prompts[player.uniqueId] = request
        val lines = settings.ui.priceLines.map { Colors.colorize(it.replace("{value}", value).replace("{label}", locales.text(player, label))
            .replace("{min}", settings.auction.minPrice.toString()).replace("{max}", settings.auction.maxPrice.toString())) }
        try {
            signInput.openLines(player, lines, settings.ui.inputLine, settings.inputSeconds * 20) { text ->
                if (!prompts.remove(player.uniqueId, request)) return@openLines
                if (session(player, touch = true)?.id != active.id) return@openLines
                done(player, text)
            }
        } catch (e: Exception) {
            prompts.remove(player.uniqueId, request)
            plugin.logger.log(java.util.logging.Level.WARNING, "Could not open market input", e)
            locales.send(player, Lang.MARKET_ERROR); done(player, null)
        }
    }

    private fun amountMenu(player: Player, key: String, action: Action, selected: Int = 1, price: Long? = null, page:Int=0) {
        val active = session(player) ?: return
        val item = settings.products[key] ?: return
        if ((action == Action.MERCHANT_SELL) != (active.kind == "shop")) return
        val holder = menu(player, Lang.MARKET_AMOUNT_TITLE, view = "amount-${action.name.lowercase().replace('_', '-')}", context = key.lowercase()) ?: return
        button(player, holder, 4, item.material, Lang.MARKET_AMOUNT_INFO, "item" to label(key), "amount" to selected,
            "max" to settings.maxQuantity, "owned" to ownedAmount(player, key))
        fun choose(viewer: Player, amount: Int) {
            if (action.order && price != null) orderEditor(viewer, key, action.side, amount, price)
            else if (action.order) priceMenu(viewer, key, action.side, amount)
            else instantConfirm(viewer, key, action, amount)
        }
        val choices=holder.grid((19..24).toList(),"quantities")
        val current=page.coerceIn(0,(settings.quantities.size-1).coerceAtLeast(0)/choices.size)
        settings.quantities.drop(current*choices.size).take(choices.size).forEachIndexed { index, amount ->
            val quote = if (!action.order && action != Action.MERCHANT_SELL)
                engine.quote(storage.marketState(), active.account, key, action.side, amount) else null
            val total = quote?.takeIf { it.quantity == amount }?.let { if (action.side == OrderSide.BUY) it.gross else it.net }
            button(player, holder, choices[index], Material.PAPER,
                if (quote == null) Lang.MARKET_QUANTITY else Lang.UI_TRADE_QUANTITY, "amount" to amount,
                "total" to (total?.let(MarketText::number) ?: "—")) { choose(it, amount) }
        }
        if (action == Action.INSTANT_BUY) {
            val stackLimit = minOf(plainStack(item.material, 1).maxStackSize, player.inventory.maxStackSize)
            val capacity = player.inventory.storageContents.withIndex().sumOf { (slot, stack) ->
                when {
                    slot == com.alytanta.oneblock.menu.MenuInventory.SLOT -> 0
                    stack == null || stack.isEmpty -> stackLimit
                    sameProduct(key, stack) -> (minOf(stackLimit, stack.maxStackSize) - stack.amount).coerceAtLeast(0)
                    else -> 0
                }
            }.coerceAtMost(settings.maxQuantity)
            val available = if (capacity == 0) 0 else engine.quote(storage.marketState(), active.account, key, action.side, capacity).quantity
            button(player, holder, 31, Material.CHEST, Lang.UI_FILL_INVENTORY, "amount" to available,
                action = if (available > 0) ({ viewer -> choose(viewer, available) }) else null)
        }
        button(player, holder, 25, Material.OAK_SIGN, Lang.MARKET_CUSTOM_AMOUNT) { viewer ->
            input(viewer, key, false, selected.toLong()) { p, number ->
                if (number == null) amountMenu(p, key, action, selected, price) else choose(p, number.toInt())
            }
        }
        if (action.side == OrderSide.SELL) {
            val all = if (action == Action.MERCHANT_SELL) ownedAmount(player, key) else minOf(ownedAmount(player, key), settings.maxQuantity)
            button(player, holder, 31, Material.CHEST, Lang.MARKET_SELL_AVAILABLE, "amount" to all,
                action = if (all > 0) ({ p -> choose(p, all) }) else null)
        }
        navigation(player, holder, { detail(it, key) }, { amountMenu(it, key, action, selected, price) })
        if(current>0)button(player,holder,46,Material.ARROW,Lang.MENU_PREVIOUS) {amountMenu(it,key,action,selected,price,current-1)}
        if((current+1)*choices.size<settings.quantities.size)button(player,holder,52,Material.ARROW,Lang.MENU_NEXT) {amountMenu(it,key,action,selected,price,current+1)}
        show(player,holder)
    }

    private fun instantConfirm(player: Player, key: String, action: Action, amount: Int) {
        val active = session(player) ?: return
        val item = settings.products[key] ?: return
        val total = if (action == Action.MERCHANT_SELL) Math.multiplyExact(item.sellPrice, amount.toLong()) else {
            val quote = engine.quote(storage.marketState(), active.account, key, action.side, amount)
            if (quote.quantity != amount) {
                storage.audit(com.alytanta.oneblock.storage.OperationLog(operation = action.name, actor = player.uniqueId, account = active.account,
                    success = false, status = "FAILED", reason = "liquidity", details = mapOf("product" to key, "quantity" to amount)))
                locales.send(player, Lang.MARKET_LIQUIDITY); return
            }
            if (action.side == OrderSide.BUY) quote.gross else quote.net
        }
        val title = if (action.side == OrderSide.BUY) Lang.MARKET_BUY else Lang.MARKET_SELL
        confirm(player, title, amount, total, { amountMenu(it, key, action, amount) }) { viewer ->
            if (action == Action.INSTANT_BUY) operation(viewer, { instantBuy(key, amount, total) }) { p -> locales.send(p, Lang.MARKET_DONE); claims(p) }
            else {
                val session = session(viewer) ?: return@confirm
                handoffs.transfer(viewer, session.account, key, amount,
                    if (action == Action.MERCHANT_SELL) TransferKind.SHOP_SELL else TransferKind.INSTANT_SELL,
                    price = if (action == Action.MERCHANT_SELL) item.sellPrice else 0, minimumProceeds = total) {
                    if (session(viewer) != null) { if (action == Action.MERCHANT_SELL) detail(viewer, key) else claims(viewer) }
                }
            }
        }
    }

    private fun priceMenu(player: Player, key: String, side: OrderSide, amount: Int, previous: Long? = null) {
        if (session(player)?.kind != "bazaar") return
        val item = settings.products[key] ?: return
        val holder = menu(player, Lang.MARKET_PRICE_TITLE, view = "price-${side.name.lowercase()}", context = key.lowercase()) ?: return
        val book = MarketBook(storage.marketState())
        product(player, holder, 4, key, book)
        button(player, holder, 13, item.material, Lang.MARKET_AMOUNT_INFO, "item" to label(key), "amount" to amount,
            "max" to settings.maxQuantity, "owned" to ownedAmount(player, key))
        val tick = settings.priceSteps.first()
        val spread = if (side == OrderSide.BUY) settings.ui.buySpreadBps else settings.ui.sellSpreadBps
        listOf(MarketBook.Suggestion.SAME, MarketBook.Suggestion.COMPETITIVE, MarketBook.Suggestion.SPREAD).forEachIndexed { index, suggestion ->
            val suggested = book.suggest(item, side, suggestion, tick, spread)
            val text = when (suggestion) { MarketBook.Suggestion.SAME -> Lang.MARKET_PRICE_SAME; MarketBook.Suggestion.COMPETITIVE -> Lang.MARKET_PRICE_COMPETITIVE; else -> Lang.MARKET_PRICE_SPREAD }
            button(player, holder, 19 + index * 2, Material.GOLD_NUGGET, text, "price" to (suggested?.toString() ?: "—"),
                "step" to (if (side == OrderSide.BUY) "+$tick" else "-$tick"), "percent" to (spread / 100.0),
                action = suggested?.let { selected -> { p -> orderEditor(p, key, side, amount, selected) } })
        }
        button(player, holder, 25, Material.OAK_SIGN, Lang.MARKET_CUSTOM_PRICE, "min" to item.minOrderPrice, "max" to item.maxOrderPrice) { viewer ->
            val start = previous ?: book.best(key, side) ?: item.sellPrice.coerceIn(item.minOrderPrice, item.maxOrderPrice)
            input(viewer, key, true, start) { p, value ->
                if (value == null) priceMenu(p, key, side, amount, previous) else orderEditor(p, key, side, amount, value)
            }
        }
        navigation(player, holder, { amountMenu(it, key, if (side == OrderSide.BUY) Action.BUY_ORDER else Action.SELL_ORDER, amount, previous) },
            { priceMenu(it, key, side, amount, previous) })
        show(player,holder)
    }

    private fun orderEditor(player: Player, key: String, side: OrderSide, amount: Int, price: Long) {
        if (session(player)?.kind != "bazaar") return
        val item = settings.products[key] ?: return
        val holder = menu(player, Lang.MARKET_ORDER_TITLE, view = "order-${side.name.lowercase()}", context = key.lowercase()) ?: return
        val gross = Math.multiplyExact(price, amount.toLong())
        button(player, holder, 13, item.material, Lang.UI_ORDER_SUMMARY, "item" to label(key), "amount" to amount,
            "action" to locales.text(player, if (side == OrderSide.BUY) Lang.MARKET_BUY_ORDER else Lang.MARKET_SELL_ORDER),
            "price" to price, "total" to gross, "tax" to if (side == OrderSide.SELL) settings.sellTaxBps / 100.0 else 0.0,
            "net" to if (side == OrderSide.SELL) gross - engine.tax(gross) else gross)
        button(player, holder, 19, Material.PAPER, Lang.MARKET_CUSTOM_AMOUNT) {
            amountMenu(it, key, if (side == OrderSide.BUY) Action.BUY_ORDER else Action.SELL_ORDER, amount, price)
        }
        button(player, holder, 25, Material.OAK_SIGN, Lang.MARKET_CUSTOM_PRICE, "min" to item.minOrderPrice, "max" to item.maxOrderPrice) { priceMenu(it, key, side, amount, price) }
        button(player, holder, 31, Material.LIME_CONCRETE, Lang.MARKET_CONFIRM) { viewer ->
            if (holder.submitted || session(viewer) == null) return@button
            holder.submitted = true
            if (side == OrderSide.BUY) operation(viewer, { buyOrder(key, amount, price) }, { holder.submitted = false }) { orders(it) }
            else {
                val active = session(viewer) ?: return@button
                handoffs.transfer(viewer, active.account, key, amount, TransferKind.SELL_ORDER, price) {
                    if (session(viewer) != null) orders(viewer)
                }
                if (!InventoryGuard.busy(viewer.uniqueId)) holder.submitted = false
            }
        }
        navigation(player, holder, { priceMenu(it, key, side, amount, price) })
        show(player,holder)
    }

    private fun confirm(player: Player, action: Lang, amount: Int, price: Long, back: (Player) -> Unit,
        unitPrice: Boolean = false, execute: (Player) -> Unit) {
        val holder = menu(player, Lang.MARKET_CONFIRM_TITLE, view = "confirm", context = action.name.lowercase().replace('_', '-')) ?: return
        val text = if (action == Lang.MARKET_CANCEL) Lang.MARKET_CANCEL_CONFIRM_DETAILS
            else if (session(player)?.kind == "shop") Lang.MARKET_SHOP_CONFIRM_DETAILS
            else if (unitPrice) Lang.MARKET_ORDER_CONFIRM_DETAILS else Lang.MARKET_INSTANT_CONFIRM_DETAILS
        button(player, holder, 13, Material.PAPER, text, "action" to locales.text(player, action, "amount" to amount),
            "amount" to amount, "price" to price, "total" to if (unitPrice) Math.multiplyExact(price, amount.toLong()) else price)
        button(player, holder, 31, Material.LIME_CONCRETE, Lang.MARKET_CONFIRM) {
            if (session(it) != null && !holder.submitted) {
                holder.submitted = true; execute(it)
                if (!InventoryGuard.busy(it.uniqueId) && it.openInventory.topInventory === holder.contents) holder.submitted = false
            }
        }
        navigation(player, holder, back)
        show(player,holder)
    }

    private fun orderBook(player: Player, key: String, page: Int = 0) {
        if (session(player)?.kind != "bazaar") return
        val holder = menu(player, Lang.MARKET_BOOK_TITLE, view = "book", context = key.lowercase()) ?: return
        val book = MarketBook(storage.marketState())
        val buys = book.orders(key, OrderSide.BUY); val sells = book.orders(key, OrderSide.SELL)
        val grids = listOf(holder.grid((0 until 16).map { 9 + it / 4 * 9 + it % 4 }, "buys"),
            holder.grid((0 until 16).map { 14 + it / 4 * 9 + it % 4 }, "sells"))
        val last = maxOf((buys.size - 1).coerceAtLeast(0) / grids[0].size, (sells.size - 1).coerceAtLeast(0) / grids[1].size)
        val current = page.coerceIn(0, last)
        button(player, holder, 1, Material.EMERALD, Lang.MARKET_BOOK_BUYS, "orders" to buys.size, "amount" to buys.sumOf { it.remaining.toLong() })
        button(player, holder, 7, Material.GOLD_INGOT, Lang.MARKET_BOOK_SELLS, "orders" to sells.size, "amount" to sells.sumOf { it.remaining.toLong() })
        settings.products[key]?.let { product(player, holder, 4, key, book); holder.actions.set(4, null) }
        listOf(buys, sells).forEachIndexed { side, entries ->
            entries.drop(current * grids[side].size).take(grids[side].size).forEachIndexed { index, order ->
                val slot = grids[side][index]
                val mine = order.account == session(player)?.account
                button(player, holder, slot, if (side == 0) Material.EMERALD else Material.GOLD_INGOT, Lang.MARKET_BOOK_ORDER,
                    "item" to label(key), "price" to order.price, "amount" to order.remaining,
                    "filled" to order.quantity - order.remaining, "quantity" to order.quantity,
                    "mine" to if (mine) locales.text(player, Lang.MARKET_YOUR_ORDER) else "",
                    action = if (mine) ({ p -> orderView(p, order.id) }) else null)
            }
        }
        navigation(player, holder, { detail(it, key) }, { orderBook(it, key, current) })
        if (current > 0) button(player, holder, 46, Material.ARROW, Lang.MENU_PREVIOUS) { orderBook(it, key, current - 1) }
        if (current < last) button(player, holder, 52, Material.ARROW, Lang.MENU_NEXT) { orderBook(it, key, current + 1) }
        show(player,holder)
    }

    private fun status(player: Player, order: MarketOrder): String = locales.text(player, when {
        order.status == OrderStatus.FILLED -> Lang.MARKET_STATUS_FILLED
        order.status == OrderStatus.CANCELLED -> Lang.MARKET_STATUS_CANCELLED
        order.status == OrderStatus.EXPIRED || order.expires <= System.currentTimeMillis() -> Lang.MARKET_STATUS_EXPIRED
        else -> Lang.MARKET_STATUS_OPEN
    })

    private fun orderButton(player: Player, holder: Holder, slot: Int, order: MarketOrder) {
        button(player, holder, slot, Material.matchMaterial(order.product) ?: Material.PAPER, Lang.MARKET_MANAGED_ORDER,
            "item" to label(order.product), "side" to locales.text(player, if (order.side == OrderSide.BUY) Lang.MARKET_BUY_ORDER else Lang.MARKET_SELL_ORDER),
            "amount" to order.remaining, "price" to order.price,
            "filled" to if (order.status in setOf(OrderStatus.OPEN, OrderStatus.FILLED)) (order.quantity - order.remaining).toString() else "—",
            "quantity" to order.quantity, "status" to status(player, order)) { orderView(it, order.id) }
    }

    private fun orders(player: Player, page: Int = 0) {
        val active = session(player) ?: return
        if (active.kind != "bazaar") return
        val holder = menu(player, Lang.MARKET_MY_ORDERS, view = "orders") ?: return
        val entries = storage.marketState().orders.values.filter { it.account == active.account }.sortedWith(
            compareBy<MarketOrder> { if (it.status == OrderStatus.OPEN) 0 else 1 }.thenByDescending { it.sequence })
        val grid = holder.grid((0..44).toList())
        val current = page.coerceIn(0, (entries.size - 1).coerceAtLeast(0) / grid.size)
        if (entries.isEmpty()) button(player, holder, 22, Material.PAPER, Lang.UI_EMPTY_ORDERS)
        entries.drop(current * grid.size).take(grid.size).forEachIndexed { index, order -> orderButton(player, holder, grid[index], order) }
        navigation(player, holder, { main(it) }, { orders(it, current) })
        if (current > 0) button(player, holder, 46, Material.ARROW, Lang.MENU_PREVIOUS) { orders(it, current - 1) }
        if ((current + 1) * grid.size < entries.size) button(player, holder, 52, Material.ARROW, Lang.MENU_NEXT) { orders(it, current + 1) }
        show(player,holder)
    }

    private fun orderView(player: Player, id: UUID) {
        val active = session(player) ?: return
        val order = storage.marketState().orders[id]?.takeIf { it.account == active.account } ?: run { orders(player); return }
        val holder = menu(player, Lang.MARKET_MY_ORDERS, view = "order", context = order.product.lowercase()) ?: return
        orderButton(player, holder, 13, order); holder.actions.set(13, null)
        button(player, holder, 20, Material.CHEST, Lang.MARKET_MY_CLAIMS) { claims(it) }
        if (order.status == OrderStatus.OPEN) button(player, holder, 24, Material.RED_CONCRETE, Lang.MARKET_CANCEL) { viewer ->
            confirm(viewer, Lang.MARKET_CANCEL, order.remaining, order.price, { orderView(it, id) }, unitPrice = true) {
                operation(it, { cancel(id) }) { p -> claims(p) }
            }
        }
        navigation(player, holder, { orders(it) }, { orderView(it, id) })
        show(player,holder)
    }

    internal fun claims(player: Player, page: Int = 0) {
        val active = session(player) ?: return
        if (active.kind == "auction") { auctionClaims(player, page); return }
        if (active.kind !in setOf("bazaar", "auction", "shop")) return
        val holder = menu(player, Lang.MARKET_MY_CLAIMS, view = "claims") ?: return
        val state = storage.marketState()
        val entries = state.claims.values.filter { it.account == active.account && (it.recipient == null || it.recipient == player.uniqueId) }
        val coins = entries.filter { it.product == null }.fold(0L) { total, claim -> Math.addExact(total, claim.amount) }
        if (entries.isEmpty()) button(player, holder, 22, Material.CHEST, Lang.UI_EMPTY_CLAIMS)
        button(player, holder, 47, Material.GOLD_INGOT, Lang.UI_COLLECT_COINS, "coins" to coins,
            action = if (coins > 0) ({ viewer -> operation(viewer, { collectAllCoins() }) { claims(it) } }) else null)
        val grid = holder.grid((0..44).toList())
        val current = page.coerceIn(0, (entries.size - 1).coerceAtLeast(0) / grid.size)
        entries.drop(current * grid.size).take(grid.size).forEachIndexed { index, claim ->
            button(player, holder, grid[index], claim.product?.let(Material::matchMaterial) ?: Material.GOLD_INGOT, Lang.MARKET_CLAIM,
                "item" to (claim.product?.let(::label) ?: locales.text(player, Lang.MARKET_COINS)), "amount" to claim.amount) { viewer ->
                if (claim.product == null) operation(viewer, { collectCoins(claim.id) }) { claims(it, current) }
                else claimOptions(viewer, claim)
            }
            claim.itemData?.let { data -> auctionPreview(player, holder, grid[index], data, Lang.MARKET_CLAIM, "item" to label(claim.product!!), "amount" to claim.amount) }
        }
        navigation(player, holder, { main(it) }, { claims(it, current) })
        if (current > 0) button(player, holder, 46, Material.ARROW, Lang.MENU_PREVIOUS) { claims(it, current - 1) }
        if ((current + 1) * grid.size < entries.size) button(player, holder, 52, Material.ARROW, Lang.MENU_NEXT) { claims(it, current + 1) }
        if (active.kind == "auction") watchAuctions(holder, state) { claims(it, current) }
        show(player,holder)
    }

    internal fun collectAuctionItem(player: Player, claim: MarketClaim, done: (Player) -> Unit) {
        val active = session(player) ?: return
        val quantity = if (claim.itemData != null) Math.toIntExact(claim.amount) else minOf(claim.amount, settings.maxQuantity.toLong()).toInt()
        handoffs.transfer(player, active.account, requireNotNull(claim.product), quantity, TransferKind.WITHDRAW, claimId = claim.id) {
            if (session(player) != null) done(player)
        }
    }
    private fun claimOptions(player: Player, claim: MarketClaim,page:Int=0) {
        val holder = menu(player, Lang.MARKET_MY_CLAIMS, view = "claim", context = claim.product?.lowercase()) ?: return
        button(player,holder,13,Material.CHEST,Lang.MARKET_CLAIM,"item" to (claim.product?.let(::label).orEmpty()),"amount" to claim.amount)
        val amounts = if (claim.itemData != null) listOf(claim.amount.toInt()) else
            (settings.quantities + minOf(claim.amount, settings.maxQuantity.toLong()).toInt()).filter { it <= claim.amount }.distinct()
        val choices=holder.grid((19..24).toList(),"quantities")
        val current=page.coerceIn(0,(amounts.size-1).coerceAtLeast(0)/choices.size)
        amounts.drop(current*choices.size).take(choices.size).forEachIndexed { index, amount ->
            button(player, holder, choices[index], Material.CHEST, Lang.MARKET_CLAIM, "item" to (claim.product?.let(::label).orEmpty()), "amount" to amount) { viewer ->
                val active = session(viewer) ?: return@button
                handoffs.transfer(viewer, active.account, requireNotNull(claim.product), amount, TransferKind.WITHDRAW, claimId = claim.id) {
                    if (session(viewer) != null) claims(viewer)
                }
            }
        }
        if (claim.itemData == null && session(player)?.kind == "bazaar" && settings.products[claim.product]?.bazaar == true) {
            val product = settings.products.getValue(claim.product!!)
            button(player, holder, 31, Material.GOLD_INGOT, Lang.MARKET_FLIP) { viewer ->
                textInput(viewer, product.sellPrice.toString(), Lang.MARKET_SIGN_PRICE_SHORT) { p, value ->
                    val price = VirtualSignInput.number(value, product.minOrderPrice, product.maxOrderPrice)
                    if (price == null) { if (value != null) locales.send(p, Lang.MARKET_INPUT_INVALID, "min" to product.minOrderPrice, "max" to product.maxOrderPrice); claimOptions(p, claim) }
                    else {
                        val count = minOf(claim.amount, settings.maxQuantity.toLong()).toInt()
                        confirm(p, Lang.MARKET_SELL_ORDER, count, price, { claimOptions(it, claim) }, unitPrice = true) {
                            operation(it, { flipClaim(claim.id, count, price) }) { viewer -> orders(viewer) }
                        }
                    }
                }
            }
        }
        navigation(player, holder, { claims(it) })
        if(current>0)button(player,holder,46,Material.ARROW,Lang.MENU_PREVIOUS) {claimOptions(it,claim,current-1)}
        if((current+1)*choices.size<amounts.size)button(player,holder,52,Material.ARROW,Lang.MENU_NEXT) {claimOptions(it,claim,current+1)}
        show(player,holder)
    }


    @EventHandler fun onClick(event: InventoryClickEvent) {
        val holder = event.view.topInventory.holder as? Holder ?: return
        event.isCancelled = true
        val player = event.whoClicked as? Player ?: return
        if (holder.viewer != player.uniqueId || InventoryGuard.busy(player.uniqueId)) return
        if (session(player, touch = true)?.id != holder.session) { player.closeInventory(); locales.send(player, Lang.MARKET_ACCESS); return }
        if (event.click !in setOf(ClickType.LEFT, ClickType.RIGHT)) return
        val select = holder.auctionItemClick
        val action = if (select != null && event.clickedInventory === player.inventory && event.slot in 0..35 && event.slot != com.alytanta.oneblock.menu.MenuInventory.SLOT) {
            val slot = event.slot
            val item = player.inventory.getItem(slot)?.takeUnless { it.isEmpty }?.clone() ?: return
            holder.actions.acquireExternal { (viewer, _) ->
                if (viewer.inventory.getItem(slot) == item) select(viewer, slot, item)
                else locales.send(viewer, Lang.MARKET_NO_ITEMS)
            }
        } else holder.actions.acquire(event.rawSlot)
        if (action == null) return
        val click = event.click
        FoliaTasks.entity(plugin, player, retired = holder.actions::release) {
            try {
                if (player.openInventory.topInventory === holder.contents && session(player)?.id == holder.session) action(player to click)
            } catch (e: Exception) {
                if (e !is MarketException) plugin.logger.log(java.util.logging.Level.WARNING, "Market menu action failed", e)
                error(player, e)
            } finally { holder.actions.release() }
        }
    }
    @EventHandler fun onDrag(event: InventoryDragEvent) { if (event.view.topInventory.holder is Holder) event.isCancelled = true }
    fun recoverPlayer(player: Player) { releaseRecovery?.invoke(player);handoffs.recover(player) }
    @EventHandler fun onJoin(event: PlayerJoinEvent) { FoliaTasks.entity(plugin, event.player) { recoverPlayer(event.player) } }
    @EventHandler fun onQuit(event: PlayerQuitEvent) { sessions.remove(event.player.uniqueId); prompts.remove(event.player.uniqueId); signInput.forget(event.player) }
    @EventHandler fun onDeath(event: org.bukkit.event.entity.PlayerDeathEvent) { sessions.remove(event.player.uniqueId); prompts.remove(event.player.uniqueId); signInput.cancel(event.player) }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true) fun onTeleport(event: PlayerTeleportEvent) { sessions.remove(event.player.uniqueId); prompts.remove(event.player.uniqueId); signInput.cancel(event.player) }
    @EventHandler fun onMove(event: PlayerMoveEvent) {
        val current = sessions[event.player.uniqueId] ?: return
        if (!allowed(event.player, current)) {
            sessions.remove(event.player.uniqueId, current)
            prompts.remove(event.player.uniqueId)
            signInput.cancel(event.player)
            FoliaTasks.entity(plugin, event.player) { if (event.player.openInventory.topInventory.holder is Holder) event.player.closeInventory() }
        }
    }
    @EventHandler fun onClose(event: InventoryCloseEvent) {
        val player = event.player as? Player ?: return
        if (event.inventory.holder !is Holder) return
        FoliaTasks.entity(plugin, player) {
            if (player.openInventory.topInventory.holder !is Holder && !InventoryGuard.busy(player.uniqueId) && !prompts.containsKey(player.uniqueId)) sessions.remove(player.uniqueId)
        }
    }

    /** Administration only; no command can open a trade UI. Recovery is console-only and explicitly confirmed. */
    var reloadConfiguration: ((CommandSender) -> Unit)? = null
    /** Role configuration and FancyNpcs placement are owned by OneBlock; this hook accepts only explicit admin placement. */
    var npcAdministration: ((CommandSender,String) -> Unit)? = null

    fun applySettings(next: MarketSettings) {
        engine = ledger(next)
        sessions.clear()
        prompts.clear()
        signInput.cancelAll()
        plugin.server.onlinePlayers.forEach { player -> FoliaTasks.entity(plugin, player) {
            if (player.openInventory.topInventory.holder is Holder) player.closeInventory()
        } }
    }

    fun admin(sender: CommandSender, args: List<String>) {
        if (!sender.isOp || !sender.hasPermission("oneblock.admin") || !sender.hasPermission("oneblock.admin.manage")) { locales.send(sender, Lang.NO_PERMISSION); return }
        when (args.firstOrNull()?.lowercase()) {
            "npc" -> {
                val kind = args.getOrNull(1)?.lowercase()
                if (sender !is Player || kind !in setOf("shop","bazaar","auction","guide","dungeon","guild") || args.size != 2) {
                    sender.sendMessage("/obadmin market npc <shop|bazaar|auction|guide|dungeon|guild>"); return
                }
                npcAdministration?.let {it(sender,kind!!);return}
                val npcName=when(kind) {"shop"->settings.shopNpc;"bazaar"->settings.bazaarNpc;"auction"->settings.auction.npc;else->null}
                if(npcName==null){sender.sendMessage("NPC role configuration is unavailable");return}
                FoliaTasks.entity(plugin, sender) {
                    if (!hub.isHubWorld(sender.world) || !plugin.server.pluginManager.isPluginEnabled("FancyNpcs")) {
                        locales.send(sender, Lang.MARKET_ACCESS); return@entity
                    }
                    FancyMarketNpc.create(plugin, storage, sender, npcName, locales)
                }
            }
            "reload" -> {
                reloadConfiguration?.invoke(sender)
            }
            "review" -> sender.sendMessage(storage.marketState().transfers.values.filter { it.status in setOf(TransferStatus.PREPARED, TransferStatus.REVIEW) }
                .joinToString("\n") { "${it.id} ${storage.nameLookup(it.actor) ?: "Unknown player"} ${it.kind} ${it.product} ${it.quantity} ${it.status}" }.ifEmpty { "No unresolved handoffs" })
            "resolve" -> {
                if (sender is Player || args.size != 4 || args[3] != "CONFIRM" || args[2] !in setOf("applied", "abort")) { locales.send(sender, Lang.ADMIN_INVALID); return }
                val id = runCatching { UUID.fromString(args[1]) }.getOrNull()
                val transfer = id?.let { storage.marketState().transfers[it] } ?: run { locales.send(sender, Lang.ADMIN_INVALID); return }
                if(transfer.kind==TransferKind.RELEASE) { releaseResolution?.invoke(sender,transfer,args[2]=="applied");return }
                FoliaTasks.async(plugin) {
                    try {
                        storage.backup()
                        islands.marketUpdate(transfer.account) { island, state -> engine.transaction(transfer.account, transfer.actor, island.coins, state) {
                            if (args[2] == "applied") finish(transfer.id) else abort(transfer.id)
                        } }
                        check(storage.flush()); plugin.logger.info("[ADMIN] ${sender.name}: market resolve $id ${args[2]}")
                    } catch (_: Exception) { plugin.logger.severe("Market recovery failed for $id; retain backups") }
                }
            }
            else -> sender.sendMessage("/obadmin market npc <shop|bazaar|auction|guide|dungeon|guild> | reload | review | resolve <transfer-UUID> applied|abort CONFIRM")
        }
    }
}
