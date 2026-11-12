package com.alytanta.oneblock.hub.generator

import com.google.gson.Gson
import java.security.MessageDigest
import java.util.UUID
import kotlin.math.abs

data class HubTarget(val key:String,val worldId:UUID,val x:Int,val y:Int,val z:Int,val rotation:Int=0) {
    init { require(rotation in listOf(0,90,180,270));require(abs(x.toLong())<29_999_900 && abs(z.toLong())<29_999_900) }
    fun point(dx:Int,dy:Int,dz:Int):HubPoint {
        val (a,b)=when(rotation) {90->-dz to dx;180->-dx to -dz;270->dz to -dx;else->dx to dz}
        return HubPoint(Math.addExact(x,a),Math.addExact(y,dy),Math.addExact(z,b))
    }
    fun contains(px:Double,py:Double,pz:Double,margin:Int=0)=px>=x-40-margin && px<x+41+margin &&
        pz>=z-40-margin && pz<z+41+margin && py>=y-16-margin && py<y+23+margin
}
data class HubPoint(val x:Int,val y:Int,val z:Int) { val chunkX get()=Math.floorDiv(x,16);val chunkZ get()=Math.floorDiv(z,16) }
data class HubBlock(val point:HubPoint,val data:String,val lines:List<String>?=null)
data class HubAnchor(val role:String,val point:HubPoint,val yaw:Float)
data class HubPlan(val version:Int,val target:HubTarget,val configHash:String,val blocks:List<HubBlock>,val anchors:List<HubAnchor>) {
    fun hash()=hashText(Gson().toJson(this))
    companion object { fun hashText(value:String)=MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) } }
}

/** Deterministic 81x81 floating settlement. No world access, random seeds, downloads or commands. */
object HubTemplate {
    fun plan(target:HubTarget,settings:HubGeneratorSettings):HubPlan {
        val blocks=linkedMapOf<HubPoint,HubBlock>();val anchors=mutableListOf<HubAnchor>()
        fun put(x:Int,y:Int,z:Int,role:String,lines:List<String>?=null) {
            require(x in -40..40 && z in -40..40 && y in -16..22)
            val point=target.point(x,y,z)
            val data=if(role.startsWith("minecraft:"))role else "minecraft:"+settings.palette.getValue(role).lowercase()
            blocks[point]=HubBlock(point,data,lines)
        }
        // Tapered geological underside, continuous walking surface and a contrasting compass rose.
        listOf(38,36,32,27,20,12).forEachIndexed { i,r -> for(x in -r..r)for(z in -r..r)if(x*x+z*z<=r*r)put(x,-i-1,z,if((x*3+z+i)%17==0)"wall" else "stone") }
        for(x in -40..40)for(z in -40..40)if(x*x+z*z<=1600) {
            put(x,0,z,when {x*x+z*z>1521->"trim";abs(x)<=2||abs(z)<=2||abs(x-z)<=1||abs(x+z)<=1->"path";else->"floor"})
            if(x*x+z*z>1521)put(x,1,z,"wall")
        }
        for(x in -7..7)for(z in -7..7)if(x*x+z*z<=49)put(x,0,z,if(abs(x)==abs(z)||x==0||z==0)"trim" else "path")
        // A continuous, level route reaches each service forecourt and the only active mine entrance.
        for(x in -29..29)for(z in -6..-2)put(x,0,z,"path")
        for(x in 17..21)for(z in -2..32)if(x*x+z*z<1450)put(x,0,z,"path")
        for(x in -25..-21)for(z in 0..25)put(x,0,z,"path")
        fun lamp(x:Int,z:Int) { for(y in 1..3)put(x,y,z,"log");put(x,4,z,"light");put(x,5,z,"roof") }
        listOf(-12,12).forEach {x->listOf(-12,12).forEach {z->lamp(x,z)} }
        listOf(-32 to -4,32 to -4,-7 to -22,7 to -22,-7 to 23,7 to 23,15 to 10,15 to 28,-29 to 16).forEach {(x,z)->lamp(x,z)}
        // Central waystone lies north of the spawn, never in its feet/head space.
        for(x in -2..2)for(z in -11..-7)put(x,1,z,"trim")
        for(y in 2..5)put(0,y,-9,"stone");put(0,6,-9,"light");put(0,7,-9,"roof")
        fun pavilion(role:String,cx:Int,cz:Int,turn:Int) {
            fun local(x:Int,y:Int,z:Int,material:String,lines:List<String>?=null) {
                val (a,b)=when(turn){90->-z to x;180->-x to -z;270->z to -x;else->x to z};put(cx+a,y,cz+b,material,lines)
            }
            for(x in -5..5)for(z in -4..4)local(x,0,z,"wood")
            for(x in listOf(-5,5))for(z in listOf(-4,4))for(y in 1..6)local(x,y,z,"log")
            for(x in -4..4)for(y in 1..4)local(x,y,-4,if(y in 2..3 && abs(x) in 1..3)"glass" else "wood")
            for(z in -3..3)for(x in listOf(-5,5))for(y in 1..3)local(x,y,z,if(y==2)"glass" else "wood")
            for(x in -5..5)local(x,5,4,"log")
            for(z in -5..5)for(x in -6..6) {val height=7+(5-abs(z))/2;local(x,height,z,"roof");if(abs(x)==6)local(x,height-1,z,"log")}
            for(x in -6..6)local(x,10,0,"log")
            // Braced eaves, stone footings and a broad porch frame the open entrance.
            for(x in listOf(-5,5))for(z in listOf(-4,4)) {local(x,1,z,"stone");local(x,4,z+if(z>0)-1 else 1,"log")}
            for(x in -5..5)for(z in 5..7)local(x,0,z,"wood")
            local(0,5,4,"light")
            local(-3,5,0,"light");local(3,5,0,"light")
            local(4,1,6,"minecraft:oak_sign[rotation=0]",settings.sign(role))
            anchors+=HubAnchor(role,target.point(cx,1,cz),((turn+target.rotation)%360).toFloat())
            when(role) {
                "workshop"->{local(-2,1,-2,"minecraft:crafting_table");local(2,1,-2,"minecraft:smithing_table")
                    for(y in 1..12)local(-4,y,-3,"stone");local(-4,13,-3,"wall");local(3,1,1,"minecraft:anvil")}
                "merchant"->{for(x in -3..3)local(x,1,-2,"wood");for(x in -3..-1)local(x,2,-2,"minecraft:hay_block")
                    for(x in -5..5)local(x,5,5,if(x%2==0)"roof" else "wood")}
                "bazaar"->{for(x in -3..3)local(x,1,-2,"wood");local(-3,2,-2,"garden");local(3,2,-2,"garden")
                    for(x in -5..5)local(x,5,5,if(x%2==0)"roof" else "trim")}
                "auction"->{for(x in -3..3)local(x,1,-2,"trim");local(0,2,-2,"light")
                    for(y in 10..13)local(0,y,-3,"stone");local(0,14,-3,"light");local(0,15,-3,"roof")}
                else->{for(x in -3..3)local(x,1,-2,"wood");for(x in listOf(-3,3))local(x,2,-2,"minecraft:bookshelf")}
            }
        }
        pavilion("merchant",-23,-4,270);pavilion("bazaar",23,-4,90);pavilion("auction",0,-25,0)
        pavilion("workshop",-23,22,180);pavilion("guide",0,23,180)
        // First gate delegates to the mine lobby when configured; the two others are reserved.
        listOf(14,22,30).forEachIndexed { index,cz ->
            for(z in cz-3..cz+3)for(x in 19..25)if(x*x+z*z<1450)put(x,0,z,"path")
            for(z in listOf(cz-2,cz+2))for(y in 1..5)put(22,y,z,"trim")
            for(z in cz-2..cz+2)put(22,6,z,"roof")
            put(22,7,cz,"light");put(20,1,cz,"minecraft:oak_sign[rotation=4]",settings.sign("dungeon${index+1}"))
            anchors+=HubAnchor("dungeon${index+1}",target.point(19,1,cz),(-90+target.rotation).toFloat())
        }
        // Corner watchtowers and planted courtyards complete the skyline and pedestrian edges.
        for(cx in listOf(-26,26))for(cz in listOf(-26,26)) {
            for(y in 1..7)for(dx in -2..2)for(dz in -2..2)if(abs(dx)==2||abs(dz)==2)
                put(cx+dx,y,cz+dz,if(y in 3..5 && (dx==0||dz==0))"glass" else "stone")
            for(r in 3 downTo 0)for(dx in -r..r)for(dz in -r..r)put(cx+dx,11-r,cz+dz,"roof")
            for(y in 12..14)put(cx,y,cz,"log");put(cx,15,cz,"light")
        }
        listOf(-13 to -22,13 to -22,-13 to 22,13 to 22,-26 to 10,26 to 7).forEach { (cx,cz) ->
            for(dx in -3..3)for(dz in -2..2)put(cx+dx,1,cz+dz,if(abs(dx)==3||abs(dz)==2)"trim" else "garden")
            for(y in 2..4)put(cx,y,cz,"log")
            for(y in 4..7) {
                val radius=when(y) {5->3;7->1;else->2}
                for(dx in -radius..radius)for(dz in -radius..radius)if(abs(dx)+abs(dz)<=radius+1 && (y!=4 || dx!=0 || dz!=0))
                    put(cx+dx,y,cz+dz,"minecraft:oak_leaves[persistent=true,distance=${minOf(7,1+abs(dx)+abs(dz)+abs(y-5))}]")
            }
        }
        // Terraced planted banks and exposed roots add relief without narrowing the main paths.
        listOf(-18 to -29,18 to -29,-32 to 7,31 to 14,-12 to 31).forEach {(cx,cz)->
            for(dx in -3..3)for(dz in -2..2)if((cx+dx)*(cx+dx)+(cz+dz)*(cz+dz)<1450) {
                put(cx+dx,1,cz+dz,if(abs(dx)==3||abs(dz)==2)"wall" else "garden")
                if(abs(dx)<=1 && dz==0)put(cx+dx,2,cz+dz,"garden")
            }
            for(y in -8..-2)put(cx,y,cz,"stone")
        }
        anchors+=HubAnchor("spawn",target.point(0,1,0),target.rotation.toFloat())
        require(blocks.size<=50_000)
        return HubPlan(1,target,settings.hash,blocks.values.sortedWith(compareBy<HubBlock>{it.point.y}.thenBy{it.point.x}.thenBy{it.point.z}),anchors)
    }
}
