package com.alytanta.oneblock.island

import com.alytanta.oneblock.util.FoliaTasks
import com.alytanta.oneblock.util.OwnedItems
import org.bukkit.Location
import org.bukkit.entity.Player
import org.bukkit.entity.Projectile
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.block.BlockIgniteEvent
import org.bukkit.event.block.BlockPlaceEvent
import org.bukkit.event.block.BlockMultiPlaceEvent
import org.bukkit.event.entity.EntityDamageByEntityEvent
import org.bukkit.event.entity.EntityPickupItemEvent
import org.bukkit.event.entity.ProjectileLaunchEvent
import org.bukkit.event.entity.EntityPortalEvent
import org.bukkit.event.hanging.HangingBreakByEntityEvent
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.inventory.InventoryDragEvent
import org.bukkit.event.inventory.InventoryOpenEvent
import org.bukkit.event.player.*
import org.bukkit.event.vehicle.VehicleEnterEvent
import org.bukkit.event.vehicle.VehicleDamageEvent
import org.bukkit.event.vehicle.VehicleMoveEvent
import org.bukkit.plugin.Plugin

class IslandProtectionListener(private val plugin: Plugin, private val islands: IslandService, private val ownedItems: OwnedItems) : Listener {
    private fun denied(player: Player, location: Location): Boolean {
        if (location.world?.let(islands::isIslandWorld) != true) return false
        val island = islands.findAt(location) ?: return true
        return !islands.canBuild(island, player)
    }

    private fun outside(location: Location): Boolean {
        val island = location.world?.let(islands::findByWorld) ?: return false
        return !islands.bounds(island).contains(location.x, location.z, 0.3)
    }

    private fun refresh(player: Player) {
        val island = islands.findByWorld(player.world)
        if (island != null && !islands.canVisit(island, player)) { islands.deniedVisit?.invoke(player); return }
        islands.applyPlayerBorder(player, island)
        if (island != null && outside(player.location)) player.teleportAsync(islands.homeLocation(island, player.world))
    }

    @EventHandler fun onChangedWorld(event: PlayerChangedWorldEvent) {
        FoliaTasks.entity(plugin, event.player) { refresh(event.player) }
    }

    @EventHandler fun onJoin(event: PlayerJoinEvent) {
        FoliaTasks.entity(plugin, event.player) { refresh(event.player) }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onMove(event: PlayerMoveEvent) {
        if (event is PlayerTeleportEvent) return
        if (outside(event.to)) {
            event.isCancelled = true
            if (outside(event.from)) FoliaTasks.entity(plugin, event.player) { refresh(event.player) }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onTeleport(event: PlayerTeleportEvent) {
        val to = event.to
        val target = to.world?.let(islands::findByWorld)
        if (target != null && !islands.canVisit(target, event.player)) { event.isCancelled = true; return }
        if (target != null && islands.underMaintenance(target.owner)) { event.isCancelled = true; return }
        val portal = event.cause in setOf(PlayerTeleportEvent.TeleportCause.NETHER_PORTAL,
            PlayerTeleportEvent.TeleportCause.END_PORTAL, PlayerTeleportEvent.TeleportCause.END_GATEWAY)
        if (outside(to) || (portal && (event.from.world?.let(islands::isIslandWorld) == true || to.world?.let(islands::isIslandWorld) == true))) {
            event.isCancelled = true
        }
    }

    // PlayerPortalEvent has its own HandlerList; Bukkit does not dispatch it to onTeleport automatically.
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onPortal(event: PlayerPortalEvent) = onTeleport(event)

    @EventHandler(ignoreCancelled = true)
    fun onEntityPortal(event: EntityPortalEvent) {
        if (event.from.world?.let(islands::isIslandWorld) == true || event.to?.world?.let(islands::isIslandWorld) == true) event.isCancelled = true
    }

    @EventHandler fun onRespawn(event: PlayerRespawnEvent) {
        val location = event.respawnLocation
        val island = location.world?.let(islands::findByWorld) ?: return
        if (!islands.canVisit(island, event.player)) FoliaTasks.entity(plugin, event.player) { islands.deniedVisit?.invoke(event.player) }
        if (outside(location)) event.respawnLocation = islands.homeLocation(island, location.world!!)
    }

    @EventHandler(ignoreCancelled = true) fun onPlace(event: BlockPlaceEvent) {
        if (denied(event.player, event.block.location)) event.isCancelled = true
        if (event is BlockMultiPlaceEvent && event.replacedBlockStates.any {
            denied(event.player, it.location) || islands.findCoreAt(it.location) != null
        }) event.isCancelled = true
    }

    @EventHandler(ignoreCancelled = true) fun onInteract(event: PlayerInteractEvent) {
        val block = event.clickedBlock ?: return
        // Includes PHYSICAL (farmland), left-click extinguishing and right-click containers.
        if (denied(event.player, block.location)) {
            event.setUseInteractedBlock(org.bukkit.event.Event.Result.DENY)
            if(!com.alytanta.oneblock.combat.WindSpells.wand(event.item))event.setUseItemInHand(org.bukkit.event.Event.Result.DENY)
        }
    }

    @EventHandler(ignoreCancelled = true) fun onEmpty(event: PlayerBucketEmptyEvent) {
        if (denied(event.player, event.block.location)) event.isCancelled = true
    }

    @EventHandler(ignoreCancelled = true) fun onFill(event: PlayerBucketFillEvent) {
        if (denied(event.player, event.block.location)) event.isCancelled = true
    }

    @EventHandler(ignoreCancelled = true) fun onEntityInteract(event: PlayerInteractEntityEvent) {
        if (denied(event.player, event.rightClicked.location)) event.isCancelled = true
    }

    @EventHandler(ignoreCancelled = true) fun onEntityInteractAt(event: PlayerInteractAtEntityEvent) {
        if (denied(event.player, event.rightClicked.location)) event.isCancelled = true
    }

    @EventHandler(ignoreCancelled = true) fun onArmorStand(event: PlayerArmorStandManipulateEvent) {
        if (denied(event.player, event.rightClicked.location)) event.isCancelled = true
    }

    @EventHandler(ignoreCancelled = true) fun onShear(event: PlayerShearEntityEvent) {
        if (denied(event.player, event.entity.location)) event.isCancelled = true
    }

    @EventHandler(ignoreCancelled = true) fun onHarvest(event: PlayerHarvestBlockEvent) {
        if (denied(event.player, event.harvestedBlock.location)) event.isCancelled = true
    }

    @EventHandler(ignoreCancelled = true) fun onOpen(event: InventoryOpenEvent) {
        val player = event.player as? Player ?: return
        val location = event.inventory.location ?: return
        if (denied(player, location)) event.isCancelled = true
    }

    @EventHandler(ignoreCancelled = true) fun onInventoryClick(event: InventoryClickEvent) {
        val player = event.whoClicked as? Player ?: return
        val location = event.view.topInventory.location ?: return
        if (denied(player, location)) event.isCancelled = true
    }

    @EventHandler(ignoreCancelled = true) fun onInventoryDrag(event: InventoryDragEvent) {
        val player = event.whoClicked as? Player ?: return
        val location = event.view.topInventory.location ?: return
        if (denied(player, location)) event.isCancelled = true
    }

    @EventHandler(ignoreCancelled = true) fun onDamage(event: EntityDamageByEntityEvent) {
        val island = islands.findByWorld(event.entity.world) ?: return
        val attacker = (event.damager as? Player) ?: ((event.damager as? Projectile)?.shooter as? Player) ?: return
        if (event.entity is Player || !islands.canBuild(island, attacker) || !islands.isInsideIsland(island, event.entity.location)) event.isCancelled = true
    }

    @EventHandler(ignoreCancelled = true) fun onHanging(event: HangingBreakByEntityEvent) {
        val island = islands.findByWorld(event.entity.world) ?: return
        val player = (event.remover as? Player) ?: ((event.remover as? Projectile)?.shooter as? Player)
        if (player == null || !islands.canBuild(island, player)) event.isCancelled = true
    }

    @EventHandler(ignoreCancelled = true) fun onProjectile(event: ProjectileLaunchEvent) {
        if(com.alytanta.oneblock.combat.WindSpells.marked(event.entity))return // Cosmetic carrier cannot interact with terrain; damage still uses island protection.
        val island = islands.findByWorld(event.entity.world) ?: return
        val player = event.entity.shooter as? Player ?: return
        if (!islands.canBuild(island, player)) event.isCancelled = true
    }

    @EventHandler(ignoreCancelled = true) fun onPickup(event: EntityPickupItemEvent) {
        val player = event.entity as? Player ?: return
        val source = islands.findByWorld(event.item.world)
        if (source != null && islands.findForPlayer(player.uniqueId)?.owner != source.owner) { event.isCancelled = true; return }
        if (!ownedItems.belongsTo(event.item, player.uniqueId) && denied(player, event.item.location)) event.isCancelled = true
    }

    @EventHandler(ignoreCancelled = true) fun onDrop(event: PlayerDropItemEvent) {
        if (denied(event.player, event.player.location)) event.isCancelled = true
    }

    @EventHandler(ignoreCancelled = true) fun onIgnite(event: BlockIgniteEvent) {
        val player = event.player ?: return
        if (denied(player, event.block.location)) event.isCancelled = true
    }

    @EventHandler(ignoreCancelled = true) fun onVehicleEnter(event: VehicleEnterEvent) {
        val player = event.entered as? Player ?: return
        if (denied(player, event.vehicle.location)) event.isCancelled = true
    }

    @EventHandler(ignoreCancelled = true) fun onVehicleDamage(event: VehicleDamageEvent) {
        val island = islands.findByWorld(event.vehicle.world) ?: return
        val player = (event.attacker as? Player) ?: ((event.attacker as? Projectile)?.shooter as? Player) ?: return
        if (!islands.canBuild(island, player) || !islands.isInsideIsland(island, event.vehicle.location)) event.isCancelled = true
    }

    @EventHandler(ignoreCancelled = true) fun onVehicleDestroy(event: org.bukkit.event.vehicle.VehicleDestroyEvent) {
        val island = islands.findByWorld(event.vehicle.world) ?: return
        val player = (event.attacker as? Player) ?: ((event.attacker as? Projectile)?.shooter as? Player) ?: return
        if (!islands.canBuild(island, player) || !islands.isInsideIsland(island, event.vehicle.location)) event.isCancelled = true
    }

    @EventHandler fun onVehicleMove(event: VehicleMoveEvent) {
        if (!outside(event.to)) return
        val island = islands.findByWorld(event.vehicle.world) ?: return
        val safe = if (!outside(event.from)) event.from.clone() else islands.homeLocation(island, event.vehicle.world)
        val passengers = event.vehicle.passengers.toList()
        event.vehicle.eject()
        event.vehicle.velocity = org.bukkit.util.Vector(0, 0, 0)
        event.vehicle.teleportAsync(safe)
        passengers.forEach { passenger -> FoliaTasks.entity(plugin, passenger) { passenger.teleportAsync(safe) } }
    }
}
