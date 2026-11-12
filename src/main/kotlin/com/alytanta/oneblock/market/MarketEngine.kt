package com.alytanta.oneblock.market

import java.util.UUID

/** Pure ledger. All changes are immutable and committed together with the island wallet by the repository. */
class MarketEngine(val settings: MarketSettings, private val clock: () -> Long = System::currentTimeMillis) {
    var relatedAuctionAccounts: (UUID, UUID) -> Boolean = { _, _ -> false }
    private fun fail(reason: String): Nothing = throw MarketException(reason)
    fun product(key: String): MarketProduct = settings.products[key] ?: fail("product")
    private fun quantity(value: Int) { if (value !in 1..settings.maxQuantity) fail("quantity") }
    fun tax(amount: Long): Long = amount / 10000 * settings.sellTaxBps + (amount % 10000 * settings.sellTaxBps) / 10000
    private fun total(price: Long, amount: Int): Long = Math.multiplyExact(price, amount.toLong())
    data class Quote(val quantity: Int, val gross: Long, val net: Long)
    fun quote(state: MarketState, account: UUID, key: String, side: OrderSide, amount: Int): Quote {
        quantity(amount)
        val candidates = state.orders.values.filter { it.status == OrderStatus.OPEN && it.expires > clock() &&
            it.account != account && it.product == key && it.side != side }
            .sortedWith(if (side == OrderSide.BUY) compareBy<MarketOrder> { it.price }.thenBy { it.sequence }
                else compareByDescending<MarketOrder> { it.price }.thenBy { it.sequence })
        var remaining = amount; var gross = 0L; var net = 0L
        for (order in candidates) {
            val count = minOf(remaining, order.remaining); val value = total(order.price, count)
            gross = Math.addExact(gross, value); net = Math.addExact(net, value - if (side == OrderSide.SELL) tax(value) else 0)
            remaining -= count; if (remaining == 0) break
        }
        return Quote(amount - remaining, gross, net)
    }

    inner class Transaction(val account: UUID, val actor: UUID, initialCoins: Long, val initial: MarketState) {
        var coins = initialCoins; private set
        val orders = initial.orders.toMutableMap()
        val claims = initial.claims.toMutableMap()
        val transfers = initial.transfers.toMutableMap()
        val trades = initial.trades.toMutableMap()
        val stock = initial.stock.toMutableMap()
        val auctions = initial.auctions.toMutableMap()
        val merchantSales = initial.merchantSales.toMutableMap()
        val buybackWindowSeconds get() = settings.buybackSeconds
        fun merchantProduct(key: String) = product(key)
        fun nextSequence(): Long { sequence = Math.addExact(sequence, 1); return sequence }
        private val walletBefore = initialCoins
        private var auditOperation = "MARKET_TRANSACTION"
        private var auditDetails: Map<String, Any?> = emptyMap()
        private var auditCorrelation: UUID? = null
        private var auditStatus = "SUCCESS"
        private var auditSuccess: Boolean? = true
        private var auditReason: String? = null
        fun begin(operation: String, details: Map<String, Any?> = emptyMap(), correlation: UUID? = null,
            status: String = "SUCCESS", success: Boolean? = true) {
            auditOperation = operation; auditDetails = details; auditCorrelation = correlation
            auditStatus = status; auditSuccess = success
        }
        fun rejected(reason: String) { auditStatus = "FAILED"; auditSuccess = false; auditReason = reason }
        fun audit(failure: String? = null): com.alytanta.oneblock.storage.OperationLog = com.alytanta.oneblock.storage.OperationLog(
            operation = auditOperation, actor = actor, account = account, success = if (failure != null) false else auditSuccess,
            status = if (failure != null) "FAILED" else auditStatus, reason = failure ?: auditReason, correlation = auditCorrelation,
            details = auditDetails + mapOf("coins_before" to walletBefore, "coins_after" to if (failure != null) walletBefore else coins,
                "orders" to orders.values.filter { initial.orders[it.id] != it },
                "claims" to claims.values.filter { initial.claims[it.id] != it },
                "claims_removed" to initial.claims.values.filter { it.id !in claims },
                "transfers" to transfers.values.filter { initial.transfers[it.id] != it },
                "trades" to trades.values.filter { initial.trades[it.id] != it },
                "auctions" to auctions.values.filter { initial.auctions[it.id] != it }.map { mapOf("id" to it.id, "status" to it.status, "buyer" to it.buyer, "paid" to it.paid) },
                "merchant_sales" to merchantSales.values.filter { initial.merchantSales[it.id] != it },
                "merchant_sales_removed" to initial.merchantSales.values.filter { it.id !in merchantSales || it.expires <= clock() })
        )
        val auctionRules get() = settings.auction
        fun marketTime() = clock()
        fun ownsAuction(listing: AuctionListing) = listing.account == account || listing.actor == actor ||
            relatedAuctionAccounts(account, listing.account)
        fun bazaarProduct(key: String) = settings.products[key]?.bazaar == true
        private var sequence = initial.sequence

        fun claim(owner: UUID, product: String?, amount: Long, source: String): UUID? {
            if (amount == 0L) return null
            require(amount > 0)
            val id = UUID.randomUUID(); claims[id] = MarketClaim(id, owner, product, amount, source); return id
        }
        internal fun debit(amount: Long) { if (amount < 0 || coins < amount) fail("coins"); coins -= amount }
        internal fun credit(amount: Long) { coins = Math.addExact(coins, amount) }
        fun expire() {
            if (auditOperation == "MARKET_TRANSACTION") begin("EXPIRE")
            expireAuctions()
            merchantSales.values.filter { it.expires <= clock() }.map { it.id }.forEach(merchantSales::remove)
            orders.values.filter { it.status == OrderStatus.OPEN && it.expires <= clock() }.toList().forEach { closeOrder(it, OrderStatus.EXPIRED) }
        }
        private fun closeOrder(order: MarketOrder, status: OrderStatus) {
            if (order.side == OrderSide.BUY) claim(order.account, null, total(order.price, order.remaining), "refund:${order.id}")
            else claim(order.account, order.product, order.remaining.toLong(), "return:${order.id}")
            orders[order.id] = order.copy(remaining = 0, status = status)
        }
        fun cancel(id: UUID) {
            begin("BAZAAR_CANCEL", mapOf("order" to id), id)
            expire()
            val order = orders[id] ?: fail("order")
            if (order.account != account) fail("order")
            if (order.status == OrderStatus.EXPIRED) return // Expiry already returned exactly the remaining escrow.
            if (order.status != OrderStatus.OPEN) fail("order")
            closeOrder(order, OrderStatus.CANCELLED)
        }
        fun collectCoins(id: UUID) {
            begin("COLLECT_COINS", mapOf("claim" to id), id)
            val claim = claims[id] ?: fail("claim")
            if (claim.account != account || claim.product != null || claim.recipient != null && claim.recipient != actor) fail("claim")
            credit(claim.amount); claims.remove(id)
        }
        fun collectAllCoins() {
            begin("COLLECT_ALL_COINS")
            val available = claims.values.filter { it.account == account && it.product == null && (it.recipient == null || it.recipient == actor) }
            val total = available.fold(0L) { value, claim -> Math.addExact(value, claim.amount) }
            credit(total)
            available.forEach { claims.remove(it.id) }
        }
        private fun shopSell(key: String, amount: Int, agreedPrice: Long, eligible: Boolean = false) {
            val item = product(key); if (!item.shop) fail("product")
            // Goods leave circulation. Merchant never creates offers or supplies Bazaar purchases.
            credit(total(agreedPrice, amount))
            val id = UUID.randomUUID()
            merchantSales[id] = MerchantSale(id, account, actor, key, amount, agreedPrice, clock(),
                Math.addExact(clock(), Math.multiplyExact(settings.buybackSeconds, 1000)), nextSequence(),eligible)
        }
        private fun newOrder(key: String, side: OrderSide, amount: Int, price: Long): MarketOrder {
            quantity(amount); val item = product(key); if (!item.bazaar) fail("product")
            if (price !in item.minOrderPrice..item.maxOrderPrice) fail("price")
            sequence = Math.addExact(sequence, 1)
            return MarketOrder(UUID.randomUUID(), account, actor, key, side, price, amount, amount, sequence,
                Math.addExact(clock(), Math.multiplyExact(settings.expiryHours, 3_600_000L)), created = clock())
        }
        private fun checkOrderLimit() {
            if (orders.values.count { it.account == account && it.status == OrderStatus.OPEN } >= settings.maxOrders) fail("order-limit")
        }
        fun buyOrder(key: String, amount: Int, price: Long): UUID {
            begin("BAZAAR_BUY_ORDER", mapOf("product" to key, "quantity" to amount, "unit_price" to price))
            expire(); checkOrderLimit()
            val order = newOrder(key, OrderSide.BUY, amount, price)
            debit(total(price, amount)); match(order, instant = false); return order.id
        }
        fun instantBuy(key: String, amount: Int, maximumCost: Long = Long.MAX_VALUE): UUID {
            begin("BAZAAR_INSTANT_BUY", mapOf("product" to key, "quantity" to amount, "maximum_cost" to maximumCost))
            expire(); val item = product(key)
            val order = newOrder(key, OrderSide.BUY, amount, item.maxOrderPrice)
            val offers = candidates(order)
            var left = amount; var cost = 0L
            offers.forEach { offer -> val count = minOf(left, offer.remaining); cost = Math.addExact(cost, total(offer.price, count)); left -= count }
            if (left > 0) fail("liquidity")
            if (cost > maximumCost) fail("price")
            debit(cost)
            match(order, instant = true) // Exact maker prices were charged, so there is no limit-price refund.
            return order.id
        }
        private fun sell(key: String, amount: Int, price: Long, instant: Boolean, requireFull: Boolean = false) {
            expire(); if (!instant) checkOrderLimit()
            val order = newOrder(key, OrderSide.SELL, amount, if (instant) product(key).minOrderPrice else price)
            match(order, instant, requireFull)
        }
        internal fun sellClaim(key: String, amount: Int, price: Long) = sell(key, amount, price, false)
        private fun candidates(order: MarketOrder): List<MarketOrder> = orders.values.filter {
            it.status == OrderStatus.OPEN && it.account != account && it.product == order.product && it.side != order.side &&
                if (order.side == OrderSide.BUY) it.price <= order.price else it.price >= order.price
        }.sortedWith(if (order.side == OrderSide.BUY) compareBy<MarketOrder> { it.price }.thenBy { it.sequence }
            else compareByDescending<MarketOrder> { it.price }.thenBy { it.sequence })

        private fun match(order: MarketOrder, instant: Boolean, requireFull: Boolean = false) {
            var left = order.remaining
            for (maker in candidates(order)) {
                if (left == 0) break
                val count = minOf(left, maker.remaining)
                val gross = total(maker.price, count); val fee = tax(gross)
                val buyer = if (order.side == OrderSide.BUY) account else maker.account
                val seller = if (order.side == OrderSide.SELL) account else maker.account
                claim(buyer, order.product, count.toLong(), "trade:${order.id}")
                if (seller == account) credit(gross - fee) else claim(seller, null, gross - fee, "trade:${order.id}")
                if (order.side == OrderSide.BUY && !instant) claim(account, null, total(order.price - maker.price, count), "price-improvement:${order.id}")
                val remaining = maker.remaining - count
                orders[maker.id] = maker.copy(remaining = remaining, status = if (remaining == 0) OrderStatus.FILLED else OrderStatus.OPEN)
                val tradeId = UUID.randomUUID()
                trades[tradeId] = MarketTrade(tradeId, buyer, seller, order.product, count, maker.price, fee, clock(),
                    buyActor = if (order.side == OrderSide.BUY) actor else maker.actor,
                    sellActor = if (order.side == OrderSide.SELL) actor else maker.actor)
                left -= count
            }
            if (instant && left > 0) {
                if (order.side == OrderSide.BUY || requireFull) fail("liquidity") // The caller discards this transaction on failure.
                claim(account, order.product, left.toLong(), "unsold:${order.id}")
            }
            orders[order.id] = order.copy(remaining = if (instant) 0 else left,
                status = if (left == 0) OrderStatus.FILLED else if (instant) OrderStatus.CANCELLED else OrderStatus.OPEN)
        }

        fun prepare(transfer: ItemTransfer) {
            require(transfer.kind != TransferKind.RELEASE) { "Release operations use the account ledger" }
            begin(transfer.kind.name, mapOf("transfer" to transfer), transfer.id, "PREPARED", null)
            if (transfer.kind in setOf(TransferKind.SHOP_SELL, TransferKind.SHOP_SELL_BATCH, TransferKind.SHOP_BUYBACK, TransferKind.INSTANT_SELL_BATCH)) {
                if (transfer.quantity !in 1..71680) fail("quantity")
            } else if (transfer.kind == TransferKind.AUCTION_LIST || transfer.itemData != null) {
                if (transfer.quantity !in 1..64) fail("quantity")
            } else quantity(transfer.quantity)
            if (transfer.account != account || transfer.actor != actor || transfers.containsKey(transfer.id)) fail("transfer")
            if (transfers.values.any { it.actor == actor && it.status in setOf(TransferStatus.PREPARED, TransferStatus.REVIEW) }) fail("busy")
            if (transfer.kind == TransferKind.WITHDRAW) {
                val held = claims[transfer.claimId] ?: fail("claim")
                if (held.account != account || held.product != transfer.product || held.amount < transfer.quantity || held.recipient != null && held.recipient != actor) fail("claim")
                if (held.itemData != transfer.itemData || (held.itemData != null && held.amount != transfer.quantity.toLong())) fail("claim")
                if (held.recipient != transfer.recipient) fail("claim")
                if(transfer.originSource!=null && transfer.originSource!=held.source)fail("claim")
                val remaining = held.amount - transfer.quantity
                if (remaining == 0L) claims.remove(held.id) else claims[held.id] = held.copy(amount = remaining)
            } else if (transfer.kind == TransferKind.SHOP_BUYBACK) {
                val sale = transfer.buyback ?: fail("buyback")
                if (merchantSales[sale.id] != sale || sale.account != account || sale.actor != actor || sale.expires <= clock() ||
                    sale.product != transfer.product || sale.quantity != transfer.quantity || sale.unitPrice != transfer.price) fail("buyback")
                debit(sale.total()); merchantSales.remove(sale.id)
            } else if (transfer.kind == TransferKind.INSTANT_SELL_BATCH) {
                expire()
                val lines = transfer.bazaarLines ?: fail("items")
                if (lines.isEmpty() || lines.size > 35 || lines.map { it.product }.distinct().size != lines.size ||
                    lines.sumOf { it.quantity.toLong() } != transfer.quantity.toLong()) fail("items")
                lines.forEach { line ->
                    if (!product(line.product).bazaar || line.minimumProceeds < 0) fail("product")
                    val quoted = quote(state(), account, line.product, OrderSide.SELL, line.quantity)
                    if (quoted.quantity != line.quantity || quoted.net < line.minimumProceeds) fail("liquidity")
                }
            } else if (transfer.kind == TransferKind.SHOP_SELL_BATCH) {
                val lines = transfer.merchantLines ?: fail("items")
                if (lines.isEmpty() || lines.size > 35 || lines.map { it.product }.distinct().size != lines.size ||
                    lines.sumOf { it.quantity.toLong() } != transfer.quantity.toLong()) fail("items")
                lines.forEach { line ->
                    val product = product(line.product)
                    if (line.quantity !in 1..71680 || !product.shop || line.unitPrice != product.sellPrice) fail("price")
                }
            } else if (transfer.kind == TransferKind.AUCTION_LIST) {
                expire()
                val draft = transfer.auction ?: fail("auction-item")
                if (draft.itemData != transfer.itemData || draft.product != transfer.product || draft.amount != transfer.quantity) fail("auction-item")
                validateAuction(draft)
                debit(draft.fee)
            } else {
                val item = product(transfer.product)
                if (transfer.kind == TransferKind.SHOP_SELL && !item.shop || transfer.kind != TransferKind.SHOP_SELL && !item.bazaar) fail("product")
                if (transfer.kind == TransferKind.SHOP_SELL && transfer.price != item.sellPrice) fail("price")
                if (transfer.kind == TransferKind.SELL_ORDER) { expire(); checkOrderLimit() }
                if (transfer.kind == TransferKind.INSTANT_SELL && transfer.minimumProceeds > 0) {
                    val quote = quote(state(), account, transfer.product, OrderSide.SELL, transfer.quantity)
                    if (quote.quantity < transfer.quantity || quote.net < transfer.minimumProceeds) fail("liquidity")
                }
                if (transfer.kind == TransferKind.SELL_ORDER && transfer.price !in item.minOrderPrice..item.maxOrderPrice) fail("price")
            }
            transfers[transfer.id] = transfer
        }
        fun finish(id: UUID) {
            begin("TRANSFER_FINISH", mapOf("transfer_id" to id), id)
            val transfer = transfers[id] ?: fail("transfer")
            require(transfer.kind != TransferKind.RELEASE) { "Release operations use the account ledger" }
            begin(transfer.kind.name, mapOf("transfer" to transfer), id)
            if (transfer.account != account) fail("transfer")
            if (transfer.status == TransferStatus.DONE) return
            if (transfer.status !in setOf(TransferStatus.PREPARED, TransferStatus.REVIEW)) fail("transfer")
            if (transfer.kind !in setOf(TransferKind.WITHDRAW, TransferKind.SHOP_BUYBACK)) {
                // Try in a separate immutable transaction: failed matching/sales cannot leak partial fills.
                val tentative = Transaction(account, actor, coins, state())
                try {
                    when (transfer.kind) {
                        TransferKind.SHOP_SELL -> tentative.shopSell(transfer.product, transfer.quantity, transfer.price,transfer.releaseEligible)
                        TransferKind.INSTANT_SELL -> {
                            val quote = quote(tentative.state(), account, transfer.product, OrderSide.SELL, transfer.quantity)
                            if (quote.quantity < transfer.quantity || quote.net < transfer.minimumProceeds) fail("liquidity")
                            val beforeCoins = tentative.coins
                            tentative.sell(transfer.product, transfer.quantity, 0, true, requireFull = true)
                            if (tentative.coins - beforeCoins < transfer.minimumProceeds) fail("liquidity")
                        }
                        TransferKind.INSTANT_SELL_BATCH -> requireNotNull(transfer.bazaarLines).forEach { line ->
                            val quoted = quote(tentative.state(), account, line.product, OrderSide.SELL, line.quantity)
                            if (quoted.quantity != line.quantity || quoted.net < line.minimumProceeds) fail("liquidity")
                            val beforeCoins = tentative.coins
                            tentative.sell(line.product, line.quantity, 0, true, requireFull = true)
                            if (tentative.coins - beforeCoins < line.minimumProceeds) fail("liquidity")
                        }
                        TransferKind.SELL_ORDER -> tentative.sell(transfer.product, transfer.quantity, transfer.price, false)
                        TransferKind.AUCTION_LIST -> tentative.createAuction(transfer)
                        TransferKind.SHOP_SELL_BATCH -> requireNotNull(transfer.merchantLines).forEach { tentative.shopSell(it.product, it.quantity, it.unitPrice,transfer.releaseEligible) }
                        TransferKind.WITHDRAW, TransferKind.SHOP_BUYBACK, TransferKind.RELEASE -> error("Transfer has no sale to finalize")
                    }
                    coins = tentative.coins
                    orders.clear(); orders.putAll(tentative.orders); claims.clear(); claims.putAll(tentative.claims)
                    stock.clear(); stock.putAll(tentative.stock); trades.clear(); trades.putAll(tentative.trades)
                    auctions.clear(); auctions.putAll(tentative.auctions)
                    merchantSales.clear(); merchantSales.putAll(tentative.merchantSales)
                    sequence = tentative.sequence
                } catch (e: IllegalArgumentException) {
                    rejected((e as? MarketException)?.reason ?: "invalid")
                    returnTransfer(transfer, "unexecuted:$id")
                } catch (_: ArithmeticException) {
                    rejected("overflow")
                    returnTransfer(transfer, "overflow:$id")
                }
            }
            transfers[id] = transfer.copy(status = TransferStatus.DONE, itemData = null, auction = null, merchantLines = null, buyback = null, bazaarLines = null)
        }
        fun abort(id: UUID) {
            begin("TRANSFER_ABORT", mapOf("transfer_id" to id), id, "ABORTED", false)
            val transfer = transfers[id] ?: fail("transfer")
            require(transfer.kind != TransferKind.RELEASE) { "Release operations use the account ledger" }
            if (transfer.account != account || transfer.status !in setOf(TransferStatus.PREPARED, TransferStatus.REVIEW)) fail("transfer")
            if (transfer.kind == TransferKind.WITHDRAW) returnTransfer(transfer, "aborted:$id")
            if (transfer.kind == TransferKind.AUCTION_LIST) claim(account, null, requireNotNull(transfer.auction).fee, "auction-fee-refund:$id")
            if (transfer.kind == TransferKind.SHOP_BUYBACK) {
                val sale = requireNotNull(transfer.buyback)
                if (sale.expires > clock()) merchantSales[sale.id] = sale
                claim(account, null, sale.total(), "buyback-refund:$id")
            }
            transfers[id] = transfer.copy(status = TransferStatus.ABORTED, itemData = null, auction = null, merchantLines = null, buyback = null, bazaarLines = null)
        }
        private fun returnTransfer(transfer: ItemTransfer, source: String) {
            if (transfer.kind == TransferKind.INSTANT_SELL_BATCH) {
                requireNotNull(transfer.bazaarLines).forEach { claim(account, it.product, it.quantity.toLong(), source) }
                return
            }
            if (transfer.kind == TransferKind.SHOP_SELL_BATCH) {
                requireNotNull(transfer.merchantLines).forEach { claim(account, it.product, it.quantity.toLong(), source) }
                return
            }
            val id = UUID.randomUUID()
            val bound=transfer.originSource?.takeIf {it.startsWith("chest:") || it.startsWith("combat:") || it.startsWith("release:")}
            val provenance=bound?.let {it.substringBefore(":return:")+":return:${transfer.id}"}?:source
            claims[id] = MarketClaim(id, account, transfer.product, transfer.quantity.toLong(), provenance, transfer.itemData, transfer.recipient)
            if (transfer.kind == TransferKind.AUCTION_LIST) claim(account, null, requireNotNull(transfer.auction).fee, "auction-fee-refund:${transfer.id}")
        }
        fun review(id: UUID) {
            begin("TRANSFER_REVIEW", mapOf("transfer_id" to id), id, "REVIEW", null)
            val transfer = transfers[id] ?: fail("transfer")
            if (transfer.account != account || transfer.status != TransferStatus.PREPARED) fail("transfer")
            transfers[id] = transfer.copy(status = TransferStatus.REVIEW)
        }
        fun state(): MarketState = MarketState(
            (orders.values.filter { it.status == OrderStatus.OPEN } + orders.values.filter { it.status != OrderStatus.OPEN }.sortedByDescending { it.sequence }.take(settings.historyLimit)).associateBy { it.id },
            claims.toMap(),
            (transfers.values.filter { it.status in setOf(TransferStatus.PREPARED, TransferStatus.REVIEW) } +
                transfers.values.filter { it.status !in setOf(TransferStatus.PREPARED, TransferStatus.REVIEW) }.sortedByDescending { it.time }.take(settings.historyLimit)).associateBy { it.id },
            trades.values.sortedByDescending { it.time }.take(settings.historyLimit).associateBy { it.id }, stock.toMap(), sequence,
            (auctions.values.filter { it.status == AuctionStatus.OPEN } + auctions.values.filter { it.status != AuctionStatus.OPEN }
                .sortedByDescending { it.created }.take(settings.historyLimit)).associateBy { it.id },
            merchantSales.filterValues { it.expires > clock() }, initial.playerTrades,initial.combatReceipts,initial.chests,initial.chestProgress)
        fun <T> result(value: T) = MarketChange(coins, state(), value, audit())
    }
    fun <T> transaction(account: UUID, actor: UUID, coins: Long, state: MarketState, action: Transaction.() -> T): MarketChange<T> {
        val transaction = Transaction(account, actor, coins, state)
        try { return transaction.result(transaction.action()) }
        catch (error: MarketException) { error.audit = transaction.audit(error.reason); throw error }
    }
}
