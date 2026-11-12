package com.alytanta.oneblock.guide

import com.alytanta.oneblock.items.ItemCatalog
import com.alytanta.oneblock.combat.CombatRules
import com.alytanta.oneblock.release.*
import org.bukkit.configuration.file.YamlConfiguration
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.io.File

class GuideItemPreviewTest {
    private fun rules():ReleaseRules {
        val base=ReleaseRules.read(File("src/main/resources/progression.yml"),true).first!!
        val items=ItemCatalog.read(File("src/main/resources/items.yml")).first!!
        val tools=base.recipes.values.flatMap {it.items}.mapNotNull {it.tool}.toSet()
        val combat=CombatRules.read(File("src/main/resources/combat.yml"),items,tools).first!!
        return base.copy(recipes=base.recipes+combat.recipes,combat=combat,customItems=items)
    }
    private fun messages():Map<String,String> {
        val y=YamlConfiguration().apply {load(File("src/main/resources/lang/uk.yml"))}
        return y.getKeys(true).filter(y::isString).associateWith {y.getString(it)!!}
    }
    @Test fun `every custom recipe preview resolves its output ID and uses catalog appearance`() {
        val rules=rules();val messages=messages();val text:(String)->String={messages[it]?:it}
        for(recipe in rules.recipes.values) {
            val card=GuideController.recipePreview(recipe,rules,0,0,text)
            assertFalse(card.name.startsWith("release."),recipe.id)
            assertTrue(card.lore.none {it.contains("release.name.")},recipe.id)
            recipe.items.first().tool?.let {id->
                val item=rules.customItems!!.require(id)
                assertEquals(item.id,card.customItem,recipe.id);assertNull(card.model,recipe.id);assertEquals(item.name,card.name,recipe.id)
            }
            assertFalse(ReleaseLabels.name(rules,recipe.id,text).startsWith("release.name."),recipe.id)
        }
        val temper=GuideController.recipePreview(rules.recipes.getValue("temper_core"),rules,0,0,text)
        assertEquals("oneblock:tempered_core",temper.customItem)
        assertTrue(temper.lore.any {it.contains("Залізний злиток")})
        assertTrue(temper.lore.none {it.contains("PRISMARINE_SHARD")})
    }
    @Test fun `a catalog appearance change reaches the guide without editing its recipe or GUI`() {
        val rules=rules();val catalog=rules.customItems!!;val item=catalog.require("tempered_core")
        val changed=rules.copy(customItems=ItemCatalog(catalog.items+(item.id to item.copy(name="Нове ядро",model="oneblock:battle_fragment"))))
        val card=GuideController.recipePreview(changed.recipes.getValue("temper_core"),changed,100,7) {it}
        assertEquals("Нове ядро",card.name);assertNull(card.model)
        assertEquals("oneblock:tempered_core",card.customItem)
    }
}
