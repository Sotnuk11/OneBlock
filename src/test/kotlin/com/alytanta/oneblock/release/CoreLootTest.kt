package com.alytanta.oneblock.release

import org.bukkit.Material
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

class CoreLootTest {
    @TempDir lateinit var dir: Path
    private fun rules()=dir.resolve("rules.yml").toFile().let { file -> file.writeText(javaClass.getResourceAsStream("/progression.yml")!!.reader().use { it.readText() });ReleaseRules.read(file,true).first!! }

    @Test fun `log and leaf cores give their own block so saplings come only by vanilla leaf chance`() {
        val r=rules()
        (r.tables.values+r.natureTable).flatMap { it.entries }.forEach { drop ->
            if(drop.block.name.endsWith("_LOG")||drop.block.name.endsWith("_LEAVES"))assertEquals(drop.block,drop.reward)
            assertFalse(drop.reward.name.endsWith("_SAPLING")||drop.reward==Material.MANGROVE_PROPAGULE)
        }
    }

    @Test fun `tree types open with chapters and every core log counts as logs in recipes`() {
        val r=rules()
        listOf("start" to 3,"living" to 5,"workshop" to 7,"depths" to 9,"developed" to 9).forEach { (chapter,count) ->
            val rewards=r.tables.getValue(chapter).entries.map { it.reward }
            assertEquals(count,rewards.count { it.name.endsWith("_LOG") },chapter)
            assertEquals(count,rewards.count { it.name.endsWith("_LEAVES") },chapter)
            rewards.filter { it.name.endsWith("_LOG") }.forEach { assertTrue(it.name in ReleaseRules.LOGS,it.name) }
        }
        assertEquals(9,r.natureTable.entries.count { it.reward.name.endsWith("_LEAVES") })
    }
}
