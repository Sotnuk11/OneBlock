package com.alytanta.oneblock.island

import org.bukkit.Location
import org.bukkit.entity.Player
import java.util.UUID

/** The lifecycle operations needed while a dead player's island landing is being prepared. */
interface IslandRespawns {
    fun findByWorld(worldName: String): Island?
    fun findByWorld(world: org.bukkit.World): Island? = findByWorld(world.key().asString())
    fun beginTeleport(owner: UUID): Boolean
    fun endTeleport(owner: UUID)
    fun prepareRespawn(island: Island, callback: (Location?) -> Unit)
    fun applyPlayerBorder(player: Player, island: Island?)
}
