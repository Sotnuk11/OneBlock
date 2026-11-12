package com.alytanta.oneblock.storage

import com.alytanta.oneblock.player.PlayerProfile
import com.google.gson.Gson
import com.google.gson.JsonObject
import java.lang.reflect.Modifier

/** Top-level JSONB merge: explicit null clears a journal, an omitted field retains its exact stored value. */
internal object PlayerStateJson {
    private val gson = Gson()
    private val fields = PlayerProfile::class.java.declaredFields.filterNot {
        Modifier.isStatic(it.modifiers) || it.isSynthetic || it.name in setOf("uniqueId", "lastKnownName", "activeAccount", "inventoryFull")
    }.onEach { it.isAccessible = true }

    fun patch(value: PlayerProfile, previous: PlayerProfile?): JsonObject = JsonObject().apply {
        fields.forEach { field ->
            val next = field.get(value)
            if (previous == null || field.get(previous) != next) add(field.name, gson.toJsonTree(next))
        }
    }
}
