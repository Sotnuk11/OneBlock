package com.alytanta.oneblock.dungeon

import org.bukkit.configuration.file.YamlConfiguration
import kotlin.math.*

/** Frozen with a run. Optional on old journals; presentation never supplies combat numbers. */
data class MinePresentation(val palette:Map<String,String> = defaults,
    val particles:Boolean=true,val sounds:Boolean=true,val pulseTicks:Long=8,
    val color:Int=0xF4A340,val impactColor:Int=0xE45A50,
    val warningSound:String="minecraft:block.note_block.hat",val impactSound:String="minecraft:entity.iron_golem.attack") {
    companion object {
        val defaults=mapOf("entry-floor" to "andesite","puzzle-floor" to "stone_bricks","boss-floor" to "polished_deepslate",
            "wall" to "deepslate_bricks","cracked" to "cracked_deepslate_bricks","roof" to "deepslate_tiles",
            "wood" to "spruce_planks","log" to "spruce_log","trim" to "chiseled_deepslate","light" to "glowstone")
        fun read(y:YamlConfiguration):MinePresentation {
            val safe=defaults.values.toSet()+setOf("stone","cobblestone","mossy_stone_bricks","deepslate_tiles","dark_oak_planks","oak_planks","dark_oak_log","oak_log","sea_lantern")
            val palette=defaults.mapValues {(key,value)->y.getString("presentation.palette.$key",value)!!.also {require(it in safe) {"Unsafe mine palette: $key"}}}
            fun flag(key:String):Boolean {val path="presentation.$key";require(!y.contains(path)||y.isBoolean(path));return y.getBoolean(path,true)}
            fun color(key:String,default:String)=y.getString("presentation.$key",default)!!.let {require(it.matches(Regex("#[0-9a-fA-F]{6}"))) {key};it.drop(1).toInt(16)}
            fun sound(key:String,default:String)=y.getString("presentation.$key",default)!!.also {require(it.matches(Regex("minecraft:[a-z0-9_.]{1,80}"))) {key}}
            val interval=y.getLong("presentation.pulse-ticks",8);require(interval in 4..20 && (!y.contains("presentation.pulse-ticks") || y.get("presentation.pulse-ticks").toString().toLongOrNull()!=null))
            return MinePresentation(palette,flag("particles"),flag("sounds"),interval,color("warning-color","#F4A340"),color("impact-color","#E45A50"),
                sound("warning-sound","minecraft:block.note_block.hat"),sound("impact-sound","minecraft:entity.iron_golem.attack"))
        }
    }
}

/** One geometry supplies both the exact hit predicate and its boundary, including non-integral lengths. */
data class MineAttackShape(val kind:Int,val origin:MinePoint,val aim:MinePoint,val length:Double,val width:Double,val radius:Double) {
    private val distance=hypot(aim.x-origin.x,aim.z-origin.z)
    private val nx=if(distance<.01)0.0 else (aim.x-origin.x)/distance
    private val nz=if(distance<.01)1.0 else (aim.z-origin.z)/distance
    fun contains(x:Double,z:Double):Boolean = if(kind==1) hypot(x-aim.x,z-aim.z)<=radius else {
        val tx=x-origin.x;val tz=z-origin.z
        tx*nx+tz*nz in 0.0..length && abs(tx*nz-tz*nx)<=width
    }
    fun boundary():List<MinePoint> = if(kind==1) {
        val count=max(24,ceil(2*PI*radius/.65).toInt())
        (0 until count).map {n->val a=n*2*PI/count;MinePoint(aim.x+radius*cos(a),65.15,aim.z+radius*sin(a))}
    } else {
        val forward=ceil(length/.65).toInt();val across=ceil(2*width/.65).toInt()
        fun point(d:Double,s:Double)=MinePoint(origin.x+nx*d-nz*s,65.15,origin.z+nz*d+nx*s)
        (0..forward).flatMap {i->listOf(point(length*i/forward,-width),point(length*i/forward,width))}+
            (1 until across).flatMap {i->listOf(point(0.0,-width+2*width*i/across),point(length,-width+2*width*i/across))}
    }
}
