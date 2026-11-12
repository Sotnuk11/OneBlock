package com.alytanta.oneblock.combat

import com.alytanta.oneblock.island.*
import com.alytanta.oneblock.oneblock.*
import com.alytanta.oneblock.release.*
import com.alytanta.oneblock.market.*
import com.google.gson.Gson
import com.google.gson.JsonParser
import com.alytanta.oneblock.items.ItemCatalog
import org.bukkit.configuration.file.YamlConfiguration
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.UUID

class CombatDomainTest {
    @TempDir lateinit var folder:Path
    private val tools=(1..3).flatMap {tier->listOf("pickaxe_$tier","axe_$tier","hoe_$tier")}.toSet()
    private fun items(change:(YamlConfiguration)->Unit = {}):ItemCatalog {
        val file=folder.resolve("items.yml").toFile();file.writeText(javaClass.getResourceAsStream("/items.yml")!!.reader().readText())
        val yaml=YamlConfiguration().apply {load(file)};change(yaml);yaml.save(file)
        return ItemCatalog.read(file).first!!
    }
    private fun rules(change:(com.google.gson.JsonObject)->Unit = {},catalog:ItemCatalog=items()):CombatRules {
        val text=javaClass.getResourceAsStream("/combat.yml")!!.reader().use { it.readText().substringAfter('\n') }
        val json=JsonParser.parseString(text).asJsonObject;change(json)
        val file=folder.resolve("combat.yml").toFile();file.writeText(json.toString());return CombatRules.read(file,catalog,tools).first!!
    }
    private fun island():Island { val id=UUID.randomUUID();return Island(id,"test",0,60,0,CoreState(CoreMode.WOOD),releaseProgress=ReleaseProgress(chapter="developed")) }
    private fun request(i:Island)=CombatRewardRequest("encounter.test",UUID.randomUUID(),i.owner,i.ownerPlayer,"elite_encounter")
    private fun catalog(r:CombatRules)=CombatRewardCatalog(r,r.items.mapValues { "exact:${it.key}" })
    @Test fun `catalog contains nine weapons three complete armor sets and finite guaranteed recipes including netherite`() {
        val r=rules()
        assertEquals(10,r.items.values.count { it.kind in setOf(CombatKind.MELEE,CombatKind.RANGED,CombatKind.MAGIC) })
        assertEquals(12,r.items.values.count { it.kind==CombatKind.ARMOR });assertEquals(3,r.sets.size)
        assertTrue(r.recipes.getValue("nether_edge_reforge").ingredients.any { "NETHERITE_SWORD" in it.materials })
        assertTrue(r.recipes.getValue("nether_edge").ingredients.any { "DIAMOND_SWORD" in it.materials })
        assertTrue(r.recipes.getValue("nether_edge").ingredients.any { "NETHERITE_INGOT" in it.materials })
        assertTrue(r.recipes.getValue("rift_staff").ingredients.any { "NETHERITE_INGOT" in it.materials })
        assertTrue(r.recipes.getValue("fragment_resources").ingredients.all { it.combatId==null })
        assertTrue(r.rewards.values.all { it.guaranteed.any { e -> e.item=="battle_fragment" } })
    }
    @Test fun `reject cyclic custom recipes invalid numbers missing armor slot and nonguaranteed guaranteed rewards`() {
        assertThrows(IllegalArgumentException::class.java) { items {it.set("items.oneblock:rift_staff.stats.damage",Double.NaN)} }
        assertThrows(IllegalArgumentException::class.java) { rules(catalog=items {it.set("sets.warden",null)}) }
        assertThrows(IllegalArgumentException::class.java) { items {it.set("items.oneblock:warden_helmet.slot",null)} }
        assertThrows(IllegalArgumentException::class.java) { rules(change={ j ->
            j.getAsJsonObject("recipes").getAsJsonObject("fragment_resources").getAsJsonArray("ingredients").let { a -> a.forEach { row -> row.asJsonObject.remove("material");row.asJsonObject.addProperty("item","battle_fragment") } }
            j.getAsJsonObject("recipes").remove("fragment_bones");j.getAsJsonObject("recipes").remove("fragment_flesh")
        }) }
        assertThrows(IllegalArgumentException::class.java) { rules(change={it.getAsJsonObject("rewards").getAsJsonObject("field_encounter").getAsJsonArray("guaranteed")[0].asJsonObject.addProperty("chance",0.1)}) }
    }
    @Test fun `magic trail configuration is bounded and legacy config uses defaults`() {
        val legacy=rules(change={it.remove("magic-trail")})
        assertEquals("ELECTRIC_SPARK",legacy.magicTrailParticle);assertEquals(2,legacy.magicTrailCount)
        for((key,value) in listOf("particle" to "EXPLOSION_EMITTER","count" to 999,"interval-ticks" to 0)) {
            assertThrows(IllegalArgumentException::class.java) {rules(change={j->
                val trail=j.getAsJsonObject("magic-trail")
                when(value) {is String->trail.addProperty(key,value);is Int->trail.addProperty(key,value)}
            })}
        }
    }
    @Test fun `legacy combat config enables lobby and creative while explicit booleans remain authoritative`() {
        val legacy=rules(change={it.remove("allow-hub-abilities");it.remove("allow-creative-abilities")})
        assertTrue(legacy.hubAbilities && legacy.creativeAbilities)
        val restricted=rules(change={it.addProperty("allow-hub-abilities",false);it.addProperty("allow-creative-abilities",false)})
        assertFalse(restricted.hubAbilities || restricted.creativeAbilities)
        assertThrows(IllegalArgumentException::class.java) {rules(change={it.addProperty("allow-hub-abilities","yes")})}
    }
    @Test fun `armor slots and complete set bonus cannot be duplicated by using same helmet in all slots`() {
        val r=rules();val fake=CombatMath.stats(r,listOf("HEAD","CHEST","LEGS","FEET").associateWith { "weaver_helmet" })
        assertEquals(105.0,fake.maxMana);assertEquals(1.01,fake.magicMultiplier,1e-9)
        val full=CombatMath.stats(r,mapOf("HEAD" to "weaver_helmet","CHEST" to "weaver_chestplate","LEGS" to "weaver_leggings","FEET" to "weaver_boots"))
        assertEquals(120.0,full.maxMana);assertEquals(1.04,full.magicMultiplier,1e-9);assertEquals(1.1,full.regenMultiplier,1e-9);assertEquals("weaver_flow",full.setProperty)
        val defensive=CombatMath.stats(r,mapOf("HEAD" to "warden_helmet","CHEST" to "warden_chestplate","LEGS" to "warden_leggings","FEET" to "warden_boots"))
        assertEquals(.04,defensive.defense,1e-9);assertEquals("guardian_resolve",defensive.setProperty)
    }
    @Test fun `mana cooldown spans switching wands clamps gear change and regenerates without polling`() {
        val r=rules();val stats=CombatMath.stats(r,emptyMap());val pool=ManaPool(100.0,0)
        assertTrue(pool.spend(0,stats,8.0,30.0,2.0));assertEquals(70.0,pool.value)
        assertFalse(pool.spend(1_000_000_000,stats,8.0,12.0,.9))
        assertTrue(pool.spend(2_000_000_000,stats,8.0,12.0,.9));assertEquals(74.0,pool.value)
        pool.value=200.0;assertTrue(pool.spend(10_000_000_000,stats,8.0,12.0,.9));assertEquals(88.0,pool.value)
        assertEquals(11.5,CombatMath.melee(8.0,3.5,1.0));assertEquals(8.7,CombatMath.melee(8.0,3.5,0.0),1e-9)
    }
    @Test fun `mana accepts a negative monotonic clock origin and cooldown across zero`() {
        val stats=CombatMath.stats(rules(),emptyMap());val pool=ManaPool(100.0,-1_000_000_000)
        assertTrue(pool.spend(-1_000_000_000,stats,8.0,30.0,2.0))
        assertFalse(pool.spend(0,stats,8.0,30.0,2.0))
        assertTrue(pool.spend(1_000_000_000,stats,8.0,30.0,2.0));assertEquals(56.0,pool.value)
    }
    @Test fun `reward receipt deduplicates after claim collection market history pruning and config replacement`() {
        val i=island();val q=request(i);val r=rules();val first=CombatRewardDomain.grant(i,MarketState(),q,catalog(r))
        assertTrue(first.state.claims.size>=3);assertTrue(first.state.claims.values.all { it.recipient==q.recipient && it.itemData!!.startsWith("exact:") })
        val collected=first.state.copy(claims=emptyMap(),transfers=emptyMap())
        val replay=CombatRewardDomain.grant(first.island,collected,q,catalog(r.copy(enabled=false,rewards=emptyMap())))
        assertEquals(first.island,replay.island);assertTrue(replay.state.claims.isEmpty())
        val moved=i.copy(owner=UUID.randomUUID(),ownerPlayerId=q.recipient)
        assertThrows(IllegalArgumentException::class.java) { CombatRewardDomain.grant(moved,collected,q.copy(account=moved.owner),catalog(r)) }
        val pruned=MarketEngine(MarketSettings.load(org.bukkit.configuration.file.YamlConfiguration()).copy(historyLimit=1))
            .transaction(i.owner,i.ownerPlayer,i.coins,first.state) { Unit }.state
        assertSame(first.state.combatReceipts,pruned.combatReceipts)
        assertThrows(IllegalArgumentException::class.java) { CombatRewardDomain.grant(first.island,collected,q.copy(reward="field_encounter"),catalog(r)) }
        assertEquals(CombatRewardDomain.selected(q,r.rewards.getValue(q.reward)),CombatRewardDomain.selected(q,r.rewards.getValue(q.reward)))
    }
    @Test fun `receipt capacity fails closed and receipt storage does not inflate legacy profile JSON`() {
        val legacy=ReleaseStateCodec.normalize(JsonParser.parseString("{\"version\":1}"))!!
        assertFalse(legacy.asJsonObject.has("combatRewards"))
        val i=island();val q=request(i);val r=rules().copy(receiptLimit=1)
        val first=CombatRewardDomain.grant(i,MarketState(),q,catalog(r))
        assertEquals(q.fingerprint,first.state.combatReceipts[q.receipt]!!.fingerprint)
        assertThrows(IllegalArgumentException::class.java) { CombatRewardDomain.grant(first.island,first.state,q.copy(event=UUID.randomUUID()),catalog(r)) }

    }
    @Test fun `reward requires explicit live account recipient and arbitrary client recipe action cannot mint reward`() {
        val i=island();val q=request(i);val r=rules()
        assertThrows(IllegalArgumentException::class.java) { CombatRewardDomain.grant(i,MarketState(),q.copy(recipient=UUID.randomUUID()),catalog(r)) }
        assertThrows(IllegalArgumentException::class.java) { CombatRewardDomain.grant(i.copy(retiredAt=1),MarketState(),q,catalog(r)) }
        val f=folder.resolve("progression.yml").toFile();f.writeText(javaClass.getResourceAsStream("/progression.yml")!!.reader().readText())
        val release=ReleaseRules.read(f,true).first!!.let { it.copy(recipes=it.recipes+r.recipes,combat=r) }
        assertThrows(IllegalStateException::class.java) { ReleaseDomain(release).plan(i,MarketState(),i.ownerPlayer,"combat_reward","elite_encounter") }
        val intent=ReleaseDomain(release).plan(i.copy(coins=100000),MarketState(),i.ownerPlayer,"recipe","rift_staff")
        assertEquals("rift_staff",intent.items.single().tool);assertTrue(intent.personal)
    }
}
