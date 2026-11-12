package com.alytanta.oneblock.admin

import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** One pending operation per administrator; target and action cannot be changed by the confirm command. */
class AdminConfirmations(private val now: () -> Long = System::nanoTime) {
    data class Request(val owner: UUID, val expires: Long, val world: Boolean, val cleanup: Boolean = false)
    private val pending = ConcurrentHashMap<String, Request>()

    fun issue(actor: String, owner: UUID, world: Boolean, cleanup: Boolean = false): Request {
        require(!world || !cleanup) { "Confirmation must identify one action" }
        val time = now()
        pending.entries.removeIf { it.value.expires <= time }
        return Request(owner, time + 30_000_000_000, world, cleanup)
            .also { pending[actor] = it }
    }

    fun consume(actor: String): Request? {
        val request = pending[actor] ?: return null
        if (request.expires <= now()) { pending.remove(actor, request); return null }
        return request.takeIf { pending.remove(actor, request) }
    }
}
