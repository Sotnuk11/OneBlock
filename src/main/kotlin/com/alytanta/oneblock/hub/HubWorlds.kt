package com.alytanta.oneblock.hub

import org.bukkit.World
import org.bukkit.plugin.Plugin

object HubWorlds {
    fun island(world:World)=world.key().namespace()=="oneblock"&&world.key().value().startsWith("island_")
    fun resolve(plugin:Plugin,value:String):World? = if(':' in value)plugin.server.getWorld(net.kyori.adventure.key.Key.key(value))?.takeUnless(::island)
        else plugin.server.worlds.filter { it.name==value&&!island(it) }.let { worlds -> worlds.firstOrNull { it.key().asString()=="minecraft:overworld" }?:worlds.singleOrNull() }
}
