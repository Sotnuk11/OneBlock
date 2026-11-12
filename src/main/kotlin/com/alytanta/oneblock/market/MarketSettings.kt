package com.alytanta.oneblock.market

import org.bukkit.Material
import org.bukkit.configuration.ConfigurationSection

data class MarketProduct(val material: Material, val sellPrice: Long,
    val minOrderPrice: Long, val maxOrderPrice: Long, val bazaar: Boolean, val shop: Boolean,
    val category: MarketCategory = MarketCategory.infer(material), val displayName: String? = null,
    val categoryId: String = category.name.lowercase(), val group: String? = null,
    val icon: Material? = null, val slot: Int? = null, val lore: List<String> = emptyList())
data class MarketSettings(val enabled: Boolean, val bazaarNpc: String, val shopNpc: String, val distance: Double,
    val sessionSeconds: Long, val maxOrders: Int, val maxQuantity: Int, val expiryHours: Long,
    val sellTaxBps: Int, val quantities: List<Int>, val products: Map<String, MarketProduct>, val historyLimit: Int,
    val priceSteps: List<Long> = listOf(1, 10, 100), val inputSeconds: Long = 60, val ui: MarketUiSettings = MarketUiSettings(),
    val auction: AuctionSettings = AuctionSettings(), val appearance: MarketAppearance = MarketAppearance(),
    val buybackSeconds: Long = 900, val catalog: BazaarCatalog = BazaarCatalog(BazaarCatalog.defaults),
    val menus: MarketMenuLayouts = MarketMenuLayouts()) {
    fun validateMaterials() {
        menus.validateMaterials()
        products.values.forEach { require(it.material.isItem && (it.icon == null || it.icon.isItem)) { "Invalid market product/icon ${it.material}" } }
        catalog.categories.forEach { require(it.icon.isItem && it.color.isItem) }
        catalog.groups.values.forEach { require(it.icon.isItem) }
        (appearance.icons.values + listOfNotNull(appearance.filler)).forEach { require(it.isItem) { "Market icon $it is not an item" } }
        auction.allowedItems?.forEach { require(requireNotNull(Material.matchMaterial(it)).isItem) { "Auction allowed item $it is not an item" } }
    }
    companion object {
        fun load(config: ConfigurationSection): MarketSettings {
            fun positive(path: String, default: Long, max: Long = 1_000_000_000) =
                (config.get(path)?.toString()?.toLongOrNull() ?: if (config.contains(path)) error("$path must be an integer") else default)
                    .also { require(it in 1..max) { "$path is outside supported limits" } }
            require(!config.contains("market.products") || config.isConfigurationSection("market.products")) { "market.products must be a mapping" }
            val catalog = BazaarCatalog.load(config)
            val products = config.getConfigurationSection("market.products")?.getKeys(false)?.associate { key ->
                val path = "market.products.$key"
                require(config.isConfigurationSection(path)) { "$path must be a mapping" }
                val custom = com.alytanta.oneblock.items.CustomProducts.custom(key)
                val material = requireNotNull(Material.matchMaterial(if (custom) config.getString("$path.material").orEmpty() else key)) { "Unknown market product: $key" }
                require(material !in setOf(Material.AIR, Material.CAVE_AIR, Material.VOID_AIR))
                // Merchant is a resource sink; legacy purchase-price/stock keys have no effect.
                val sell = positive("$path.sell-price", 1)
                val minPrice = positive("$path.min-order-price", 1)
                val maxPrice = positive("$path.max-order-price", 1_000_000_000)
                require(minPrice <= maxPrice)
                fun enabled(key: String) = if (!config.contains("$path.$key")) true else {
                    require(config.isBoolean("$path.$key")) { "$path.$key must be true or false" }
                    config.getBoolean("$path.$key")
                }
                val configuredGroup = config.getString("$path.group")
                val categoryId = config.getString("$path.category")?.lowercase()
                    ?: configuredGroup?.let { catalog.groups[it]?.category } ?: MarketCategory.infer(material).name.lowercase()
                val category = MarketCategory.entries.firstOrNull { it.name.equals(categoryId, true) } ?: MarketCategory.infer(material)
                val group = config.getString("$path.group")?.also { require(it.matches(Regex("[a-z0-9_-]{1,40}"))) }
                val displayName = config.getString("$path.display-name")?.also { require(it.isNotBlank() && it.length <= 100 && '\n' !in it) }
                val product = MarketProduct(material, sell, minPrice, maxPrice, enabled("bazaar"), enabled("shop"), category, displayName, categoryId, group,
                    config.getString("$path.icon")?.let { requireNotNull(Material.matchMaterial(it)) },
                    if (config.contains("$path.slot")) requireNotNull(config.get("$path.slot")?.toString()?.toIntOrNull()).also { require(it in 0..53) } else null,
                    config.getStringList("$path.lore"))
                require(!(product.bazaar || product.shop) || catalog.categories.any { it.id == catalog.category(product) }) { "Unknown $path.category: $categoryId" }
                (if (custom) key else material.name) to product
            }.orEmpty()
            fun numbers(path: String, fallback: List<Long>): List<Long> {
                if (!config.contains(path)) return fallback
                require(config.isList(path)) { "$path must be a list" }
                return config.getList(path)!!.map { it.toString().toLongOrNull() ?: error("$path must contain integers") }
            }
            val rawQuantities = numbers("market.quantities", listOf(1, 16, 64))
            require(rawQuantities.all { it in 1..71680 }) { "market.quantities is outside supported limits" }
            val quantities = rawQuantities.map(Long::toInt)
            val maxQuantity = positive("market.max-quantity", 2304, 71680).toInt()
            require(quantities.size in 1..5 && quantities.distinct().size == quantities.size && quantities.all { it in 1..maxQuantity })
            val distance = config.getDouble("market.interaction-distance", 6.0).also { require(it.isFinite() && it in 1.0..32.0) }
            val tax = config.getInt("market.sell-tax-bps", 125).also { require(it in 0..10000) }
            val bazaarNpc = config.getString("market.npcs.bazaar", "oneblock_bazaar")!!
            val shopNpc = config.getString("market.npcs.shop", "oneblock_merchant")!!
            require(bazaarNpc.isNotBlank() && shopNpc.isNotBlank() && bazaarNpc != shopNpc)
            val auction = AuctionSettings.load(config)
            require(auction.npc !in setOf(bazaarNpc, shopNpc)) { "Market NPC names must be distinct" }
            val steps = numbers("market.price-steps", listOf(1L, 10L, 100L))
            require(steps.size in 1..3 && steps.all { it in 1..1_000_000_000 } && steps.distinct().size == steps.size)
            return MarketSettings(config.getBoolean("market.enabled", true), bazaarNpc, shopNpc, distance,
                positive("market.session-seconds", 120, 3600), positive("market.max-orders-per-island", 14, 100).toInt(),
                maxQuantity, positive("market.order-expiry-hours", 168, 8760), tax, quantities, products,
                positive("market.trade-history-limit", 10000, 100000).toInt().also { require(it >= 100) }, steps,
                positive("market.input-timeout-seconds", 60, 300), MarketUiSettings.load(config), auction, MarketAppearance.load(config),
                positive("market.merchant.buyback-seconds", 900, 86400), catalog)
        }
    }
}
