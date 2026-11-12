package com.alytanta.oneblock.island

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class IslandBoundsTest {
    @Test fun `100 square contains precisely blocks minus50 through49 on both axes`() {
        val bounds = IslandBounds(0.0, 0.0, 100.0)
        assertTrue(bounds.contains(-50.0, -50.0))
        assertTrue(bounds.contains(49.999, 49.999))
        assertFalse(bounds.contains(50.0, 0.0))
        assertFalse(bounds.contains(0.0, -50.001))
        assertFalse(bounds.contains(Double.NaN, 0.0))
        assertFalse(bounds.contains(Double.POSITIVE_INFINITY, 0.0))
        assertFalse(bounds.contains(49.9, 0.0, 0.3))
        assertTrue(bounds.contains(49.5, 0.0, 0.3))
    }
}
