package com.alytanta.oneblock.storage

import com.alytanta.oneblock.oneblock.*
import org.bukkit.configuration.ConfigurationSection

object CoreYamlCodec {
    fun read(section: ConfigurationSection): CoreState = CoreState(
        mode = CoreMode.byId(section.getString("core.mode", "wood").orEmpty()) ?: CoreMode.WOOD,
        tier = section.getInt("core.tier", 1).coerceAtLeast(1),
        mutation = CoreMutation.byId(section.getString("core.mutation", "none").orEmpty()) ?: CoreMutation.NONE,
        stability = CoreStability.byId(section.getString("core.stability", "stable").orEmpty()) ?: CoreStability.STABLE,
        infusion = section.getInt("core.infusion", 0).coerceAtLeast(0),
        mastery = section.getInt("core.mastery", 0).coerceAtLeast(0),
        breaks = if (section.contains("core.breaks")) section.getLong("core.breaks").coerceAtLeast(0)
            else legacyBreakCount(section.getConfigurationSection("progress.collections")?.let { collections ->
                collections.getKeys(false).associateWith { collections.getLong(it) }
            }.orEmpty()),
    )

    fun write(section: ConfigurationSection, core: CoreState) {
        section.set("core.mode", core.mode.id)
        section.set("core.tier", core.tier)
        section.set("core.mutation", core.mutation.id)
        section.set("core.stability", core.stability.id)
        section.set("core.infusion", core.infusion)
        section.set("core.mastery", core.mastery)
        section.set("core.breaks", core.breaks)
    }

    /** One collection unit is recorded per core break; mastery is spent and cannot be used for migration. */
    fun legacyBreakCount(collections: Map<String, Long>): Long = collections.values.fold(0L) { total, value ->
        val nonnegative = value.coerceAtLeast(0)
        if (Long.MAX_VALUE - total < nonnegative) Long.MAX_VALUE else total + nonnegative
    }
}
