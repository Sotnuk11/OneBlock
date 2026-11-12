package com.alytanta.oneblock.activities

import com.alytanta.oneblock.release.*
import com.alytanta.oneblock.items.*
import org.bukkit.Material
import org.bukkit.configuration.file.YamlConfiguration
import java.io.File

data class ActivityRules(val enabled:Boolean,val orders:List<OrderRule>,val events:Map<String,IslandEventRule>,
    val readiness:Int,val creditMs:Long,val motionMs:Long,val actionMs:Long,val radius:Int,val npc:String,val display:String,
    val recipes:Map<String,RecipeRule>,val unlocks:Map<String,Long>,val texts:Map<String,String>,val orderCooldownMs:Long=14_400_000) {
    fun text(key:String,vararg values:Pair<String,Any>):String=values.fold(texts.getValue(key)) {s,(k,v)->s.replace("{$k}",v.toString())}
    fun offers(chapter:String,completed:Long):List<OrderRule> {
        val eligible=orders.filter {ReleaseRules.CHAPTERS.indexOf(it.chapter)<=ReleaseRules.CHAPTERS.indexOf(chapter)}
        val tier=eligible.maxOf {ReleaseRules.CHAPTERS.indexOf(it.chapter)}
        val pool=eligible.filter {ReleaseRules.CHAPTERS.indexOf(it.chapter)==tier}
        // One card of each kind, variants rotate as orders are completed; older configs without all kinds keep the plain window.
        val kinds=listOf("mine","grow","supply").map {type->pool.filter {it.type==type}}
        if(kinds.all {it.isNotEmpty()})return kinds.map {it[(completed%it.size).toInt()]}
        return (0..2).map {pool[((completed%pool.size)+it).toInt()%pool.size]}
    }
    fun validate(catalog:ItemCatalog?,base:ReleaseRules) {
        (orders.map {it.reward}+events.values.map {it.reward}).flatMap {it.items}.plus(recipes.values.flatMap {it.items}).forEach {item->
            require(item.amount in 1..64 && item.material !in setOf("AIR","WATER","LAVA"))
            item.tool?.let {id->val d=requireNotNull(catalog).require(id);require(d.material==item.material && d.kind!=ItemKind.ARMOR)}
        }
        require((recipes.keys intersect base.recipes.keys).isEmpty())
        require(unlocks.keys==recipes.keys)
    }
    fun validateMaterials() {
        (orders.map {it.reward}+events.values.map {it.reward}).flatMap {it.items}.forEach {require(Material.valueOf(it.material).isItem)}
        orders.flatMap {it.ingredients}.flatMap {it.materials}.forEach {require(Material.valueOf(it).isItem)}
        (orders.flatMap {it.blocks}+events.values.flatMap {it.blocks}).forEach {require(Material.valueOf(it).isBlock)}
    }
    companion object {
        fun read(file:File):ActivityRules {
            val s=if(file.exists())file.readText(Charsets.UTF_8) else ActivityRules::class.java.getResourceAsStream("/activities.yml")!!.reader().use {it.readText()}
            com.alytanta.oneblock.util.StrictYaml.validate(s)
            val y=YamlConfiguration().apply {loadFromString(s)}
            require(y.getInt("version")==1 && y.isBoolean("enabled"))
            fun num(v:Any?,min:Long,max:Long)=v.toString().toLong().also {require(it in min..max)}
            fun id(v:Any?)=v.toString().also {require(it.matches(Regex("[a-z][a-z0-9_]{0,47}")))}
            fun items(v:Any?)=(v as? List<*>).orEmpty().map {r->val m=r as Map<*,*>;ReleaseItem(m["material"].toString().also {Material.valueOf(it)},num(m["amount"],1,64).toInt(),m["tool"]?.let(::id))}.also {require(it.size<=8)}
            fun ingredients(v:Any?)=(v as? List<*>).orEmpty().map {r->val m=r as Map<*,*>;Ingredient(setOf(m["material"].toString().also {require(Material.valueOf(it).name !in setOf("AIR","WATER","LAVA"))}),num(m["amount"],1,4096).toInt())}.also {require(it.size<=8)}
            fun blocks(v:Any?)=(v as? List<*>).orEmpty().map {it.toString().also {Material.valueOf(it)}}.toSet().also {require(it.size<=64)}
            fun reward(v:Any?):ActivityReward {val m=v as Map<*,*>;return ActivityReward(m["skill"].toString().also {require(it in ReleaseRules.SKILLS)},num(m["xp"],0,10000),items(m["items"]),num(m["discovery"]?:0,0,100))}
            val orders=y.getMapList("orders").map {m->
                val type=m["type"].toString().also {require(it in setOf("mine","grow","supply"))}
                OrderRule(id(m["id"]),m["name"].toString(),m["chapter"].toString().also {require(it in ReleaseRules.CHAPTERS)},type,blocks(m["blocks"]),num(m["target"]?:1,1,10000).toInt(),ingredients(m["ingredients"]),reward(m["reward"]),(m["text"]?:"").toString().also {require(it.length<=120)})
                    .also {require(it.name.length in 1..80);require(if(type=="supply")it.ingredients.isNotEmpty() else it.blocks.isNotEmpty())}
            }.also {require(it.size in 3..64 && it.map {r->r.id}.distinct().size==it.size)}
            require(orders.filter {it.chapter=="start"}.size>=3 && orders.groupBy {it.chapter}.values.all {it.size>=3})
            val events=y.getMapList("events").associate {m->val kind=m["kind"].toString().also {require(it in setOf("resonance","echo"))}
                val health=m["health"]?.toString()?.toDouble()?:30.0;val damage=m["damage"]?.toString()?.toDouble()?:4.0
                require(health.isFinite() && health in 1.0..200.0 && damage.isFinite() && damage in 0.0..12.0)
                kind to IslandEventRule(kind,m["name"].toString(),num(m["goal"],1,1000).toInt(),num(m["interval-ms"],500,60000),num(m["duration-seconds"],120,600)*1000,
                    blocks(m["blocks"]),(m["hints"] as List<*>).map {it.toString()}.also {require(it.size in 1..12 && it.all {h->h.length in 1..240})},reward(m["reward"]),
                    (m["mob"]?:"ZOMBIE").toString().also {require(it in setOf("ZOMBIE","HUSK"))},health,damage)
            }.also {require(it.keys==setOf("resonance","echo"));require(it.getValue("resonance").blocks.isNotEmpty())}
            val recipes=linkedMapOf<String,RecipeRule>();val unlocks=linkedMapOf<String,Long>()
            y.getMapList("recipes").forEach {m->val key=id(m["id"]);require(key !in recipes);unlocks[key]=num(m["discovery"],1,10000)
                recipes[key]=RecipeRule(key,m["chapter"].toString().also {require(it in ReleaseRules.CHAPTERS)},null,0,0,ingredients(m["ingredients"]),items(m["items"]))}
            require(recipes.size<=12)
            val texts=y.getConfigurationSection("texts")!!.getValues(false).mapValues {it.value.toString()}
            require(listOf("unavailable","updated","unsafe","hint","status","order","none","ready","charging","paused","failed","discovery","quote","running").all {it in texts})
            return ActivityRules(y.getBoolean("enabled"),orders,events,num(y.get("readiness"),1,10000).toInt(),num(y.get("credit-ms"),500,10000),num(y.get("motion-ms"),5000,120000),num(y.get("action-ms"),500,60000),num(y.get("combat-radius"),4,12).toInt(),
                y.getString("npc.name")!!.also {require(it.matches(Regex("[A-Za-z0-9_/-]{1,48}")))},y.getString("npc.display")!!,recipes,unlocks,texts+("cooldown" to (texts["cooldown"]?:"&cНаступний квест через {time}")),(y.get("order-cooldown-hours")?:4).toString().toLong().also {require(it in 0..168)}*3_600_000)
        }
    }
}
