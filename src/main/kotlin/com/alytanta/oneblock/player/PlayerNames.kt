package com.alytanta.oneblock.player

import com.alytanta.oneblock.storage.StorageService
import com.google.gson.JsonParser
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.plugin.Plugin
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** No remote name lookup in commands, audit writer or Folia region threads. */
class PlayerNames(plugin: Plugin, storage: StorageService) : Listener {
    private val names = ConcurrentHashMap<UUID, String>()
    init {
        val cache = plugin.server.worldContainer.resolve("usercache.json")
        if (cache.isFile) runCatching {
            JsonParser.parseString(cache.readText()).asJsonArray.forEach { entry ->
                val row = entry.asJsonObject
                remember(UUID.fromString(row["uuid"].asString), row["name"].asString)
            }
        }.onFailure { plugin.logger.warning("Could not read cached player names; stored profiles and new joins will fill the name cache") }
        storage.loadProfiles().forEach { it.lastKnownName?.let { name -> remember(it.uniqueId, name) } }
        plugin.server.onlinePlayers.forEach { remember(it.uniqueId, it.name) }
    }
    fun remember(id: UUID, name: String) { if (name.matches(Regex("[A-Za-z0-9_]{1,16}"))) names[id] = name }
    fun known(id: UUID): String? = names[id]
    fun display(id: UUID): String = known(id) ?: "Unknown player"
    fun resolve(name: String): UUID? = names.entries.firstOrNull { it.value.equals(name, true) }?.key
    fun suggestions(): List<String> = names.values.distinct().sortedBy { it.lowercase() }
    @EventHandler(priority = EventPriority.LOWEST) fun join(event: PlayerJoinEvent) { remember(event.player.uniqueId, event.player.name) }
}
