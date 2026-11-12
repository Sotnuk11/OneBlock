package com.alytanta.oneblock.storage

import com.google.gson.Gson
import com.google.gson.JsonElement
import java.security.MessageDigest

/** Exact item blobs live in escrow/claims, not repeated in every phase of the audit trail. */
internal object AuditPayload {
    private val gson = Gson()
    fun json(value: Any): String {
        val tree = gson.toJsonTree(value)
        fun compact(node: JsonElement) {
            if (node.isJsonObject) {
                val objectNode = node.asJsonObject
                objectNode.entrySet().toList().forEach { (key, child) ->
                    if (key == "itemData" && child.isJsonPrimitive && child.asJsonPrimitive.isString) {
                        val bytes = child.asString.toByteArray(Charsets.UTF_8)
                        objectNode.remove(key)
                        objectNode.addProperty("itemDataSha256", MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) })
                        objectNode.addProperty("itemDataLength", bytes.size)
                    } else compact(child)
                }
            } else if (node.isJsonArray) node.asJsonArray.forEach(::compact)
        }
        compact(tree)
        return gson.toJson(tree)
    }
}
