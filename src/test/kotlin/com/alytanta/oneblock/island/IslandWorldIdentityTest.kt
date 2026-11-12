package com.alytanta.oneblock.island

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Test
import java.util.UUID

class IslandWorldIdentityTest {
    @Test
    fun `world name is stable for each owner and unique between owners`() {
        val first = UUID.fromString("a6b5e51c-c494-3fcc-b27e-ed2e01502eae")
        val second = UUID.fromString("d74ad3b0-8e13-4ce7-a98a-4063417ba14f")

        assertEquals("oneblock_island_$first", IslandWorldIdentity.worldName(first))
        assertEquals(IslandWorldIdentity.worldName(first), IslandWorldIdentity.worldName(first))
        assertNotEquals(IslandWorldIdentity.worldName(first), IslandWorldIdentity.worldName(second))
    }
}
