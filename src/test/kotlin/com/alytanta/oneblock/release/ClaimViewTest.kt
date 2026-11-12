package com.alytanta.oneblock.release

import com.alytanta.oneblock.market.MarketClaim
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.util.UUID

class ClaimViewTest {
    @Test fun `co-op removal or amount change cannot redirect a displayed claim to its neighbour`() {
        val account=UUID.randomUUID();val actor=UUID.randomUUID()
        val first=MarketClaim(UUID.randomUUID(),account,"OAK_LOG",16,"GUI")
        val second=first.copy(id=UUID.randomUUID(),product="DIAMOND",amount=1)
        val view=ClaimView(account,1,Any(),0,listOf(first,second))
        assertNull(view.resolve(first.id,actor,mapOf(second.id to second)))
        assertEquals(second,view.resolve(second.id,actor,mapOf(second.id to second)))
        assertNull(view.resolve(first.id,actor,mapOf(first.id to first.copy(amount=8))))
        assertNull(view.resolve(first.id,actor,mapOf(first.id to first.copy(account=UUID.randomUUID()))))
        val personal=first.copy(recipient=UUID.randomUUID());val privateView=view.copy(claims=listOf(personal))
        assertNull(privateView.resolve(personal.id,actor,mapOf(personal.id to personal)))
    }
}
