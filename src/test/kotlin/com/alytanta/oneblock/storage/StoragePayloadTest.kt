package com.alytanta.oneblock.storage

import com.alytanta.oneblock.island.Island
import com.alytanta.oneblock.oneblock.CoreMode
import com.alytanta.oneblock.oneblock.CoreState
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.util.UUID

class StoragePayloadTest {
    @Test fun `mining audit contains changed leaves without repeating unrelated collections`() {
        val island = Island(UUID.randomUUID(), "world", 0, 100, 0, CoreState(CoreMode.WOOD),
            collections = (1..500).associate { "old_$it" to it.toLong() })
        val changed = island.copy(core = island.core.copy(breaks = 1), collections = island.collections + ("oak_log" to 1L))
        val diff = AuditChanges.between(island, changed)
        assertEquals(setOf("core.breaks", "collections.oak_log"), diff.keys)
        assertFalse(diff.toString().contains("old_"))
    }
    @Test fun `audit hashes exact item data while escrow payload stays intact`() {
        val bytes = "large-exact-enchanted-item".repeat(500)
        val details = mapOf("transfer" to mapOf("id" to "id", "auction" to mapOf("itemData" to bytes, "price" to 50)))
        val json = AuditPayload.json(details)
        assertFalse(json.contains(bytes)); assertTrue(json.contains("itemDataSha256")); assertTrue(json.contains("itemDataLength"))
        assertTrue(json.length < 500); assertEquals(bytes, (details["transfer"]!!["auction"] as Map<*, *>)["itemData"])
    }
}
