package com.alytanta.oneblock.combat

import com.alytanta.oneblock.util.Colors
import org.bukkit.NamespacedKey
import org.bukkit.Material
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataType

/** Schema 1 IDs are permanent. Display name, lore and enchantments never establish identity. */
object CombatItems {
    private val identity=NamespacedKey("one-block","combat_item")
    private val schema=NamespacedKey("one-block","combat_schema")
    fun id(stack:ItemStack?):String? {
        if(com.alytanta.oneblock.items.CustomItems.marked(stack))return com.alytanta.oneblock.items.CustomItems.id(stack)?.removePrefix("oneblock:")
        if(stack==null || stack.isEmpty)return null
        val p=stack.itemMeta?.persistentDataContainer ?: return null
        return if(p.get(schema,PersistentDataType.INTEGER)==1)p.get(identity,PersistentDataType.STRING) else null
    }
    fun resolve(stack:ItemStack?,rules:CombatRules):CombatItem? = id(stack)?.let(rules.items::get)?.takeIf { it.material==stack?.type?.name }
    fun create(item:CombatItem,amount:Int=1):ItemStack {
        item.definition?.let {return com.alytanta.oneblock.items.CustomItems.create(it,amount)}
        require(amount in 1..64 && (item.kind==CombatKind.COMPONENT || amount==1))
        return ItemStack(Material.valueOf(item.material),amount).apply { editMeta { meta ->
            meta.persistentDataContainer.set(identity,PersistentDataType.STRING,item.id)
            meta.persistentDataContainer.set(schema,PersistentDataType.INTEGER,1)
            meta.displayName(Colors.colorize(item.name));meta.lore((item.lore+(com.alytanta.oneblock.items.CustomItems.current()?.visibleIdFormat?:"&8ID: {id}").replace("{id}","oneblock:${item.id}")).map(Colors::colorize))
            // Wands use a stackable vanilla carrier; custom max stack size keeps them individual.
            if(item.kind==CombatKind.MAGIC)meta.setMaxStackSize(1)
        } }
    }
}
