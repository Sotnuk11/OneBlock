package com.alytanta.oneblock.config

import com.alytanta.oneblock.oneblock.CoreMode
import com.alytanta.oneblock.oneblock.CoreStability
import com.alytanta.oneblock.oneblock.DropSettings
import com.alytanta.oneblock.hub.HubSettings
import org.bukkit.configuration.file.FileConfiguration
import java.util.logging.Logger

data class PluginConfig(
    val defaultLanguage: String,
    val islandWorldSize: Double,
    val islandY: Int,
    val islandSize: Int,
    val startingMode: CoreMode,
    val defaultStability: CoreStability,
    val flushIntervalSeconds: Long,
    val idleWorldUnloadMinutes: Long,
    val startingItems: List<String>,
    val upgrades: Map<String, Pair<Int, Long>>,
    val skills: Map<String, Pair<Int, Long>>,
    val drops: DropSettings,
    val visibleBorder: Boolean,
    val hub: HubSettings = HubSettings("world", true, 0.5, 100.0, 0.5, 0f, 0f),
    val economy: EconomySettings = EconomySettings(),
    val visitorDefault: com.alytanta.oneblock.island.VisitMode = com.alytanta.oneblock.island.VisitMode.ALL,
    val coopManageVisitors: Boolean = false,
    val visitorLimit: Int = 100,
    val coreToolDamage: Int = 1,
    val platformUpgradeStep: Int = 2,
    val playerCommands: Set<String> = setOf("help", "home", "hub", "menu", "profiles", "profile", "collections", "collection", "skills", "settings", "coop", "visit", "trade"),
    val itemGathering: com.alytanta.oneblock.oneblock.CoreItemGatheringSettings = com.alytanta.oneblock.oneblock.CoreItemGatheringSettings(),
    val hubRules: com.alytanta.oneblock.hub.HubRules = com.alytanta.oneblock.hub.HubRules(),
) {
    companion object {
        fun load(config: FileConfiguration, logger: Logger): PluginConfig {
            val modeId = config.getString("core.starting-mode", "wood")!!
            val startingMode = CoreMode.byId(modeId) ?: CoreMode.WOOD.also {
                logger.warning("Unknown core.starting-mode '$modeId', falling back to ${it.id}")
            }
            val stabilityId = config.getString("core.default-stability", "stable")!!
            val stability = CoreStability.byId(stabilityId) ?: CoreStability.STABLE.also {
                logger.warning("Unknown core.default-stability '$stabilityId', falling back to ${it.id}")
            }
            val worldSize = config.getDouble("islands.world-size", 100.0)
                .coerceIn(16.0, MAX_WORLD_BORDER_SIZE)
            require(worldSize.isFinite()) { "islands.world-size must be finite" }
            fun catalog(path: String, defaults: Map<String, Pair<Int, Long>>) = defaults.mapValues { (key, default) ->
                (config.getInt("$path.$key.max-level", default.first).also { require(it in 1..100000) }) to
                    config.getLong("$path.$key.base-cost", default.second).also { require(it in 1..1_000_000_000) }
            }

            val islandSize = config.getInt("islands.size", 1).also { require(it in 1..100) }
            val startingItemsRaw = config.getStringList("islands.starting-items")
            val startingItems = startingItemsRaw.ifEmpty {
                listOf(
                    "WOODEN_SWORD",
                    "WOODEN_PICKAXE",
                    "WOODEN_AXE",
                    "WOODEN_SHOVEL",
                    "BREAD:8",
                    "OAK_SAPLING:4",
                    "TORCH:16",
                )
            }

            return PluginConfig(
                defaultLanguage = config.getString("language.default", "uk")!!.lowercase(),
                islandWorldSize = worldSize,
                islandY = config.getInt("islands.y", 60).also { require(it in -62..317) { "islands.y must leave room for a floor and player in the overworld" } },
                islandSize = islandSize,
                startingMode = startingMode,
                defaultStability = stability,
                flushIntervalSeconds = config.getLong("storage.flush-interval-seconds", 1).also { require(it in 1..60) },
                idleWorldUnloadMinutes = config.getLong("islands.idle-unload-minutes", 10).coerceAtLeast(1),
                startingItems = startingItems,
                upgrades = catalog(
                    "progression.upgrades",
                    mapOf("fortune" to (5 to 100L), "island_size" to (5 to 250L)),
                ),
                skills = emptyMap(),
                drops = DropSettings.load(config),
                itemGathering = com.alytanta.oneblock.oneblock.CoreItemGatheringSettings.load(config),
                visibleBorder = config.getBoolean("islands.visible-border", false),
                hub = HubSettings.load(config),
                hubRules = com.alytanta.oneblock.hub.HubRules.load(config),
                economy = EconomySettings.load(config),
                visitorDefault = com.alytanta.oneblock.island.VisitMode.valueOf(config.getString("islands.visitors.default-mode", "ALL")!!.uppercase()),
                coopManageVisitors = config.getBoolean("islands.visitors.coop-can-manage", false),
                visitorLimit = config.getInt("islands.visitors.max-selected", 100).also { require(it in 1..1000) },
                coreToolDamage = config.getInt("core.tool-damage-per-break", 1).also { require(it in 0..100) },
                platformUpgradeStep = config.getInt("islands.platform-upgrade-step", 2).also { require(it in 1..100) },
                playerCommands = (if (config.contains("commands.player")) config.getStringList("commands.player").map { it.lowercase() }.toSet() else
                    setOf("help", "home", "hub", "menu", "profiles", "profile", "collections", "collection", "skills", "settings", "coop", "visit", "trade")).also {
                    require(it.all { route -> route in setOf("help", "home", "hub", "menu", "profiles", "profile", "collections", "collection", "skills", "skill", "profession", "professions", "settings", "coop", "visit", "trade") }) { "commands.player contains an unsupported/admin route" }
                },
            )
        }

        private const val MAX_WORLD_BORDER_SIZE = 59_999_968.0
    }
}
