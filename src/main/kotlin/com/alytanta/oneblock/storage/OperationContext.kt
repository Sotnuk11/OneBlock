package com.alytanta.oneblock.storage

import java.util.UUID

/** Synchronous attribution only; never leaks a player context to another Folia region/async task. */
object OperationContext {
    data class Context(val actor: UUID?, val operation: String, val request: UUID = UUID.randomUUID(), val details: Map<String, Any?> = emptyMap())
    private val local = ThreadLocal<Context>()
    fun current(): Context? = local.get()
    fun <T> with(context: Context, action: () -> T): T {
        val before = local.get(); local.set(context)
        try { return action() } finally { if (before == null) local.remove() else local.set(before) }
    }
}
