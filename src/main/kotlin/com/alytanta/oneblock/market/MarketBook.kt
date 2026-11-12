package com.alytanta.oneblock.market

/** Read-only book shared by tooltips, depth pages and price choices. Expired/empty orders never appear. */
class MarketBook(state: MarketState, now: Long = System.currentTimeMillis()) {
    private val products = state.orders.values.filter { it.status == OrderStatus.OPEN && it.remaining > 0 && it.expires > now }
        .groupBy { it.product }

    fun orders(product: String, side: OrderSide): List<MarketOrder> = products[product].orEmpty().filter { it.side == side }
        .sortedWith(if (side == OrderSide.BUY) compareByDescending<MarketOrder> { it.price }.thenBy { it.sequence }
            else compareBy<MarketOrder> { it.price }.thenBy { it.sequence })

    fun best(product: String, side: OrderSide, excludeAccount: java.util.UUID? = null): Long? =
        orders(product, side).firstOrNull { it.account != excludeAccount }?.price

    enum class Suggestion { SAME, COMPETITIVE, SPREAD }

    fun suggest(product: MarketProduct, side: OrderSide, choice: Suggestion, step: Long, spreadBps: Int): Long? {
        val key = product.material.name
        val price = best(key, side) ?: return null
        val proposed = when (choice) {
            Suggestion.SAME -> price
            Suggestion.COMPETITIVE -> if (side == OrderSide.BUY) Math.addExact(price, step) else price - step
            Suggestion.SPREAD -> {
                val bid = best(key, OrderSide.BUY) ?: return null
                val ask = best(key, OrderSide.SELL) ?: return null
                if (ask <= bid) return null
                val delta = maxOf(1, (ask - bid) * spreadBps / 10000)
                if (side == OrderSide.BUY) bid + delta else ask - delta
            }
        }
        return proposed.takeIf { it in product.minOrderPrice..product.maxOrderPrice }
    }
}
