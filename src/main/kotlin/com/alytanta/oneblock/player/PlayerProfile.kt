package com.alytanta.oneblock.player

import java.util.UUID

data class PlayerProfile(
    val uniqueId: UUID,
    val language: String? = null,
    val inviteOwner: UUID? = null,
    val inviteExpiresAt: Long = 0,
    val activeAccount: UUID? = null,
    val savedInventories: Map<UUID, ProfileInventory>? = null,
    val profileSwitch: ProfileSwitch? = null,
    val profileOrder: List<UUID>? = null,
    val lastKnownName: String? = null,
    val harvested: Map<String, Long>? = null,
    val inventoryFull: Boolean = false,
    val inventoryNotifications: Boolean? = true,
    val profileActivity: Map<UUID, Long>? = null,
    val profileCheckpoints: Map<UUID, ProfileInventory>? = null,
    val eviction: ProfileEviction? = null,
    val inventoryDetached: Boolean? = null,
    val guideIntroSeen: Boolean = false,
)

data class ProfileEviction(val id: UUID, val account: UUID, val beforeHash: String, val review: Boolean = false,
    val pendingCapture: Boolean = false)

data class ProfileSwitch(val id: UUID, val from: UUID?, val to: UUID, val beforeHash: String, val review: Boolean = false)
