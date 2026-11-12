package com.alytanta.oneblock.hub

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import java.util.UUID

class HubCollisionExemptionsTest {
    @Test fun `hub only removes its own exemptions and preserves foreign settings`() {
        val foreign=UUID.randomUUID();val peer=UUID.randomUUID();val set=mutableSetOf(foreign)
        val state=HubCollisionExemptions();state.update(set,setOf(foreign,peer))
        assertEquals(setOf(foreign,peer),set);state.clear(set);assertEquals(setOf(foreign),set)
    }
    @Test fun `departed players are removed and newcomers are added without retaining old ids`() {
        val old=UUID.randomUUID();val next=UUID.randomUUID();val set=mutableSetOf<UUID>()
        val state=HubCollisionExemptions();state.update(set,setOf(old));state.update(set,setOf(next))
        assertEquals(setOf(next),set);state.clear(set);assertTrue(set.isEmpty())
    }
}
