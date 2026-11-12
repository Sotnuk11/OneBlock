package com.alytanta.oneblock.guide

import org.bukkit.Material
import org.bukkit.configuration.file.YamlConfiguration
import java.io.File

data class GuideTopic(val id:String,val icon:Material,val pages:List<String>,val action:String,val visible:Boolean=true,val permission:String?=null)
data class GuideSettings(val topics:List<GuideTopic>,val intro:Boolean=true) {
    companion object {
        val IDS=listOf("start","core","skills","workshop","charge","merchant","bazaar","auction","claims","profiles-coop","survival","hub")
        val ACTIONS=setOf("profiles","home","core","skills","workshop","charge","claims","coop","hub","merchant","bazaar","auction","recovery")
        fun read(file:File):Pair<GuideSettings?,String?> {
            if(!file.exists())return null to null
            val source=file.readText(Charsets.UTF_8);com.alytanta.oneblock.util.StrictYaml.validate(source)
            val y=YamlConfiguration().apply {loadFromString(source)}
            require(y.getInt("version")==1)
            val section=requireNotNull(y.getConfigurationSection("topics"))
            require(section.getKeys(false).toSet()==IDS.toSet())
            val topics=section.getKeys(false).map { id -> val s=section.getConfigurationSection(id)!!
                val pages=s.getStringList("pages").toMutableList()
                if(y.getBoolean("hub-generator-help",true) && id in setOf("hub","workshop")) {
                    val key=if(id=="hub")"guides.body.hub-generation" else "guides.body.hub-workshop"
                    if(key !in pages && pages.size<16)pages+=key
                }
                if(y.getBoolean("combat-help",true) && id in setOf("skills","workshop","claims")) {
                    val key="guides.body.combat-$id"
                    if(key !in pages && pages.size<16)pages+=key
                }
                if(y.getBoolean("mine-help",true) && id in setOf("hub","survival","claims")) {
                    val key="guides.body.mine-$id"
                    if(key !in pages && pages.size<16)pages+=key
                }
                GuideTopic(id,Material.valueOf(s.getString("icon")!!),pages.also { require(it.isNotEmpty() && it.size<=16 && it.all { key->key.matches(Regex("guides\\.[a-z0-9_.-]+")) }) },
                    s.getString("action")!!.also {require(it in ACTIONS)},s.getBoolean("visible",true),s.getString("permission")) }
            require(topics.any { it.visible })
            return GuideSettings(topics,y.getBoolean("intro",true)) to source
        }
    }
}
