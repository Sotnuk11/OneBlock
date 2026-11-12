package com.alytanta.oneblock.menu

import com.alytanta.oneblock.island.IslandService
import com.alytanta.oneblock.island.VisitMode
import com.alytanta.oneblock.locale.Lang
import com.alytanta.oneblock.locale.LocaleService
import com.alytanta.oneblock.market.InventoryGuard
import com.alytanta.oneblock.player.PlayerNames
import com.alytanta.oneblock.util.Colors
import com.alytanta.oneblock.util.FoliaTasks
import com.alytanta.oneblock.util.OwnedItems
import org.bukkit.NamespacedKey
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.command.TabCompleter
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.inventory.InventoryDragEvent
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.InventoryHolder
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.meta.SkullMeta
import org.bukkit.persistence.PersistentDataType
import org.bukkit.permissions.Permission
import org.bukkit.permissions.PermissionDefault
import org.bukkit.plugin.java.JavaPlugin
import java.util.UUID

/** Static chest renderer. Typed commands and slot clicks share the configured dependency checks. */
class PlayerMenu(private val plugin: JavaPlugin, private val islands: IslandService, private val locales: LocaleService,
    private val ownedItems: OwnedItems, initial: MenuConfiguration, private val names: PlayerNames?) : Listener, CommandExecutor, TabCompleter {
    @Volatile private var configuration = initial
    var inventoryStatus: com.alytanta.oneblock.player.InventoryStatus? = null
    var playerState: com.alytanta.oneblock.player.PlayerService? = null
    var profileAction: ((Player,List<String>)->Unit)? = null
    var openProfiles: ((Player) -> Unit)? = null
    var externalMenuId: ((Player) -> String?)? = null
    var releaseAction: ((Player,String) -> Unit)? = null
    var onOpen: ((Player,String) -> Unit)? = null
    var guideAction: ((Player,String) -> Unit)? = null
    var dynamicOpen: ((Player,String)->Boolean)? = null
    var dungeonAction:((Player,String)->Unit)?=null
    var askInput:((Player,(String?)->Unit)->Unit)?=null
    var operationAllowed:((Player,MenuRoute)->Boolean)?=null
    var generation: () -> Long = { 0L }
    fun definition(id: String)=configuration.menus[id]
    fun currentMenuId(player:Player):String?=currentHolder(player)?.definition?.id
    val configurationIdentity: Any get() = configuration
    fun show(player: Player, id: String) { if(dynamicOpen?.invoke(player,id)==true)return;configuration.menus[id]?.let { render(player,it,configuration) } }
    private val key = NamespacedKey(plugin,"player_menu")
    private val registered = mutableListOf<Command>()
    private val permissions = mutableSetOf<String>()
    val statistics = MenuStatistics(islands)
    private class Holder(val viewer: UUID, val account: UUID?, val definition: StaticMenu, val configuration: MenuConfiguration) : InventoryHolder {
        lateinit var contents: Inventory
        val states = mutableMapOf<Int,String>()
        var pending = false
        var generation=0L
        val actions=mutableMapOf<Int,()->Unit>()
        override fun getInventory() = contents
    }
    fun isMenu(item: ItemStack?) = item?.itemMeta?.persistentDataContainer?.has(key,PersistentDataType.BYTE)==true
    val reservedSlot = ReservedMenuSlot(plugin,::isMenu,::createMenuItem,
        { player,stack -> ownedItems.drop(player.location.clone(),stack,player.uniqueId) }, { open(it) })
    private fun createMenuItem(player: Player) = makeItem(player,configuration.launcher,statistics.values(player,configuration.statistics)).apply {
        editMeta { it.persistentDataContainer.set(key,PersistentDataType.BYTE,1.toByte()) }
    }
    fun makeItem(player: Player, spec: MenuItemSpec, values: Map<String,String>): ItemStack = (spec.customItem?.let {com.alytanta.oneblock.items.CustomItems.current()?.find(it)}?.let {com.alytanta.oneblock.items.CustomItems.create(it,spec.amount)}?:ItemStack(spec.material,spec.amount)).apply {
        editMeta { meta ->
            if(spec.customItem==null)meta.displayName(Colors.colorize(statistics.render(localized(player,spec.name),values)))
            val extra=spec.lore.flatMap { statistics.render(localized(player,it),values).split('\n') }.map { Colors.colorize(it) }
            meta.lore((if(spec.customItem==null)emptyList() else meta.lore().orEmpty())+extra)
            spec.model?.let { meta.setItemModel(NamespacedKey.fromString(it)) }
            spec.customModel?.let { meta.setCustomModelData(it) }
            meta.setEnchantmentGlintOverride(spec.glow)
            if(meta is SkullMeta && spec.texture != null) {
                if(spec.texture == "{player}")GuiItems.viewerSkin(meta,player) else {
                    val profile=plugin.server.createPlayerProfile(UUID.nameUUIDFromBytes(spec.texture.toByteArray(Charsets.UTF_8)))
                    profile.setTextures(profile.textures.apply { setSkin(MenuConfiguration.skinUrl(spec.texture)) })
                    meta.ownerProfile=profile
                }
            }
        }
        GuiItems.quiet(this)
    }
    private fun message(player: Player, id: String, vararg values: Pair<String,String>) {
        val text=values.fold(configuration.messages.getValue(id)) { current,(key,value) -> current.replace("{$key}",value) }
        player.sendMessage(Colors.colorize(text))
        player.playSound(player.location,org.bukkit.Sound.BLOCK_NOTE_BLOCK_BASS,.5f,.8f)
    }
    private fun currentHolder(player: Player): Holder? = (player.openInventory.topInventory.holder as? Holder)?.takeIf {
        it.viewer==player.uniqueId && it.configuration===configuration && it.account==islands.findForPlayer(player.uniqueId)?.owner
    }
    private fun allowed(player: Player, route: MenuRoute, feedback: Boolean, expected: MenuConfiguration = configuration): Boolean {
        if(operationAllowed?.invoke(player,route)==false)return false
        fun deny(id: String): Boolean { if(feedback)message(player,id,"menu" to route.target.orEmpty());return false }
        if(configuration!==expected || !player.isOnline||player.isDead||InventoryGuard.busy(player.uniqueId))return deny("busy")
        if(!player.hasPermission("oneblock.command") || route.permission?.let { !player.hasPermission(it) }==true)return deny("permission")
        if(route.requireProfile && islands.findForPlayer(player.uniqueId)==null)return deny("profile")
        val current=currentHolder(player)?.definition?.id ?: externalMenuId?.invoke(player)
        val fromParent=route.requiresOpen.isEmpty() || current!=null && (current in route.requiresOpen || "*" in route.requiresOpen)
        val bypass=route.directPermission?.let(player::hasPermission)==true
        return fromParent || bypass || deny("denied")
    }
    fun execute(player: Player, id: String) {
        val snapshot=configuration
        val route=snapshot.routes[id]?:run { message(player,"unknown");return }
        if (!allowed(player,route.copy(requiresOpen=emptySet(),requireProfile=false),true,snapshot)) return
        if (islands.findForPlayer(player.uniqueId) == null && route.type !in setOf("profiles", "profile-action", "close", "guide") && route.target != "no-island" && !route.target.orEmpty().startsWith("guide")) {
            snapshot.menus["no-island"]?.let { render(player,it,snapshot) }; return
        }
        if(!allowed(player,route,true,snapshot))return
        when(route.type) {
            "open" -> show(player,route.target!!)
            "profiles" -> openProfiles?.invoke(player)
            "profile-action" -> profileAction?.invoke(player,listOf(route.target!!))
            "inventory-notifications" -> { inventoryStatus?.toggle(player); currentHolder(player)?.let { paint(player,it) } }
            "close" -> player.closeInventory()
            "player-command" -> player.performCommand(route.target!!)
            "release" -> releaseAction?.invoke(player,route.target!!)
            "guide" -> guideAction?.invoke(player,route.target!!)
            "dungeon" -> dungeonAction?.invoke(player,route.target!!)
            "input" -> askName(player,route.target!!)
        }
    }
    /** Asks for a player name on a sign instead of sending the player to chat, then returns to the same menu. */
    private fun askName(player:Player,command:String) {
        val back=currentHolder(player)?.definition?.id
        val ask=askInput?:return
        ask(player) { value->
            com.alytanta.oneblock.util.FoliaTasks.entity(plugin,player) {
                val name=value?.trim().orEmpty()
                if(name.matches(Regex("[A-Za-z0-9_.]{1,16}")))player.performCommand(command.replace("{input}",name))
                else if(name.isNotEmpty())message(player,"unknown")
                back?.let {show(player,it)}
            }
        }
    }
    fun hasMenu(player: Player) = currentHolder(player) != null || externalMenuId?.invoke(player) != null
    fun authorize(player: Player, id: String): Boolean = configuration.routes[id]?.let { allowed(player,it,true) } ?: false
    fun open(player: Player, id: String = "main-menu") = execute(player,id)
    private fun render(player: Player, definition: StaticMenu, current: MenuConfiguration) {
        onOpen?.invoke(player,definition.id)
        val values=statistics.values(player,current.statistics)
        val holder=Holder(player.uniqueId,islands.findForPlayer(player.uniqueId)?.owner,definition,current)
        holder.generation=generation()
        holder.contents=plugin.server.createInventory(holder,definition.rows*9,Colors.title(statistics.render(localized(player,definition.title),values)))
        definition.filler?.let { spec -> val filler=makeItem(player,spec,values);repeat(definition.rows*9) { holder.contents.setItem(it,filler.clone()) } }
        paint(player,holder)
        if(configuration===current)player.openInventory(holder.contents)
    }
    fun refreshControls(player:Player) {currentHolder(player)?.let {paint(player,it)}}
    private fun selected(player: Player, holder: Holder, slot: Int, cell: MenuCell): MenuCell {
        val source = cell.stateSource?.let { statistics.values(player,holder.configuration.statistics)[it] }
        val state = source?.takeIf { it in cell.states } ?: holder.states[slot] ?: cell.initialState
        return cell.states[state] ?: cell
    }
    fun page(player: Player, id: String, entries: List<Pair<String,MenuItemSpec>>, page: Int, valuesFor:(String)->Map<String,String> = {emptyMap()},displayFor:(String)->ItemStack? = {null},pick: (String)->Unit) {
        val definition=configuration.menus[id] ?: return
        require(definition.content.isNotEmpty()) { "$id needs a content grid" }
        render(player,definition,configuration)
        val holder=currentHolder(player) ?: return
        val pageValues=mapOf("page" to (page+1).toString(),"pages" to (((entries.size-1).coerceAtLeast(0)/definition.content.size)+1).toString(),"entries" to entries.size.toString())
        if(entries.isEmpty() && definition.empty?.enabled!=false)definition.empty?.let {style ->
            val slot=definition.emptySlot?:definition.content.first()
            holder.contents.setItem(slot,GuiItems.copy(plugin,player,ItemStack(org.bukkit.Material.PAPER),style.copy(name=style.name?.let {localized(player,it)},lore=style.lore?.map {localized(player,it)}),pageValues))
        }
        entries.drop(page.coerceAtLeast(0)*definition.content.size).take(definition.content.size).forEachIndexed { index,(key,spec) ->
            val slot=definition.content[index]
            val values=pageValues+mapOf("entry_id" to key,"label" to localized(player,spec.name),"body" to spec.lore.joinToString("\n"),"material" to spec.material.name,"amount" to spec.amount.toString())+valuesFor(key)
            val entry=definition.entry?.state(values["state"]?:"available")
            if(entry?.enabled==false)return@forEachIndexed
            holder.contents.setItem(slot,GuiItems.copy(plugin,player,displayFor(key)?:makeItem(player,spec,statistics.values(player,configuration.statistics)),entry,values))
            holder.actions[slot]={pick(key)}
        }
    }
    private fun paint(player: Player, holder: Holder) {
        val values = statistics.values(player,holder.configuration.statistics)
        holder.definition.slots.forEach { (slot,base) ->
            val cell = selected(player,holder,slot,base)
            if(!base.enabled || !cell.enabled) {holder.contents.setItem(slot,holder.definition.filler?.let {makeItem(player,it,values)});return@forEach}
            val spec = cell.item ?: holder.configuration.statistics.displays.getValue(cell.statistics!!)
            holder.contents.setItem(slot,makeItem(player,spec,values))
        }
    }
    fun settingsCommand(player: Player, args: List<String>) {
        val mode=args.firstOrNull()?.lowercase()
        if (mode == "inventory-notifications") { execute(player,"inventory-notifications"); return }
        val routeId=if(mode in setOf("all","none","selected","allow","deny"))"visitors-$mode" else "settings"
        val route=configuration.routes[routeId]?:run { message(player,"unknown");return }
        if(args.isEmpty()) { execute(player,route.id);return }
        if(!allowed(player,route,true))return
        val island=islands.findForPlayer(player.uniqueId)?:run { message(player,"profile");return }
        if(!islands.persistenceAvailable()) { message(player,"busy");return }
        try {
            com.alytanta.oneblock.storage.OperationContext.with(com.alytanta.oneblock.storage.OperationContext.Context(player.uniqueId,"ISLAND_VISITORS")) {
                when(args[0].lowercase()) {
                    "all","none","selected" -> { require(args.size==1);islands.changeVisitors(island.owner,player.uniqueId,mode=VisitMode.valueOf(args[0].uppercase())) }
                    "allow" -> { require(args.size==2);islands.changeVisitors(island.owner,player.uniqueId,addName=args[1]) }
                    "deny" -> { require(args.size==2);islands.changeVisitors(island.owner,player.uniqueId,removeName=args[1],remove=names?.resolve(args[1])) }
                    else -> error("Invalid settings command")
                }
            }
            locales.send(player,Lang.ADMIN_UPDATED,"player" to player.name,"field" to "visitors","value" to args.joinToString(" "))
        } catch (_:Exception) { locales.send(player,Lang.UI2_SETTINGS_ERROR) }
    }
    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        if(sender !is Player) { sender.sendMessage(Colors.colorize(configuration.messages.getValue("player-only")));return true }
        val current=configuration
        val id=if(command.name=="obmenu") { if(args.size<=1)args.firstOrNull()?:"main-menu" else null }
            else if(args.isEmpty())current.aliases[command.name] else null
        if(id==null)message(sender,"unknown") else execute(sender,id)
        return true
    }
    override fun onTabComplete(sender: CommandSender, command: Command, alias: String, args: Array<out String>): List<String> {
        if(sender !is Player || command.name!="obmenu" || args.size!=1)return emptyList()
        return configuration.routes.values.filter { it.id.startsWith(args[0]) && (it.permission==null || sender.hasPermission(it.permission)) }.map { it.id }
    }
    fun validateAliases(next: MenuConfiguration) {
        next.aliases.keys.forEach { alias ->
            if(alias=="guide")return@forEach
            val existing=plugin.server.commandMap.getCommand(alias)
            require(existing==null || existing in registered) { "Menu alias /$alias is already owned; change commands.yml" }
            val namespaced=plugin.server.commandMap.getCommand("oneblockmenu:$alias")
            require(namespaced==null || namespaced in registered) { "Menu alias /oneblockmenu:$alias is already owned" }
        }
        next.routes.values.filter { it.type=="player-command" }.forEach { route ->
            val label=route.target!!.substringBefore(' ').lowercase().substringAfter(':')
            require(label!="obmenu" && label !in next.aliases) { "${route.id}: recursive menu command; use type: open" }
            val parts=route.target.trim().lowercase().split(Regex("\\s+"))
            if(label in setOf("ob","oneblock")) {
                require(parts.getOrNull(1) !in setOf("menu","profile","skills","skill","profession","professions","collection","collections")) { "${route.id}: recursive menu command" }
                require(parts.getOrNull(1)!="settings" || parts.size>2) { "${route.id}: use type: open for settings" }
            }
        }
    }
    fun installCommands() {
        validateAliases(configuration);installPermissions(configuration);registerAliases(configuration)
        plugin.getCommand("obmenu")!!.apply { setExecutor(this@PlayerMenu);tabCompleter=this@PlayerMenu }
    }
    private fun installPermissions(next: MenuConfiguration) {
        next.routes.values.mapNotNull { it.directPermission }.forEach { permission ->
            if(plugin.server.pluginManager.getPermission(permission)==null) {
                plugin.server.pluginManager.addPermission(Permission(permission,"Open a menu without its parent",PermissionDefault.FALSE))
                permissions+=permission
            }
        }
    }
    private fun registerAliases(next: MenuConfiguration) {
        next.aliases.keys.forEach { alias ->
            if(alias=="guide" && plugin.server.commandMap.getCommand(alias)!=null)return@forEach
            val command=object:Command(alias) {
                override fun execute(sender: CommandSender, label: String, args: Array<out String>): Boolean = this@PlayerMenu.onCommand(sender,this,label,args)
                override fun tabComplete(sender: CommandSender, alias: String, args: Array<out String>) = emptyList<String>()
            }
            check(plugin.server.commandMap.register("oneblockmenu",command)) { "Cannot register /$alias" }
            registered+=command
        }
    }
    private fun unregisterAliases() {
        val owned=registered.toSet()
        val known=plugin.server.commandMap.knownCommands
        known.entries.filter { it.value in owned }.map { it.key to it.value }.forEach { (key,value)->known.remove(key,value) }
        registered.forEach { it.unregister(plugin.server.commandMap) };registered.clear()
    }
    fun apply(next: MenuConfiguration) {
        validateAliases(next)
        val changed=configuration.aliases.keys!=next.aliases.keys
        installPermissions(next)
        if(changed) {
            val previous=configuration
            unregisterAliases()
            try {registerAliases(next)} catch(error:Exception) {
                unregisterAliases();runCatching {registerAliases(previous)}.onFailure(error::addSuppressed);throw error
            }
        }
        configuration=next
        plugin.server.onlinePlayers.forEach { player -> FoliaTasks.entity(plugin,player) {
            if(player.openInventory.topInventory.holder is Holder)player.closeInventory()
            reservedSlot.ensureItem(player)
            if(changed)player.updateCommands()
        } }
    }
    fun close() { unregisterAliases();permissions.forEach { plugin.server.pluginManager.removePermission(it) };permissions.clear() }
    private fun localized(player: Player, text: String) = if(text.startsWith("@release.") || text.startsWith("@guides.") || text.startsWith("@gui."))locales.textKey(player,text.drop(1)) else text
    @EventHandler(priority=EventPriority.HIGHEST) fun click(event: InventoryClickEvent) {
        val holder=event.view.topInventory.holder as? Holder?:return
        event.isCancelled=true
        val player=event.whoClicked as? Player?:return
        if(holder.viewer!=player.uniqueId || holder.pending || (!event.isLeftClick&&!event.isRightClick) || event.isShiftClick)return
        holder.actions[event.rawSlot]?.let { action ->
            holder.pending=true
            FoliaTasks.entity(plugin,player) {
                try { if(currentHolder(player)===holder && holder.generation==generation() && !InventoryGuard.busy(player.uniqueId) && player.hasPermission("oneblock.command"))action() }
                finally {holder.pending=false}
            }
            return
        }
        val base=holder.definition.slots[event.rawSlot]?:return
        val cell=selected(player,holder,event.rawSlot,base)
        if(!base.enabled || !cell.enabled)return
        val command=cell.command?:return
        holder.pending=true
        FoliaTasks.entity(plugin,player) {
            try {
                if(player.openInventory.topInventory===holder.contents && currentHolder(player)===holder && holder.generation==generation()) {
                    val route=configuration.routeFor(command)?:return@entity
                    if (!allowed(player,route,true)) return@entity
                    execute(player,route.id)
                    cell.nextState?.let { holder.states[event.rawSlot] = it }
                    if (player.openInventory.topInventory === holder.contents) paint(player,holder)
                }
            } finally { holder.pending=false }
        }
    }
    @EventHandler(priority=EventPriority.HIGHEST) fun drag(event: InventoryDragEvent) {
        if(event.view.topInventory.holder is Holder)event.isCancelled=true
    }
}
