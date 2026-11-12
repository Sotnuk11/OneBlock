package com.alytanta.oneblock.market

import org.bukkit.configuration.file.YamlConfiguration
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.util.UUID

class MarketEngineTest {
    @Test fun `removing a configured product preserves cancellation refunds and item collection`() {
        val owner = UUID.randomUUID()
        val other = UUID.randomUUID()
        val book = Book(engine())
        book.balances[other] = 1000
        book.deposit(owner, 10, TransferKind.SELL_ORDER, 8)
        val sell = book.state.orders.values.single().id
        val buy = book.run(other) { buyOrder("OAK_LOG", 10, 5) }
        val removed = MarketEngine(MarketSettings.load(YamlConfiguration())) { 1000 }
        var state = removed.transaction(owner, owner, 0, book.state) { cancel(sell) }.state
        val refund = removed.transaction(other, other, book.balances.getValue(other), state) { cancel(buy) }
        state = refund.state
        val coins = state.claims.values.single { it.account == other && it.product == null }
        assertEquals(50L, coins.amount)
        assertEquals(1000L, removed.transaction(other, other, refund.coins, state) { collectCoins(coins.id) }.coins)
        val items = state.claims.values.single { it.account == owner && it.product == "OAK_LOG" }
        val id = UUID.randomUUID()
        val withdrawal = removed.transaction(owner, owner, 0, state) {
            prepare(ItemTransfer(id, owner, owner, "OAK_LOG", 10, TransferKind.WITHDRAW, 0, items.id, "before", "after"))
            finish(id)
        }
        assertFalse(withdrawal.state.claims.containsKey(items.id))
        assertEquals(TransferStatus.DONE, withdrawal.state.transfers.getValue(id).status)
        assertThrows(MarketException::class.java) { removed.transaction(owner, owner, 1000, state) { buyOrder("OAK_LOG", 1, 5) } }
    }

    private fun engine(tax: Int = 0): MarketEngine = MarketEngine(MarketSettings.load(YamlConfiguration().apply {
        set("market.sell-tax-bps", tax)
        set("market.products.OAK_LOG.sell-price", 4)
    })) { 1000 }
    private class Book(val engine: MarketEngine) {
        var state = MarketState()
        val balances = mutableMapOf<UUID, Long>()
        fun <T> run(account: UUID, action: MarketEngine.Transaction.() -> T): T {
            val change = engine.transaction(account, account, balances[account] ?: 0, state, action)
            state = change.state; balances[account] = change.coins; return change.result
        }
        fun deposit(account: UUID, amount: Int, kind: TransferKind, price: Long): UUID {
            val id = UUID.randomUUID()
            run(account) { prepare(ItemTransfer(id, account, account, "OAK_LOG", amount, kind, price, null, "before", "after")); finish(id) }
            return id
        }
    }
    @Test fun `sell offer partial fill cancellation and one time proceeds conserve items and coins`() {
        val book = Book(engine()); val seller = UUID.randomUUID(); val buyer = UUID.randomUUID()
        book.balances[buyer] = 1000
        book.deposit(seller, 100, TransferKind.SELL_ORDER, 8)
        book.run(buyer) { instantBuy("OAK_LOG", 40) }
        assertEquals(680, book.balances[buyer])
        val offer = book.state.orders.values.first { it.account == seller }
        assertEquals(60, offer.remaining)
        book.run(seller) { cancel(offer.id) }
        assertEquals(60, book.state.claims.values.filter { it.account == seller && it.product != null }.sumOf { it.amount })
        assertEquals(40, book.state.claims.values.filter { it.account == buyer && it.product != null }.sumOf { it.amount })
        val proceeds = book.state.claims.values.single { it.account == seller && it.product == null }
        assertEquals(320, proceeds.amount)
        book.run(seller) { collectCoins(proceeds.id) }
        assertEquals(320, book.balances[seller])
        assertThrows(MarketException::class.java) { book.run(seller) { collectCoins(proceeds.id) } }
        assertThrows(MarketException::class.java) { book.run(seller) { cancel(offer.id) } }
    }
    @Test fun `buy order reserves funds fills from instant sell and refunds unspent escrow`() {
        val book = Book(engine()); val buyer = UUID.randomUUID(); val seller = UUID.randomUUID()
        book.balances[buyer] = 1000
        val order = book.run(buyer) { buyOrder("OAK_LOG", 100, 5) }
        assertEquals(500, book.balances[buyer])
        book.deposit(seller, 40, TransferKind.INSTANT_SELL, 0)
        assertEquals(200, book.balances[seller])
        book.run(buyer) { cancel(order) }
        assertEquals(300, book.state.claims.values.filter { it.account == buyer && it.product == null }.sumOf { it.amount })
        assertEquals(40, book.state.claims.values.filter { it.account == buyer && it.product != null }.sumOf { it.amount })
    }
    @Test fun `best price and FIFO matching improve incoming limit price and charge tax`() {
        val book = Book(engine(125)); val sellerA = UUID.randomUUID(); val sellerB = UUID.randomUUID(); val buyer = UUID.randomUUID()
        book.balances[buyer] = 10000
        book.deposit(sellerA, 20, TransferKind.SELL_ORDER, 100)
        book.deposit(sellerB, 20, TransferKind.SELL_ORDER, 100)
        book.run(buyer) { buyOrder("OAK_LOG", 25, 110) }
        assertEquals(7250, book.balances[buyer])
        assertEquals(250, book.state.claims.values.filter { it.account == buyer && it.product == null }.sumOf { it.amount })
        assertEquals(1975, book.state.claims.values.filter { it.account == sellerA }.sumOf { it.amount })
        assertEquals(494, book.state.claims.values.filter { it.account == sellerB }.sumOf { it.amount })
        assertEquals(15, book.state.orders.values.single { it.account == sellerB }.remaining)
        assertEquals(31, book.state.trades.values.sumOf { it.fee })
    }
    @Test fun `insufficient liquidity stale quotes and self matching do not change balances or orders`() {
        val book = Book(engine()); val seller = UUID.randomUUID(); val buyer = UUID.randomUUID()
        book.balances[buyer] = 1000
        book.deposit(seller, 10, TransferKind.SELL_ORDER, 10)
        val prior = book.state
        assertThrows(MarketException::class.java) { book.run(buyer) { instantBuy("OAK_LOG", 11) } }
        assertThrows(MarketException::class.java) { book.run(buyer) { instantBuy("OAK_LOG", 10, 99) } }
        assertEquals(prior, book.state); assertEquals(1000, book.balances[buyer])
        book.balances[seller] = 1000
        assertThrows(MarketException::class.java) { book.run(seller) { instantBuy("OAK_LOG", 1) } }
    }
    @Test fun `merchant pays once for resources without creating stock offers or purchasable claims`() {
        val book = Book(engine()); val seller = UUID.randomUUID(); val buyer = UUID.randomUUID()
        book.balances[buyer] = 100
        val id = book.deposit(seller, 10, TransferKind.SHOP_SELL, 4)
        assertEquals(40, book.balances[seller])
        assertTrue(book.state.stock.isEmpty()); assertTrue(book.state.orders.isEmpty()); assertTrue(book.state.claims.isEmpty())
        book.run(seller) { finish(id) }; assertEquals(40, book.balances[seller])
        assertThrows(MarketException::class.java) { book.run(buyer) { instantBuy("OAK_LOG", 1) } }
        assertEquals(100, book.balances[buyer])
    }
    @Test fun `legacy stock cannot supply Bazaar or prevent further merchant sales`() {
        val book = Book(engine()); val seller = UUID.randomUUID(); val buyer = UUID.randomUUID()
        book.state = MarketState(stock = mapOf("OAK_LOG" to 1_000_000L))
        book.balances[buyer] = 100
        book.deposit(seller, 64, TransferKind.SHOP_SELL, 4)
        assertEquals(256, book.balances[seller])
        assertEquals(1_000_000L, book.state.stock["OAK_LOG"])
        assertThrows(MarketException::class.java) { book.run(buyer) { instantBuy("OAK_LOG", 1) } }
        assertEquals(100, book.balances[buyer])
        book.run(buyer) { buyOrder("OAK_LOG", 1, 10) }
        assertEquals(1, book.state.orders.values.single().remaining)
        assertTrue(book.state.claims.isEmpty())
    }
    @Test fun `disabling merchant during a prepared sale returns resources without paying coins`() {
        val original = engine(); val owner = UUID.randomUUID(); val id = UUID.randomUUID()
        val prepared = original.transaction(owner, owner, 72, MarketState()) {
            prepare(ItemTransfer(id, owner, owner, "OAK_LOG", 10, TransferKind.SHOP_SELL, 4, null, "b", "a"))
        }
        val disabled = MarketEngine(original.settings.copy(products = original.settings.products.mapValues { (_, item) -> item.copy(shop = false) }))
        val result = disabled.transaction(owner, owner, prepared.coins, prepared.state) { finish(id) }
        assertEquals(72, result.coins)
        assertEquals(10, result.state.claims.values.single().amount)
        assertEquals("unexecuted:$id", result.state.claims.values.single().source)
        assertTrue(result.state.orders.isEmpty()); assertTrue(result.state.stock.isEmpty())
    }
    @Test fun `withdraw reserves claim and abort restores exactly the reserved items`() {
        val book = Book(engine()); val account = UUID.randomUUID()
        val claimId = book.run(account) { claim(account, "OAK_LOG", 100, "test") }!!
        val id = UUID.randomUUID()
        book.run(account) { prepare(ItemTransfer(id, account, account, "OAK_LOG", 64, TransferKind.WITHDRAW, 0, claimId, "b", "a")) }
        assertEquals(36, book.state.claims.getValue(claimId).amount)
        assertThrows(MarketException::class.java) { book.run(account) { prepare(ItemTransfer(UUID.randomUUID(), account, account, "OAK_LOG", 1, TransferKind.WITHDRAW, 0, claimId, "b", "a")) } }
        book.run(account) { abort(id) }
        assertEquals(100, book.state.claims.values.sumOf { it.amount })
        assertThrows(MarketException::class.java) { book.run(account) { abort(id) } }
    }
    @Test fun `failed instant sell preserves deposited goods as a claim and review requires explicit resolution`() {
        val book = Book(engine()); val seller = UUID.randomUUID()
        val id = UUID.randomUUID()
        book.run(seller) { prepare(ItemTransfer(id, seller, seller, "OAK_LOG", 10, TransferKind.INSTANT_SELL, 0, null, "b", "a")); review(id) }
        assertEquals(TransferStatus.REVIEW, book.state.transfers.getValue(id).status)
        book.run(seller) { finish(id) }
        assertEquals(10, book.state.claims.values.sumOf { it.amount }); assertEquals(0, book.balances[seller])
    }
    @Test fun `expired orders refund their escrow and cannot match`() {
        var time = 0L
        val base = engine().settings.copy(expiryHours = 1)
        val book = Book(MarketEngine(base) { time }); val buyer = UUID.randomUUID(); book.balances[buyer] = 100
        val id = book.run(buyer) { buyOrder("OAK_LOG", 10, 5) }
        time = 3_600_001
        book.run(buyer) { expire() }
        assertEquals(OrderStatus.EXPIRED, book.state.orders.getValue(id).status)
        assertEquals(50, book.state.claims.values.sumOf { it.amount })
        book.run(buyer) { cancel(id) }; book.run(buyer) { expire() }; assertEquals(50, book.state.claims.values.sumOf { it.amount })
    }
    @Test fun `claims remain withdrawable after their product is removed from the catalog`() {
        val account = UUID.randomUUID(); val claim = MarketClaim(UUID.randomUUID(), account, "OAK_LOG", 10, "old order")
        val engine = MarketEngine(engine().settings.copy(products = emptyMap()))
        val id = UUID.randomUUID()
        val state = MarketState(claims = mapOf(claim.id to claim))
        val change = engine.transaction(account, account, 0, state) {
            prepare(ItemTransfer(id, account, account, "OAK_LOG", 10, TransferKind.WITHDRAW, 0, claim.id, "b", "a")); finish(id)
        }
        assertTrue(change.state.claims.isEmpty())
        assertEquals(TransferStatus.DONE, change.state.transfers.getValue(id).status)
    }
    @Test fun `unauthorized cancellation and collection cannot spend another islands escrow`() {
        val book = Book(engine()); val owner = UUID.randomUUID(); val other = UUID.randomUUID()
        book.balances[owner] = 100
        val id = book.run(owner) { buyOrder("OAK_LOG", 10, 5) }
        assertThrows(MarketException::class.java) { book.run(other) { cancel(id) } }
        book.run(owner) { cancel(id) }
        val claim = book.state.claims.values.single()
        assertThrows(MarketException::class.java) { book.run(other) { collectCoins(claim.id) } }
        assertEquals(50, claim.amount)
    }
}
