package com.alytanta.oneblock.hub

import com.alytanta.oneblock.config.PluginConfig
import com.alytanta.oneblock.island.IslandService
import com.alytanta.oneblock.locale.LocaleService
import com.alytanta.oneblock.menu.MenuFixture.Companion.stub
import com.alytanta.oneblock.player.PlayerService
import com.alytanta.oneblock.storage.StorageService
import io.papermc.paper.threadedregions.scheduler.GlobalRegionScheduler
import net.kyori.adventure.key.Key
import org.bukkit.*
import org.bukkit.plugin.Plugin
import org.bukkit.configuration.file.YamlConfiguration
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.util.logging.Logger

class HubRespawnTest {
    @Test fun `respawn and hub travel share exact coordinates facing world spawn mode and reloads`() {
        fun world(name: String, key: String): World {
            lateinit var world: World
            world=stub(World::class.java) { method,_->when(method) {
                "getName" -> name
                "getKey","key" -> Key.key(key)
                "getSpawnLocation" -> Location(world,4.5,80.0,4.5,25f,0f)
                "getMinHeight" -> -64
                "getMaxHeight" -> 320
                else -> error(method)
            } }
            return world
        }
        val hubWorld=world("hub","minecraft:overworld")
        val other=world("other","minecraft:the_nether")
        val global=stub(GlobalRegionScheduler::class.java) { name,args -> check(name=="execute");(args!![1] as Runnable).run();null }
        val server=stub(Server::class.java) { name,_->when(name) {
            "getWorlds" -> listOf(hubWorld,other)
            "getGlobalRegionScheduler" -> global
            else -> error(name)
        } }
        val plugin=stub(Plugin::class.java) { name,_->when(name) {
            "getServer" -> server
            "getLogger" -> Logger.getAnonymousLogger()
            else -> error(name)
        } }
        val storage=stub(StorageService::class.java) { name,_->when(name) {
            "loadProfiles","loadIslands" -> emptyList<Any>()
            else -> error(name)
        } }
        val players=PlayerService(storage)
        val islands=IslandService(plugin,PluginConfig.load(YamlConfiguration(),Logger.getAnonymousLogger()),storage,players)
        val service=HubService(plugin,HubSettings("hub",false,30.5,90.0,-12.5,135f,-15f),islands,LocaleService(plugin,"uk",players))
        var hub: Location?=null;var respawn: Location?=null
        service.resolve { hub=it };service.resolveWorldSpawn(hubWorld) { respawn=it }
        assertEquals(hub,respawn);assertEquals(30.5,respawn!!.x);assertEquals(135f,respawn!!.yaw)
        service.resolveWorldSpawn(other) { respawn=it };assertEquals(other.spawnLocation,respawn)
        service.applySettings(HubSettings("hub",true,300.0,100.0,300.0,0f,0f))
        service.resolveWorldSpawn(hubWorld) { respawn=it };assertEquals(hubWorld.spawnLocation,respawn)
        service.applySettings(HubSettings("other",false,40.5,70.0,40.5,0f,0f))
        service.resolveWorldSpawn(hubWorld) { respawn=it };assertEquals(hubWorld.spawnLocation,respawn)
    }
}
