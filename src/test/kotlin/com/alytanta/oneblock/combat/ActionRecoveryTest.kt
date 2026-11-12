package com.alytanta.oneblock.combat

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class ActionRecoveryTest {
    @Test fun `native swings and volleys retain vanilla behavior but cannot add a free spell`() {
        val r=ActionRecovery();r.native(1_000_000_000,1.1)
        assertFalse(r.blocksNative(1_100_000_000));assertFalse(r.canUse(1_100_000_000))
        r.native(1_000_000_001,1.1) // multishot siblings are not rejected as new primary attacks
        assertTrue(r.canUse(2_100_000_001))
    }
    @Test fun `ability recovery survives switching weapon style and expires exactly at its boundary`() {
        val r=ActionRecovery();r.used(1_000_000_000,1.3)
        assertTrue(r.blocksNative(2_299_999_999));assertFalse(r.canUse(2_299_999_999))
        assertFalse(r.blocksNative(2_300_000_000));assertTrue(r.canUse(2_300_000_000))
        r.native(2_300_000_000,.625);assertFalse(r.canUse(2_900_000_000));assertTrue(r.canUse(2_925_000_000))
    }
}
