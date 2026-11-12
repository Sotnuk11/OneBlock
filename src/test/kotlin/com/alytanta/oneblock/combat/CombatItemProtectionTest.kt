package com.alytanta.oneblock.combat

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

class CombatItemProtectionTest {
    @Test fun `repair can rename or strip enchantments only while preserving the same combat identity and material`() {
        assertTrue(CombatItemProtection.mayRepair("warden_blade",null,"warden_blade",true))
        assertTrue(CombatItemProtection.mayRepair("warden_blade","warden_blade","warden_blade",true))
        assertFalse(CombatItemProtection.mayRepair("warden_blade",null,null,true))
        assertFalse(CombatItemProtection.mayRepair("warden_blade",null,"warden_blade",false))
        assertFalse(CombatItemProtection.mayRepair(null,"tempered_core",null,true))
        assertFalse(CombatItemProtection.mayRepair("warden_blade","nether_edge","warden_blade",true))
    }
}
