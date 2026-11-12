package com.alytanta.oneblock.market

import com.alytanta.oneblock.locale.Lang
import org.bukkit.Material
import org.bukkit.entity.Player
import java.util.UUID

internal fun MarketController.merchantSellAll(player: Player, only: String? = null) {
    if (session(player)?.kind != "shop") return
    val lines = MerchantBasket.quote(player.inventory.storageContents, settings, only)
    if (lines.isEmpty()) {
        storage.audit(com.alytanta.oneblock.storage.OperationLog(operation = "SHOP_SELL_BATCH", actor = player.uniqueId, account = session(player)?.account,
            success = false, status = "FAILED", reason = "items", details = mapOf("only_product" to only)))
        locales.send(player, Lang.MARKET_NO_ITEMS); return
    }
    val total = MerchantBasket.total(lines)
    val holder = menu(player, Lang.MERCHANT_SELL_CONFIRM, view = "sell-confirm", context = only?.lowercase()) ?: return
    val listing = lines.joinToString("\n") { line -> locales.text(player, Lang.MERCHANT_BASKET_LINE,
        "item" to (settings.products[line.product]?.displayName ?: line.product), "amount" to line.quantity, "price" to line.unitPrice, "total" to line.total()) }
    button(player, holder, 13, Material.PAPER, Lang.MERCHANT_BASKET, "lines" to listing,
        "total" to total, "seconds" to settings.buybackSeconds)
    button(player, holder, 31, Material.LIME_CONCRETE, Lang.MARKET_CONFIRM) { viewer ->
        val active = session(viewer) ?: return@button
        if (holder.submitted) return@button
        holder.submitted = true
        handoffs.transfer(viewer, active.account, "BASKET", lines.sumOf { it.quantity }, TransferKind.SHOP_SELL_BATCH,
            merchantLines = lines, failed = { holder.submitted = false }) { if (session(viewer)?.id == active.id) main(viewer) }
    }
    navigation(player, holder, { main(it) })
    show(player,holder)
}

internal fun MarketController.merchantHistory(player: Player, page: Int = 0) {
    val active = session(player)?.takeIf { it.kind == "shop" } ?: return
    val holder = menu(player, Lang.MERCHANT_HISTORY_TITLE, view = "history") ?: return
    val entries = storage.marketState().merchantSales.values.filter {
        it.actor == player.uniqueId && it.account == active.account && it.expires > System.currentTimeMillis()
    }.sortedByDescending { it.sequence }
    val grid = holder.grid((0..44).toList())
    val current = page.coerceIn(0, (entries.size - 1).coerceAtLeast(0) / grid.size)
    entries.drop(current * grid.size).take(grid.size).forEachIndexed { index, sale ->
        button(player, holder, grid[index], requireNotNull(Material.matchMaterial(sale.product)), Lang.MERCHANT_HISTORY_ENTRY,
            "item" to (settings.products[sale.product]?.displayName ?: sale.product), "amount" to sale.quantity,
            "price" to sale.unitPrice, "total" to sale.total(), "seconds" to (sale.expires - System.currentTimeMillis()).coerceAtLeast(0) / 1000) {
            merchantBuybackConfirm(it, sale.id)
        }
    }
    if (entries.isEmpty()) button(player, holder, 22, Material.CLOCK, Lang.MERCHANT_HISTORY_EMPTY)
    navigation(player, holder, { main(it) }, { merchantHistory(it, current) })
    entries.firstOrNull()?.let { latest ->
        button(player, holder, 47, Material.EMERALD, Lang.MERCHANT_BUYBACK_LATEST,
            "item" to (settings.products[latest.product]?.displayName ?: latest.product), "amount" to latest.quantity,
            "total" to latest.total()) { merchantBuybackConfirm(it, latest.id) }
    }
    if (current > 0) button(player, holder, 46, Material.ARROW, Lang.MENU_PREVIOUS) { merchantHistory(it, current - 1) }
    if ((current + 1) * grid.size < entries.size) button(player, holder, 52, Material.ARROW, Lang.MENU_NEXT) { merchantHistory(it, current + 1) }
    show(player,holder)
}

private fun MarketController.merchantBuybackConfirm(player: Player, id: UUID) {
    val active = session(player)?.takeIf { it.kind == "shop" } ?: return
    val sale = storage.marketState().merchantSales[id]?.takeIf { it.account == active.account && it.actor == player.uniqueId && it.expires > System.currentTimeMillis() }
        ?: run { locales.send(player, Lang.MERCHANT_BUYBACK_EXPIRED); merchantHistory(player); return }
    val holder = menu(player, Lang.MERCHANT_BUYBACK_TITLE, view = "buyback") ?: return
    button(player, holder, 13, requireNotNull(Material.matchMaterial(sale.product)), Lang.MERCHANT_HISTORY_ENTRY,
        "item" to (settings.products[sale.product]?.displayName ?: sale.product), "amount" to sale.quantity,
        "price" to sale.unitPrice, "total" to sale.total(), "seconds" to (sale.expires - System.currentTimeMillis()).coerceAtLeast(0) / 1000)
    button(player, holder, 31, Material.LIME_CONCRETE, Lang.MARKET_CONFIRM) { viewer ->
        if (holder.submitted || session(viewer)?.id != active.id) return@button
        holder.submitted = true
        handoffs.transfer(viewer, active.account, sale.product, sale.quantity, TransferKind.SHOP_BUYBACK, sale.unitPrice,
            saleId = sale.id, failed = { holder.submitted = false }) { if (session(viewer)?.id == active.id) merchantHistory(viewer) }
    }
    navigation(player, holder, { merchantHistory(it) })
    show(player,holder)
}
