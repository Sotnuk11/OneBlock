package com.alytanta.oneblock.oneblock

import com.alytanta.oneblock.island.Island
import com.alytanta.oneblock.island.IslandProgressStore
import com.alytanta.oneblock.island.IslandProgressionService
import org.bukkit.Material
import org.bukkit.World
import org.bukkit.block.Block
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.lang.reflect.Proxy
import java.util.UUID

class CoreMiningTest {
    @Suppress("UNCHECKED_CAST")
    private inline fun <reified T> fake(crossinline call: (String) -> Any?): T =
        Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { _, method, _ -> call(method.name) } as T

    @Test fun `release core preserves legacy breaks while crediting survival once independently of bonus quantity and silk`() {
        val file=kotlin.io.path.createTempFile("release-core-",".yml").toFile()
        try {
            file.writeText(javaClass.getResourceAsStream("/progression.yml")!!.reader().use { it.readText() })
            val table=WeightedDropTable(listOf(CoreDrop(Material.IRON_ORE,Material.RAW_IRON,10000)))
            val rules=com.alytanta.oneblock.release.ReleaseRules.read(file,true).first!!.copy(starterBreaks=0,tables=com.alytanta.oneblock.release.ReleaseRules.CHAPTERS.associateWith { table })
            val owner=UUID.randomUUID();var state=Island(owner,"test",0,100,0,CoreState(CoreMode.WOOD,breaks=90000),
                releaseProgress=com.alytanta.oneblock.release.ReleaseProgress(chapter="workshop",upgrades=mapOf("fortune" to 5)))
            val store=object:IslandProgressStore { override fun update(owner:UUID,change:(Island)->Island):Island { state=change(state);return state } }
            val service=OneBlockService(IslandProgressionService(store),DropSettings(0,table,table)).apply { releaseRules=rules }
            val world=fake<World> { if(it in setOf("key","getKey"))com.alytanta.oneblock.island.IslandWorldIdentity.key(owner) else null }
            val block=fake<Block> { when(it) { "getType"->Material.IRON_ORE;"getWorld"->world;"getX","getZ"->0;"getY"->100;"setType"->null;else->error(it) } }
            service.releaseBusy={true};assertNull(service.regenerateCore(block,state,owner));assertEquals(90000,state.core.breaks)
            service.releaseBusy={false}
            assertEquals(0,service.regenerateCore(block,state,owner,creative=true)!!.amount)
            assertEquals(0,service.regenerateCore(block,state,owner,eligible=false)!!.amount)
            service.regenerateCore(block,state,owner,survival=false)
            service.regenerateCore(block,state,UUID.randomUUID()) // Administrative bypass must not earn release credit.
            assertEquals(0,state.releaseProgress!!.eligibleCoreBreaks);assertTrue(state.releaseProgress!!.members.isEmpty())
            repeat(64) { val reward=service.regenerateCore(block,state,owner)!!;assertTrue(reward.amount in 1..2) }
            assertEquals(64,state.releaseProgress!!.eligibleCoreBreaks);assertEquals(64,state.releaseProgress!!.charge)
            assertEquals(256,state.releaseProgress!!.member(owner).xp["mining"])
            val silk=object:org.bukkit.inventory.ItemStack() { override fun getEnchantmentLevel(enchantment:org.bukkit.enchantments.Enchantment)=1 }
            val reward=service.regenerateCore(block,state,owner,tool=silk,silkDrop=Material.IRON_ORE)!!
            assertEquals(Material.IRON_ORE,reward.material);assertEquals(1,reward.amount)
            assertEquals(256,state.releaseProgress!!.member(owner).xp["mining"]);assertEquals(65,state.releaseProgress!!.charge)
            assertEquals(90069,state.core.breaks)
        } finally { file.delete() }
    }

    @Test fun `only the exact core updates the shared counter and gives one reward`() {
        val owner = UUID.randomUUID()
        var state = Island(owner, "private", 0, 100, 0, CoreState(CoreMode.WOOD))
        var writes = 0
        val store = object : IslandProgressStore {
            override fun update(owner: UUID, change: (Island) -> Island): Island? {
                if (owner != state.owner) return null
                return change(state).also { state = it }
            }
        }
        val table = WeightedDropTable(listOf(CoreDrop(Material.IRON_ORE, Material.RAW_IRON, 1)))
        val service = OneBlockService(IslandProgressionService(store), DropSettings(100, table, table))
        fun block(worldName: String, y: Int): Block {
            val world = fake<World> { when(it) { "getName" -> worldName; "key", "getKey" -> if(worldName == "private") com.alytanta.oneblock.island.IslandWorldIdentity.key(owner) else net.kyori.adventure.key.Key.key("test:other"); else -> error(it) } }
            return fake { method -> when (method) {
                "getType" -> state.core.currentDrop?.block ?: Material.IRON_ORE
                "getWorld" -> world
                "getX", "getZ" -> 0
                "getY" -> y
                "setType" -> { writes++; null }
                else -> error(method)
            } }
        }
        assertNull(service.regenerateCore(block("other", 100), state))
        assertNull(service.regenerateCore(block("private", 99), state))
        assertEquals(0, writes)
        assertEquals(0L, state.core.breaks)
        val reward = service.regenerateCore(block("private", 100), state)
        assertEquals(Material.RAW_IRON, reward?.material)
        assertEquals(1L, state.core.breaks)
        assertTrue(state.collections.isEmpty())
        assertEquals(1, writes)
        assertFalse(reward!!.notifyReward)
        val changed = WeightedDropTable(listOf(CoreDrop(Material.DIAMOND_ORE, Material.DIAMOND, 1, 3)))
        service.drops = DropSettings(0, changed, changed, rewardMessages = true, bonusEnabled = false)
        val reloaded = service.regenerateCore(block("private", 100), state)!!
        assertEquals(Material.RAW_IRON, reloaded.material)
        assertEquals(1, reloaded.amount)
        assertTrue(reloaded.notifyReward)
        assertEquals(2L, state.core.breaks)
        assertEquals(2, writes)
        val noLoot = service.regenerateCore(block("private",100),state,eligible=false)!!
        assertEquals(0,noLoot.amount)
        assertEquals(3L,state.core.breaks)
        val silk = service.regenerateCore(block("private",100),state,silkDrop=Material.DIAMOND_ORE)!!
        assertEquals(Material.DIAMOND_ORE,silk.material)
        val beforeCreative=state.core.breaks
        val creative=service.regenerateCore(block("private",100),state,creative=true)!!
        assertEquals(0,creative.amount) // A Creative break may advance the core, but has no natural loot.
        assertEquals(beforeCreative+1,state.core.breaks)
        val survival=service.regenerateCore(block("private",100),state,creative=false)!!
        assertEquals(3,survival.amount)
        assertTrue(state.collections.isEmpty())
    }
}
