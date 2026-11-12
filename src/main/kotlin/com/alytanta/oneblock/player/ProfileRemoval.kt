package com.alytanta.oneblock.player

import com.alytanta.oneblock.island.Island
import com.alytanta.oneblock.island.IslandRole
import com.alytanta.oneblock.market.*
import org.bukkit.inventory.ItemStack
import java.util.UUID

data class RemovalCapture(val active: Boolean, val inventory: ProfileInventory? = null)

data class ProfileRemoval(val island: Island, val player: PlayerProfile, val market: MarketState, val salvaged: Int) {
    companion object {
        const val INACTIVE_DAYS = 30L
        const val INACTIVE_MILLIS = INACTIVE_DAYS * 86_400_000
        fun participants(island: Island) = island.members.filterValues { it == IslandRole.MEMBER }.keys + island.ownerPlayer

        fun validate(island: Island, player: PlayerProfile, market: MarketState, requester: UUID, now: Long,
            administrative: Boolean = false) {
            val who = player.uniqueId
            val people = participants(island)
            require(island.retiredAt == null && who in people && (administrative || requester in people)) { "membership" }
            require(player.profileSwitch == null && player.eviction == null) { "pending" }
            require(market.transfers.values.none { it.account == island.owner && it.status in setOf(TransferStatus.PREPARED, TransferStatus.REVIEW) } &&
                !com.alytanta.oneblock.trade.PlayerTradeLedger.pending(market, who)) { "pending" }
            // These exact outputs are profile-bound personal entitlements. Leaving would make them
            // inaccessible (or wrongly share combat loot with the remaining co-op). Collect first.
            require(market.claims.values.none {it.account==island.owner && it.recipient==who && (it.source.startsWith("release:") || it.source.startsWith("combat:") || it.source.startsWith("chest:"))}) {"unclaimed"}
            if (!administrative && requester != who) {
                val last = player.profileActivity.orEmpty()[island.owner] ?: throw IllegalStateException("activity-unknown")
                require(last > 0 && now >= last && now - last >= INACTIVE_MILLIS) { "active-member" }
            }
        }
        /** Pure plan: all escrow, membership and player changes must be persisted in one snapshot. */
        fun plan(island: Island, player: PlayerProfile, market: MarketState, requester: UUID, now: Long,
            administrative: Boolean = false, activeSnapshot: ProfileInventory? = null, detachActive: Boolean = false,
            decode: (String) -> ItemStack = AuctionItems::decode): ProfileRemoval {
            validate(island, player, market, requester, now, administrative)
            val who = player.uniqueId
            val remaining = participants(island) - who
            val solo = remaining.isEmpty()
            val active = player.activeAccount == island.owner || detachActive
            val snapshot = activeSnapshot ?: if (active) player.profileCheckpoints.orEmpty()[island.owner] else player.savedInventories.orEmpty()[island.owner]
            require(!active || requester != who || activeSnapshot != null || administrative) { "snapshot-required" }
            require(!active || snapshot != null || administrative) { "checkpoint-missing" }
            require(administrative || solo || snapshot != null || player.inventoryDetached == true ||
                player.activeAccount != null && player.activeAccount != island.owner) { "checkpoint-missing" }
            val claims = market.claims.mapValues { (_, claim) ->
                // Unclaimed release crafts retain their recipient; leaving is not an explicit item transfer.
                if (!solo && claim.account == island.owner && claim.recipient == who && !claim.source.startsWith("release:")) claim.copy(recipient = null) else claim
            }.toMutableMap()
            var count = 0
            if (!solo && snapshot != null) count = salvage(snapshot, island.owner, who, claims, decode)
            val successor = remaining.sortedBy(UUID::toString).firstOrNull()
            val updated = if (solo) island.copy(retiredAt = now, worldRetirement = com.alytanta.oneblock.island.WorldRetirement()) else island.copy(
                ownerPlayerId = if (island.ownerPlayer == who) successor else island.ownerPlayerId,
                members = island.members - setOfNotNull(who, if (island.ownerPlayer == who) successor else null))
            val nextMarket = if (solo) retireMarket(island.owner, market.copy(claims = claims)) else market.copy(claims = claims)
            return ProfileRemoval(updated, player.copy(
                savedInventories = player.savedInventories.orEmpty() - island.owner,
                profileCheckpoints = player.profileCheckpoints.orEmpty() - island.owner,
                profileActivity = player.profileActivity.orEmpty() - island.owner,
                profileOrder = player.profileOrder.orEmpty() - island.owner,
                activeAccount = if (active) null else player.activeAccount,
                eviction = if (active) ProfileEviction(UUID.randomUUID(), island.owner, snapshot?.hash().orEmpty(), pendingCapture = snapshot == null) else null,
                inventoryDetached = if (active) true else player.inventoryDetached), nextMarket, count)
        }

        internal fun salvage(snapshot: ProfileInventory, account: UUID, who: UUID, claims: MutableMap<UUID, MarketClaim>,
            decode: (String) -> ItemStack): Int {
                var count = 0
                require(snapshot.items.size in setOf(41, 43) && snapshot.enderItems.size == 27) { "inventory-layout" }
                val items = snapshot.items.filterIndexed { index, _ -> index != com.alytanta.oneblock.menu.MenuInventory.SLOT } + snapshot.enderItems
                items.filterNotNull().forEach { data ->
                    val item = decode(data)
                    require(!item.isEmpty && item.amount in 1..maxOf(64, item.maxStackSize)) { "inventory-item" }
                    var remainingAmount = item.amount
                    while (remainingAmount > 0) {
                        val amount = minOf(64, remainingAmount)
                        val payload = if (amount == item.amount) data else AuctionItems.encode(item.clone().apply { this.amount = amount }, 1_048_576)
                        val id = UUID.randomUUID()
                        claims[id] = MarketClaim(id, account, item.type.name, amount.toLong(), "coop-salvage:$who", payload)
                        remainingAmount -= amount; count++
                    }
                }
                return count
        }

        private fun retireMarket(account: UUID, market: MarketState): MarketState {
            val claims = market.claims.toMutableMap()
            fun claim(owner: UUID, product: String?, amount: Long, source: String, data: String? = null) {
                if (amount <= 0) return
                val id = UUID.randomUUID(); claims[id] = MarketClaim(id, owner, product, amount, source, data)
            }
            val orders = market.orders.mapValues { (_, order) ->
                if (order.account != account || order.status != OrderStatus.OPEN) order else {
                    claim(account, if (order.side == OrderSide.SELL) order.product else null,
                        if (order.side == OrderSide.SELL) order.remaining.toLong() else Math.multiplyExact(order.price, order.remaining.toLong()), "profile-delete-order:${order.id}")
                    order.copy(status = OrderStatus.CANCELLED)
                }
            }
            val auctions = market.auctions.mapValues { (_, listing) ->
                if (listing.account != account || listing.status != AuctionStatus.OPEN) listing else {
                    listing.bids.lastOrNull()?.let { claim(it.account, null, it.amount, "profile-delete-refund:${listing.id}") }
                    claim(account, listing.item.product, listing.item.amount.toLong(), "profile-delete-auction:${listing.id}", listing.item.itemData)
                    listing.copy(status = AuctionStatus.CANCELLED)
                }
            }
            return market.copy(orders = orders, auctions = auctions, claims = claims)
        }
    }
}
