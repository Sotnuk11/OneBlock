package com.alytanta.oneblock.items

import com.alytanta.oneblock.combat.CombatRules
import com.alytanta.oneblock.combat.CombatKind
import org.bukkit.configuration.file.YamlConfiguration
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

class ItemCatalogTest {
    @TempDir lateinit var temporary:Path
    private fun file(name:String="items.yml")=temporary.resolve(name).toFile().apply {writeText(this@ItemCatalogTest.javaClass.getResourceAsStream("/$name")!!.reader(Charsets.UTF_8).use {it.readText()})}
    private val tools=(1..3).flatMap {tier->listOf("pickaxe_$tier","axe_$tier","hoe_$tier")}.toSet()
    @Test fun `legacy identities and tool balances remain compatible with three combat sets`() {
        val catalog=ItemCatalog.read(file()).first!!
        val combatFile=file("combat.yml");val merged=CombatRules.read(combatFile,catalog,tools).first!!
        assertEquals(34,catalog.items.size);assertEquals(10,merged.items.values.count {it.kind in setOf(CombatKind.MELEE,CombatKind.RANGED,CombatKind.MAGIC)})
        assertEquals(12,merged.items.values.count {it.kind==CombatKind.ARMOR});assertEquals(3,merged.sets.size)
        assertEquals("guardian_resolve",merged.sets.getValue("warden").property);assertEquals("weaver_flow",merged.sets.getValue("weaver").property)
        assertEquals("PLAYER_HEAD",catalog.require("warden_helmet").material)
        assertEquals("LEATHER_CHESTPLATE",catalog.require("warden_chestplate").material)
        assertEquals(4.0,catalog.require("warden_helmet").armorCompensation)
        assertEquals(9.0,catalog.require("warden_chestplate").armorCompensation)
        assertEquals(4.0,catalog.require("warden_helmet").armorToughnessCompensation)
        assertEquals(4.0,catalog.require("warden_chestplate").armorToughnessCompensation)
        assertEquals(16.0,catalog.items.values.filter {it.set=="warden"}.sumOf {it.armorToughnessCompensation})
        assertEquals(16.0,catalog.items.values.filter {it.set=="weaver"}.sumOf {it.armorToughnessCompensation})
        // Item attribute components replace vanilla defaults, as verified on native Folia.
        for(set in listOf("warden","ranger","weaver"))assertEquals(24.0,
            listOf("helmet","chestplate","leggings","boots").sumOf {part->catalog.require("${set}_$part").armorCompensation})
        assertTrue(merged.recipes.containsKey("nether_edge_reforge"))
        val progress=YamlConfiguration().apply {load(file("progression.yml"))}
        tools.forEach {id->val effect=catalog.require(id).effects("tool_traits").single()
            assertEquals(progress.getDouble("recipes.$id.durability"),effect.number("durability"));assertEquals(progress.getDouble("recipes.$id.sapling"),effect.number("sapling"))}
    }
    @Test fun `a new ID combines melee and magic through existing mechanics and recipe data only`() {
        val source=file();val y=YamlConfiguration().apply {load(source)}
        val blade=y.getConfigurationSection("items.oneblock:warden_blade")!!
        blade.getKeys(true).filterNot(blade::isConfigurationSection).forEach {key->y.set("items.oneblock:test_blade.$key",blade.get(key))}
        y.set("items.oneblock:test_blade.stats",mapOf("damage" to 4.0,"mana-cost" to 12.0,"cooldown-seconds" to 1.0,"projectile-speed" to 2.5))
        y.set("items.oneblock:test_blade.tags",listOf("melee","unique"))
        y.set("items.oneblock:test_blade.abilities",listOf(mapOf("ability" to "oneblock:melee","trigger" to "HIT"),mapOf("ability" to "oneblock:bolt","trigger" to "USE","hand" to "OFF_HAND")))
        y.save(source);val catalog=ItemCatalog.read(source).first!!
        assertEquals(setOf("melee_bonus","magic_bolt"),catalog.require("test_blade").abilities.map {it.type}.toSet())
        val combat=file("combat.yml");val c=YamlConfiguration().apply {load(combat)}
        c.set("recipes.test_blade",mapOf("chapter" to "workshop","coins" to 0,"ingredients" to listOf(mapOf("material" to "IRON_INGOT","amount" to 1)),
            "items" to listOf(mapOf("item" to "oneblock:test_blade","amount" to 1))));c.save(combat)
        val rules=CombatRules.read(combat,catalog,tools).first!!
        assertEquals("test_blade",rules.recipes.getValue("test_blade").items.single().tool)
        assertEquals(4.0,rules.items.getValue("test_blade").damage)
        assertNull(catalog.find("oneblock:unique")) // A tag never resolves to an item identity.
    }
    @Test fun `invalid IDs mechanics references parameters and repeated keys fail with precise paths`() {
        val source=file();val original=source.readText()
        val candidates=listOf("items.oneblock:warden_blade.model" to "oneblock:missing","items.oneblock:warden_blade.name" to "release.name.warden_blade",
            "abilities.oneblock:melee.type" to "execute_kotlin","items.oneblock:warden_blade.stats.damage" to Double.NaN)
        for((path,value) in candidates) {
            val y=YamlConfiguration().apply {loadFromString(original);set(path,value)};y.save(source)
            val failure=assertThrows(Exception::class.java) {ItemCatalog.read(source)};assertTrue(failure.message.orEmpty().isNotBlank())
        }
        source.writeText(original+"\nversion: 1\n");assertThrows(Exception::class.java) {ItemCatalog.read(source)}
        for(cooldown in listOf(0.0,.1,61.0,Double.NaN)) {
            assertThrows(IllegalArgumentException::class.java) {ItemMechanics.validate("items.oneblock:test.abilities[0]","magic_bolt",ItemTrigger.USE,
                mapOf("damage" to 4.0,"mana-cost" to 10.0,"cooldown-seconds" to cooldown,"projectile-speed" to 2.5,"lifetime-ticks" to 100.0))}
        }
    }
    @Test fun `reload preserves identities carriers stack limits and permits cosmetic and statistic updates`() {
        val old=ItemCatalog.read(file()).first!!;val id="oneblock:warden_blade";val item=old.items.getValue(id)
        assertThrows(IllegalStateException::class.java) {ItemCatalog(old.items-id).compatible(old)}
        assertThrows(IllegalArgumentException::class.java) {ItemCatalog(old.items+(id to item.copy(material="DIAMOND_SWORD"))).compatible(old)}
        val helmet=old.require("warden_helmet")
        val legacy=ItemCatalog(old.items+(helmet.id to helmet.copy(material="DIAMOND_HELMET",migrationFrom=emptySet())))
        assertDoesNotThrow {old.compatible(legacy)}
        assertThrows(IllegalArgumentException::class.java) {ItemCatalog(old.items+(helmet.id to helmet.copy(migrationFrom=emptySet()))).compatible(legacy)}
        assertDoesNotThrow {ItemCatalog(old.items+(id to item.copy(name="Оновлена назва",stats=item.stats+("damage" to 6.0)))).compatible(old)}
        assertNotEquals(item.appearanceHash,item.copy(stats=item.stats+("damage" to 6.0)).appearanceHash)
    }
    @Test fun `armor rejects wrong slots and duplicate or wrong slot migration carriers`() {
        val source=file(); val original=source.readText()
        for ((path,value) in listOf(
            "items.oneblock:warden_chestplate.material" to "LEATHER_BOOTS",
            "items.oneblock:warden_helmet.migration-from" to listOf("DIAMOND_HELMET","DIAMOND_HELMET"),
            "items.oneblock:warden_helmet.migration-from" to listOf("DIAMOND_BOOTS")
        )) {
            YamlConfiguration().apply { loadFromString(original); set(path,value); save(source) }
            assertThrows(IllegalArgumentException::class.java) { ItemCatalog.read(source) }
        }
    }
    @Test fun `guard is bound to the existing sword and rejects invalid configuration`() {
        val source=file();val original=source.readText()
        val guard=ItemCatalog.read(source).first!!.require("warden_blade").effects("charged_guard").single()
        assertEquals(ItemTrigger.HIT,guard.trigger);assertEquals(ItemHand.MAIN_HAND,guard.hand)
        for ((path,value) in listOf("abilities.oneblock:warden_guard.parameters.hits" to 2.5,
            "abilities.oneblock:warden_guard.parameters.reduction" to 1.0,
            "abilities.oneblock:warden_guard.type" to "execute_code",
            "abilities.oneblock:lightning.particle" to "BLOCK",
            "abilities.oneblock:lightning.sound" to "execute:arbitrary",
            "abilities.oneblock:cleave.parameters.cos-angle" to -1.0)) {
            YamlConfiguration().apply {loadFromString(original);set(path,value);save(source)}
            assertThrows(Exception::class.java) {ItemCatalog.read(source)}
        }
    }
}
