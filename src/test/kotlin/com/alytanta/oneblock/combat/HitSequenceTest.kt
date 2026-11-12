package com.alytanta.oneblock.combat

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import java.util.UUID

class HitSequenceTest {
    @Test fun `changing target timeout and negative monotonic origins reset sequences`() {
        val a=UUID.randomUUID();val b=UUID.randomUUID()
        val one=HitSequence.next(null,a,-10,10,3)
        assertEquals(2,one.next(a,-5,10,3).hits)
        assertEquals(1,one.next(b,-5,10,3).hits)
        assertEquals(1,one.next(a,1,10,3).hits)
        assertEquals(3,one.next(a,-5,10,3).next(a,0,10,3).next(a,1,10,3).hits)
    }
}
