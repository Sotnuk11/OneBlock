package com.alytanta.oneblock.dungeon

import org.bukkit.configuration.file.YamlConfiguration
import java.util.UUID
import kotlin.math.min

data class WaveArena(val id:String,val size:Int,val height:Int,val material:String,val entry:MinePoint,val spawns:List<MinePoint>,val theme:String="plain") {
    fun point(p:MinePoint,slot:Int)=p.copy(x=p.x+slot*64)
    fun inside(x:Double,y:Double,z:Double,slot:Int)=x>slot*64 && x<slot*64+size && z>0 && z<size && y>=65 && y<65+height
}
data class WaveEnemy(val id:String,val type:String,val weight:Int,val health:Double,val damage:Double,val speed:Double,
    val name:String,val ability:String)
data class WavePool(val id:String,val boss:Boolean,val weight:Int,val enemies:List<String>)
data class WaveLimits(val entities:Int,val health:Double,val damage:Double,val speed:Double,val projectiles:Int=64)
data class WaveSpawn(val enemy:WaveEnemy,val health:Double,val damage:Double,val speed:Double,val point:MinePoint)
/** Exact plan (including rolls and bounded stats) is journaled before warning/spawn, and reused after a wipe. */
data class WavePlan(val number:Long,val boss:Boolean,val arena:Int,val pool:String,val spawns:List<WaveSpawn>)
data class WaveProgress(val plan:WavePlan,val completed:Long=0,val started:Long?=null,val elapsed:Long=0,
    val reached:Long?=null,val ended:Long?=null)
data class WaveRules(val version:String,val bossEvery:Int,val rewardLimit:Int,val warningTicks:Long,val pauseTicks:Long,
    val minDistance:Double,val count:Int,val countGrowth:Double,val healthGrowth:Double,val damageGrowth:Double,
    val speedGrowth:Double,val partyCount:Double,val partyHealth:Double,val partyDamage:Double,
    val limits:WaveLimits,val arenas:List<WaveArena>,val order:List<String>,val enemies:List<WaveEnemy>,val pools:List<WavePool>) {
    fun arena(plan:WavePlan)=arenas[plan.arena]
    fun room(number:Long)=(number-1)/bossEvery
    fun plan(run:UUID,number:Long,party:Int):WavePlan {
        require(number>0 && party in 1..4)
        val random=java.util.Random(run.mostSignificantBits xor run.leastSignificantBits xor number)
        fun <T> pick(values:List<T>,weight:(T)->Int):T {
            var roll=random.nextInt(values.sumOf(weight));return values.first {roll-=weight(it);roll<0}
        }
        val boss=number%bossEvery==0L
        val pool=pick(pools.filter {it.boss==boss}) {it.weight}
        val slot=arenas.indexOfFirst {it.id==order[(room(number)%order.size).toInt()]}
        val arena=arenas[slot]
        fun scaled(base:Double,growth:Double,partyGrowth:Double,cap:Double):Double =
            (base*(1+min(number.toDouble()-1,1e15)*growth)*(1+(party-1)*partyGrowth)).coerceIn(.01,cap)
        val total=if(boss)1 else min(limits.entities.toDouble(),count+(number-1).toDouble()*countGrowth+(party-1)*partyCount).toInt().coerceAtLeast(1)
        val spawn=List(total) {i->
            val enemy=pick(enemies.filter {it.id in pool.enemies}) {it.weight}
            WaveSpawn(enemy,scaled(enemy.health,healthGrowth,partyHealth,limits.health),
                scaled(enemy.damage,damageGrowth,partyDamage,limits.damage),scaled(enemy.speed,speedGrowth,0.0,limits.speed),arena.point(arena.spawns[i%arena.spawns.size],slot))
        }
        return WavePlan(number,boss,slot,pool.id,spawn)
    }
    companion object {
        fun read(source:YamlConfiguration):WaveRules {
            val y=if(source.contains("endless"))source else YamlConfiguration().apply {
                loadFromString(WaveRules::class.java.getResourceAsStream("/dungeon.yml")!!.reader().use {it.readText()})
            }
            fun int(k:String,min:Int,max:Int)=y.get("endless.$k").toString().toInt().also {require(it in min..max) {"endless.$k"}}
            fun num(k:String,min:Double,max:Double)=y.get("endless.$k").toString().toDouble().also {require(it.isFinite() && it in min..max) {"endless.$k"}}
            fun rows(k:String)=y.getMapList("endless.$k").also {require(it.isNotEmpty() && it.size<=32) {"endless.$k"}}
            fun id(v:Any?)=v.toString().also {require(it.matches(Regex("[a-z][a-z0-9_-]{0,47}")))}
            fun n(v:Any?,min:Double,max:Double)=v.toString().toDouble().also {require(it.isFinite() && it in min..max)}
            fun i(v:Any?,min:Int,max:Int)=v.toString().toInt().also {require(it in min..max)}
            fun p(v:Any?):MinePoint {val a=v as? List<*>?:error("Point must be [x,y,z]");require(a.size==3);return MinePoint(n(a[0],1.5,62.5),n(a[1],65.0,65.0),n(a[2],1.5,62.5))}
            val arenas=rows("arenas").map {r->
                val size=i(r["size"],12,48);val height=i(r["height"],5,48)
                val material=r["material"].toString().uppercase();require(material in setOf("STONE","STONE_BRICKS","MOSSY_STONE_BRICKS","DEEPSLATE_BRICKS","DEEPSLATE_TILES","ANDESITE","POLISHED_ANDESITE","BRICKS","NETHER_BRICKS","END_STONE_BRICKS")) {"Arena material must be a supported solid wall block"}
                val entry=p(r["entry"]);val spawns=(r["spawns"] as? List<*>)?.map(::p)?:error("spawns")
                require(spawns.size in 1..64 && (spawns+entry).all {it.x>=2 && it.z>=2 && it.x<=size-2 && it.z<=size-2})
                WaveArena(id(r["id"]),size,height,material,entry,spawns,(r["theme"]?:"plain").toString().also {require(it in ArenaThemes.NAMES) {"Unknown arena theme $it"}})
            }
            require(arenas.size<=8 && arenas.map {it.id}.distinct().size==arenas.size)
            val enemies=rows("enemies").map {r->
                val type=r["type"].toString().uppercase();require(type in setOf("ZOMBIE","SKELETON","HUSK","STRAY","IRON_GOLEM"))
                val ability=r["ability"]?.toString()?:"native";require(ability in setOf("native","guardian")) {"Only implemented abilities: native, guardian"}
                val name=r["name"]?.toString()?:id(r["id"]);require(name.length in 1..80 && name.none(Char::isISOControl))
                WaveEnemy(id(r["id"]),type,i(r["weight"],1,10000),n(r["health"],1.0,1024.0),n(r["damage"],.1,100.0),n(r["speed"],.01,.6),name,ability)
            }
            require(enemies.map {it.id}.distinct().size==enemies.size)
            val pools=rows("pools").map {r->
                require(r["boss"] is Boolean);val ids=(r["enemies"] as? List<*>)?.map(::id)?:error("pool enemies")
                require(ids.isNotEmpty() && ids.distinct().size==ids.size && ids.all {id->enemies.any {it.id==id}})
                WavePool(id(r["id"]),r["boss"] as Boolean,i(r["weight"],1,10000),ids)
            }
            require(pools.any {it.boss} && pools.any {!it.boss} && pools.map {it.id}.distinct().size==pools.size)
            val order=y.getStringList("endless.order");require(order.size in 1..128 && order.all {id->arenas.any {it.id==id}})
            val version=y.getString("endless.rules-version")!!;require(version.matches(Regex("[a-zA-Z0-9_.-]{1,48}")))
            return WaveRules(version,int("boss-every",1,100),int("reward-wave-limit",0,100),int("warning-ticks",20,200).toLong(),int("between-wave-ticks",1,200).toLong(),
                num("spawn-distance",2.0,8.0),int("count",1,64),num("growth.count",0.0,10.0),num("growth.health",0.0,2.0),num("growth.damage",0.0,1.0),num("growth.speed",0.0,.1),
                num("party.count",0.0,16.0),num("party.health",0.0,2.0),num("party.damage",0.0,1.0),
                WaveLimits(int("limits.entities",1,64),num("limits.health",1.0,1024.0),num("limits.damage",.1,100.0),num("limits.speed",.01,.6),
                    if(y.contains("endless.limits.projectiles"))int("limits.projectiles",1,128) else 64),arenas,order,enemies,pools)
        }
    }
}
