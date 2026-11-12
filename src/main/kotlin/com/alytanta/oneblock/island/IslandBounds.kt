package com.alytanta.oneblock.island

/** Half-open build area: for size 100 and center 0, blocks -50..49 belong to the island. */
data class IslandBounds(val centerX: Double, val centerZ: Double, val size: Double) {
    init { require(size.isFinite() && size in 16.0..59_999_968.0) }

    fun contains(x: Double, z: Double, margin: Double = 0.0): Boolean =
        x.isFinite() && z.isFinite() &&
            x >= centerX - size / 2 + margin && x < centerX + size / 2 - margin &&
            z >= centerZ - size / 2 + margin && z < centerZ + size / 2 - margin
}
