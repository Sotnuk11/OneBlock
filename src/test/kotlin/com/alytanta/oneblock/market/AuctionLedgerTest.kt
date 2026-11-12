package com.alytanta.oneblock.market

import org.bukkit.configuration.file.YamlConfiguration
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.util.UUID

class AuctionLedgerTest {
    @Test fun `same human cannot buy or bid from another profile and rejection preserves wallets and escrow`() {
        for (mode in AuctionMode.entries) {
            val b = Book(); val id = b.list(b.draft(mode))
            val before = b.state
            val error = assertThrows(MarketException::class.java) {
                b.engine.transaction(b.buyer, b.seller, 10000, b.state) {
                    if (mode == AuctionMode.BIN) buyAuction(id, 1000) else bidAuction(id, 1000)
                }
            }
            assertEquals("auction-self", error.reason)
            assertEquals(before, b.state)
            assertEquals(10000, b.balances[b.buyer])
        }
    }
    @Test fun `shared coop across seller and buyer profiles also blocks unrelated acting members`() {
        for (mode in AuctionMode.entries) {
            val b = Book(); val id = b.list(b.draft(mode)); val before = b.state
            b.engine.relatedAuctionAccounts = { first, second -> first == b.buyer && second == b.seller }
            val error = assertThrows(MarketException::class.java) {
                b.run(b.buyer) { if (mode == AuctionMode.BIN) buyAuction(id, 1000) else bidAuction(id, 1000) }
            }
            assertEquals("auction-self", error.reason); assertEquals(before, b.state)
        }
    }
    private class Book(rules: AuctionSettings = AuctionSettings()) {
        var time = 1000L
        var engine = MarketEngine(MarketSettings.load(YamlConfiguration()).copy(auction = rules)) { time }
        var state = MarketState()
        val seller = UUID.randomUUID()
        val buyer = UUID.randomUUID()
        val other = UUID.randomUUID()
        val balances = mutableMapOf(seller to 10000L, buyer to 10000L, other to 10000L)
        fun <T> run(account: UUID, block: MarketEngine.Transaction.() -> T): T {
            val changed = engine.transaction(account, account, balances.getValue(account), state, block)
            state = changed.state; balances[account] = changed.coins; return changed.result
        }
        fun draft(mode: AuctionMode = AuctionMode.BIN, price: Long = 1000) = AuctionDraft("encoded-exact-item", "DIAMOND_SWORD", 1,
            "Enchanted test sword", "WEAPONS", mode, price, 1, engine.settings.auction.fee(price), false)
        fun prepare(draft: AuctionDraft = draft()): UUID {
            val id = UUID.randomUUID()
            run(seller) { prepare(ItemTransfer(id, seller, seller, draft.product, draft.amount, TransferKind.AUCTION_LIST, 0, null,
                "before", "after", itemData = draft.itemData, auction = draft)) }
            return id
        }
        fun list(draft: AuctionDraft = draft()): UUID = prepare(draft).also { id -> run(seller) { finish(id) }; time += 20000 }
    }
    @Test fun `BIN charges once preserves item payload and credits taxed seller claim`() {
        val b = Book(); val id = b.list()
        b.run(b.buyer) { buyAuction(id, 1000) }
        assertEquals(9000, b.balances[b.buyer]); assertEquals(9990, b.balances[b.seller])
        val item = b.state.claims.values.single { it.itemData != null }
        assertEquals(b.buyer, item.account); assertEquals("encoded-exact-item", item.itemData)
        val money = b.state.claims.values.single { it.product == null }
        assertEquals(990, money.amount)
        assertEquals(AuctionStatus.SOLD, b.state.auctions.getValue(id).status)
        assertThrows(MarketException::class.java) { b.run(b.other) { buyAuction(id, 1000) } }
        b.run(b.seller) { collectCoins(money.id) }
        assertThrows(MarketException::class.java) { b.run(b.seller) { collectCoins(money.id) } }
    }
    @Test fun `seller and coop account cannot buy own BIN and purchase grace is enforced`() {
        val b = Book(); val id = b.prepare(); b.run(b.seller) { finish(id) }
        assertThrows(MarketException::class.java) { b.run(b.buyer) { buyAuction(id, 1000) } }
        b.time += 20000
        assertThrows(MarketException::class.java) { b.run(b.seller) { buyAuction(id, 1000) } }
        assertThrows(MarketException::class.java) { b.run(b.buyer) { buyAuction(id, 999) } }
        assertEquals(10000, b.balances[b.buyer])
    }
    @Test fun `outbids refund escrow and raising own bid debits only difference`() {
        val b = Book(); val id = b.list(b.draft(AuctionMode.BID))
        b.run(b.buyer) { bidAuction(id, 1000) }
        b.run(b.buyer) { bidAuction(id, 1150) }
        assertEquals(8850, b.balances[b.buyer]); assertTrue(b.state.claims.isEmpty())
        b.run(b.other) { bidAuction(id, 1400) }
        assertEquals(8600, b.balances[b.other])
        assertEquals(1150, b.state.claims.values.single().amount)
        assertEquals(b.buyer, b.state.claims.values.single().account)
        assertThrows(MarketException::class.java) { b.run(b.seller) { cancelAuction(id) } }
    }
    @Test fun `stale own bid confirmation never charges more after being outbid`() {
        val b = Book(); val id = b.list(b.draft(AuctionMode.BID))
        b.run(b.buyer) { bidAuction(id, 1000) }
        // Buyer confirms 2000, seeing a 1000 charge. Another player outbids before the click arrives.
        b.run(b.other) { bidAuction(id, 1400) }
        val before = b.state; val balance = b.balances.getValue(b.buyer)
        val error = assertThrows(MarketException::class.java) {
            b.run(b.buyer) { bidAuction(id, 2000, agreedCharge = 1000) }
        }
        assertEquals("auction-bid", error.reason); assertEquals(before, b.state)
        assertEquals(balance, b.balances[b.buyer])
        b.run(b.buyer) { bidAuction(id, 2000, agreedCharge = 2000) }
        assertEquals(balance - 2000, b.balances[b.buyer])
    }
    @Test fun `coop raising a high bid invalidates the old charge without changing escrow`() {
        val b = Book(); val id = b.list(b.draft(AuctionMode.BID))
        b.run(b.buyer) { bidAuction(id, 1000) }
        b.run(b.buyer) { bidAuction(id, 1400, agreedCharge = 400) }
        val before = b.state
        assertThrows(MarketException::class.java) { b.run(b.buyer) { bidAuction(id, 2000, agreedCharge = 1000) } }
        assertEquals(before, b.state)
        b.run(b.buyer) { bidAuction(id, 2000, agreedCharge = 600) }
        assertEquals(8000, b.balances[b.buyer]); assertTrue(b.state.claims.isEmpty())
    }
    @Test fun `BIN and BID reject the other purchase action without changing wallets or escrow`() {
        for (mode in AuctionMode.entries) {
            val b = Book(); val id = b.list(b.draft(mode)); val before = b.state
            assertThrows(MarketException::class.java) { b.run(b.buyer) {
                if (mode == AuctionMode.BIN) bidAuction(id, 1000) else buyAuction(id, 1000)
            } }
            assertEquals(before, b.state); assertEquals(10000, b.balances[b.buyer])
        }
    }
    @Test fun `bid limit prevents any additional debit or refund and still settles the last winner`() {
        val b = Book(AuctionSettings(maxBids = 1)); val id = b.list(b.draft(AuctionMode.BID))
        b.run(b.buyer) { bidAuction(id, 1000) }; val before = b.state
        assertEquals("auction-bid-limit", assertThrows(MarketException::class.java) { b.run(b.other) { bidAuction(id, 2000) } }.reason)
        assertEquals(before, b.state); assertEquals(10000, b.balances[b.other])
        b.time = b.state.auctions.getValue(id).expires; b.run(b.seller) { expire() }
        assertEquals(b.buyer, b.state.auctions.getValue(id).buyer)
    }
    @Test fun `invalid bid discards transaction without leaking debit refund or history`() {
        val b = Book(); val id = b.list(b.draft(AuctionMode.BID))
        b.run(b.buyer) { bidAuction(id, 1000) }
        val before = b.state
        assertThrows(MarketException::class.java) { b.run(b.other) { bidAuction(id, 1100) } }
        assertEquals(before, b.state); assertEquals(10000, b.balances[b.other])
        assertThrows(MarketException::class.java) { b.run(b.other) { bidAuction(id, 100000) } }
        assertEquals(before, b.state)
    }
    @Test fun `late bid extends deadline then expiry settles winner once even offline`() {
        val b = Book(); val id = b.list(b.draft(AuctionMode.BID))
        b.time = b.state.auctions.getValue(id).expires - 100
        b.run(b.buyer) { bidAuction(id, 1000) }
        assertEquals(b.time + 120000, b.state.auctions.getValue(id).expires)
        b.time += 120000; b.run(b.other) { expire() }
        val settled = b.state
        assertEquals(b.buyer, settled.claims.values.single { it.itemData != null }.account)
        assertEquals(AuctionStatus.SOLD, settled.auctions.getValue(id).status)
        b.run(b.other) { expire() }; assertEquals(settled, b.state)
    }
    @Test fun `unsold expiry returns exact item and rejects late BIN without debit`() {
        val b = Book(); val id = b.list()
        b.time = b.state.auctions.getValue(id).expires
        assertThrows(MarketException::class.java) { b.run(b.buyer) { buyAuction(id, 1000) } }
        b.run(b.buyer) { expire() }
        assertEquals(b.seller, b.state.claims.values.single().account)
        assertNotNull(b.state.claims.values.single().itemData)
        assertEquals(10000, b.balances[b.buyer])
    }
    @Test fun `cancellation is one time and listing fee stays paid`() {
        val b = Book(); val id = b.list(); b.run(b.seller) { cancelAuction(id) }
        assertEquals(9990, b.balances[b.seller])
        assertEquals("encoded-exact-item", b.state.claims.values.single().itemData)
        assertThrows(MarketException::class.java) { b.run(b.seller) { cancelAuction(id) } }
    }
    @Test fun `aborted preparation refunds fee and does not issue an item still in inventory`() {
        val b = Book(); val id = b.prepare(); b.run(b.seller) { abort(id) }
        assertTrue(b.state.auctions.isEmpty()); assertTrue(b.state.claims.values.all { it.product == null })
        assertEquals(10, b.state.claims.values.single().amount)
        assertThrows(MarketException::class.java) { b.run(b.seller) { abort(id) } }
    }
    @Test fun `reload during handoff returns exact item and fee instead of losing it`() {
        val b = Book(); val id = b.prepare()
        b.engine = MarketEngine(b.engine.settings.copy(auction = b.engine.settings.auction.copy(enabled = false))) { b.time }
        b.run(b.seller) { finish(id) }; b.run(b.seller) { finish(id) }
        assertTrue(b.state.auctions.isEmpty())
        assertEquals(1, b.state.claims.values.count { it.itemData != null })
        assertEquals(10, b.state.claims.values.single { it.product == null }.amount)
    }
    @Test fun `unique item withdrawal cannot split payload or substitute plain resource`() {
        val b = Book(); val id = b.list(); b.run(b.seller) { cancelAuction(id) }
        val claim = b.state.claims.values.single()
        val transfer = ItemTransfer(UUID.randomUUID(), b.seller, b.seller, claim.product!!, 1, TransferKind.WITHDRAW, 0, claim.id,
            "before", "after", itemData = claim.itemData)
        assertThrows(MarketException::class.java) { b.run(b.seller) { prepare(transfer.copy(itemData = null)) } }
        b.run(b.seller) { prepare(transfer); finish(transfer.id) }
        assertTrue(b.state.claims.isEmpty())
    }
    @Test fun `aborting exact item withdrawal restores metadata claim once`() {
        val b = Book(); val id = b.list(); b.run(b.seller) { cancelAuction(id) }
        val claim = b.state.claims.values.single()
        val transfer = ItemTransfer(UUID.randomUUID(), b.seller, b.seller, claim.product!!, 1, TransferKind.WITHDRAW, 0, claim.id,
            "before", "after", itemData = claim.itemData)
        b.run(b.seller) { prepare(transfer); abort(transfer.id) }
        assertEquals(claim.itemData, b.state.claims.values.single().itemData)
    }
    @Test fun `pending handoff reserves island listing slot against coop parallel creation`() {
        val b = Book(AuctionSettings(maxListings = 1)); b.prepare()
        assertThrows(MarketException::class.java) { b.prepare() }
    }
    @Test fun `minimum bid tiers and round up avoid subminimum increments`() {
        val rules = AuctionSettings()
        val b = Book(); val id = b.list(b.draft(AuctionMode.BID))
        val listing = b.state.auctions.getValue(id)
        for ((amount, expected) in listOf(9L to 11L, 100000L to 110000L, 1000000L to 1050000L, 10000000L to 10250000L)) {
            assertEquals(expected, rules.minimumBid(listing.copy(bids = listOf(AuctionBid(b.buyer, b.buyer, amount, 0)))))
        }
    }
    @Test fun `bad auction settings fail reload and configured tiers override defaults`() {
        val config = YamlConfiguration().apply { set("market.auction.bid-increment-tiers.0", 500) }
        assertEquals(mapOf(0L to 500), AuctionSettings.load(config).bidTiers)
        config.set("market.auction.duration-hours", listOf(0))
        assertThrows(IllegalArgumentException::class.java) { AuctionSettings.load(config) }
    }
    @Test fun `flipping a partial Bazaar claim creates an offer without changing item supply`() {
        val owner = UUID.randomUUID()
        val engine = MarketEngine(MarketSettings.load(YamlConfiguration().apply { set("market.products.OAK_LOG.sell-price", 4) }))
        val held = MarketClaim(UUID.randomUUID(), owner, "OAK_LOG", 100, "trade:partial")
        val changed = engine.transaction(owner, owner, 0, MarketState(claims = mapOf(held.id to held))) { flipClaim(held.id, 64, 5) }
        assertEquals(36, changed.state.claims.getValue(held.id).amount)
        assertEquals(64, changed.state.orders.values.single().remaining)
        assertEquals(0, changed.coins)
    }
    @Test fun `auction allowlist rejects unknown materials and narrowing it preserves already listed lots`() {
        val b = Book(AuctionSettings(allowedItems = setOf("DIAMOND_SWORD")))
        val id = b.list()
        assertThrows(MarketException::class.java) { b.prepare(b.draft().copy(product = "NETHERITE_SWORD")) }
        b.engine = MarketEngine(b.engine.settings.copy(auction = b.engine.settings.auction.copy(allowedItems = emptySet()))) { b.time }
        assertThrows(MarketException::class.java) { b.prepare() }
        b.run(b.buyer) { buyAuction(id, 1000) }
        assertEquals(AuctionStatus.SOLD, b.state.auctions.getValue(id).status)
    }
}
