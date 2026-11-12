package com.alytanta.oneblock.trade

import com.alytanta.oneblock.island.Island
import com.alytanta.oneblock.market.MarketState
import com.alytanta.oneblock.storage.OperationLog
import java.util.UUID

enum class TradeStatus { PREPARED, REVIEW, DONE, ABORTED }
enum class DepositStatus { PENDING, APPLIED, DECLINED, REVIEW }
data class TradeItem(val product: String, val quantity: Int, val itemData: String?, val sha256: String)
data class TradeLeg(val actor: UUID, val nickname: String, val account: UUID, val coins: Long, val items: List<TradeItem>,
    val beforeHash: String, val afterHash: String, val status: DepositStatus = DepositStatus.PENDING)
data class PlayerTrade(val id: UUID, val legs: List<TradeLeg>, val status: TradeStatus = TradeStatus.PREPARED,
    val time: Long = System.currentTimeMillis(), val reason: String? = null)
data class TradeChange<T>(val coins: Map<UUID, Long>, val state: MarketState, val result: T, val audit: OperationLog)

object PlayerTradeLedger {
    fun pending(state: MarketState, actor: UUID) = state.playerTrades.values.any {
        it.status in setOf(TradeStatus.PREPARED, TradeStatus.REVIEW) && it.legs.any { leg -> leg.actor == actor }
    }
    fun prepare(record: PlayerTrade, islands: Map<UUID, Island>, state: MarketState, historyLimit: Int = 1000): TradeChange<PlayerTrade> {
        require(record.id !in state.playerTrades && record.legs.size == 2 && record.legs.map { it.actor }.distinct().size == 2)
        require(record.legs.any { it.items.isNotEmpty() || it.coins > 0 }) { "empty-trade" }
        require(record.legs.none { pending(state, it.actor) }) { "trade-pending" }
        val coins = islands.mapValues { it.value.coins }.toMutableMap()
        record.legs.forEach { leg ->
            val island = requireNotNull(islands[leg.account])
            require(island.ownerPlayer == leg.actor || island.members[leg.actor] == com.alytanta.oneblock.island.IslandRole.MEMBER)
            require(leg.coins >= 0 && leg.coins <= coins.getValue(leg.account)) { "coins" }
            require(leg.items.size <= 16 && leg.items.all { it.quantity in 1..64 && !it.itemData.isNullOrBlank() })
            if (record.legs.map { it.account }.distinct().size == 1) require(leg.coins == 0L) { "shared-wallet" }
            coins[leg.account] = coins.getValue(leg.account) - leg.coins
        }
        val retained = (state.playerTrades.values.filter { it.status in setOf(TradeStatus.PREPARED, TradeStatus.REVIEW) } +
            state.playerTrades.values.filter { it.status in setOf(TradeStatus.DONE, TradeStatus.ABORTED) }.sortedByDescending { it.time }.take(historyLimit)).associateBy { it.id }
        return TradeChange(coins, state.copy(playerTrades = retained + (record.id to record)), record,
            OperationLog(operation = "PLAYER_TRADE_PREPARE", actor = record.legs[0].actor, account = record.legs[0].account,
                success = null, status = "PREPARED", correlation = record.id, details = mapOf("trade" to record)))
    }
    fun resolve(id: UUID, actor: UUID, deposit: DepositStatus, islands: Map<UUID, Island>, state: MarketState, reason: String?): TradeChange<PlayerTrade> {
        val old = requireNotNull(state.playerTrades[id])
        val coins = islands.mapValues { it.value.coins }.toMutableMap()
        if (old.status in setOf(TradeStatus.DONE, TradeStatus.ABORTED)) return TradeChange(coins, state, old,
            OperationLog(operation = "PLAYER_TRADE_RECOVERY", actor = actor, account = null, success = true, status = "ALREADY_FINISHED", correlation = id))
        require(deposit != DepositStatus.PENDING)
        val leg = old.legs.first { it.actor == actor }
        require(leg.status in setOf(DepositStatus.PENDING, DepositStatus.REVIEW) || leg.status == deposit) { "Conflicting deposit result" }
        var record = old.copy(legs = old.legs.map { if (it.actor == actor) it.copy(status = deposit) else it }, reason = reason ?: old.reason)
        val claims = state.claims.toMutableMap()
        var success: Boolean? = null
        if (record.legs.any { it.status == DepositStatus.REVIEW }) record = record.copy(status = TradeStatus.REVIEW)
        else if (record.legs.all { it.status in setOf(DepositStatus.APPLIED, DepositStatus.DECLINED) }) {
            val completed = record.legs.all { it.status == DepositStatus.APPLIED }
            record.legs.forEachIndexed { index, offered ->
                val recipient = if (completed) record.legs[1-index] else offered
                coins[recipient.account] = Math.addExact(coins.getValue(recipient.account), offered.coins)
                if (completed || offered.status == DepositStatus.APPLIED) offered.items.forEach { item ->
                    val claimId = UUID.randomUUID()
                    claims[claimId] = com.alytanta.oneblock.market.MarketClaim(claimId, recipient.account, item.product, item.quantity.toLong(),
                        "player-trade${if (completed) "" else "-return"}:$id", requireNotNull(item.itemData), recipient.actor)
                }
            }
            success = completed
            record = record.copy(status = if (completed) TradeStatus.DONE else TradeStatus.ABORTED,
                legs = record.legs.map { it.copy(items = it.items.map { item -> item.copy(itemData = null) }) })
        } else record = record.copy(status = TradeStatus.PREPARED)
        return TradeChange(coins, state.copy(claims = claims, playerTrades = state.playerTrades + (id to record)), record,
            OperationLog(operation = "PLAYER_TRADE_${record.status.name}", actor = actor, account = leg.account,
                success = success, status = record.status.name, reason = record.reason, correlation = id,
                details = mapOf("trade" to record, "deposit" to deposit, "coins_after" to coins)))
    }
}
