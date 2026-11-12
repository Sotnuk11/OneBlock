package com.alytanta.oneblock.release

import com.alytanta.oneblock.activities.*
import com.alytanta.oneblock.combat.*
import com.alytanta.oneblock.dungeon.*
import com.alytanta.oneblock.island.*
import com.alytanta.oneblock.items.*
import com.alytanta.oneblock.market.*
import com.alytanta.oneblock.oneblock.*
import com.alytanta.oneblock.rewards.*
import org.bukkit.Material
import org.bukkit.configuration.file.YamlConfiguration
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.UUID
import kotlin.random.Random

/** Full actual domain path from empty progress; native inventory/SQL failures are tested independently. */
class GoldenPathTest {
    @TempDir lateinit var root:Path
    private fun file(name:String)=root.resolve(name).toFile().apply {writeText(this@GoldenPathTest.javaClass.getResourceAsStream("/$name")!!.reader().readText())}
    @Test fun `new profiles earn each style final weapon full purchased set and final chapter from primary sources`() {
        val base=ReleaseRules.read(file("progression.yml"),true).first!!;val items=ItemCatalog.read(file("items.yml")).first!!
        val combat=CombatRules.read(file("combat.yml"),items,base.recipes.keys).first!!;val activity=ActivityRules.read(file("activities.yml"))
        val r=base.copy(recipes=base.recipes+combat.recipes+activity.recipes,combat=combat,customItems=items,activities=activity)
        val catalog=CombatRewardCatalog(combat,combat.items.mapValues {"oneblock:${it.key}"})
        val settings=YamlConfiguration.loadConfiguration(file("config.yml"));val market=MarketSettings.load(settings);val drops=DropSettings.load(settings)
        val room=RoomRewardRules.read(YamlConfiguration.loadConfiguration(file("dungeon.yml")))
        val records=mutableListOf<Map<String,Any>>()
        for((style,middle,last) in listOf(Triple("warden","rift_cleaver","nether_edge"),Triple("ranger","ranger_bow","ember_shortbow"),Triple("weaver","frost_wand","rift_staff"))) {
            val actor=UUID.nameUUIDFromBytes("golden-$style".toByteArray());val d=ReleaseDomain(r)
            var i=Island(actor,"model",0,60,0,CoreState(CoreMode.WOOD),releaseProgress=ReleaseProgress())
            var state=MarketState();val bag=mutableMapOf<String,Long>();val random=Random(1407);var now=10000L
            var core=0;var grown=0;var crops=0;var roomRuns=0;var paid=0L;var smelted=0L;var fundingCore=0;var maxFundingMs=0L;var mode="mine"
            fun add(id:String,n:Long){bag[id]=(bag[id]?:0)+n}
            fun use(id:String,n:Long):Boolean {if((bag[id]?:0)<n)return false;add(id,-n);return true}
            fun collect(){
                state.claims.values.forEach {c->if(c.product==null)i=i.copy(coins=i.coins+c.amount) else add(c.itemData?.takeIf {it.startsWith("oneblock:")}?:c.product,c.amount)}
                state=state.copy(claims=emptyMap()) // Ordinary claims only; never touches unopened free/paid offers.
            }
            fun smelt(material:String,n:Long):Boolean {
                val missing=(n-(bag[material]?:0)).coerceAtLeast(0);if(missing==0L)return true
                val raw=when(material){"IRON_INGOT"->"RAW_IRON";"GOLD_INGOT"->"RAW_GOLD";"NETHERITE_SCRAP"->"ANCIENT_DEBRIS";else->return false}
                val fuel=(missing+7)/8
                if((bag[raw]?:0)<missing || (bag["COAL"]?:0)<fuel)return false
                use(raw,missing);use("COAL",fuel);add(material,missing);smelted+=missing;return true
            }
            fun sticks(n:Long):Boolean {val missing=(n-(bag["STICK"]?:0)).coerceAtLeast(0);if(missing==0L)return true
                val logs=(missing+7)/8;if(!use("OAK_LOG",logs))return false;add("STICK",logs*8);return true}
            fun vanilla(material:String,n:Long):Boolean {
                val missing=(n-(bag[material]?:0)).coerceAtLeast(0);if(missing==0L)return true
                if(material=="STICK")return sticks(n)
                if(material in setOf("IRON_INGOT","GOLD_INGOT","NETHERITE_SCRAP"))return smelt(material,n)
                val inputs=when(material) {
                    "IRON_SWORD"->mapOf("IRON_INGOT" to 2L,"STICK" to 1L)
                    "DIAMOND_SWORD"->mapOf("DIAMOND" to 2L,"STICK" to 1L)
                    "DIAMOND_AXE"->mapOf("DIAMOND" to 3L,"STICK" to 2L)
                    "BOW"->mapOf("STRING" to 3L,"STICK" to 3L)
                    "TRIPWIRE_HOOK"->mapOf("IRON_INGOT" to 1L,"STICK" to 1L,"OAK_LOG" to 1L) // conservative full log, output hook remainder ignored
                    "CROSSBOW"->mapOf("STRING" to 2L,"STICK" to 3L,"IRON_INGOT" to 1L,"TRIPWIRE_HOOK" to 1L)
                    "NETHERITE_INGOT"->mapOf("NETHERITE_SCRAP" to 4L,"GOLD_INGOT" to 4L)
                    else->return false
                }.mapValues {it.value*missing}
                for((id,amount) in inputs.entries.sortedBy {if(it.key=="TRIPWIRE_HOOK")0 else 1}) {
                    if(id=="STICK")sticks(amount) else if(id in setOf("IRON_INGOT","GOLD_INGOT","NETHERITE_SCRAP"))smelt(id,amount)
                    else if(id=="TRIPWIRE_HOOK") {
                        if((bag[id]?:0)<amount && smelt("IRON_INGOT",amount) && sticks(amount) && (bag["OAK_LOG"]?:0)>=amount){use("IRON_INGOT",amount);use("STICK",amount);use("OAK_LOG",amount);add(id,amount)}
                    }
                }
                if(inputs.any {(id,amount)->(bag[id]?:0)<amount})return false
                inputs.forEach {(id,amount)->check(use(id,amount))};add(material,missing);return true
            }
            fun perform(action:String,key:String):Boolean {
                val q=runCatching {d.plan(i,state,actor,action,key)}.getOrNull()?:return false
                q.ingredients.forEach {g->if(g.combatId==null && g.materials.size==1)vanilla(g.materials.single(),g.amount.toLong())}
                if(q.ingredients.any {g->if(g.combatId!=null)(bag["oneblock:${g.combatId}"]?:0)<g.amount else g.materials.sumOf {bag[it]?:0}<g.amount})return false
                q.ingredients.forEach {g->var left=g.amount.toLong();val ids=if(g.combatId!=null)listOf("oneblock:${g.combatId}") else g.materials.sorted()
                    ids.forEach {id->val n=minOf(bag[id]?:0,left);check(use(id,n));left-=n};check(left==0L)}
                val t=ItemTransfer(UUID.randomUUID(),actor,actor,"STONE",1,TransferKind.RELEASE,q.coins,null,"model-before","model-after",release=q,releaseItems=q.items.map {it.tool?.let {id->"oneblock:${id.removePrefix("oneblock:")}"}})
                val a=d.prepare(i,state,t);val b=d.settle(a.island,a.state,t.id,TransferStatus.DONE);i=b.island;state=b.state;collect();return true
            }
            fun sell(){
                val reserves=mapOf("OAK_LOG" to 800L,"SPRUCE_LOG" to 400L,"BIRCH_LOG" to 400L,"COBBLESTONE" to 1200L,"COAL" to 800L,"RAW_IRON" to 800L,"RAW_GOLD" to 256L,"IRON_INGOT" to 256L,"GOLD_INGOT" to 64L,"DIAMOND" to 64L,"WHEAT" to 400L,"CARROT" to 200L,"POTATO" to 200L,"REDSTONE" to 256L)
                bag.toMap().filterKeys {!it.startsWith("oneblock:")}.forEach {(id,n)->val price=market.products[id]?.takeIf {it.shop}?.sellPrice?:0;val excess=(n-(reserves[id]?:0)).coerceAtLeast(0)
                    if(price>0 && excess>0){use(id,excess);i=i.copy(coins=i.coins+price*excess)}}
            }
            fun activities(){
                val a=i.releaseProgress!!.activity
                // The quest timer also runs while the player is offline.
                if(a.order==null && a.completed<12 && now<a.orderReadyAt)now=a.orderReadyAt
                if(a.order==null && now>=a.orderReadyAt && now-a.lastChange>=activity.actionMs)activity.offers(i.releaseProgress!!.chapter,a.completed).firstOrNull {o->if(mode=="mine")o.type=="mine" else if(mode=="farm")o.type=="grow" && "WHEAT" in o.blocks else false}?.let {o->
                    i=ActivityDomain.accept(i,state,actor,o.id,now,activity)}
                val e=i.releaseProgress!!.activity.event
                if(e!=null){
                    i=ActivityDomain.tick(i,e.id,now,true)
                    if(e.rule.kind=="echo")i=ActivityDomain.killed(i,e.id,e.progress,now)
                    i.releaseProgress!!.activity.event?.takeIf {it.progress>=it.rule.goal}?.let {perform("activity-reward",it.id.toString())}
                }else if(a.ready){
                    val kind=listOf("resonance","echo").firstOrNull {"activity:$it" !in i.releaseProgress!!.facts}
                    if(kind!=null)i=ActivityDomain.start(i,state,actor,kind,now,activity)
                }
                i.releaseProgress!!.activity.order?.takeIf {it.progress>=it.rule.target}?.let {perform("guild-deliver",it.id.toString())}
            }
            fun primary(block:Material,isCore:Boolean,isGrown:Boolean=false,isMature:Boolean=false){
                now+=1500;var p=d.credit(i.releaseProgress!!,actor,block,isCore,false,isGrown,isMature)
                p=ActivityDomain.credit(p,block.name,isGrown||isMature,isCore,now,activity);i=i.copy(releaseProgress=p);activities()
            }
            fun mine(){
                val p=i.releaseProgress!!;val drop=r.table(p,drops.starter).randomDrop(random)
                val bonus=((p.upgrades["fortune"]?:0)*r.fortuneStep+if(ReleaseRules.ore(drop.block))r.bonus(p.member(actor).xp["mining"]?:0) else 0.0).coerceAtMost(r.coreBonusCap)
                add(drop.reward.name,if(random.nextDouble()<bonus)2 else 1);primary(drop.block,true);core++;sell();check(core<1000000){"Unreachable path $style ${i.releaseProgress!!.chapter}"}
            }
            repeat(16){add("OAK_LOG",1);primary(Material.OAK_LOG,false,true);grown++}
            var roomsDone=false
            fun run(){
                now+=675000;val req=CombatRewardRequest("endless.v1",UUID.nameUUIDFromBytes("$style:$roomRuns".toByteArray()),actor,actor,"mine_boss",wave=15)
                val awarded=CombatRewardDomain.grantPrepared(i,state,room.prepare(req,catalog,15,true,now,final=true,bossesKilled=3),100000);i=awarded.island;state=awarded.state;collect()
                state.chests.values.filter {it.status=="OFFERED"}.sortedBy {it.price}.toList().forEach {offer->
                    while(i.coins<offer.price){mine();fundingCore++}
                    val b=ChestDomain.open(i,state,actor,offer,now);i=i.copy(coins=b.coins);state=b.state;collect()
                }
                roomRuns++
            }
            fun rooms(){
                val pid=ChestDomain.progressId(actor,actor);state=state.copy(chestProgress=state.chestProgress+(pid to ChestProgress(pid,actor,actor,style)))
                repeat(4){run()}
            }
            fun craft(recipe:String){var tries=0;while(!perform("recipe",recipe)){mine();fundingCore++;check(++tries<500000) {"$style $recipe $bag"}}}
            // Final gear starts after the last chapter: fragments come from full arena runs with the paid chest bought.
            fun enchanted(need:Long){
                while((bag["oneblock:enchanted_fragment"]?:0)<need) {
                    while((bag["oneblock:battle_fragment"]?:0)<12)run()
                    craft("enchanted_fragment")
                }
            }
            fun cores(need:Long){while((bag["oneblock:tempered_core"]?:0)<need) {
                while((bag["oneblock:battle_fragment"]?:0)<8)run()
                craft("temper_core")
            }}
            fun armor(){
                for((part,cost,core) in listOf(Triple("helmet",5L,1L),Triple("chestplate",8L,2L),Triple("leggings",7L,2L),Triple("boots",4L,1L))) {
                    if((bag["oneblock:${style}_$part"]?:0)>0)continue
                    enchanted(cost);cores(core);craft("${style}_$part");paid+=1
                }
            }
            while(i.releaseProgress!!.chapter!="developed"){
                when(mode){
                    "mine"->{mine();if(r.level(i.releaseProgress!!.member(actor).xp["mining"]?:0)>=11)mode="logs"}
                    "logs"->{add("OAK_LOG",1);primary(Material.OAK_LOG,false,true);grown++;if(r.level(i.releaseProgress!!.member(actor).xp["forestry"]?:0)>=11){mode="farm";i.releaseProgress!!.activity.order?.let {o->i=ActivityDomain.cancel(i,state,actor,o.id,now+2000,activity)}}}
                    else->{add("WHEAT",1);add("CARROT",1);primary(Material.WHEAT,false,false,true);crops++;sell();check(crops<100000)}
                }
                if(r.atLeast(i.releaseProgress!!.chapter,"workshop")) {
                    if("pickaxe_1" !in i.releaseProgress!!.crafted)perform("recipe","pickaxe_1")
                    if((i.releaseProgress!!.upgrades["fortune"]?:0)==0)perform("upgrade","fortune")
                    if((i.releaseProgress!!.upgrades["territory"]?:0)<2)perform("upgrade","territory")
                }
                if(r.atLeast(i.releaseProgress!!.chapter,"depths") && !roomsDone){
                    if(style=="ranger" && (bag["STRING"]?:0)<3)perform("pack","nature")
                    if((bag["oneblock:battle_fragment"]?:0)<16)perform("recipe","fragment_resources")
                    if(middle !in i.releaseProgress!!.crafted && perform("recipe",middle)){rooms();roomsDone=true}
                }
                val next=r.chapters.getOrNull(r.index(i.releaseProgress!!.chapter)+1)
                if(next!=null)perform("chapter",next.id)
            }
            if(style=="ranger" && (bag["STRING"]?:0)<2)check(perform("pack","nature"))
            armor();enchanted(8);cores(4)
            while(i.coins<25000){mine();fundingCore++}
            repeat(4){check(perform("pack","ancient"))}
            check(perform("recipe",last)) {"$style final $last $bag"}
            assertEquals(1,bag["oneblock:$last"]);assertTrue(roomsDone);assertTrue(paid in 0L..4L)
            assertTrue(i.releaseProgress!!.activity.completed>=12);assertTrue(setOf("activity:resonance","activity:echo","arena:wave:15").all {it in i.releaseProgress!!.facts})
            records+=mapOf("style" to style,"coreActions" to core,"grownLogs" to grown,"matureCrops" to crops,"guildOrders" to i.releaseProgress!!.activity.completed,"roomRuns" to roomRuns,"armorCoins" to paid,"remainingCoins" to i.coins,"remainingCharge" to i.releaseProgress!!.charge,"finalWeapon" to last,"smeltedPrimaryUnits" to smelted,"logicalInputStepMs" to 1500,"extraChestFundingCoreActions" to fundingCore,"maxOfferAgeWhenBoughtModelMs" to maxFundingMs)
        }
        val out=java.io.File("build/reports/release10-golden-path.json");out.parentFile.mkdirs();out.writeText(com.google.gson.GsonBuilder().setPrettyPrinting().create().toJson(mapOf("domainPaths" to records,"scope" to "fresh account, actual rules and domain transitions, virtual ingredient ledger, deterministic drops; not a human or physical-client playthrough")))
    }
}
