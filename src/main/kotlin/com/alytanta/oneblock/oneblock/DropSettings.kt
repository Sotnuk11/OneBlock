package com.alytanta.oneblock.oneblock

import org.bukkit.Material
import org.bukkit.configuration.file.FileConfiguration
import kotlin.random.Random

/** Relative weights, not percentages. The sum need not be 100 or 10,000. */
class WeightedDropTable(val entries: List<CoreDrop>) {
    val totalWeight: Long = entries.sumOf { it.weight.toLong() }

    init {
        require(entries.isNotEmpty()) { "Drop table must not be empty" }
        require(entries.all { it.weight > 0 && it.block !in AIR && it.reward !in AIR }) {
            "Drop entries require a non-air block, an obtainable item, and a positive weight"
        }
        require(entries.all { it.minAmount in 1..64 && it.maxAmount in it.minAmount..64 }) {
            "Drop amounts must satisfy 1 <= min-amount <= max-amount <= 64"
        }
    }

    /** Paper 26 resolves isBlock/isItem through live registries; validate these once the server is running. */
    fun validateMaterials() {
        require(entries.all { it.block.isBlock && it.reward.isItem }) { "Invalid display block or unobtainable reward item" }
    }

    companion object {
        private val AIR = setOf(Material.AIR, Material.CAVE_AIR, Material.VOID_AIR)
    }

    fun randomDrop(random: Random = Random.Default): CoreDrop {
        var roll = random.nextLong(totalWeight)
        return entries.first { roll -= it.weight; roll < 0 }
    }
}

data class DropSettings(
    val starterBreaks: Long,
    val starter: WeightedDropTable,
    val main: WeightedDropTable,
    val rewardMessages: Boolean = false,
    val bonusEnabled: Boolean = true,
    val richChance: Double = 0.2,
    val wildChance: Double = 0.15,
    val ancientChance: Double = 0.4,
    val fortuneChancePerLevel: Double = 0.04,
    val infusionChancePerLevel: Double = 0.02,
    val fracturedChance: Double = 0.05,
) {
    fun tableFor(completedBreaks: Long): WeightedDropTable = if (completedBreaks < starterBreaks) starter else main

    fun amountFor(drop: CoreDrop, core: CoreState, fortune: Int, random: Random = Random.Default): Int {
        val base = random.nextInt(drop.minAmount, drop.maxAmount + 1)
        if (!bonusEnabled) return base
        val extraChance = (fortune * fortuneChancePerLevel).coerceIn(0.0, 1.0)
        return base + if (random.nextDouble() < extraChance) 1 else 0
    }

    companion object {
        val starterDefaults = listOf(
            CoreDrop(Material.OAK_LOG, Material.OAK_LOG, 30),
            CoreDrop(Material.SPRUCE_LOG, Material.SPRUCE_LOG, 10),
            CoreDrop(Material.COAL_ORE, Material.COAL, 20),
            CoreDrop(Material.IRON_ORE, Material.RAW_IRON, 15),
            CoreDrop(Material.HAY_BLOCK, Material.HAY_BLOCK, 15),
            CoreDrop(Material.DIRT, Material.DIRT, 5),
            CoreDrop(Material.STONE, Material.COBBLESTONE, 5),
        )

        fun load(config: FileConfiguration): DropSettings {
            fun table(path: String, defaults: List<CoreDrop>): WeightedDropTable {
                if (!config.contains(path)) return WeightedDropTable(defaults)
                require(config.isList(path)) { "$path must be a list" }
                require(config.getList(path)!!.all { it is Map<*, *> }) { "$path must contain only drop mappings" }
                val entries = config.getMapList(path).mapIndexed { index, entry ->
                    fun material(key: String): Material = Material.matchMaterial(entry[key]?.toString().orEmpty())
                        ?: error("Invalid material at $path[$index].$key")
                    val weight = entry["weight"]?.toString()?.toIntOrNull()
                        ?: error("Invalid integer weight at $path[$index]")
                    fun amount(key: String, default: Int) = entry[key]?.toString()?.toIntOrNull()
                        ?: if (entry.containsKey(key)) error("Invalid integer $key at $path[$index]") else default
                    val min = amount("min-amount", amount("amount", 1))
                    CoreDrop(material("block"), material("reward"), weight, min, amount("max-amount", min))
                }
                return WeightedDropTable(entries)
            }
            val count = config.get("core.starter-breaks")?.toString()?.toLongOrNull()
                ?: if (config.contains("core.starter-breaks")) error("core.starter-breaks must be an integer") else 100L
            require(count >= 0) { "core.starter-breaks must be non-negative" }
            fun chance(key: String, default: Double): Double = (config.get("core.bonus-drops.$key")?.toString()?.toDoubleOrNull()
                ?: if (config.contains("core.bonus-drops.$key")) error("core.bonus-drops.$key must be numeric") else default).also {
                require(it.isFinite() && it in 0.0..1.0) { "core.bonus-drops.$key must be between 0 and 1" }
            }
            return DropSettings(count, table("core.drops.starter", starterDefaults), table("core.drops.main", CoreDropTable.entries),
                config.getBoolean("core.reward-messages", false), config.getBoolean("core.bonus-drops.enabled", true),
                0.0, 0.0, 0.0,
                chance("fortune-chance-per-level", 0.04), 0.0, 0.0)
        }
    }
}
