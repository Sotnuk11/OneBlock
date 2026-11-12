package com.alytanta.oneblock.hub

import org.bukkit.GameMode
import org.bukkit.configuration.ConfigurationSection

data class HubRules(val enabled:Boolean=true,val adventure:Boolean=true,val pvp:Boolean=false,val collisions:Boolean=false,
    val mobSpawning:Boolean=false,val islandMode:GameMode=GameMode.SURVIVAL) {
    companion object {
        fun load(config:ConfigurationSection):HubRules {
            listOf("enabled","adventure","pvp","collisions","mob-spawning").forEach {
                require(!config.contains("hub.rules.$it")||config.isBoolean("hub.rules.$it"))
            }
            return HubRules(config.getBoolean("hub.rules.enabled",true),config.getBoolean("hub.rules.adventure",true),
                config.getBoolean("hub.rules.pvp",false),config.getBoolean("hub.rules.collisions",false),config.getBoolean("hub.rules.mob-spawning",false),
                GameMode.valueOf(config.getString("hub.rules.island-game-mode","SURVIVAL")!!.uppercase()))
        }
    }
}
