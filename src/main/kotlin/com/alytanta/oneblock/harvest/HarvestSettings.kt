package com.alytanta.oneblock.harvest

import org.bukkit.Material
import org.bukkit.configuration.file.YamlConfiguration
import java.io.File

/** Only real, eligible drops contribute. Ore counters always use the source ore key. */
data class HarvestSettings(val materials: Set<Material> = emptySet(), val plants: Set<Material> = emptySet(),
    val notificationSeconds: Int = 30, val notification: String = "&eІнвентар повністю заповнений!") {
    fun credit(block: Material, drops: List<Pair<Material, Int>>, silk: Boolean, placed: Boolean = false): Map<String, Long> {
        if (placed && block !in plants || silk && block !in plants) return emptyMap()
        val actual = drops.filter { it.second > 0 }
        if (actual.isEmpty()) return emptyMap()
        val ore = ORES[block]
        if (ore != null) return if (block in materials) actual.filter { it.first in ore }.sumOf { it.second.toLong() }
            .takeIf { it > 0 }?.let { mapOf(block.name.lowercase() to it) }.orEmpty() else emptyMap()
        if (block in materials) {
            val expected = PRIMARY_DROPS[block] ?: block
            val amount = actual.filter { it.first == expected || it.first == block }.sumOf { it.second.toLong() }
            return if (amount > 0) mapOf(block.name.lowercase() to amount) else emptyMap()
        }
        return actual.filter { it.first in materials }.groupBy { it.first.name.lowercase() }
            .mapValues { (_, values) -> values.sumOf { it.second.toLong() } }
    }
    companion object {
        val PRIMARY_DROPS = mapOf(Material.STONE to Material.COBBLESTONE, Material.GRASS_BLOCK to Material.DIRT,
            Material.CARROTS to Material.CARROT, Material.POTATOES to Material.POTATO, Material.BEETROOTS to Material.BEETROOT,
            Material.COCOA to Material.COCOA_BEANS, Material.SWEET_BERRY_BUSH to Material.SWEET_BERRIES,
            Material.CAVE_VINES to Material.GLOW_BERRIES, Material.CAVE_VINES_PLANT to Material.GLOW_BERRIES,
            Material.KELP_PLANT to Material.KELP)
        val ORES: Map<Material, Set<Material>> = buildMap {
            fun ore(name: String, vararg drops: Material) { Material.matchMaterial(name)?.let { put(it, drops.toSet()) } }
            listOf("", "DEEPSLATE_").forEach { prefix ->
                ore("${prefix}COAL_ORE", Material.COAL); ore("${prefix}IRON_ORE", Material.RAW_IRON)
                ore("${prefix}COPPER_ORE", Material.RAW_COPPER); ore("${prefix}GOLD_ORE", Material.RAW_GOLD)
                ore("${prefix}DIAMOND_ORE", Material.DIAMOND); ore("${prefix}EMERALD_ORE", Material.EMERALD)
                ore("${prefix}LAPIS_ORE", Material.LAPIS_LAZULI); ore("${prefix}REDSTONE_ORE", Material.REDSTONE)
            }
            ore("NETHER_GOLD_ORE", Material.GOLD_NUGGET); ore("NETHER_QUARTZ_ORE", Material.QUARTZ)
            ore("ANCIENT_DEBRIS", Material.ANCIENT_DEBRIS)
        }
        fun read(file: File): Pair<HarvestSettings, String?> {
            if (!file.exists()) return HarvestSettings() to null
            val text = file.readText(); val y = YamlConfiguration().apply { loadFromString(text) }
            require(y.getInt("version") == 1)
            fun materials(key: String): Set<Material> {
                require(y.isList(key)) { "$key must be a list" }
                return y.getList(key)!!.map { requireNotNull((it as? String)?.let(Material::matchMaterial)) { "Invalid $key material: $it" } }.toSet()
            }
            val plants = materials("plants")
            val interval = y.getInt("inventory.notification-seconds", 30).also { require(it in 1..3600) }
            return HarvestSettings(materials("materials"), plants, interval,
                y.getString("inventory.notification", "&eІнвентар повністю заповнений!")!!) to text
        }
    }
}
