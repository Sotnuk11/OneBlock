package com.alytanta.oneblock.island

import java.util.UUID

object IslandWorldIdentity {
    fun worldName(owner: UUID): String = "oneblock_island_$owner"
    fun key(owner: UUID) = net.kyori.adventure.key.Key.key("oneblock", "island_$owner")
    fun matches(world: org.bukkit.World, owner: UUID): Boolean = world.key().asString() == key(owner).asString()
}
