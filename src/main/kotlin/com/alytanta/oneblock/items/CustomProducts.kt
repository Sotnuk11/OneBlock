package com.alytanta.oneblock.items

import org.bukkit.Material
import org.bukkit.inventory.ItemStack

/** Catalog items traded on the Bazaar: product key `oneblock_<id>`, moved as whole custom stacks, never as plain material. */
object CustomProducts {
    const val PREFIX="oneblock_"
    fun custom(key:String)=key.startsWith(PREFIX)
    private fun id(key:String)=ItemCatalog.canonical(key.removePrefix(PREFIX))
    fun matches(key:String,stack:ItemStack?)=custom(key) && CustomItems.id(stack)==id(key)
    fun stack(key:String,amount:Int):ItemStack {
        val definition=requireNotNull(CustomItems.current()?.find(id(key))) {"Unknown custom product $key"}
        return CustomItems.create(definition).apply {this.amount=amount}
    }
    fun material(key:String):Material?=if(custom(key))stack(key,1).type else Material.matchMaterial(key)
    /** Gear sorts by what it does, not by the material it is drawn on. */
    fun auctionCategory(stack:ItemStack)=when(CustomItems.resolve(stack)?.kind) {
        ItemKind.MELEE,ItemKind.RANGED,ItemKind.MAGIC->"WEAPONS"
        ItemKind.ARMOR->"ARMOR"
        ItemKind.TOOL->"TOOLS"
        else->null
    }
}
