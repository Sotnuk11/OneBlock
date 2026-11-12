package com.alytanta.oneblock.combat

import com.alytanta.oneblock.release.*
import com.alytanta.oneblock.util.StrictYaml
import org.bukkit.Material
import org.bukkit.configuration.ConfigurationSection
import org.bukkit.configuration.file.YamlConfiguration
import java.io.File

enum class CombatKind { MELEE, RANGED, MAGIC, ARMOR, COMPONENT, TOOL }
data class CombatItem(val id: String, val material: String, val kind: CombatKind, val name: String, val lore: List<String>,
    val damage: Double = 0.0, val multiplier: Double = 1.0, val mana: Double = 0.0, val cooldown: Double = 0.0,
    val speed: Double = 2.5, val armorSlot: String? = null, val set: String? = null,
    val defense: Double = 0.0, val manaBonus: Double = 0.0, val magicBonus: Double = 0.0,
    val definition:com.alytanta.oneblock.items.ItemDefinition?=null,val meleeBonus:Double=0.0,val rangedBonus:Double=0.0,val regenBonus:Double=0.0)
data class CombatSet(val defense: Double, val mana: Double, val magic: Double,val property:String?=null,val strength:Double=0.0)
data class RewardEntry(val item: String, val amount: Int, val chance: Double = 1.0)
data class CombatReward(val guaranteed: List<RewardEntry>, val trophies: List<RewardEntry>)
data class CombatRules(val enabled: Boolean, val items: Map<String,CombatItem>, val recipes: Map<String,RecipeRule>,
    val sets: Map<String,CombatSet>, val rewards: Map<String,CombatReward>, val baseMana: Double,
    val manaPerSecond: Double, val defenseCap: Double, val receiptLimit: Int, val messages: Map<String,String>,
    val magicTrailParticle:String="ELECTRIC_SPARK",val magicTrailCount:Int=2,val magicTrailInterval:Long=2,val demo:DemoSettings=DemoSettings(),val headEnchantCost:Int=2,val bossControlScale:Double=0.0,
    val hubAbilities:Boolean=true,val creativeAbilities:Boolean=true) {
    companion object {
        private val ID=Regex("[a-z][a-z0-9_]{0,47}")
        fun read(file: File,catalog:com.alytanta.oneblock.items.ItemCatalog?=null,externalCrafted:Set<String> = emptySet()): Pair<CombatRules?,String?> {
            if(!file.exists())return null to null
            val source=file.readText(Charsets.UTF_8);StrictYaml.validate(source)
            val y=YamlConfiguration().apply { loadFromString(source) }
            require(y.getInt("version")==1 && y.isBoolean("enabled"))
            fun section(path:String)=requireNotNull(y.getConfigurationSection(path)) { "Missing combat $path" }
            fun number(s:ConfigurationSection,key:String,default:Double,min:Double,max:Double)=s.getDouble(key,default).also {
                require((!s.contains(key)||s.get(key) is Number) && it.isFinite() && it in min..max) { "Invalid combat $key" }
            }
            fun integer(s:ConfigurationSection,key:String,default:Int,min:Int,max:Int):Int {
                val n=if(s.contains(key))s.get(key).toString().toIntOrNull() ?: error("Invalid integer $key") else default
                require(n in min..max);return n
            }
            fun <T> rows(path:String,read:(String,ConfigurationSection)->T):Map<String,T> = section(path).let { s -> s.getKeys(false).associateWith { id ->
                require(ID.matches(id));read(id,requireNotNull(s.getConfigurationSection(id)))
            } }
            // items.yml is canonical when it declares set bonuses. combat.yml sets remain a read-only legacy fallback.
            val sets=catalog?.sets?.takeIf {it.isNotEmpty()}?.mapValues {(_,bonus)->CombatSet(bonus.defense,bonus.mana,bonus.magic,bonus.property,bonus.strength)}
                ?: if(catalog==null)rows("sets") { _,s -> CombatSet(number(s,"defense",0.0,0.0,0.25),number(s,"mana",0.0,0.0,500.0),number(s,"magic",0.0,0.0,2.0)) } else emptyMap()
            val legacyItems=if(catalog==null)rows("items") { id,s ->
                val kind=CombatKind.valueOf(s.getString("kind")!!)
                val slot=s.getString("slot");val set=s.getString("set")
                require(if(kind==CombatKind.ARMOR)slot in setOf("HEAD","CHEST","LEGS","FEET") && set in sets else slot==null && set==null)
                CombatItem(id,s.getString("material")!!.also { require(Material.matchMaterial(it)!=null) },kind,s.getString("name")!!,s.getStringList("lore"),
                    number(s,"damage",0.0,0.0,100.0),number(s,"multiplier",1.0,0.1,5.0),number(s,"mana-cost",0.0,0.0,500.0),
                    number(s,"cooldown-seconds",0.0,0.0,60.0),number(s,"projectile-speed",2.5,0.5,4.0),slot,set,
                    number(s,"defense",0.0,0.0,0.10),number(s,"mana",0.0,0.0,100.0),number(s,"magic",0.0,0.0,0.5)).also {
                        require(kind!=CombatKind.MAGIC || it.damage>0 && it.mana>0 && it.cooldown>=0.25)
                    }
            } else emptyMap()
            val items=catalog?.items?.values?.associate {d->
                fun sum(type:String,key:String)=d.effects(type).sumOf {it.number(key)}
                val magic=d.abilities.firstOrNull {it.type in setOf("magic_bolt","aimed_lightning","spark_stream","frost_burst")}
                require(d.kind!=com.alytanta.oneblock.items.ItemKind.ARMOR || d.set in sets) {"${d.id}.set: unknown combat set ${d.set}"}
                d.legacyId to CombatItem(d.legacyId,d.material,CombatKind.valueOf(d.kind.name),d.name,d.lore,
                    if(d.kind==com.alytanta.oneblock.items.ItemKind.MAGIC)magic?.number("damage")?:0.0 else sum("melee_bonus","damage"),d.effects("ranged_multiplier").fold(1.0) {value,a->value*a.number("multiplier",1.0)},
                    magic?.number("mana-cost")?:0.0,magic?.number("cooldown-seconds")?:0.0,magic?.number("projectile-speed")?:2.5,d.slot,d.set,
                    sum("armor_stats","defense"),sum("armor_stats","mana"),sum("armor_stats","magic"),d,sum("armor_stats","melee"),sum("armor_stats","ranged"),sum("armor_stats","regen"))
            }?:legacyItems
            val recipes=rows("recipes") { id,s ->
                val inputs=s.getMapList("ingredients").map { row ->
                    val custom=row["item"]?.toString()?.removePrefix("oneblock:");val material=custom?.let { items.getValue(it).material } ?: row["material"].toString()
                    require(Material.matchMaterial(material)!=null && !(custom!=null && row.containsKey("material")))
                    Ingredient(setOf(material),row["amount"].toString().toInt().also { require(it in 1..4096) },custom)
                }
                val outputs=s.getMapList("items").map { row -> if(row.containsKey("material"))return@map ReleaseItem(requireNotNull(Material.matchMaterial(row["material"].toString())).name,row["amount"].toString().toInt().also { require(it in 1..64) })
                    val item=items.getValue(row["item"].toString().removePrefix("oneblock:"))
                    ReleaseItem(item.material,row["amount"].toString().toInt().also { require(it in 1..64 && (item.kind==CombatKind.COMPONENT || it==1)) },item.id) }
                require(inputs.isNotEmpty() && outputs.isNotEmpty())
                val chapter=s.getString("chapter","workshop")!!;require(chapter in ReleaseRules.CHAPTERS)
                RecipeRule(id,chapter,null,0,integer(s,"coins",0,0,1_000_000).toLong(),inputs,outputs)
            }
            val playableRecipes=recipes
            // Every custom ingredient and every trophy also has a finite, deterministic crafting path.
            val reachable=externalCrafted.toMutableSet()
            repeat(items.size+1) { playableRecipes.values.filter { r -> r.ingredients.all { it.combatId==null || it.combatId in reachable } }
                .forEach { r -> reachable.addAll(r.items.mapNotNull { it.tool }) } }
            require(items.values.filter {it.kind!=CombatKind.ARMOR}.all { it.id in reachable }) { "Unreachable/cyclic combat item" }
            sets.keys.forEach { set -> require(items.values.filter { it.set==set }.map { it.armorSlot }.toSet()==setOf("HEAD","CHEST","LEGS","FEET")) }
            fun entries(s:ConfigurationSection,key:String)=s.getMapList(key).map { row ->
                RewardEntry(row["item"].toString().removePrefix("oneblock:").also { require(it in items) },row["amount"].toString().toInt().also { require(it in 1..64) },
                    row["chance"]?.toString()?.toDouble() ?: 1.0).also { require(it.chance.isFinite() && it.chance in 0.0..1.0 && (items.getValue(it.item).kind==CombatKind.COMPONENT || it.amount==1)) }
            }
            val rewards=rows("rewards") { _,s -> CombatReward(entries(s,"guaranteed").filter {items[it.item]?.kind!=CombatKind.ARMOR},entries(s,"trophies").filter {items[it.item]?.kind!=CombatKind.ARMOR}).also {
                require(it.guaranteed.all { entry -> entry.chance==1.0 })
            } }
            require(!y.contains("demo") || y.isConfigurationSection("demo")) {"combat demo must be a mapping"}
            require(!y.contains("magic-trail") || y.isConfigurationSection("magic-trail")) {"combat magic-trail must be a mapping"}
            val trail=y.getConfigurationSection("magic-trail")
            require(trail==null || trail.getKeys(false).all {it in setOf("particle","count","interval-ticks")}) {"Invalid combat magic-trail key"}
            val particle=trail?.getString("particle","ELECTRIC_SPARK")?:"ELECTRIC_SPARK"
            require(particle in setOf("ELECTRIC_SPARK","END_ROD","CRIT")) {"Unsupported combat magic-trail particle"}
            val count=trail?.let {integer(it,"count",2,0,8)}?:2
            val interval=trail?.let {integer(it,"interval-ticks",2,1,20)}?:2
            for(key in listOf("allow-hub-abilities","allow-creative-abilities"))require(!y.contains(key)||y.isBoolean(key)) {"$key must be boolean"}
            return CombatRules(y.getBoolean("enabled"),items,playableRecipes,sets,rewards,
                number(y,"mana.base",100.0,1.0,1000.0),number(y,"mana.regeneration-per-second",8.0,0.1,100.0),
                number(y,"defense-cap",0.35,0.0,0.75),integer(y,"reward-receipt-limit",100000,1,1000000),
                section("messages").getKeys(false).associateWith { section("messages").getString(it)!! },particle,count,interval.toLong(),DemoSettings.read(y.getConfigurationSection("demo")),integer(y,"head-enchant-level-cost",2,1,10),number(y,"boss-control-duration-scale",0.0,0.0,.5),y.getBoolean("allow-hub-abilities",true),y.getBoolean("allow-creative-abilities",true)) to source
        }
    }
    fun validateMaterials() {
        items.values.forEach {
            val material=Material.valueOf(it.material)
            val headArmor=it.kind==CombatKind.ARMOR && it.armorSlot=="HEAD" && material==Material.PLAYER_HEAD
            require(material.isItem && (it.kind==CombatKind.COMPONENT || material.maxStackSize==1 || it.kind==CombatKind.MAGIC || headArmor)) {
                "Combat item ${it.id}: unsupported material ${it.material} for ${it.kind}/${it.armorSlot}"
            }
        }
    }
}
