package com.alytanta.oneblock.release

import com.alytanta.oneblock.oneblock.*
import org.bukkit.Material
import org.bukkit.configuration.ConfigurationSection
import org.bukkit.configuration.file.YamlConfiguration
import java.io.File
import kotlin.random.Random

data class ReleaseRules(val enabled: Boolean, val chapters: List<ChapterRule>, val tables: Map<String, WeightedDropTable>,
    val recipes: Map<String, RecipeRule>, val packs: Map<String, PackRule>,
    val fortuneCosts: List<Long>, val territoryCosts: List<Long>, val widths: List<Int>, val focusCost: Long,
    val xpScale: Long, val starterBreaks: Long, val chargePerBreak: Long,
    val tutorialIds: List<String>, val achievementIds: Set<String>,
    val livingItems: List<ReleaseItem>, val tutorialRewards: List<TutorialReward>,
    val xpRates: Map<String,Long>, val passiveStep: Double, val fortuneStep: Double, val coreBonusCap: Double,
    val natureTable: WeightedDropTable, val focusMultiplier: Int,
    val combat: com.alytanta.oneblock.combat.CombatRules? = null,
    val customItems:com.alytanta.oneblock.items.ItemCatalog?=null,
    val activities:com.alytanta.oneblock.activities.ActivityRules?=null) {
    fun index(chapter: String) = CHAPTERS.indexOf(chapter).also { require(it >= 0) }
    fun atLeast(current: String, required: String) = index(current) >= index(required)
    fun level(xp: Long): Int = (0..30).last { threshold(it) <= xp }
    fun threshold(level: Int): Long = Math.multiplyExact(xpScale, level.toLong() * level * level)
    fun bonus(xp: Long) = level(xp) / 5 * passiveStep
    fun table(progress: ReleaseProgress, starter: WeightedDropTable): WeightedDropTable {
        if (progress.chapter == "start" && progress.eligibleCoreBreaks < starterBreaks) return starter
        val base = tables.getValue(progress.chapter)
        if (progress.chapter != "developed" || progress.focus == "balanced") return base
        if (progress.focus == "nature") {
            return WeightedDropTable(base.entries + natureTable.entries)
        }
        return WeightedDropTable(base.entries.map { drop -> drop.copy(weight = drop.weight * if (
            progress.focus == "building" && drop.reward.name in BUILDING || progress.focus == "mineral" && ore(drop.block)) focusMultiplier else 1) })
    }
    fun validateMaterials() {
        tables.values.forEach { it.validateMaterials() }
        natureTable.validateMaterials()
        (recipes.values.flatMap { it.items } + packs.values.flatMap { it.items+it.advancedItems }+livingItems+tutorialRewards.flatMap { it.items }).forEach { item ->
            require(Material.valueOf(item.material).isItem && item.amount in 1..4096)
        }
        (recipes.values.flatMap { it.ingredients } + chapters.flatMap { it.ingredients }).forEach { ingredient ->
            require(ingredient.materials.all { Material.valueOf(it).isItem })
        }
    }
    companion object {
        val CHAPTERS = listOf("start","living","workshop","depths","developed")
        val SKILLS = listOf("mining","forestry","farming")
        val FOCUSES = setOf("balanced","building","mineral","nature")
        val LOGS = setOf("OAK_LOG","SPRUCE_LOG","BIRCH_LOG","JUNGLE_LOG","ACACIA_LOG","DARK_OAK_LOG","CHERRY_LOG","MANGROVE_LOG","PALE_OAK_LOG")
        fun treeLog(material: Material)=material.name.endsWith("_LOG")
        fun saplingFor(material: Material): Material? {
            val family=material.name.removePrefix("STRIPPED_").removeSuffix("_LOG")
            return Material.matchMaterial(if(family=="MANGROVE")"MANGROVE_PROPAGULE" else "${family}_SAPLING")
        }
        val BUILDING = LOGS + setOf("DIRT","COBBLESTONE","SAND","GRAVEL")
        val ACHIEVEMENTS = setOf("core_1k","core_10k","core_50k","core_250k","chapter_living","chapter_workshop","chapter_depths","chapter_developed",
            "fortune_max","territory_max","focus_unlocked","crafted_all_families") + SKILLS.flatMap { listOf("${it}_10","${it}_30") }
        fun ore(material: Material) = material.name.endsWith("_ORE") || material == Material.ANCIENT_DEBRIS
        fun read(file: File, optIn: Boolean): Pair<ReleaseRules?, String?> {
            if (!file.exists()) return null to null
            val source = file.readText(Charsets.UTF_8)
            com.alytanta.oneblock.util.StrictYaml.validate(source)
            val y = YamlConfiguration().apply { loadFromString(source) }
            require(y.getInt("version") == 1 && y.isBoolean("enabled"))
            require(y.getString("application-policy") in setOf("new-profiles-only","all-profiles"))
            fun number(section: ConfigurationSection, key: String, default: Long? = null): Long {
                val raw=section.get(key)
                val n = if(raw==null)default ?: error("Missing integer $key") else raw.toString().toLongOrNull() ?: error("Invalid integer $key")
                require(n in 0..1_000_000_000_000L) { "Invalid range $key" }; return n
            }
            fun items(s: ConfigurationSection): List<ReleaseItem> = s.getMapList("items").map { row ->
                val material = row["material"].toString(); require(Material.matchMaterial(material) != null)
                ReleaseItem(material,row["amount"].toString().toInt().also { require(it in 1..4096) },row["tool"]?.toString())
            }
            fun ingredients(s: ConfigurationSection): List<Ingredient> = s.getMapList("ingredients").map { row ->
                val materials = if (row["material"] == "logs") LOGS else setOf(row["material"].toString())
                require(materials.all { Material.matchMaterial(it) != null })
                Ingredient(materials,row["amount"].toString().toInt().also { require(it in 1..4096) })
            }
            val chapterSection = requireNotNull(y.getConfigurationSection("chapters"))
            require(chapterSection.getKeys(false).toList() == CHAPTERS)
            val chapters = CHAPTERS.map { id -> val s = chapterSection.getConfigurationSection(id)!!
                require(!s.contains("tool") || s.isBoolean("tool"))
                ChapterRule(id,number(s,"breaks"),number(s,"logs",0),number(s,"level",0).toInt(),s.getBoolean("tool",false),
                    number(s,"upgrades",0).toInt(),number(s,"coins",0),ingredients(s),ChapterMilestones(
                    number(s,"milestones.all-skills-level",0).also {require(it in 0L..30L)}.toInt(),number(s,"milestones.guild-orders",0).also {require(it<=100)},
                    s.getStringList("milestones.events").toSet().also {require(it.all {kind->kind in setOf("resonance","echo")})},number(s,"milestones.arena-wave",0).also {require(it<=100)},
                    s.getStringList("milestones.recipes-any").toSet().also {require(it.all {id->id.matches(Regex("[a-z][a-z0-9_]{0,47}"))})})) }
            require(chapters.first().breaks == 0L && chapters.zipWithNext().all { (a,b) -> a.breaks < b.breaks })
            require(chapters.first().let { it.logs==0L && it.level==0 && it.coins==0L && it.ingredients.isEmpty() && !it.tool && it.upgrades==0 })
            require(chapters.all { it.level in 0..10 && it.upgrades in 0..10 })
            val tables = CHAPTERS.associateWith { id ->
                val rows = y.getMapList("drop-tables.$id").map { row -> CoreDrop(Material.valueOf(row["block"].toString()),Material.valueOf(row["reward"].toString()),row["weight"].toString().toInt()) }
                WeightedDropTable(rows).also { require(it.totalWeight == 10000L) { "Table $id must total 10000" } }
            }
            val recipeSection = requireNotNull(y.getConfigurationSection("recipes"))
            val recipes = recipeSection.getKeys(false).associateWith { id -> val s = recipeSection.getConfigurationSection(id)!!
                require(id.matches(Regex("[a-z][a-z0-9_]*")))
                val chapter = s.getString("chapter")!!; require(chapter in CHAPTERS)
                val skill = s.getString("skill"); require(skill == null || skill in SKILLS)
                val level = number(s,"level",0).toInt(); require(level in 0..10)
                val durability = s.getDouble("durability",0.0); val sapling = s.getDouble("sapling",0.0)
                require(durability.isFinite() && durability in 0.0..0.5 && sapling.isFinite() && sapling in 0.0..0.25)
                RecipeRule(id,chapter,skill,level,number(s,"coins",0),ingredients(s),items(s),durability,sapling,s.getStringList("replant").toSet().also { require(it.all { crop -> crop in setOf("WHEAT","CARROTS","POTATOES") }) }) }
            require(recipes.keys == setOf("pickaxe_1","pickaxe_2","pickaxe_3","axe_1","axe_2","axe_3","hoe_1","hoe_2","hoe_3","water","lava","farm"))
            require(recipes.values.flatMap { it.items }.all { it.tool == null || it.tool in recipes })
            recipes.values.forEach { r ->
                require(r.items.isNotEmpty()) { "Recipe ${r.id} needs output" }
                val outputs=r.items.filter { it.tool!=null }
                require(outputs.all { it.tool==r.id && it.amount==1 }) { "Tool identity must match its recipe" }
                if(r.id.substringBefore('_') in setOf("pickaxe","axe","hoe"))require(outputs.size==1 && r.items.size==1)
            }
            val packSection = requireNotNull(y.getConfigurationSection("charge.packs"))
            val packs = packSection.getKeys(false).associateWith { id -> val s = packSection.getConfigurationSection(id)!!
                PackRule(id,s.getString("chapter")!!.also { require(it in CHAPTERS) },number(s,"points").also { require(it > 0) },items(s),s.getConfigurationSection("advanced")?.let(::items).orEmpty()) }
            require(packs.keys == setOf("building","nature","mineral","ancient"))
            require(packs.values.all { it.items.isNotEmpty() })
            fun costs(key: String) = y.getLongList(key).also { require(y.getList(key)?.all { value -> value.toString().toLongOrNull()!=null }==true && it.size == 5 && it.all { n -> n in 1..1_000_000_000L }) }
            val fortune = costs("upgrades.fortune"); val territory = costs("upgrades.territory")
            val widths = y.getIntegerList("upgrades.widths").also { require(y.getList("upgrades.widths")?.all { value -> value.toString().toIntOrNull()!=null }==true && it.size == 6 && it.zipWithNext().all { (a,b) -> a > 0 && b > a } && it.last() <= 100000) }
            val tutorials = y.getStringList("tutorial"); require(tutorials.size == 12 && tutorials.distinct().size == 12)
            val achievementList=y.getStringList("achievements")
            val achievements = achievementList.toSet(); require(achievements == ACHIEVEMENTS && achievementList.size==ACHIEVEMENTS.size)
            val livingItems=items(requireNotNull(y.getConfigurationSection("rewards.living")))
            val tutorialRewards=(0..11).map { index -> val s=requireNotNull(y.getConfigurationSection("rewards.tutorial.$index"));TutorialReward(items(s),number(s,"coins",0),number(s,"xp",0)) }
            require(tutorialRewards.withIndex().all { (index,reward) -> reward.xp==0L || index in setOf(5,10) })
            require(tutorialRewards[4].items.isEmpty()) { "Living kit must have only one award path" }
            val xpRates=listOf("stone","ore","log","crop").associateWith { number(y,"skills.xp.$it").also { require(it in 1..1000000) } }
            fun chance(path: String,max: Double): Double = y.getDouble(path,-1.0).also { require(it.isFinite() && it in 0.0..max) }
            val nature=WeightedDropTable(y.getMapList("focus.nature").map { row -> CoreDrop(Material.valueOf(row["block"].toString()),Material.valueOf(row["reward"].toString()),row["weight"].toString().toInt()) }).also { require(it.totalWeight==10000L) }
            val multiplier=number(y,"focus.multiplier").toInt().also { require(it in 1..10) }
            val rules = ReleaseRules(true,chapters,tables,recipes,packs,fortune,territory,widths,number(y,"upgrades.focus"),
                number(y,"skills.xp-scale").also { require(it > 0); Math.multiplyExact(it,27000) },number(y,"starter-breaks"),number(y,"charge.per-break").also { require(it > 0) },tutorials,achievements,
                livingItems,tutorialRewards,xpRates,chance("skills.bonus-per-five-levels",0.01),chance("upgrades.fortune-chance-per-level",0.04),chance("skills.core-bonus-cap",0.25),nature,multiplier)
            rules.validateDependencies()
            return rules to source
        }
    }
    /** Sources are available before each gate; smelting/crafting are explicit vanilla edges. */
    fun validateDependencies() {
        val vanilla = mapOf("IRON_INGOT" to "RAW_IRON","GOLD_INGOT" to "RAW_GOLD","BUCKET" to "RAW_IRON","WHEAT" to "WHEAT_SEEDS")
        fun sources(chapter: String): Set<String> = tables.getValue(chapter).entries.map { it.reward.name }.toSet() +
            if (atLeast(chapter,"living")) recipes.getValue("farm").items.map { it.material }.toSet() else emptySet()
        fun checkIngredients(input: List<Ingredient>, chapter: String) {
            val available = sources(chapter)
            require(input.all { ingredient -> ingredient.materials.any { material ->
                material in available || vanilla[material] in available &&
                    (material !in setOf("IRON_INGOT","GOLD_INGOT","BUCKET") || "COAL" in available || LOGS.any { it in available })
            } }) { "Unreachable ingredient at $chapter" }
        }
        chapters.drop(1).forEach { gate -> checkIngredients(gate.ingredients,CHAPTERS[index(gate.id)-1]) }
        chapters.drop(1).filter { it.tool }.forEach { gate ->
            require(recipes.values.any { it.id.endsWith("_1") && index(it.chapter)<index(gate.id) && it.level<=gate.level }) { "Cyclic tool gate at ${gate.id}" }
        }
        recipes.values.forEach { checkIngredients(it.ingredients,it.chapter) }
        require(recipes.getValue("farm").chapter == "living" && recipes.getValue("water").chapter == "living" && recipes.getValue("lava").chapter == "living")
        require(recipes.getValue("farm").items.map { it.material }.containsAll(listOf("WHEAT_SEEDS","CARROT","POTATO","SUGAR_CANE","OAK_SAPLING","COW_SPAWN_EGG","CHICKEN_SPAWN_EGG"))) { "Farm recovery sources are required" }
        require(recipes.getValue("water").items.any { it.material=="WATER_BUCKET" } && recipes.getValue("lava").items.any { it.material=="LAVA_BUCKET" })
        require(packs.getValue("mineral").advancedItems.any { it.material=="DIAMOND" } && index(packs.getValue("mineral").chapter)<=index("depths")) { "Guaranteed diamond recovery is required before Developed" }
        require(packs.getValue("ancient").items.any { it.material=="ANCIENT_DEBRIS" })
    }
    fun validateEconomy(market: com.alytanta.oneblock.market.MarketSettings) {
        if(!enabled)return
        chapters.drop(1).filter { it.coins>0 }.forEach { gate ->
            val sources=tables.getValue(CHAPTERS[index(gate.id)-1]).entries.map { it.reward.name }
            require(sources.any { market.products[it]?.let { p -> p.shop && p.sellPrice>0 }==true }) { "No renewable Merchant income before ${gate.id}" }
        }
    }
}
