package com.alytanta.oneblock.oneblock

import com.alytanta.oneblock.island.IslandService
import com.alytanta.oneblock.locale.Lang
import com.alytanta.oneblock.locale.LocaleService
import com.alytanta.oneblock.util.FoliaTasks
import com.alytanta.oneblock.util.OwnedItems
import org.bukkit.inventory.ItemStack
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.block.BlockBreakEvent
import org.bukkit.event.block.BlockBurnEvent
import org.bukkit.event.block.BlockExplodeEvent
import org.bukkit.event.block.BlockFadeEvent
import org.bukkit.event.block.BlockFormEvent
import org.bukkit.event.block.BlockPlaceEvent
import org.bukkit.event.block.BlockPistonExtendEvent
import org.bukkit.event.block.BlockPistonRetractEvent
import org.bukkit.event.block.BlockFromToEvent
import org.bukkit.event.block.EntityBlockFormEvent
import org.bukkit.event.block.BlockPhysicsEvent
import org.bukkit.event.block.LeavesDecayEvent
import org.bukkit.event.block.BlockGrowEvent
import org.bukkit.event.entity.EntityChangeBlockEvent
import org.bukkit.event.entity.EntityExplodeEvent
import org.bukkit.plugin.java.JavaPlugin

class OneBlockListener(
    private val plugin: JavaPlugin,
    private val islands: IslandService,
    private val oneBlock: OneBlockService,
    private val locales: LocaleService,
    private val ownedItems: OwnedItems,
    private val inventoryStatus: com.alytanta.oneblock.player.InventoryStatus? = null,
    @Volatile var toolDamage: Int = 1,
) : Listener {
    var releaseBusy: (java.util.UUID) -> Boolean = { false }
    private val handled=java.util.Collections.newSetFromMap(java.util.WeakHashMap<BlockBreakEvent,Boolean>())

    @EventHandler(ignoreCancelled = true)
    fun onCorePhysics(event: BlockPhysicsEvent) {
        if (islands.findCoreAt(event.block.location) != null) event.isCancelled = true
    }

    @EventHandler(ignoreCancelled = true)
    fun onCoreDecay(event: LeavesDecayEvent) {
        if (islands.findCoreAt(event.block.location) != null) event.isCancelled = true
    }

    @EventHandler(ignoreCancelled = true)
    fun onCoreGrow(event: BlockGrowEvent) {
        if (islands.findCoreAt(event.block.location) != null) event.isCancelled = true
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onBlockBreak(event: BlockBreakEvent) {
        if(!synchronized(handled) { handled.add(event) })return
        if (com.alytanta.oneblock.market.InventoryGuard.busy(event.player.uniqueId)) { event.isCancelled = true; return }
        val block = event.block
        val island = islands.findAt(block.location)
        if (island == null) {
            if (islands.isIslandWorld(block.world)) event.isCancelled = true
            return
        }
        if(releaseBusy(island.owner)) {
            event.isCancelled=true
            FoliaTasks.entity(plugin,event.player) { locales.send(event.player,Lang.MARKET_BUSY) }
            return
        }

        // Core block cannot be broken by anyone except through the proper mining mechanic
        val coreIsland = islands.findCoreAt(block.location)
        if (coreIsland != null) {
            if (!islands.persistenceAvailable()) {
                event.isCancelled = true
                FoliaTasks.entity(plugin, event.player) { locales.send(event.player, Lang.MARKET_UNAVAILABLE) }
                return
            }
            // Only the owner or a co-op member can mine the core
            if (!islands.canBuild(coreIsland, event.player)) {
                event.isCancelled = true
                FoliaTasks.entity(plugin, event.player) { locales.send(event.player, Lang.CORE_PROTECTED) }
                return
            }
            // One cancelled vanilla break -> one shared progress update -> one recipient.
            event.isCancelled = true
            event.isDropItems = false
            // The server already worked out the ore's XP for this tool; a cancelled break would lose it.
            val experience = event.expToDrop
            event.expToDrop = 0
            val naturalDrops = block.getDrops(event.player.inventory.itemInMainHand,event.player)
            val silk = event.player.inventory.itemInMainHand.getEnchantmentLevel(org.bukkit.enchantments.Enchantment.SILK_TOUCH) > 0
            val leaves = org.bukkit.Tag.LEAVES.isTagged(block.type) && !silk
            val reward = com.alytanta.oneblock.storage.OperationContext.with(
                com.alytanta.oneblock.storage.OperationContext.Context(event.player.uniqueId, "CORE_BREAK",
                    details = mapOf("world" to block.world.key().asString(), "x" to block.x, "y" to block.y, "z" to block.z))) {
                oneBlock.regenerateCore(block, coreIsland, event.player.uniqueId, event.player.inventory.itemInMainHand.clone(),
                    event.player.gameMode == org.bukkit.GameMode.CREATIVE,
                    // Leaves often drop nothing in vanilla; the break still counts, and the loot stays vanilla.
                    naturalDrops.isNotEmpty() || leaves, if(silk) naturalDrops.firstOrNull()?.type else null,
                    event.player.gameMode == org.bukkit.GameMode.SURVIVAL)
            } ?: return
            if (experience > 0 && event.player.gameMode == org.bukkit.GameMode.SURVIVAL) event.player.giveExp(experience, true)
            if (toolDamage > 0) {
                event.player.damageItemStack(org.bukkit.inventory.EquipmentSlot.HAND, toolDamage)
                inventoryStatus?.slotChanged(event.player.inventory,event.player.inventory.heldItemSlot)
            }
            // Re-send after the cancelled vanilla break (also when the next block has the same type).
            val corePosition = block.location.clone()
            FoliaTasks.regionDelayed(plugin, corePosition, 1L) {
                val visible = corePosition.block.blockData
                val revision = islands.findByOwner(coreIsland.owner)?.core
                FoliaTasks.entity(plugin, event.player) {
                    if (event.player.world == corePosition.world && islands.findByOwner(coreIsland.owner)?.core == revision)
                        event.player.sendBlockChange(corePosition, visible)
                }
            }
            if (reward.amount <= 0) return
            val natural = if (leaves) naturalDrops.firstOrNull() ?: return else null
            val material = natural?.type ?: reward.material
            val amount = natural?.amount ?: reward.amount
            val player = event.player
            val recipient = player.uniqueId
            val dropLocation = block.location.clone()
            FoliaTasks.entity(
                plugin,
                player,
                retired = {
                    ownedItems.drop(dropLocation, ItemStack(material, amount), recipient)
                },
            ) {
                oneBlock.rareFind(player, material)
                if (!player.hasPermission("oneblock.magnet.core") || inventoryStatus?.full(player) == true || player.isDead || com.alytanta.oneblock.market.InventoryGuard.busy(recipient)) {
                    ownedItems.drop(dropLocation, ItemStack(material, amount), recipient)
                    return@entity
                }
                val overflow = com.alytanta.oneblock.menu.MenuInventory.add(player.inventory, ItemStack(material, amount))
                if (overflow.isNotEmpty()) {
                    overflow.forEach { ownedItems.drop(dropLocation, it, recipient) }
                }
                if (reward.notifyReward) locales.send(player, Lang.CORE_REWARD, "item" to material.name.lowercase(), "coins" to reward.coins)
            }
            return
        }

        // Non-core blocks: check protection
        if (!islands.canBuild(island, event.player)) {
            event.isCancelled = true
            FoliaTasks.entity(plugin, event.player) { locales.send(event.player, Lang.ISLAND_PROTECTED) }
        }
    }

    @EventHandler(ignoreCancelled = true)
    fun onBlockPlace(event: BlockPlaceEvent) {
        if (!islands.isIslandWorld(event.block.world)) return
        val island = islands.findAt(event.block.location) ?: return
        if(releaseBusy(island.owner)) { event.isCancelled=true;return }
        if (!islands.canBuild(island, event.player)) {
            event.isCancelled = true
            FoliaTasks.entity(plugin, event.player) { locales.send(event.player, Lang.ISLAND_PROTECTED) }
        }
        // Prevent placing blocks on the core
        if (islands.findCoreAt(event.block.location) != null) {
            event.isCancelled = true
        }
    }

    @EventHandler(ignoreCancelled = true)
    fun onBlockExplode(event: BlockExplodeEvent) {
        event.blockList().removeIf {
            islands.isIslandWorld(it.world)
        }
    }

    @EventHandler(ignoreCancelled = true)
    fun onEntityExplode(event: EntityExplodeEvent) {
        event.blockList().removeIf {
            islands.isIslandWorld(it.world)
        }
    }

    @EventHandler(ignoreCancelled = true)
    fun onFluidFlow(event: BlockFromToEvent) {
        if (islands.findCoreAt(event.toBlock.location) != null) {
            event.isCancelled = true
            return
        }
        if (islands.isIslandWorld(event.block.world)) {
            val source = islands.findAt(event.block.location)
            val destination = islands.findAt(event.toBlock.location)
            if (source == null || destination?.owner != source.owner) event.isCancelled = true
        }
    }

    @EventHandler(ignoreCancelled = true)
    fun onPistonExtend(event: BlockPistonExtendEvent) {
        if (islands.isIslandWorld(event.block.world)) {
            val island = islands.findAt(event.block.location)
            if (island == null || event.blocks.any { block ->
                    islands.findAt(block.location)?.owner != island.owner ||
                        islands.findAt(block.getRelative(event.direction).location)?.owner != island.owner
                }
            ) {
                event.isCancelled = true
                return
            }
        }
        // Prevent pistons from pushing the core block or pushing blocks into the core location
        if (event.blocks.any { block ->
                islands.findCoreAt(block.location) != null ||
                    islands.findCoreAt(block.getRelative(event.direction).location) != null
            }
        ) {
            event.isCancelled = true
        }
    }

    @EventHandler(ignoreCancelled = true)
    fun onPistonRetract(event: BlockPistonRetractEvent) {
        val direction = event.direction.oppositeFace
        if (islands.isIslandWorld(event.block.world)) {
            val island = islands.findAt(event.block.location)
            if (island == null || event.blocks.any { block ->
                    islands.findAt(block.location)?.owner != island.owner ||
                        islands.findAt(block.getRelative(direction).location)?.owner != island.owner
                }
            ) {
                event.isCancelled = true
                return
            }
        }
        if (event.blocks.any { block ->
                islands.findCoreAt(block.location) != null ||
                    islands.findCoreAt(block.getRelative(direction).location) != null
            }
        ) {
            event.isCancelled = true
        }
    }

    @EventHandler(ignoreCancelled = true)
    fun onBlockBurn(event: BlockBurnEvent) {
        if (islands.isIslandWorld(event.block.world) && islands.findAt(event.block.location) == null) {
            event.isCancelled = true
            return
        }
        if (islands.findCoreAt(event.block.location) != null) event.isCancelled = true
    }

    @EventHandler(ignoreCancelled = true)
    fun onEntityChangeBlock(event: EntityChangeBlockEvent) {
        if (islands.isIslandWorld(event.block.world) && islands.findAt(event.block.location) == null) {
            event.isCancelled = true
            return
        }
        if (islands.findCoreAt(event.block.location) != null) event.isCancelled = true
    }

    @EventHandler(ignoreCancelled = true)
    fun onBlockFade(event: BlockFadeEvent) {
        if (islands.isIslandWorld(event.block.world) && islands.findAt(event.block.location) == null) {
            event.isCancelled = true
            return
        }
        if (islands.findCoreAt(event.block.location) != null) event.isCancelled = true
    }

    @EventHandler(ignoreCancelled = true)
    fun onBlockForm(event: BlockFormEvent) {
        if (islands.isIslandWorld(event.block.world) && islands.findAt(event.block.location) == null) {
            event.isCancelled = true
            return
        }
        if (islands.findCoreAt(event.block.location) != null) event.isCancelled = true
    }

    @EventHandler(ignoreCancelled = true)
    fun onEntityBlockForm(event: EntityBlockFormEvent) {
        if (islands.isIslandWorld(event.block.world) && islands.findAt(event.block.location) == null) {
            event.isCancelled = true
            return
        }
        if (islands.findCoreAt(event.block.location) != null) event.isCancelled = true
    }
}
