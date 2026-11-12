package com.alytanta.oneblock.hub

import java.util.UUID

/** Own only the exemptions we added; never turn a player into an unpickable projectile target. */
internal class HubCollisionExemptions {
    private val added=mutableSetOf<UUID>()
    fun update(exemptions:MutableSet<UUID>,others:Set<UUID>) {
        val stale=added-others;exemptions.removeAll(stale);added.removeAll(stale)
        for(id in others)if(exemptions.add(id))added.add(id)
    }
    fun clear(exemptions:MutableSet<UUID>) {exemptions.removeAll(added);added.clear()}
}
