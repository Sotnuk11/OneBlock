package com.alytanta.oneblock.player

object ProfileIdentity {
    fun nextId(used: Set<String>): String? = (1..9999).asSequence().map { it.toString().padStart(4,'0') }.firstOrNull { it !in used }
}

data class ProfileNaming(val number: Int?, val custom: String?)
