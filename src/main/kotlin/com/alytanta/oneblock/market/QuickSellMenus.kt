package com.alytanta.oneblock.market

import com.alytanta.oneblock.locale.Lang
import com.alytanta.oneblock.menu.MenuInventory
import org.bukkit.Material
import org.bukkit.entity.Player

/** Only ordinary storage stacks can enter the existing player buy-order book. */
internal fun MarketController.quickSell(player: Player) {
    val active = session(player)?.takeIf { it.kind == "bazaar" } ?: return
    val owned = linkedMapOf<String, Int>()
    player.inventory.storageContents.forEachIndexed { slot, stack ->
        if (slot != MenuInventory.SLOT && stack != null && !stack.isEmpty && !stack.hasItemMeta() && stack.type.maxDurability.toInt() == 0 &&
            settings.products[stack.type.name]?.bazaar == true)
            owned[stack.type.name] = Math.addExact(owned[stack.type.name] ?: 0, stack.amount)
    }
    val state = storage.marketState()
    val engine = MarketEngine(settings)
    val lines = try {
        owned.mapNotNull { (product, amount) ->
            val quote = engine.quote(state, active.account, product, OrderSide.SELL, minOf(amount, settings.maxQuantity))
            if (quote.quantity == 0) null else QuickSellLine(product, quote.quantity, quote.net)
        }
    } catch (_: ArithmeticException) { locales.send(player, Lang.MARKET_ERROR); return }
    if (lines.isEmpty()) { locales.send(player, Lang.QUICK_SELL_EMPTY); return }
    quickSellPreview(player, lines, owned.values.sum() - lines.sumOf { it.quantity })
}

private fun MarketController.quickSellPreview(player: Player, lines: List<QuickSellLine>, unsold: Int, page: Int = 0) {
    val active = session(player)?.takeIf { it.kind == "bazaar" } ?: return
    val holder = menu(player, Lang.QUICK_SELL_TITLE, view = "quick-sell") ?: return
    val grid = holder.grid((0 until 28).map { 10 + it / 7 * 9 + it % 7 })
    val current = page.coerceIn(0, (lines.size - 1) / grid.size)
    val amount = lines.sumOf { it.quantity }
    val net = try { lines.fold(0L) { sum, line -> Math.addExact(sum, line.minimumProceeds) } }
        catch (_: ArithmeticException) { locales.send(player, Lang.MARKET_ERROR); return }
    button(player, holder, 4, Material.HOPPER, Lang.QUICK_SELL_SUMMARY,
        "amount" to amount, "coins" to net, "unsold" to unsold)
    lines.drop(current * grid.size).take(grid.size).forEachIndexed { index, line ->
        button(player, holder, grid[index],
            settings.products.getValue(line.product).material, Lang.QUICK_SELL_LINE,
            "item" to label(line.product), "amount" to line.quantity, "coins" to line.minimumProceeds)
    }
    navigation(player, holder, { returnCatalog(it) }, { quickSell(it) })
    button(player, holder, 49, Material.LIME_CONCRETE, Lang.QUICK_SELL_CONFIRM, "amount" to amount, "coins" to net) { viewer ->
        if (holder.submitted || session(viewer)?.id != active.id) return@button
        holder.submitted = true
        handoffs.transfer(viewer, active.account, lines.first().product, amount, TransferKind.INSTANT_SELL_BATCH,
            bazaarLines = lines, failed = { holder.submitted = false }) {
            if (session(viewer)?.id == active.id) returnCatalog(viewer)
        }
    }
    if (current > 0) button(player, holder, 46, Material.ARROW, Lang.MENU_PREVIOUS) { quickSellPreview(it, lines, unsold, current - 1) }
    if ((current + 1) * grid.size < lines.size) button(player, holder, 52, Material.ARROW, Lang.MENU_NEXT) { quickSellPreview(it, lines, unsold, current + 1) }
    show(player,holder)
}
