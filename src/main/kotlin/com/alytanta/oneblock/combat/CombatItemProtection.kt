package com.alytanta.oneblock.combat

import org.bukkit.event.*
import org.bukkit.event.inventory.*
import org.bukkit.event.block.*
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.event.block.Action
import org.bukkit.inventory.*

/** Vanilla recipes compare carrier materials and may rebuild an ItemStack without our identity. */
class CombatItemProtection(private val rules:()->CombatRules?):Listener {
    companion object {
        internal fun mayRepair(base:String?,second:String?,result:String?,sameMaterial:Boolean)=base!=null && base==result && (second==null || second==base) && sameMaterial
    }
    private fun denied(inventory:Inventory,result:ItemStack?):Boolean {
        val inputs=when(inventory) {
            is CraftingInventory->inventory.matrix.toList()
            is SmithingInventory->(0..2).map(inventory::getItem)
            is AnvilInventory,is GrindstoneInventory->(0..1).map(inventory::getItem)
            is StonecutterInventory->listOf(inventory.getItem(0))
            is CartographyInventory->(0..1).map(inventory::getItem)
            else->return false
        }
        if(inputs.none {com.alytanta.oneblock.items.CustomItems.marked(it)})return false
        if(inventory is CraftingInventory || inventory is SmithingInventory || inventory is StonecutterInventory || inventory is CartographyInventory)return true
        if(inputs.any {com.alytanta.oneblock.items.CustomItems.resolve(it)?.vanillaRepair==false})return true
        return !mayRepair(CombatItems.id(inputs[0]),CombatItems.id(inputs[1]),CombatItems.id(result),inputs[0]?.type==result?.type)
    }
    @EventHandler(priority=EventPriority.HIGHEST) fun craft(e:PrepareItemCraftEvent) {if(denied(e.inventory,e.inventory.result))e.inventory.result=null}
    /** Native anvil owns XP payment, input consumption and pickup; this only prepares a canonical helmet result. */
    internal fun headBook(base:ItemStack?,book:ItemStack?):Pair<ItemStack,Int>? {
        val current=rules()?:return null
        val definition=CombatItems.resolve(base,current)?.takeIf {it.kind==CombatKind.ARMOR && it.armorSlot=="HEAD" && it.material=="PLAYER_HEAD" && it.definition?.vanillaRepair!=false}?:return null
        if(base!!.amount!=1 || book?.type!=org.bukkit.Material.ENCHANTED_BOOK || com.alytanta.oneblock.items.CustomItems.marked(book))return null
        val stored=(book.itemMeta as? org.bukkit.inventory.meta.EnchantmentStorageMeta)?.storedEnchants?:return null
        val allowed=setOf("protection","fire_protection","blast_protection","projectile_protection","respiration","aqua_affinity","unbreaking","mending","thorns","binding_curse","vanishing_curse")
        val result=base.clone();var cost=0
        for((enchantment,level) in stored) {
            if(enchantment.key.key !in allowed || result.enchantments.keys.any {it!=enchantment && it.conflictsWith(enchantment)})continue
            val old=result.getEnchantmentLevel(enchantment);val next=(if(old==level)level+1 else maxOf(old,level)).coerceAtMost(enchantment.maxLevel)
            if(next<=old)continue
            result.addUnsafeEnchantment(enchantment,next);cost+=next*current.headEnchantCost
        }
        if(cost==0)return null
        val penalty=(base.itemMeta as? org.bukkit.inventory.meta.Repairable)?.repairCost?:0
        cost=(cost+penalty).coerceAtMost(1000)
        result.editMeta {if(it is org.bukkit.inventory.meta.Repairable)it.repairCost=(penalty*2+1).coerceAtMost(1000)}
        check(CombatItems.id(result)==definition.id)
        return result to cost
    }
    @EventHandler(priority=EventPriority.HIGHEST) fun anvil(e:PrepareAnvilEvent) {
        headBook(e.inventory.getItem(0),e.inventory.getItem(1))?.let {(result,cost)->
            e.view.renameText?.takeIf {it.isNotBlank() && it.length<=50}?.let {name->result.editMeta {it.displayName(net.kyori.adventure.text.Component.text(name))}}
            e.result=result;e.view.repairCost=cost;e.view.repairItemCountCost=1
        }
        if(denied(e.inventory,e.result))e.result=null
    }
    @EventHandler(priority=EventPriority.HIGHEST) fun smith(e:PrepareSmithingEvent) {if(denied(e.inventory,e.result))e.result=null}
    @EventHandler(priority=EventPriority.HIGHEST) fun grind(e:PrepareGrindstoneEvent) {if(denied(e.inventory,e.result))e.result=null}
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true) fun take(e:InventoryClickEvent) {
        if(e.slotType==InventoryType.SlotType.RESULT && denied(e.view.topInventory,e.currentItem))e.isCancelled=true
    }
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true) fun cook(e:BlockCookEvent) {if(CombatItems.id(e.source)!=null)e.isCancelled=true}
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true) fun burn(e:FurnaceBurnEvent) {if(CombatItems.id(e.fuel)!=null)e.isCancelled=true}
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true) fun brewFuel(e:BrewingStandFuelEvent) {if(CombatItems.id(e.fuel)!=null)e.isCancelled=true}
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true) fun brew(e:BrewEvent) {if(e.contents.contents.any {CombatItems.id(it)!=null})e.isCancelled=true}
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true) fun autoCraft(e:CrafterCraftEvent) {
        val crafter=e.block.state as? org.bukkit.block.Crafter?:return
        if(crafter.inventory.contents.any {CombatItems.id(it)!=null})e.isCancelled=true
    }
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true) fun dispense(e:BlockDispenseEvent) {if(CombatItems.id(e.item)!=null)e.isCancelled=true}
    @EventHandler(priority=EventPriority.HIGH) fun use(e:PlayerInteractEvent) {
        if(e.action !in setOf(Action.RIGHT_CLICK_AIR,Action.RIGHT_CLICK_BLOCK))return
        val current=rules()?:return
        if(CombatItems.resolve(e.item,current)?.kind==CombatKind.COMPONENT)e.setUseItemInHand(Event.Result.DENY)
    }
}
