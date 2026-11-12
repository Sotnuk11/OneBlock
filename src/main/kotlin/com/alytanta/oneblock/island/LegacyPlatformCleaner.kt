package com.alytanta.oneblock.island

import com.alytanta.oneblock.util.FoliaTasks
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.World
import org.bukkit.plugin.Plugin
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** Caller must hold island maintenance, refuse occupants and obtain a saved world backup first. */
class LegacyPlatformCleaner(private val plugin: Plugin) {
    private data class Position(val x: Int, val y: Int, val z: Int)
    fun clean(world: World, callback: (Boolean) -> Unit) {
        val cells = ConcurrentHashMap<Position, Material>()
        chunks(world, { x, z ->
            for (y in world.minHeight..world.minHeight + 9) cells[Position(x, y, z)] = world.getBlockAt(x, y, z).type
        }) { read ->
            val y = if (read) LegacyVoidPlatform.find(world.minHeight) { x, height, z ->
                cells.getValue(Position(x, height, z))
            } else null
            if (y == null) callback(false) else chunks(world, { x, z ->
                if (x in LegacyVoidPlatform.MIN..LegacyVoidPlatform.MAX && z in LegacyVoidPlatform.MIN..LegacyVoidPlatform.MAX) {
                    val block = world.getBlockAt(x, y, z)
                    check(block.type == LegacyVoidPlatform.expected(x, z)) { "Platform changed during maintenance; retain world backup" }
                    block.setType(Material.AIR, false)
                }
            }, callback)
        }
    }

    private fun chunks(world: World, action: (Int, Int) -> Unit, complete: (Boolean) -> Unit) {
        val pending = AtomicInteger(9)
        val failed = AtomicBoolean()
        for (cx in -1..1) for (cz in -1..1) {
            val location = Location(world, cx * 16.0, world.minHeight.toDouble(), cz * 16.0)
            FoliaTasks.region(plugin, location) {
                try {
                    for (x in maxOf(-9, cx * 16)..minOf(25, cx * 16 + 15)) {
                        for (z in maxOf(-9, cz * 16)..minOf(25, cz * 16 + 15)) action(x, z)
                    }
                } catch (e: Exception) {
                    failed.set(true)
                    plugin.logger.log(java.util.logging.Level.WARNING, "Legacy platform operation failed; retain backups", e)
                }
                if (pending.decrementAndGet() == 0) complete(!failed.get())
            }
        }
    }
}
