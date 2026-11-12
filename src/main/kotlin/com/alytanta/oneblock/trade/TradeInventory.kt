package com.alytanta.oneblock.trade

import com.alytanta.oneblock.market.AuctionItems
import com.alytanta.oneblock.menu.MenuInventory
import org.bukkit.inventory.ItemStack
import java.security.MessageDigest
import java.nio.ByteBuffer

internal object TradeInventory {
    fun hash(contents:Array<ItemStack?>):String {
        val digest=MessageDigest.getInstance("SHA-256")
        contents.forEachIndexed { slot,item -> if(slot!=MenuInventory.SLOT) {
            val bytes=if(item==null||item.isEmpty)ByteArray(0) else item.serializeAsBytes()
            digest.update(ByteBuffer.allocate(4).putInt(bytes.size).array());digest.update(bytes)
        } }
        return digest.digest().joinToString(""){"%02x".format(it)}
    }
    fun item(stack:ItemStack,maxBytes:Int):TradeItem {
        val data=AuctionItems.encode(stack,maxBytes)
        val hash=MessageDigest.getInstance("SHA-256").digest(java.util.Base64.getDecoder().decode(data)).joinToString(""){"%02x".format(it)}
        return TradeItem(stack.type.name,stack.amount,data,hash)
    }
}
