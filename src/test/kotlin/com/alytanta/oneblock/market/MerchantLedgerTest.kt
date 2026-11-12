package com.alytanta.oneblock.market

import org.bukkit.configuration.file.YamlConfiguration
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.util.UUID

class MerchantLedgerTest {
    private class Book {
        val owner = UUID.randomUUID()
        var time = 1000L
        var coins = 0L
        var state = MarketState()
        var engine = MarketEngine(MarketSettings.load(YamlConfiguration().apply {
            set("market.products.DIAMOND.sell-price", 100)
            set("market.products.OAK_LOG.sell-price", 4)
        })) { time }
        fun <T> run(actor: UUID = owner, action: MarketEngine.Transaction.() -> T): MarketChange<T> {
            return engine.transaction(owner, actor, coins, state, action).also { coins = it.coins; state = it.state }
        }
        fun sell(lines: List<MerchantLine> = listOf(MerchantLine("DIAMOND", 2, 100), MerchantLine("OAK_LOG", 3, 4))): UUID {
            val transfer = ItemTransfer(UUID.randomUUID(), owner, owner, "BASKET", lines.sumOf { it.quantity }, TransferKind.SHOP_SELL_BATCH,
                0, null, "before", "after", merchantLines = lines)
            run { prepare(transfer) }; run { finish(transfer.id) }; return transfer.id
        }
        fun withdrawal(sale: MerchantSale) = ItemTransfer(UUID.randomUUID(), owner, owner, sale.product, sale.quantity,
            TransferKind.SHOP_BUYBACK, sale.unitPrice, null, "before", "after", buyback = sale)
    }
    @Test fun `basket sale credits exact sum once and records each resource at original price`() {
        val b = Book(); val id = b.sell()
        assertEquals(212L, b.coins); assertEquals(2, b.state.merchantSales.size)
        val ordered = b.state.merchantSales.values.sortedByDescending { it.sequence }
        assertEquals("OAK_LOG", ordered.first().product)
        assertTrue(ordered.all { it.expires == 901000L && it.actor == b.owner })
        b.run { finish(id) }; assertEquals(212L, b.coins); assertEquals(2, b.state.merchantSales.size)
    }
    @Test fun `single product sale is also available for buyback`() {
        val b = Book()
        val t = ItemTransfer(UUID.randomUUID(), b.owner, b.owner, "DIAMOND", 7, TransferKind.SHOP_SELL, 100, null, "b", "a")
        b.run { prepare(t); finish(t.id) }
        assertEquals(700L, b.coins); assertEquals(7, b.state.merchantSales.values.single().quantity)
    }
    @Test fun `buyback reserves receipt and money once then delivers through the journal`() {
        val b = Book(); b.sell()
        val sale = b.state.merchantSales.values.single { it.product == "DIAMOND" }
        val t = b.withdrawal(sale)
        val prepared = b.run { prepare(t) }
        assertNull(prepared.audit!!.success); assertEquals("PREPARED", prepared.audit.status)
        assertEquals(12L, b.coins); assertFalse(b.state.merchantSales.containsKey(sale.id))
        val done = b.run { finish(t.id) }; assertEquals(true, done.audit!!.success)
        assertTrue(b.state.claims.isEmpty())
        assertThrows(MarketException::class.java) { b.run { prepare(b.withdrawal(sale)) } }
        b.run { finish(t.id) }; assertEquals(12L, b.coins)
    }
    @Test fun `not enough money does not consume receipt and has a failed audit with coins reason`() {
        val b = Book(); b.sell(); b.coins = 0
        val sale = b.state.merchantSales.values.first(); val before = b.state
        val error = assertThrows(MarketException::class.java) { b.run { prepare(b.withdrawal(sale)) } }
        assertEquals(before, b.state); assertEquals(0L, b.coins)
        assertEquals(false, error.audit!!.success); assertEquals("coins", error.audit!!.reason)
        assertEquals("SHOP_BUYBACK", error.audit!!.operation)
    }
    @Test fun `receipt price survives config price change and product removal`() {
        val b = Book(); b.sell()
        val sale = b.state.merchantSales.values.single { it.product == "DIAMOND" }
        b.engine = MarketEngine(b.engine.settings.copy(products = emptyMap(), buybackSeconds = 1)) { b.time }
        val t = b.withdrawal(sale); b.run { prepare(t); finish(t.id) }
        assertEquals(12L, b.coins); assertEquals(1, b.state.merchantSales.size)
    }
    @Test fun `exact fifteen minute deadline prevents new buyback and clears history`() {
        val b = Book(); b.sell(); val sale = b.state.merchantSales.values.first()
        b.time = sale.expires
        assertThrows(MarketException::class.java) { b.run { prepare(b.withdrawal(sale)) } }
        b.run { expire() }; assertTrue(b.state.merchantSales.isEmpty()); assertEquals(212L, b.coins)
    }
    @Test fun `buyback already prepared before expiry can finish after expiry`() {
        val b = Book(); b.sell(); val sale = b.state.merchantSales.values.first(); val t = b.withdrawal(sale)
        b.time = sale.expires - 1; b.run { prepare(t) }
        b.time += 10000; b.run { expire(); finish(t.id) }
        assertEquals(TransferStatus.DONE, b.state.transfers.getValue(t.id).status)
        assertEquals(212L - sale.total(), b.coins)
    }
    @Test fun `unchanged inventory abort refunds via claim and restores unexpired receipt once`() {
        val b = Book(); b.sell(); val sale = b.state.merchantSales.values.first(); val t = b.withdrawal(sale)
        b.run { prepare(t); abort(t.id) }
        assertEquals(sale, b.state.merchantSales[sale.id])
        val refund = b.state.claims.values.single(); assertNull(refund.product); assertEquals(sale.total(), refund.amount)
        assertThrows(MarketException::class.java) { b.run { abort(t.id) } }
        b.run { collectCoins(refund.id) }; assertEquals(212L, b.coins)
    }
    @Test fun `expired receipt is not resurrected on aborted buyback but payment is returned`() {
        val b = Book(); b.sell(); val sale = b.state.merchantSales.values.first(); val t = b.withdrawal(sale)
        b.run { prepare(t) }; b.time = sale.expires; b.run { abort(t.id) }
        assertFalse(b.state.merchantSales.containsKey(sale.id))
        assertEquals(sale.total(), b.state.claims.values.single().amount)
    }
    @Test fun `another coop member cannot buy back somebody else's personal sale`() {
        val b = Book(); b.sell(); val sale = b.state.merchantSales.values.first()
        val actor = UUID.randomUUID(); val t = b.withdrawal(sale).copy(actor = actor)
        assertThrows(MarketException::class.java) { b.run(actor) { prepare(t) } }
        assertEquals(212L, b.coins); assertTrue(b.state.merchantSales.containsKey(sale.id))
    }
    @Test fun `reload disabling one basket product returns every resource and pays nothing`() {
        val b = Book(); val lines = listOf(MerchantLine("DIAMOND", 2, 100), MerchantLine("OAK_LOG", 3, 4))
        val t = ItemTransfer(UUID.randomUUID(), b.owner, b.owner, "BASKET", 5, TransferKind.SHOP_SELL_BATCH, 0, null, "b", "a", merchantLines = lines)
        b.run { prepare(t) }
        b.engine = MarketEngine(b.engine.settings.copy(products = b.engine.settings.products - "OAK_LOG")) { b.time }
        val result = b.run { finish(t.id) }
        assertEquals(0L, b.coins); assertTrue(b.state.merchantSales.isEmpty()); assertEquals(2, b.state.claims.size)
        assertEquals(false, result.audit!!.success); assertEquals("product", result.audit.reason)
    }
    @Test fun `merchant sell all is independent from Bazaar per-order quantity limit`() {
        val b = Book(); b.engine = MarketEngine(b.engine.settings.copy(maxQuantity = 1, quantities = listOf(1))) { b.time }
        b.sell(listOf(MerchantLine("DIAMOND", 2240, 100)))
        assertEquals(224000L, b.coins)
        val sale = b.state.merchantSales.values.single(); val t = b.withdrawal(sale)
        b.run { prepare(t); finish(t.id) }; assertEquals(0L, b.coins)
    }
}
