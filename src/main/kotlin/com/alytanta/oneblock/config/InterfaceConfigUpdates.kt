package com.alytanta.oneblock.config

import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.plugin.Plugin
import java.io.File
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.security.MessageDigest
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Properties

/**
 * Replaces interface files once for each new bundled version, keeping a backup of the old bytes.
 * Edits made after an update survive until that bundled file changes again.
 * Files with server settings (hub, progression) are never replaced whole. Listed sections are:
 * dungeon.yml gets messages, room rewards and rooms, combat.yml recipes, progression.yml recipes, chapters and prices, config.yml Bazaar products, auction.yml allowed gear; items.yml gains missing entries and takes the listed gear.
 */
object InterfaceConfigUpdates {
    val replaced=listOf("lang/uk.yml","lang/en.yml","sidebar.yml","market-menus.yml","interface-menus.yml")+
        listOf("main-menu","release","guides","combat","dungeon").flatMap {g->listOf("layout","commands","statistics").map {"menus/$g/$it.yml"}}
    private val sections=mapOf("dungeon.yml" to listOf("messages","room-rewards"),"combat.yml" to listOf("recipes","rewards"),"progression.yml" to listOf("recipes","chapters","daily","drop-tables"),"config.yml" to listOf("market.products","market.groups"),"auction.yml" to listOf("auction.categories"),"activities.yml" to listOf("texts"),"items.yml" to listOf("items.oneblock:pickaxe_1","items.oneblock:pickaxe_2","items.oneblock:pickaxe_3","items.oneblock:axe_1","items.oneblock:axe_2","items.oneblock:axe_3","items.oneblock:ember_shortbow","items.oneblock:rift_staff","items.oneblock:nether_edge","abilities.oneblock:shortbow","abilities.oneblock:precise","items.oneblock:warden_helmet","items.oneblock:warden_chestplate","items.oneblock:warden_leggings","items.oneblock:warden_boots","items.oneblock:ranger_helmet","items.oneblock:ranger_chestplate","items.oneblock:ranger_leggings","items.oneblock:ranger_boots","items.oneblock:weaver_helmet","items.oneblock:weaver_chestplate","items.oneblock:weaver_leggings","items.oneblock:weaver_boots"),"profiles.yml" to (1..18).map {"profile-slot-$it"})
    private val lists=mapOf("dungeon.yml" to listOf("endless.arenas","endless.order"),"progression.yml" to listOf("upgrades.fortune","upgrades.territory","focus.nature"),"activities.yml" to listOf("orders","events","recipes"),"auction.yml" to listOf("auction.allowed-items"))
    private val values=mapOf("progression.yml" to listOf("upgrades.focus"),"activities.yml" to listOf("readiness","order-cooldown-hours"))
    private val additions=mapOf("items.yml" to listOf("abilities","items"))
    private const val STATE="config-update-state.properties"

    fun apply(plugin:Plugin) {
        val folder=plugin.dataFolder
        val enabled=YamlConfiguration.loadConfiguration(folder.resolve("config.yml")).getBoolean("auto-update-interface-configs",true)
        if(!enabled)return
        val result=apply(folder,plugin::getResource)
        if(result.updated.isNotEmpty())plugin.logger.info("Updated interface configs: ${result.updated.joinToString()}; old copies: ${result.backup}")
        result.failed.forEach {(name,error)->plugin.logger.warning("Could not update $name, keeping the current file: $error")}
    }
    data class Result(val updated:List<String>,val failed:Map<String,String>,val backup:Path)

    @Synchronized
    fun apply(folder:File,resource:(String)->InputStream?):Result {
        val stateFile=folder.resolve(STATE)
        val state=Properties().apply {if(stateFile.isFile)stateFile.inputStream().use(::load)}
        val backup=folder.toPath().resolve("backups/config-update-"+LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")))
        val updated=mutableListOf<String>();val failed=linkedMapOf<String,String>()
        for(name in (replaced+sections.keys+additions.keys).distinct()) {
            try {
                val bundled=requireNotNull(resource(name)) {"Missing bundled resource"}.use {it.readBytes()}
                val hash=sha256(if(name in replaced)bundled else summary(name,bundled).toByteArray(Charsets.UTF_8))
                if(state.getProperty(name)==hash)continue
                val file=target(folder,name)
                val current=Files.readAllBytes(file)
                val next=if(name in replaced)bundled else merge(name,current,bundled)
                if(!current.contentEquals(next)) {
                    val copy=backup.resolve(name);Files.createDirectories(copy.parent);Files.write(copy,current)
                    replace(file,next);updated.add(name)
                }
                state.setProperty(name,hash)
            } catch(e:Exception) {failed[name]=e.message?:e.javaClass.simpleName}
        }
        val staged=Files.createTempFile(folder.toPath(),".oneblock-state-",".tmp")
        try {
            Files.newOutputStream(staged).use {state.store(it,"Bundled versions already applied. Delete a line to apply that file again.")}
            Files.move(staged,stateFile.toPath(),REPLACE_EXISTING,ATOMIC_MOVE)
        } finally {Files.deleteIfExists(staged)}
        return Result(updated,failed,backup)
    }
    private fun target(folder:File,name:String)=
        (if(name.startsWith("menus/"))folder.parentFile.resolve(name) else folder.resolve(name)).toPath()
    private fun yaml(bytes:ByteArray)=YamlConfiguration().apply {loadFromString(bytes.toString(Charsets.UTF_8))}
    private fun summary(name:String,bundled:ByteArray):String {
        val y=yaml(bundled)
        return (sections[name].orEmpty()+additions[name].orEmpty()).joinToString {y.getConfigurationSection(it)!!.getValues(true).filterValues {v->v !is org.bukkit.configuration.ConfigurationSection}.toSortedMap().toString()}+lists[name].orEmpty().joinToString {y.getList(it).toString()}+values[name].orEmpty().joinToString {y.get(it).toString()}
    }
    private fun merge(name:String,current:ByteArray,bundled:ByteArray):ByteArray {
        val target=yaml(current);val source=yaml(bundled)
        sections[name].orEmpty().forEach {path->
            target.set(path,null)
            source.getConfigurationSection(path)!!.getValues(true).filterValues {it !is org.bukkit.configuration.ConfigurationSection}.forEach {(key,value)->target.set("$path.$key",value)}
        }
        if(name !in sections && name in additions && additions.getValue(name).all {path->source.getConfigurationSection(path)!!.getKeys(false).all {target.contains("$path.$it")}})return current
        lists[name].orEmpty().forEach {target.set(it,source.getList(it))}
        values[name].orEmpty().forEach {target.set(it,source.get(it))}
        additions[name].orEmpty().forEach {path->
            val section=source.getConfigurationSection(path)!!
            section.getKeys(false).filterNot {target.contains("$path.$it")}.forEach {entry->
                val inner=section.getConfigurationSection(entry)!!
                inner.getValues(true).filterValues {it !is org.bukkit.configuration.ConfigurationSection}.forEach {(key,value)->target.set("$path.$entry.$key",value)}
            }
        }
        return target.saveToString().toByteArray(Charsets.UTF_8)
    }
    private fun replace(file:Path,bytes:ByteArray) {
        val staged=Files.createTempFile(file.parent,".oneblock-config-",".tmp")
        try {
            Files.write(staged,bytes)
            Files.move(staged,file,REPLACE_EXISTING,ATOMIC_MOVE)
        } finally {Files.deleteIfExists(staged)}
    }
    private fun sha256(bytes:ByteArray)=MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") {"%02x".format(it)}
}
