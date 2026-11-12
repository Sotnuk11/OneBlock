package com.alytanta.oneblock.player

import com.alytanta.oneblock.menu.MenuConfiguration
import com.alytanta.oneblock.menu.MenuItemSpec
import org.bukkit.Material
import org.bukkit.configuration.file.YamlConfiguration
import java.io.File
import kotlin.random.Random

data class ProfileSlot(val slot: Int, val empty: MenuItemSpec, val occupied: MenuItemSpec, val active: MenuItemSpec, val locked: MenuItemSpec,
    val enabled: Boolean = true)
data class ProfileSettings(val visibleSlots: Int = 5, val availableSlots: Int = 2, val rows: Int = 6,
    val title: String = "&8Профілі", val filler: MenuItemSpec? = null, val slots: List<ProfileSlot> = emptyList(),
    val names: Map<Int, Map<String, String>> = emptyMap(), val closeSlot: Int = 49,
    val close: MenuItemSpec = MenuItemSpec(Material.BARRIER,"&cЗакрити",emptyList()),
    val closeEnabled: Boolean = true, val showRemovalHint: Boolean = true, val coopSlot: Int? = null,
    val coopButton: MenuItemSpec? = MenuItemSpec(Material.PLAYER_HEAD, "{label}", emptyList()),
    val backSlot:Int=45,val back:MenuItemSpec?=null) {
    fun name(number: Int?, language: String = "uk"): String? = names[number]?.let { it[language]?.takeIf(String::isNotBlank) ?: it["en"]?.takeIf(String::isNotBlank) ?: it["uk"]?.takeIf(String::isNotBlank) }
    fun choose(used: List<Int?>): Int? {
        val enabled = names.keys.filter { it in 1..46 && name(it) != null }
        if (enabled.isEmpty()) return null
        val counts = used.filterNotNull().groupingBy { it }.eachCount()
        val min = enabled.minOf { counts[it] ?: 0 }
        return enabled.filter { (counts[it] ?: 0) == min }.random()
    }
    companion object {
        fun read(file: File): Pair<ProfileSettings, String?> {
            if (!file.exists()) return ProfileSettings() to null
            val text = file.readText(); com.alytanta.oneblock.util.StrictYaml.validate(text);val y = YamlConfiguration().apply { loadFromString(text) }
            listOf("filler","close","coop-button","back").forEach { require(!y.contains(it) || y.isConfigurationSection(it)) { "profiles.yml $it must be a mapping" } }
            require(!y.contains("title") || y.isString("title")) { "profiles.yml title must be text" }
            require(y.getString("title")?.none { it == '\r' || it == '\n' } != false) { "profiles.yml title must be one line" }
            listOf("version","visible-slots","available-slots","rows","close.slot","coop-button.slot").forEach {
                require(!y.contains(it) || y.isInt(it)) { "profiles.yml $it must be an integer" }
            }
            require(y.getInt("version") == 1)
            val visible = y.getInt("visible-slots",5).also { require(it in 1..18) }
            val available = y.getInt("available-slots",2).also { require(it in 1..visible) }
            val rows = y.getInt("rows",6).also { require(it in 1..6) }
            val slots = (1..18).map { index ->
                val s = requireNotNull(y.getConfigurationSection("profile-slot-$index"))
                require(s.isInt("slot")) { "profile-slot-$index.slot must be an integer" }
                require(!s.contains("enabled") || s.isBoolean("enabled"))
                val enabled = s.getBoolean("enabled", true)
                val slot = s.getInt("slot").also { require(it in 0..53 && (!enabled || it < rows*9)) }
                fun item(key: String) = MenuConfiguration.item(requireNotNull(s.getConfigurationSection(key)))
                ProfileSlot(slot,item("empty"),item("occupied"),item("active"),item("locked"),enabled)
            }
            val used = slots.filter { it.enabled }.map { it.slot }
            require(used.distinct().size == used.size)
            val names = (1..46).associateWith { id -> listOf("uk","en").associateWith { lang -> y.getString("names.$id.$lang","")!!.also { require(it.length <= 24 && it.none { c -> c == '&' || c == '§' || c.isISOControl() }) } } }
            listOf("close.enabled", "show-removal-hint", "coop-button.enabled").forEach { require(!y.contains(it) || y.isBoolean(it)) }
            val closeEnabled = y.getBoolean("close.enabled", true)
            val closeSlot = y.getInt("close.slot",49).also { require(it in 0..53 && (!closeEnabled || it < rows*9 && it !in used)) }
            val coopEnabled = y.getBoolean("coop-button.enabled", true)
            val coopSlot = if (y.contains("coop-button.slot")) y.getInt("coop-button.slot").also {
                require(it in 0..53 && (!coopEnabled || it < rows*9 && it !in used && (!closeEnabled || it != closeSlot)))
            } else null
            require(!y.contains("back.enabled") || y.isBoolean("back.enabled"))
            val back=if(y.getBoolean("back.enabled",false))MenuConfiguration.item(requireNotNull(y.getConfigurationSection("back"))) else null
            require(!y.contains("back.slot") || y.isInt("back.slot"))
            val backSlot=y.getInt("back.slot",45)
            require(back==null || backSlot in 0 until rows*9 && backSlot !in used && (!closeEnabled || backSlot!=closeSlot) && (!coopEnabled || backSlot!=coopSlot)) {"profiles.back.slot overlaps another action"}
            return ProfileSettings(visible,available,rows,y.getString("title","&8Профілі")!!,
                y.getConfigurationSection("filler")?.let(MenuConfiguration::item),slots,names,closeSlot,
                MenuConfiguration.item(requireNotNull(y.getConfigurationSection("close"))), closeEnabled,
                y.getBoolean("show-removal-hint", true), coopSlot,
                if (!coopEnabled) null else y.getConfigurationSection("coop-button")?.let(MenuConfiguration::item)
                    ?: MenuItemSpec(Material.PLAYER_HEAD, "{label}", emptyList()),backSlot,back) to text
        }
    }
}
