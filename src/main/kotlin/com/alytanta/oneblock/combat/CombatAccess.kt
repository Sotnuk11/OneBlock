package com.alytanta.oneblock.combat

import org.bukkit.GameMode

internal object CombatAccess {
    fun mode(mode:GameMode,creative:Boolean)=mode!=GameMode.SPECTATOR && (mode!=GameMode.CREATIVE || creative)
    fun world(role:String,worlds:Set<String>,hub:Boolean)=if(role=="hub")hub else role in worlds
    fun pvp(worldPvp:Boolean,protectedHub:Boolean,hubPvp:Boolean)=worldPvp && (!protectedHub || hubPvp)
}
