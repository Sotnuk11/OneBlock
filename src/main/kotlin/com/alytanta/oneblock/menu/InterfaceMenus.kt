package com.alytanta.oneblock.menu

import com.alytanta.oneblock.market.MarketMenuButton
import com.alytanta.oneblock.market.MarketMenuLayouts
import com.alytanta.oneblock.market.MarketWindow
import org.bukkit.configuration.file.YamlConfiguration
import java.io.File

/** Layouts for dynamic profile and administration screens; actions keep their server-side identity. */
object InterfaceMenus {
    private fun window(id: String, controls: List<Int>, rows: Int = 6, content: List<Int>? = null) =
        MarketWindow(id = id, rows = rows, buttons = controls.associateWith { MarketMenuButton(slot = it) },
            grids = content?.let { mapOf("content" to it) }.orEmpty())
    val catalog = GuiContracts.all.keys.filter {it.startsWith("profile-") || it.startsWith("admin-")}.associateWith {GuiContracts.template(it)}
    fun defaults() = MarketMenuLayouts(windows = catalog)
    fun read(file: File): Pair<MarketMenuLayouts, String?> {
        val result = MarketMenuLayouts.read(file, catalog)
        result.second?.let { text ->
            val yaml = YamlConfiguration().apply { loadFromString(text) }
            require(yaml.isInt("version") && yaml.getInt("version") == 1) { "interface-menus.yml version must be 1" }
        }
        return result
    }
}

fun MarketWindow.style(source: Int, item: MenuItemSpec,state:String="available"): MenuItemSpec? {
    if (target(source) == null) return null
    val style = guiStyle(source,if(grids["content"]?.contains(source)==true)"content" else "control",state) ?: return item
    if(!style.enabled)return null
    return item.copy(material = style.icon ?: item.material, name = style.name ?: item.name, lore = style.lore ?: item.lore,
        amount = style.amount ?: item.amount, glow = style.glow ?: item.glow,
        customModel = style.customModelData ?: item.customModel,texture=style.texture?:item.texture,model=style.model?:item.model)
}
