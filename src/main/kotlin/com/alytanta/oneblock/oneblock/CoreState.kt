package com.alytanta.oneblock.oneblock

data class CoreState(
    val mode: CoreMode,
    val tier: Int = 1,
    val mutation: CoreMutation = CoreMutation.NONE,
    val stability: CoreStability = CoreStability.STABLE,
    val infusion: Int = 0,
    val mastery: Int = 0,
    val breaks: Long = 0,
    /** Reward of the block currently present in the world, survives config reloads and restarts. */
    val currentDrop: CoreDrop? = null,
)

enum class CoreMutation(val id: String) {
    NONE("none"),
    RICH("rich"),
    WILD("wild"),
    ANCIENT("ancient");

    companion object {
        fun byId(id: String): CoreMutation? = entries.firstOrNull { it.id.equals(id, ignoreCase = true) }
    }
}

enum class CoreStability(val id: String) {
    STABLE("stable"),
    SURGING("surging"),
    FRACTURED("fractured");

    companion object {
        fun byId(id: String): CoreStability? = entries.firstOrNull { it.id.equals(id, ignoreCase = true) }
    }
}
