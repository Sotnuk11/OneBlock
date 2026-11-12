package com.alytanta.oneblock.util

import org.bukkit.Location
import org.bukkit.entity.Entity
import org.bukkit.plugin.Plugin
import java.util.concurrent.TimeUnit

/**
 * Thin wrappers over Folia schedulers.
 * World/block access -> [region], entity access (incl. teleportAsync) -> [entity], IO -> [async].
 */
object FoliaTasks {

    fun region(plugin: Plugin, location: Location, task: () -> Unit) {
        plugin.server.regionScheduler.execute(plugin, location) { task() }
    }

    fun regionDelayed(plugin: Plugin, location: Location, delayTicks: Long, task: () -> Unit) {
        plugin.server.regionScheduler.runDelayed(plugin, location, { task() }, delayTicks.coerceAtLeast(1L))
    }

    fun globalDelayed(plugin: Plugin, delayTicks: Long, task: () -> Unit) {
        plugin.server.globalRegionScheduler.runDelayed(plugin, { task() }, delayTicks.coerceAtLeast(1L))
    }

    /** Retired callbacks must only schedule follow-up work: already-retired entities reject on the caller thread. */
    fun entity(plugin: Plugin, entity: Entity, retired: (() -> Unit)? = null, task: () -> Unit) {
        if (!entity.scheduler.execute(plugin, { task() }, retired?.let { Runnable { it() } }, 1L)) retired?.invoke()
    }

    fun async(plugin: Plugin, task: () -> Unit) {
        plugin.server.asyncScheduler.runNow(plugin) { task() }
    }

    fun asyncDelayed(plugin: Plugin, delay: Long, unit: TimeUnit, task: () -> Unit) {
        plugin.server.asyncScheduler.runDelayed(plugin, { task() }, delay, unit)
    }

    fun asyncRepeating(plugin: Plugin, period: Long, unit: TimeUnit, task: () -> Unit) =
        plugin.server.asyncScheduler.runAtFixedRate(plugin, { task() }, period, period, unit)

    fun cancelAll(plugin: Plugin) {
        plugin.server.asyncScheduler.cancelTasks(plugin)
        plugin.server.globalRegionScheduler.cancelTasks(plugin)
    }
}
