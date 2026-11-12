package com.alytanta.oneblock.market

import java.util.UUID

private fun reject(reason: String): Nothing = throw MarketException(reason)

fun MarketEngine.Transaction.validateAuction(draft: AuctionDraft) {
    val rules = auctionRules
    if (!rules.enabled || draft.price !in rules.minPrice..rules.maxPrice || draft.durationHours !in rules.durations ||
        draft.amount !in 1..64 || draft.fee != rules.fee(draft.price) || draft.itemData.isBlank() ||
        draft.itemData.length > (rules.maxItemBytes.toLong() + 2) / 3 * 4 || draft.product in rules.blocked ||
        (rules.allowedItems != null && draft.product !in rules.allowedItems) ||
        (rules.excludeBazaar && draft.plain && bazaarProduct(draft.product))) reject("auction-item")
    if (auctions.values.count { it.account == account && it.status == AuctionStatus.OPEN } +
        transfers.values.count { it.account == account && it.kind == TransferKind.AUCTION_LIST && it.status in setOf(TransferStatus.PREPARED, TransferStatus.REVIEW) } >= rules.maxListings) reject("order-limit")
}

fun MarketEngine.Transaction.createAuction(transfer: ItemTransfer) {
    val draft = requireNotNull(transfer.auction)
    // The prepared handoff itself already reserved one listing slot.
    val pending = transfers.remove(transfer.id)
    try { validateAuction(draft) } finally { if (pending != null) transfers[transfer.id] = pending }
    val time = marketTime()
    auctions[transfer.id] = AuctionListing(transfer.id, account, actor, draft, time,
        Math.addExact(time, auctionRules.graceSeconds * 1000), Math.addExact(time, draft.durationHours * 3_600_000), auctionRules.saleTaxBps)
}

private fun MarketEngine.Transaction.auctionClaim(owner: UUID, listing: AuctionListing, source: String) {
    val id = UUID.randomUUID()
    claims[id] = MarketClaim(id, owner, listing.item.product, listing.item.amount.toLong(), source, listing.item.itemData)
}

fun MarketEngine.Transaction.expireAuctions() {
    auctions.values.filter { it.status == AuctionStatus.OPEN && it.expires <= marketTime() }.toList().forEach { listing ->
        if (listing.bids.isEmpty()) {
            auctionClaim(listing.account, listing, "auction-return:${listing.id}")
            auctions[listing.id] = listing.copy(status = AuctionStatus.EXPIRED)
        } else settleAuction(listing, listing.bids.last().account, listing.bids.last().amount)
    }
}

private fun MarketEngine.Transaction.settleAuction(listing: AuctionListing, buyer: UUID, price: Long) {
    auctionClaim(buyer, listing, "auction-purchase:${listing.id}")
    claim(listing.account, null, price - AuctionSettings.percent(price, listing.saleTaxBps), "auction-proceeds:${listing.id}")
    auctions[listing.id] = listing.copy(status = AuctionStatus.SOLD, buyer = buyer, paid = price)
}

private fun MarketEngine.Transaction.liveAuction(id: UUID): AuctionListing {
    val listing = auctions[id] ?: reject("auction-ended")
    if (listing.status != AuctionStatus.OPEN || listing.expires <= marketTime()) reject("auction-ended")
    return listing
}

fun MarketEngine.Transaction.buyAuction(id: UUID, agreedPrice: Long) {
    begin("AUCTION_BUY", mapOf("listing" to id, "agreed_price" to agreedPrice), id)
    val listing = liveAuction(id)
    if (ownsAuction(listing)) reject("auction-self")
    if (!auctionRules.enabled || listing.item.mode != AuctionMode.BIN ||
        marketTime() < listing.available || listing.item.price != agreedPrice) reject("auction-access")
    debit(listing.item.price)
    settleAuction(listing, account, listing.item.price)
}

fun MarketEngine.Transaction.bidAuction(id: UUID, amount: Long, actorName: String? = null, agreedCharge: Long? = null) {
    begin("AUCTION_BID", mapOf("listing" to id, "amount" to amount), id)
    val listing = liveAuction(id)
    if (ownsAuction(listing)) reject("auction-self")
    if (!auctionRules.enabled || listing.item.mode != AuctionMode.BID ||
        marketTime() < listing.available) reject("auction-access")
    if (listing.bids.size >= auctionRules.maxBids) reject("auction-bid-limit")
    if (amount < auctionRules.minimumBid(listing) || amount > auctionRules.maxPrice) reject("auction-bid")
    val previous = listing.bids.lastOrNull()
    val charge = amount - if (previous?.account == account) previous.amount else 0
    if (agreedCharge != null && agreedCharge != charge) reject("auction-bid")
    // Raising one's own high bid only escrows the difference; other high bidders receive a claim.
    debit(charge)
    if (previous != null && previous.account != account) claim(previous.account, null, previous.amount, "auction-outbid:$id")
    auctions[id] = listing.copy(bids = listing.bids + AuctionBid(account, actor, amount, marketTime(), actorName?.take(32)),
        expires = maxOf(listing.expires, Math.addExact(marketTime(), auctionRules.extensionSeconds * 1000)))
}

fun MarketEngine.Transaction.cancelAuction(id: UUID) {
    begin("AUCTION_CANCEL", mapOf("listing" to id), id)
    val listing = liveAuction(id)
    if (listing.account != account || listing.bids.isNotEmpty()) reject("auction-bids")
    auctionClaim(account, listing, "auction-cancel:$id")
    auctions[id] = listing.copy(status = AuctionStatus.CANCELLED)
}

/** Relist escrowed Bazaar purchases directly, without an inventory round trip. */
fun MarketEngine.Transaction.flipClaim(id: UUID, amount: Int, price: Long) {
    begin("BAZAAR_FLIP", mapOf("claim" to id, "quantity" to amount, "unit_price" to price), id)
    val held = claims[id] ?: reject("claim")
    if (held.account != account || held.recipient != null && held.recipient != actor || held.product == null || held.itemData != null || amount <= 0 || amount > held.amount) reject("claim")
    sellClaim(held.product, amount, price)
    val remaining = held.amount - amount
    if (remaining == 0L) claims.remove(id) else claims[id] = held.copy(amount = remaining)
}
