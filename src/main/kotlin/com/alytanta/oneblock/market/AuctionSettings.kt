package com.alytanta.oneblock.market

import org.bukkit.Material
import org.bukkit.configuration.ConfigurationSection

data class AuctionSettings(
    val enabled: Boolean = true, val npc: String = "oneblock_auction", val maxListings: Int = 14,
    val minPrice: Long = 1, val maxPrice: Long = 25_000_000_000,
    val durations: List<Long> = listOf(1, 6, 12, 24, 48), val graceSeconds: Long = 20,
    val bidIncrementBps: Int = 1500, val bidMinimumStep: Long = 1, val extensionSeconds: Long = 120,
    val listingFeeBps: Int = 100, val listingFlatFee: Long = 0, val saleTaxBps: Int = 100,
    val maxBids: Int = 10000, val maxItemBytes: Int = 65536, val excludeBazaar: Boolean = true,
    val blocked: Set<String> = setOf("BARRIER", "COMMAND_BLOCK", "STRUCTURE_BLOCK"),
    val categories: Map<String, String> = emptyMap(),
    val bidTiers: Map<Long, Int> = linkedMapOf(0L to 1500, 100000L to 1000, 1000000L to 500, 10000000L to 250),
    val rarityOverrides: Map<String, String> = emptyMap(), val requirePriceInput: Boolean = true,
    val allowedItems: Set<String>? = null,
) {
    fun fee(price: Long): Long = Math.addExact(listingFlatFee, percent(price, listingFeeBps))
    fun minimumBid(listing: AuctionListing): Long = listing.bids.lastOrNull()?.amount?.let {
        val rate = bidTiers.filterKeys { threshold -> it >= threshold }.maxByOrNull { it.key }?.value ?: bidIncrementBps
        val increase = Math.addExact(percent(it, rate), if ((it % 10000 * rate) % 10000 != 0L) 1L else 0L)
        Math.addExact(it, maxOf(bidMinimumStep, increase))
    } ?: listing.item.price
    fun category(material: Material): String = categories[material.name] ?: AuctionCategory.infer(material.name)
        .let { if (it == AuctionCategory.MISC && material.isBlock) AuctionCategory.BLOCKS else it }.name
    companion object {
        fun percent(value: Long, bps: Int): Long = value / 10000 * bps + value % 10000 * bps / 10000
        fun load(config: ConfigurationSection): AuctionSettings {
            fun number(key: String, default: Long, range: LongRange): Long {
                val path = "market.auction.$key"
                val value = config.get(path)?.toString()?.toLongOrNull() ?: if (config.contains(path)) error("$path must be an integer") else default
                require(value in range) { "$path is outside supported limits" }; return value
            }
            fun bool(key: String, default: Boolean): Boolean {
                val path = "market.auction.$key"
                require(!config.contains(path) || config.isBoolean(path)) { "$path must be boolean" }
                return config.getBoolean(path, default)
            }
            val defaults = AuctionSettings()
            val durations = if (!config.contains("market.auction.duration-hours")) defaults.durations else {
                require(config.isList("market.auction.duration-hours"))
                config.getList("market.auction.duration-hours")!!.map { it.toString().toLongOrNull() ?: error("Invalid auction duration") }
            }
            require(durations.size in 1..5 && durations.distinct().size == durations.size && durations.all { it in 1..8760 })
            val blocked = if (!config.contains("market.auction.blocked-materials")) defaults.blocked else {
                require(config.isList("market.auction.blocked-materials"))
                config.getStringList("market.auction.blocked-materials").map { requireNotNull(Material.matchMaterial(it)).name }.toSet()
            }
            require(!config.contains("market.auction.categories") || config.isConfigurationSection("market.auction.categories"))
            val categories = config.getConfigurationSection("market.auction.categories")?.getKeys(false)?.associate { key ->
                requireNotNull(Material.matchMaterial(key)).name to AuctionCategory.valueOf(config.getString("market.auction.categories.$key")!!.uppercase()).name
            }.orEmpty()
            val min = number("min-price", 1, 1..1_000_000_000_000)
            val max = number("max-price", defaults.maxPrice, min..1_000_000_000_000)
            val npc = config.getString("market.npcs.auction", defaults.npc)!!.also { require(it.isNotBlank()) }
            require(!config.contains("market.auction.bid-increment-tiers") || config.isConfigurationSection("market.auction.bid-increment-tiers"))
            val tiers = config.getConfigurationSection("market.auction.bid-increment-tiers")?.getValues(false)?.map { (key, value) ->
                val threshold = requireNotNull(key.toLongOrNull()).also { require(it >= 0) }
                val bps = requireNotNull(value.toString().toIntOrNull()).also { require(it in 0..10000) }
                threshold to bps
            }?.toMap() ?: if (config.contains("market.auction.bid-increment-bps")) emptyMap() else defaults.bidTiers
            require(!config.contains("market.auction.rarity-overrides") || config.isConfigurationSection("market.auction.rarity-overrides"))
            val rarities = config.getConfigurationSection("market.auction.rarity-overrides")?.getValues(false)?.map { (key, value) ->
                val rarity = value.toString().uppercase()
                require(rarity in RARITIES)
                requireNotNull(Material.matchMaterial(key)).name to rarity
            }?.toMap().orEmpty()
            val allowed = if (config.contains("market.auction.allowed-items")) {
                require(config.isList("market.auction.allowed-items")) { "auction.allowed-items must be a list" }
                config.getList("market.auction.allowed-items")!!.map {
                    require(it is String) { "auction.allowed-items must contain material names" }
                    requireNotNull(Material.matchMaterial(it)).name
                }.toSet()
            } else null
            return AuctionSettings(bool("enabled", true), npc, number("max-listings-per-island", 14, 1L..100L).toInt(), min, max,
                durations, number("purchase-delay-seconds", 20, 0L..300L), number("bid-increment-bps", 1500, 0L..10000L).toInt(),
                number("bid-minimum-step", 1, 1..max), number("bid-extension-seconds", 120, 0L..3600L),
                number("listing-fee-bps", 100, 0L..10000L).toInt(), number("listing-flat-fee", 0, 0L..1_000_000_000L),
                number("sale-tax-bps", 100, 0L..10000L).toInt(), number("max-bids-per-listing", 10000, 1L..100000L).toInt(),
                number("max-item-bytes", 65536, 1024L..1048576L).toInt(), bool("exclude-plain-bazaar-products", true), blocked, categories, tiers,
                rarities, bool("require-price-input", true), allowed)
        }
        val RARITIES = listOf("COMMON", "UNCOMMON", "RARE", "EPIC", "LEGENDARY", "MYTHIC", "DIVINE", "SPECIAL", "VERY_SPECIAL")
    }
}
