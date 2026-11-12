package com.alytanta.oneblock.release

import com.alytanta.oneblock.activities.*
import com.alytanta.oneblock.combat.*
import com.alytanta.oneblock.island.*
import com.alytanta.oneblock.items.*
import com.alytanta.oneblock.market.*
import com.alytanta.oneblock.oneblock.*
import com.google.gson.GsonBuilder
import org.bukkit.configuration.file.YamlConfiguration
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.UUID
import kotlin.math.*

/** Mechanics and explicit action-time assumptions. Not a human playtime measurement. */
class ReleaseReadinessTest {
    @TempDir lateinit var root:Path
    private fun file(name:String)=root.resolve(name).toFile().apply {parentFile.mkdirs();writeText(this@ReleaseReadinessTest.javaClass.getResourceAsStream("/$name")!!.reader().readText())}
    private fun rules():ReleaseRules {
        val base=ReleaseRules.read(file("progression.yml"),true).first!!;val items=ItemCatalog.read(file("items.yml")).first!!
        val combat=CombatRules.read(file("combat.yml"),items,base.recipes.keys).first!!;val activity=ActivityRules.read(file("activities.yml"))
        return base.copy(customItems=items,combat=combat,activities=activity,recipes=base.recipes+combat.recipes+activity.recipes)
    }
    @Test fun `final chapter requires diverse earned milestones and existing developed profiles remain valid`() {
        val r=rules();val d=ReleaseDomain(r);val id=UUID.randomUUID()
        val p=ReleaseProgress(chapter="depths",eligibleCoreBreaks=15000,grownLogs=16,upgrades=mapOf("fortune" to 1,"territory" to 2),
            members=mapOf(id to MemberProgress(ReleaseRules.SKILLS.associateWith {r.threshold(r.chapters.last().milestones.allSkills)})))
        val i=Island(id,"test",0,60,0,CoreState(CoreMode.WOOD),coins=100000,releaseProgress=p)
        assertThrows(Exception::class.java) {d.plan(i,MarketState(),id,"chapter","developed")}
        val earned=p.copy(activity=ActivityProgress(completed=12),crafted=setOf("pickaxe_1","frost_wand"),facts=setOf("activity:resonance","activity:echo","arena:wave:10"))
        val ready=i.copy(releaseProgress=earned);assertEquals("developed",d.plan(ready,MarketState(),id,"chapter","developed").value)
        for(missing in listOf(earned.copy(activity=ActivityProgress(completed=11)),earned.copy(facts=earned.facts-"activity:echo"),earned.copy(facts=earned.facts-"arena:wave:10"),earned.copy(crafted=setOf("pickaxe_1"))))
            assertThrows(Exception::class.java) {d.plan(i.copy(releaseProgress=missing),MarketState(),id,"chapter","developed")}
        assertTrue(r.recipes.getValue("nether_edge").chapter=="developed")
        assertTrue(r.packs.getValue("ancient").items.any {it.material=="NETHERITE_UPGRADE_SMITHING_TEMPLATE"})
        val old=i.copy(releaseProgress=ReleaseProgress(chapter="developed",charge=3000))
        assertTrue(d.plan(old,MarketState(),id,"pack","ancient").items.any {it.material=="NETHERITE_UPGRADE_SMITHING_TEMPLATE"})
    }
    @Test fun `actual sources budgets skill costs and explicit route times are reviewable`() {
        val r=rules();val market=MarketSettings.load(YamlConfiguration.loadConfiguration(file("config.yml")))
        val report=linkedMapOf<String,Any>()
        fun price(id:String)=market.products[id]?.takeIf {it.shop}?.sellPrice?:0
        val tables=r.tables.mapValues {(_,t)->
            mapOf("coinEV" to t.entries.sumOf {it.weight.toDouble()/t.totalWeight*price(it.reward.name)},
                "miningXP" to t.entries.sumOf {it.weight.toDouble()/t.totalWeight*if(ReleaseRules.ore(it.block))r.xpRates.getValue("ore") else if(it.block.name in setOf("STONE","COBBLESTONE"))r.xpRates.getValue("stone") else 0},
                "forestryXP" to t.entries.sumOf {it.weight.toDouble()/t.totalWeight*if(ReleaseRules.treeLog(it.block))r.xpRates.getValue("log") else 0})}
        report["actualCoreTables"]=tables
        val developed=r.chapters.last();val needed=r.threshold(developed.milestones.allSkills)
        // First mine the chapter/XP path, then subtract forestry XP already earned on the core.
        var mining=0.0;var forestry=0.0;var cores=0L;var coins=0.0
        while(mining<needed) {
            val chapter=when {cores<100->"start";cores<1800->"living";cores<6500->"workshop";else->"depths"}
            val t=tables.getValue(chapter);mining+=t.getValue("miningXP");forestry+=t.getValue("forestryXP");coins+=t.getValue("coinEV")*.5;cores++
        }
        val grown=ceil((needed-forestry).coerceAtLeast(0.0)/r.xpRates.getValue("log")).toLong()
        val crops=ceil(needed.toDouble()/r.xpRates.getValue("crop")).toLong()
        val profiles=listOf(
            listOf("efficient",.6,1.0,.3,4.0),
            listOf("reference",1.0,1.8,.85,9.0),
            listOf("deliberate",1.2,2.2,1.0,10.0))
        report["activeHourScenarios"]=profiles.map {row->
            val coreHours=cores*(row[1] as Double)/3600;val logHours=grown*(row[2] as Double)/3600;val farmHours=crops*(row[3] as Double)/3600
            val other=row[4] as Double
            mapOf("name" to row[0],"coreSecondsEach" to row[1],"grownLogSecondsIncludingPlantMove" to row[2],"cropSecondsIncludingReplant" to row[3],"coreHours" to coreHours,"forestryHours" to logHours,"farmingHours" to farmHours,"activeBuildEnchantCraftTravelCombatHours" to other,"totalHours" to coreHours+logHours+farmHours+other)
        }
        report["withRecurringGuildRewards"]=profiles.map {row->
            val coreSec=row[1] as Double;val logSec=row[2] as Double;val cropSec=row[3] as Double
            var xp=200.0;var logXp=0.0;var actions=0L;var rewardXP=0.0
            while(xp<needed) {
                val chapter=when {actions<100->"start";actions<1800->"living";actions<6500->"workshop";else->"depths"}
                val t=r.tables.getValue(chapter);val q=r.activities!!.offers(chapter,0).first {it.type=="mine"}
                val fraction=t.entries.filter {it.block.name in q.blocks}.sumOf {it.weight}.toDouble()/t.totalWeight
                val bonus=q.reward.xp*fraction/q.target/ceil(r.activities.creditMs/1000.0/coreSec)
                rewardXP+=bonus;xp+=tables.getValue(chapter).getValue("miningXP")+bonus;logXp+=tables.getValue(chapter).getValue("forestryXP");actions++
            }
            val grow=r.activities!!.offers("depths",0).first {it.type=="grow"}
            assertEquals("farming",grow.reward.skill)
            val cropBonus=grow.reward.xp/grow.target.toDouble()/ceil(r.activities.creditMs/1000.0/cropSec)
            val logActions=ceil((needed-logXp).coerceAtLeast(0.0)/r.xpRates.getValue("log")).toLong()
            val cropActions=ceil(needed/(r.xpRates.getValue("crop")+cropBonus)).toLong()
            val hours=actions*coreSec/3600+logActions*logSec/3600+cropActions*cropSec/3600+(row[4] as Double)
            mapOf("name" to row[0],"coreActions" to actions,"grownLogs" to logActions,"matureCrops" to cropActions,"expectedMiningQuestXP" to rewardXP,"farmingQuestXPPerCrop" to cropBonus,"totalActiveHours" to hours)
        }
        report["guildTimingModel"]="Recurring relevant orders; latest available tier; profile credit interval rounds up to the next eligible input. Core and resonance actions overlap; only active build/enchant/craft/travel/combat allowance is added. Fractional expected quest XP is a model, not a producer. Supply XP is mining for pickaxes and forestry for chests, never farming XP from iron."
        val ev=tables.getValue("depths").getValue("coinEV");val income=ev*.25*.5*3600
        report["chestBudget"]=mapOf("price" to 500,"selectedSetCostFourPaidPieces" to 2000,"conservativeMerchantCoinsPerActiveHour" to income,"minutesForOneOffer" to 500/income*60,"activeHoursForFourCoopSetsFromZero" to 8000/income,"unpaidWallClockHours" to 48,"offersDoNotReroll" to true)
        assertTrue(8000/income<48)
        report["requirements"]=mapOf("allSkillsLevel" to developed.milestones.allSkills,"eachSkillXP" to needed,"estimatedEligibleCoreActions" to cores,"additionalGrownLogsAfterCoreXP" to grown,"matureCrops" to crops,"coreMerchantProceedsAt50PercentSale" to coins,"guildOrders" to developed.milestones.orders,"events" to developed.milestones.events,"arenaWave" to developed.milestones.arenaWave)
        report["itemSources"]=r.customItems!!.items.values.associate {item->
            val recipe=r.recipes.values.filter {q->q.items.any {it.tool==item.legacyId}}.map {it.id}
            item.id to if(item.kind==ItemKind.ARMOR)mapOf("source" to "enchanted fragment recipes and rare boss trophies","craftable" to true)
            else mapOf("recipes" to recipe,"activityOrRoomComponent" to (item.kind==ItemKind.COMPONENT))
        }
        r.customItems.items.values.filter {it.kind!=ItemKind.ARMOR}.forEach {item->assertTrue(r.recipes.values.any {q->q.items.any {it.tool==item.legacyId}},"No renewable recipe for ${item.id}")}
        assertEquals(12,r.recipes.values.count {q->q.items.any {it.tool?.let(r.customItems::find)?.kind==ItemKind.ARMOR}})
        report["recipeInputs"]=r.recipes.mapValues {(_,recipe)->recipe.ingredients}
        report["guarantee"]=mapOf("everyEligibleBosses" to 3,"missingOwnedPiecesBoundBosses" to 12,"full15WaveRunsBound" to 4,"randomDuplicatesCannotReset" to true,"requiresBuyingEachNewGuaranteedPiece" to true)
        report["assumptions"]=listOf("active action times are explicit design assumptions, not human timing data","50 percent core sale leaves material reserves; no Bazaar liquidity","guild and resonance primary actions overlap resource actions and are not added twice","build/enchant/travel/combat allowance is active time, no AFK growth or offline time","efficient optimized route can be much shorter than 40 hours; no hard time gate","normal enchantments require vanilla bookshelves, levels, repair and ammunition; no free enchanted gear is assumed")
        val out=java.io.File("build/reports/release10-economy.json");out.parentFile.mkdirs();out.writeText(GsonBuilder().setPrettyPrinting().create().toJson(report))
    }
}
