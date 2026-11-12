package com.alytanta.oneblock.items

import com.alytanta.oneblock.util.StrictYaml
import org.bukkit.Material
import org.bukkit.configuration.ConfigurationSection
import org.bukkit.configuration.file.YamlConfiguration
import java.io.File
import java.security.MessageDigest

enum class ItemKind { MELEE, RANGED, MAGIC, ARMOR, COMPONENT, TOOL }
enum class ItemTrigger { USE, HIT, SHOOT, EQUIP, GATHER }
enum class ItemHand { MAIN_HAND, OFF_HAND, EITHER }
data class ItemAbility(val id:String,val type:String,val trigger:ItemTrigger,val hand:ItemHand,
    val parameters:Map<String,Double>,val permission:String?,val worlds:Set<String>,val cooldownGroup:String,val particle:String?=null,val sound:String?=null) {
    fun number(key:String,default:Double=0.0)=parameters[key]?:default
}
data class ItemDefinition(val id:String,val material:String,val kind:ItemKind,val name:String,val lore:List<String>,
    val model:String?,val rarity:String,val tags:Set<String>,val stats:Map<String,Double>,val abilities:List<ItemAbility>,
    val slot:String?=null,val set:String?=null,val equipmentModel:String?=null,val leatherColor:String?=null,
    val headTexture:String?=null,val headProfile:String?=null,val armorCompensation:Double=0.0,val armorToughnessCompensation:Double=0.0,
    val migrationFrom:Set<String> = emptySet(),val stackSize:Int=1,
    val marketTransfer:Boolean=true,val vanillaRepair:Boolean=true) {
    val legacyId get()=id.removePrefix("oneblock:")
    fun effects(type:String)=abilities.filter {it.type==type}
    val appearanceHash:String get()=MessageDigest.getInstance("SHA-256").digest(
        listOf("presentation-v7",id,material,name,lore.joinToString("\n"),model,rarity,equipmentModel,leatherColor,headTexture,headProfile,armorCompensation,armorToughnessCompensation,migrationFrom.sorted(),stackSize,stats.toSortedMap()).joinToString("\u0000").toByteArray()).joinToString("") {"%02x".format(it)}
}
data class ItemSetBonus(val defense:Double=0.0,val mana:Double=0.0,val magic:Double=0.0,val property:String?=null,val strength:Double=0.0)

/** A finite registry of trusted handlers. YAML is data, never executable code. */
object ItemMechanics {
    data class Rule(val trigger:ItemTrigger,val ranges:Map<String,ClosedFloatingPointRange<Double>>,val optional:Set<String> = emptySet())
    val rules=mapOf(
        "shortbow" to Rule(ItemTrigger.USE,mapOf("cooldown-seconds" to (.25..10.0),"damage-scale" to (.1..1.0),"speed" to (1.0..5.0))),
        "melee_bonus" to Rule(ItemTrigger.HIT,mapOf("damage" to (0.0..100.0))),
        "charged_guard" to Rule(ItemTrigger.HIT,mapOf("charge-threshold" to (0.5..1.0),"hits" to (1.0..10.0),"window-seconds" to (0.5..30.0),"duration-seconds" to (0.5..15.0),"reduction" to (0.0..0.5))),
        "aimed_lightning" to Rule(ItemTrigger.USE,mapOf("damage" to (0.1..100.0),"mana-cost" to (0.1..500.0),"cooldown-seconds" to (.25..60.0),"range" to (1.0..24.0),"jumps" to (0.0..4.0),"jump-range" to (1.0..6.0),"falloff" to (0.1..1.0))),
        "ember_mark" to Rule(ItemTrigger.SHOOT,mapOf("bonus" to (0.0..0.5),"delay-ticks" to (1.0..100.0),"radius" to (0.5..4.0),"targets" to (1.0..8.0),"cooldown-seconds" to (.25..30.0))),
        "wide_cleave" to Rule(ItemTrigger.USE,mapOf("damage" to (.1..40.0),"mana-cost" to (0.0..100.0),"cooldown-seconds" to (1.0..30.0),"range" to (1.0..5.0),"cos-angle" to (0.0..1.0),"targets" to (1.0..8.0),"charge-threshold" to (.5..1.0))),
        "precise_slash" to Rule(ItemTrigger.HIT,mapOf("hits" to (2.0..8.0),"window-seconds" to (1.0..15.0),"charge-threshold" to (.5..1.0),"bonus" to (0.0..1.0))),
        "wind_slow" to Rule(ItemTrigger.SHOOT,mapOf("force" to (.5..1.0),"duration-ticks" to (1.0..100.0),"amplifier" to (0.0..2.0))),
        "ranged_focus" to Rule(ItemTrigger.SHOOT,mapOf("step" to (0.0..0.1),"cap" to (0.0..0.3),"window-seconds" to (1.0..15.0))),
        "spark_stream" to Rule(ItemTrigger.USE,mapOf("damage" to (.1..100.0),"mana-cost" to (.1..500.0),"cooldown-seconds" to (.5..60.0),"range" to (1.0..16.0),"pulses" to (1.0..5.0),"interval-ticks" to (10.0..30.0))),
        "frost_burst" to Rule(ItemTrigger.USE,mapOf("damage" to (.1..100.0),"mana-cost" to (.1..500.0),"cooldown-seconds" to (.5..60.0),"range" to (1.0..16.0),"radius" to (.5..4.0),"targets" to (1.0..8.0),"duration-ticks" to (1.0..100.0),"amplifier" to (0.0..2.0))),
        "ranged_multiplier" to Rule(ItemTrigger.SHOOT,mapOf("multiplier" to (0.1..5.0))),
        "magic_bolt" to Rule(ItemTrigger.USE,mapOf("damage" to (0.1..100.0),"mana-cost" to (0.1..500.0),"cooldown-seconds" to (.25..60.0),"projectile-speed" to (.5..4.0),"lifetime-ticks" to (1.0..200.0))),
        "armor_stats" to Rule(ItemTrigger.EQUIP,mapOf("defense" to (0.0..0.10),"mana" to (0.0..100.0),"magic" to (0.0..0.5),"melee" to (0.0..0.05),"ranged" to (0.0..0.05),"regen" to (0.0..0.10)),setOf("melee","ranged","regen")),
        "tool_traits" to Rule(ItemTrigger.GATHER,mapOf("durability" to (0.0..0.5),"sapling" to (0.0..0.25)))
    )
    fun validate(path:String,type:String,trigger:ItemTrigger,values:Map<String,Double>) {
        val rule=rules[type]?:error("$path.type: unsupported mechanic '$type'; supported: ${rules.keys}")
        require(trigger==rule.trigger) {"$path.trigger: $type supports ${rule.trigger}"}
        require(values.keys.containsAll(rule.ranges.keys-rule.optional) && values.keys.all {it in rule.ranges}) {"$path.parameters: expected ${rule.ranges.keys}, got ${values.keys}"}
        values.forEach {(key,value)->require(value.isFinite() && value in rule.ranges.getValue(key)) {"$path.parameters.$key: expected ${rule.ranges.getValue(key)}, got $value"}}
        if(type=="magic_bolt")require(values.getValue("lifetime-ticks")%1.0==0.0) {"$path.parameters.lifetime-ticks must be an integer"}
        if(type=="charged_guard")require(values.getValue("hits")%1.0==0.0) {"$path.parameters.hits must be an integer"}
        for(key in setOf("jumps","delay-ticks","targets","hits","duration-ticks","amplifier","pulses","interval-ticks").intersect(values.keys)) require(values.getValue(key)%1.0==0.0) {"$path.parameters.$key must be an integer"}
        if(type=="spark_stream")require(values.getValue("cooldown-seconds")*20>=values.getValue("pulses")*values.getValue("interval-ticks")) {"$path: stream cooldown must cover the complete channel"}
    }
}
data class ItemCatalog(val items:Map<String,ItemDefinition>,val messages:Map<String,String> = defaultMessages,val sets:Map<String,ItemSetBonus> = emptyMap(),val visibleIdFormat:String="&8ID: {id}") {
    fun find(id:String)=items[canonical(id)]
    fun require(id:String)=find(id)?:error("Unknown custom item '$id'")
    fun compatible(previous:ItemCatalog?) {
        previous?.items?.forEach {(id,old)->val next=items[id]?:error("items.yml: cannot remove saved ID $id during reload")
            kotlin.require((next.material==old.material || old.material in next.migrationFrom && old.kind==ItemKind.ARMOR && next.kind==ItemKind.ARMOR) && next.kind==old.kind && next.slot==old.slot && next.stackSize>=old.stackSize) {"$id: changing carrier/kind/slot or reducing stack-size requires an explicit armor migration-from entry"}}
    }
    companion object {
        val defaultMessages=mapOf("denied" to "&cНемає дозволу.","unavailable" to "&cКаталог предметів недоступний.",
            "entry" to "&e{id} &7— {name}","usage" to "&e/obadmin items list | status <UUID> | give <гравець> <oneblock:id> [1–64] [request-UUID]",
            "busy" to "&cЗаверши поточну передачу предметів.","queued" to "&aВидача {id} збережена. Предмети → особисті «Нагороди» початкового профілю. Повтор із тим самим UUID не дублює їх.",
            "failed" to "&cВидачу відхилено: {reason}","status" to "&e{id}: {state}; {item} × {amount}; профіль {account}",
            "pending" to "очікує збереження","done" to "збережено у видачах")
        fun canonical(id:String)=if(':' in id)id else "oneblock:$id"
        fun validId(id:String)=id.matches(Regex("oneblock:[a-z][a-z0-9_]{0,47}"))
        fun read(file:File):Pair<ItemCatalog?,String?> {
            if(!file.exists())return null to null
            val source=file.readText(Charsets.UTF_8);StrictYaml.validate(source)
            val y=YamlConfiguration().apply {loadFromString(source)}
            require(y.getInt("version")==1) {"items.yml version must be 1"}
            require(y.getKeys(false).all {it in setOf("version","models","equipment-models","abilities","items","sets","messages","visible-id-format")}) {"items.yml: unknown top-level key"}
            val models=y.getStringList("models").toSet();val equipmentModels=y.getStringList("equipment-models").toSet()
            require(models.isNotEmpty() && models.all(::validId) && equipmentModels.all(::validId)) {"items.yml: explicit models/equipment-models resource IDs required"}
            fun section(path:String)=requireNotNull(y.getConfigurationSection(path)) {"items.yml $path must be a mapping"}
            fun particle(value:String?):String?=value?.also {require(it in setOf("ELECTRIC_SPARK","END_ROD","SNOWFLAKE","SWEEP_ATTACK","FLAME","CRIT")) {"Unsupported ability particle $it"}}
            fun sound(value:String?):String?=value?.also {require(it in setOf("minecraft:entity.lightning_bolt.impact","minecraft:entity.generic.explode","minecraft:entity.player.attack.sweep","minecraft:block.amethyst_block.chime","minecraft:block.glass.break")) {"Unsupported ability sound $it"}}
            data class Ability(val type:String,val defaults:Map<String,Any?>,val particle:String?,val sound:String?)
            val definitions=section("abilities").let {all->all.getKeys(false).associateWith {id->
                require(validId(id)) {"abilities.$id: namespaced OneBlock ID required"}
                val s=section("abilities.$id");val type=s.getString("type")?:error("abilities.$id.type missing")
                require(type in ItemMechanics.rules) {"abilities.$id.type: unsupported '$type'"}
                require(s.getKeys(false).all {it in setOf("type","parameters","particle","sound")}) {"abilities.$id: unknown key"}
                Ability(type,s.getConfigurationSection("parameters")?.getValues(false).orEmpty(),particle(s.getString("particle")),sound(s.getString("sound")))
            }}
            val sets=y.getConfigurationSection("sets")?.getKeys(false)?.associateWith {id->
                require(id.matches(Regex("[a-z][a-z0-9_]{0,47}"))) {"sets.$id: invalid set ID"}
                val s=section("sets.$id")
                require(s.getKeys(false).all {it in setOf("defense","mana","magic","property","property-strength")}) {"sets.$id: unknown key"}
                fun bonus(key:String,max:Double)=(s.get(key) as? Number)?.toDouble()?.also {require(it.isFinite() && it in 0.0..max) {"sets.$id.$key outside 0..$max"}}?:if(s.contains(key))error("sets.$id.$key must be numeric") else 0.0
                val property=s.getString("property")
                require(property==null || property in setOf("guardian_resolve","ranger_focus","weaver_flow")) {"sets.$id.property"}
                val strength=bonus("property-strength",if(property=="guardian_resolve")5.0 else .25)
                require(property!=null || strength==0.0) {"sets.$id requires a property for property-strength"}
                ItemSetBonus(bonus("defense",0.25),bonus("mana",500.0),bonus("magic",2.0),property,strength)
            }.orEmpty()
            val items=section("items").let {all->all.getKeys(false).associateWith {id->
                val path="items.$id";require(validId(id)) {"$path: namespaced OneBlock ID required"}
                val s=section(path);val kind=runCatching {ItemKind.valueOf(s.getString("kind")!!)}.getOrElse {error("$path.kind: ${ItemKind.entries}")}
                require(s.getKeys(false).all {it in setOf("material","kind","name","lore","model","rarity","tags","stats","abilities","slot","set","equipment-model","color","head-texture","head-profile","armor-compensation","armor-toughness-compensation","migration-from","stack-size","market-transfer","vanilla-repair")}) {"$path: unknown key"}
                for(key in listOf("lore","tags","abilities"))require(s.isList(key)) {"$path.$key must be a list"}
                for(key in listOf("lore","tags"))require(s.getList(key)!!.all {it is String}) {"$path.$key must contain strings"}
                require(s.getList("abilities")!!.all {it is Map<*,*>}) {"$path.abilities must contain mappings"}
                val material=s.getString("material")!!;require(Material.matchMaterial(material)!=null) {"$path.material: unknown $material"}
                val name=s.getString("name")?:error("$path.name missing");require(name.isNotBlank() && !name.startsWith("release.") && !name.startsWith("@")) {"$path.name must be a display name"}
                val model=s.getString("model");require(if(model==null)kind==ItemKind.ARMOR else model in models) {"$path.model: unknown resource ID '$model' (only armor may omit its item model)"}
                val stats=s.getConfigurationSection("stats")?.getValues(false).orEmpty().mapValues {(key,value)->
                    (value as? Number)?.toDouble()?.also {require(it.isFinite()) {"$path.stats.$key must be finite"}}?:error("$path.stats.$key must be numeric")}
                val bindings=s.getMapList("abilities").mapIndexed {index,row->
                    val at="$path.abilities[$index]";val ability=row["ability"].toString();val definition=definitions[ability]?:error("$at.ability: unknown '$ability'")
                    require(row.keys.all {it in setOf("ability","trigger","hand","parameters","permission","worlds","cooldown-group","particle","sound")}) {"$at: unknown key"}
                    require(!row.containsKey("parameters") || row["parameters"] is Map<*,*>) {"$at.parameters must be a mapping"}
                    require(!row.containsKey("worlds") || row["worlds"] is List<*>) {"$at.worlds must be a list"}
                    val trigger=runCatching {ItemTrigger.valueOf(row["trigger"].toString())}.getOrElse {error("$at.trigger missing/unknown")}
                    val hand=runCatching {ItemHand.valueOf(row["hand"]?.toString()?:"MAIN_HAND")}.getOrElse {error("$at.hand: MAIN_HAND, OFF_HAND or EITHER")}
                    val params=(definition.defaults+(row["parameters"] as? Map<*,*>)?.entries.orEmpty().associate {it.key.toString() to it.value}).mapValues {(key,value)->
                        if(value is String && value.startsWith('@'))stats[value.drop(1)]?:error("$at.parameters.$key: unknown stat $value")
                        else (value as? Number)?.toDouble()?:error("$at.parameters.$key must be numeric or @stat")}
                    ItemMechanics.validate(at,definition.type,trigger,params)
                    val permission=row["permission"]?.toString()?.takeIf {it.isNotBlank()};require(permission==null || permission.matches(Regex("[a-z0-9_.-]+"))) {"$at.permission"}
                    val worlds=(row["worlds"] as? List<*>)?.map {it.toString()}?.toSet()?:setOf("island","dungeon","other")
                    require(worlds.isNotEmpty() && worlds.all {it in setOf("island","hub","dungeon","other")}) {"$at.worlds"}
                    val group=row["cooldown-group"]?.toString()?:ability;require(group.length in 1..96) {"$at.cooldown-group"}
                    ItemAbility(ability,definition.type,trigger,hand,params,permission,worlds,group,particle(row["particle"]?.toString()?:definition.particle),sound(row["sound"]?.toString()?:definition.sound))
                }
                require(bindings.map {it.id to it.trigger}.distinct().size==bindings.size) {"$path.abilities: duplicate binding"}
                require(bindings.size<=8) {"$path.abilities: at most 8 bindings per item"}
                val ranged=bindings.filter {it.type=="ranged_multiplier"}.fold(1.0) {value,a->value*a.number("multiplier")}
                require(ranged in .1..5.0) {"$path.abilities: combined ranged multiplier must be 0.1..5"}
                for((key,max) in mapOf("durability" to .5,"sapling" to .25))require(bindings.filter {it.type=="tool_traits"}.sumOf {it.number(key)}<=max) {"$path.abilities: combined tool $key must not exceed $max"}
                val slot=s.getString("slot");val set=s.getString("set");val equipment=s.getString("equipment-model")
                require(kind!=ItemKind.ARMOR || slot in setOf("HEAD","CHEST","LEGS","FEET") && (equipment==null || equipment in equipmentModels)) {"$path: armor requires a slot and optional known equipment-model"}
                val leatherColor=s.getString("color")?.also {require(it.matches(Regex("#[0-9a-fA-F]{6}"))) {"$path.color must be #RRGGBB"}}
                val headTexture=s.getString("head-texture")?.also { texture ->
                    require(HeadTextures.url(texture)!=null) {"$path.head-texture must be a textures.minecraft.net URL or base64 texture value"}
                }
                val headProfile=s.getString("head-profile")?.also {require(it.matches(Regex("[A-Za-z0-9_]{1,16}"))) {"$path.head-profile must be a Minecraft profile name"}}
                require(headTexture==null || headProfile==null) {"$path: set head-texture or head-profile, not both"}
                if(kind==ItemKind.ARMOR) {
                    val carrier=Material.valueOf(material)
                    if(slot=="HEAD")require(carrier==Material.PLAYER_HEAD && (headTexture!=null || headProfile!=null)) {"$path: head armor must use PLAYER_HEAD with head-texture or head-profile"}
                    else require(carrier==when(slot) {"CHEST"->Material.LEATHER_CHESTPLATE;"LEGS"->Material.LEATHER_LEGGINGS;"FEET"->Material.LEATHER_BOOTS;else->null} && leatherColor!=null) {"$path: armor carrier must match its slot and have a HEX color"}
                    require(equipment==null) {"$path.equipment-model must be omitted for vanilla armor presentation"}
                } else require(leatherColor==null && headTexture==null && headProfile==null && !s.contains("armor-compensation") && !s.contains("armor-toughness-compensation") && !s.contains("migration-from")) {"$path: armor presentation keys require kind ARMOR"}
                val compensation=(s.get("armor-compensation") as? Number)?.toDouble()?:if(s.contains("armor-compensation"))error("$path.armor-compensation must be numeric") else 0.0
                require(compensation.isFinite() && compensation in 0.0..20.0) {"$path.armor-compensation must be 0..20"}
                val toughness=(s.get("armor-toughness-compensation") as? Number)?.toDouble()?:if(s.contains("armor-toughness-compensation"))error("$path.armor-toughness-compensation must be numeric") else 0.0
                require(toughness.isFinite() && toughness in 0.0..20.0) {"$path.armor-toughness-compensation must be 0..20"}
                val migrationList=s.getStringList("migration-from")
                val migrationFrom=migrationList.toSet()
                require(migrationList.size==migrationFrom.size && migrationFrom.size<=4 && material !in migrationFrom) {"$path.migration-from contains an invalid/duplicate carrier"}
                require(kind==ItemKind.ARMOR || migrationFrom.isEmpty()) {"$path.migration-from is only valid for armor"}
                if(kind==ItemKind.ARMOR && migrationFrom.isNotEmpty()) {
                    val sameSlotCarriers=when(slot) {
                        "HEAD"->setOf("LEATHER_HELMET","CHAINMAIL_HELMET","IRON_HELMET","GOLDEN_HELMET","DIAMOND_HELMET","NETHERITE_HELMET","TURTLE_HELMET")
                        "CHEST"->setOf("LEATHER_CHESTPLATE","CHAINMAIL_CHESTPLATE","IRON_CHESTPLATE","GOLDEN_CHESTPLATE","DIAMOND_CHESTPLATE","NETHERITE_CHESTPLATE")
                        "LEGS"->setOf("LEATHER_LEGGINGS","CHAINMAIL_LEGGINGS","IRON_LEGGINGS","GOLDEN_LEGGINGS","DIAMOND_LEGGINGS","NETHERITE_LEGGINGS")
                        "FEET"->setOf("LEATHER_BOOTS","CHAINMAIL_BOOTS","IRON_BOOTS","GOLDEN_BOOTS","DIAMOND_BOOTS","NETHERITE_BOOTS")
                        else->emptySet()
                    }
                    require(migrationFrom.all {it in sameSlotCarriers}) {"$path.migration-from must contain armor materials for slot $slot only"}
                }
                val stack=s.getInt("stack-size",if(kind==ItemKind.COMPONENT)64 else 1)
                require(!s.contains("stack-size") || s.isInt("stack-size")) {"$path.stack-size must be an integer"}
                require(stack in 1..64 && (kind==ItemKind.COMPONENT || stack==1)) {"$path.stack-size"}
                for(key in listOf("market-transfer","vanilla-repair"))require(!s.contains(key)||s.isBoolean(key)) {"$path.$key must be boolean"}
                ItemDefinition(id,material,kind,name,s.getStringList("lore"),model,s.getString("rarity","COMMON")!!.also {require(it in setOf("COMMON","UNCOMMON","RARE","EPIC","LEGENDARY")) {"$path.rarity"}},
                    s.getStringList("tags").toSet(),stats,bindings,slot,set,equipment,leatherColor,headTexture,headProfile,compensation,toughness,migrationFrom,stack,s.getBoolean("market-transfer",true),s.getBoolean("vanilla-repair",true))
            }}
            require(items.isNotEmpty()) {"items.yml items must not be empty"}
            val messages=defaultMessages+y.getConfigurationSection("messages")?.getValues(false).orEmpty().mapValues {(key,value)->
                require(key in defaultMessages && value is String && value.isNotBlank()) {"items.yml messages.$key: unknown/empty message"};value.toString()}
            val format=y.getString("visible-id-format","&8ID: {id}")!!;require(format.length in 4..256 && "{id}" in format && format.none {it=='\n' || it=='\r'})
            return ItemCatalog(items,messages,sets,format) to source
        }
    }
}
