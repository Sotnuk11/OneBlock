package com.alytanta.oneblock.market

import com.alytanta.oneblock.island.Island
import com.alytanta.oneblock.oneblock.CoreMode
import com.alytanta.oneblock.oneblock.CoreState
import com.alytanta.oneblock.player.PlayerProfile
import com.alytanta.oneblock.storage.DatabaseSettings
import com.alytanta.oneblock.storage.PostgresStorageService
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres
import org.bukkit.configuration.file.YamlConfiguration
import org.junit.jupiter.api.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.logging.Logger

/** Uses a disposable database, including restart between escrow and settlement. */
class AuctionPersistenceTest {
    @TempDir lateinit var folder: Path
    companion object {
        private lateinit var postgres: EmbeddedPostgres
        @JvmStatic @BeforeAll fun start() { postgres = EmbeddedPostgres.start() }
        @JvmStatic @AfterAll fun stop() { if (::postgres.isInitialized) postgres.close() }
    }

    @Test fun `BIN purchase and unique item withdrawal survive database reopen without reissuing escrow`() = roundTrip(AuctionMode.BIN)
    @Test fun `BID escrow refunds winner and proceeds survive database reopen without settling twice`() = roundTrip(AuctionMode.BID)

    private fun roundTrip(mode: AuctionMode) {
        val db = "auction_" + UUID.randomUUID().toString().replace("-", "")
        postgres.postgresDatabase.connection.use { c -> c.createStatement().use { it.execute("CREATE DATABASE $db") } }
        val settings = DatabaseSettings("127.0.0.1", postgres.port, db, "postgres", "test-only", "disable", "disable", 5)
        fun open() = PostgresStorageService(folder.toFile(), Logger.getAnonymousLogger(), settings).also { it.load() }
        var repo = open()
        val seller = UUID.randomUUID(); val buyer = UUID.randomUUID(); val other = UUID.randomUUID()
        var now = 1_000_000L
        val engine = MarketEngine(MarketSettings.load(YamlConfiguration()).copy(auction = AuctionSettings(graceSeconds = 0))) { now }
        fun transact(account: UUID, action: MarketEngine.Transaction.() -> Unit) {
            val island = repo.loadIslands().single { it.owner == account }
            repo.changeMarket(island) { state -> engine.transaction(account, account, island.coins, state, action) }
            repo.flushAsync().get(10, TimeUnit.SECONDS)
        }
        fun restart() { repo.close(); repo = open() }
        try {
            for (id in listOf(seller, buyer, other)) {
                repo.saveProfile(PlayerProfile(id, activeAccount = id))
                repo.saveIsland(Island(id, "oneblock:island_$id", 0, 60, 0, CoreState(CoreMode.WOOD), coins = 1000))
            }
            val id = UUID.randomUUID()
            val draft = AuctionDraft("exact-custom-item", "DIAMOND_SWORD", 1, "Custom sword", "WEAPONS", mode, 100, 1, 1, false)
            transact(seller) { prepare(ItemTransfer(id, seller, seller, draft.product, 1, TransferKind.AUCTION_LIST, 0, null,
                "before", "after", itemData = draft.itemData, auction = draft)) }
            restart()
            assertTrue(repo.marketState().auctions.isEmpty())
            transact(seller) { finish(id) }
            if (mode == AuctionMode.BID) transact(buyer) { bidAuction(id, 100, agreedCharge = 100) }
            restart()
            assertEquals(mode, repo.marketState().auctions.getValue(id).item.mode)
            if (mode == AuctionMode.BIN) transact(buyer) { buyAuction(id, 100) } else {
                transact(other) { bidAuction(id, 120, agreedCharge = 120) }
                transact(buyer) { bidAuction(id, 150, agreedCharge = 150) }
                now = repo.marketState().auctions.getValue(id).expires
                transact(seller) { expire() }
            }
            restart()
            val settled = repo.marketState()
            assertEquals(AuctionStatus.SOLD, settled.auctions.getValue(id).status)
            assertEquals(buyer, settled.auctions.getValue(id).buyer)
            transact(seller) { expire() }
            assertEquals(settled, repo.marketState())
            assertEquals(if (mode == AuctionMode.BIN) 900 else 750, repo.loadIslands().single { it.owner == buyer }.coins)
            val proceeds = settled.claims.values.single { it.source == "auction-proceeds:$id" }
            assertEquals(if (mode == AuctionMode.BIN) 99 else 149, proceeds.amount)
            transact(seller) { collectCoins(proceeds.id) }
            assertEquals(if (mode == AuctionMode.BIN) 1098 else 1148, repo.loadIslands().single { it.owner == seller }.coins)
            val item = repo.marketState().claims.values.single { it.itemData != null }
            assertEquals(draft.itemData, item.itemData); assertEquals(buyer, item.account)
            val withdrawal = UUID.randomUUID()
            transact(buyer) { prepare(ItemTransfer(withdrawal, buyer, buyer, item.product!!, 1, TransferKind.WITHDRAW,
                0, item.id, "inventory-before", "inventory-after", itemData = item.itemData)) }
            restart()
            transact(buyer) { finish(withdrawal) }; transact(buyer) { finish(withdrawal) }
            assertTrue(repo.marketState().claims.values.none { it.itemData != null })
            if (mode == AuctionMode.BID) {
                assertEquals(100, repo.marketState().claims.values.single { it.account == buyer }.amount)
                assertEquals(120, repo.marketState().claims.values.single { it.account == other }.amount)
            }
        } finally { repo.close() }
    }
}
