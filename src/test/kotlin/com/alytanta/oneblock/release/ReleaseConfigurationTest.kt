package com.alytanta.oneblock.release

import com.alytanta.oneblock.menu.MenuConfiguration
import org.bukkit.configuration.file.YamlConfiguration
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

class ReleaseConfigurationTest {
    @TempDir lateinit var folder: Path
    private fun source(path: String)=javaClass.getResourceAsStream("/$path")!!.reader(Charsets.UTF_8).use { it.readText() }
    private fun menus(): MenuConfiguration {
        for(part in listOf("main-menu","release","guides","combat","dungeon"))for(name in listOf("layout.yml","commands.yml","statistics.yml")) {
            val file=folder.resolve("$part/$name").toFile();file.parentFile.mkdirs();file.writeText(source("menus/$part/$name"))
        }
        return MenuConfiguration.read(folder.toFile())
    }
    @Test fun `all bundled release menus actions placeholders and both locales form one complete graph`() {
        val config=menus()
        assertTrue(config.menus.keys.containsAll(listOf("release-core","release-workshop","release-goals","release-claims","release-confirm","release-skills")))
        assertFalse("skills/combat" in config.menus)
        assertEquals(setOf("release-confirm"),config.routes.getValue("release-confirm-action").requiresOpen)
        assertEquals("release",config.routes.getValue("release-recipe-pickaxe_1").type)
        val cells=config.menus.values.flatMap { it.slots.values }.mapNotNull { it.item }
        val texts=cells.flatMap { listOf(it.name)+it.lore }+config.menus.values.map { it.title }
        val keys=texts.filter { it.startsWith("@release.") }.map { it.drop(1) }
        val translations=listOf("uk","en").map { lang -> YamlConfiguration().apply { loadFromString(source("lang/$lang.yml")) } }
        val sets=translations.map { y -> y.getKeys(true).filter { it.startsWith("release.") && y.isString(it) }.toSet() }
        assertEquals(sets[0],sets[1]);assertTrue(sets[0].containsAll(keys))
        for(y in translations)for(key in sets[0])assertTrue(y.getString(key)!!.isNotBlank(),key)
        texts.flatMap { Regex("\\{(release_[a-z0-9_]+)}").findAll(it).map { match -> match.groupValues[1] }.toList() }.forEach { assertTrue(it in config.statistics.values,"Unbound $it") }
        assertTrue(config.menus.getValue("skills").slots.values.none { it.item?.material in setOf(org.bukkit.Material.IRON_SWORD,org.bukkit.Material.FISHING_ROD) })
    }
    @Test fun `duplicate slots IDs and unreachable tier one gate are rejected before publication`() {
        val active=menus();val original=source("progression.yml")
        val file=folder.resolve("rules.yml").toFile()
        val candidates=listOf(original+"\nversion: 1\n",original.replace("pickaxe_1: {\"chapter\":\"workshop\"","pickaxe_1: {\"chapter\":\"developed\"")
            .replace("axe_1: {\"chapter\":\"workshop\"","axe_1: {\"chapter\":\"developed\"").replace("hoe_1: {\"chapter\":\"workshop\"","hoe_1: {\"chapter\":\"developed\""))
        candidates.forEach { candidate -> file.writeText(candidate);assertThrows(Exception::class.java) { ReleaseRules.read(file,true) } }
        val layout=folder.resolve("release/layout.yml").toFile()
        val duplicate=layout.readText().replaceFirst("\"slots\": {","\"slots\": {\n      \"45\": {\"material\": \"STONE\", \"name\": \"Duplicate\"},")
        assertNotEquals(layout.readText(),duplicate)
        layout.writeText(duplicate)
        assertThrows(Exception::class.java) { MenuConfiguration.read(folder.toFile()) }
        assertTrue("release-core" in active.menus)
    }
}
