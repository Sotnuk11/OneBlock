package com.alytanta.oneblock.menu

import com.alytanta.oneblock.island.IslandRole
import com.alytanta.oneblock.island.IslandService
import org.bukkit.entity.Player
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/** Register additional sources without changing layouts or placeholder parsing. Read on the player's entity thread. */
class MenuStatistics(private val islands: IslandService) {
    var playerState: com.alytanta.oneblock.player.PlayerService? = null
    private val providers = ConcurrentHashMap<String, (Player) -> Any?>()
    fun register(source: String, provider: (Player) -> Any?) {
        require(source.matches(Regex("[a-z][a-z0-9_.-]*")))
        check(providers.putIfAbsent(source, provider) == null) { "Statistic already registered: $source" }
    }
    fun unregister(source: String) { providers.remove(source) }
    private fun source(player: Player, source: String): Any? {
        providers[source]?.let { return it(player) }
        val island = islands.findForPlayer(player.uniqueId)
        when(source) {
            "player.inventory_notifications" -> return playerState?.find(player.uniqueId)?.inventoryNotifications != false
            "player.inventory_full" -> return playerState?.find(player.uniqueId)?.inventoryFull == true
            "player.name" -> return player.name
            "player.uuid" -> return player.uniqueId
            "player.health" -> return player.health
            "player.food" -> return player.foodLevel
            "player.experience_level" -> return player.level
            "player.world" -> return player.world.key().asString()
            "profile.name" -> return island?.let { islands.profileName(it) }
            "profile.id" -> return island?.publicId
            "profile.name_number" -> return island?.let(islands::nameNumber)
            "profile.coins" -> return island?.coins
            "profile.blocks" -> return island?.core?.breaks
            "profile.members" -> return island?.let { it.members.values.count { role -> role == IslandRole.MEMBER } + 1 }
            "profile.type" -> return island?.let { if (it.members.values.any { role -> role == IslandRole.MEMBER }) "Co-op" else "Solo" }
        }
        if(source.startsWith("harvest.")) return playerState?.find(player.uniqueId)?.harvested?.get(source.removePrefix("harvest.")) ?: 0L
        return null // Future skills use a registered source, never a made-up level.
    }
    fun values(player: Player, configuration: MenuStatisticsConfig): Map<String, String> {
        val builtIn = listOf("player.inventory_notifications", "player.inventory_full", "player.name", "player.uuid", "profile.name", "profile.coins", "profile.blocks", "profile.members", "profile.type")
            .associateWith { source(player,it)?.toString()?:"—" }
        return builtIn + configuration.values.mapValues { (_,value) ->
            val raw = source(player,value.source)
            if(raw == null)value.fallback else if(raw !is Number || value.format == "text")raw.toString() else {
                val symbols = DecimalFormatSymbols(Locale.US).apply { groupingSeparator = ' ' }
                when(value.format) {
                    "integer" -> DecimalFormat("#,##0",symbols).format(raw)
                    "decimal" -> DecimalFormat("#,##0.##",symbols).format(raw)
                    "compact" -> {
                        val number=raw.toDouble()
                        val (divisor,suffix)=when { kotlin.math.abs(number)>=1e9 -> 1e9 to "b"; kotlin.math.abs(number)>=1e6 -> 1e6 to "m"; kotlin.math.abs(number)>=1e3 -> 1e3 to "k"; else -> 1.0 to "" }
                        DecimalFormat("0.##",symbols).format(number/divisor)+suffix
                    }
                    else -> raw.toString()
                }
            }
        }
    }
    fun render(text: String, values: Map<String,String>) = Regex("\\{([a-zA-Z0-9_.-]+)}").replace(text) { match -> values[match.groupValues[1]]?:match.value }
}
