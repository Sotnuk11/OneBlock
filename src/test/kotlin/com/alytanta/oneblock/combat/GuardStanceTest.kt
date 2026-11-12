package com.alytanta.oneblock.combat

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class GuardStanceTest {
    @Test fun `only consecutive hits inside the window activate a finite stance`() {
        val one=GuardStance().hit(0,3,5,4)
        val two=one.hit(3,3,5,4)
        assertFalse(two.protects(3))
        val active=two.hit(7,3,5,4)
        assertTrue(active.protects(7));assertTrue(active.protects(10));assertFalse(active.protects(11))
        assertFalse(one.hit(6,3,5,4).hit(12,3,5,4).protects(12))
    }
    @Test fun `monotonic origin can be negative`() {
        val active=GuardStance().hit(-5,2,10,8).hit(-1,2,10,8)
        assertTrue(active.protects(0));assertFalse(active.protects(7))
    }
}
