package com.alytanta.oneblock.player

import com.alytanta.oneblock.island.Island
import com.alytanta.oneblock.island.IslandService
import com.alytanta.oneblock.island.IslandRole
import com.alytanta.oneblock.hub.HubService
import com.alytanta.oneblock.locale.Lang
import com.alytanta.oneblock.locale.LocaleService
import com.alytanta.oneblock.market.InventoryGuard
import com.alytanta.oneblock.market.TransferStatus
import com.alytanta.oneblock.storage.PostgresStorageService
import com.alytanta.oneblock.storage.StorageService
import com.alytanta.oneblock.util.Colors
import com.alytanta.oneblock.util.FoliaTasks
import com.alytanta.oneblock.menu.style
import com.alytanta.oneblock.market.MarketWindow
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.inventory.InventoryDragEvent
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.event.player.AsyncPlayerPreLoginEvent
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.InventoryHolder
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataType
import org.bukkit.plugin.Plugin
import java.util.UUID

/** A journaled account/inventory switch, using the same failure model as market handoffs. */
class ProfileController(private val plugin: Plugin, private val islands: IslandService, private val players: PlayerService,
    private val storage: StorageService, private val hub: HubService, private val locales: LocaleService,
    private val home: (Player, Island) -> Unit) : Listener {
    fun closeMenus() { plugin.server.onlinePlayers.forEach { player -> FoliaTasks.entity(plugin,player) { if(isOpen(player)) player.closeInventory() } } }
    fun isOpen(player: Player) = (player.openInventory.topInventory.holder as? Holder)?.player == player.uniqueId
    var authorize: ((Player,String)->Boolean)? = null
    var backToMenu:((Player)->Unit)?=null
    var changeAllowed:(UUID)->Boolean={true}
    var makeItem: ((Player, com.alytanta.oneblock.menu.MenuItemSpec, Map<String,String>) -> ItemStack)? = null
    @Volatile var interfaces = com.alytanta.oneblock.menu.InterfaceMenus.defaults()
    private val marker = NamespacedKey(plugin, "profile_switch")
    private val evictionMarker = NamespacedKey(plugin, "profile_eviction")
    var resolvePlayerName: (String) -> UUID? = { null }
    var displayPlayerName: (UUID) -> String? = { null }
    private val resolving = java.util.concurrent.ConcurrentHashMap.newKeySet<UUID>()
    var onProfileRetired: (Island) -> Unit = {}
    private val removals = ProfileRemovalCoordinator(plugin, storage, islands, players, resolving,
        { actor, token -> players.find(actor.uniqueId)?.eviction?.let { recoverEviction(actor, it, token) }
            ?: InventoryGuard.release(actor.uniqueId, token) }, { onProfileRetired(it) })
    private class Holder(val player: UUID, val source: com.alytanta.oneblock.market.MarketMenuLayouts, val layout: MarketWindow? = null) : InventoryHolder {
        lateinit var contents: Inventory
        val actions = mutableMapOf<Int, (Player) -> Unit>()
        val secondary = mutableMapOf<Int, (Player) -> Unit>()
        var profileSettings:ProfileSettings?=null
        var account:UUID?=null
        val rendered=mutableSetOf<Int>()
        var pending=false
        override fun getInventory() = contents
        fun target(source: Int): Int? = if (layout == null) source else layout.target(source)
        fun bind(source: Int, action: (Player) -> Unit) { if(source in rendered)target(source)?.let { actions[it] = action } }
    }
    private fun window(player: Player, id: String, title: Lang, vararg args: Pair<String, Any>): Holder {
        val source = interfaces
        val layout = source.window(id, null)
        val holder = Holder(player.uniqueId, source, layout)
        holder.account=islands.findForPlayer(player.uniqueId)?.owner
        var heading = layout.title ?: locales.text(player, title, *args)
        args.forEach { (key, value) -> heading = heading.replace("{$key}", value.toString()) }
        heading = heading.replace("{viewer}", player.name)
        holder.contents = plugin.server.createInventory(holder, layout.rows * 9, Colors.title(heading))
        com.alytanta.oneblock.menu.GuiItems.background(plugin,player,holder.contents,layout,layout.filler)
        place(player,holder,1000,com.alytanta.oneblock.menu.MenuItemSpec(Material.BARRIER,locales.text(player,Lang.MARKET_CLOSE),emptyList()))
        holder.bind(1000) {it.closeInventory()}
        return holder
    }
    private fun place(player: Player, holder: Holder, source: Int, spec: com.alytanta.oneblock.menu.MenuItemSpec,
        values: Map<String, String> = emptyMap()) {
        val target = holder.target(source) ?: return
        val styled = if(holder.layout==null)spec else holder.layout.style(source,spec) ?: return
        makeItem?.let { holder.contents.setItem(target, it(player, styled, values));holder.rendered+=source }
    }
    fun open(player: Player) {
        if (!storage.healthy || InventoryGuard.busy(player.uniqueId) || player.isDead) return
        val settings = islands.profileSettings
        val holder = Holder(player.uniqueId, interfaces)
        holder.profileSettings=settings
        holder.account=islands.findForPlayer(player.uniqueId)?.owner
        holder.contents = plugin.server.createInventory(holder, settings.rows*9, Colors.title(settings.title))
        val renderer = makeItem ?: return
        settings.filler?.let { filler -> repeat(settings.rows*9) { holder.contents.setItem(it,renderer(player,filler,emptyMap())) } }
        val profiles = islands.profilesFor(player.uniqueId)
        // Preserve access to existing profiles even if a later config lowers the creation limit.
        val visible = maxOf(settings.visibleSlots,profiles.size).coerceAtMost(18)
        settings.slots.take(visible).forEachIndexed { index, spec ->
            val island = profiles.getOrNull(index)
            val active = island != null && islands.findForPlayer(player.uniqueId)?.owner == island.owner
            val unlocked = index < settings.availableSlots
            val values = mapOf("slot" to (index+1).toString(), "name" to (island?.let { islands.profileName(it,locales.languageOf(player)) } ?: ""),
                "id" to island?.publicId.orEmpty(), "type" to if(island?.members?.values?.any { it == IslandRole.MEMBER } == true) "CO-OP" else "SOLO",
                "members" to ((island?.members?.values?.count { it == IslandRole.MEMBER } ?: 0)+1).toString(),
                "coins" to (island?.coins ?: 0).toString(), "breaks" to (island?.core?.breaks ?: 0).toString())
            val item = when { active -> spec.active; island != null -> spec.occupied; unlocked -> spec.empty; else -> spec.locked }
            if (!spec.enabled) return@forEachIndexed
            holder.contents.setItem(spec.slot,renderer(player,if (island != null && settings.showRemovalHint) item.copy(lore = item.lore + locales.text(player, Lang.PROFILE_DELETE_HINT)) else item,values))
            if (island != null) holder.actions[spec.slot] = { if(authorize?.invoke(it,"profiles-switch")==true)switch(it,island.owner) }
            if (island != null) holder.secondary[spec.slot] = { confirmRemoval(it, island.owner, it.uniqueId) }
            else if (unlocked) holder.actions[spec.slot] = { command(it,listOf("create")) }
        }
        if (settings.closeEnabled) {
            holder.contents.setItem(settings.closeSlot,renderer(player,settings.close,emptyMap()))
            holder.actions[settings.closeSlot] = { it.closeInventory() }
        }
        settings.back?.let {back->
            holder.contents.setItem(settings.backSlot,renderer(player,back,emptyMap()))
            holder.actions[settings.backSlot]={backToMenu?.invoke(it)}
        }
        val activeCoop = islands.findForPlayer(player.uniqueId)?.takeIf { ProfileRemoval.participants(it).size > 1 }
        val free = settings.coopSlot ?: listOf(48, 50, 45, 53).firstOrNull { (settings.back==null || it!=settings.backSlot) && (!settings.closeEnabled || it != settings.closeSlot) && settings.slots.none { spec -> spec.enabled && spec.slot == it } && it < settings.rows * 9 }
        if (activeCoop != null && free != null && settings.coopButton != null) {
            holder.contents.setItem(free, renderer(player, settings.coopButton, mapOf("label" to locales.text(player, Lang.COOP_MANAGE))))
            holder.actions[free] = { members(it, activeCoop.owner) }
        }
        player.openInventory(holder.contents)
    }
    private fun members(player: Player, account: UUID, page: Int = 0) {
        val island = islands.findByOwner(account) ?: return
        if (player.uniqueId !in ProfileRemoval.participants(island)) return
        if (makeItem == null) return
        val people = ProfileRemoval.participants(island).sortedBy { displayPlayerName(it) ?: it.toString() }
        val holder = window(player, "profile-members", Lang.COOP_MANAGE)
        val grid = holder.layout!!.grid("content", (0..44).toList())
        val current = page.coerceIn(0, (people.size - 1).coerceAtLeast(0) / grid.size)
        people.drop(current * grid.size).take(grid.size).forEachIndexed { index, id ->
            val slot = grid[index]
            val last = players.find(id)?.profileActivity.orEmpty()[account]
            val days = last?.let { ((System.currentTimeMillis() - it).coerceAtLeast(0) / 86_400_000).toString() } ?: "?"
            val text = locales.text(player, Lang.COOP_MEMBER, "name" to (displayPlayerName(id) ?: id.toString().take(8)), "days" to days).split('\n')
            place(player, holder, slot, com.alytanta.oneblock.menu.MenuItemSpec(Material.PLAYER_HEAD, text.first(), text.drop(1)),
                mapOf("name" to (displayPlayerName(id) ?: id.toString().take(8)), "days" to days))
            if (id != player.uniqueId && island.ownerPlayer==player.uniqueId) holder.bind(slot) { confirmRemoval(it, account, id) }
        }
        fun back(slot: Int, lang: Lang, action: (Player) -> Unit) {
            place(player, holder, slot, com.alytanta.oneblock.menu.MenuItemSpec(Material.ARROW, locales.text(player, lang), emptyList()))
            holder.bind(slot, action)
        }
        back(49, Lang.MARKET_BACK, ::open)
        if (current > 0) back(45, Lang.MENU_PREVIOUS) { members(it, account, current - 1) }
        if ((current + 1) * grid.size < people.size) back(53, Lang.MENU_NEXT) { members(it, account, current + 1) }
        player.openInventory(holder.contents)
    }
    fun command(player: Player, args: List<String>) {
        if(!changeAllowed(player.uniqueId)) {locales.send(player,Lang.PROFILES_UNAVAILABLE);return}
        val route = args.firstOrNull()?.lowercase()?.let { if (it == "delete") "profiles" else "profiles-$it" } ?: "profiles"
        if (authorize?.invoke(player,route) != true) return
        when (args.firstOrNull()?.lowercase()) {
            null -> open(player)
            "create" -> create(player, args.drop(1).joinToString(" "))
            "switch" -> {
                val index = args.getOrNull(1)?.toIntOrNull()?.minus(1)
                val island = index?.let { islands.profilesFor(player.uniqueId).getOrNull(it) }
                if (island == null) locales.send(player, Lang.PROFILES_USAGE) else switch(player, island.owner)
            }
            "rename" -> {
                val island = islands.findForPlayer(player.uniqueId)
                val name = args.drop(1).joinToString(" ").trim()
                if (island?.ownerPlayer != player.uniqueId || !validName(name)) locales.send(player, Lang.PROFILES_USAGE)
                else { islands.update(island.owner) { it.copy(profileName = name) }; open(player) }
            }
            "delete" -> {
                val island = if (args.size == 1) islands.findForPlayer(player.uniqueId) else
                    args.getOrNull(1)?.toIntOrNull()?.minus(1)?.let { islands.profilesFor(player.uniqueId).getOrNull(it) }
                if (island == null) locales.send(player, Lang.PROFILES_USAGE) else confirmRemoval(player, island.owner, player.uniqueId)
            }
            else -> locales.send(player, Lang.PROFILES_USAGE)
        }
    }
    private fun validName(name: String) = name.isNotBlank() && name.length <= 24 && name.none { it == '&' || it == '§' || it.isISOControl() }
    private fun pendingTrade(player: Player) = (storage as? PostgresStorageService)?.marketState()?.let {
        com.alytanta.oneblock.trade.PlayerTradeLedger.pending(it, player.uniqueId)
    } == true
    fun create(player: Player, name: String) {
        if(!changeAllowed(player.uniqueId)) {locales.send(player,Lang.PROFILES_UNAVAILABLE);return}
        if (pendingTrade(player)) { locales.send(player, Lang.P2P_REVIEW); return }
        if (name.isNotBlank() && !validName(name)) { locales.send(player, Lang.PROFILES_USAGE); return }
        if (player.isDead || player.isInsideVehicle || !player.itemOnCursor.isEmpty) { locales.send(player, Lang.PROFILES_UNAVAILABLE); return }
        if (players.find(player.uniqueId)?.profileSwitch != null || InventoryGuard.busy(player.uniqueId)) return
        val island = islands.createProfile(player.uniqueId, name) ?: run { locales.send(player, Lang.PROFILES_LIMIT); return }
        switch(player, island.owner)
    }
    fun leave(player: Player) {
        val previous = islands.findForPlayer(player.uniqueId)?.takeIf { it.members[player.uniqueId] == IslandRole.MEMBER }
            ?: run { locales.send(player, Lang.COOP_NOT_MEMBER); return }
        confirmRemoval(player, previous.owner, player.uniqueId)
    }
    fun kick(player: Player, name: String?) {
        val account = islands.findForPlayer(player.uniqueId) ?: return
        val target = name?.let { resolvePlayerName(it) ?: plugin.server.getPlayerExact(it)?.uniqueId }
        if (target == null || target == player.uniqueId || target !in ProfileRemoval.participants(account)) {
            locales.send(player, Lang.COOP_TARGET_NOT_FOUND); return
        }
        confirmRemoval(player, account.owner, target)
    }
    fun salvage(player: Player) {
        locales.send(player, Lang.PROFILE_SALVAGE_HELP)
    }
    private fun confirmRemoval(player: Player, account: UUID, who: UUID) {
        if (InventoryGuard.busy(player.uniqueId) || !storage.healthy) return
        val island = islands.findByOwner(account) ?: return
        if (player.uniqueId !in ProfileRemoval.participants(island) || who !in ProfileRemoval.participants(island)) return
        if (makeItem == null) return
        val holder = window(player, "profile-delete", Lang.PROFILE_DELETE_TITLE,
            "name" to islands.profileName(island, locales.languageOf(player)),
            "member" to (displayPlayerName(who) ?: players.find(who)?.lastKnownName ?: who.toString().take(8)))
        fun item(slot: Int, material: Material, text: Lang) {
            val lines = locales.text(player, text, "name" to islands.profileName(island, locales.languageOf(player)),
                "member" to (displayPlayerName(who) ?: players.find(who)?.lastKnownName ?: who.toString().take(8))).split('\n')
            place(player, holder, slot, com.alytanta.oneblock.menu.MenuItemSpec(material, lines.first(), lines.drop(1)),
                mapOf("name" to islands.profileName(island, locales.languageOf(player)),
                    "member" to (displayPlayerName(who) ?: players.find(who)?.lastKnownName ?: who.toString().take(8))))
        }
        item(11, Material.RED_CONCRETE, Lang.PROFILE_DELETE_CONFIRM)
        item(13, Material.BOOK, if (ProfileRemoval.participants(island).size > 1) Lang.PROFILE_DELETE_COOP else Lang.PROFILE_DELETE_SOLO)
        item(15, Material.ARROW, Lang.MARKET_BACK)
        holder.bind(15, ::open)
        holder.bind(11, action@{ viewer ->
            if (viewer.isDead || viewer.isInsideVehicle || !viewer.itemOnCursor.isEmpty) {
                locales.send(viewer, Lang.PROFILES_UNAVAILABLE); return@action
            }
            viewer.closeInventory()
            runCatching {
                ProfileRemoval.validate(requireNotNull(islands.findByOwner(account)), players.getOrCreate(who),
                    (storage as PostgresStorageService).marketState(), viewer.uniqueId, System.currentTimeMillis())
                removeMember(viewer, account, who)
            }.onFailure { locales.send(viewer, if(it.message=="unclaimed")Lang.PROFILE_UNCLAIMED else if (it.message in setOf("active-member", "activity-unknown")) Lang.COOP_KICK_INACTIVE else Lang.PROFILE_DELETE_FAILED) }
        })
        player.openInventory(holder.contents)
    }
    private fun removeMember(player: Player, account: UUID, who: UUID) {
        if(!changeAllowed(player.uniqueId) || !changeAllowed(who)) {locales.send(player,Lang.PROFILES_UNAVAILABLE);return}
        removals.remove(account, who, player.uniqueId, false).whenComplete { result, error ->
            FoliaTasks.entity(plugin, player) {
                if (error == null) { locales.send(player, Lang.PROFILE_DELETE_DONE, "items" to result.sumOf { it.salvaged }); open(player) }
                else { locales.send(player, if(generateSequence(error) {it.cause}.any {it.message=="unclaimed"})Lang.PROFILE_UNCLAIMED else Lang.PROFILE_DELETE_FAILED); plugin.logger.log(java.util.logging.Level.WARNING, "Profile removal failed", error) }
            }
        }
    }
    fun administrativeRemoval(sender: CommandSender, account: UUID, who: UUID?) {
        if((who?.let { !changeAllowed(it) }==true) || islands.findByOwner(account)?.let {island->(island.members.keys+island.ownerPlayer).any {!changeAllowed(it)}}==true) {
            locales.send(sender,Lang.PROFILES_UNAVAILABLE);return
        }
        if (!sender.isOp || !sender.hasPermission("oneblock.admin")) { locales.send(sender, Lang.NO_PERMISSION); return }
        val actor = (sender as? Player)?.uniqueId ?: UUID.nameUUIDFromBytes("console:${sender.name}".toByteArray())
        removals.remove(account, who, actor, true).whenComplete { plans, error ->
            fun reply() {
                if (error == null) locales.send(sender, Lang.PROFILE_DELETE_DONE, "items" to plans.sumOf { it.salvaged })
                else { locales.send(sender, if(generateSequence(error) {it.cause}.any {it.message=="unclaimed"})Lang.PROFILE_UNCLAIMED else Lang.ADMIN_FAILED); plugin.logger.log(java.util.logging.Level.WARNING, "Administrative profile removal failed", error) }
            }
            if (sender is Player) FoliaTasks.entity(plugin, sender) { reply() } else FoliaTasks.globalDelayed(plugin, 1) { reply() }
        }
    }
    fun switch(player: Player, account: UUID, after: (() -> Unit)? = null) {
        if(!changeAllowed(player.uniqueId)) {locales.send(player,Lang.PROFILES_UNAVAILABLE);return}
        if (pendingTrade(player)) { locales.send(player, Lang.P2P_REVIEW); return }
        val target = islands.profilesFor(player.uniqueId).firstOrNull { it.owner == account }
            ?: run { locales.send(player, Lang.ISLAND_NOT_FOUND); return }
        if (!storage.healthy || player.isDead || player.isInsideVehicle || !player.itemOnCursor.isEmpty) {
            locales.send(player, Lang.PROFILES_UNAVAILABLE); return
        }
        if (players.find(player.uniqueId)?.profileSwitch != null || (storage as? PostgresStorageService)?.marketState()?.transfers?.values?.any {
                it.actor == player.uniqueId && it.status in setOf(TransferStatus.PREPARED, TransferStatus.REVIEW) } == true) {
            locales.send(player, Lang.PROFILES_REVIEW); return
        }
        val state = players.getOrCreate(player.uniqueId)
        val current = islands.findForPlayer(player.uniqueId)?.owner
        // An implicit first account still needs durable adoption before it owns a live inventory.
        val from = if (state.activeAccount == null && current == account) null else current
        if (from == account) { after?.invoke(); home(player, target); return }
        val token = InventoryGuard.acquireToken(player.uniqueId) ?: run { locales.send(player, Lang.MARKET_BUSY); return }
        val switchGeneration = storage.generation
        player.closeInventory()
        // Move out of the old profile before installing the other inventory; fail without changing state.
        hub.resolve { destination ->
            FoliaTasks.entity(plugin, player, retired = { if(storage.generation == switchGeneration) InventoryGuard.release(player.uniqueId, token) }) {
                if (!storage.healthy || storage.generation != switchGeneration) return@entity
                if (destination == null || player.isDead) { fail(player, token); return@entity }
                runCatching {
                    player.teleportAsync(destination).whenComplete { ok, error ->
                        FoliaTasks.entity(plugin, player, retired = { if(storage.generation == switchGeneration) InventoryGuard.release(player.uniqueId, token) }) {
                            if (!storage.healthy || storage.generation != switchGeneration) return@entity
                            if (ok != true || error != null || player.isDead) fail(player, token)
                            else prepare(player, from, target, after, token)
                        }
                    }
                }.onFailure { fail(player, token) }
            }
        }
    }
    private fun prepare(player: Player, from: UUID?, target: Island, after: (() -> Unit)?, token: UUID) {
        val generation = storage.generation
        try {
            check(islands.profilesFor(player.uniqueId).any { it.owner == target.owner })
            val before = ProfileInventory.capture(player)
            val state = players.getOrCreate(player.uniqueId)
            val next = state.savedInventories.orEmpty()[target.owner] ?: if (from == null) before else ProfileInventory()
            val prepared = next.prepare(player)
            val intent = ProfileSwitch(UUID.randomUUID(), from, target.owner, before.hash())
            players.update(player.uniqueId) { it.copy(profileSwitch = intent,
                savedInventories = it.savedInventories.orEmpty() + (from?.let { id -> mapOf(id to before) } ?: emptyMap())) }
            storage.flushAsync().whenComplete { _, error ->
                if (error != null) { fail(player, token); return@whenComplete }
                FoliaTasks.entity(plugin, player, retired = { if(storage.healthy && storage.generation == generation) abort(player.uniqueId, intent, token) }) {
                    if (!storage.healthy || storage.generation != generation) return@entity
                    if (player.isDead || ProfileInventory.capture(player).hash() != intent.beforeHash ||
                        islands.profilesFor(player.uniqueId).none { it.owner == intent.to }) { abort(player.uniqueId, intent, token); return@entity }
                    try {
                        prepared.apply(player)
                        player.persistentDataContainer.set(marker, PersistentDataType.STRING, intent.id.toString())
                        player.saveData()
                        finish(player.uniqueId, intent)
                        storage.flushAsync().whenComplete { _, finishError ->
                            if (storage.generation != generation) return@whenComplete
                            if (finishError != null) { fail(player, token); return@whenComplete }
                            InventoryGuard.release(player.uniqueId, token)
                            FoliaTasks.entity(plugin, player) {
                                after?.invoke()
                                locales.send(player, Lang.PROFILES_SWITCHED, "name" to islands.profileName(target,locales.languageOf(player)))
                                home(player, target)
                            }
                        }
                    } catch (e: Exception) {
                        review(player.uniqueId, intent)
                        locales.send(player, Lang.PROFILES_REVIEW)
                        plugin.logger.log(java.util.logging.Level.SEVERE, "Profile switch ${intent.id} requires review", e)
                    }
                }
            }
        } catch (e: Exception) { fail(player, token); plugin.logger.log(java.util.logging.Level.WARNING, "Could not prepare profile switch", e) }
    }
    private fun finish(player: UUID, intent: ProfileSwitch) {
        players.update(player) { check(it.profileSwitch?.id == intent.id); it.copy(activeAccount = intent.to,
            profileSwitch = null, inventoryDetached = null, savedInventories = it.savedInventories.orEmpty() - intent.to,
            profileActivity = it.profileActivity.orEmpty() + (intent.from?.let { id -> mapOf(id to System.currentTimeMillis()) } ?: emptyMap()) +
                (intent.to to System.currentTimeMillis())) }
    }
    private fun abort(player: UUID, intent: ProfileSwitch, token: UUID? = null) {
        runCatching {
            players.update(player) { check(it.profileSwitch?.id == intent.id); it.copy(profileSwitch = null,
                savedInventories = it.savedInventories.orEmpty() - listOfNotNull(intent.from).toSet()) }
            storage.flushAsync().whenComplete { _, _ -> if (token == null) InventoryGuard.release(player) else InventoryGuard.release(player, token) }
        }.onFailure { plugin.logger.severe("Could not abort profile switch ${intent.id}") }
    }
    private fun review(player: UUID, intent: ProfileSwitch) {
        players.update(player) { it.copy(profileSwitch = intent.copy(review = true)) }
        storage.flushAsync()
        // Keep inventory blocked until reviewed; never replay a target inventory on reconnect.
    }
    private fun fail(player: Player, token: UUID) {
        if (players.find(player.uniqueId)?.profileSwitch == null) InventoryGuard.release(player.uniqueId, token)
        FoliaTasks.entity(plugin, player) { locales.send(player, Lang.PROFILES_UNAVAILABLE) }
    }
    fun recover(player: Player) {
        if (!storage.healthy) return
        val state = players.getOrCreate(player.uniqueId)
        if (state.eviction != null) { recoverEviction(player, state.eviction); return }
        if (state.inventoryDetached == true) { hub.teleport(player); return }
        val intent = state.profileSwitch
        if (intent == null) {
            if (state.activeAccount == null) islands.findForPlayer(player.uniqueId)?.let { current ->
                val order = islands.profilesFor(player.uniqueId).map { island -> island.owner }
                players.update(player.uniqueId) { it.copy(activeAccount = current.owner,
                    profileOrder = order, profileActivity = it.profileActivity.orEmpty() + (current.owner to System.currentTimeMillis())) }
            }
            return
        }
        val token = InventoryGuard.acquireToken(player.uniqueId) ?: return
        if (!storage.healthy || intent.review) { locales.send(player, Lang.PROFILES_REVIEW); return }
        try {
            when {
                player.persistentDataContainer.get(marker, PersistentDataType.STRING) == intent.id.toString() -> {
                    finish(player.uniqueId, intent)
                    storage.flushAsync().whenComplete { _, error -> if (error == null) InventoryGuard.release(player.uniqueId, token) }
                }
                ProfileInventory.capture(player).hash() == intent.beforeHash -> abort(player.uniqueId, intent, token)
                else -> { review(player.uniqueId, intent); locales.send(player, Lang.PROFILES_REVIEW) }
            }
        } catch (e: Exception) { plugin.logger.log(java.util.logging.Level.SEVERE, "Profile recovery failed for ${player.uniqueId}", e) }
    }
    private fun recoverEviction(player: Player, eviction: ProfileEviction, heldToken: UUID? = null) {
        val token = heldToken ?: InventoryGuard.acquireToken(player.uniqueId) ?: return
        if (!InventoryGuard.owns(player.uniqueId, token)) return
        if (eviction.review) { locales.send(player, Lang.PROFILES_REVIEW); return }
        val generation = storage.generation
        try {
            if (eviction.pendingCapture) {
                val snapshot = ProfileInventory.capture(player); player.saveData()
                islands.captureEviction(player.uniqueId, snapshot)
                storage.flushAsync().whenComplete { _, error ->
                    if (error == null && storage.generation == generation) FoliaTasks.entity(plugin, player,
                        retired = { InventoryGuard.release(player.uniqueId, token) }) {
                        recoverEviction(player, requireNotNull(players.find(player.uniqueId)?.eviction), token)
                    }
                }
                return
            }
            if (player.persistentDataContainer.get(evictionMarker, PersistentDataType.STRING) != eviction.id.toString()) {
                check(ProfileInventory.capture(player).hash() == eviction.beforeHash) { "Evicted inventory differs from checkpoint" }
                ProfileInventory().apply(player)
                player.persistentDataContainer.set(evictionMarker, PersistentDataType.STRING, eviction.id.toString())
                player.saveData()
            }
            players.update(player.uniqueId) { check(it.eviction?.id == eviction.id); it.copy(eviction = null, activeAccount = null, inventoryDetached = true) }
            storage.flushAsync().whenComplete { _, error ->
                if (error == null && storage.generation == generation) FoliaTasks.entity(plugin, player) {
                    InventoryGuard.release(player.uniqueId, token); hub.teleport(player); locales.send(player, Lang.PROFILE_EVICTED)
                }
            }
        } catch (e: Exception) {
            runCatching { players.update(player.uniqueId) { it.copy(eviction = it.eviction?.copy(review = true)) }; storage.flushAsync() }
            locales.send(player, Lang.PROFILES_REVIEW)
            plugin.logger.log(java.util.logging.Level.SEVERE, "Profile eviction ${eviction.id} requires review", e)
        }
    }
    fun admin(sender: CommandSender, args: List<String>) {
        if (!sender.isOp || sender is Player || !sender.hasPermission("oneblock.admin.manage")) { locales.send(sender, Lang.NO_PERMISSION); return }
        if (args.firstOrNull() == "review") {
            sender.sendMessage(storage.loadProfiles().flatMap { p -> listOfNotNull(
                p.profileSwitch?.let { "${displayPlayerName(p.uniqueId) ?: p.uniqueId} ${it.id} review=${it.review}" },
                p.eviction?.let { "${displayPlayerName(p.uniqueId) ?: p.uniqueId} ${it.id} eviction review=${it.review}" }) }.joinToString("\n").ifEmpty { "No pending profile switches" }); return
        }
        val actor = args.getOrNull(1)?.let { runCatching { UUID.fromString(it) }.getOrNull() ?: resolvePlayerName(it) }
        if (args.size != 4 || args[0] != "resolve" || actor == null || args[2] !in setOf("applied", "abort") || args[3] != "CONFIRM") {
            sender.sendMessage("/ob admin profiles review | resolve <player-name> <applied|abort> CONFIRM"); return
        }
        if (plugin.server.getPlayer(actor) != null) { sender.sendMessage("Player must be offline; inspect playerdata and the switch marker before resolving."); return }
        val profile = players.find(actor)
        val intent = profile?.profileSwitch
        val eviction = profile?.eviction
        if (intent == null && eviction == null) { sender.sendMessage("No pending switch"); return }
        if (eviction != null && args[2] != "applied") { sender.sendMessage("Eviction escrow already exists. Verify cleared playerdata; only applied is supported."); return }
        if (!resolving.add(actor)) { sender.sendMessage("Resolution is already in progress"); return }
        FoliaTasks.async(plugin) {
            runCatching {
                val backup = storage.backup()
                check(plugin.server.getPlayer(actor) == null) { "Player joined during backup" }
                if (eviction != null) players.update(actor) { check(it.eviction?.id == eviction.id); it.copy(eviction = null, activeAccount = null, inventoryDetached = true) }
                else if (args[2] == "applied") finish(actor, requireNotNull(intent)) else abort(actor, requireNotNull(intent))
                check(storage.flush())
                InventoryGuard.release(actor)
                sender.sendMessage("Profile switch resolved; backup ${backup.name}")
            }.onFailure { sender.sendMessage("Profile resolution failed: ${it.javaClass.simpleName}") }
            resolving.remove(actor)
        }
    }
    @EventHandler fun login(event: AsyncPlayerPreLoginEvent) {
        if (event.uniqueId in resolving) event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_OTHER, Colors.colorize("Profile recovery is in progress. Please reconnect shortly."))
    }
    @EventHandler(priority = EventPriority.LOWEST) fun join(event: PlayerJoinEvent) {
        // A new entity must recover its durable journals instead of inheriting a retired entity's transient lock.
        val player = event.player
        InventoryGuard.release(player.uniqueId)
        if (!storage.healthy) return
        val token = InventoryGuard.acquireToken(player.uniqueId) ?: return
        val generation = storage.generation
        // Invalidate the offline checkpoint durably before this session can change inventory.
        // A crash during an online session must never leave a stale inventory available for salvage.
        players.update(player.uniqueId) { it.copy(profileCheckpoints = it.profileCheckpoints.orEmpty() - listOfNotNull(it.activeAccount).toSet()) }
        storage.flushAsync().whenComplete { _, error ->
            if (error == null && storage.generation == generation) FoliaTasks.entity(plugin, player,
                retired = { InventoryGuard.release(player.uniqueId, token) }) {
                InventoryGuard.release(player.uniqueId, token); recover(player)
            }
        }
    }
    @EventHandler(priority = EventPriority.MONITOR) fun quit(event: PlayerQuitEvent) {
        val player = event.player
        val state = players.find(player.uniqueId)
        if (storage.healthy && !InventoryGuard.busy(player.uniqueId) && state?.profileSwitch == null && state?.eviction == null) {
            state?.activeAccount?.let { account ->
                runCatching { val snapshot = ProfileInventory.capture(player)
                    player.saveData()
                    players.update(player.uniqueId) { it.copy(profileCheckpoints = it.profileCheckpoints.orEmpty() + (account to snapshot),
                        profileActivity = it.profileActivity.orEmpty() + (account to System.currentTimeMillis())) }; storage.flushAsync()
                }.onFailure { plugin.logger.warning("Could not checkpoint profile inventory for ${player.uniqueId}: ${it.javaClass.simpleName}") }
            }
        }
        if (players.find(event.player.uniqueId)?.profileSwitch?.review == true) InventoryGuard.release(event.player.uniqueId)
    }
    @EventHandler(priority = EventPriority.HIGHEST) fun click(event: InventoryClickEvent) {
        val holder = event.view.topInventory.holder as? Holder ?: return
        event.isCancelled = true
        val player = event.whoClicked as? Player ?: return
        if (holder.pending || holder.player != player.uniqueId || holder.source !== interfaces || holder.profileSettings?.let {it!==islands.profileSettings}==true || holder.account!=islands.findForPlayer(player.uniqueId)?.owner || InventoryGuard.busy(player.uniqueId) || event.click !in setOf(org.bukkit.event.inventory.ClickType.LEFT, org.bukkit.event.inventory.ClickType.RIGHT)) return
        val action = (if(event.isRightClick)holder.secondary[event.rawSlot]?:holder.actions[event.rawSlot] else holder.actions[event.rawSlot]) ?: return
        holder.pending=true
        FoliaTasks.entity(plugin, player,retired={holder.pending=false}) { try {if (holder.source === interfaces && (holder.profileSettings==null || holder.profileSettings===islands.profileSettings) && holder.account==islands.findForPlayer(player.uniqueId)?.owner && player.openInventory.topInventory === holder.contents && !player.isDead && !InventoryGuard.busy(player.uniqueId))
            com.alytanta.oneblock.storage.OperationContext.with(com.alytanta.oneblock.storage.OperationContext.Context(player.uniqueId, "PROFILE_MENU")) { action(player) }
        } finally {holder.pending=false} }
    }
    @EventHandler fun drag(event: InventoryDragEvent) { if (event.view.topInventory.holder is Holder) event.isCancelled = true }
}
