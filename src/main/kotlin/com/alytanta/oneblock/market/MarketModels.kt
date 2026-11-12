package com.alytanta.oneblock.market

import java.util.UUID

enum class OrderSide { BUY, SELL }
enum class OrderStatus { OPEN, FILLED, CANCELLED, EXPIRED }
data class MarketOrder(val id: UUID, val account: UUID, val actor: UUID, val product: String,
    val side: OrderSide, val price: Long, val quantity: Int, val remaining: Int, val sequence: Long,
    val expires: Long, val status: OrderStatus = OrderStatus.OPEN, val created: Long = System.currentTimeMillis())
data class MarketClaim(val id: UUID, val account: UUID, val product: String?, val amount: Long, val source: String,
    val itemData: String? = null, val recipient: UUID? = null, val created: Long = System.currentTimeMillis())
data class MarketTrade(val id: UUID, val buyAccount: UUID, val sellAccount: UUID, val product: String,
    val quantity: Int, val price: Long, val fee: Long, val time: Long, val buyActor: UUID? = null, val sellActor: UUID? = null)
enum class TransferKind { SHOP_SELL, INSTANT_SELL, SELL_ORDER, WITHDRAW, AUCTION_LIST, SHOP_SELL_BATCH, SHOP_BUYBACK, INSTANT_SELL_BATCH, RELEASE }

data class QuickSellLine(val product: String, val quantity: Int, val minimumProceeds: Long)
enum class TransferStatus { PREPARED, DONE, ABORTED, REVIEW }
data class ItemTransfer(val id: UUID, val account: UUID, val actor: UUID, val product: String, val quantity: Int,
    val kind: TransferKind, val price: Long, val claimId: UUID?, val beforeHash: String, val afterHash: String,
    val status: TransferStatus = TransferStatus.PREPARED, val time: Long = System.currentTimeMillis(), val minimumProceeds: Long = 0,
    val itemData: String? = null, val auction: AuctionDraft? = null,
    val merchantLines: List<MerchantLine>? = null, val buyback: MerchantSale? = null,
    val bazaarLines: List<QuickSellLine>? = null, val recipient: UUID? = null,
    val release: com.alytanta.oneblock.release.ReleaseIntent? = null, val releaseItems: List<String?>? = null, val releaseEligible: Boolean = false,
    val originSource:String?=null)
data class MarketState(
    val orders: Map<UUID, MarketOrder> = emptyMap(),
    val claims: Map<UUID, MarketClaim> = emptyMap(),
    val transfers: Map<UUID, ItemTransfer> = emptyMap(),
    val trades: Map<UUID, MarketTrade> = emptyMap(),
    /** Preserved legacy stock for backups/compatibility; it cannot be bought or matched on Bazaar. */
    val stock: Map<String, Long> = emptyMap(),
    val sequence: Long = 0,
    val auctions: Map<UUID, AuctionListing> = emptyMap(),
    val merchantSales: Map<UUID, MerchantSale> = emptyMap(),
    val playerTrades: Map<UUID, com.alytanta.oneblock.trade.PlayerTrade> = emptyMap(),
    /** Permanent deduplication rows, outside player JSON and pruned market histories. */
    val combatReceipts: Map<UUID, com.alytanta.oneblock.combat.CombatReceipt> = emptyMap(),
    val chests:Map<UUID,com.alytanta.oneblock.rewards.ChestOffer> = emptyMap(),
    val chestProgress:Map<UUID,com.alytanta.oneblock.rewards.ChestProgress> = emptyMap(),
)
data class MarketChange<T>(val coins: Long, val state: MarketState, val result: T, val audit: com.alytanta.oneblock.storage.OperationLog? = null,
    val releaseProgress: com.alytanta.oneblock.release.ReleaseProgress? = null)
class MarketException(val reason: String) : IllegalArgumentException(reason) {
    var audit: com.alytanta.oneblock.storage.OperationLog? = null
}
