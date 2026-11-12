package com.alytanta.oneblock.player

import com.alytanta.oneblock.storage.StorageService
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerJoinEvent

class PlayerService(private val storage: StorageService) : Listener {
    private val profiles = ConcurrentHashMap<UUID, PlayerProfile>().apply {
        storage.loadProfiles().forEach { put(it.uniqueId, it) }
    }

    fun find(uniqueId: UUID): PlayerProfile? = profiles[uniqueId]
    internal fun removeMembership(id: UUID, operation: (PlayerProfile) -> ProfileRemoval): ProfileRemoval = synchronized(profiles) {
        val plan = operation(requireNotNull(profiles[id]))
        profiles[id] = plan.player
        plan
    }
    internal fun removeMemberships(ids: Set<UUID>, operation: (List<PlayerProfile>) -> List<ProfileRemoval>): List<ProfileRemoval> = synchronized(profiles) {
        val plans = operation(ids.map { requireNotNull(profiles[it]) })
        plans.forEach { profiles[it.player.uniqueId] = it.player }
        plans
    }

    fun getOrCreate(uniqueId: UUID): PlayerProfile =
        profiles.computeIfAbsent(uniqueId) { PlayerProfile(it).also(storage::saveProfile) }

    @EventHandler fun onJoin(event: PlayerJoinEvent) {
        if (storage.healthy) update(event.player.uniqueId) { it.copy(lastKnownName = event.player.name,
            profileActivity = it.profileActivity.orEmpty() + (it.activeAccount?.let { id -> mapOf(id to System.currentTimeMillis()) } ?: emptyMap())) }
    }

    fun invite(player: UUID, owner: UUID, expiresAt: Long) { update(player) { it.copy(inviteOwner = owner, inviteExpiresAt = expiresAt) } }
    fun takeInvitation(player: UUID): PlayerProfile? = synchronized(profiles) {
        val prior = profiles[player] ?: return null
        if (prior.inviteOwner == null) return null
        update(player) { it.copy(inviteOwner = null, inviteExpiresAt = 0) }
        prior
    }

    fun setLanguage(uniqueId: UUID, language: String?) {
        update(uniqueId) { it.copy(language = language?.lowercase()) }
    }

    fun update(uniqueId: UUID, change: (PlayerProfile) -> PlayerProfile): PlayerProfile =
        synchronized(profiles) {
            val updated = change(profiles[uniqueId] ?: PlayerProfile(uniqueId))
            storage.saveProfile(updated)
            profiles[uniqueId] = updated
            updated
        }
}
