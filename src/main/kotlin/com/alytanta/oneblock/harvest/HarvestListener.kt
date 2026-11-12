package com.alytanta.oneblock.harvest

import com.alytanta.oneblock.island.IslandService
import com.alytanta.oneblock.player.InventoryStatus
import com.alytanta.oneblock.menu.MenuInventory
import com.alytanta.oneblock.market.InventoryGuard
import com.alytanta.oneblock.util.FoliaTasks
import com.alytanta.oneblock.util.OwnedItems
import org.bukkit.GameMode
import org.bukkit.NamespacedKey
import org.bukkit.block.Block
import org.bukkit.enchantments.Enchantment
import org.bukkit.entity.Player
import org.bukkit.entity.FallingBlock
import org.bukkit.event.*
import org.bukkit.event.block.*
import org.bukkit.event.entity.*
import org.bukkit.persistence.PersistentDataType
import org.bukkit.plugin.Plugin
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** Persistent chunk marks survive restarts; natural crop growth remains harvestable. */
class HarvestListener(private val plugin: Plugin, private val islands: IslandService, private val harvest: HarvestService,
    private val inventory: InventoryStatus, private val owned: OwnedItems) : Listener {
    private data class Key(val world: String, val x: Int, val y: Int, val z: Int, val player: UUID)
    private data class Break(val silk: Boolean, val placed: Boolean, val account: UUID?, val grown: Boolean,
        val mature: Boolean, val tool: org.bukkit.inventory.ItemStack)
    var release: com.alytanta.oneblock.release.ReleaseRuntime? = null
    var releaseTools: com.alytanta.oneblock.release.ReleaseTools? = null
    private val pending = ConcurrentHashMap<Key, Break>()
    private val handledBreaks=java.util.Collections.newSetFromMap(java.util.WeakHashMap<BlockBreakEvent,Boolean>())
    private data class Resolved(val source: Break, val drops: List<org.bukkit.inventory.ItemStack>)
    private val resolved = ConcurrentHashMap<Key,Resolved>()
    private val falling = NamespacedKey(plugin, "placed_falling")
    private fun markKey(b: Block) = NamespacedKey(plugin, "placed_${b.x and 15}_${b.y + 2048}_${b.z and 15}")
    private fun marked(b: Block) = b.chunk.persistentDataContainer.has(markKey(b), PersistentDataType.BYTE)
    private fun grownKey(b: Block) = NamespacedKey(plugin,"grown_${b.x and 15}_${b.y+2048}_${b.z and 15}")
    private fun grown(b: Block) = b.chunk.persistentDataContainer.has(grownKey(b),PersistentDataType.BYTE)
    private fun grown(b: Block, value: Boolean) { if(value)b.chunk.persistentDataContainer.set(grownKey(b),PersistentDataType.BYTE,1.toByte()) else b.chunk.persistentDataContainer.remove(grownKey(b)) }
    private fun mark(b: Block, value: Boolean) {
        val pdc = b.chunk.persistentDataContainer
        if (value) pdc.set(markKey(b), PersistentDataType.BYTE, 1.toByte()) else pdc.remove(markKey(b))
    }
    private fun key(b: Block, p: Player) = Key(b.world.key().asString(),b.x,b.y,b.z,p.uniqueId)
    @EventHandler(priority=EventPriority.MONITOR, ignoreCancelled=true) fun place(e: BlockPlaceEvent) {
        mark(e.blockPlaced, true)
        grown(e.blockPlaced,false)
        if (e is BlockMultiPlaceEvent) e.replacedBlockStates.forEach { mark(it.block, true) }
        inventory.changed(e.player)
    }
    @EventHandler(priority=EventPriority.MONITOR, ignoreCancelled=true) fun breakBlock(e: BlockBreakEvent) {
        if(!synchronized(handledBreaks) { handledBreaks.add(e) })return
        val placed = marked(e.block); val grew=grown(e.block);mark(e.block, false);grown(e.block,false)
        if (!e.isDropItems || e.player.gameMode == GameMode.CREATIVE || islands.findCoreAt(e.block.location) != null) return
        val k = key(e.block,e.player)
        val crop=e.block.blockData as? org.bukkit.block.data.Ageable
        val island=islands.findAt(e.block.location)?.takeIf { e.player.gameMode==GameMode.SURVIVAL && islands.findForPlayer(e.player.uniqueId)?.owner==it.owner && islands.roleOf(it,e.player.uniqueId) in setOf(com.alytanta.oneblock.island.IslandRole.OWNER,com.alytanta.oneblock.island.IslandRole.MEMBER) }
        val value = Break(e.player.inventory.itemInMainHand.getEnchantmentLevel(Enchantment.SILK_TOUCH)>0,placed,island?.owner,grew,
            crop!=null && crop.age==crop.maximumAge,e.player.inventory.itemInMainHand.clone())
        pending[k] = value
        FoliaTasks.regionDelayed(plugin,e.block.location,1) { pending.remove(k,value) }
        inventory.changed(e.player)
    }
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true) fun drops(e: BlockDropItemEvent) {
        val value = pending.remove(key(e.block,e.player)) ?: return
        val resolvedKey=key(e.block,e.player)
        val source=Resolved(value,e.items.map { it.itemStack.clone() })
        resolved[resolvedKey]=source
        FoliaTasks.regionDelayed(plugin,e.block.location,1) { resolved.remove(resolvedKey,source) }
        if (islands.persistenceAvailable()) harvest.credit(e.player.uniqueId,e.blockState.type,
            e.items.map { it.itemStack.type to it.itemStack.amount },value.silk,value.placed)
        if (!e.player.hasPermission("oneblock.magnet.blocks") || inventory.full(e.player) || InventoryGuard.busy(e.player.uniqueId)) return
        // Transfer the event's actual stacks, never synthesize a material from the broken block.
        val iter = e.items.iterator()
        while(iter.hasNext()) {
            val entity = iter.next()
            val remaining = MenuInventory.add(e.player.inventory,entity.itemStack)
            if (remaining.isEmpty()) { entity.remove(); iter.remove() } else entity.itemStack = remaining.single()
        }
    }
    @EventHandler(priority=EventPriority.MONITOR,ignoreCancelled=true) fun releaseDrops(e: BlockDropItemEvent) {
        val source=resolved.remove(key(e.block,e.player)) ?: return
        val value=source.source
        if(value.account==null)return
        val all=source.drops.toMutableList()
        release?.harvest(e.player,value.account,e.blockState.type,value.silk,value.placed,value.grown,value.mature,all,value.tool)
        all.drop(source.drops.size).forEach { stack -> owned.drop(e.block.location.clone(),stack,e.player.uniqueId) }
        replant(e,value)
    }
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true) fun death(e: EntityDeathEvent) {
        // Instance combat has one durable reward producer. Never let the magnet race drop suppression.
        if(e.entity.world.key().namespace()=="oneblock_dungeons")return
        if (e.entity is Player) return
        val player = e.entity.killer ?: return
        if (!player.hasPermission("oneblock.magnet.mobs")) return
        val stacks = e.drops.map { it.clone() }; e.drops.clear()
        val location = e.entity.location.clone()
        FoliaTasks.entity(plugin,player,retired={ stacks.forEach { owned.drop(location,it,player.uniqueId) } }) {
            stacks.forEach { stack ->
                if (player.isDead || InventoryGuard.busy(player.uniqueId) || inventory.full(player)) owned.drop(location,stack,player.uniqueId)
                else MenuInventory.add(player.inventory,stack).forEach { owned.drop(location,it,player.uniqueId) }
            }
        }
    }
    private fun move(blocks: List<Block>, face: org.bukkit.block.BlockFace) {
        val values = blocks.map { it to marked(it) }
        val growth=blocks.map { it to grown(it) }
        values.forEach { mark(it.first,false) }
        values.forEach { (block, value) -> mark(block.getRelative(face),value) }
        growth.forEach { grown(it.first,false) };growth.forEach { (block,value)->grown(block.getRelative(face),value) }
    }
    @EventHandler(priority=EventPriority.MONITOR,ignoreCancelled=true) fun extend(e: BlockPistonExtendEvent) { move(e.blocks,e.direction) }
    @EventHandler(priority=EventPriority.MONITOR,ignoreCancelled=true) fun retract(e: BlockPistonRetractEvent) { move(e.blocks,e.direction.oppositeFace) }
    @EventHandler(priority=EventPriority.MONITOR,ignoreCancelled=true) fun fall(e: EntityChangeBlockEvent) {
        val entity = e.entity as? FallingBlock ?: return
        if (e.to.isAir) { if (marked(e.block)) entity.persistentDataContainer.set(falling,PersistentDataType.BYTE,1.toByte()); mark(e.block,false) }
        else mark(e.block,entity.persistentDataContainer.has(falling,PersistentDataType.BYTE))
    }
    @EventHandler(priority=EventPriority.MONITOR,ignoreCancelled=true) fun explode(e: BlockExplodeEvent) { e.blockList().forEach { mark(it,false) } }
    @EventHandler(priority=EventPriority.MONITOR,ignoreCancelled=true) fun explodeEntity(e: EntityExplodeEvent) { e.blockList().forEach { mark(it,false) } }
    @EventHandler(priority=EventPriority.MONITOR,ignoreCancelled=true) fun grow(e: org.bukkit.event.world.StructureGrowEvent) {
        e.blocks.forEach { mark(it.block,false);grown(it.block,com.alytanta.oneblock.release.ReleaseRules.treeLog(it.type)) }
    }
    /** BlockDropItemEvent runs in the owning region. Require that the same region also owns the player;
     * keep seed consumption and the protected one-block placement synchronous, with no scheduler gap. */
    private fun replant(e: BlockDropItemEvent, value: Break) {
        if(!value.mature || e.player.gameMode!=GameMode.SURVIVAL)return
        val rule=releaseTools?.recipe(e.player,value.tool) ?: return
        if(e.blockState.type.name !in rule.replant)return
        val location=e.block.location.clone();val player=e.player;val type=e.blockState.type
        val seed=when(type) { org.bukkit.Material.WHEAT->org.bukkit.Material.WHEAT_SEEDS;org.bukkit.Material.CARROTS->org.bukkit.Material.CARROT;org.bukkit.Material.POTATOES->org.bukkit.Material.POTATO;else->return }
        if(!org.bukkit.Bukkit.isOwnedByCurrentRegion(player) || !org.bukkit.Bukkit.isOwnedByCurrentRegion(location))return
        run {
                if(player.isDead || InventoryGuard.busy(player.uniqueId) ||
                    islands.findForPlayer(player.uniqueId)?.owner!=value.account || releaseTools?.recipe(player,player.inventory.itemInMainHand)?.id!=rule.id ||
                    !location.block.type.isAir || location.block.getRelative(org.bukkit.block.BlockFace.DOWN).type!=org.bukkit.Material.FARMLAND ||
                    !islands.canBuild(islands.findByOwner(value.account!!) ?: return,player.uniqueId))return
                val slot=player.inventory.storageContents.indices.firstOrNull { slot -> val item=player.inventory.getItem(slot)
                    slot!=MenuInventory.SLOT && item?.type==seed && !item.hasItemMeta() && item.amount>0 } ?: return
                // Fire the standard placement protection hook before consuming the planting item.
                val prior=location.block.state
                val planting=player.inventory.getItem(slot)!!.clone().apply { amount=1 }
                val check=BlockPlaceEvent(location.block,prior,location.block.getRelative(org.bukkit.block.BlockFace.DOWN),planting,player,true,org.bukkit.inventory.EquipmentSlot.HAND)
                plugin.server.pluginManager.callEvent(check)
                if(check.isCancelled || !check.canBuild() || !location.block.type.isAir || location.block.getRelative(org.bukkit.block.BlockFace.DOWN).type!=org.bukkit.Material.FARMLAND)return
                val item=player.inventory.getItem(slot) ?: return
                if(item.type!=seed||item.hasItemMeta()||item.amount<1)return
                if(item.amount==1)player.inventory.setItem(slot,null) else item.amount-=1
                location.block.setType(type,false);mark(location.block,true)
        }
    }
    @EventHandler(priority=EventPriority.MONITOR,ignoreCancelled=true) fun fade(e: BlockFadeEvent) { mark(e.block,false) }
    @EventHandler(priority=EventPriority.MONITOR,ignoreCancelled=true) fun burn(e: BlockBurnEvent) { mark(e.block,false) }
}
