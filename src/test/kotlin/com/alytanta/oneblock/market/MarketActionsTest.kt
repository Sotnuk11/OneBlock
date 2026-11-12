package com.alytanta.oneblock.market

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class MarketActionsTest {
    @Test fun `named action invalidates an old position immediately when hidden or moved`() {
        var target:Int?=12;var calls=0
        val actions=MarketActions<Unit>({target},{"browse"})
        actions.set(11) {calls++}
        assertNull(actions.acquire(11));actions.acquire(12)!!(Unit);actions.release()
        target=null;assertNull(actions.acquire(12));actions.set(11) {calls++}
        target=15;assertNull(actions.acquire(12));actions.set(11) {calls++}
        actions.acquire(15)!!(Unit);actions.release();assertEquals(2,calls)
    }
    @Test fun `information empty slots and failed actions never consume navigation`() {
        val actions = MarketActions<Unit>()
        var navigated = 0
        actions.set(4, null)
        actions.set(45) { navigated++ }
        assertNull(actions.acquire(4)); assertNull(actions.acquire(99))
        actions.acquire(45)!!(Unit); actions.release()
        actions.set(10) { throw MarketException("liquidity") }
        assertThrows(MarketException::class.java) { try { actions.acquire(10)!!(Unit) } finally { actions.release() } }
        actions.acquire(45)!!(Unit); actions.release()
        assertEquals(2, navigated)
    }

    @Test fun `duplicate packets while queued invoke one action and later navigation still works`() {
        val actions = MarketActions<Unit>(); var calls = 0
        actions.set(31) { calls++ }; actions.set(45) { calls += 10 }
        val first = actions.acquire(31)!!
        assertNull(actions.acquire(31)); assertNull(actions.acquire(45))
        first(Unit); actions.release()
        actions.acquire(45)!!(Unit); actions.release()
        assertEquals(11, calls)
    }
}
