package com.alytanta.oneblock.menu

import com.alytanta.oneblock.market.InventoryGuard
import com.alytanta.oneblock.util.FoliaTasks
import com.destroystokyo.paper.event.player.PlayerPostRespawnEvent
import com.destroystokyo.paper.event.player.PlayerRecipeBookClickEvent
import io.papermc.paper.threadedregions.scheduler.ScheduledTask
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.block.BlockBreakEvent
import org.bukkit.event.block.BlockPlaceEvent
import org.bukkit.event.block.Action
import org.bukkit.event.entity.EntityDamageByEntityEvent
import org.bukkit.event.entity.EntityPickupItemEvent
import org.bukkit.event.entity.PlayerDeathEvent
import org.bukkit.event.inventory.*
import org.bukkit.event.player.*
import org.bukkit.inventory.*
import org.bukkit.plugin.Plugin
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** Owns the utility slot on the player's Folia scheduler, including recovery after inventory replacement. */
class ReservedMenuSlot(
    private val plugin: Plugin,
    private val isMenu: (ItemStack?) -> Boolean,
    private val createItem: (Player) -> ItemStack,
    private val overflow: (Player, ItemStack) -> Unit,
    private val open: (Player) -> Unit,
) : Listener {
    private class Watch { var task: ScheduledTask? = null }
    private val watches = ConcurrentHashMap<UUID, Watch>()
    private val pending = ConcurrentHashMap.newKeySet<UUID>()

    fun ensureItem(player: Player) {
        if (!player.isOnline || player.isDead || InventoryGuard.busy(player.uniqueId)) return
        val leftovers = MenuInventory.repair(player.inventory, createItem(player), isMenu)
        if (isMenu(player.itemOnCursor)) player.setItemOnCursor(null)
        leftovers.forEach { overflow(player, it) }
    }

    fun watch(player: Player) {
        val watch = Watch()
        watches.put(player.uniqueId, watch)?.task?.cancel()
        ensureItem(player)
        watch.task = player.scheduler.runAtFixedRate(plugin, { ensureItem(player) }, {
            watches.remove(player.uniqueId, watch)
        }, 1L, 10L)
        if (watch.task == null) watches.remove(player.uniqueId, watch)
    }

    fun close() {
        watches.values.forEach { it.task?.cancel() }
        watches.clear()
        pending.clear()
    }

    /** Inventory events must finish before displaced items/cursors are repaired. Coalesce same-tick events. */
    private fun repairSoon(player: Player) {
        if (!pending.add(player.uniqueId)) return
        FoliaTasks.entity(plugin, player, retired = { pending.remove(player.uniqueId) }) {
            pending.remove(player.uniqueId)
            ensureItem(player)
        }
    }

    private fun validSlot(player: Player): Boolean = player.inventory.getItem(MenuInventory.SLOT).let {
        isMenu(it) && it?.amount == 1
    }
    private fun selected(player: Player) = player.inventory.heldItemSlot == MenuInventory.SLOT
    private fun reserved(inventory: Inventory?, slot: Int, player: Player) =
        inventory is PlayerInventory && inventory.holder?.uniqueId == player.uniqueId && slot == MenuInventory.SLOT

    // Market recovery reads the saved inventory hash first. Do not rewrite it during the join event.
    @EventHandler(priority = EventPriority.MONITOR)
    fun onJoin(event: PlayerJoinEvent) {
        event.player.scheduler.runDelayed(plugin, { watch(event.player) }, null, 2L)
    }
    @EventHandler fun onRespawn(event: PlayerPostRespawnEvent) = watch(event.player)
    @EventHandler fun onWorld(event: PlayerChangedWorldEvent) = watch(event.player)
    @EventHandler fun onQuit(event: PlayerQuitEvent) {
        watches.remove(event.player.uniqueId)?.task?.cancel()
        pending.remove(event.player.uniqueId)
    }
    @EventHandler fun onRespawning(event: PlayerRespawnEvent) = repairSoon(event.player)
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onDeath(event: PlayerDeathEvent) {
        event.drops.removeIf(isMenu)
        event.itemsToKeep.removeIf(isMenu)
    }

    @EventHandler(priority = EventPriority.LOWEST)
    fun onInteract(event: PlayerInteractEvent) {
        if (event.action == Action.PHYSICAL) return
        if (!selected(event.player) && !isMenu(event.item)) return
        event.isCancelled = true
        repairSoon(event.player)
        if (event.hand == EquipmentSlot.HAND && event.action.isRightClick) {
            FoliaTasks.entity(plugin, event.player) { ensureItem(event.player); if (!event.player.isDead) open(event.player) }
        }
    }
    @EventHandler(priority = EventPriority.LOWEST)
    fun onEntity(event: PlayerInteractEntityEvent) {
        if (selected(event.player) || isMenu(event.player.inventory.getItem(event.hand))) {
            event.isCancelled = true; repairSoon(event.player)
        }
    }
    // This subclass has its own HandlerList.
    @EventHandler(priority = EventPriority.LOWEST)
    fun onEntityAt(event: PlayerInteractAtEntityEvent) = onEntity(event)
    @EventHandler(priority = EventPriority.LOWEST)
    fun onStand(event: PlayerArmorStandManipulateEvent) {
        if (selected(event.player) || isMenu(event.playerItem) || isMenu(event.armorStandItem)) {
            event.isCancelled = true; repairSoon(event.player)
        }
    }
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    fun onMine(event: BlockBreakEvent) {
        if (selected(event.player) || isMenu(event.player.inventory.itemInMainHand)) {
            event.isCancelled = true; repairSoon(event.player)
        }
    }
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    fun onPlace(event: BlockPlaceEvent) {
        if (selected(event.player) || isMenu(event.itemInHand)) { event.isCancelled = true; repairSoon(event.player) }
    }
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    fun onDamage(event: EntityDamageByEntityEvent) {
        val player = event.damager as? Player ?: return
        if (selected(player) || isMenu(player.inventory.itemInMainHand)) { event.isCancelled = true; repairSoon(player) }
    }
    @EventHandler(priority = EventPriority.HIGHEST)
    fun onDrop(event: PlayerDropItemEvent) {
        if (isMenu(event.itemDrop.itemStack)) event.isCancelled = true
        repairSoon(event.player)
    }
    @EventHandler(priority = EventPriority.HIGHEST)
    fun onSwap(event: PlayerSwapHandItemsEvent) {
        if (selected(event.player) || isMenu(event.mainHandItem) || isMenu(event.offHandItem)) {
            event.isCancelled = true; repairSoon(event.player)
        }
    }
    @EventHandler fun onHeld(event: PlayerItemHeldEvent) = repairSoon(event.player)
    @EventHandler fun onOpen(event: InventoryOpenEvent) { (event.player as? Player)?.let(::repairSoon) }
    @EventHandler fun onClose(event: InventoryCloseEvent) { (event.player as? Player)?.let(::repairSoon) }

    @EventHandler(priority = EventPriority.LOWEST)
    fun onAttemptPickup(event: PlayerAttemptPickupItemEvent) {
        if (isMenu(event.item.itemStack)) { event.isCancelled = true; event.item.remove() }
        if (!validSlot(event.player)) { event.isCancelled = true; repairSoon(event.player) }
    }
    @EventHandler(priority = EventPriority.LOWEST)
    fun onPickup(event: EntityPickupItemEvent) {
        if (isMenu(event.item.itemStack)) { event.isCancelled = true; event.item.remove() }
        val player = event.entity as? Player ?: return
        if (!validSlot(player)) { event.isCancelled = true; repairSoon(player) }
    }
    @EventHandler(priority = EventPriority.LOWEST)
    fun onHopper(event: InventoryPickupItemEvent) {
        if (isMenu(event.item.itemStack)) { event.isCancelled = true; event.item.remove() }
    }
    @EventHandler(priority = EventPriority.LOWEST)
    fun onMove(event: InventoryMoveItemEvent) { if (isMenu(event.item)) event.isCancelled = true }

    // Auto-fill must never borrow the utility star. Other recipes continue normally; real stars can be crafted manually.
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onRecipe(event: PlayerRecipeBookClickEvent) {
        val choices = when (val recipe = plugin.server.getRecipe(event.recipe)) {
            is ShapedRecipe -> recipe.choiceMap.values.filterNotNull()
            is ShapelessRecipe -> recipe.choiceList
            else -> emptyList()
        }
        if (choices.any { it.test(createItem(event.player)) }) event.isCancelled = true
        repairSoon(event.player)
    }
    @EventHandler fun onCraft(event: PrepareItemCraftEvent) {
        if (event.inventory.matrix.any(isMenu)) event.inventory.result = null
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    fun onClick(event: InventoryClickEvent) {
        val player = event.whoClicked as? Player ?: return
        if (reserved(event.clickedInventory, event.slot, player) || event.hotbarButton == MenuInventory.SLOT ||
            isMenu(event.currentItem) || isMenu(event.cursor) ||
            (event.click == ClickType.SWAP_OFFHAND && isMenu(player.inventory.itemInOffHand))) {
            event.isCancelled = true
            // Remove utility copies left in external containers by an older version; never remove normal items.
            if (event.clickedInventory !is PlayerInventory && isMenu(event.currentItem)) event.currentItem = null
        }
        if(isMenu(event.currentItem) && event.clickedInventory is PlayerInventory && event.click in setOf(ClickType.LEFT,ClickType.RIGHT)) {
            event.isCancelled=true
            if(pending.add(player.uniqueId)) FoliaTasks.entity(plugin,player,retired={pending.remove(player.uniqueId)}) {
                pending.remove(player.uniqueId)
                ensureItem(player)
                if(player.isOnline && !player.isDead && !InventoryGuard.busy(player.uniqueId))open(player)
            }
        }
        repairSoon(player)
    }
    @EventHandler(priority = EventPriority.HIGHEST)
    fun onDrag(event: InventoryDragEvent) {
        val player = event.whoClicked as? Player ?: return
        if (isMenu(event.oldCursor) || event.newItems.values.any(isMenu) || event.rawSlots.any {
                reserved(event.view.getInventory(it), event.view.convertSlot(it), player)
            }) event.isCancelled = true
        repairSoon(player)
    }
}
