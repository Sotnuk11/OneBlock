package com.alytanta.oneblock.market

import org.bukkit.Material
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.util.UUID

class MarketBookTest {
    private val owner = UUID.randomUUID()
    private fun order(side: OrderSide, price: Long, sequence: Long, remaining: Int = 10, expires: Long = 10000,
        status: OrderStatus = OrderStatus.OPEN) = MarketOrder(UUID.randomUUID(), owner, owner, "OAK_LOG", side, price, 10, remaining, sequence, expires, status)

    @Test fun `book lists every live order in price then FIFO order and omits expired empty and closed orders`() {
        val buyA = order(OrderSide.BUY, 8, 2); val buyB = order(OrderSide.BUY, 8, 1); val buyC = order(OrderSide.BUY, 4, 3)
        val sellA = order(OrderSide.SELL, 12, 4); val sellB = order(OrderSide.SELL, 10, 5)
        val ignored = listOf(order(OrderSide.BUY, 100, 6, expires = 1000), order(OrderSide.SELL, 1, 7, remaining = 0),
            order(OrderSide.SELL, 2, 8, status = OrderStatus.CANCELLED))
        val all = listOf(buyA, buyB, buyC, sellA, sellB) + ignored
        val book = MarketBook(MarketState(orders = all.associateBy { it.id }), 1000)
        assertEquals(listOf(buyB, buyA, buyC), book.orders("OAK_LOG", OrderSide.BUY))
        assertEquals(listOf(sellB, sellA), book.orders("OAK_LOG", OrderSide.SELL))
        assertEquals(8L, book.best("OAK_LOG", OrderSide.BUY))
        assertNull(book.best("OAK_LOG", OrderSide.BUY, owner))
        assertTrue(book.orders("DIAMOND", OrderSide.SELL).isEmpty())
    }

    @Test fun `whole coin price suggestions use both sides and enforce configured bounds`() {
        val all = listOf(order(OrderSide.BUY, 100, 1), order(OrderSide.SELL, 200, 2))
        val book = MarketBook(MarketState(orders = all.associateBy { it.id }), 1000)
        val product = MarketProduct(Material.OAK_LOG, 4, 1, 1000, true, true)
        assertEquals(100L, book.suggest(product, OrderSide.BUY, MarketBook.Suggestion.SAME, 1, 500))
        assertEquals(101L, book.suggest(product, OrderSide.BUY, MarketBook.Suggestion.COMPETITIVE, 1, 500))
        assertEquals(199L, book.suggest(product, OrderSide.SELL, MarketBook.Suggestion.COMPETITIVE, 1, 1000))
        assertEquals(105L, book.suggest(product, OrderSide.BUY, MarketBook.Suggestion.SPREAD, 1, 500))
        assertEquals(190L, book.suggest(product, OrderSide.SELL, MarketBook.Suggestion.SPREAD, 1, 1000))
        assertNull(book.suggest(product.copy(maxOrderPrice = 100), OrderSide.BUY, MarketBook.Suggestion.COMPETITIVE, 1, 500))
        assertNull(MarketBook(MarketState(), 1000).suggest(product, OrderSide.BUY, MarketBook.Suggestion.SAME, 1, 500))
    }
}
