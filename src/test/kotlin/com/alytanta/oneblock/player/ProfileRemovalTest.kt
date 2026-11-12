package com.alytanta.oneblock.player

import com.alytanta.oneblock.island.*
import com.alytanta.oneblock.market.*
import com.alytanta.oneblock.menu.Stack
import com.alytanta.oneblock.oneblock.*
import org.bukkit.Material
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.util.UUID

class ProfileRemovalTest {
    private val who = UUID.randomUUID()
    private val partner = UUID.randomUUID()
    private val now = ProfileRemoval.INACTIVE_MILLIS * 2
    private val island = Island(UUID.randomUUID(), "coop", 0, 100, 0, CoreState(CoreMode.WOOD),
        members = mapOf(partner to IslandRole.MEMBER), coins = 1234, ownerPlayerId = who)
    private val inventory = ProfileInventory(items = List(43) { if (it in setOf(0, 8, 36, 40, 41, 42)) "slot-$it" else null },
        enderItems = List(27) { if (it == 26) "ender" else null })
    private fun player(active: Boolean = false) = PlayerProfile(who, activeAccount = if (active) island.owner else UUID.randomUUID(),
        savedInventories = mapOf(island.owner to inventory), profileCheckpoints = mapOf(island.owner to inventory),
        profileActivity = mapOf(island.owner to now - ProfileRemoval.INACTIVE_MILLIS), profileOrder = listOf(island.owner))
    private fun plan(p: PlayerProfile = player(), actor: UUID = who, market: MarketState = MarketState(), at: Long = now,
        account: Island = island) = ProfileRemoval.plan(account, p, market, actor, at) { Stack(Material.DIAMOND, 2) }

    @Test fun `leaving owner transfers ownership and all personal equipment plus ender stacks exactly once`() {
        val result = plan()
        assertEquals(6, result.salvaged) // storage, armor, offhand, body, saddle, ender; never the menu star
        assertEquals(6, result.market.claims.size)
        assertEquals(1234, result.island.coins)
        assertEquals(partner, result.island.ownerPlayer)
        assertTrue(result.island.members.isEmpty())
        assertTrue(result.market.claims.values.all { it.account == island.owner && it.recipient == null && it.amount == 2L })
        assertFalse(result.player.savedInventories!!.containsKey(island.owner))
        assertFalse(result.player.profileCheckpoints!!.containsKey(island.owner))
        assertFalse(result.player.profileOrder!!.contains(island.owner))
        assertThrows(IllegalArgumentException::class.java) { plan(result.player, market = result.market, account = result.island) }
    }
    @Test fun `kick boundary is thirty complete days with known profile activity`() {
        assertThrows(IllegalArgumentException::class.java) { plan(actor = partner, at = now - 1) }
        assertEquals(6, plan(actor = partner).salvaged)
        assertThrows(IllegalStateException::class.java) { plan(player().copy(profileActivity = null), actor = partner) }
        assertThrows(IllegalArgumentException::class.java) { plan(player().copy(profileActivity = mapOf(island.owner to now + 1)), actor = partner) }
    }
    @Test fun `active offline member needs checkpoint and an eviction journal`() {
        val result = plan(player(active = true), actor = partner)
        assertEquals(inventory.hash(), result.player.eviction!!.beforeHash)
        assertNull(result.player.activeAccount)
        assertEquals(true, result.player.inventoryDetached)
        assertEquals(6, result.market.claims.size)
        assertThrows(IllegalArgumentException::class.java) { plan(player(true).copy(profileCheckpoints = null), actor = partner) }
        assertThrows(IllegalArgumentException::class.java) { plan(player(true)) }
    }
    @Test fun `unknown or invalid offline items cannot partially create salvage`() {
        val bad = player().copy(savedInventories = mapOf(island.owner to inventory.copy(items = List(42) { null })))
        assertThrows(IllegalArgumentException::class.java) { plan(bad) }
        assertThrows(IllegalStateException::class.java) { ProfileRemoval.plan(island, player(), MarketState(), who, now) { error("bad item bytes") } }
    }
    @Test fun `recipient restricted items and coins become available to remaining coop`() {
        val claim = MarketClaim(UUID.randomUUID(), island.owner, null, 99, "refund", recipient = who)
        val unrelated = claim.copy(id = UUID.randomUUID(), account = UUID.randomUUID())
        val result = plan(market = MarketState(claims = listOf(claim, unrelated).associateBy { it.id }))
        assertNull(result.market.claims.getValue(claim.id).recipient)
        assertEquals(unrelated, result.market.claims.getValue(unrelated.id))
    }
    @Test fun `unclaimed crafted or combat output prevents departure without sharing or stranding the personal item`() {
        val claim=MarketClaim(UUID.randomUUID(),island.owner,"IRON_PICKAXE",1,"release:${UUID.randomUUID()}","exact-tool",recipient=who)
        val progress=com.alytanta.oneblock.release.ReleaseProgress(chapter="living",members=mapOf(who to com.alytanta.oneblock.release.MemberProgress(mapOf("mining" to 2700))))
        for(source in listOf(claim.source,"combat:${UUID.randomUUID()}")) {
            val held=claim.copy(source=source)
            val error=assertThrows(IllegalArgumentException::class.java) {plan(account=island.copy(releaseProgress=progress,startingKitClaimed=true),market=MarketState(claims=mapOf(held.id to held)))}
            assertEquals("unclaimed",error.message)
        }
        val result=plan(account=island.copy(releaseProgress=progress,startingKitClaimed=true))
        assertEquals(progress,result.island.releaseProgress);assertTrue(result.island.startingKitClaimed)
        assertFalse(who in ProfileRemoval.participants(result.island))
    }
    @Test fun `solo deletion retires the account and does not move inventory into another profile`() {
        val solo = island.copy(members = emptyMap())
        val result = plan(account = solo)
        assertEquals(now, result.island.retiredAt)
        assertEquals(0, result.salvaged)
        assertTrue(result.market.claims.isEmpty())
        assertFalse(result.player.savedInventories!!.containsKey(island.owner))
    }
    @Test fun `custom stacks larger than sixty four split into claimable exact chunks`() {
        val snapshot = ProfileInventory(items = List(43) { if (it == 0) "custom-stack" else null })
        val result = ProfileRemoval.plan(island, player().copy(savedInventories = mapOf(island.owner to snapshot)), MarketState(), who, now) {
            Stack(Material.DIAMOND, 99, limit = 99)
        }
        assertEquals(2, result.salvaged)
        assertEquals(listOf(35L, 64L), result.market.claims.values.map { it.amount }.sorted())
        assertEquals(99, result.market.claims.values.sumOf { it.amount })
    }
    @Test fun `solo deletion permits unclaimed proceeds and archives them with the retired account`() {
        val money = MarketClaim(UUID.randomUUID(), island.owner, null, 55, "auction-proceeds:test")
        val result = plan(market = MarketState(claims = mapOf(money.id to money)), account = island.copy(members = emptyMap()))
        assertEquals(now, result.island.retiredAt); assertEquals(money, result.market.claims[money.id])
    }
    @Test fun `active self deletion accepts freshly captured inventory without a replacement profile`() {
        val result = ProfileRemoval.plan(island.copy(members = emptyMap()), player(true), MarketState(), who, now,
            activeSnapshot = inventory) { Stack(Material.DIAMOND, 2) }
        assertNull(result.player.activeAccount); assertEquals(inventory.hash(), result.player.eviction!!.beforeHash)
        assertEquals(now, result.island.retiredAt); assertFalse(result.player.eviction.pendingCapture)
    }
    @Test fun `administrator can remove active owners without activity history and defer unknown offline inventory`() {
        val result = ProfileRemoval.plan(island, player(true).copy(profileActivity = null, profileCheckpoints = null),
            MarketState(), UUID.randomUUID(), now, administrative = true) { Stack(Material.DIAMOND) }
        assertEquals(partner, result.island.ownerPlayer)
        assertTrue(result.player.eviction!!.pendingCapture)
        assertEquals(0, result.salvaged); assertNull(result.player.activeAccount)
    }
    @Test fun `deleting seller cancels open orders and auctions and refunds the foreign high bidder once`() {
        val order = MarketOrder(UUID.randomUUID(), island.owner, who, "DIAMOND", OrderSide.BUY, 5, 10, 7, 1, Long.MAX_VALUE)
        val listing = AuctionListing(UUID.randomUUID(), island.owner, who,
            AuctionDraft("payload", "DIAMOND", 1, "Diamond", "MISC", AuctionMode.BID, 10, 1, 0, false),
            0, 0, Long.MAX_VALUE, 100, bids = listOf(AuctionBid(UUID.randomUUID(), partner, 50, 1)))
        val result = plan(market = MarketState(orders = mapOf(order.id to order), auctions = mapOf(listing.id to listing)), account = island.copy(members = emptyMap()))
        assertEquals(OrderStatus.CANCELLED, result.market.orders.getValue(order.id).status)
        assertEquals(AuctionStatus.CANCELLED, result.market.auctions.getValue(listing.id).status)
        assertEquals(50, result.market.claims.values.single { it.account == listing.bids.last().account }.amount)
        assertEquals(35, result.market.claims.values.single { it.source.startsWith("profile-delete-order") }.amount)
    }
    @Test fun `pending switch and handoff block removal without touching escrow`() {
        val pending = ItemTransfer(UUID.randomUUID(), island.owner, partner, "DIAMOND", 1, TransferKind.WITHDRAW, 0, null, "b", "a")
        assertThrows(IllegalArgumentException::class.java) { plan(market = MarketState(transfers = mapOf(pending.id to pending))) }
        assertThrows(IllegalArgumentException::class.java) { plan(player().copy(profileSwitch = ProfileSwitch(UUID.randomUUID(), null, island.owner, "hash"))) }
        assertThrows(IllegalArgumentException::class.java) { plan(player().copy(eviction = ProfileEviction(UUID.randomUUID(), island.owner, "hash"))) }
        assertThrows(IllegalArgumentException::class.java) { plan(actor = UUID.randomUUID()) }
    }
}
