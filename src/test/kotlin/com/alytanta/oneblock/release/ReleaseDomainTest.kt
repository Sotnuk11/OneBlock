package com.alytanta.oneblock.release

import com.alytanta.oneblock.island.*
import com.alytanta.oneblock.oneblock.*
import com.alytanta.oneblock.market.*
import com.alytanta.oneblock.menu.Stack
import com.google.gson.JsonParser
import org.bukkit.Material
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.UUID

class ReleaseDomainTest {
    @TempDir lateinit var folder: Path
    private fun rules(text: String? = null, optIn: Boolean = true): ReleaseRules {
        val file=folder.resolve("progression.yml").toFile()
        file.writeText(text ?: javaClass.getResourceAsStream("/progression.yml")!!.reader(Charsets.UTF_8).use { it.readText() })
        return ReleaseRules.read(file,optIn).first!!
    }
    private fun island(p: ReleaseProgress=ReleaseProgress()) = UUID.randomUUID().let { id -> Island(id,"test",0,60,0,CoreState(CoreMode.WOOD),coins=1_000_000,releaseProgress=p) }
    private fun transfer(island: Island, actor: UUID, intent: ReleaseIntent) = ItemTransfer(UUID.randomUUID(),island.owner,actor,"STONE",1,TransferKind.RELEASE,intent.coins,null,"before","after",release=intent,
        releaseItems=intent.items.map { if(it.tool!=null)"exact:${it.tool}" else null })
    @Test fun `bundled rules have five tables twelve recipes eighteen achievements and safe legacy opt in`() {
        val r=rules();assertTrue(r.enabled);assertTrue(rules(optIn=false).enabled)
        assertEquals(5,r.tables.size);assertTrue(r.tables.values.all { it.totalWeight==10000L })
        assertEquals(12,r.recipes.size);assertEquals(18,r.achievementIds.size);assertEquals(12,r.tutorialIds.size)
        assertEquals(2700,r.threshold(3));assertEquals(2_700_000,r.threshold(30))
        assertEquals(30,r.level(Long.MAX_VALUE));r.validateDependencies()
    }
    @Test fun `invalid candidate rejects missing recipes bad weights overflow and unreachable gates`() {
        val source=javaClass.getResourceAsStream("/progression.yml")!!.reader().use { it.readText() }
        for(bad in listOf(source.replace("\"weight\":1100","\"weight\":1101"),source.replace("xp-scale: 100","xp-scale: 10000000000000"),
            source.replace("pickaxe_1:","unknown_1:"),source.replace("\"material\":\"IRON_INGOT\",\"amount\":16","\"material\":\"ANCIENT_DEBRIS\",\"amount\":16")))
            assertThrows(Exception::class.java) { rules(bad) }
    }
    @Test fun `all chapters recipes upgrades focus and charge settle with personal and shared claims`() {
        val r=rules();val d=ReleaseDomain(r);var island=island(ReleaseProgress(eligibleCoreBreaks=15000,grownLogs=16,activity=com.alytanta.oneblock.activities.ActivityProgress(completed=12),facts=setOf("activity:resonance","activity:echo","arena:wave:10"),crafted=setOf("frost_wand")))
        ReleaseRules.SKILLS.forEach {skill->island=island.copy(releaseProgress=d.xp(island.releaseProgress!!,island.owner,skill,r.threshold(r.chapters.last().milestones.allSkills)))}
        var state=MarketState()
        fun run(action: String, value: String) {
            val t=transfer(island,island.owner,d.plan(island,state,island.owner,action,value))
            val prepare=d.prepare(island,state,t);val result=d.settle(prepare.island,prepare.state,t.id,TransferStatus.DONE)
            island=result.island;state=result.state
            assertEquals(result,d.settle(island,state,t.id,TransferStatus.DONE))
        }
        run("chapter","living");assertEquals(8,state.claims.size)
        assertTrue(state.claims.values.all { it.recipient==null })
        run("chapter","workshop");run("recipe","pickaxe_1");run("upgrade","fortune");run("upgrade","territory");run("upgrade","territory")
        run("chapter","depths");run("recipe","pickaxe_2");run("chapter","developed");run("recipe","pickaxe_3");run("upgrade","focus");run("focus","mineral")
        assertEquals("mineral",island.releaseProgress!!.focus)
        val tools=state.claims.values.filter { it.itemData!=null };assertEquals(3,tools.size);assertTrue(tools.all { it.recipient==island.owner })
        island=island.copy(releaseProgress=island.releaseProgress!!.copy(charge=3000));run("pack","ancient")
        assertEquals(0,island.releaseProgress!!.charge);assertTrue(state.claims.values.any { it.product=="ANCIENT_DEBRIS" && it.recipient==null })
    }
    @Test fun `concurrent coop quote cannot buy twice and unchanged inventory abort refunds via claims once`() {
        val r=rules();val d=ReleaseDomain(r);val actor=UUID.randomUUID()
        val island=island(ReleaseProgress(chapter="workshop",charge=3000)).let { it.copy(members=mapOf(actor to IslandRole.MEMBER)) }
        val first=transfer(island,island.owner,d.plan(island,MarketState(),island.owner,"upgrade","fortune"))
        val other=transfer(island,actor,d.plan(island,MarketState(),actor,"upgrade","fortune"))
        val prepared=d.prepare(island,MarketState(),first)
        assertThrows(IllegalArgumentException::class.java) { d.prepare(prepared.island,prepared.state,other) }
        val aborted=d.settle(prepared.island,prepared.state,first.id,TransferStatus.ABORTED)
        assertEquals(island.coins,aborted.island.coins+aborted.state.claims.values.sumOf { it.amount })
        assertEquals(aborted,d.settle(aborted.island,aborted.state,first.id,TransferStatus.ABORTED))
        assertTrue(aborted.island.releaseProgress!!.upgrades.isEmpty())
    }
    @Test fun `charge reservation and review never reissue and preserves concurrent primary action credits`() {
        val r=rules();val d=ReleaseDomain(r);val island=island(ReleaseProgress(chapter="depths",charge=1000))
        val t=transfer(island,island.owner,d.plan(island,MarketState(),island.owner,"pack","mineral"))
        val p=d.prepare(island,MarketState(),t);assertEquals(0,p.island.releaseProgress!!.charge)
        val reviewed=d.settle(p.island,p.state,t.id,TransferStatus.REVIEW);assertTrue(reviewed.state.claims.isEmpty())
        val credit=d.credit(reviewed.island.releaseProgress!!,island.owner,Material.IRON_ORE,true,false,false,false)
        val done=d.settle(reviewed.island.copy(releaseProgress=credit),reviewed.state,t.id,TransferStatus.DONE)
        assertEquals(1,done.island.releaseProgress!!.charge);assertEquals(1,done.island.releaseProgress.eligibleCoreBreaks)
        assertEquals(setOf("RAW_IRON","COAL","RAW_GOLD","DIAMOND"),done.state.claims.values.map { it.product }.toSet())
    }
    @Test fun `member skill progress stays within account and former member cannot plan operations`() {
        val r=rules();val d=ReleaseDomain(r);val actor=UUID.randomUUID()
        val first=island();val second=island()
        val xp=d.credit(first.releaseProgress!!,actor,Material.DIAMOND_ORE,true,false,false,false)
        assertEquals(4,xp.member(actor).xp["mining"]);assertTrue(second.releaseProgress!!.member(actor).xp.isEmpty())
        assertThrows(IllegalArgumentException::class.java) { d.plan(first.copy(releaseProgress=xp),MarketState(),actor,"chapter","living") }
        assertTrue(first.copy(members=mapOf(actor to IslandRole.MEMBER),releaseProgress=xp).releaseProgress!!.member(actor).xp.isNotEmpty())
    }
    @Test fun `silk stone and ore do not give mining XP and crops require maturity once per source`() {
        val r=rules();val d=ReleaseDomain(r);val actor=UUID.randomUUID();val p=ReleaseProgress()
        assertTrue(d.credit(p,actor,Material.DIAMOND_ORE,true,true,false,false).member(actor).xp.isEmpty())
        assertTrue(d.credit(p,actor,Material.STONE,false,true,false,false).member(actor).xp.isEmpty())
        assertTrue(d.credit(p,actor,Material.WHEAT,false,false,false,false).member(actor).xp.isEmpty())
        assertTrue(d.credit(p,actor,Material.HAY_BLOCK,true,false,false,false).member(actor).xp.isEmpty())
        val crop=d.credit(p,actor,Material.WHEAT,false,false,false,true)
        assertEquals(2,crop.member(actor).xp["farming"]);assertEquals(1,crop.matureCrops)
        val log=d.credit(p,actor,Material.OAK_LOG,false,false,true,false)
        assertEquals(4,log.member(actor).xp["forestry"]);assertEquals(1,log.grownLogs)
        assertEquals(0,d.credit(p,actor,Material.OAK_LOG,true,false,false,false).grownLogs)
    }
    @Test fun `all tutorials claim once with living kit not repeated and crop final actor retained after departure`() {
        val r=rules();val d=ReleaseDomain(r);val finalActor=UUID.randomUUID()
        var island=island(ReleaseProgress(chapter="depths",eligibleCoreBreaks=5000,grownLogs=16,matureCrops=32,cropActor=finalActor,
            merchantNet=100,crafted=setOf("pickaxe_1"),upgrades=mapOf("fortune" to 1),facts=setOf("menu","craft:CRAFTING_TABLE","craft:STONE_PICKAXE","hub","merchant","bazaar")))
        var state=MarketState()
        repeat(12) { index ->
            val t=transfer(island,island.owner,d.plan(island,state,island.owner,"tutorial","$index:mining"))
            val p=d.prepare(island,state,t);val done=d.settle(p.island,p.state,t.id,TransferStatus.DONE)
            island=done.island;state=done.state
            assertEquals(done,d.settle(island,state,t.id,TransferStatus.DONE))
        }
        assertEquals(12,island.releaseProgress!!.tutorial)
        assertEquals(100,island.releaseProgress!!.member(finalActor).xp["farming"])
        assertEquals(100,island.releaseProgress!!.member(island.owner).xp["mining"])
        assertFalse(state.claims.values.any { it.product in setOf("WATER_BUCKET","LAVA_BUCKET") })
        assertEquals(300,state.claims.values.filter { it.product==null }.sumOf { it.amount })
    }
    @Test fun `ingredient plan accepts mixed plain logs protects metadata slot eight and is mutation free on failure`() {
        val input=arrayOfNulls<org.bukkit.inventory.ItemStack>(36)
        input[0]=Stack(Material.OAK_LOG,8);input[1]=Stack(Material.SPRUCE_LOG,8);input[2]=Stack(Material.BIRCH_LOG,64,tag="custom");input[8]=Stack(Material.OAK_LOG,64)
        val after=ReleaseInventoryPlan.remove(input,listOf(Ingredient(ReleaseRules.LOGS,16)))
        assertNull(after[0]);assertNull(after[1]);assertEquals(input[2],after[2]);assertEquals(input[8],after[8]);assertEquals(8,input[0]!!.amount)
        assertThrows(IllegalArgumentException::class.java) { ReleaseInventoryPlan.remove(input,listOf(Ingredient(ReleaseRules.LOGS,17))) }
        assertEquals(8,input[0]!!.amount)
    }
    @Test fun `state codec defaults nested nulls keeps legacy null and rejects unknown versions and negative counters`() {
        assertNull(ReleaseStateCodec.normalize(null))
        val id=UUID.randomUUID()
        val decoded=ReleaseStateCodec.normalize(JsonParser.parseString("""{"version":1,"members":{"$id":{"xp":null,"achievements":null}},"facts":null}"""))!!.asJsonObject
        assertEquals("start",decoded["chapter"].asString);assertEquals(0,decoded.getAsJsonObject("members").getAsJsonObject(id.toString()).getAsJsonObject("xp").size())
        for(json in listOf("{\"version\":2}","{\"version\":1,\"charge\":-1}"))assertThrows(Exception::class.java) { ReleaseStateCodec.normalize(JsonParser.parseString(json)) }
    }
    @Test fun `focus weights normalize once without altering current drop and achievements are capped cosmetic sets`() {
        val r=rules();val base=ReleaseProgress(chapter="developed");val starter=r.tables.getValue("start")
        assertEquals(10000,r.table(base,starter).totalWeight)
        assertEquals(20000,r.table(base.copy(focus="nature"),starter).totalWeight)
        val mineral=r.table(base.copy(focus="mineral"),starter)
        assertEquals(400,mineral.entries.single { it.reward==Material.DIAMOND }.weight)
        val d=ReleaseDomain(r);val actor=UUID.randomUUID()
        val max=d.achievements(d.xp(base.copy(eligibleCoreBreaks=Long.MAX_VALUE,upgrades=mapOf("fortune" to 5,"territory" to 5,"focus" to 1),crafted=setOf("pickaxe_1","axe_2","hoe_3")),actor,"mining",Long.MAX_VALUE))
        assertEquals(12,max.achievements.size);assertEquals(setOf("mining_10","mining_30"),max.member(actor).achievements)
        assertEquals(6.0,r.bonus(r.threshold(30))*100,0.00001)
    }
}
