package com.alytanta.oneblock.market

import java.util.UUID

enum class AuctionMode { BID, BIN }
enum class AuctionStatus { OPEN, SOLD, EXPIRED, CANCELLED }
data class AuctionDraft(val itemData: String, val product: String, val amount: Int, val name: String,
    val category: String, val mode: AuctionMode, val price: Long, val durationHours: Long, val fee: Long,
    val plain: Boolean, val rarity: String? = null, val sellerName: String? = null)
data class AuctionBid(val account: UUID, val actor: UUID, val amount: Long, val time: Long, val actorName: String? = null)
data class AuctionListing(val id: UUID, val account: UUID, val actor: UUID, val item: AuctionDraft,
    val created: Long, val available: Long, val expires: Long, val saleTaxBps: Int,
    val bids: List<AuctionBid> = emptyList(), val status: AuctionStatus = AuctionStatus.OPEN,
    val buyer: UUID? = null, val paid: Long? = null)

enum class AuctionCategory { WEAPONS, ARMOR, ACCESSORIES, CONSUMABLES, BLOCKS, TOOLS, MISC;
    companion object {
        fun infer(name: String): AuctionCategory = when {
            name.endsWith("SWORD") || name.endsWith("BOW") || name in setOf("TRIDENT", "MACE") -> WEAPONS
            listOf("HELMET", "CHESTPLATE", "LEGGINGS", "BOOTS", "ELYTRA").any(name::endsWith) -> ARMOR
            listOf("PICKAXE", "AXE", "SHOVEL", "HOE", "FISHING_ROD", "SHEARS").any(name::endsWith) -> TOOLS
            name.contains("POTION") || name in setOf("GOLDEN_APPLE", "ENCHANTED_GOLDEN_APPLE") -> CONSUMABLES
            else -> MISC
        }
    }
}
