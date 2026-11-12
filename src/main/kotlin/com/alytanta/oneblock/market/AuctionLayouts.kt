package com.alytanta.oneblock.market

import org.bukkit.configuration.ConfigurationSection

/** Source IDs stay stable for custom layouts; defaults place them like the reference auction UI. */
internal object AuctionLayouts {
    val content = (0 until 24).map { 11 + it / 6 * 9 + it % 6 }
    val personal = (0 until 28).map { 10 + it / 7 * 9 + it % 7 }
    val categories = listOf(0, 9, 18, 27, 36, 8)
    private val footer = mapOf(45 to 49, 46 to 46, 52 to 52)

    fun window(id: String, base: MarketWindow): MarketWindow {
        if (!id.startsWith("auction-")) return base.copy(id = id)
        val positions = when (id) {
            "auction-main" -> mapOf(4 to 32, 11 to 11, 13 to 13, 15 to 15, 45 to 31)
            "auction-browse-all" -> mapOf(4 to 4, 6 to 47, 7 to 48, 8 to 45, 45 to 49, 46 to 46, 47 to 50, 49 to 52, 51 to 51, 52 to 53)
            "auction-browse-mine" -> footer + mapOf(48 to 53, 50 to 48)
            "auction-browse-bids" -> footer + mapOf(50 to 48)
            "auction-claims" -> footer + mapOf(47 to 48, 52 to 53)
            "auction-select", "auction-draft" -> mapOf(13 to 13, 19 to 48, 22 to 33, 25 to 31, 31 to 29, 45 to 49)
            "auction-confirm" -> mapOf(13 to 13, 31 to 11, 45 to 15)
            "auction-listing" -> mapOf(13 to 13, 22 to 33, 31 to 29, 33 to 31, 45 to 49)
            else -> footer
        }
        val grids = when (id) {
            "auction-browse-all" -> mapOf("content" to content, "categories" to categories, "separators" to listOf(1,10,19,28,37))
            "auction-browse-mine", "auction-browse-bids" -> mapOf("content" to personal)
            "auction-claims" -> mapOf("content" to personal)
            "auction-history" -> mapOf("content" to (0..44).toList())
            else -> emptyMap()
        }
        val rows = when (id) { "auction-main" -> 4; "auction-confirm" -> 3; else -> 6 }
        val inherited = base.buttons.mapValues { (source, style) -> if (rows < 6 && source !in positions) style.copy(enabled = false) else style }
        return base.copy(id = id, rows = rows,
            buttons = inherited + positions.mapValues { (source, slot) -> (base.buttons[source] ?: MarketMenuButton()).copy(slot = slot) },
            grids = (if (rows < 6) emptyMap() else base.grids) + grids)
    }

    /** Upgrade only the old generated skeleton. Personalised layouts remain authoritative. */
    fun previousClaims(section: ConfigurationSection): Boolean {
        if (section.getInt("rows", 6) != 6 || section.contains("title") || section.getString("filler", "INHERIT") != "INHERIT" ||
            section.getConfigurationSection("contexts")?.getKeys(false)?.isNotEmpty() == true ||
            section.getConfigurationSection("grids")?.getKeys(false) != setOf("content") || section.getIntegerList("grids.content") != (0..44).toList()) return false
        val buttons = section.getConfigurationSection("buttons") ?: return false
        return buttons.getKeys(false) == setOf("45", "46", "52") && mapOf(45 to 49, 46 to 46, 52 to 52).all { (source, slot) ->
            buttons.getConfigurationSection(source.toString())!!.getKeys(false).all { it in setOf("slot", "enabled") } &&
                buttons.getInt("$source.slot") == slot && buttons.getBoolean("$source.enabled", true)
        }
    }
    fun legacyDefault(id: String, section: ConfigurationSection): Boolean {
        if (!id.startsWith("auction-") || section.getInt("rows", 6) != 6 || section.contains("title") ||
            section.getString("filler", "INHERIT") != "INHERIT" || section.getConfigurationSection("contexts")?.getKeys(false)?.isNotEmpty() == true) return false
        val buttons = section.getConfigurationSection("buttons") ?: return false
        if (buttons.getKeys(false).any { key ->
            val button = buttons.getConfigurationSection(key)
            button == null || button.getKeys(false).any { it !in setOf("slot", "enabled") } ||
                button.getInt("slot", -1) != key.toIntOrNull() || !button.getBoolean("enabled", true)
        }) return false
        val expected = when {
            id.startsWith("auction-browse-") -> mapOf("content" to content, "categories" to listOf(0,9,18,27,36), "separators" to listOf(1,10,19,28,37))
            id == "auction-select" -> mapOf("content" to personal)
            id in setOf("auction-history", "auction-claims") -> mapOf("content" to (0..44).toList())
            else -> emptyMap()
        }
        val actual = section.getConfigurationSection("grids")
        return actual?.getKeys(false).orEmpty() == expected.keys && expected.all { (key, value) -> actual!!.getIntegerList(key) == value }
    }
}
