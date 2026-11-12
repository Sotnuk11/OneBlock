package com.alytanta.oneblock.market

import org.bukkit.Material
import org.bukkit.configuration.ConfigurationSection
import org.bukkit.configuration.file.YamlConfiguration
import java.io.File

data class MarketMenuButton(val slot: Int? = null, val enabled: Boolean = true, val icon: Material? = null,
    val name: String? = null, val lore: List<String>? = null,
    val amount: Int? = null, val glow: Boolean? = null, val customModelData: Int? = null,
    val texture:String?=null,val model:String?=null,val states:Map<String,MarketMenuButton> = emptyMap()) {
    fun state(id:String):MarketMenuButton = states[id]?.let { s -> copy(enabled=s.enabled,icon=s.icon?:icon,name=s.name?:name,lore=s.lore?:lore,
        amount=s.amount?:amount,glow=s.glow?:glow,customModelData=s.customModelData?:customModelData,texture=s.texture?:texture,model=s.model?:model,states=emptyMap()) } ?: this
}

data class MarketWindow(val id: String = "default", val rows: Int = 6, val title: String? = null,
    val filler: Material? = null, val inheritFiller: Boolean = true,
    val buttons: Map<Int, MarketMenuButton> = emptyMap(), val grids: Map<String, List<Int>> = emptyMap(),
    val templates:Map<String,MarketMenuButton> = emptyMap(),val background:MarketMenuButton?=null,
    val decorations:Map<Int,com.alytanta.oneblock.menu.MenuItemSpec> = emptyMap()) {
    fun identity(source:Int)=com.alytanta.oneblock.menu.GuiContracts.identity(id,source)
    fun style(source:Int,state:String="available"):MarketMenuButton? = (buttons[source] ?: templates["content"])?.state(state)
    fun target(source: Int): Int? = buttons[source].let { button ->
        if(source !in 0..53 && button==null)return null
        if (button?.enabled == false) null else (button?.slot ?: source).also {
            require(it in 0 until rows * 9) { "$id: slot $it outside $rows rows; move or disable source button $source" }
        }
    }
    fun grid(name: String, fallback: List<Int>): List<Int> = (grids[name] ?: fallback).filter { buttons[it]?.enabled != false }.also {
        require(it.isNotEmpty()) { "$id.grids.$name must have a visible slot" }
        val targets = it.map { slot -> target(slot) }
        require(targets.distinct().size == targets.size) { "$id.grids.$name has overlapping positions" }
    }
}

data class MarketMenuLayouts(val defaults: MarketWindow = MarketWindow(), val windows: Map<String, MarketWindow> = emptyMap(),
    val contexts: Map<String, Map<String, MarketWindow>> = emptyMap()) {
    fun window(id: String, context: String?): MarketWindow = context?.let { contexts[id]?.get(it) } ?: windows[id] ?: com.alytanta.oneblock.menu.GuiContracts.template(id, defaults)
    fun validateMaterials() {
        (listOf(defaults) + windows.values + contexts.values.flatMap { it.values }).forEach { window ->
            val styles=(window.buttons.values+window.templates.values+listOfNotNull(window.background)).flatMap {listOf(it)+it.states.values}
            (listOfNotNull(window.filler) + styles.mapNotNull { it.icon }).forEach { require(it.isItem) }
            styles.forEach { s -> s.icon?.let { require(s.amount==null || s.amount<=it.maxStackSize) };require(s.texture==null || s.icon==null || s.icon==Material.PLAYER_HEAD) }
            window.decorations.values.forEach {require(it.material.isItem && it.amount<=it.material.maxStackSize)}
        }
    }
    companion object {
        fun read(file: File, catalog: Map<String, MarketWindow> = emptyMap()): Pair<MarketMenuLayouts, String?> {
            if (!file.exists()) return MarketMenuLayouts(windows = catalog) to null
            val source = file.readText(Charsets.UTF_8)
            com.alytanta.oneblock.util.StrictYaml.validate(source)
            val config = YamlConfiguration().apply { loadFromString(source) }
            listOf("defaults", "windows").forEach { require(!config.contains(it) || config.isConfigurationSection(it)) { "$it must be a mapping" } }
            require(!config.contains("auction-layout-version") || config.isInt("auction-layout-version") && config.getInt("auction-layout-version") in 2..3) {
                "market-menus.yml: supported auction-layout-version is 2 or 3"
            }
            fun load(id: String, section: ConfigurationSection?, base: MarketWindow): MarketWindow {
                if (section == null) return base.copy(id = id)
                listOf("buttons", "grids", "contexts","templates","background","decorations").forEach { require(!section.contains(it) || section.isConfigurationSection(it)) { "$id.$it must be a mapping" } }
                fun integer(part: ConfigurationSection, key: String, fallback: Int): Int = if (part.contains(key)) {
                    require(part.isInt(key)) {"$id.$key must be an integer"};part.getInt(key)
                } else fallback
                val rows = integer(section, "rows", base.rows).also { require(it in 1..6) { "$id.rows: use 1..6" } }
                fun material(value: String): Material = requireNotNull(Material.matchMaterial(value)).also {
                    require(it !in setOf(Material.AIR, Material.CAVE_AIR, Material.VOID_AIR)) { "$id: invalid icon $value" }
                }
                val buttons = base.buttons.toMutableMap()
                val aliases=mutableSetOf<Int>()
                section.getConfigurationSection("buttons")?.getKeys(false)?.forEach { key ->
                    val sourceSlot = key.toIntOrNull()?.also {require(it in 0..53)} ?: com.alytanta.oneblock.menu.GuiContracts.source(id,key)
                    require(aliases.add(sourceSlot)) { "$id: duplicate action alias $key" }
                    val part = requireNotNull(section.getConfigurationSection("buttons.$key"))
                    val old = buttons[sourceSlot] ?: MarketMenuButton()
                    require(!part.contains("enabled") || part.isBoolean("enabled"))
                    buttons[sourceSlot] = readStyle(part, old).copy(slot =
                        if (part.contains("slot")) integer(part, "slot", sourceSlot).also { require(it in 0 until rows * 9) } else old.slot,
                    )
                }
                val grids = base.grids.toMutableMap()
                section.getConfigurationSection("grids")?.getKeys(false)?.forEach { name ->
                    require(name in setOf("content","categories","separators","quantities","buys","sells")) { "$id: unknown grid $name" }
                    require(section.isList("grids.$name"))
                    val values = requireNotNull(section.getList("grids.$name"))
                    grids[name] = values.map { requireNotNull(it.toString().toIntOrNull()) }.also { grid ->
                        require((grid.isNotEmpty() || name == "separators") && grid.distinct().size == grid.size && grid.all { it in 0..53 }) { "$id.grids.$name: invalid slots" }
                    }
                }
                val fill = section.getString("filler")
                require(!section.contains("title") || section.isString("title")) { "$id.title must be text" }
                require(section.getString("title")?.none { it == '\n' || it == '\r' } != false) { "$id.title must be one line" }
                val templates=base.templates.toMutableMap()
                section.getConfigurationSection("templates")?.getKeys(false)?.forEach { key ->
                    require(key in setOf("content","categories","preview","empty","quantities","separators","buys","sells")) { "$id: unknown template $key" }
                    val part=requireNotNull(section.getConfigurationSection("templates.$key"));require(key=="empty" || !part.contains("slot"))
                    templates[key]=readStyle(part,templates[key]?:MarketMenuButton()).copy(slot=if(part.contains("slot"))integer(part,"slot",22).also {require(it in 0 until rows*9)} else templates[key]?.slot)
                }
                val decorations=base.decorations.toMutableMap()
                section.getConfigurationSection("decorations")?.getKeys(false)?.forEach { key ->
                    val pos=requireNotNull(key.toIntOrNull());require(pos in 0 until rows*9)
                    decorations[pos]=com.alytanta.oneblock.menu.MenuConfiguration.item(requireNotNull(section.getConfigurationSection("decorations.$key")))
                }
                val background=section.getConfigurationSection("background")?.let { readStyle(it,base.background?:MarketMenuButton()) }?:base.background
                return MarketWindow(id, rows, section.getString("title") ?: base.title,
                    when { fill == null || fill.equals("INHERIT", true) -> base.filler; fill.equals("NONE", true) -> null; else -> material(fill) },
                    if (fill == null || fill.equals("INHERIT", true)) base.inheritFiller else false, buttons, grids,templates,background,decorations).also { window ->
                        window.buttons.filterValues { it.enabled }.keys.forEach(window::target)
                        window.grids.filterKeys { it != "separators" }.forEach { (name, grid) -> window.grid(name, grid) }
                        val sources = (window.buttons.keys + window.grids.values.flatten()).distinct()
                        val targets = sources.mapNotNull(window::target)
                        require(targets.distinct().size == targets.size) { "$id: two controls/content slots share the same target; move or disable the other source slot" }
                    }
            }
            val defaults = load("default", config.getConfigurationSection("defaults"), MarketWindow())
            fun baseFor(id: String): MarketWindow = catalog[id]?.let { template -> template.copy(
                filler = defaults.filler, inheritFiller = defaults.inheritFiller,
                buttons = template.buttons + defaults.buttons, grids = template.grids + defaults.grids)
            } ?: com.alytanta.oneblock.menu.GuiContracts.template(id, defaults)
            val known=if(catalog.isEmpty())com.alytanta.oneblock.menu.GuiContracts.all.keys.filterNot {it.startsWith("profile-") || it.startsWith("admin-")}.toSet() else catalog.keys
            val windows = known.associateWithTo(linkedMapOf(), ::baseFor)
            val contexts = linkedMapOf<String, Map<String, MarketWindow>>()
            config.getConfigurationSection("windows")?.getKeys(false)?.forEach { id ->
                require(id.matches(Regex("[a-z0-9-]+")))
                require(id in known) { "Unknown menu: $id" }
                val part = requireNotNull(config.getConfigurationSection("windows.$id"))
                val legacy = !config.contains("auction-layout-version") && id.startsWith("auction-")
                val upgrade = legacy && AuctionLayouts.legacyDefault(id, part) ||
                    id == "auction-claims" && config.getInt("auction-layout-version", 0) < 3 && AuctionLayouts.previousClaims(part)
                val base = if (legacy && !upgrade) defaults.copy(id = id) else baseFor(id)
                val window = if (upgrade) base else load(id, part, base)
                windows[id] = window
                contexts[id] = part.getConfigurationSection("contexts")?.getKeys(false)?.associateWith { context ->
                    load("$id/$context", requireNotNull(part.getConfigurationSection("contexts.$context")), window)
                }.orEmpty()
            }
            (windows.values + contexts.values.flatMap { it.values }).forEach { window ->
                val sources = (window.buttons.keys + window.grids.values.flatten()).distinct()
                val targets = sources.mapNotNull(window::target)
                require(targets.distinct().size == targets.size) { "${window.id}: overlapping controls/content" }
                window.templates["empty"]?.slot?.let { pos->require(window.buttons.keys.mapNotNull(window::target).none {it==pos}) {"${window.id}: empty state overlaps a control"} }
                window.grids.filterKeys { it != "separators" }.forEach { (name, grid) -> window.grid(name, grid) }
            }
            return MarketMenuLayouts(defaults, windows, contexts) to source
        }
        fun readStyle(part: ConfigurationSection, old: MarketMenuButton = MarketMenuButton(), nested:Boolean=false): MarketMenuButton {
            require(part.getKeys(false).all {it in setOf("slot","enabled","icon","name","lore","amount","glow","custom-model-data","texture","item-model","states")}) {"${part.currentPath}: unknown style field"}
            require(!part.contains("states") || part.isConfigurationSection("states")) {"${part.currentPath}.states must be a mapping"}
            require(!part.contains("enabled") || part.isBoolean("enabled")) { "${part.currentPath}.enabled must be boolean" }
            require(!part.contains("glow") || part.isBoolean("glow")) { "${part.currentPath}.glow must be boolean" }
            require(!part.contains("name") || part.isString("name")) { "${part.currentPath}.name must be text" }
            require(!part.contains("lore") || part.isList("lore") && part.getList("lore")!!.all { it is String }) { "${part.currentPath}.lore must be text lines" }
            fun number(key: String, fallback: Int?, range: IntRange): Int? = if (!part.contains(key)) fallback else {
                require(part.isInt(key)) {"${part.currentPath}.$key must be integer"};part.getInt(key).also {require(it in range)}
            }
            val icon = if (!part.contains("icon")) old.icon else requireNotNull(Material.matchMaterial(requireNotNull(part.getString("icon")))) {
                "${part.currentPath}.icon is unknown"
            }.also { require(it !in setOf(Material.AIR, Material.CAVE_AIR, Material.VOID_AIR)) }
            val texture=if(part.contains("texture"))requireNotNull(part.get("texture") as? String).also { com.alytanta.oneblock.menu.MenuConfiguration.skinUrl(it);require(icon==null || icon==Material.PLAYER_HEAD) } else old.texture
            val model=if(part.contains("item-model"))requireNotNull(part.get("item-model") as? String).also {require(org.bukkit.NamespacedKey.fromString(it)!=null)} else old.model
            require(!nested || !part.contains("states")) { "Nested states are unsupported" }
            val states=old.states.toMutableMap()
            part.getConfigurationSection("states")?.getKeys(false)?.forEach { state ->
                require(state.matches(Regex("[a-z0-9_.-]+")))
                val s=requireNotNull(part.getConfigurationSection("states.$state"));require(!s.contains("slot"))
                states[state]=readStyle(s,old.copy(enabled=part.getBoolean("enabled",old.enabled)),true)
            }
            return old.copy(enabled = part.getBoolean("enabled", old.enabled), icon = icon,
                name = part.getString("name") ?: old.name, lore = if (part.contains("lore")) part.getStringList("lore") else old.lore,
                amount = number("amount", old.amount, 1..99), glow = if (part.contains("glow")) part.getBoolean("glow") else old.glow,
                customModelData = number("custom-model-data", old.customModelData, 0..Int.MAX_VALUE),texture=texture,model=model,states=states)
        }
    }
}
