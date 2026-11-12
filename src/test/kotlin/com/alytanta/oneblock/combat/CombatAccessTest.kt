package com.alytanta.oneblock.combat

import org.bukkit.GameMode
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

class CombatAccessTest {
    @Test fun `hub support upgrades legacy world lists without disabling other roles`() {
        val legacy=setOf("island","dungeon","other")
        assertTrue(CombatAccess.world("hub",legacy,true));assertFalse(CombatAccess.world("hub",legacy,false))
        legacy.forEach {assertTrue(CombatAccess.world(it,legacy,true))}
        assertFalse(CombatAccess.world("other",setOf("island"),true))
    }
    @Test fun `creative casting is explicit and spectators never cast`() {
        assertTrue(CombatAccess.mode(GameMode.CREATIVE,true));assertFalse(CombatAccess.mode(GameMode.CREATIVE,false))
        assertTrue(CombatAccess.mode(GameMode.SURVIVAL,false));assertTrue(CombatAccess.mode(GameMode.ADVENTURE,false))
        assertFalse(CombatAccess.mode(GameMode.SPECTATOR,true))
    }
    @Test fun `world pvp and protected hub pvp are independent mandatory gates`() {
        for(hub in listOf(false,true))for(hubPvp in listOf(false,true))assertFalse(CombatAccess.pvp(false,hub,hubPvp))
        assertFalse(CombatAccess.pvp(true,true,false));assertTrue(CombatAccess.pvp(true,true,true));assertTrue(CombatAccess.pvp(true,false,false))
    }
}
