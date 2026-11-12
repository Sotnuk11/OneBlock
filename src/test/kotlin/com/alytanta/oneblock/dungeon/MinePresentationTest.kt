package com.alytanta.oneblock.dungeon

import org.bukkit.configuration.file.YamlConfiguration
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import kotlin.math.*

class MinePresentationTest {
    @Test fun `fractional cleave boundary shares exact hit dimensions in every direction`() {
        for(angle in 0 until 360 step 15) {
            val a=Math.toRadians(angle.toDouble());val origin=MinePoint(91.5,65.0,16.5)
            val shape=MineAttackShape(0,origin,MinePoint(origin.x+cos(a)*8,65.0,origin.z+sin(a)*8),5.5,1.8,3.0)
            val points=shape.boundary();assertTrue(points.size in 20..100)
            points.forEach {p->
                val forward=(p.x-origin.x)*cos(a)+(p.z-origin.z)*sin(a)
                val lateral=abs((p.x-origin.x)*sin(a)-(p.z-origin.z)*cos(a))
                assertTrue(forward>=-1e-10 && forward<=5.5+1e-10);assertTrue(lateral<=1.8+1e-10)
                assertTrue(abs(forward)<1e-10 || abs(forward-5.5)<1e-10 || abs(lateral-1.8)<1e-10)
            }
            assertTrue(shape.contains(origin.x+cos(a)*2,origin.z+sin(a)*2))
            assertFalse(shape.contains(origin.x+cos(a)*5.51,origin.z+sin(a)*5.51))
        }
        val centered=MineAttackShape(0,MinePoint(0.0,65.0,0.0),MinePoint(0.0,65.0,0.0),5.5,1.8,3.0)
        assertFalse(centered.contains(50.0,50.0));assertTrue(centered.contains(0.0,2.0))
    }
    @Test fun `area perimeter has exact radius and maximum configured geometry stays bounded`() {
        val shape=MineAttackShape(1,MinePoint(91.5,65.0,16.5),MinePoint(80.0,65.0,12.0),12.0,5.0,8.0)
        assertTrue(shape.boundary().size<=80)
        shape.boundary().forEach {assertEquals(8.0,hypot(it.x-80,it.z-12),1e-10)}
        assertTrue(shape.contains(88.0,12.0));assertFalse(shape.contains(88.01,12.0))
    }
    @Test fun `presentation accepts absent old settings rejects hazards and caps pulse tasks`() {
        val old=MinePresentation.read(YamlConfiguration());assertEquals(MinePresentation(),old)
        for((key,value) in listOf("palette.wall" to "lava","pulse-ticks" to 0,"pulse-ticks" to 4.5,"warning-color" to "red","particles" to "true","impact-sound" to "file:secret")) {
            val y=YamlConfiguration();y.set("presentation.$key",value)
            assertThrows(IllegalArgumentException::class.java) {MinePresentation.read(y)}
        }
        val y=YamlConfiguration();y.set("presentation.palette.entry-floor","mossy_stone_bricks");y.set("presentation.particles",false)
        val custom=MinePresentation.read(y);assertFalse(custom.particles)
        assertTrue(MineLayout.blocks(custom).any {it.y==64 && it.x<32 && it.data=="minecraft:mossy_stone_bricks"})
    }
    @Test fun `three rooms retain safe checkpoints gates puzzle signs secret and clear travel lanes`() {
        val blocks=MineLayout.blocks();val map=blocks.associateBy {Triple(it.x,it.y,it.z)}
        assertEquals(blocks.size,map.size);assertTrue(blocks.size<18000)
        for(x in 2..102)for(z in 14..18) {
            assertNotNull(map[Triple(x,64,z)])
            if(x !in listOf(32,72))for(y in 65..67) {
                val data=map[Triple(x,y,z)]?.data.orEmpty()
                assertTrue(data.isEmpty() || data.contains("rail"),"Blocked travel lane at $x/$y/$z: $data")
            }
        }
        listOf(MineLayout.spawn,MineLayout.checkpoint(MinePhase.PUZZLE),MineLayout.checkpoint(MinePhase.BOSS)).forEach {p->
            assertNotNull(map[Triple(p.x.toInt(),64,p.z.toInt())]);assertNull(map[Triple(p.x.toInt(),66,p.z.toInt())])
        }
        for(x in listOf(48,52,56))assertTrue(map.getValue(Triple(x,65,8)).data.contains("lever"))
        for(x in 8..100 step 8)for(z in listOf(12,20))assertEquals("minecraft:glowstone",map.getValue(Triple(x,64,z)).data)
        assertTrue(map.getValue(Triple(54,66,31)).data.contains("button"))
    }
}
