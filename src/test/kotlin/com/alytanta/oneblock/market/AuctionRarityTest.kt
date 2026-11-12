package com.alytanta.oneblock.market

import com.alytanta.oneblock.menu.MenuFixture.Companion.stub
import org.bukkit.Material
import org.bukkit.inventory.ItemRarity
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.meta.ItemMeta
import org.bukkit.persistence.PersistentDataContainer
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

/** Reproduces the live Folia getter precondition: ordinary items have no explicit rarity metadata. */
internal class AuctionTestStack : ItemStack() {
    private var count = 1
    private val data = stub(PersistentDataContainer::class.java) { name, _ -> if (name == "has") false else null }
    private val metadata = stub(ItemMeta::class.java) { name, _ -> when (name) {
        "hasRarity" -> false
        "getRarity" -> throw IllegalStateException("We don't have rarity! Check hasRarity first!")
        "getPersistentDataContainer" -> data
        else -> null
    } }
    override fun getType() = Material.DIAMOND_SWORD
    override fun getAmount() = count
    override fun getMaxStackSize() = 1
    override fun setAmount(amount: Int) { count = amount }
    override fun isEmpty() = count <= 0
    override fun hasItemMeta() = false
    override fun getItemMeta(): ItemMeta = metadata
    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    override fun getRarity(): io.papermc.paper.inventory.ItemRarity =
        throw IllegalStateException("We don't have rarity! Check hasRarity first!")
    override fun serializeAsBytes() = "test-sword:$count".toByteArray()
    override fun clone() = AuctionTestStack().apply { amount = count }
    override fun isSimilar(other: ItemStack?) = other is AuctionTestStack
    override fun equals(other: Any?) = other is AuctionTestStack && amount == other.amount
    override fun hashCode() = count
}

class AuctionRarityTest {
    @Test fun `ordinary item without explicit metadata never calls throwing legacy rarity getter`() {
        val item = AuctionTestStack()
        assertFalse(item.itemMeta.hasRarity())
        @Suppress("DEPRECATION")
        assertThrows(IllegalStateException::class.java) { item.rarity }
        assertEquals("COMMON", AuctionItemRarity.resolve(item, emptyMap()) { ItemRarity.COMMON })
    }
    @Test fun `component rarity and safe absent component fallback preserve item metadata`() {
        val item = AuctionTestStack()
        val before = item.serializeAsBytes()
        assertEquals("EPIC", AuctionItemRarity.resolve(item, emptyMap()) { ItemRarity.EPIC })
        assertEquals("RARE", AuctionItemRarity.resolve(item, emptyMap()) { ItemRarity.RARE })
        assertEquals("COMMON", AuctionItemRarity.resolve(item, emptyMap()) { null })
        assertArrayEquals(before, item.serializeAsBytes())
    }
    @Test fun `configured rarity override takes priority without reading native components`() {
        assertEquals("LEGENDARY", AuctionItemRarity.resolve(AuctionTestStack(), mapOf("DIAMOND_SWORD" to "LEGENDARY")) {
            error("Native component must not be read when configured")
        })
    }
}
