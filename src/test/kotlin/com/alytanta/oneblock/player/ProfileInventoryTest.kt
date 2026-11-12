package com.alytanta.oneblock.player

import com.alytanta.oneblock.menu.MenuFixture.Companion.stub
import com.alytanta.oneblock.menu.Stack
import io.papermc.paper.threadedregions.scheduler.EntityScheduler
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.entity.Player
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.PlayerInventory
import org.bukkit.inventory.InventoryView
import org.bukkit.persistence.PersistentDataContainer
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.util.Base64
import java.util.Locale
import java.util.UUID

internal class ProfilePlayer(val size: Int = 43) {
    val id = UUID.randomUUID()
    var items = arrayOfNulls<ItemStack>(size)
    var ender = arrayOfNulls<ItemStack>(27)
    val values = mutableMapOf<String, Any>("Level" to 3, "Exp" to 0.25f, "TotalExperience" to 42,
        "Health" to 18.0, "FoodLevel" to 19, "Saturation" to 4f, "Exhaustion" to 0.5f, "HeldItemSlot" to 2)
    var writes = 0
    var saves = 0
    var failSave = false
    var onSave: () -> Unit = {}
    val queued = ArrayDeque<Runnable>()
    var opened: Inventory? = null
    var opens = 0
    val markers = mutableMapOf<NamespacedKey, Any>()
    private val pdc = stub(PersistentDataContainer::class.java) { name, args -> when (name) {
        "get" -> markers[args!![0]]
        "set" -> { markers[args!![0] as NamespacedKey] = args[2]!!; null }
        else -> null
    } }
    private val scheduler = stub(EntityScheduler::class.java) { name, args -> when (name) {
        "execute" -> { queued += args!![1] as Runnable; true }
        else -> null
    } }
    private val inventory = stub(PlayerInventory::class.java) { name, args -> when (name) {
        "getSize" -> size
        "getContents" -> items.copyOf()
        "getItem" -> items[args!![0] as Int]
        "setContents" -> { @Suppress("UNCHECKED_CAST") val next = args!![0] as Array<ItemStack?>; items = next.copyOf(); writes++; null }
        "getHeldItemSlot" -> values["HeldItemSlot"]
        "setHeldItemSlot" -> { values["HeldItemSlot"] = args!![0]!!; writes++; null }
        else -> null
    } }
    private val chest = stub(Inventory::class.java) { name, args -> when (name) {
        "getSize" -> 27
        "getContents" -> ender.copyOf()
        "setContents" -> { @Suppress("UNCHECKED_CAST") val next = args!![0] as Array<ItemStack?>; ender = next.copyOf(); writes++; null }
        else -> null
    } }
    val view: InventoryView = stub(InventoryView::class.java) { name, args -> when (name) {
        "getTopInventory" -> opened ?: inventory
        "getBottomInventory" -> inventory
        "getPlayer" -> player
        "convertSlot" -> args!![0]
        else -> null
    } }
    val player: Player = stub(Player::class.java) { name, args -> when {
        name == "getUniqueId" -> id
        name == "getName" -> "ProfilePlayer"
        name == "getInventory" -> inventory
        name == "getOpenInventory" -> view
        name == "openInventory" -> { opened = args!![0] as Inventory; opens++; view }
        name == "closeInventory" -> { opened = null; null }
        name == "getEnderChest" -> chest
        name == "getScheduler" -> scheduler
        name == "getPersistentDataContainer" -> pdc
        name == "getMaxHealth" -> 20.0
        name == "getActivePotionEffects" -> emptyList<org.bukkit.potion.PotionEffect>()
        name == "isDead" -> false
        name == "locale" -> Locale.ENGLISH
        name == "saveData" -> { onSave(); if (failSave) error("Injected saveData failure"); saves++; null }
        name.startsWith("get") -> values[name.removePrefix("get")]
        name.startsWith("set") -> { values[name.removePrefix("set")] = args!![0]!!; writes++; null }
        else -> null
    } }
    fun drain() { while (queued.isNotEmpty()) queued.removeFirst().run() }
    fun decode(data: String?): ItemStack? = data?.let {
        val parts = String(Base64.getDecoder().decode(it), Charsets.UTF_8).split(':')
        Stack(Material.valueOf(parts[0]), parts[1].toInt())
    }
}

class ProfileInventoryTest {
    @Test fun `current snapshot restores all slots and keeps the menu star`() {
        val f = ProfilePlayer()
        listOf(0, 36, 40, 41, 42).forEach { f.items[it] = Stack(Material.DIAMOND, it + 1) }
        f.items[8] = Stack(Material.NETHER_STAR, menu = true)
        f.ender[26] = Stack(Material.EMERALD, 7)
        val snapshot = ProfileInventory.capture(f.player)
        assertEquals(43, snapshot.items.size)
        assertNull(snapshot.items[8])
        val expected = f.items.copyOf()
        f.items.fill(null); f.ender.fill(null)
        f.items[8] = Stack(Material.NETHER_STAR, menu = true)
        snapshot.prepare(f.player, f::decode).apply(f.player)
        assertArrayEquals(expected, f.items)
        assertEquals(Stack(Material.EMERALD, 7), f.ender[26])
        assertEquals(snapshot, ProfileInventory.capture(f.player))
    }
    @Test fun `legacy snapshot clears new equipment slots on modern inventory`() {
        val f = ProfilePlayer()
        f.items[41] = Stack(Material.DIAMOND); f.items[42] = Stack(Material.SADDLE)
        ProfileInventory().apply(f.player)
        assertEquals(43, f.items.size)
        assertTrue(f.items.all { it == null })
    }
    @Test fun `legacy inventory implementation still accepts legacy snapshots`() {
        val f = ProfilePlayer(41)
        ProfileInventory().apply(f.player)
        assertEquals(41, f.items.size)
    }
    @Test fun `invalid layouts and experience fail before any mutation`() {
        val f = ProfilePlayer()
        val bad = listOf(ProfileInventory(items = List(40) { null }), ProfileInventory(items = List(42) { null }),
            ProfileInventory(items = List(44) { null }), ProfileInventory(enderItems = List(26) { null }),
            ProfileInventory(level = -1), ProfileInventory(experience = Float.NaN), ProfileInventory(totalExperience = -1),
            ProfileInventory(health = Double.NaN))
        bad.forEach { assertThrows(IllegalArgumentException::class.java) { it.apply(f.player) } }
        assertEquals(0, f.writes)
    }
    @Test fun `larger saved snapshot is never silently truncated`() {
        val f = ProfilePlayer(41)
        assertThrows(IllegalArgumentException::class.java) { ProfileInventory(items = List(43) { null }).apply(f.player) }
        assertEquals(0, f.writes)
    }
    @Test fun `invalid item bytes leave both inventories and stats intact`() {
        val f = ProfilePlayer()
        f.items[0] = Stack(Material.DIAMOND)
        f.ender[0] = Stack(Material.EMERALD)
        val before = ProfileInventory.capture(f.player)
        val corrupt = ProfileInventory(enderItems = List(27) { if (it == 26) "!invalid!" else null })
        assertThrows(IllegalArgumentException::class.java) { corrupt.apply(f.player) }
        assertEquals(before, ProfileInventory.capture(f.player))
        assertEquals(0, f.writes)
    }
    @Test fun `preparation has no side effects and rechecks destination size`() {
        val f = ProfilePlayer()
        val prepared = ProfileInventory().prepare(f.player)
        assertEquals(0, f.writes)
        val different = ProfilePlayer(41)
        assertThrows(IllegalStateException::class.java) { prepared.apply(different.player) }
        assertEquals(0, different.writes)
    }
}
