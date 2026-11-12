package com.alytanta.oneblock.market

import com.alytanta.oneblock.locale.Lang
import com.alytanta.oneblock.menu.guiStyle
import com.alytanta.oneblock.util.Colors
import org.bukkit.Material
import org.bukkit.entity.Player

internal fun catalogDescription(holder: MarketController.Holder, slot: Int, lore: List<String>) {
    if (lore.isEmpty() || holder.layout.guiStyle(slot)?.lore != null) return
    val target = holder.layout.target(slot) ?: return
    val item = holder.contents.getItem(target)?.clone() ?: return
    item.editMeta { it.lore(it.lore().orEmpty() + lore.map(Colors::colorize)) }
    holder.place(slot, item)
}

private fun <T> positions(items: List<T>, grid: List<Int>, preferred: (T) -> Int?): List<Pair<Int, T>> {
    val fixed = items.mapNotNull(preferred)
    require(fixed.distinct().size == fixed.size && fixed.all { it in grid }) { "Catalog preferred slots must be distinct and belong to the window grid" }
    val free = grid.filter { it !in fixed }.iterator()
    return items.map { (preferred(it) ?: free.next()) to it }
}

private fun MarketController.catalogFooter(player: Player, holder: MarketController.Holder) {
    if (session(player)?.kind == "shop") {
        button(player, holder, 48, Material.HOPPER, Lang.MERCHANT_SELL_ALL) { merchantSellAll(it) }
    } else button(player, holder, 51, Material.HOPPER, Lang.QUICK_SELL_ALL) { quickSell(it) }
}

/** Shared category -> group -> variant layout for the two resource NPCs. */
internal fun MarketController.bazaar(player: Player, categoryId: String? = null, page: Int = 0,
    query: String = "", categoryPage: Int = 0) {
    val active = session(player) ?: return
    val catalog = settings.catalog
    val categories = catalog.categories.filter { it.enabled }
    val selected = categories.firstOrNull { it.id == categoryId } ?: categories.first()
    val holder = menu(player, if (active.kind == "shop") Lang.MARKET_SHOP_TITLE else Lang.MARKET_TITLE,
        view = "catalog", context = selected.id) ?: return
    val grid = holder.grid((0 until 24).map { 11 + it / 6 * 9 + it % 6 })
    val sidebar = holder.grid(listOf(0,9,18,27,36), "categories")
    val categoriesPage = categoryPage.coerceIn(0, (categories.size - 1) / sidebar.size)
    val products = settings.products.values.filter { (if (active.kind == "shop") it.shop else it.bazaar) && catalog.category(it) == selected.id }
        .filter { catalog.groups[catalog.group(it)]?.enabled != false }
        .filter { query.isBlank() || label(it.material.name).contains(query, true) || it.material.name.contains(query, true) ||
            catalog.groups[catalog.group(it)]?.name?.contains(query, true) == true }
    val groups = products.groupBy(catalog::group).entries.sortedBy { catalog.groups[it.key]?.order ?: 0 }
    val current = page.coerceIn(0, (groups.size - 1).coerceAtLeast(0) / grid.size)
    active.catalogReturn = { bazaar(it, selected.id, current, query, categoriesPage) }
    for (slot in holder.layout.grids["separators"] ?: listOf(1,10,19,28,37)) button(player, holder, slot, selected.color, Lang.BAZAAR_SEPARATOR)
    positions(categories.drop(categoriesPage * sidebar.size).take(sidebar.size), sidebar) { it.slot }.forEach { (slot, category) ->
        val name = when (category.name) {
            "Farming" -> locales.text(player, Lang.MARKET_CATEGORY_FARMING)
            "Mining" -> locales.text(player, Lang.MARKET_CATEGORY_MINING)
            "Combat" -> locales.text(player, Lang.MARKET_CATEGORY_COMBAT)
            "Woods & Fishes" -> locales.text(player, Lang.BAZAAR_WOODS_FISHES)
            "Oddities" -> locales.text(player, Lang.MARKET_CATEGORY_OTHER)
            else -> category.name
        }
        button(player, holder, slot, category.icon, Lang.MARKET_CATEGORY, "category" to name,
            "selected" to if (category.id == selected.id) "✔ " else "") { bazaar(it, category.id, categoryPage = categoriesPage) }
        catalogDescription(holder, slot, category.lore)
    }
    if (categories.size > sidebar.size) button(player, holder, 8, Material.ARROW, Lang.BAZAAR_MORE_CATEGORIES) {
        bazaar(it, selected.id, current, query, (categoriesPage + 1) % ((categories.size + sidebar.size - 1) / sidebar.size))
    }
    positions(groups.drop(current * grid.size).take(grid.size), grid) { catalog.groups[it.key]?.slot }.forEach { (slot, entry) ->
        val group = catalog.groups[entry.key]
        val first = entry.value.first()
        button(player, holder, slot, group?.icon ?: first.material, Lang.BAZAAR_GROUP,
            "name" to (group?.name ?: label(first.material.name)), "count" to entry.value.size) {
            bazaarVariants(it, selected.id, entry.key, query, current, categoriesPage)
        }
        catalogDescription(holder, slot, group?.lore.orEmpty())
    }
    if (groups.isEmpty()) button(player, holder, 22, Material.BARRIER, Lang.UI_EMPTY_PRODUCTS)
    navigation(player, holder, { it.closeInventory() }, { bazaar(it, selected.id, current, query, categoriesPage) }, root = true)
    button(player, holder, 4, selected.icon, Lang.BAZAAR_SELECTED, "name" to selected.name, "count" to products.size)
    catalogFooter(player, holder)
    button(player, holder, 49, Material.BARRIER, Lang.MARKET_CLOSE) { it.closeInventory() }
    button(player, holder, 45, Material.OAK_SIGN, Lang.MARKET_SEARCH, "query" to query) {
        textInput(it, query, Lang.MARKET_SEARCH_LABEL) { viewer, value -> bazaar(viewer, selected.id, query = value.orEmpty().take(50), categoryPage = categoriesPage) }
    }
    if (current > 0) button(player, holder, 46, Material.ARROW, Lang.MENU_PREVIOUS) { bazaar(it, selected.id, current - 1, query, categoriesPage) }
    if ((current + 1) * grid.size < groups.size) button(player, holder, 52, Material.ARROW, Lang.MENU_NEXT) { bazaar(it, selected.id, current + 1, query, categoriesPage) }
    show(player,holder)
}

private fun MarketController.bazaarVariants(player: Player, category: String, group: String, query: String,
    rootPage: Int, categoryPage: Int, page: Int = 0) {
    val active = session(player) ?: return
    val holder = menu(player, if (active.kind == "shop") Lang.MERCHANT_VARIANTS else Lang.BAZAAR_VARIANTS_TITLE,
        view = "variants", context = "$category--$group") ?: return
    val products = settings.products.values.filter { (if (active.kind == "shop") it.shop else it.bazaar) && settings.catalog.category(it) == category && settings.catalog.group(it) == group }
    val grid = holder.grid((0 until 28).map { 10 + it / 7 * 9 + it % 7 })
    val current = page.coerceIn(0, (products.size - 1).coerceAtLeast(0) / grid.size)
    val book = MarketBook(storage.marketState())
    active.catalogReturn = { bazaarVariants(it, category, group, query, rootPage, categoryPage, current) }
    positions(products.drop(current * grid.size).take(grid.size), grid) { it.slot }.forEach { (slot, item) ->
        if (active.kind == "shop") {
            button(player, holder, slot, item.icon ?: item.material, Lang.MARKET_SHOP_DETAILS,
                "item" to label(item.material.name), "sell" to item.sellPrice) { detail(it, item.material.name) }
            catalogDescription(holder, slot, item.lore)
        } else product(player, holder, slot, item.material.name, book)
    }
    navigation(player, holder, { bazaar(it, category, rootPage, query, categoryPage) },
        { bazaarVariants(it, category, group, query, rootPage, categoryPage, current) })
    catalogFooter(player, holder)
    if (current > 0) button(player, holder, 46, Material.ARROW, Lang.MENU_PREVIOUS) { bazaarVariants(it, category, group, query, rootPage, categoryPage, current - 1) }
    if ((current + 1) * grid.size < products.size) button(player, holder, 52, Material.ARROW, Lang.MENU_NEXT) { bazaarVariants(it, category, group, query, rootPage, categoryPage, current + 1) }
    show(player,holder)
}
