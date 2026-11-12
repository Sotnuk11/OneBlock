package com.alytanta.oneblock.market

import com.alytanta.oneblock.locale.Lang
import com.alytanta.oneblock.menu.MenuInventory
import com.alytanta.oneblock.menu.guiStyle
import com.alytanta.oneblock.util.Colors
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataType
import java.util.UUID

internal enum class AuctionScope { ALL, MINE, BIDS }
internal enum class AuctionSort { LOWEST, HIGHEST, ENDING, MOST_BIDS, NEWEST }

internal fun MarketController.auctionPreview(player: Player, holder: MarketController.Holder, slot: Int,
    data: String, text: Lang, vararg args: Pair<String, Any>) {
    // Preview copies retain the item's actual metadata; these slots are always cancelled by onClick/onDrag.
    val style=holder.layout.guiStyle(slot,if(holder.layout.grids["content"]?.contains(slot)==true)"content" else "preview")
    holder.visible(slot,style?.enabled!=false)
    if (holder.target(slot) == null) return
    val item = auctionRenderer(data,
        listOf(Colors.colorize(" ")) + locales.text(player, text, *MarketText.arguments(args)).lines().map(Colors::colorize))
    holder.place(slot,displayCopy(player,item,style,MarketText.arguments(args).associate {it.first to it.second.toString()}))
}

internal fun MarketController.auctionMain(player: Player) {
    if (session(player)?.kind != "auction") return
    val holder = menu(player, Lang.AUCTION_TITLE, view = "main") ?: return
    val active = session(player) ?: return
    val state = storage.marketState()
    val now = System.currentTimeMillis()
    button(player, holder, 4, Material.MAP, Lang.UI_AUCTION_SUMMARY,
        "lots" to state.auctions.values.count { it.status == AuctionStatus.OPEN && it.expires > now },
        "mine" to state.auctions.values.count { it.account == active.account && it.status == AuctionStatus.OPEN && it.expires > now },
        "claims" to state.claims.values.count { it.account == active.account })
    button(player, holder, 11, Material.GOLD_BLOCK, Lang.AUCTION_BROWSE) { auctionBrowse(it) }
    button(player, holder, 13, Material.GOLDEN_CARROT, Lang.AUCTION_MY_BIDS) { auctionBrowse(it, scope = AuctionScope.BIDS) }
    val ready = state.claims.values.filter { it.account == active.account && (it.recipient == null || it.recipient == player.uniqueId) }
    button(player, holder, 15, Material.GOLDEN_HORSE_ARMOR, Lang.AUCTION_MANAGE_COLLECTIONS,
        "coins" to ready.filter { it.product == null }.fold(0L) { sum, claim -> Math.addExact(sum, claim.amount) },
        "items" to ready.count { it.product != null }) { auctionManage(it) }
    auctionInventorySelection(holder)
    navigation(player, holder, { it.closeInventory() }, { p -> operation(p, { expire() }) { auctionMain(it) } }, root = true)
    watchAuctions(holder, state) { auctionMain(it) }
    show(player,holder)
}

internal fun MarketController.returnAuctions(player: Player) {
    session(player)?.auctionReturn?.invoke(player) ?: auctionBrowse(player)
}

private fun MarketController.auctionManage(player: Player) {
    val active = session(player) ?: return
    if (active.browseOnly) { locales.send(player, Lang.ISLAND_NOT_FOUND); return }
    val state = storage.marketState()
    if (state.auctions.values.any { it.account == active.account && it.status == AuctionStatus.OPEN && it.expires > System.currentTimeMillis() } ||
        state.claims.values.any { it.account == active.account && (it.recipient == null || it.recipient == player.uniqueId) })
        auctionBrowse(player, scope = AuctionScope.MINE)
    else auctionSelect(player)
}

private fun MarketController.auctionInventorySelection(holder: MarketController.Holder,
    mode: AuctionMode = AuctionMode.BIN, hours: Long = settings.auction.durations.first()) {
    holder.auctionItemClick = { player, slot, item ->
        if (session(player)?.browseOnly == true) locales.send(player, Lang.ISLAND_NOT_FOUND)
        else if (!auctionEligible(item)) locales.send(player, Lang.AUCTION_INVALID_ITEM)
        else auctionDraft(player, slot, item, mode = mode, hours = hours)
    }
}

internal fun MarketController.auctionBrowse(player: Player, page: Int = 0, scope: AuctionScope = AuctionScope.ALL,
    mode: AuctionMode? = if (scope == AuctionScope.ALL) session(player)?.auctionMode else null,
    category: AuctionCategory? = if (scope == AuctionScope.ALL) AuctionCategory.WEAPONS else null,
    query: String = "", sort: AuctionSort = AuctionSort.HIGHEST,
    rarity: String? = null, categoryPage: Int? = null) {
    val active = session(player)?.takeIf { it.kind == "auction" } ?: return
    if (scope == AuctionScope.ALL) active.auctionMode = mode
    val holder = menu(player, when (scope) { AuctionScope.ALL -> Lang.AUCTION_BROWSE; AuctionScope.MINE -> Lang.AUCTION_MY_LISTINGS; AuctionScope.BIDS -> Lang.AUCTION_MY_BIDS }, view = "browse-${scope.name.lowercase()}") ?: return
    val now = System.currentTimeMillis()
    val state = storage.marketState()
    val entries = state.auctions.values.filter { listing ->
        listing.status == AuctionStatus.OPEN && listing.expires > now &&
        (when (scope) {
            AuctionScope.ALL -> true
            AuctionScope.MINE -> listing.account == active.account
            AuctionScope.BIDS -> listing.buyer == active.account || listing.bids.any { it.account == active.account }
        }) && (mode == null || listing.item.mode == mode) && (category == null || listing.item.category == category.name ||
            category == AuctionCategory.TOOLS && listing.item.category == AuctionCategory.MISC.name) &&
            (rarity == null || (listing.item.rarity ?: "COMMON") == rarity) &&
            (query.isBlank() || listing.item.name.contains(query, true) || listing.item.product.contains(query, true))
    }.sortedWith(when (sort) {
        AuctionSort.LOWEST -> compareBy<AuctionListing> { it.bids.lastOrNull()?.amount ?: it.item.price }.thenBy { it.created }
        AuctionSort.HIGHEST -> compareByDescending<AuctionListing> { it.bids.lastOrNull()?.amount ?: it.item.price }.thenBy { it.created }
        AuctionSort.ENDING -> compareBy<AuctionListing> { it.expires }.thenBy { it.created }
        AuctionSort.MOST_BIDS -> compareByDescending<AuctionListing> { it.bids.size }.thenBy { it.expires }
        AuctionSort.NEWEST -> compareByDescending<AuctionListing> { it.created }
    })
    val grid = holder.grid(if (scope == AuctionScope.ALL) AuctionLayouts.content else AuctionLayouts.personal)
    val sidebarSlots = if (scope == AuctionScope.ALL) holder.grid(AuctionLayouts.categories, "categories") else emptyList()
    val categories = AuctionCategory.entries.filter { it != AuctionCategory.MISC }
    val sidebar = if (sidebarSlots.isEmpty()) 0 else (categoryPage ?: (categories.indexOf(category).coerceAtLeast(0) / sidebarSlots.size))
        .coerceIn(0, (categories.size - 1) / sidebarSlots.size)
    val current = page.coerceIn(0, (entries.size - 1).coerceAtLeast(0) / grid.size)
    fun browse(p: Player, nextPage: Int = 0, nextMode: AuctionMode? = mode, nextCategory: AuctionCategory? = category,
        nextQuery: String = query, nextSort: AuctionSort = sort, nextRarity: String? = rarity, nextSidebar: Int = sidebar) =
        auctionBrowse(p, nextPage, scope, nextMode, nextCategory, nextQuery, nextSort, nextRarity, nextSidebar)
    if (scope == AuctionScope.ALL) {
        for (separator in holder.layout.grids["separators"] ?: listOf(1,10,19,28,37)) button(player, holder, separator, Material.ORANGE_STAINED_GLASS_PANE, Lang.BAZAAR_SEPARATOR)
        val icons = listOf(Material.GOLDEN_SWORD, Material.DIAMOND_CHESTPLATE, Material.NETHER_STAR, Material.APPLE, Material.BRICKS, Material.STICK)
        categories.drop(sidebar * sidebarSlots.size).take(sidebarSlots.size).forEachIndexed { index, entry ->
            button(player, holder, sidebarSlots[index], icons[entry.ordinal], Lang.AUCTION_CATEGORY,
                "category" to locales.text(player, if (entry == AuctionCategory.TOOLS) Lang.AUCTION_TOOLS_MISC else Lang.valueOf("AUCTION_CATEGORY_${entry.name}")),
                "selected" to if (entry == category) "✔ " else "") { browse(it, nextCategory = entry) }
        }
        if (sidebarSlots.size < categories.size) button(player, holder, 8, Material.ARROW, Lang.BAZAAR_MORE_CATEGORIES) {
            browse(it, current, nextSidebar = (sidebar + 1) % ((categories.size + sidebarSlots.size - 1) / sidebarSlots.size))
        }
    }
    active.auctionReturn = { browse(it, current) }
    pageInfo(player, holder, current, entries.size, grid.size, slot = 4)
    if (entries.isEmpty()) button(player, holder, 22, Material.BARRIER, Lang.UI_EMPTY_AUCTIONS)
    entries.drop(current * grid.size).take(grid.size).forEachIndexed { index, listing ->
        val slot = grid[index]
        auctionPreview(player, holder, slot, listing.item.itemData, Lang.UI_AUCTION_LOT,
            "mode" to locales.text(player, if (listing.item.mode == AuctionMode.BIN) Lang.AUCTION_BIN else Lang.AUCTION_BID),
            "price" to (listing.bids.lastOrNull()?.amount ?: listing.item.price), "bids" to listing.bids.size, "amount" to listing.item.amount,
            "seller" to (listing.item.sellerName ?: listing.actor.toString().take(8)),
            "time" to MarketText.duration((listing.expires - now) / 1000), "status" to auctionStatus(player, listing))
        holder.actions.set(slot) { (p, _) -> auctionView(p, listing.id) }
    }
    if (scope == AuctionScope.ALL) {
    button(player, holder, 7, Material.OAK_SIGN, Lang.MARKET_SEARCH, "query" to query) { viewer ->
        textInput(viewer, query, Lang.MARKET_SEARCH_LABEL) { p, value -> browse(p, nextQuery = value.orEmpty().take(50)) }
    }
    button(player, holder, 47, Material.HOPPER, Lang.MARKET_SORT,
        "sort" to locales.text(player, Lang.valueOf("AUCTION_SORT_${sort.name}"))) {
        auctionSortOptions(it, sort, { p -> browse(p, current) }) { p, choice -> browse(p, nextSort = choice) }
    }
    button(player, holder, 51, Material.ENDER_EYE, Lang.AUCTION_RARITY,
        "rarity" to locales.text(player, if (rarity == null) Lang.AUCTION_ALL_RARITIES else Lang.valueOf("AUCTION_RARITY_$rarity"))) {
        auctionRarityOptions(it, rarity, { p -> browse(p, current) }) { p, choice -> browse(p, nextRarity = choice) }
    }
    button(player, holder, 49, when(mode) { null -> Material.POWERED_RAIL; AuctionMode.BIN -> Material.GOLD_INGOT; AuctionMode.BID -> Material.GOLD_BLOCK },
        Lang.AUCTION_FILTER, "mode" to locales.text(player, when(mode) { null -> Lang.AUCTION_ALL; AuctionMode.BIN -> Lang.AUCTION_BIN; AuctionMode.BID -> Lang.AUCTION_BID })) {
        browse(it, nextMode = when(mode) { null -> AuctionMode.BIN; AuctionMode.BIN -> AuctionMode.BID; AuctionMode.BID -> null })
    }
    button(player, holder, 6, Material.CAULDRON, Lang.UI_CLEAR_FILTERS) {
        browse(it, nextMode = null, nextCategory = null, nextQuery = "", nextSort = AuctionSort.HIGHEST, nextRarity = null, nextSidebar = 0)
    }
    }
    navigation(player, holder, { auctionMain(it) })
    if (current > 0) button(player, holder, 46, Material.ARROW, Lang.MENU_PREVIOUS) { browse(it, current - 1) }
    if ((current + 1) * grid.size < entries.size) button(player, holder, 52, Material.ARROW, Lang.MENU_NEXT) { browse(it, current + 1) }
    watchAuctions(holder, state) { browse(it, current) }
    show(player,holder)
}

private fun MarketController.auctionStatus(player: Player, listing: AuctionListing): String = locales.text(player,
    when {
        listing.status == AuctionStatus.SOLD -> Lang.AUCTION_STATUS_SOLD
        listing.status == AuctionStatus.CANCELLED -> Lang.AUCTION_STATUS_CANCELLED
        listing.status == AuctionStatus.EXPIRED || listing.expires <= System.currentTimeMillis() -> Lang.AUCTION_STATUS_EXPIRED
        listing.available > System.currentTimeMillis() -> Lang.AUCTION_STATUS_WAIT
        else -> Lang.AUCTION_STATUS_OPEN
    })

private fun MarketController.auctionView(player: Player, id: UUID) {
    val active = session(player) ?: return
    val state = storage.marketState()
    val listing = state.auctions[id] ?: run { returnAuctions(player); return }
    val holder = menu(player, if (listing.item.mode == AuctionMode.BIN) Lang.AUCTION_VIEW_BIN else Lang.AUCTION_VIEW_BID, view = "listing") ?: return
    auctionPreview(player, holder, 13, listing.item.itemData, Lang.UI_AUCTION_LOT,
        "mode" to locales.text(player, if (listing.item.mode == AuctionMode.BIN) Lang.AUCTION_BIN else Lang.AUCTION_BID),
        "price" to (listing.bids.lastOrNull()?.amount ?: listing.item.price), "bids" to listing.bids.size, "amount" to listing.item.amount,
        "seller" to (listing.item.sellerName ?: listing.actor.toString().take(8)),
        "time" to MarketText.duration((listing.expires - System.currentTimeMillis()) / 1000), "status" to auctionStatus(player, listing))
    val open = listing.status == AuctionStatus.OPEN && listing.expires > System.currentTimeMillis()
    val ready = listing.available <= System.currentTimeMillis()
    if (open && !ready && listing.account != active.account)
        button(player, holder, 31, Material.CLOCK, Lang.UI_AUCTION_STARTS, "time" to MarketText.duration((listing.available - System.currentTimeMillis() + 999) / 1000))
    if (open && listing.account == active.account && listing.bids.isNotEmpty())
        button(player, holder, 31, Material.CHEST, Lang.UI_AUCTION_OWN_BIDS, "bids" to listing.bids.size)
    if (open && listing.account == active.account && listing.bids.isEmpty()) {
        button(player, holder, 31, Material.RED_CONCRETE, Lang.MARKET_CANCEL) { viewer ->
            auctionConfirm(viewer, listing.item.itemData, Lang.AUCTION_CANCEL_DETAILS, "price" to listing.item.fee,
                back = { auctionView(it, id) }) { operation(it, { cancelAuction(id) }) { p -> claims(p) } }
        }
    } else if (open && ready && (listing.actor == player.uniqueId || islands.relatedAuctionAccounts(active.account, listing.account))) {
        button(player, holder, 31, Material.BARRIER, Lang.AUCTION_SELF)
    } else if (open && ready && listing.account != active.account) {
        if (listing.item.mode == AuctionMode.BIN) button(player, holder, 31, Material.GOLD_INGOT, Lang.AUCTION_BUY, "price" to listing.item.price) { viewer ->
            auctionConfirm(viewer, listing.item.itemData, Lang.AUCTION_BUY_DETAILS, "price" to listing.item.price,
                back = { auctionView(it, id) }) { operation(it, { buyAuction(id, listing.item.price) }) { p -> claims(p) } }
        } else {
            val minimum = settings.auction.minimumBid(listing)
            if (minimum > settings.auction.maxPrice || listing.bids.size >= settings.auction.maxBids) {
                button(player, holder, 31, Material.BARRIER, Lang.AUCTION_BID_LIMIT)
            } else {
                button(player, holder, 31, Material.GOLD_NUGGET, Lang.AUCTION_PLACE_BID, "price" to minimum) { auctionBidConfirm(it, listing, minimum) }
                button(player, holder, 33, Material.GOLD_INGOT, Lang.AUCTION_CUSTOM_PRICE, "min" to minimum, "max" to settings.auction.maxPrice) { viewer ->
                    textInput(viewer, minimum.toString(), Lang.AUCTION_SIGN_PRICE) { p, value ->
                        val price = VirtualSignInput.number(value, minimum, settings.auction.maxPrice)
                        if (price == null) { if (value != null) locales.send(p, Lang.MARKET_INPUT_INVALID, "min" to minimum, "max" to settings.auction.maxPrice); auctionView(p, id) }
                        else auctionBidConfirm(p, listing, price)
                    }
                }
            }
        }
    }
    button(player, holder, 22, Material.MAP, Lang.AUCTION_HISTORY, "count" to listing.bids.size) { auctionHistory(it, id) }
    if (!open && state.claims.values.any { it.account == active.account && it.source.endsWith(":$id") })
        button(player, holder, 31, Material.CHEST, Lang.MARKET_MY_CLAIMS) { claims(it) }
    navigation(player, holder, { returnAuctions(it) }, { p -> operation(p, { expire() }) { auctionView(it, id) } })
    watchAuctions(holder, state) { auctionView(it, id) }
    show(player,holder)
}

private fun MarketController.auctionBidConfirm(player: Player, listing: AuctionListing, price: Long) {
    val account = session(player)?.account ?: return
    val held = listing.bids.lastOrNull()?.takeIf { it.account == account }?.amount ?: 0
    auctionConfirm(player, listing.item.itemData, Lang.UI_AUCTION_BID_REVIEW, "price" to price, "charge" to price - held,
        back = { auctionView(it, listing.id) }) { viewer ->
        operation(viewer, { bidAuction(listing.id, price, viewer.name, agreedCharge = price - held) },
            failed = { auctionView(viewer, listing.id) }) { p -> auctionView(p, listing.id) }
    }
}

private fun MarketController.auctionHistory(player: Player, id: UUID, page: Int = 0) {
    val listing = storage.marketState().auctions[id] ?: return
    val holder = menu(player, Lang.AUCTION_HISTORY_TITLE, view = "history") ?: return
    val entries = listing.bids.asReversed()
    val grid = holder.grid((0..44).toList())
    val current = page.coerceIn(0, (entries.size - 1).coerceAtLeast(0) / grid.size)
    entries.drop(current * grid.size).take(grid.size).forEachIndexed { index, bid ->
        button(player, holder, grid[index], Material.GOLD_NUGGET, Lang.AUCTION_HISTORY_ENTRY,
            "player" to (bid.actorName ?: bid.actor.toString().take(8)), "price" to bid.amount,
            "time" to java.time.Instant.ofEpochMilli(bid.time))
    }
    navigation(player, holder, { auctionView(it, id) })
    if (current > 0) button(player, holder, 46, Material.ARROW, Lang.MENU_PREVIOUS) { auctionHistory(it, id, current - 1) }
    if ((current + 1) * grid.size < entries.size) button(player, holder, 52, Material.ARROW, Lang.MENU_NEXT) { auctionHistory(it, id, current + 1) }
    show(player,holder)
}

private fun MarketController.auctionEligible(item: ItemStack): Boolean {
    val rules = settings.auction
    return !item.isEmpty && com.alytanta.oneblock.items.CustomItems.marketTransferAllowed(item) && item.type.name !in rules.blocked &&
        (rules.allowedItems == null || item.type.name in rules.allowedItems) &&
        item.itemMeta?.persistentDataContainer?.has(NamespacedKey(plugin, "player_menu"), PersistentDataType.BYTE) != true &&
        !(rules.excludeBazaar && !item.hasItemMeta() && settings.products[item.type.name]?.bazaar == true)
}

internal fun MarketController.auctionSelect(player: Player, mode: AuctionMode = AuctionMode.BIN,
    hours: Long = settings.auction.durations.first()) {
    if (session(player)?.browseOnly == true) { locales.send(player, Lang.ISLAND_NOT_FOUND); return }
    val holder = menu(player, if (mode == AuctionMode.BIN) Lang.AUCTION_CREATE_BIN else Lang.AUCTION_CREATE_BID, view = "select") ?: return
    button(player, holder, 13, Material.STONE_BUTTON, Lang.UI_AUCTION_SELECT_HELP)
    button(player, holder, 31, Material.RED_TERRACOTTA, Lang.AUCTION_EMPTY_DRAFT)
    button(player, holder, 25, if (mode == AuctionMode.BIN) Material.GOLD_INGOT else Material.POWERED_RAIL, Lang.AUCTION_CUSTOM_PRICE,
        "min" to settings.auction.minPrice, "max" to settings.auction.maxPrice)
    button(player, holder, 19, if (mode == AuctionMode.BIN) Material.POWERED_RAIL else Material.GOLD_INGOT, Lang.AUCTION_TOGGLE_MODE,
        "mode" to locales.text(player, if (mode == AuctionMode.BIN) Lang.AUCTION_BIN else Lang.AUCTION_BID)) {
        auctionSelect(it, if (mode == AuctionMode.BIN) AuctionMode.BID else AuctionMode.BIN, hours)
    }
    button(player, holder, 22, Material.CLOCK, Lang.AUCTION_DURATION, "hours" to hours) {
        auctionDurationOptions(it, hours, { p -> auctionSelect(p, mode, hours) }) { p, selected -> auctionSelect(p, mode, selected) }
    }
    auctionInventorySelection(holder, mode, hours)
    navigation(player, holder, { auctionMain(it) })
    show(player,holder)
}

private fun MarketController.auctionDraft(player: Player, sourceSlot: Int, item: ItemStack, mode: AuctionMode = AuctionMode.BIN,
    price: Long = settings.auction.minPrice, hours: Long = settings.auction.durations.first(), priceChosen: Boolean = false) {
    val active = session(player)?.takeIf { it.kind == "auction" } ?: return
    val rules = settings.auction
    if (sourceSlot == MenuInventory.SLOT || !com.alytanta.oneblock.items.CustomItems.marketTransferAllowed(item) || item.type.name in rules.blocked ||
        (rules.allowedItems != null && item.type.name !in rules.allowedItems) ||
        item.itemMeta?.persistentDataContainer?.has(NamespacedKey(plugin, "player_menu"), PersistentDataType.BYTE) == true ||
        rules.excludeBazaar && !item.hasItemMeta() && settings.products[item.type.name]?.bazaar == true) {
        locales.send(player, Lang.AUCTION_INVALID_ITEM); return
    }
    val name = item.itemMeta?.displayName()?.let { PlainTextComponentSerializer.plainText().serialize(it) } ?: item.type.name
    val draft = AuctionDraft(AuctionItems.encode(item, rules.maxItemBytes), item.type.name, item.amount, name.take(150),
        com.alytanta.oneblock.items.CustomProducts.auctionCategory(item) ?: rules.category(item.type), mode, price, hours, rules.fee(price), !item.hasItemMeta(),
        AuctionItemRarity.resolve(item, rules.rarityOverrides, rarityComponent), player.name)
    val holder = menu(player, if (mode == AuctionMode.BIN) Lang.AUCTION_CREATE_BIN else Lang.AUCTION_CREATE_BID, view = "draft") ?: return
    auctionPreview(player, holder, 13, draft.itemData, Lang.UI_AUCTION_REVIEW,
        "mode" to locales.text(player, if (mode == AuctionMode.BIN) Lang.AUCTION_BIN else Lang.AUCTION_BID),
        "price" to price, "hours" to hours, "fee" to draft.fee, "net" to price - AuctionSettings.percent(price, rules.saleTaxBps), "tax" to rules.saleTaxBps / 100.0)
    holder.actions.set(13) { (viewer, _) -> auctionSelect(viewer, mode, hours) }
    auctionInventorySelection(holder, mode, hours)
    button(player, holder, 19, if (mode == AuctionMode.BIN) Material.POWERED_RAIL else Material.GOLD_INGOT, Lang.AUCTION_TOGGLE_MODE,
        "mode" to locales.text(player, if (mode == AuctionMode.BIN) Lang.AUCTION_BIN else Lang.AUCTION_BID)) {
        auctionDraft(it, sourceSlot, item, if (mode == AuctionMode.BID) AuctionMode.BIN else AuctionMode.BID, price, hours, priceChosen)
    }
    button(player, holder, 22, Material.CLOCK, Lang.AUCTION_DURATION, "hours" to hours) {
        auctionDurationOptions(it, hours, { p -> auctionDraft(p, sourceSlot, item, mode, price, hours, priceChosen) }) { p, selected ->
            auctionDraft(p, sourceSlot, item, mode, price, selected, priceChosen)
        }
    }
    button(player, holder, 25, if (mode == AuctionMode.BIN) Material.GOLD_INGOT else Material.POWERED_RAIL, Lang.AUCTION_CUSTOM_PRICE, "min" to rules.minPrice, "max" to rules.maxPrice) { viewer ->
        textInput(viewer, price.toString(), Lang.AUCTION_SIGN_PRICE) { p, value ->
            val chosen = VirtualSignInput.number(value, rules.minPrice, rules.maxPrice)
            if (value != null && chosen == null) locales.send(p, Lang.MARKET_INPUT_INVALID, "min" to rules.minPrice, "max" to rules.maxPrice)
            auctionDraft(p, sourceSlot, item, mode, chosen ?: price, hours, chosen != null || priceChosen)
        }
    }
    button(player, holder, 31, Material.LIME_TERRACOTTA, Lang.AUCTION_PUBLISH) { viewer ->
        if (rules.requirePriceInput && !priceChosen) { locales.send(viewer, Lang.AUCTION_PRICE_REQUIRED); return@button }
        auctionConfirm(viewer, draft.itemData, Lang.UI_AUCTION_REVIEW,
            "mode" to locales.text(viewer, if (mode == AuctionMode.BIN) Lang.AUCTION_BIN else Lang.AUCTION_BID),
            "price" to price, "hours" to hours, "fee" to draft.fee, "net" to price - AuctionSettings.percent(price, rules.saleTaxBps), "tax" to rules.saleTaxBps / 100.0,
            back = { auctionDraft(it, sourceSlot, item, mode, price, hours, priceChosen) }) { p ->
            handoffs.transfer(p, active.account, draft.product, draft.amount, TransferKind.AUCTION_LIST,
                auction = draft, sourceSlot = sourceSlot) { if (session(p) != null) auctionBrowse(p, scope = AuctionScope.MINE) }
        }
    }
    navigation(player, holder, { auctionSelect(it) })
    show(player,holder)
}

private fun MarketController.auctionConfirm(player: Player, data: String, text: Lang, vararg args: Pair<String, Any>,
    back: (Player) -> Unit, execute: (Player) -> Unit) {
    val holder = menu(player, Lang.MARKET_CONFIRM_TITLE, view = "confirm", context = text.name.lowercase().replace('_', '-')) ?: return
    auctionPreview(player, holder, 13, data, text, *args)
    button(player, holder, 31, Material.LIME_TERRACOTTA, Lang.MARKET_CONFIRM) { viewer ->
        if (!holder.submitted && session(viewer) != null) {
            holder.submitted = true; execute(viewer)
            if (!InventoryGuard.busy(viewer.uniqueId) && viewer.openInventory.topInventory === holder.contents) holder.submitted = false
        }
    }
    navigation(player, holder, back)
    show(player,holder)
}
