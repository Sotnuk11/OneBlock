package com.alytanta.oneblock.command

import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerCommandSendEvent

class CommandVisibilityListener : Listener {
    @EventHandler fun commands(event:PlayerCommandSendEvent) {
        event.commands.removeIf { !CommandAccess.visibleRoot(event.player,it) }
    }
}
