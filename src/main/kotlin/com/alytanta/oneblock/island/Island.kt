package com.alytanta.oneblock.island

import com.alytanta.oneblock.oneblock.CoreState
import java.util.UUID

data class Island(
    val owner: UUID,
    val worldName: String,
    val x: Int,
    val y: Int,
    val z: Int,
    val core: CoreState,
    val members: Map<UUID, IslandRole> = emptyMap(),
    val coins: Long = 0,
    val upgrades: Map<String, Int> = emptyMap(),
    val skills: Map<String, Int> = emptyMap(),
    val collections: Map<String, Long> = emptyMap(),
    val questProgress: Map<String, Long> = emptyMap(),
    val claimedQuests: Set<String> = emptySet(),
    val professionXp: Map<String, Long> = emptyMap(),
    val workers: Int = 0,
    val workerCollectedAt: Long = 0,
    val seasonPoints: Long = 0,
    val weeklyProgress: Long = 0,
    val weeklyProgressWeek: Long = 0,
    val weeklyClaimedWeek: Long = 0,
    val platformInitialized: Boolean = false,
    val startingKitClaimed: Boolean = false,
    /** Null follows the global configuration; an admin can override individual islands. */
    val worldSize: Double? = null,
    /** The world that actually received the starter platform; a regenerated world has a new UUID. */
    val platformWorldId: UUID? = null,
    /** owner remains the durable account/world ID; the human owner can own several accounts. */
    val ownerPlayerId: UUID? = null,
    val publicId: String? = null,
    val nameNumber: Int? = null,
    val profileName: String? = null,
    val createdAt: Long = 0,
    val visitorSettings: VisitorSettings? = null,
    val collectionContributions: Map<UUID, Map<String, Long>>? = null,
    val retiredAt: Long? = null,
    val worldRetirement: WorldRetirement? = null,
    val releaseProgress: com.alytanta.oneblock.release.ReleaseProgress? = null,
    /** One-use durable permission issued only when allocating a new profile, consumed before Worlds.create. */
    val worldCreationPending: Boolean = false,
) {
    val ownerPlayer: UUID get() = ownerPlayerId ?: owner
    fun hasPlatformIn(world: UUID): Boolean = platformInitialized && platformWorldId == world
}

data class WorldRetirement(val backup: String? = null, val deletedAt: Long? = null,
    val expiresAt: Long? = null, val purgedAt: Long? = null)
