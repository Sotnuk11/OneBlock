package com.alytanta.oneblock.oneblock

import com.alytanta.oneblock.island.Island
import com.alytanta.oneblock.util.FoliaTasks
import io.papermc.paper.threadedregions.scheduler.ScheduledTask
import org.bukkit.Location
import org.bukkit.World
import org.bukkit.configuration.ConfigurationSection
import org.bukkit.entity.Item
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.entity.ItemSpawnEvent
import org.bukkit.event.world.ChunkLoadEvent
import org.bukkit.event.world.ChunkUnloadEvent
import org.bukkit.event.world.WorldLoadEvent
import org.bukkit.event.world.WorldUnloadEvent
import org.bukkit.plugin.Plugin
import org.bukkit.util.BoundingBox
import org.bukkit.util.Vector
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs

/** Radius one means the core's cell and its immediately adjacent cells: a 3 x 3 x 3 cube. */
data class CoreItemGatheringSettings(val enabled: Boolean = true, val radius: Int = 1,
    val intervalTicks: Long = 1, val height: Double = 1.1) {
    init { require(radius in 0..8); require(intervalTicks in 1..20); require(height.isFinite() && height in 1.0..2.0) }
    companion object {
        fun load(y: ConfigurationSection): CoreItemGatheringSettings {
            val path = "core.item-gathering"
            require(!y.contains(path) || y.isConfigurationSection(path)) { "$path must be a mapping" }
            require(!y.contains("$path.enabled") || y.isBoolean("$path.enabled"))
            require(!y.contains("$path.radius") || y.isInt("$path.radius"))
            require(!y.contains("$path.interval-ticks") || y.isInt("$path.interval-ticks"))
            require(!y.contains("$path.height") || y.get("$path.height") is Number)
            return CoreItemGatheringSettings(y.getBoolean("$path.enabled",true),y.getInt("$path.radius",1),
                y.getLong("$path.interval-ticks",1),y.getDouble("$path.height",1.1))
        }
    }
}

internal data class CoreItemArea(val x: Int, val y: Int, val z: Int, val radius: Int, val height: Double) {
    fun bounds() = BoundingBox(x-radius.toDouble(),y-radius.toDouble(),z-radius.toDouble(),
        x+radius+1.0,y+radius+1.0,z+radius+1.0)
    fun contains(location: Location) = bounds().contains(location.x,location.y,location.z)
    fun target(world: World) = Location(world,x+0.5,y+height,z+0.5)
    // Gravity may settle an item on the core surface; do not teleport that item every tick.
    fun settled(location: Location) = abs(location.x-(x+0.5)) <= 0.01 && abs(location.z-(z+0.5)) <= 0.01 &&
        location.y >= y+1.0 && location.y <= y+height+0.02
}

/** One spatially bounded region query per loaded core, rather than a task for every item in the world. */
class CoreItemGathering(private val plugin: Plugin, initial: CoreItemGatheringSettings,
    private val findIsland: (World) -> Island?) : Listener {
    @Volatile private var settings = initial
    @Volatile private var closed = false
    private class Watch { var task: ScheduledTask? = null }
    private val watches = ConcurrentHashMap<String,Watch>()
    private val pending = ConcurrentHashMap.newKeySet<UUID>()
    private fun area(island: Island, config: CoreItemGatheringSettings) = CoreItemArea(island.x,island.y,island.z,config.radius,config.height)

    fun start() { plugin.server.globalRegionScheduler.execute(plugin) {
        if (!closed && settings.enabled) plugin.server.worlds.forEach(::watch)
    } }
    fun apply(next: CoreItemGatheringSettings) {
        settings = next
        watches.values.forEach { it.task?.cancel() }; watches.clear()
        start()
    }
    private fun stop(world: World) { watches.remove(world.key().asString())?.task?.cancel() }
    private fun watch(world: World) {
        val config = settings
        if (closed || !config.enabled) return
        val island = findIsland(world) ?: return
        val watch = Watch()
        if (watches.putIfAbsent(world.key().asString(),watch) != null) return
        val area = area(island,config)
        watch.task = plugin.server.regionScheduler.runAtFixedRate(plugin,area.target(world), { task ->
            if (closed || settings !== config || !world.isChunkLoaded(island.x shr 4,island.z shr 4)) {
                watches.remove(world.key().asString(),watch); task.cancel()
            } else {
                // Query only the small area. Returned entities can straddle a Folia region boundary;
                // their location and physics are inspected exclusively on their entity scheduler.
                world.getNearbyEntities(area.bounds()) { it is Item }.forEach { entity -> request(entity as Item,world,island,config) }
            }
        },1L,config.intervalTicks)
    }
    internal fun request(item: Item, world: World, island: Island, config: CoreItemGatheringSettings = settings) {
        if (closed || !config.enabled || !pending.add(item.uniqueId)) return
        val id = item.uniqueId
        FoliaTasks.entity(plugin,item,retired={ pending.remove(id) }) work@{
            if (closed || settings !== config || !item.isValid || item.isDead || item.world.key() != world.key()) { pending.remove(id); return@work }
            val area = area(island,config)
            val location = item.location
            if (!area.contains(location) || area.settled(location)) { pending.remove(id); return@work }
            // Move the entity itself: metadata, amount, owner, pickup delay and age remain intact.
            try {
                item.velocity = Vector(0,0,0)
                item.teleportAsync(area.target(world)).whenComplete { success,error ->
                    if (closed) { pending.remove(id); return@whenComplete }
                    if (error != null || success != true) pending.remove(id)
                    else FoliaTasks.entity(plugin,item,retired={ pending.remove(id) }) {
                        if (!closed && item.isValid && item.world.key() == world.key() && area.settled(item.location)) item.velocity = Vector(0,0,0)
                        pending.remove(id)
                    }
                }
            } catch (e: Exception) {
                pending.remove(id)
                plugin.logger.log(java.util.logging.Level.WARNING,"Could not gather item $id above core ${island.publicId ?: island.owner}",e)
            }
        }
    }
    @EventHandler(priority=EventPriority.MONITOR,ignoreCancelled=true) fun spawn(e: ItemSpawnEvent) {
        if (!settings.enabled || closed) return
        val world = e.location.world ?: return
        val island = findIsland(world) ?: return
        if (area(island,settings).contains(e.location)) request(e.entity,world,island)
    }
    @EventHandler fun worldLoaded(e: WorldLoadEvent) { watch(e.world) }
    @EventHandler(priority=EventPriority.MONITOR,ignoreCancelled=true) fun worldUnloaded(e: WorldUnloadEvent) { stop(e.world) }
    @EventHandler fun chunkLoaded(e: ChunkLoadEvent) {
        val island = findIsland(e.world) ?: return
        if (e.chunk.x == island.x shr 4 && e.chunk.z == island.z shr 4) watch(e.world)
    }
    @EventHandler(priority=EventPriority.MONITOR,ignoreCancelled=true) fun chunkUnloaded(e: ChunkUnloadEvent) {
        val island = findIsland(e.world) ?: return
        if (e.chunk.x == island.x shr 4 && e.chunk.z == island.z shr 4) stop(e.world)
    }
    fun close() { closed = true; watches.values.forEach { it.task?.cancel() }; watches.clear(); pending.clear() }
}
