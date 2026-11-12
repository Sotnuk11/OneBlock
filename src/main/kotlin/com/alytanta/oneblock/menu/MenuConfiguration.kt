package com.alytanta.oneblock.menu

import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.configuration.ConfigurationSection
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.plugin.Plugin
import java.io.File
import java.nio.file.Files
import java.util.Base64
import com.google.gson.JsonParser

data class MenuItemSpec(val material: Material, val name: String, val lore: List<String>, val amount: Int = 1,
    val texture: String? = null, val model: String? = null, val customModel: Int? = null, val glow: Boolean = false,val customItem:String?=null)
data class MenuCell(val item: MenuItemSpec?, val statistics: String?, val command: String?,
    val states: Map<String, MenuCell> = emptyMap(), val initialState: String? = null, val stateSource: String? = null, val nextState: String? = null,val enabled:Boolean=true)
data class StaticMenu(val id: String, val title: String, val filler: MenuItemSpec?, val slots: Map<Int, MenuCell>, val rows: Int = 6, val content: List<Int> = emptyList(),
    val entry:com.alytanta.oneblock.market.MarketMenuButton?=null,val empty:com.alytanta.oneblock.market.MarketMenuButton?=null,val emptySlot:Int?=null)
data class MenuRoute(val id: String, val aliases: List<String>, val type: String, val target: String?,
    val requiresOpen: Set<String>, val permission: String?, val directPermission: String?, val requireProfile: Boolean)
data class MenuStatValue(val source: String, val format: String, val fallback: String)
data class MenuStatisticsConfig(val values: Map<String, MenuStatValue>, val displays: Map<String, MenuItemSpec>)

/** A complete immutable candidate, including the original texts for consistent reload publication. */
data class MenuConfiguration(val root: File, val sources: Map<String, String>, val menus: Map<String, StaticMenu>,
    val routes: Map<String, MenuRoute>, val statistics: MenuStatisticsConfig, val launcher: MenuItemSpec,
    val messages: Map<String, String>) {
    val aliases: Map<String, String> = routes.values.flatMap { route -> route.aliases.map { it to route.id } }.toMap()
    fun validateMaterials() {
        fun checkItem(spec: MenuItemSpec) { require(spec.material.isItem && spec.amount <= spec.material.maxStackSize) { "Invalid menu item ${spec.material}" } }
        checkItem(launcher)
        statistics.displays.values.forEach(::checkItem)
        menus.values.forEach { menu ->
            menu.filler?.let(::checkItem)
            menu.slots.values.flatMap { listOf(it) + it.states.values }.mapNotNull { it.item }.forEach(::checkItem)
            com.alytanta.oneblock.market.MarketMenuLayouts(windows=mapOf(menu.id to com.alytanta.oneblock.market.MarketWindow(templates=listOfNotNull(menu.entry,menu.empty).mapIndexed {i,s->i.toString() to s}.toMap()))).validateMaterials()
        }
    }
    fun inputsUnchanged() = texts(root) == sources
    fun routeFor(command: String): MenuRoute? {
        val parts = command.removePrefix("/").trim().split(Regex("\\s+"))
        val id = if (parts.size == 2 && parts[0].equals("obmenu", true)) parts[1]
            else if (parts.size == 1) aliases[parts[0]] else null
        return id?.let(routes::get)
    }

    companion object {
        fun root(dataFolder: File) = dataFolder.parentFile.resolve("menus")
        fun ensure(plugin: Plugin) {
            com.alytanta.oneblock.config.DefaultConfigs.ensureMenus(plugin.dataFolder,plugin::getResource)
        }
        /** Explicit migration with backups; startup and reload never rewrite existing custom files. */
        fun migrateLegacy(plugin: Plugin, folder: File = root(plugin.dataFolder).resolve("main-menu")) {
            val marker = folder.resolve(".harvest-profiles-v16")
            if (marker.exists()) return
            for (name in listOf("commands.yml","layout.yml","statistics.yml")) {
                val file = folder.resolve(name)
                if (!file.exists()) continue
                val original = file.readText(Charsets.UTF_8)
                val y = YamlConfiguration().apply { loadFromString(original) }
                val bundled = plugin.getResource("menus/main-menu/$name")!!.reader(Charsets.UTF_8).use { reader ->
                    YamlConfiguration().apply { loadFromString(reader.readText()) }
                }
                when(name) {
                    "commands.yml" -> {
                        y.set("commands.collections",null)
                        for (id in listOf("profiles","profiles-create","profiles-switch","profiles-rename","inventory-notifications","no-island","visitors-allow","visitors-deny")) {
                            if (!y.contains("commands.$id") || id == "profiles" && y.getString("commands.profiles.execute") == "ob profiles")
                                y.set("commands.$id",bundled.get("commands.$id"))
                        }
                    }
                    "layout.yml" -> {
                        y.set("menus.collections",null)
                        y.getConfigurationSection("menus")?.getKeys(false)?.forEach { id ->
                            val slots = y.getConfigurationSection("menus.$id.slots")
                            slots?.getKeys(false)?.toList()?.forEach { slot ->
                                if (slots.getString("$slot.command") in setOf("/obmenu collections","/collections","/collection")) slots.set(slot,null)
                            }
                        }
                        if (!y.contains("menus.no-island")) y.set("menus.no-island",bundled.get("menus.no-island"))
                        val slots = y.getConfigurationSection("menus.settings.slots")
                        val free = listOf(31,30,32,39,40,41).firstOrNull { slots != null && !slots.contains(it.toString()) }
                        if(free != null) slots!!.set(free.toString(),bundled.get("menus.settings.slots.31"))
                    }
                    "statistics.yml" -> {
                        y.getConfigurationSection("values")?.let { values -> values.getKeys(false).toList().forEach { id ->
                            if(values.getString("$id.source").orEmpty().startsWith("collections.")) values.set(id,null)
                        } }
                    }
                }
                if (y.saveToString() != original) {
                    val backup = folder.resolve("$name.before-v16")
                    if(!backup.exists()) file.copyTo(backup)
                    y.save(file)
                }
            }
            marker.writeText("Migrated collection UI and profile routes")
        }
        private fun texts(root: File): Map<String, String> {
            require(root.isDirectory) { "Menu folder is missing: $root" }
            return root.walkTopDown().onEnter { directory ->
                require(!Files.isSymbolicLink(directory.toPath())) { "Menu directory cannot be a symlink: $directory" }; true
            }.filter { it.isFile && it.name in setOf("commands.yml", "layout.yml", "statistics.yml") }.sortedBy { it.path }
                .associate { file ->
                    require(!Files.isSymbolicLink(file.toPath())) { "Menu file cannot be a symlink: $file" }
                    file.relativeTo(root).invariantSeparatorsPath to file.readText(Charsets.UTF_8)
                }
        }
        fun read(root: File): MenuConfiguration {
            val sources = texts(root)
            listOf("commands.yml", "layout.yml", "statistics.yml").forEach {
                require("main-menu/$it" in sources) { "Missing menus/main-menu/$it" }
            }
            val menus = linkedMapOf<String, StaticMenu>()
            val routes = linkedMapOf<String, MenuRoute>()
            val values = linkedMapOf<String, MenuStatValue>()
            val displays = linkedMapOf<String, MenuItemSpec>()
            var launcher: MenuItemSpec? = null
            var messages: Map<String, String> = emptyMap()
            sources.forEach { (file, text) ->
                try {
                    com.alytanta.oneblock.util.StrictYaml.validate(text)
                    val y = YamlConfiguration().apply { loadFromString(text) }
                    require(y.isInt("version") && y.getInt("version") == 1) { "version must be 1" }
                    when (file.substringAfterLast('/')) {
                        "layout.yml" -> {
                            if (file == "main-menu/layout.yml") launcher = item(section(y, "launcher")).also { require(it.amount==1) { "launcher.amount must be 1" } }
                            val definitions = section(y, "menus")
                            definitions.getKeys(false).forEach { id ->
                                require(menus.put(id, menu(id,section(definitions,id))) == null) { "Duplicate menu $id" }
                            }
                        }
                        "commands.yml" -> {
                            if (file == "main-menu/commands.yml") {
                                val m = section(y, "messages")
                                messages = listOf("denied", "permission", "profile", "busy", "unknown", "player-only").associateWith { string(m, it) }
                            }
                            val definitions = section(y, "commands")
                            definitions.getKeys(false).forEach { id ->
                                validId(id)
                                val data = section(definitions, id)
                                val type = string(data, "type").also { require(it in setOf("open", "player-command", "close", "profiles", "profile-action", "inventory-notifications", "release", "guide","dungeon","input")) }
                                val aliases = strings(data, "aliases").onEach { require(it.matches(Regex("[a-z][a-z0-9_-]{0,31}")) && it != "obmenu") { "Invalid alias: $it" } }
                                val configuredParents = strings(data, "requires-open").toSet()
                                val parents = if(boolean(data,"require-open-menu",true)) configuredParents else emptySet()
                                val permission = optional(data, "permission")?.also(::validPermission)
                                val bypass = optional(data, "direct-permission")?.also(::validPermission)
                                val target = if (type in setOf("close", "profiles", "inventory-notifications")) null else string(data, if (type == "open") "menu" else "execute")
                                if (type == "player-command") require(target != null && target==target.trim() && !target.startsWith('/') && target.none { it=='\n' || it=='\r' || it=='\t' }) { "execute must be a command without / or control characters" }
                                if(type=="input")require(target!=null && "{input}" in target && !target.startsWith('/')) {"$id: execute must contain {input}"}
                                if(type=="profile-action")require(target in setOf("create","switch","rename")) {"$id: unknown profile action"}
                                if(type=="guide")require(target in com.alytanta.oneblock.guide.GuideSettings.IDS+listOf("index","back","next","previous","link")) {"$id: unknown guide action"}
                                if(type=="dungeon")require(target in setOf("continue","finish","records","records-next","records-previous","select","ready","start","reconnect","leave","info","guide","menu","admin","admin-settings","admin-sessions","admin-players","admin-stop-all","admin-entry","admin-player-tp","admin-player-heal","admin-player-sendhub","admin-player-profile","admin-confirm","admin-next","admin-previous")) {"$id: unknown dungeon action"}
                                if(type=="release") {
                                    val known=setOf("activities","guild-accept-0","guild-accept-1","guild-accept-2","guild-cancel","guild-deliver","activity-resonance","activity-echo","activity-pause","activity-reward","activity-confirm","activity-recipes","claims-all","chests-confirm","chests-buy","chests-set","chests-loot-next","chests-loot-previous","chapter","confirm","claims","claims-next","claims-previous")+
                                        listOf("fortune","territory","focus").map {"upgrade $it"}+listOf("balanced","building","mineral","nature").map {"focus $it"}+
                                        listOf("building","nature","mineral","ancient").map {"pack $it"}+listOf("mining","forestry","farming").map {"tutorial $it"}+
                                        (listOf("water","lava","farm")+listOf("pickaxe","axe","hoe").flatMap {tool->(1..3).map {"${tool}_$it"}}).map {"recipe $it"}+(0..6).map {"claim $it"}+"daily"+"go"
                                    require(target in known || target?.matches(Regex("recipe [a-z][a-z0-9_]{0,47}"))==true) {"$id: unknown release action $target"}
                                }
                                val profile = boolean(data, "require-profile", false)
                                require(routes.put(id, MenuRoute(id, aliases, type, target, parents, permission, bypass, profile)) == null) { "Duplicate command $id" }
                            }
                        }
                        "statistics.yml" -> {
                            section(y, "values").let { definitions -> definitions.getKeys(false).forEach { id ->
                                require(id.matches(Regex("[a-z][a-z0-9_]*"))) { "Invalid statistic alias $id" }
                                val data = section(definitions, id)
                                val format = data.getString("format", "text")!!.also { require(it in setOf("text", "integer", "decimal", "compact")) }
                                require(values.put(id, MenuStatValue(string(data, "source"), format, data.getString("fallback", "—")!!)) == null) { "Duplicate statistic $id" }
                            } }
                            section(y, "displays").let { definitions -> definitions.getKeys(false).forEach { id ->
                                require(displays.put(id, item(section(definitions, id))) == null) { "Duplicate statistics display $id" }
                            } }
                        }
                    }
                } catch (e: Exception) { throw IllegalArgumentException("menus/$file: ${e.message}", e) }
            }
            completeNoIsland(menus,routes)
            migrateClaims(menus,routes)
            completeRoomRewards(menus,routes,values)
            completeActivities(menus,routes,values)
            require(menus.containsKey("main-menu") && routes["main-menu"]?.let { it.type == "open" && it.target == "main-menu" && it.requiresOpen.isEmpty() } == true) {
                "main-menu must be a root menu with a root open command"
            }
            val aliases = routes.values.flatMap { it.aliases }
            require(aliases.distinct().size == aliases.size) { "Menu command aliases must be unique" }
            routes.values.forEach { route ->
                require(route.type != "open" || route.target in menus) { "${route.id}: unknown destination ${route.target}" }
                require(route.requiresOpen.all { it == "*" || it == "profiles" || it in menus }) { "${route.id}: unknown parent menu" }
            }
            val result = MenuConfiguration(root, sources, menus, routes, MenuStatisticsConfig(values, displays), requireNotNull(launcher), messages)
            require("no-island" in menus) { "no-island must be a separate configured menu" }
            // Recognize only the exact old bundled placeholders in memory; never overwrite custom files.
            val oldSkills=menus["skills"]
            if(oldSkills?.slots?.get(20)?.item?.lore==listOf("&7Незабаром.") &&
                oldSkills.slots[21]?.item?.lore==listOf("&7Незабаром.") && oldSkills.slots[22]?.item?.lore==listOf("&7Незабаром.") &&
                oldSkills.slots[23]?.item?.name=="&aРибальство" && oldSkills.slots[24]?.command=="/scombat" && "release-skills" in menus) {
                menus["skills"]=menus.getValue("release-skills").copy(id="skills")
            }
            menus.values.forEach { menu -> menu.slots.values.flatMap { listOf(it) + it.states.values }.forEach { cell ->
                require(cell.item != null || cell.statistics != null || cell.states.isNotEmpty())
                require(cell.statistics == null || cell.statistics in displays) { "${menu.id}: unknown statistics ${cell.statistics}" }
                require(cell.command == null || result.routeFor(cell.command) != null) { "${menu.id}: command must be a configured alias or /obmenu <id>: ${cell.command}" }
            } }
            return result
        }
        /** Add only missing reward controls in memory; never rewrite an operator's existing menu files. */
        private fun completeRoomRewards(menus:MutableMap<String,StaticMenu>,routes:MutableMap<String,MenuRoute>,values:MutableMap<String,MenuStatValue>) {
            val claims=menus["release-claims"]?:return
            val layout=YamlConfiguration().apply {loadFromString(MenuConfiguration::class.java.getResourceAsStream("/menus/release/layout.yml")!!.reader().readText())}
            for(id in listOf("chest-preview","chest-confirm"))if(id !in menus)menus[id]=menu(id,layout.getConfigurationSection("menus.$id")!!)
            for(id in listOf("claims-all","chests-confirm","chests-buy","chests-set","chests-loot-next","chests-loot-previous","chests-back"))
                routes.putIfAbsent(id,MenuRoute(id,emptyList(),"release",if(id=="chests-back")"claims" else id,setOf("release-claims","chest-preview","chest-confirm"),null,null,true))
            values.putIfAbsent("reward_quote",MenuStatValue("reward.quote","text",""))
            values.putIfAbsent("reward_pity",MenuStatValue("reward.pity","text",""))
            val slots=claims.slots.toMutableMap()
            for((wanted,id) in listOf(47 to "claims-all")) {
                if(slots.values.any {it.command=="/obmenu $id"})continue
                val slot=(listOf(wanted)+(45 until claims.rows*9)).firstOrNull {it !in slots && it !in claims.content}?:continue
                slots[slot]=MenuCell(MenuItemSpec(if(id=="claims-all")Material.HOPPER else Material.LEATHER_CHESTPLATE,
                    if(id=="claims-all")"&aЗабрати все" else "&eОбрати комплект гарантії",if(id=="claims-all")listOf("&7Лише звичайні нагороди, без скринь.") else listOf("{reward_pity}")),null,"/obmenu $id")
            }
            menus[claims.id]=claims.copy(slots=slots)
        }
        private fun completeActivities(menus:MutableMap<String,StaticMenu>,routes:MutableMap<String,MenuRoute>,values:MutableMap<String,MenuStatValue>) {
            val layout=YamlConfiguration().apply {loadFromString(MenuConfiguration::class.java.getResourceAsStream("/menus/release/layout.yml")!!.reader().readText())}
            for(id in listOf("island-activities","activity-confirm"))if(id !in menus)menus[id]=menu(id,layout.getConfigurationSection("menus.$id")!!)
            val commands=YamlConfiguration().apply {loadFromString(MenuConfiguration::class.java.getResourceAsStream("/menus/release/commands.yml")!!.reader().readText())}
            for(id in commands.getConfigurationSection("commands")!!.getKeys(false).filter {it=="activities" || it.startsWith("guild-") || it.startsWith("activity-")}) {
                val c=commands.getConfigurationSection("commands.$id")!!
                routes.putIfAbsent(id,MenuRoute(id,c.getStringList("aliases"),"release",c.getString("execute"),c.getStringList("requires-open").toSet(),null,null,true))
            }
            for(id in listOf("status","quote","offer_0","offer_1","offer_2"))values.putIfAbsent("activity_$id",MenuStatValue("activity."+id.replace('_','.'),"text",""))
            val main=menus["main-menu"]?:return;val slots=main.slots.toMutableMap()
            if(slots.values.none {it.command=="/obmenu activities"})listOf(32,33,34,42,43,44).firstOrNull {it !in slots && it !in main.content}?.let {
                slots[it]=MenuCell(MenuItemSpec(Material.WRITABLE_BOOK,"&6Гільдія й острівні події",listOf("&7Замовлення, готовність і запуск подій.")),null,"/obmenu activities")
            }
            menus[main.id]=main.copy(slots=slots)
        }
        private fun menu(id:String,data:ConfigurationSection):StaticMenu {
            validId(id)
            listOf("filler","entry","empty").forEach {require(!data.contains(it)||data.isConfigurationSection(it)) {"$id.$it must be a mapping"}}
            require((!data.contains("rows") || data.isInt("rows")) && data.getInt("rows",6) in 1..6) {"$id rows must be 1..6"}
            val rows=data.getInt("rows",6)
            val cells=section(data,"slots").let {slots->slots.getKeys(false).associate {key->
                val slot=key.toIntOrNull()?.takeIf {it in 0 until rows*9}?:error("Invalid slot $id:$key")
                require(key==slot.toString()) {"Use canonical slot numbers, e.g. 4, not 04"}
                slot to cell(section(slots,key))
            }}
            val grid=if(data.contains("content"))requireNotNull(data.getList("content")).map {it.toString().toInt()} else emptyList()
            require(grid.distinct().size==grid.size && grid.all {it in 0 until rows*9 && it !in cells}) {"$id: overlapping content grid"}
            return StaticMenu(id,string(data,"title"),data.getConfigurationSection("filler")?.let(::item),cells,rows,grid,
                data.getConfigurationSection("entry")?.let {com.alytanta.oneblock.market.MarketMenuLayouts.readStyle(it)},
                data.getConfigurationSection("empty")?.let {com.alytanta.oneblock.market.MarketMenuLayouts.readStyle(it)},
                if(data.contains("empty.slot"))data.getInt("empty.slot").also {require(data.isInt("empty.slot") && it in grid)} else null)
        }
        /** Old layouts may lack this mandatory screen. Complete only the runtime candidate, never the files. */
        private fun completeNoIsland(menus:MutableMap<String,StaticMenu>,routes:MutableMap<String,MenuRoute>) {
            if("no-island" !in menus) {
                val source=requireNotNull(MenuConfiguration::class.java.getResourceAsStream("/menus/main-menu/layout.yml"))
                    .reader(Charsets.UTF_8).use {it.readText()}
                com.alytanta.oneblock.util.StrictYaml.validate(source)
                val bundled=YamlConfiguration().apply {loadFromString(source)}
                val fallback=menu("no-island",section(bundled,"menus.no-island"))
                val aliases=routes.values.flatMap {route->route.aliases.map {it to route.id}}.toMap()
                val cells=fallback.slots.mapValues {(_,cell)->
                    val command=cell.command
                    val id=command?.removePrefix("/")?.let {if(it.startsWith("obmenu "))it.removePrefix("obmenu ") else aliases[it]}
                    if(command!=null && id !in routes)cell.copy(command=null,item=cell.item?.let {it.copy(lore=it.lore+"@gui.action_unavailable")}) else cell
                }
                menus["no-island"]=fallback.copy(slots=cells)
            }
            routes.putIfAbsent("no-island",MenuRoute("no-island",emptyList(),"open","no-island",emptySet(),null,null,false))
        }
        private fun migrateClaims(menus:MutableMap<String,StaticMenu>,routes:MutableMap<String,MenuRoute>) {
            val menu=menus["release-claims"]?:return
            if(menu.content.isNotEmpty())return
            val old=menu.slots.entries.mapNotNull {entry->
                val index=entry.value.command?.removePrefix("/obmenu release-claim-")?.toIntOrNull()
                if(index==null)null else index to entry.key
            }.sortedBy {it.first}
            if(old.isEmpty())return
            val grid=old.map {it.second}
            val cells=menu.slots.filterKeys {it !in grid}.toMutableMap()
            for((id,preferred,name) in listOf(Triple("claims-previous",46,"@guides.previous"),Triple("claims-next",52,"@guides.next"))) {
                if(cells.values.none {it.command=="/obmenu $id"}) {
                    val free=(listOf(preferred)+(0 until menu.rows*9).reversed()).firstOrNull {it !in grid && it !in cells}
                        ?: error("release-claims: add a content grid and pagination controls; back up your custom layout before migrating")
                    cells[free]=MenuCell(MenuItemSpec(Material.ARROW,name,emptyList()),null,"/obmenu $id")
                }
                routes.putIfAbsent(id,MenuRoute(id,emptyList(),"release",id,setOf(menu.id),null,null,true))
            }
            menus[menu.id]=menu.copy(slots=cells,content=grid)
        }
        private fun cell(y: ConfigurationSection, nested: Boolean = false): MenuCell {
            val states = y.getConfigurationSection("states")?.let { definitions ->
                require(!nested) { "States cannot be nested" }
                definitions.getKeys(false).associateWith { cell(section(definitions,it),true) }
            }.orEmpty()
            val initial = optional(y,"initial-state")
            require(states.isEmpty() || initial in states) { "initial-state must select a configured state" }
            val stats = optional(y,"statistics")
            val spec = if (stats == null && y.contains("material")) item(y) else null
            require(stats == null || spec == null)
            val next = optional(y,"next-state")
            states.values.forEach { require(it.nextState == null || it.nextState in states) { "Unknown next-state" } }
            return MenuCell(spec,stats,optional(y,"command"),states,initial,optional(y,"state-source"),next,boolean(y,"enabled",true))
        }
        private fun validId(id: String) { require(id.matches(Regex("[a-z][a-z0-9_/-]*")) && id.split('/').none { it.isEmpty() }) { "Invalid id $id" } }
        private fun validPermission(value: String) { require(value.matches(Regex("[a-z0-9_.-]+"))) { "Invalid permission $value" } }
        private fun section(y: ConfigurationSection, key: String) = requireNotNull(y.getConfigurationSection(key)) { "$key must be a mapping" }
        private fun string(y: ConfigurationSection, key: String): String = requireNotNull(y.get(key) as? String) { "$key must be a string" }.also { require(it.isNotBlank()) { "$key must not be blank" } }
        private fun optional(y: ConfigurationSection, key: String): String? = if (!y.contains(key)) null else string(y, key)
        private fun strings(y: ConfigurationSection, key: String): List<String> {
            require(y.isList(key)) { "$key must be a list (use [] for no conditions/aliases)" }
            return y.getList(key)!!.map { requireNotNull(it as? String) { "$key must contain strings" } }
        }
        private fun boolean(y: ConfigurationSection, key: String, fallback: Boolean): Boolean {
            require(!y.contains(key) || y.isBoolean(key)) { "$key must be boolean" }; return y.getBoolean(key, fallback)
        }
        fun item(y: ConfigurationSection): MenuItemSpec {
            val material = requireNotNull(Material.matchMaterial(string(y, "material"))).also { require(it !in setOf(Material.AIR,Material.CAVE_AIR,Material.VOID_AIR)) }
            val amount = y.getInt("amount", 1).also { require((!y.contains("amount") || y.isInt("amount")) && it in 1..64) }
            val texture = optional(y, "texture")?.also {
                require(material == Material.PLAYER_HEAD) { "texture requires PLAYER_HEAD" }
                skinUrl(it) // Reject invalid texture before replacing any runtime menus.
            }
            val model = optional(y, "item-model")?.also { require(NamespacedKey.fromString(it) != null) }
            val custom = if (y.contains("custom-model-data")) y.getInt("custom-model-data").also { require(y.isInt("custom-model-data") && it >= 0) } else null
            require(!y.contains("name") || y.get("name") is String) { "name must be a string" }
            require(!y.contains("lore") || y.isList("lore") && y.getList("lore")!!.all { it is String }) { "lore must be a list of strings" }
            val customItem=optional(y,"custom-item")?.also {require(com.alytanta.oneblock.items.ItemCatalog.validId(it)) {"custom-item: namespaced OneBlock ID required"}}
            return MenuItemSpec(material, y.getString("name", " ")!!, y.getStringList("lore"), amount, texture, model, custom, boolean(y, "glow", false),customItem)
        }
        fun skinUrl(texture: String): java.net.URL? {
            if (texture == "{player}") return null
            val value = if (texture.startsWith("https://")) texture else {
                val json = JsonParser.parseString(String(Base64.getDecoder().decode(texture), Charsets.UTF_8)).asJsonObject
                json.getAsJsonObject("textures").getAsJsonObject("SKIN").get("url").asString.replace("http://", "https://")
            }
            val uri = java.net.URI(value)
            require(uri.scheme == "https" && uri.host == "textures.minecraft.net" && uri.port == -1 && uri.userInfo == null && uri.path.startsWith("/texture/")) {
                "texture must be {player}, a textures.minecraft.net HTTPS URL or a base64 Minecraft skin texture"
            }
            return uri.toURL()
        }
    }
}
