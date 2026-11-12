package com.alytanta.oneblock.player

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

class ProfileSettingsTest {
    @TempDir lateinit var directory: Path
    @Test fun `names are balanced in complete cycles and blank entries are ignored`() {
        val settings = ProfileSettings(names=(1..46).associateWith { mapOf("en" to "Name$it", "uk" to "Назва$it") } + (47 to mapOf("en" to "")))
        val used = mutableListOf<Int?>()
        repeat(92) { used += settings.choose(used) }
        assertEquals((1..46).toSet(),used.toSet())
        assertTrue(used.groupingBy { it }.eachCount().values.all { it == 2 })
        assertEquals("Name1",settings.name(1,"en"))
        assertEquals("Назва1",settings.name(1,"uk"))
    }
    @Test fun `public IDs fill gaps and stop after 9999`() {
        assertEquals("0001",ProfileIdentity.nextId(emptySet()))
        assertEquals("0002",ProfileIdentity.nextId(setOf("0001","0003","0004","0005")))
        assertNull(ProfileIdentity.nextId((1..9999).map { it.toString().padStart(4,'0') }.toSet()))
    }
    @Test fun `bundled config exposes eighteen individually configured slots and forty six names`() {
        val file = directory.resolve("profiles.yml").toFile()
        file.writeText(javaClass.getResourceAsStream("/profiles.yml")!!.reader(Charsets.UTF_8).use { it.readText() })
        val config = ProfileSettings.read(file).first
        assertEquals(5,config.visibleSlots); assertEquals(2,config.availableSlots)
        assertEquals(18,config.slots.size); assertEquals(46,config.names.size)
    }
    @Test fun `small profile menu permits hidden slots but rejects control collisions and malformed positions`() {
        val file = directory.resolve("profiles.yml").toFile()
        val y = org.bukkit.configuration.file.YamlConfiguration().apply {
            loadFromString(javaClass.getResourceAsStream("/profiles.yml")!!.reader().use { it.readText() })
            set("rows", 1); (1..18).forEach { set("profile-slot-$it.enabled", false) }
            set("profile-slot-1.enabled", true); set("profile-slot-1.slot", 3)
            set("close.slot", 6); set("coop-button.slot", 5); set("show-removal-hint", false)
            set("back.slot",0)
        }
        y.save(file)
        val parsed = ProfileSettings.read(file).first
        assertEquals(1, parsed.rows); assertEquals(1, parsed.slots.count { it.enabled })
        assertFalse(parsed.showRemovalHint); assertEquals(5, parsed.coopSlot)
        y.set("coop-button.slot", 3); y.save(file)
        assertThrows(IllegalArgumentException::class.java) { ProfileSettings.read(file) }
        y.set("coop-button.slot", "bad"); y.save(file)
        assertThrows(IllegalArgumentException::class.java) { ProfileSettings.read(file) }
    }
}
