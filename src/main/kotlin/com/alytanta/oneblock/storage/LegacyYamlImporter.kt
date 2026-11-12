package com.alytanta.oneblock.storage

import com.alytanta.oneblock.island.Island
import com.alytanta.oneblock.island.IslandRole
import com.alytanta.oneblock.player.PlayerProfile
import org.bukkit.configuration.InvalidConfigurationException
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.plugin.java.JavaPlugin
import java.io.File
import java.io.IOException
import java.util.UUID

/** Read-only one-time importer. Runtime state is exclusively written by PostgreSQL. */
class LegacyYamlImporter(private val plugin: JavaPlugin) {
    private val lock = Any()
    private val islands = linkedMapOf<UUID, Island>()
    private val profiles = linkedMapOf<UUID, PlayerProfile>()
    private val file = File(plugin.dataFolder, "data.yml")

    fun load() {
        synchronized(lock) {
            if (!file.exists()) {
                plugin.logger.info("No legacy data.yml found; PostgreSQL is the only runtime state store")
                return
            }
            val yaml = YamlConfiguration()
            try {
                yaml.load(file)
            } catch (exception: IOException) {
                throw IllegalStateException("Could not read island data at ${file.absolutePath}", exception)
            } catch (exception: InvalidConfigurationException) {
                throw IllegalStateException("Island data is invalid; refusing legacy import at ${file.absolutePath}", exception)
            }
            yaml.getConfigurationSection("islands")?.getKeys(false)?.forEach { key ->
                val owner = parseUuid(key)
                val section = yaml.getConfigurationSection("islands.$key") ?: return@forEach
                if (owner == null) {
                    error("Invalid island UUID '$key'; refusing legacy import")
                }
                islands[owner] = Island(
                    owner = owner,
                    worldName = section.getString("world", "world") ?: "world",
                    x = section.getInt("x"),
                    y = section.getInt("y", 100),
                    z = section.getInt("z"),
                    core = CoreYamlCodec.read(section),
                    coins = section.getLong("progress.coins", 0).coerceAtLeast(0),
                    upgrades = readIntMap(section, "progress.upgrades"),
                    skills = readIntMap(section, "progress.skills"),
                    collections = readLongMap(section, "progress.collections"),
                    questProgress = readLongMap(section, "progress.quest-progress"),
                    claimedQuests = section.getStringList("progress.claimed-quests").toSet(),
                    professionXp = readLongMap(section, "progress.professions"),
                    workers = section.getInt("progress.workers", 0).coerceAtLeast(0),
                    workerCollectedAt = section.getLong("progress.worker-collected-at", 0).coerceAtLeast(0),
                    seasonPoints = section.getLong("progress.season-points", 0).coerceAtLeast(0),
                    weeklyProgress = section.getLong("progress.weekly-progress", 0).coerceAtLeast(0),
                    weeklyProgressWeek = section.getLong("progress.weekly-progress-week", 0).coerceAtLeast(0),
                    weeklyClaimedWeek = section.getLong("progress.weekly-claimed-week", 0).coerceAtLeast(0),
                    platformInitialized = section.getBoolean("platform-initialized", false),
                    platformWorldId = section.getString("platform-world-id")?.let { parseUuid(it) ?: error("Invalid platform world UUID") },
                    startingKitClaimed = section.getBoolean("starting-kit-claimed", true),
                    worldSize = section.getDouble("world-size", 0.0).takeIf { it.isFinite() && it in 16.0..59_999_968.0 },
                    members = section.getConfigurationSection("members")
                        ?.getKeys(false)
                        ?.mapNotNull { memberKey ->
                            val memberId = parseUuid(memberKey)
                            val role = section.getString("members.$memberKey")
                                ?.let { value -> IslandRole.entries.firstOrNull { it.name.equals(value, true) } }
                            if (memberId == null || role == null || role == IslandRole.OWNER) {
                                error("Invalid island member '$memberKey' for '$key'; refusing legacy import")
                            } else {
                                memberId to role
                            }
                        }
                        ?.toMap()
                        .orEmpty(),
                )
            }
            yaml.getConfigurationSection("players")?.getKeys(false)?.forEach { key ->
                val uniqueId = parseUuid(key)
                if (uniqueId == null) {
                    error("Invalid player UUID '$key'; refusing legacy import")
                }
                val language = yaml.getString("players.$key.language")
                profiles[uniqueId] = PlayerProfile(
                    uniqueId = uniqueId,
                    language = language,
                )
            }
            plugin.logger.info("Loaded ${islands.size} islands and ${profiles.size} player profiles from ${file.absolutePath}")
        }
    }

    fun loadIslands(): List<Island> = synchronized(lock) { islands.values.toList() }

    fun loadProfiles(): List<PlayerProfile> = synchronized(lock) { profiles.values.toList() }

    private fun parseUuid(value: String): UUID? = try {
        UUID.fromString(value)
    } catch (_: IllegalArgumentException) {
        null
    }

    private fun readIntMap(section: org.bukkit.configuration.ConfigurationSection, path: String): Map<String, Int> =
        section.getConfigurationSection(path)?.getKeys(false)?.associateWith { section.getInt("$path.$it").coerceAtLeast(0) }
            .orEmpty()

    private fun readLongMap(section: org.bukkit.configuration.ConfigurationSection, path: String): Map<String, Long> =
        section.getConfigurationSection(path)?.getKeys(false)?.associateWith { section.getLong("$path.$it").coerceAtLeast(0) }
            .orEmpty()

}
