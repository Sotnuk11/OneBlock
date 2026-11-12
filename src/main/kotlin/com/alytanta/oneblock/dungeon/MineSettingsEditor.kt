package com.alytanta.oneblock.dungeon

import com.alytanta.oneblock.combat.CombatRules
import com.alytanta.oneblock.util.StrictYaml
import org.bukkit.configuration.ConfigurationSection
import org.bukkit.configuration.file.YamlConfiguration
import java.nio.file.*
import java.nio.channels.FileChannel
import java.nio.ByteBuffer
import java.util.UUID

/** Edits only existing dungeon leaves. Existing unrelated values survive; every published edit has a backup. */
class MineSettingsEditor(private val file:Path,private val catalog:()->CombatRules?) {
    data class Field(val path:String,val value:String)
    data class View(val source:String,val fields:List<Field>)
    private val roots=setOf("name","description","enabled","allow-instance-worlds","max-sessions","attempts","reconnect-seconds","max-seconds","blocks-per-batch","entry","mobs","boss","scaling","puzzle","death-rewards","rewards","messages","sidebar")
    private fun raw(v:Any?):Any?=when(v) {is ConfigurationSection->v.getValues(false).mapValuesTo(linkedMapOf()) {raw(it.value)};is Map<*,*>->v.entries.associateTo(linkedMapOf()) {it.key.toString() to raw(it.value)};is List<*>->v.mapTo(arrayListOf()) {raw(it)};else->v}
    fun view():View {
        val source=Files.readString(file);StrictYaml.validate(source);val y=YamlConfiguration().apply {options().parseComments(true);loadFromString(source)}
        val out=mutableListOf<Field>()
        fun walk(path:String,v:Any?) {when(v) {is Map<*,*>->v.forEach {(k,value)->walk(if(path.isEmpty())k.toString() else "$path.$k",value)};is List<*>->v.forEachIndexed {i,value->walk("$path[$i]",value)};else->if(path.substringBefore('.').substringBefore('[') in roots)out+=Field(path,v.toString())}}
        walk("",raw(y));return View(source,out)
    }
    fun edit(view:View,path:String,input:String):MineSettings {
        require(path in view.fields.map {it.path} && input.length<=512)
        check(Files.readString(file)==view.source) {"Configuration changed; reopen the editor"}
        val y=YamlConfiguration().apply {options().parseComments(true);loadFromString(view.source)}
        @Suppress("UNCHECKED_CAST") val root=raw(y) as MutableMap<String,Any?>
        val tokens=Regex("([a-z0-9_-]+)|\\[(\\d+)]").findAll(path).map {m->m.groups[1]?.value?:m.groups[2]!!.value.toInt()}.toList()
        var node:Any=root
        for(t in tokens.dropLast(1))node=if(t is Int)(node as List<*>)[t]!! else (node as Map<*,*>)[t]!!
        val last=tokens.last();val old=if(last is Int)(node as List<*>)[last] else (node as Map<*,*>)[last]
        val value:Any=when(old) {is Boolean->input.toBooleanStrict();is Int,is Long->input.toLong();is Number->input.toDouble().also {require(it.isFinite())};else->input}
        @Suppress("UNCHECKED_CAST") if(last is Int)(node as MutableList<Any?>)[last]=value else (node as MutableMap<String,Any?>)[last.toString()]=value
        val top=path.substringBefore('.').substringBefore('[');y.set(top,root[top]);return publish(view.source,y)
    }
    fun entry(source:String,entry:MineEntry):MineSettings {
        check(Files.readString(file)==source)
        val y=YamlConfiguration().apply {options().parseComments(true);loadFromString(source)}
        y.set("entry.world",entry.world);y.set("entry.x",entry.point.x);y.set("entry.y",entry.point.y);y.set("entry.z",entry.point.z)
        return publish(source,y)
    }
    private fun publish(source:String,y:YamlConfiguration):MineSettings {
        val part=file.resolveSibling("${file.fileName}.candidate-${UUID.randomUUID()}")
        try {
            val bytes=y.saveToString().toByteArray(Charsets.UTF_8)
            FileChannel.open(part,StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE).use {ch->val b=ByteBuffer.wrap(bytes);while(b.hasRemaining())ch.write(b);ch.force(true)}
            val settings=MineSettings.read(part.toFile()).first!!
            if(settings.enabled)settings.validateCatalog(requireNotNull(catalog()) {"Combat disabled"})
            check(Files.readString(file)==source) {"Configuration changed during edit"}
            Files.copy(file,file.resolveSibling("${file.fileName}.before-admin-${System.currentTimeMillis()}-${UUID.randomUUID()}"))
            Files.move(part,file,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING)
            return settings
        } finally {Files.deleteIfExists(part)}
    }
}
