package com.alytanta.oneblock.market

import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.block.BlockBreakEvent
import org.bukkit.event.block.BlockPlaceEvent
import org.bukkit.event.entity.EntityPickupItemEvent
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.inventory.InventoryDragEvent
import org.bukkit.event.inventory.InventoryOpenEvent
import org.bukkit.event.entity.EntityDamageEvent
import org.bukkit.event.player.*
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** A short inventory handoff lock, never a permission or a remote-trading entitlement. */
object InventoryGuard : Listener {
    /** Admission policy only. Recovery tokens remain independent so persisted handoffs can always reconcile. */
    @Volatile var canStart:(UUID)->Boolean={true}
    @Volatile var interactionAllowed:(UUID)->Boolean={true}
    private val active = ConcurrentHashMap<UUID, UUID>()
    fun busy(player: UUID) = active.containsKey(player) || !interactionAllowed(player)
    fun owns(player: UUID, token: UUID) = active[player] == token
    fun acquireToken(player: UUID): UUID? = UUID.randomUUID().takeIf { active.putIfAbsent(player, it) == null }
    fun acquire(player: UUID) = acquireToken(player) != null
    fun release(player: UUID) { active.remove(player) }
    fun release(player: UUID, token: UUID) { active.remove(player, token) }
    @EventHandler(priority = EventPriority.HIGHEST) fun click(e: InventoryClickEvent) { if (busy(e.whoClicked.uniqueId)) e.isCancelled = true }
    @EventHandler(priority = EventPriority.HIGHEST) fun drag(e: InventoryDragEvent) { if (busy(e.whoClicked.uniqueId)) e.isCancelled = true }
    @EventHandler(priority = EventPriority.HIGHEST) fun open(e: InventoryOpenEvent) { if (busy(e.player.uniqueId)) e.isCancelled = true }
    @EventHandler(priority = EventPriority.HIGHEST) fun damage(e: EntityDamageEvent) {
        val source = (e as? org.bukkit.event.entity.EntityDamageByEntityEvent)?.damager
        val attacker = (source as? Player) ?: ((source as? org.bukkit.entity.Projectile)?.shooter as? Player)
        if (busy(e.entity.uniqueId) || attacker?.let { busy(it.uniqueId) } == true) e.isCancelled = true
    }
    @EventHandler(priority = EventPriority.HIGHEST) fun drop(e: PlayerDropItemEvent) { if (busy(e.player.uniqueId)) e.isCancelled = true }
    @EventHandler(priority = EventPriority.HIGHEST) fun pickup(e: EntityPickupItemEvent) { if (busy(e.entity.uniqueId)) e.isCancelled = true }
    @EventHandler(priority = EventPriority.HIGHEST) fun swap(e: PlayerSwapHandItemsEvent) { if (busy(e.player.uniqueId)) e.isCancelled = true }
    @EventHandler(priority = EventPriority.HIGHEST) fun interact(e: PlayerInteractEvent) { if (busy(e.player.uniqueId)) e.isCancelled = true }
    @EventHandler(priority = EventPriority.HIGHEST) fun entity(e: PlayerInteractEntityEvent) { if (busy(e.player.uniqueId)) e.isCancelled = true }
    @EventHandler(priority = EventPriority.HIGHEST) fun mine(e: BlockBreakEvent) { if (busy(e.player.uniqueId)) e.isCancelled = true }
    @EventHandler(priority = EventPriority.HIGHEST) fun place(e: BlockPlaceEvent) { if (busy(e.player.uniqueId)) e.isCancelled = true }
    @EventHandler(priority = EventPriority.HIGHEST) fun command(e: PlayerCommandPreprocessEvent) {
        val route=e.message.trim().lowercase().split(Regex("\\s+"))
        val root=route.first().substringAfter('/').substringAfter(':')
        val recovery=e.player.isOp&&e.player.hasPermission("oneblock.admin.reload")&&
            (root in setOf("obreload","oneblockreload","obrestart")||root in setOf("ob","oneblock")&&
                (route.getOrNull(1)=="reload"||route.getOrNull(1)=="admin"&&route.getOrNull(2)=="reload"))
        if (busy(e.player.uniqueId)&&!recovery) e.isCancelled = true
    }
}
