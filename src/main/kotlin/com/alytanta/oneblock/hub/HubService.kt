package com.alytanta.oneblock.hub

import com.alytanta.oneblock.island.IslandService
import com.alytanta.oneblock.locale.Lang
import com.alytanta.oneblock.locale.LocaleService
import com.alytanta.oneblock.util.FoliaTasks
import org.bukkit.Location
import org.bukkit.entity.Player
import org.bukkit.plugin.Plugin
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.AtomicMoveNotSupportedException

/** A destination inside this backend; never sends a Velocity /server command. */
class HubService(
    private val plugin: Plugin,
    initial: HubSettings,
    private val islands: IslandService,
    private val locales: LocaleService,
) {
    @Volatile private var settings = initial
    var onArrival: ((Player) -> Unit)? = null
    fun currentSettings() = settings
    fun applySettings(next: HubSettings) { settings = next }
    /** Explicit destination shared by sethub and generator activation. Call on an IO thread. */
    fun persist(next:HubSettings) {
        require(!next.world.startsWith("oneblock_dungeons:")) {"Temporary dungeon world cannot be the hub"}
        synchronized(plugin) {
            val destination=plugin.dataFolder.toPath().resolve("config.yml")
            val source=Files.readString(destination,Charsets.UTF_8)
            com.alytanta.oneblock.util.StrictYaml.validate(source)
            val candidate=org.bukkit.configuration.file.YamlConfiguration().apply {loadFromString(source)}
            val changes=mapOf("hub.world" to next.world,"hub.use-world-spawn" to next.useWorldSpawn,"hub.x" to next.x,"hub.y" to next.y,
                "hub.z" to next.z,"hub.yaw" to next.yaw.toDouble(),"hub.pitch" to next.pitch.toDouble())
            changes.forEach(candidate::set)
            HubSettings.load(candidate)
            val temporary=plugin.dataFolder.toPath().resolve("config.yml.hub.tmp")
            Files.writeString(temporary,candidate.saveToString(),Charsets.UTF_8)
            java.nio.channels.FileChannel.open(temporary,java.nio.file.StandardOpenOption.WRITE).use {it.force(true)}
            Files.move(temporary,destination,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING)
            changes.forEach(plugin.config::set)
            settings=next
        }
    }
    private fun configuredWorld(value: String): org.bukkit.World? = HubWorlds.resolve(plugin,value)?.takeUnless {islands.isIslandWorld(it) || it.key().namespace()=="oneblock_dungeons"}
    fun isHubWorld(world: org.bukkit.World): Boolean = configuredWorld(settings.world)?.key() == world.key()
    fun isHubWorld(name: String): Boolean = settings.world == name

    /** Respawn resolves the same hub destination as /hub; other worlds keep their own main spawn. */
    fun resolveWorldSpawn(world: org.bukkit.World, callback: (Location?) -> Unit) {
        plugin.server.globalRegionScheduler.execute(plugin) {
            val current = settings
            val location = if (configuredWorld(current.world)?.key() == world.key() && !current.useWorldSpawn)
                Location(world, current.x, current.y, current.z, current.yaw, current.pitch)
            else world.spawnLocation.clone()
            callback(location.takeIf { it.y >= world.minHeight && it.y < world.maxHeight - 1 })
        }
    }

    fun resolve(callback: (Location?) -> Unit) {
        plugin.server.globalRegionScheduler.execute(plugin) {
            val current = settings
            val world = configuredWorld(current.world)
            val location = world?.takeUnless { islands.isIslandWorld(it) }?.let {
                if (current.useWorldSpawn) it.spawnLocation else
                    Location(it, current.x, current.y, current.z, current.yaw, current.pitch)
            }
            callback(location?.takeIf { it.y >= it.world!!.minHeight && it.y < it.world!!.maxHeight - 1 })
        }
    }

    fun teleport(player: Player) = resolve { location ->
        FoliaTasks.entity(plugin, player) {
            if (location == null) locales.send(player, Lang.HUB_UNAVAILABLE) else {
                player.closeInventory()
                try {
                    player.teleportAsync(location).whenComplete { success, error ->
                        FoliaTasks.entity(plugin, player) {
                            if(error==null&&success==true&&isHubWorld(player.world))onArrival?.invoke(player)
                            locales.send(player, if (error == null && success == true) Lang.HUB_TELEPORTED else Lang.HUB_FAILED)
                        }
                    }
                } catch (e: Exception) {
                    plugin.logger.warning("Hub teleport failed for ${player.uniqueId}: ${e.message}")
                    locales.send(player, Lang.HUB_FAILED)
                }
            }
        }
    }

    /** Called on the administrator's entity thread. Snapshot first; config IO runs asynchronously. */
    fun setHere(player: Player) {
        val location = player.location
        if (location.world?.let(islands::isIslandWorld) == true || location.world?.key()?.namespace()=="oneblock_dungeons") { locales.send(player, Lang.HUB_INVALID); return }
        val next = HubSettings(location.world!!.key().asString(), false, location.x, location.y, location.z, location.yaw, location.pitch)
        val actorName = player.name
        FoliaTasks.async(plugin) {
            try {
                persist(next)
                plugin.logger.info("[ADMIN] $actorName: sethub ${next.world} ${next.x} ${next.y} ${next.z}")
                FoliaTasks.entity(plugin, player) { locales.send(player, Lang.HUB_SET) }
            } catch (e: Exception) {
                plugin.logger.log(java.util.logging.Level.WARNING, "Could not save hub destination", e)
                FoliaTasks.entity(plugin, player) { locales.send(player, Lang.ADMIN_FAILED) }
            }
        }
    }
}
