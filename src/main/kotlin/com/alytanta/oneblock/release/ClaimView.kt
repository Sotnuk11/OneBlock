package com.alytanta.oneblock.release

import com.alytanta.oneblock.market.MarketClaim
import java.util.UUID

/** Display identity is immutable. Removal/reordering by co-op can never select its neighbour. */
data class ClaimView(val account: UUID, val generation: Long, val configuration: Any, val page: Int, val claims: List<MarketClaim>) {
    fun resolve(id: UUID, actor: UUID, current: Map<UUID,MarketClaim>): MarketClaim? {
        val shown=claims.firstOrNull { it.id==id } ?: return null
        return current[id]?.takeIf { it==shown && it.account==account && (it.recipient==null || it.recipient==actor) }
    }
}
