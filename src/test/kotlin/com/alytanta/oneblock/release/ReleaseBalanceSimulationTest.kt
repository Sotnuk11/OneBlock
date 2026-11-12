package com.alytanta.oneblock.release

import com.alytanta.oneblock.island.*
import com.alytanta.oneblock.oneblock.*
import com.alytanta.oneblock.market.*
import org.bukkit.Material
import org.bukkit.configuration.file.YamlConfiguration
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.UUID
import kotlin.random.Random

/** Deterministic source/cost model, not a measurement of play time or native crop/tree growth speed. */
class ReleaseBalanceSimulationTest {
    @TempDir lateinit var folder: Path
    @Test fun `solo and coop can complete all chapters recipes and recovery with an empty player market`() {
        val file=folder.resolve("progression.yml").toFile().apply { writeText(this@ReleaseBalanceSimulationTest.javaClass.getResourceAsStream("/progression.yml")!!.reader().use { it.readText() }) }
        val r=ReleaseRules.read(file,true).first!!.let {base->base.copy(chapters=base.chapters.map {it.copy(milestones=ChapterMilestones())})} // Legacy mechanics comparison; integrated milestones are tested in ReleaseReadinessTest.
        val config=javaClass.getResourceAsStream("/config.yml")!!.reader().use { YamlConfiguration.loadConfiguration(it) }
        val market=MarketSettings.load(config);val drops=DropSettings.load(config)
        fun price(material: String)=market.products[material]?.takeIf { it.shop }?.sellPrice ?: 0
        fun value(items: List<ReleaseItem>)=items.sumOf { price(it.material)*it.amount }
        val report=mutableListOf("# OneBlock 1.0 — deterministic balance simulation","",
            "Seed 1001; successful eligible resource rolls; prices from bundled config.yml. No Bazaar counterparties or hidden liquidity. These are action counts, not hours or playtest evidence.","",
            "| Chapter / focus | Coins per 1000 rolls | Fortune V + mining 30 |","|---|---:|---:|")
        val ev=mutableMapOf<String,Double>()
        for(chapter in ReleaseRules.CHAPTERS)for(focus in if(chapter=="developed")ReleaseRules.FOCUSES.sorted() else listOf("balanced")) {
            val table=r.table(ReleaseProgress(chapter=chapter,eligibleCoreBreaks=100,focus=focus),drops.starter)
            fun expected(bonus: Boolean)=table.entries.sumOf { d ->
                val chance=if(bonus)(5*r.fortuneStep+if(ReleaseRules.ore(d.block))r.bonus(r.threshold(30)) else 0.0).coerceAtMost(r.coreBonusCap) else 0.0
                d.weight.toDouble()/table.totalWeight*price(d.reward.name)*(1+chance)*1000
            }
            ev["$chapter/$focus"]=expected(false)
            report+="| $chapter / $focus | %.3f | %.3f |".format(java.util.Locale.ROOT,expected(false),expected(true))
        }
        assertTrue(ReleaseRules.CHAPTERS.map { ev.getValue("$it/balanced") }.zipWithNext().all { (a,b)->b>=a })
        report+=listOf("","| Charge pack | Points | Merchant value (coins) |","|---|---:|---:|")
        r.packs.values.forEach { report+="| ${it.id} | ${it.points} | ${value(it.items)} |" }
        val mineral=r.packs.getValue("mineral");report+="| mineral (depths) | ${mineral.points} | ${value(mineral.items+mineral.advancedItems)} |"
        val diamondChance=r.tables.getValue("depths").entries.filter { it.reward==Material.DIAMOND }.sumOf { it.weight }/10000.0
        val tailRandom=Random(1001)
        val samples=List(10000) { (1..8).sumOf { kotlin.math.ceil(kotlin.math.ln(1-tailRandom.nextDouble())/kotlin.math.ln(1-diamondChance)).toInt() } }.sorted()
        report+=listOf("","Diamond gate (8 diamonds, Depths, no bonuses): expected ${8/diamondChance} successful rolls; deterministic sample p95=${samples[9500]}, p99=${samples[9900]}. These sample quantiles are not a finite RNG worst-case guarantee. The improved mineral charge pack guarantees one diamond per ${mineral.points} points: at most ${8*mineral.points/r.chargePerBreak} additional eligible core actions from zero saved charge, without needing a random diamond roll.")
        r.recipes.values.forEach { recipe ->
            val ingredients=recipe.ingredients.sumOf { ingredient -> ingredient.materials.minOf(::price)*ingredient.amount }
            val output=if(recipe.items.any { it.tool!=null })0 else value(recipe.items)
            assertTrue(output<=ingredients+recipe.coins,"Merchant-positive supply cycle: ${recipe.id}")
        }
        report+=listOf("","Baseline farming: mature crop = ${r.xpRates.getValue("crop")} XP per primary action, independent of Fortune/drop count. Vanilla crop/tree growth and fuel rates must be measured in game.","",
            "Start adjustment: 5 percentage points of core log output become oak saplings (30% logs + 5% saplings). This restores a renewable pre-Living source after loss of all four initial saplings. Oak-log display follows the existing configurable core reward contract. Start support EV changes from 3600 to ${ev.getValue("start/balanced").toLong()} coins / 1000 rolls.","",
            "| Members (round-robin core) | Living core actions | Workshop | Depths | Developed | Total core for 12 recipes | Extra grown logs | Mature crops |","|---|---:|---:|---:|---:|---:|---:|---:|")
        for(size in listOf(1,2,4)) {
            val actors=(0 until size).map { UUID.nameUUIDFromBytes("member-$it".toByteArray()) };val owner=UUID.nameUUIDFromBytes("account-$size".toByteArray())
            val d=ReleaseDomain(r);var island=Island(owner,"simulation",0,60,0,CoreState(CoreMode.WOOD),ownerPlayerId=actors[0],
                members=actors.drop(1).associateWith { IslandRole.MEMBER },releaseProgress=ReleaseProgress())
            var state=MarketState();val inventory=mutableMapOf<String,Long>();val random=Random(1001);var core=0;var grown=0;var crops=0
            val gates=mutableListOf<Int>()
            var blocked=""
            fun add(key: String,n: Long) { inventory[key]=(inventory[key]?:0)+n }
            fun take(key: String,n: Long): Boolean { if((inventory[key]?:0)<n)return false;add(key,-n);return true }
            fun smelt(item: String,n: Int) {
                val raw=when(item) { "IRON_INGOT"->"RAW_IRON";"GOLD_INGOT"->"RAW_GOLD";else->return }
                val missing=(n-(inventory[item]?:0)).coerceAtLeast(0)
                val fuel=(missing+7)/8
                if((inventory[raw]?:0)>=missing && (inventory["COAL"]?:0)>=fuel) { take(raw,missing);take("COAL",fuel);add(item,missing) }
            }
            fun reserve(ingredients: List<Ingredient>): Boolean {
                ingredients.forEach { input -> if(input.materials.size==1) {
                    val item=input.materials.single()
                    if(item=="BUCKET" && (inventory[item]?:0)<input.amount) {
                        smelt("IRON_INGOT",3);if(take("IRON_INGOT",3))add("BUCKET",1)
                    } else smelt(item,input.amount)
                } }
                return ingredients.all { input -> input.materials.sumOf { inventory[it]?:0 } >= input.amount }
            }
            fun perform(action: String, key: String): Boolean {
                val intent=runCatching { d.plan(island,state,actors[0],action,key) }.getOrElse { blocked="$action $key: ${it.message}";return false }
                if(!reserve(intent.ingredients)) { blocked="$action $key: ingredients ${intent.ingredients}";return false }
                intent.ingredients.forEach { input -> var left=input.amount.toLong();input.materials.sorted().forEach { material -> val n=minOf(inventory[material]?:0,left);take(material,n);left-=n };check(left==0L) }
                val t=ItemTransfer(UUID.randomUUID(),owner,actors[0],"STONE",1,TransferKind.RELEASE,intent.coins,null,"before","after",release=intent,releaseItems=intent.items.map { null })
                val prepared=d.prepare(island,state,t);val done=d.settle(prepared.island,prepared.state,t.id,TransferStatus.DONE)
                island=done.island;state=done.state
                state.claims.values.forEach { c -> if(c.product==null)island=island.copy(coins=Math.addExact(island.coins,c.amount)) else add(c.product,c.amount) }
                state=state.copy(claims=emptyMap());return true
            }
            fun sellSurplus() {
                val reserves=mapOf("OAK_LOG" to 512,"SPRUCE_LOG" to 256,"BIRCH_LOG" to 256,"COBBLESTONE" to 1024,"COAL" to 256,"RAW_IRON" to 512,"RAW_GOLD" to 128,"IRON_INGOT" to 128,"GOLD_INGOT" to 64,"REDSTONE" to 64,"DIAMOND" to 64,"WHEAT" to 512,"CARROT" to 256,"POTATO" to 256)
                inventory.toMap().forEach { (key,amount) -> val surplus=(amount-(reserves[key]?:0)).coerceAtLeast(0);val unit=price(key)
                    if(unit>0 && surplus>0) { take(key,surplus);island=island.copy(coins=Math.addExact(island.coins,surplus*unit)) }
                }
            }
            fun mine() {
                val p=island.releaseProgress!!;val actor=actors[core%size];val drop=r.table(p,drops.starter).randomDrop(random)
                val bonus=((p.upgrades["fortune"]?:0)*r.fortuneStep+(if(ReleaseRules.ore(drop.block))r.bonus(p.member(actor).xp["mining"]?:0) else 0.0)).coerceAtMost(r.coreBonusCap)
                add(drop.reward.name,1+if(random.nextDouble()<bonus)1L else 0L)
                island=island.copy(releaseProgress=d.credit(p,actor,drop.block,true,false,false,false));core++;sellSurplus()
                check(core<2_000_000) { "Unreachable simulation gate: $blocked; chapter=${island.releaseProgress!!.chapter}; coins=${island.coins}; XP=${island.releaseProgress!!.member(actors[0]).xp}; inventory=$inventory" }
            }
            // The actual starting kit contains four oak saplings; four ordinary trees supply the Living gate.
            repeat(16) { island=island.copy(releaseProgress=d.credit(island.releaseProgress!!,actors[0],Material.OAK_LOG,false,false,true,false));add("OAK_LOG",1);grown++ }
            for(chapter in ReleaseRules.CHAPTERS.drop(1)) {
                while(!perform("chapter",chapter)) {
                    mine()
                    if(island.releaseProgress!!.chapter=="workshop" && "pickaxe_1" !in island.releaseProgress!!.crafted)perform("recipe","pickaxe_1")
                    if(island.releaseProgress!!.chapter in setOf("workshop","depths")) {
                        if(island.releaseProgress!!.upgrades["fortune"]==null)perform("upgrade","fortune")
                        if((island.releaseProgress!!.upgrades["territory"]?:0)<2)perform("upgrade","territory")
                    }
                }
                gates+=core
            }
            // Model manual mature farming/trees explicitly; do not invent XP from market purchases.
            for(skill in listOf("forestry","farming"))while(r.level(island.releaseProgress!!.member(actors[0]).xp[skill]?:0)<8) {
                val block=if(skill=="forestry")Material.OAK_LOG else listOf(Material.WHEAT,Material.CARROTS,Material.POTATOES)[crops%3]
                island=island.copy(releaseProgress=d.credit(island.releaseProgress!!,actors[0],block,false,false,skill=="forestry",skill=="farming"))
                add(when(block) { Material.CARROTS->"CARROT";Material.POTATOES->"POTATO";else->block.name },1)
                if(skill=="forestry")grown++ else crops++
            }
            // Loss/recovery: renewable supply recipes are paid from new primary resources, without counterparties.
            r.recipes.values.forEach { recipe ->
                while(!perform("recipe",recipe.id)) { mine();sellSurplus() }
            }
            assertEquals("developed",island.releaseProgress!!.chapter)
            assertTrue("crafted_all_families" in island.releaseProgress!!.achievements)
            report+="| $size | ${gates.joinToString(" | ")} | $core | $grown | $crops |"
            report+=""
        }
        report+=listOf("","No recipe creates positive merchant value without consuming primary resources; buckets cost three smelted iron and fuel, farm kits consume logs/cobblestone. Buyback is tested separately at the exact sale total. Silk Touch, item metadata and physical inventory failures are covered by targeted tests; this simulation assumes suitable tools and successful claims.","",
            "The round-robin co-op model splits personal XP and can delay gates. A specialized miner, tree grower or farmer changes these counts. No real growth, durability, latency or session-length measurement is inferred.")
        val out=java.io.File("build/reports/release-1.0-balance.md");out.parentFile.mkdirs();out.writeText(report.joinToString("\n"))
    }
}
