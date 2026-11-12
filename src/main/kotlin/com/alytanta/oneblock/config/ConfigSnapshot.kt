package com.alytanta.oneblock.config

import com.alytanta.oneblock.market.MarketSettings
import org.bukkit.configuration.file.YamlConfiguration
import java.io.File
import java.util.logging.Logger

/** Strict, independent candidate. Never merges bundled products into an administrator's catalog. */
data class ConfigSnapshot(val yaml: YamlConfiguration, val plugin: PluginConfig, val market: MarketSettings, val sourceText: String,
    val auctionSourceText: String? = null,
    val harvest: com.alytanta.oneblock.harvest.HarvestSettings = com.alytanta.oneblock.harvest.HarvestSettings(),
    val harvestSourceText: String? = null, val menusSourceText: String? = null,
    val trades: com.alytanta.oneblock.trade.TradeSettings = com.alytanta.oneblock.trade.TradeSettings(), val tradesSourceText: String? = null,
    val sidebar: com.alytanta.oneblock.ui.SidebarSettings = com.alytanta.oneblock.ui.SidebarSettings(), val sidebarSourceText: String? = null,
    val mainMenus: com.alytanta.oneblock.menu.MenuConfiguration? = null,
    val profiles: com.alytanta.oneblock.player.ProfileSettings = com.alytanta.oneblock.player.ProfileSettings(), val profileSourceText: String? = null,
    val interfaces: com.alytanta.oneblock.market.MarketMenuLayouts = com.alytanta.oneblock.menu.InterfaceMenus.defaults(), val interfaceSourceText: String? = null,
    val release: com.alytanta.oneblock.release.ReleaseRules? = null, val releaseSourceText: String? = null,
    val guides: com.alytanta.oneblock.guide.GuideSettings? = null, val guideSourceText: String? = null,
    val hubGenerator:com.alytanta.oneblock.hub.generator.HubGeneratorSettings?=null,val hubGeneratorSourceText:String?=null,
    val combatSourceText:String?=null,
    val mine:com.alytanta.oneblock.dungeon.MineSettings?=null,val mineSourceText:String?=null,
    val minePending:List<com.alytanta.oneblock.dungeon.MineRun> = emptyList(),
    val customItems:com.alytanta.oneblock.items.ItemCatalog?=null,val itemSourceText:String?=null,
    val resourcePack:com.alytanta.oneblock.items.ResourcePackSettings?=null,val resourcePackSourceText:String?=null,val activitySourceText:String?=null) {
    fun inputsUnchanged(file: File): Boolean = file.readText(Charsets.UTF_8) == sourceText &&
        file.parentFile.resolve("auction.yml").let { if (it.exists()) it.readText(Charsets.UTF_8) else null } == auctionSourceText &&
        file.parentFile.resolve("harvest.yml").let { if (it.exists()) it.readText(Charsets.UTF_8) else null } == harvestSourceText &&
        file.parentFile.resolve("market-menus.yml").let { if (it.exists()) it.readText(Charsets.UTF_8) else null } == menusSourceText &&
        file.parentFile.resolve("trades.yml").let { if (it.exists()) it.readText(Charsets.UTF_8) else null } == tradesSourceText &&
        file.parentFile.resolve("sidebar.yml").let { if (it.exists()) it.readText(Charsets.UTF_8) else null } == sidebarSourceText &&
        file.parentFile.resolve("profiles.yml").let { if (it.exists()) it.readText(Charsets.UTF_8) else null } == profileSourceText &&
        file.parentFile.resolve("interface-menus.yml").let { if (it.exists()) it.readText(Charsets.UTF_8) else null } == interfaceSourceText &&
        file.parentFile.resolve("progression.yml").let { if(it.exists())it.readText(Charsets.UTF_8) else null } == releaseSourceText &&
        file.parentFile.resolve("guides.yml").let { if(it.exists())it.readText(Charsets.UTF_8) else null } == guideSourceText &&
        file.parentFile.resolve("hub-generator.yml").let {if(it.exists())it.readText(Charsets.UTF_8) else null}==hubGeneratorSourceText &&
        file.parentFile.resolve("combat.yml").let {if(it.exists())it.readText(Charsets.UTF_8) else null}==combatSourceText &&
        file.parentFile.resolve("dungeon.yml").let {if(it.exists())it.readText(Charsets.UTF_8) else null}==mineSourceText &&
        file.parentFile.resolve("items.yml").let {if(it.exists())it.readText(Charsets.UTF_8) else null}==itemSourceText &&
        file.parentFile.resolve("resource-pack.yml").let {if(it.exists())it.readText(Charsets.UTF_8) else null}==resourcePackSourceText && file.parentFile.resolve("activities.yml").let {if(it.exists())it.readText(Charsets.UTF_8) else null}==activitySourceText && mainMenus?.inputsUnchanged() != false
    fun validateMaterials() {
        plugin.drops.starter.validateMaterials()
        plugin.drops.main.validateMaterials()
        market.validateMaterials()
        mainMenus?.validateMaterials()
        interfaces.validateMaterials()
        release?.validateMaterials()
        release?.activities?.validateMaterials()
        release?.combat?.validateMaterials()
        release?.validateEconomy(market)
        guides?.topics?.forEach { require(it.icon.isItem) }
        require(harvest.plants.all { it.isBlock })
        listOfNotNull(profiles.filler,profiles.close,profiles.coopButton,profiles.back).forEach { require(it.material.isItem && it.amount <= it.material.maxStackSize) }
        profiles.slots.forEach { slot -> listOf(slot.empty,slot.occupied,slot.active,slot.locked).forEach { require(it.material.isItem && it.amount <= it.material.maxStackSize) } }
        require(trades.filler.isItem && trades.divider.isItem)
        require(trades.icons.values.all { it.isItem })
        require(trades.styles.values.mapNotNull { it.icon }.all { it.isItem })
        customItems?.items?.values?.forEach {require(org.bukkit.Material.valueOf(it.material).isItem) {"${it.id}: base material must be an item"}}
        com.alytanta.oneblock.market.MarketMenuLayouts(windows=mapOf("trade" to com.alytanta.oneblock.market.MarketWindow(templates=trades.styles))).validateMaterials()
        trades.blocked.forEach { require(org.bukkit.Material.matchMaterial(it)?.isItem == true) { "Unknown blocked trade item: $it" } }
    }

    companion object {
        fun read(file: File, logger: Logger): ConfigSnapshot {
            val source = file.readText(Charsets.UTF_8)
            com.alytanta.oneblock.util.StrictYaml.validate(source)
            val yaml = YamlConfiguration().apply { loadFromString(source) } // Never replace invalid YAML with defaults.
            listOf("language", "hub", "islands", "core", "core.drops", "core.bonus-drops", "market", "market.npcs", "market.sign", "market.auction", "market.merchant", "market.appearance", "storage", "economy", "progression").forEach {
                require(!yaml.contains(it) || yaml.isConfigurationSection(it)) { "$it must be a mapping" }
            }
            listOf("hub.use-world-spawn", "islands.visible-border", "core.reward-messages", "core.bonus-drops.enabled", "market.enabled").forEach {
                require(!yaml.contains(it) || yaml.isBoolean(it)) { "$it must be true or false" }
            }
            val (marketConfig, auctionSource) = AuctionConfig.overlay(yaml, file.parentFile.resolve("auction.yml"))
            val harvestFile = file.parentFile.resolve("harvest.yml")
            val (harvest, harvestSource) = com.alytanta.oneblock.harvest.HarvestSettings.read(harvestFile)
            val (menus, menuSource) = com.alytanta.oneblock.market.MarketMenuLayouts.read(file.parentFile.resolve("market-menus.yml"))
            val (trades, tradeSource) = com.alytanta.oneblock.trade.TradeSettings.read(file.parentFile.resolve("trades.yml"))
            val (sidebar, sidebarSource) = com.alytanta.oneblock.ui.SidebarSettings.read(file.parentFile.resolve("sidebar.yml"))
            val mainMenus = com.alytanta.oneblock.menu.MenuConfiguration.read(com.alytanta.oneblock.menu.MenuConfiguration.root(file.parentFile))
            val (profiles, profileSource) = com.alytanta.oneblock.player.ProfileSettings.read(file.parentFile.resolve("profiles.yml"))
            val (interfaces, interfaceSource) = com.alytanta.oneblock.menu.InterfaceMenus.read(file.parentFile.resolve("interface-menus.yml"))
            require(!yaml.contains("release.enabled") || yaml.isBoolean("release.enabled"))
            val (baseRelease, releaseSource) = com.alytanta.oneblock.release.ReleaseRules.read(file.parentFile.resolve("progression.yml"),yaml.getBoolean("release.enabled",false))
            val (itemCatalog,itemSource)=com.alytanta.oneblock.items.ItemCatalog.read(file.parentFile.resolve("items.yml"))
            val (pack,packSource)=com.alytanta.oneblock.items.ResourcePackSettings.read(file.parentFile.resolve("resource-pack.yml"))
            val external=baseRelease?.recipes?.values.orEmpty().flatMap {it.items}.mapNotNull {it.tool}.toSet()
            val (combat,combatSource)=com.alytanta.oneblock.combat.CombatRules.read(file.parentFile.resolve("combat.yml"),itemCatalog,external)
            if(itemCatalog!=null && baseRelease!=null)baseRelease.recipes.values.flatMap {it.items}.filter {it.tool!=null}.forEach {output->
                require(itemCatalog.require(output.tool!!).material==output.material) {"${output.tool}: catalog material differs from progression recipe"}
            }
            val (mine,mineSource)=com.alytanta.oneblock.dungeon.MineSettings.read(file.parentFile.resolve("dungeon.yml"))
            if(mine?.npc?.enabled==true) {
                require(mainMenus.routes["dungeons"]?.let {it.type=="dungeon" && it.target=="select"}==true && "dungeon-select" in mainMenus.menus) {"NPC requires dungeons/select route and dungeon-select layout in plugins/menus/dungeon"}
                require(mine.npc.name !in setOf(marketConfig.getString("market.npcs.shop"),marketConfig.getString("market.npcs.bazaar"),marketConfig.getString("market.auction.npc"))) {"Dungeon NPC name collides with a trading NPC"}
            }
            if(mine?.enabled==true) {require(combat?.enabled==true);mine.validateCatalog(combat)}
            val minePending=com.alytanta.oneblock.dungeon.MineJournal(file.parentFile.toPath().resolve("mine-sessions")).load()
            require(combat==null || baseRelease==null || (combat.recipes.keys intersect baseRelease.recipes.keys).isEmpty()) { "Combat recipe ID collides with progression" }
            val activitySource=file.parentFile.resolve("activities.yml").takeIf {it.exists()}?.readText(Charsets.UTF_8)
            val activityRules=com.alytanta.oneblock.activities.ActivityRules.read(file.parentFile.resolve("activities.yml"))
            val release=baseRelease?.let { r ->
                activityRules.validate(itemCatalog,r)
                require((activityRules.recipes.keys intersect combat?.recipes?.keys.orEmpty()).isEmpty()) {"Guild recipe ID collides with combat"}
                val recipes=r.recipes.filterValues {recipe->recipe.items.none {it.tool?.let {id->itemCatalog?.find(id)?.kind==com.alytanta.oneblock.items.ItemKind.ARMOR}==true}}.mapValues {(id,recipe)->itemCatalog?.find(id)?.takeIf {it.kind==com.alytanta.oneblock.items.ItemKind.TOOL}?.let {d->
                    recipe.copy(durability=d.effects("tool_traits").sumOf {it.number("durability")},sapling=d.effects("tool_traits").sumOf {it.number("sapling")})
                }?:recipe}
                if(combat?.enabled==true)r.copy(recipes=recipes+combat.recipes+activityRules.recipes,combat=combat,customItems=itemCatalog,activities=activityRules) else r.copy(recipes=recipes+activityRules.recipes,customItems=itemCatalog,activities=activityRules)
            }
            if(baseRelease!=null && combat!=null)mainMenus.routes.values.filter { it.type=="release" && it.target?.startsWith("recipe ")==true }.forEach {
                val id=it.target!!.substringAfter(' ')
                require(id in baseRelease.recipes || id in combat.recipes || id in activityRules.recipes || combat.items[id]?.kind==com.alytanta.oneblock.combat.CombatKind.ARMOR) { "${it.id}: unknown recipe $id" }
            }
            require(!yaml.getBoolean("release.enabled",false) || release!=null) { "Enabled release requires progression.yml" }
            val (guides, guideSource)=com.alytanta.oneblock.guide.GuideSettings.read(file.parentFile.resolve("guides.yml"))
            val (hubGenerator,hubGeneratorSource)=com.alytanta.oneblock.hub.generator.HubGeneratorSettings.read(file.parentFile.resolve("hub-generator.yml"))
            val npcNames=listOf(marketConfig.getString("market.npcs.shop"),marketConfig.getString("market.npcs.bazaar"),marketConfig.getString("market.auction.npc"),
                hubGenerator?.guideNpc,mine?.npc?.name,activityRules.npc).filterNotNull()
            require(npcNames.all {it.matches(Regex("[A-Za-z0-9_/-]{1,48}"))}) {"NPC names must use FancyNpcs-safe characters and be at most 48 characters"}
            require(npcNames.distinct().size==npcNames.size) {"Configured NPC role names must be unique across shop, bazaar, auction, guide and dungeon"}
            if(itemCatalog!=null)mainMenus.menus.values.flatMap {it.slots.values}.flatMap {listOf(it)+it.states.values}.mapNotNull {it.item}.filter {it.customItem!=null}.forEach {spec->
                val definition=itemCatalog.require(spec.customItem!!)
                require(spec.amount<=definition.stackSize) {"GUI ${definition.id}: amount ${spec.amount} exceeds configured stack-size ${definition.stackSize}"}
            }
            return ConfigSnapshot(yaml, PluginConfig.load(yaml, logger), MarketSettings.load(marketConfig).copy(menus = menus), source, auctionSource, harvest, harvestSource, menuSource, trades, tradeSource, sidebar, sidebarSource, mainMenus, profiles, profileSource, interfaces, interfaceSource,release,releaseSource,guides,guideSource,hubGenerator,hubGeneratorSource,combatSource,mine,mineSource,minePending,itemCatalog,itemSource,pack,packSource,activitySource)
        }
    }
}
