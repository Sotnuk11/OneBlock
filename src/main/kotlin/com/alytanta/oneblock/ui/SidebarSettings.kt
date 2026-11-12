package com.alytanta.oneblock.ui

import org.bukkit.configuration.file.YamlConfiguration
import java.io.File
import java.time.ZoneId

data class SidebarLanguage(val title:String,val lines:List<String>,val hub:String,val island:String,val elsewhere:String,
    val solo:String,val coop:String,val noProfile:String,val available:String,val unavailable:String)
data class SidebarSettings(val enabled:Boolean=true,val interval:Long=20,val zone:String="Europe/Kyiv",val languages:Map<String,SidebarLanguage> = emptyMap()) {
    companion object {
        fun read(file:File):Pair<SidebarSettings,String?> {
            if(!file.exists())return SidebarSettings(enabled=false) to null
            val source=file.readText(Charsets.UTF_8);val y=YamlConfiguration().apply { loadFromString(source) }
            require(!y.contains("enabled")||y.isBoolean("enabled"))
            val period=requireNotNull(y.get("update-ticks")?.toString()?.toLongOrNull()?:20L).also { require(it in 10..200) }
            val zone=y.getString("timezone","Europe/Kyiv")!!;ZoneId.of(zone)
            val languages=listOf("uk","en").associateWith { language ->
                val section=requireNotNull(y.getConfigurationSection("languages.$language"))
                fun text(key:String)=requireNotNull(section.getString(key)) { "sidebar $language.$key is required" }.also { require(it.length<=200) }
                val lines=section.getStringList("lines").also { require(it.size in 1..15 && it.all { line->line.length<=200 }) }
                SidebarLanguage(text("title"),lines,text("hub"),text("island"),text("elsewhere"),text("solo"),text("coop"),text("no-profile"),text("available"),text("unavailable"))
            }
            return SidebarSettings(y.getBoolean("enabled",true),period,zone,languages) to source
        }
    }
}
