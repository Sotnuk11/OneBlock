package com.alytanta.oneblock.storage

import java.util.UUID

/** Append-only audit. Pending/review are null, committed success true, rejection/abort false. */
data class OperationLog(val id: UUID = UUID.randomUUID(), val time: Long = System.currentTimeMillis(),
    val operation: String, val actor: UUID?, val account: UUID?, val success: Boolean?, val status: String,
    val reason: String? = null, val correlation: UUID? = null, val details: Map<String, Any?> = emptyMap(),
    val nickname: String? = null, val ownerNickname: String? = null)
