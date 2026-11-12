package com.alytanta.oneblock.player

import com.alytanta.oneblock.menu.MenuInventory
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import org.bukkit.potion.PotionEffect
import org.bukkit.potion.PotionEffectType
import java.util.Base64
import java.security.MessageDigest

data class ProfileEffect(val type: String, val duration: Int, val amplifier: Int, val ambient: Boolean, val particles: Boolean, val icon: Boolean)

/** Only inactive inventories live here; the active inventory is Minecraft playerdata. */
data class ProfileInventory(val items: List<String?> = List(41) { null }, val enderItems: List<String?> = List(27) { null },
    val level: Int = 0, val experience: Float = 0f, val totalExperience: Int = 0,
    val health: Double = 20.0, val food: Int = 20, val saturation: Float = 5f, val exhaustion: Float = 0f,
    val effects: List<ProfileEffect> = emptyList(), val selectedSlot: Int = 0) {
    fun hash(): String = MessageDigest.getInstance("SHA-256").digest(
        com.google.gson.Gson().toJson(listOf(items, enderItems, level, experience, totalExperience)).toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }

    fun apply(player: Player) = prepare(player).apply(player)

    /** Validate/decode before creating a switch journal, while still on the entity scheduler. */
    internal fun prepare(player: Player, decodeItem: (String?) -> ItemStack? = ::decode): Prepared {
        val size = player.inventory.size
        require(size >= 41 && (items.size == 41 || items.size == size)) {
            "Profile inventory has ${items.size} slots; expected legacy 41 or current $size"
        }
        require(enderItems.size == player.enderChest.size) { "Invalid profile ender chest size: ${enderItems.size}" }
        require(level >= 0 && experience in 0f..1f && totalExperience >= 0) { "Invalid profile experience" }
        require(health.isFinite() && saturation.isFinite() && exhaustion.isFinite()) { "Invalid profile vitals" }
        // Old snapshots have no body/saddle slots. Explicitly clear those slots instead of carrying
        // equipment from the source profile over to the target. Never truncate a newer snapshot.
        val inventory = Array(size) { index -> if (index < items.size) decodeItem(items[index]) else null }
        val ender = enderItems.map(decodeItem).toTypedArray()
        val potions = effects.map { effect -> PotionEffect(requireNotNull(PotionEffectType.getByName(effect.type)),
            effect.duration, effect.amplifier, effect.ambient, effect.particles, effect.icon) }
        return Prepared(this, inventory, ender, potions)
    }
    internal class Prepared(private val snapshot: ProfileInventory, private val inventory: Array<ItemStack?>,
        private val ender: Array<ItemStack?>, private val potions: List<PotionEffect>) {
        fun apply(player: Player) = with(snapshot) {
            check(player.inventory.size == inventory.size && player.enderChest.size == ender.size) { "Inventory size changed during profile switch" }
            inventory[MenuInventory.SLOT] = player.inventory.getItem(MenuInventory.SLOT)?.clone()
            player.inventory.contents = inventory
            player.enderChest.contents = ender
            player.level = level; player.exp = experience; player.totalExperience = totalExperience
            player.foodLevel = food.coerceIn(0, 20); player.saturation = saturation.coerceIn(0f, 20f); player.exhaustion = exhaustion.coerceAtLeast(0f)
            player.activePotionEffects.forEach { player.removePotionEffect(it.type) }
            potions.forEach { player.addPotionEffect(it) }
            player.health = health.coerceIn(1.0, player.maxHealth)
            player.fireTicks = 0; player.fallDistance = 0f
            player.inventory.heldItemSlot = selectedSlot.coerceIn(0, 7)
        }
    }
    companion object {
        fun capture(player: Player): ProfileInventory = ProfileInventory(
            player.inventory.contents.mapIndexed { index, item -> if (index == MenuInventory.SLOT) null else encode(item) },
            player.enderChest.contents.map(::encode), player.level, player.exp, player.totalExperience,
            player.health, player.foodLevel, player.saturation, player.exhaustion,
            player.activePotionEffects.map { ProfileEffect(it.type.name, it.duration, it.amplifier, it.isAmbient, it.hasParticles(), it.hasIcon()) },
            player.inventory.heldItemSlot)
        private fun encode(item: ItemStack?): String? = item?.takeUnless { it.isEmpty }?.let { Base64.getEncoder().encodeToString(it.serializeAsBytes()) }
        private fun decode(data: String?): ItemStack? = data?.let { ItemStack.deserializeBytes(Base64.getDecoder().decode(it)) }
    }
}
