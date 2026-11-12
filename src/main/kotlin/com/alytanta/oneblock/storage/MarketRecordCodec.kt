package com.alytanta.oneblock.storage

import com.google.gson.*

/** Relational fields have one canonical SQL copy. JSON holds only kind-specific details. */
internal object MarketRecordCodec {
    private val gson = Gson()
    private val layouts = mapOf(
        "order" to mapOf("profile_id" to "account", "actor_id" to "actor", "product" to "product", "quantity" to "quantity", "unit_price" to "price", "status" to "status", "created_at" to "created", "expires_at" to "expires"),
        "claim" to mapOf("profile_id" to "account", "actor_id" to "recipient", "product" to "product", "quantity" to "amount", "created_at" to "created"),
        "handoff" to mapOf("profile_id" to "account", "actor_id" to "actor", "product" to "product", "quantity" to "quantity", "unit_price" to "price", "status" to "status", "created_at" to "time"),
        "bazaar_trade" to mapOf("profile_id" to "sellAccount", "other_profile_id" to "buyAccount", "actor_id" to "sellActor", "other_actor_id" to "buyActor", "product" to "product", "quantity" to "quantity", "unit_price" to "price", "created_at" to "time"),
        "auction" to mapOf("profile_id" to "account", "actor_id" to "actor", "status" to "status", "created_at" to "created", "expires_at" to "expires", "product" to "item.product", "quantity" to "item.amount", "unit_price" to "item.price"),
        "merchant_sale" to mapOf("profile_id" to "account", "actor_id" to "actor", "product" to "product", "quantity" to "quantity", "unit_price" to "unitPrice", "created_at" to "created", "expires_at" to "expires"),
        "player_trade" to mapOf("profile_id" to "legs.0.account", "other_profile_id" to "legs.1.account", "actor_id" to "legs.0.actor", "other_actor_id" to "legs.1.actor", "status" to "status", "created_at" to "time"),
        "room_chest" to mapOf("profile_id" to "account","actor_id" to "recipient","status" to "status","created_at" to "created","expires_at" to "expires","unit_price" to "price"),
        "chest_progress" to mapOf("profile_id" to "account","actor_id" to "recipient","created_at" to "created"),
        "combat_receipt" to mapOf("profile_id" to "account","actor_id" to "recipient","created_at" to "created"))
    val columns = listOf("profile_id", "other_profile_id", "actor_id", "other_actor_id", "status", "product", "quantity", "unit_price", "created_at", "expires_at")
    private val required = mapOf(
        "order" to setOf("profile_id","actor_id","product","quantity","unit_price","status","created_at","expires_at"),
        "claim" to setOf("profile_id","quantity","created_at"),
        "handoff" to setOf("profile_id","actor_id","product","quantity","unit_price","status","created_at"),
        "bazaar_trade" to setOf("profile_id","other_profile_id","product","quantity","unit_price","created_at"),
        "auction" to setOf("profile_id","actor_id","product","quantity","unit_price","status","created_at","expires_at"),
        "merchant_sale" to setOf("profile_id","actor_id","product","quantity","unit_price","created_at","expires_at"),
        "player_trade" to setOf("profile_id","other_profile_id","actor_id","other_actor_id","status","created_at"),
        "room_chest" to setOf("profile_id","actor_id","status","created_at","unit_price"),
        "chest_progress" to setOf("profile_id","actor_id","created_at"),
        "combat_receipt" to setOf("profile_id","actor_id","created_at"))
    data class Encoded(val fields: Map<String, JsonElement>, val payload: String)
    private fun parent(root: JsonElement, path: String): Pair<JsonObject, String> {
        val parts = path.split('.')
        var current = root
        parts.dropLast(1).forEach { current = if (current.isJsonArray) current.asJsonArray[it.toInt()] else current.asJsonObject[it] }
        return current.asJsonObject to parts.last()
    }
    fun encode(kind: String, value: Any): Encoded {
        val root = gson.toJsonTree(value).asJsonObject
        root.remove("id")
        val fields = layouts.getValue(kind).mapNotNull { (column, path) ->
            val (holder, key) = parent(root, path)
            holder.remove(key)?.takeUnless { it.isJsonNull }?.let { column to it }
        }.toMap().toMutableMap()
        val missing=required.getValue(kind).filter { it !in fields }
        require(missing.isEmpty()) {
            "Market record $kind ${gson.toJsonTree(value).asJsonObject["id"]}: missing SQL fields ${missing.joinToString()}"
        }
        if ("status" !in fields) fields["status"] = JsonPrimitive(if (kind == "claim") "AVAILABLE" else "SETTLED")
        return Encoded(fields, root.toString())
    }
    fun decode(kind: String, id: java.util.UUID, payload: String, fields: Map<String, JsonElement>): JsonObject {
        val root = JsonParser.parseString(payload).asJsonObject
        root.addProperty("id", id.toString())
        layouts.getValue(kind).forEach { (column, path) -> fields[column]?.let { value ->
            val (holder, key) = parent(root, path); holder.add(key, value)
        } }
        return root
    }
}
