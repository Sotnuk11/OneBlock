package com.alytanta.oneblock.storage

import com.google.gson.Gson
import com.google.gson.JsonElement

/** Store only changed leaves instead of copying entire growing collections on each mined block. */
internal object AuditChanges {
    private val gson = Gson()
    fun between(old: Any?, value: Any): Map<String, Any?> {
        val result = linkedMapOf<String, Any?>()
        fun inventoryHash(node: JsonElement?): String? = node?.takeUnless { it.isJsonNull }?.toString()?.let { data ->
            java.security.MessageDigest.getInstance("SHA-256").digest(data.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
        }
        fun visit(path: String, before: JsonElement?, after: JsonElement?) {
            if (before == after) return
            if (listOf("savedInventories", "profileCheckpoints").any { path == it || path.startsWith("$it.") }) {
                result["$path.sha256"] = mapOf("before" to inventoryHash(before), "after" to inventoryHash(after))
                return
            }
            if (before?.isJsonObject == true && after?.isJsonObject == true) {
                (before.asJsonObject.keySet() + after.asJsonObject.keySet()).forEach { key ->
                    visit(if (path.isEmpty()) key else "$path.$key", before.asJsonObject[key], after.asJsonObject[key])
                }
            } else result[path] = mapOf("before" to before, "after" to after)
        }
        // Compare immutable fields before constructing JSON. A switch must not serialize every unchanged inventory.
        if(old != null && old.javaClass == value.javaClass) {
            value.javaClass.declaredFields.filterNot { java.lang.reflect.Modifier.isStatic(it.modifiers) || it.isSynthetic }.forEach { field ->
                field.isAccessible=true
                val before=field.get(old);val after=field.get(value)
                if(before != after)visit(field.name,gson.toJsonTree(before),gson.toJsonTree(after))
            }
        } else gson.toJsonTree(value).asJsonObject.entrySet().forEach { (key, entry) -> visit(key, null, entry) }
        // New records have no previous object, so preserve their fields as individually attributable changes.
        return result
    }
}
