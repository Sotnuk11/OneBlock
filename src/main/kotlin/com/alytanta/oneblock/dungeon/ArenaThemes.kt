package com.alytanta.oneblock.dungeon

import kotlin.math.abs

/**
 * Fixed room decoration, built once per instance. Spawn points and the entry always stay clear, so the existing
 * spawn checks (solid floor, three free blocks above) keep passing. Nothing here flows, burns or changes over time.
 */
object ArenaThemes {
    val NAMES = setOf("plain", "hall", "crypt", "mine", "forge")

    private class Room(val a: WaveArena, val slot: Int) {
        val n = a.size
        val top = 64 + a.height
        val blocks = linkedMapOf<Triple<Int, Int, Int>, String>()
        private val keep = (a.spawns + a.entry).map { it.x.toInt() to it.z.toInt() }
        fun free(x: Int, z: Int) = keep.none { (kx, kz) -> abs(kx - x) <= 2 && abs(kz - z) <= 2 } && x in 1 until n && z in 1 until n
        fun set(x: Int, y: Int, z: Int, data: String) { blocks[Triple(slot * 64 + x, y, z)] = "minecraft:$data" }
        /** Solid decoration: skipped where it would block a spawn point. */
        fun solid(x: Int, y: Int, z: Int, data: String) { if (free(x, z)) set(x, y, z, data) }
        fun shell(floor: (Int, Int) -> String, wall: (Int, Int, Int) -> String, roof: String) {
            for (x in 0..n) for (z in 0..n) {
                set(x, 64, z, floor(x, z))
                set(x, 65 + a.height, z, roof)
                for (y in 65 until 65 + a.height) if (x == 0 || z == 0 || x == n || z == n) set(x, y, z, wall(x, y, z))
            }
        }
        fun pillar(x: Int, z: Int, data: String, size: Int = 1) {
            for (dx in 0 until size) for (dz in 0 until size) for (y in 65..top) solid(x + dx, y, z + dz, data)
        }
        fun hanging(x: Int, z: Int, lantern: String = "lantern") {
            set(x, top, z, "iron_chain[axis=y]")
            set(x, top - 1, z, "$lantern[hanging=true]")
        }
        fun mix(x: Int, z: Int) = (x * 7 + z * 13 + (x * z) % 5) % 9
    }

    fun blocks(a: WaveArena, slot: Int): List<MineBlock> {
        val room = Room(a, slot)
        when (a.theme) {
            "hall" -> hall(room)
            "crypt" -> crypt(room)
            "mine" -> mine(room)
            "forge" -> forge(room)
            else -> plain(room)
        }
        return room.blocks.map { (at, data) -> MineBlock(at.first, at.second, at.third, data) }
    }
    private fun plain(r: Room) {
        val base = r.a.material.lowercase()
        r.shell({ x, z -> if (x % 6 == 0 && z % 6 == 0) "sea_lantern" else base }, { _, _, _ -> base }, base)
    }
    /** Ruined hall: columns, broken low walls to hide behind. */
    private fun hall(r: Room) {
        val n = r.n
        r.shell({ x, z -> when (r.mix(x, z)) { 0, 1 -> "cracked_stone_bricks"; 2 -> "mossy_stone_bricks"; else -> "stone_bricks" } },
            { x, y, z -> if (y == 65 || (x + z + y) % 7 == 0) "mossy_stone_bricks" else "stone_bricks" }, "stone_bricks")
        for (x in listOf(n / 4, 3 * n / 4 - 1)) for (z in listOf(n / 4, 3 * n / 4 - 1)) {
            r.pillar(x, z, "chiseled_stone_bricks", 2)
            r.hanging(x - 2, z)
        }
        for (i in 0..3) {
            r.solid(n / 2 - 7 + i, 65, 5, "stone_brick_wall");r.solid(n / 2 + 4 + i, 65, n - 5, "stone_brick_wall")
            r.solid(5, 65, n / 2 + 4 + i, "cobblestone_wall");r.solid(n - 5, 65, n / 2 - 7 + i, "cobblestone_wall")
        }
        r.hanging(n / 2, n / 2, "lantern")
        for (x in listOf(2, n - 2)) for (z in listOf(2, n - 2)) r.set(x, r.top, z, "cobweb")
    }
    /** Low crypt: rows of tombs split the room into lanes, cold light. */
    private fun crypt(r: Room) {
        val n = r.n
        r.shell({ x, z -> when (r.mix(x, z)) { 0 -> "cracked_deepslate_tiles"; 1, 2 -> "deepslate_tiles"; else -> "deepslate_bricks" } },
            { x, y, z -> if ((x + z) % 5 == 0) "polished_basalt" else if (y == 65) "cracked_deepslate_bricks" else "deepslate_bricks" }, "deepslate_tiles")
        for (x in listOf(6, n / 2 - 2, n - 8)) for (z in listOf(7, n - 8)) {
            for (dx in 0..2) { r.solid(x + dx, 65, z, "polished_deepslate");r.solid(x + dx, 65, z + 1, "polished_deepslate") }
            r.solid(x + 1, 66, z, "candle[candles=3,lit=true]")
        }
        for (x in listOf(3, n - 3)) for (z in listOf(n / 2 - 5, n / 2 + 5)) r.pillar(x, z, "polished_basalt[axis=y]")
        for (x in listOf(n / 4, 3 * n / 4)) for (z in listOf(n / 4, 3 * n / 4)) r.hanging(x, z, "soul_lantern")
        r.hanging(n / 2, n / 2, "soul_lantern")
        for (x in listOf(1, n - 1)) for (z in listOf(1, n - 1)) { r.set(x, r.top, z, "cobweb");r.set(x, r.top - 1, z, "cobweb") }
    }
    /** Old mine: timber frames, rock piles, ore in the walls, a rail through the middle. */
    private fun mine(r: Room) {
        val n = r.n
        val ores = listOf("coal_ore", "iron_ore", "copper_ore", "gold_ore", "redstone_ore")
        r.shell({ x, z -> when (r.mix(x, z)) { 0 -> "gravel";1 -> "cobblestone";2 -> "andesite";else -> "stone" } },
            { x, y, z -> val m = (x * 3 + y * 5 + z * 7) % 23; if (m < ores.size) ores[m] else if (m % 5 == 0) "andesite" else "stone" }, "stone")
        for (x in 1 until n) if (r.free(x, n / 2 + 3)) r.set(x, 65, n / 2 + 3, "rail[shape=east_west]")
        for (z in listOf(8, n - 8)) for (x in listOf(7, n / 2, n - 7)) {
            r.solid(x - 2, 65, z, "oak_log");r.solid(x + 2, 65, z, "oak_log")
            for (y in 66..68) { r.solid(x - 2, y, z, "oak_log");r.solid(x + 2, y, z, "oak_log") }
            for (dx in -2..2) r.solid(x + dx, 69, z, "oak_planks")
            r.set(x, 68, z, "lantern[hanging=true]")
        }
        for ((x, z) in listOf(5 to n / 2 - 5, n - 6 to n / 2 + 6, n / 2 - 6 to 4, n / 2 + 6 to n - 4)) {
            for (dx in 0..2) for (dz in 0..1) r.solid(x + dx, 65, z + dz, "cobblestone")
            r.solid(x + 1, 66, z, "cobblestone")
        }
    }
    /** Forge: hot floor patches to step around, lava in cauldrons for light, iron partitions. */
    private fun forge(r: Room) {
        val n = r.n
        r.shell({ x, z ->
            val hot = (x % 9 in 2..4 && z % 9 in 2..4) && r.free(x, z)
            if (hot) "magma_block" else when (r.mix(x, z)) { 0 -> "cracked_nether_bricks";1 -> "blackstone";else -> "nether_bricks" }
        }, { x, y, z -> if (y == 65) "polished_blackstone_bricks" else if ((x + z) % 6 == 0) "basalt" else "nether_bricks" }, "nether_bricks")
        for (x in listOf(n / 4, 3 * n / 4)) for (z in listOf(n / 4, 3 * n / 4)) {
            r.pillar(x, z, "polished_blackstone")
            r.solid(x + 1, 65, z, "lava_cauldron")
            r.hanging(x, z + 2, "lantern")
        }
        for (i in 0..4) {
            r.solid(n / 2 - 2 + i, 65, 8, "iron_bars");r.solid(n / 2 - 2 + i, 66, 8, "iron_bars")
            r.solid(n / 2 - 2 + i, 65, n - 8, "iron_bars");r.solid(n / 2 - 2 + i, 66, n - 8, "iron_bars")
        }
        r.solid(n / 2 + 5, 65, n / 2 + 5, "anvil");r.solid(n / 2 - 5, 65, n / 2 - 5, "blast_furnace")
        r.hanging(n / 2, n / 2, "lantern")
    }
}
