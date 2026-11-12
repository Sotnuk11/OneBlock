package com.alytanta.oneblock.storage

import com.alytanta.oneblock.combat.CombatReceipt
import com.google.gson.Gson
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.util.UUID

class CombatReceiptCodecTest {
    @Test fun `receipt SQL fields and JSON fingerprint round trip without adding data to player profiles`() {
        val receipt=CombatReceipt(UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),"a".repeat(64),1234)
        val encoded=MarketRecordCodec.encode("combat_receipt",receipt)
        assertEquals(setOf("profile_id","actor_id","created_at","status"),encoded.fields.keys)
        assertFalse(encoded.payload.contains(receipt.account.toString()))
        assertTrue(encoded.payload.contains(receipt.fingerprint))
        assertEquals(receipt,Gson().fromJson(MarketRecordCodec.decode("combat_receipt",receipt.id,encoded.payload,encoded.fields),CombatReceipt::class.java))
    }
}
