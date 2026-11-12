package com.alytanta.oneblock.market

import com.alytanta.oneblock.locale.Lang
import org.bukkit.Material
import org.bukkit.entity.Player

/** Collections belong to the selected profile, including sales completed while its seller was offline. */
internal fun MarketController.auctionClaims(player: Player, page: Int = 0, filter: Int = 0) {
    val active = session(player) ?: return
    val state = storage.marketState()
    val available = state.claims.values.filter { it.account == active.account && (it.recipient == null || it.recipient == player.uniqueId) }
    val entries = available.filter { filter == 0 || (it.product == null) == (filter == 2) }.sortedByDescending { it.created }
    val holder = menu(player, Lang.AUCTION_COLLECTIONS, view = "claims") ?: return
    val grid = holder.grid(AuctionLayouts.personal)
    val current = page.coerceIn(0, (entries.size - 1).coerceAtLeast(0) / grid.size)
    val coins = available.filter { it.product == null }.fold(0L) { sum, claim -> Math.addExact(sum, claim.amount) }
    if (4 !in grid) button(player, holder, 4, Material.MAP, Lang.AUCTION_COLLECTION_SUMMARY,
        "coins" to coins, "items" to available.count { it.product != null }, "page" to current + 1)
    if (entries.isEmpty()) button(player, holder, 22, Material.CHEST, Lang.UI_EMPTY_CLAIMS)
    entries.drop(current * grid.size).take(grid.size).forEachIndexed { index, claim ->
        val text = when {
            claim.product != null -> Lang.AUCTION_COLLECT_ITEM
            claim.source.startsWith("auction-proceeds:") -> Lang.AUCTION_COLLECT_SALE
            claim.source.startsWith("auction-outbid:") -> Lang.AUCTION_COLLECT_REFUND
            else -> Lang.AUCTION_COLLECT_COINS
        }
        val listing = runCatching { java.util.UUID.fromString(claim.source.substringAfterLast(':')) }.getOrNull()?.let(state.auctions::get)
        val item = listing?.item?.name ?: claim.product?.let(::label) ?: locales.text(player, Lang.MARKET_COINS)
        val args = arrayOf<Pair<String, Any>>("item" to item, "amount" to claim.amount,
            "gross" to (listing?.paid ?: claim.amount), "tax" to ((listing?.paid ?: claim.amount) - claim.amount).coerceAtLeast(0))
        button(player, holder, grid[index], if (claim.product == null) Material.GOLD_INGOT else Material.CHEST, text, *args) { viewer ->
            if (claim.product == null) operation(viewer, { collectCoins(claim.id) }) { auctionClaims(it, current, filter) }
            else collectAuctionItem(viewer, claim) { auctionClaims(it, current, filter) }
        }
        claim.itemData?.let { auctionPreview(player, holder, grid[index], it, text, *args) }
    }
    button(player, holder, 47, Material.CAULDRON, Lang.UI_COLLECT_COINS, "coins" to coins,
        action = if (coins > 0) ({ viewer -> operation(viewer, { collectAllCoins() }) { auctionClaims(it, current, filter) } }) else null)
    button(player, holder, 50, Material.HOPPER, Lang.AUCTION_COLLECTION_FILTER,
        "filter" to locales.text(player, listOf(Lang.AUCTION_ALL, Lang.AUCTION_ITEMS_ONLY, Lang.AUCTION_COINS_ONLY)[filter])) {
        auctionClaims(it, filter = (filter + 1) % 3)
    }
    navigation(player, holder, { main(it) }, { auctionClaims(it, current, filter) })
    if (current > 0) button(player, holder, 46, Material.ARROW, Lang.MENU_PREVIOUS) { auctionClaims(it, current - 1, filter) }
    if ((current + 1) * grid.size < entries.size) button(player, holder, 52, Material.ARROW, Lang.MENU_NEXT) { auctionClaims(it, current + 1, filter) }
    watchAuctions(holder, state) { auctionClaims(it, current, filter) }
    show(player,holder)
}
