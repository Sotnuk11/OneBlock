package com.alytanta.oneblock.market

import org.bukkit.configuration.file.YamlConfiguration
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.util.UUID

class CollectCoinsTest {
    @Test fun `collect all pays once and keeps foreign accounts and item claims`() {
        val owner = UUID.randomUUID(); val other = UUID.randomUUID()
        val claims = listOf(MarketClaim(UUID.randomUUID(),owner,null,10,"first"),
            MarketClaim(UUID.randomUUID(),owner,null,25,"second"), MarketClaim(UUID.randomUUID(),other,null,99,"foreign"),
            MarketClaim(UUID.randomUUID(),owner,"DIAMOND",5,"items"))
        val engine = MarketEngine(MarketSettings.load(YamlConfiguration()))
        val first = engine.transaction(owner,owner,100,MarketState(claims=claims.associateBy { it.id })) { collectAllCoins() }
        assertEquals(135L,first.coins); assertEquals(2,first.state.claims.size)
        assertTrue(first.state.claims.containsKey(claims[2].id)); assertTrue(first.state.claims.containsKey(claims[3].id))
        assertEquals(135L,engine.transaction(owner,owner,first.coins,first.state) { collectAllCoins() }.coins)
    }
}
