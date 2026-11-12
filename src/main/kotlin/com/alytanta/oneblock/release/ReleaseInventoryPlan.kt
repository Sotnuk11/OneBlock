package com.alytanta.oneblock.release

import com.alytanta.oneblock.menu.MenuInventory
import org.bukkit.inventory.ItemStack
import java.nio.ByteBuffer
import java.security.MessageDigest

object ReleaseInventoryPlan {
    fun remove(before: Array<ItemStack?>, ingredients: List<Ingredient>): Array<ItemStack?> {
        val amounts=requireNotNull(amounts(before,ingredients)) { "items" }
        return before.mapIndexed { slot,stack -> if(amounts[slot]==0)null else stack?.clone()?.apply { amount=amounts[slot] } }.toTypedArray()
    }
    /** Read-only menu preflight shares the exact selector/allocation rules, without cloning a whole inventory per recipe. */
    fun canRemove(before:Array<ItemStack?>,ingredients:List<Ingredient>)=amounts(before,ingredients)!=null
    private fun amounts(before:Array<ItemStack?>,ingredients:List<Ingredient>):IntArray? {
        val counts=IntArray(before.size) { before[it]?.takeUnless { item -> item.isEmpty }?.amount ?: 0 }
        ingredients.forEach { ingredient ->
            var remaining=ingredient.amount
            for(slot in before.indices) {
                if(slot==MenuInventory.SLOT)continue
                val stack=before[slot] ?: continue
                if(counts[slot]==0)continue
                if(stack.type.name !in ingredient.materials)continue
                if(ingredient.combatId == null) { if(stack.hasItemMeta())continue }
                else if(com.alytanta.oneblock.combat.CombatItems.id(stack) != ingredient.combatId)continue
                val count=minOf(counts[slot],remaining);remaining-=count;counts[slot]-=count
                if(remaining==0)break
            }
            if(remaining!=0)return null
        }
        return counts
    }
    fun hash(contents: Array<ItemStack?>): String {
        val digest=MessageDigest.getInstance("SHA-256")
        contents.forEach { item ->
            val bytes=if(item==null||item.isEmpty)ByteArray(0) else item.serializeAsBytes()
            digest.update(ByteBuffer.allocate(4).putInt(bytes.size).array());digest.update(bytes)
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
