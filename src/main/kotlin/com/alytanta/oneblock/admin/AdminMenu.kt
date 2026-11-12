package com.alytanta.oneblock.admin

import com.alytanta.oneblock.island.IslandService
import com.alytanta.oneblock.locale.Lang
import com.alytanta.oneblock.locale.LocaleService
import com.alytanta.oneblock.oneblock.CoreMode
import com.alytanta.oneblock.util.Colors
import com.alytanta.oneblock.util.FoliaTasks
import com.alytanta.oneblock.market.MarketMenuLayouts
import com.alytanta.oneblock.market.MarketWindow
import com.alytanta.oneblock.menu.MenuItemSpec
import com.alytanta.oneblock.menu.style
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.inventory.InventoryDragEvent
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.InventoryHolder
import org.bukkit.inventory.ItemStack
import org.bukkit.plugin.Plugin
import java.util.UUID

class AdminMenu(
    private val plugin: Plugin,
    private val islands: IslandService,
    private val locales: LocaleService,
    private val admin: AdminController,
) : Listener {
    @Volatile var layouts = com.alytanta.oneblock.menu.InterfaceMenus.defaults()
    var dungeonOpen:((Player)->Unit)?=null
    var playerOpen:((Player)->Unit)?=null
    var dungeonLabel:()->String={"Dungeons"}
    var playerLabel:()->String={"Players"}
    private class Holder(val viewer: UUID, val source: MarketMenuLayouts, val layout: MarketWindow,
        val values: Map<String, String>) : InventoryHolder {
        lateinit var contents: Inventory
        val actions = mutableMapOf<String, (Player, Boolean) -> Unit>()
        val rendered = mutableMapOf<Int, String>()
        var pending=false
        fun bind(source:Int,action:(Player,Boolean)->Unit) {actions[layout.identity(source)]=action}
        override fun getInventory(): Inventory = contents
    }

    private fun inventory(player: Player, title: Lang, vararg args: Pair<String, Any>): Holder {
        val id = when (title) { Lang.ADMIN_MENU_TITLE -> "admin-list"; Lang.ADMIN_MENU_ISLAND -> "admin-island"; else -> "admin-members" }
        val source = layouts
        val layout = source.window(id, null)
        val values = args.associate { it.first to it.second.toString() } + ("viewer" to player.name)
        fun expand(text: String) = values.entries.fold(text) { value, (key, replacement) -> value.replace("{$key}", replacement) }
        val holder = Holder(player.uniqueId, source, layout, values)
        holder.contents = plugin.server.createInventory(holder, layout.rows * 9, Colors.title(expand(layout.title ?: locales.text(player, title, *args))))
        com.alytanta.oneblock.menu.GuiItems.background(plugin,player,holder.contents,layout,layout.filler)
        button(holder,1000,Material.BARRIER,locales.text(player,Lang.MARKET_CLOSE)) {it.closeInventory()}
        return holder
    }

    private fun button(holder: Holder, slot: Int, material: Material, label: String, lore: List<String> = emptyList(),
        extra: Map<String, Any> = emptyMap(), action: (Player) -> Unit) {
        val target = holder.layout.target(slot) ?: return
        val viewer=plugin.server.getPlayer(holder.viewer) ?: return
        val id=holder.layout.identity(slot)
        val permission=when(id) {"teleport","evacuate"->"teleport";"backup","save"->"save";"reset-progress","reset-world"->"reset"
            "previous","next","back","close","info","members","edit"->"inspect";else->if(id.startsWith("entry:") && holder.layout.id=="admin-list")"inspect" else "manage"}
        val permitted=viewer.hasPermission("oneblock.admin.$permission")
        val spec = holder.layout.style(slot, MenuItemSpec(material,label,if(permitted)lore else lore+locales.text(viewer,Lang.NO_PERMISSION)),if(permitted)"available" else "unavailable") ?: return
        val values = holder.values + extra.mapValues { it.value.toString() } + ("label" to label)
        fun expand(text: String) = values.entries.fold(text) { value, (key, replacement) -> value.replace("{$key}", replacement) }
        val item=com.alytanta.oneblock.menu.GuiItems.copy(plugin,viewer,ItemStack(spec.material,spec.amount),
            com.alytanta.oneblock.market.MarketMenuButton(name=expand(spec.name),lore=spec.lore.map(::expand),glow=spec.glow,
                customModelData=spec.customModel,texture=spec.texture,model=spec.model))
        holder.contents.setItem(target, item)
        if(!permitted)return
        holder.rendered[target] = holder.layout.identity(slot)
        holder.bind(slot) { viewer, _ -> action(viewer) }
    }

    fun openList(player: Player, page: Int = 0) {
        if (!player.isOp || !player.hasPermission("oneblock.admin.inspect")) return
        val all = islands.all().sortedBy { it.publicId }
        val source = layouts
        val grid = source.window("admin-list", null).grid("content", (0..44).toList())
        val last = ((all.size - 1).coerceAtLeast(0) / grid.size)
        val current = page.coerceIn(0, last)
        val holder = inventory(player, Lang.ADMIN_MENU_TITLE, "page" to (current + 1))
        if (holder.source !== source) return
        all.drop(current * grid.size).take(grid.size).forEachIndexed { index, island ->
            button(holder, grid[index], Material.GRASS_BLOCK, admin.name(island.owner), listOf(
                islands.profileName(island,locales.languageOf(player)),
                locales.text(player, Lang.ADMIN_MENU_SUMMARY, "breaks" to island.core.breaks, "coins" to island.coins),
            ), mapOf("name" to islands.profileName(island, locales.languageOf(player)), "id" to island.publicId.orEmpty(),
                "coins" to island.coins, "breaks" to island.core.breaks)) { openIsland(it, island.owner) }
        }
        button(holder, 45, Material.ARROW, "←") { openList(it, current - 1) }
        button(holder, 53, Material.ARROW, "→") { openList(it, current + 1) }
        button(holder, 49, Material.CHEST, locales.text(player, Lang.ADMIN_MENU_SAVE)) { admin.handle(it, listOf("save")) }
        dungeonOpen?.let {open->button(holder,48,Material.IRON_PICKAXE,dungeonLabel(),action=open)}
        playerOpen?.let {open->button(holder,50,Material.PLAYER_HEAD,playerLabel(),action=open)}
        player.openInventory(holder.contents)
    }

    fun openIsland(player: Player, owner: UUID) {
        if (!player.isOp || !player.hasPermission("oneblock.admin.inspect")) return
        val island = islands.findByOwner(owner) ?: return
        val holder = inventory(player, Lang.ADMIN_MENU_ISLAND, "player" to admin.name(owner))
        fun control(slot: Int, material: Material, label: Lang, vararg command: String) {
            button(holder, slot, material, locales.text(player, label)) { viewer ->
                viewer.closeInventory()
                admin.handle(viewer, command.toList())
            }
        }
        val id = owner.toString()
        fun adjust(slot: Int, material: Material, field: String, current: Long, step: Long, max: Long, min: Long = 0) {
            button(holder, slot, material, locales.text(player, Lang.ADMIN_MENU_ADJUST,
                "field" to field, "value" to current, "step" to step), extra = mapOf("field" to field, "value" to current, "step" to step)) { }
            holder.bind(slot) { viewer, decrease ->
                val next = if (decrease) (current - step).coerceAtLeast(min)
                    else if (current >= max - step) max else current + step
                admin.handle(viewer, listOf("set", id, field, next.toString()))
                openIsland(viewer, owner)
            }
        }
        control(10, Material.ENDER_PEARL, Lang.ADMIN_MENU_TP, "tp", id)
        control(12, Material.OAK_LOG, Lang.ADMIN_MENU_CORE, "respawn", id)
        control(14, Material.BOOK, Lang.ADMIN_MENU_INFO, "info", id)
        control(16, Material.CHEST, Lang.ADMIN_MENU_BACKUP, "backup", id)
        val nextMode = CoreMode.entries[(island.core.mode.ordinal + 1) % CoreMode.entries.size]
        button(holder, 21, Material.COMPASS, locales.text(player, Lang.ADMIN_MENU_MODE), listOf("${island.core.mode.id} → ${nextMode.id}"),
            mapOf("current" to island.core.mode.id, "next" to nextMode.id)) {
            admin.handle(it, listOf("set", id, "mode", nextMode.id)); openIsland(it, owner)
        }
        button(holder, 23, Material.GOLD_INGOT, locales.text(player, Lang.ADMIN_MENU_EDIT), listOf(
            "/ob admin island set ${admin.reference(owner)} <field> <value>", AdminEdits.fields.take(12).joinToString(", "),
        )) { admin.handle(it, listOf("info", id)) }
        adjust(19, Material.IRON_PICKAXE, "breaks", island.core.breaks, 10, Long.MAX_VALUE)
        adjust(20, Material.GOLD_NUGGET, "coins", island.coins, 100, 1_000_000_000_000L)
        val size = islands.worldSize(island)
        button(holder, 24, Material.GLASS, locales.text(player, Lang.ADMIN_MENU_ADJUST,
            "field" to "world-size", "value" to size, "step" to 10), extra = mapOf("field" to "world-size", "value" to size, "step" to 10)) { }
        holder.bind(24) { viewer, decrease ->
            val next = (size + if (decrease) -10 else 10).coerceIn(16.0, 59_999_968.0)
            admin.handle(viewer, listOf("set", id, "world-size", next.toString()))
            openIsland(viewer, owner)
        }
        button(holder, 30, Material.PLAYER_HEAD, locales.text(player, Lang.ADMIN_MENU_MEMBERS)) { openMembers(it, owner) }
        control(28, Material.OAK_DOOR, Lang.ADMIN_MENU_EVACUATE, "evacuate", id)
        control(32, Material.REDSTONE, Lang.ADMIN_MENU_RESET_PROGRESS, "reset-progress", id)
        control(34, Material.TNT, Lang.ADMIN_MENU_RESET_WORLD, "reset-world", id)
        button(holder, 49, Material.ARROW, "←") { openList(it) }
        player.openInventory(holder.contents)
    }

    private fun openMembers(player: Player, owner: UUID, page: Int = 0) {
        val island = islands.findByOwner(owner) ?: return
        val all = island.members.keys.sortedBy(UUID::toString)
        val holder = inventory(player, Lang.ADMIN_MENU_MEMBERS)
        val grid = holder.layout.grid("content", (0..44).toList())
        val current = page.coerceIn(0, (all.size - 1).coerceAtLeast(0) / grid.size)
        all.drop(current * grid.size).take(grid.size).forEachIndexed { index, member ->
            val slot = grid[index]
            button(holder, slot, Material.PLAYER_HEAD, admin.name(member), listOf(locales.text(player, Lang.ADMIN_MENU_REMOVE)), mapOf("name" to admin.name(member))) {
                admin.handle(it, listOf("member", owner.toString(), member.toString(), "remove"))
                openMembers(it, owner, current)
            }
        }
        button(holder, 45, Material.ARROW, "←") { openMembers(it, owner, current - 1) }
        button(holder, 53, Material.ARROW, "→") { openMembers(it, owner, current + 1) }
        button(holder, 49, Material.BOOK, "/ob admin island member <нік[#профіль]> <нік> add") { openIsland(it, owner) }
        player.openInventory(holder.contents)
    }

    fun applyLayouts(next: MarketMenuLayouts) {
        layouts = next
        plugin.server.onlinePlayers.forEach { player -> FoliaTasks.entity(plugin, player) {
            if (player.openInventory.topInventory.holder is Holder) player.closeInventory()
        } }
    }

    @EventHandler fun onClick(event: InventoryClickEvent) {
        val holder = event.view.topInventory.holder as? Holder ?: return
        event.isCancelled = true // Also rejects shift-click, hotbar swap, double-click and bottom inventory actions.
        val player = event.whoClicked as? Player ?: return
        if (holder.pending || holder.viewer != player.uniqueId || holder.source !== layouts || !player.isOp || !player.hasPermission("oneblock.admin.inspect") ||
            event.click !in setOf(org.bukkit.event.inventory.ClickType.LEFT, org.bukkit.event.inventory.ClickType.RIGHT)) return
        val action = holder.actions[holder.rendered[event.rawSlot] ?: return] ?: return
        holder.pending=true
        val rightClick = event.isRightClick
        // Opening/closing another inventory during InventoryClickEvent is not supported.
        FoliaTasks.entity(plugin, player,retired={holder.pending=false}) {try {
            if (holder.source === layouts && player.isOp && !player.isDead && player.openInventory.topInventory === holder.contents && player.hasPermission("oneblock.admin.inspect")) action(player, rightClick)
        } finally {holder.pending=false} }
    }

    @EventHandler fun onDrag(event: InventoryDragEvent) {
        if (event.view.topInventory.holder is Holder) event.isCancelled = true
    }
}
