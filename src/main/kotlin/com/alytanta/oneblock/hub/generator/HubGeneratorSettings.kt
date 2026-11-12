package com.alytanta.oneblock.hub.generator

import com.alytanta.oneblock.util.StrictYaml
import org.bukkit.configuration.file.YamlConfiguration
import java.io.File

data class HubNpcAppearance(val type:String="VILLAGER",val skin:String="")
data class HubGeneratorSettings(val allowVoid:Boolean,val batchSize:Int,val inFlight:Int,val scanPerTick:Int,val delayTicks:Long,
    val language:String,val palette:Map<String,String>,val signs:Map<String,List<String>>,val messages:Map<String,String>,val hash:String,val guideNpc:String,
    val npcs:Map<String,HubNpcAppearance> = emptyMap()) {
    fun sign(role:String)=signs.getValue(role)
    fun message(key:String,vararg values:Pair<String,Any?>)=values.fold(messages.getValue(key)) { s,(k,v)->s.replace("{$k}",v.toString()) }
    companion object {
        private val safe=setOf("STONE_BRICKS","MOSSY_STONE_BRICKS","DEEPSLATE_BRICKS","POLISHED_ANDESITE","SMOOTH_STONE","SMOOTH_QUARTZ","CHISELED_STONE_BRICKS","POLISHED_DEEPSLATE","SPRUCE_PLANKS","OAK_PLANKS","DARK_OAK_PLANKS","STRIPPED_SPRUCE_LOG","STRIPPED_OAK_LOG","DARK_PRISMARINE","DEEPSLATE_TILES","BRICKS","GLASS","LIGHT_BLUE_STAINED_GLASS","SEA_LANTERN","GLOWSTONE","MOSS_BLOCK","GRASS_BLOCK")
        fun read(file:File):Pair<HubGeneratorSettings,String?> {
            val source=file.takeIf {it.exists()}?.readText(Charsets.UTF_8)
            val defaults=requireNotNull(javaClass.getResourceAsStream("/hub-generator.yml")).reader(Charsets.UTF_8).use {it.readText()}
            StrictYaml.validate(source?:defaults)
            val y=YamlConfiguration().apply {loadFromString(source?:defaults);setDefaults(YamlConfiguration().apply {loadFromString(defaults)})}
            y.options().copyDefaults(true)
            require(y.getInt("version")==1)
            require(y.isBoolean("allow-void-worlds"));val language=y.getString("language")!!;require(language in setOf("uk","en"))
            val palette=y.getConfigurationSection("palette")!!.getKeys(true).associateWith { y.getString("palette.$it")!!.also { name->require(name in safe){"Unsafe hub palette: $name"} } }
            require(palette.keys==setOf("stone","floor","path","trim","wood","log","roof","glass","light","garden","wall"))
            val roles=listOf("merchant","bazaar","auction","workshop","guide","dungeon1","dungeon2","dungeon3")
            val signs=roles.associateWith { y.getStringList("signs.$language.$it").also { lines->require(lines.size==4 && lines.all {it.length<=48}) } }
            val messages=y.getConfigurationSection("messages.$language")!!.getKeys(false).associateWith {y.getString("messages.$language.$it")!!}
            val npcRoles=listOf("merchant","bazaar","auction","guide")
            require(!y.contains("npcs") || y.isConfigurationSection("npcs")) {"npcs must be a mapping"}
            require(y.getConfigurationSection("npcs")?.getKeys(false).orEmpty().all {it in npcRoles}) {"npcs contains an unknown service role"}
            val npcs=npcRoles.associateWith {role->
                val path="npcs.$role";val section=y.getConfigurationSection(path)
                val type=section?.getString("type","VILLAGER")?:"VILLAGER"
                require(type in setOf("VILLAGER","WANDERING_TRADER","PLAYER")) {"$path.type must be VILLAGER, WANDERING_TRADER or PLAYER"}
                val skin=section?.getString("skin","")?:""
                require(skin.isEmpty() || skin.matches(Regex("[A-Za-z0-9_]{1,16}|[0-9a-fA-F-]{36}")) ||
                    skin.matches(Regex("https://textures\\.minecraft\\.net/texture/[0-9a-fA-F]+"))) {"$path.skin must be a profile name, UUID or textures.minecraft.net URL"}
                require(type=="PLAYER" || skin.isEmpty()) {"$path.skin is only supported for type PLAYER"}
                HubNpcAppearance(type,skin)
            }
            fun number(key:String,min:Int,max:Int)=y.getInt(key).also {require(it in min..max){"Invalid $key"}}
            // New command hints do not invalidate existing pinned hub journals.
            val hashYaml=YamlConfiguration().apply {
                loadFromString(y.saveToString())
                for(lang in listOf("uk","en"))for(key in listOf("command-help","next-preview","next-job","preview-missing","preview-stale","job-ambiguous"))set("messages.$lang.$key",null)
                set("npcs",null) // Service appearance belongs to manually placed NPCs, not to a pinned block plan.
            }
            return HubGeneratorSettings(y.getBoolean("allow-void-worlds"),number("blocks-per-batch",16,512),number("in-flight-chunks",1,2),
                number("scan-per-tick",256,8192),number("batch-delay-ticks",1,20).toLong(),language,palette,signs,messages,HubPlan.hashText(hashYaml.saveToString()),
                y.getString("guide-npc")!!.also {require(it.matches(Regex("[a-zA-Z0-9_-]{1,48}")))},npcs) to source
        }
    }
}
