package com.alytanta.oneblock.admin

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.util.UUID

class AdminConfirmationsTest {
    @Test fun `platform cleanup token cannot be reused as a reset`() {
        val confirmations = AdminConfirmations()
        val owner = UUID.randomUUID()
        val cleanup = confirmations.issue("admin", owner, world = false, cleanup = true)
        val request = requireNotNull(confirmations.consume("admin"))
        assertTrue(request.cleanup)
        assertFalse(request.world)
        assertEquals(owner, request.owner)
        assertNull(confirmations.consume("admin"))
        assertThrows(IllegalArgumentException::class.java) { confirmations.issue("admin", owner, world = true, cleanup = true) }
    }
    @Test fun `destructive confirmation is actor bound one use and expires`() {
        var now = 0L
        val confirmations = AdminConfirmations { now }
        val owner = UUID.randomUUID()
        val request = confirmations.issue("alice", owner, true)
        assertNull(confirmations.consume("bob"))
        assertEquals(request, confirmations.consume("alice"))
        assertNull(confirmations.consume("alice"))
        val expired = confirmations.issue("alice", owner, false)
        now = expired.expires
        assertNull(confirmations.consume("alice"))
        val old = confirmations.issue("alice", owner, false)
        val replacement = confirmations.issue("alice", UUID.randomUUID(), true)
        assertEquals(replacement.owner, confirmations.consume("alice")?.owner)
    }
}
