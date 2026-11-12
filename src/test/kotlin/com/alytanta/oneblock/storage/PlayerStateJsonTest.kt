package com.alytanta.oneblock.storage

import com.alytanta.oneblock.player.*
import com.google.gson.Gson
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.util.UUID

class PlayerStateJsonTest {
    @Test fun `progress patches retain exact large inventories and explicitly clear journals and nullable fields`() {
        val id = UUID.randomUUID(); val account = UUID.randomUUID()
        val inventory = ProfileInventory(items = List(43) { "exact-$it-" + "x".repeat(10000) })
        val original = PlayerProfile(id, language = "uk", savedInventories = mapOf(account to inventory),
            profileCheckpoints = mapOf(account to inventory),
            profileSwitch = ProfileSwitch(UUID.randomUUID(), null, account, "hash"),
            eviction = ProfileEviction(UUID.randomUUID(), account, "before"))
        val next = original.copy(language = null, harvested = mapOf("oak_log" to 42), profileSwitch = null,
            eviction = null, inventoryFull = true, lastKnownName = "Renamed", activeAccount = account)
        val patch = PlayerStateJson.patch(next, original)
        assertEquals(0,PlayerStateJson.patch(original.copy(inventoryFull=true,lastKnownName="Metadata"),original).size())
        assertFalse(patch.has("savedInventories")); assertFalse(patch.has("profileCheckpoints"))
        assertTrue(patch.get("profileSwitch").isJsonNull); assertTrue(patch.get("eviction").isJsonNull)
        assertTrue(patch.get("language").isJsonNull); assertTrue(patch.toString().length < 200)
        val stored = PlayerStateJson.patch(original, null)
        patch.entrySet().forEach { (key, value) -> stored.add(key, value) }
        stored.addProperty("uniqueId", id.toString()); stored.addProperty("lastKnownName", next.lastKnownName)
        stored.addProperty("activeAccount", account.toString()); stored.addProperty("inventoryFull", true)
        assertEquals(next, Gson().fromJson(stored, PlayerProfile::class.java))
        val cleared = PlayerStateJson.patch(next.copy(savedInventories = null, profileCheckpoints = emptyMap()), next)
        assertTrue(cleared.get("savedInventories").isJsonNull)
        assertEquals(0, cleared.getAsJsonObject("profileCheckpoints").size())
    }

    @Test fun `audit skips unchanged inventories but preserves changed leaf and checkpoint hashes`() {
        val id = UUID.randomUUID(); val account = UUID.randomUUID()
        val inventory = ProfileInventory(items = listOf("exact-secret-item"))
        val original = PlayerProfile(id, savedInventories = mapOf(account to inventory), profileCheckpoints = mapOf(account to inventory))
        val next = original.copy(profileSwitch = ProfileSwitch(UUID.randomUUID(), null, account, "before"))
        val changes = AuditChanges.between(original, next)
        assertTrue(changes.containsKey("profileSwitch")); assertFalse(changes.keys.any { it.contains("Inventor") || it.contains("Checkpoints") })
        val removed = AuditChanges.between(original, original.copy(profileCheckpoints = null))
        assertTrue(removed.containsKey("profileCheckpoints.sha256")); assertFalse(removed.toString().contains("exact-secret-item"))
    }
}
