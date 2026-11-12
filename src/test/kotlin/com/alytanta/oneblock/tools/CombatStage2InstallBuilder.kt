package com.alytanta.oneblock.tools

import com.alytanta.oneblock.items.ItemCatalog
import com.alytanta.oneblock.combat.CombatRules
import com.alytanta.oneblock.util.StrictYaml
import com.google.gson.GsonBuilder
import org.bukkit.configuration.file.YamlConfiguration
import java.io.File
import java.security.MessageDigest

/** Public YAML only. Creates reviewable staging; never writes into its source directory. */
object CombatStage2InstallBuilder {
    @JvmStatic fun main(args:Array<String>) {
        require(args.size==1 && args[0].isNotBlank()) {"Supply -PcombatConfigs=<public OneBlock config directory>"}
        val source=File(args[0]).canonicalFile;val output=File("build/combat-stage2-install").canonicalFile
        require(source!=output && !source.toPath().startsWith(output.toPath()))
        val hashes=linkedMapOf<String,String>()
        fun sha(file:File)=MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") {"%02x".format(it)}
        fun read(file:File):YamlConfiguration {
            require(file.isFile) {"Missing public file $file"};hashes[file.path]=sha(file)
            val text=file.readText();StrictYaml.validate(text);return YamlConfiguration().apply {loadFromString(text)}
        }
        fun template(name:String)=YamlConfiguration().apply {loadFromString(CombatStage2InstallBuilder::class.java.getResourceAsStream("/$name")!!.reader().use {it.readText()})}
        fun copy(from:YamlConfiguration,to:YamlConfiguration,path:String) {
            to.set(path,null)
            val section=from.getConfigurationSection(path)
            if(section==null)to.set(path,from.get(path)) else section.getKeys(true).filterNot(section::isConfigurationSection).forEach {key->to.set("$path.$key",section.get(key))}
        }
        val items=read(source.resolve("items.yml"));val defaults=template("items.yml")
        val knownAbilities=defaults.getConfigurationSection("abilities")!!.getKeys(false)
        defaults.getConfigurationSection("abilities")!!.getKeys(false).forEach {copy(defaults,items,"abilities.$it")}
        defaults.getConfigurationSection("items")!!.getKeys(false).forEach {id->
            val path="items.$id";val fields=listOf("name","color","head-texture","head-profile","market-transfer","vanilla-repair","stack-size")+
                if(defaults.getString("$path.kind")=="ARMOR")emptyList() else listOf("model")
            if(items.contains(path)) {
                require(items.getString("$path.kind")==defaults.getString("$path.kind") && items.getString("$path.slot")==defaults.getString("$path.slot")) {"Existing ID $id has a different kind/slot; manual migration required"}
                val old=items.getString("$path.material")
                require(old==defaults.getString("$path.material") || old in defaults.getStringList("$path.migration-from")) {"Existing ID $id has a different carrier; manual migration required"}
            }
            val appearance=fields.associateWith {items.get("$path.$it")}
            val oldStats=items.getConfigurationSection("$path.stats")?.getValues(false).orEmpty()
            val oldTags=items.getStringList("$path.tags")
            val customBindings=items.getMapList("$path.abilities").filter {it["ability"].toString() !in knownAbilities}
            copy(defaults,items,path)
            appearance.filterValues {it!=null}.forEach {(key,value)->items.set("$path.$key",value)}
            oldStats.filterKeys {!items.contains("$path.stats.$it")}.forEach {(key,value)->items.set("$path.stats.$key",value)}
            items.set("$path.tags",(items.getStringList("$path.tags")+oldTags).distinct())
            if(customBindings.isNotEmpty())items.set("$path.abilities",items.getMapList("$path.abilities")+customBindings)
            if(appearance["head-texture"]!=null)items.set("$path.head-profile",null)
        }
        defaults.getConfigurationSection("sets")!!.getKeys(false).forEach {copy(defaults,items,"sets.$it")}
        val combat=read(source.resolve("combat.yml"));val combatDefault=template("combat.yml")
        combat.set("items",null);combat.set("sets",null)
        combatDefault.getConfigurationSection("recipes")!!.getKeys(false).filter {it.startsWith("ranger_") && !combat.contains("recipes.$it")}.forEach {copy(combatDefault,combat,"recipes.$it")}
        for(key in listOf("demo","magic-trail","head-enchant-level-cost","boss-control-duration-scale"))if(!combat.contains(key))copy(combatDefault,combat,key)
        combatDefault.getConfigurationSection("messages")!!.getKeys(false).filter {!combat.contains("messages.$it")}.forEach {copy(combatDefault,combat,"messages.$it")}
        output.resolve("plugins/one-block").mkdirs()
        val itemFile=output.resolve("plugins/one-block/items.yml");val combatFile=output.resolve("plugins/one-block/combat.yml")
        items.save(itemFile);combat.save(combatFile)
        val pack=read(source.resolve("resource-pack.yml"));pack.set("enabled",false);pack.set("required",false);pack.save(output.resolve("plugins/one-block/resource-pack.yml"))
        val catalog=ItemCatalog.read(itemFile).first!!
        val tools=(1..3).flatMap {tier->listOf("pickaxe_$tier","axe_$tier","hoe_$tier")}.toSet()
        CombatRules.read(combatFile,catalog,tools)
        for(name in listOf("commands","statistics","layout")) {
            val currentFile=source.parentFile.resolve("menus/combat/$name.yml")
            val next=if(currentFile.isFile)read(currentFile) else template("menus/combat/$name.yml")
            val fresh=template("menus/combat/$name.yml")
            when(name) {
                "commands"->fresh.getConfigurationSection("commands")!!.getKeys(false).filter {it.startsWith("combat-craft-ranger_")}.forEach {key->
                    require(!next.contains("commands.$key") || listOf("type","action","value","command","recipe").all {next.getString("commands.$key.$it")==fresh.getString("commands.$key.$it")}) {"Custom command conflict $key"}
                    if(!next.contains("commands.$key"))copy(fresh,next,"commands.$key")
                }
                "statistics"->fresh.getConfigurationSection("values")!!.getKeys(false).filter {it.startsWith("release_recipe_ranger_")}.forEach {key->
                    require(!next.contains("values.$key") || next.getString("values.$key.source")==fresh.getString("values.$key.source")) {"Custom statistic conflict $key"}
                    if(!next.contains("values.$key"))copy(fresh,next,"values.$key")
                }
                "layout"->{
                    val path="menus.combat-armor.slots";val slots=next.getConfigurationSection(path)?:error("Custom combat armor menu missing")
                    fresh.getConfigurationSection(path)!!.getKeys(false).filter {fresh.getString("$path.$it.custom-item")?.startsWith("oneblock:ranger_")==true}.forEach {key->
                        val id=fresh.getString("$path.$key.custom-item")
                        if(slots.getKeys(false).none {slots.getString("$it.custom-item")==id}) {
                            val free=(10..43).firstOrNull {!slots.contains(it.toString())}?:error("No free slot for $id; manual menu merge required")
                            val value=fresh.getConfigurationSection("$path.$key")!!
                            value.getKeys(true).filterNot(value::isConfigurationSection).forEach {next.set("$path.$free.$it",value.get(it))}
                        }
                    }
                }
            }
            val target=output.resolve("plugins/menus/combat/$name.yml");target.parentFile.mkdirs();next.save(target)
        }
        hashes.forEach {(path,before)->check(sha(File(path))==before) {"Public source changed during staging: $path"}}
        val manifest=mapOf("sourceHashes" to hashes,"sourceUnchanged" to true,"items" to catalog.items.size,
            "review" to "Known item stats/abilities and set properties updated. Display names/colors/skins and existing recipe prices preserved. Review custom balance, menu positions and YAML before installation. No installation, secrets or database access.")
        output.resolve("manifest.json").writeText(GsonBuilder().setPrettyPrinting().create().toJson(manifest))
        println("Prepared review-only stage $output; ${catalog.items.size} item IDs; public sources unchanged")
    }
}
