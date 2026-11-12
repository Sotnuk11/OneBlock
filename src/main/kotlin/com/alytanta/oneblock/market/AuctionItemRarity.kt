package com.alytanta.oneblock.market

import io.papermc.paper.datacomponent.DataComponentTypes
import org.bukkit.inventory.ItemRarity
import org.bukkit.inventory.ItemStack

/** The legacy ItemStack rarity getter requires an explicit ItemMeta override and throws for ordinary items. */
internal object AuctionItemRarity {
    fun component(item: ItemStack): ItemRarity? = item.getData(DataComponentTypes.RARITY)

    fun resolve(item: ItemStack, overrides: Map<String, String>,
        readComponent: (ItemStack) -> ItemRarity? = ::component): String =
        overrides[item.type.name] ?: (readComponent(item) ?: ItemRarity.COMMON).name
}
