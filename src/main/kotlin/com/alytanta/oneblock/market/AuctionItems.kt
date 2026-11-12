package com.alytanta.oneblock.market

import org.bukkit.inventory.ItemStack
import java.util.Base64

object AuctionItems {
    fun encode(item: ItemStack, maxBytes: Int): String {
        val bytes = item.serializeAsBytes()
        if (bytes.size > maxBytes) throw MarketException("auction-item")
        return Base64.getEncoder().encodeToString(bytes)
    }
    fun decode(data: String): ItemStack {
        require(data.length <= 1_398_104) { "Auction item exceeds hard safety limit" }
        return ItemStack.deserializeBytes(Base64.getDecoder().decode(data))
    }
}
