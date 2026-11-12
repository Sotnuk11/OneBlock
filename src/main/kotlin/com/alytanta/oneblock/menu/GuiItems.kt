package com.alytanta.oneblock.menu

import com.alytanta.oneblock.market.MarketMenuButton
import com.alytanta.oneblock.market.MarketWindow
import com.alytanta.oneblock.util.Colors
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.meta.SkullMeta
import org.bukkit.plugin.Plugin
import java.util.UUID

/** Always returns a display copy. Escrow, player inventory and exact serialized stacks are never styled. */
object GuiItems {
    /** Do not ask Mojang to complete offline UUIDs just to paint a menu icon. Known signed skins remain intact. */
    fun viewerSkin(meta:SkullMeta,player:Player) {
        val profile=player.playerProfile
        meta.ownerProfile=profile.takeIf {it.properties.any {property->property.name=="textures" && property.value.isNotBlank()}}
    }
    /** Background panes show no empty tooltip when hovered. */
    fun quiet(item:ItemStack):ItemStack {
        if(item.type.name.endsWith("GLASS_PANE"))item.editMeta {meta->
            if(meta.hasDisplayName() && net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().serialize(meta.displayName()!!).isBlank())meta.isHideTooltip=true
        }
        return item
    }
    fun copy(plugin:Plugin,player:Player,original:ItemStack,style:MarketMenuButton?,values:Map<String,String> = emptyMap()):ItemStack {
        val result=original.clone()
        if(style==null) {com.alytanta.oneblock.items.CustomItems.ensureVisibleId(result);return result}
        fun expand(s:String):String {
            val auto=mutableMapOf("viewer" to player.name,"player" to (values["player"]?:player.name))
            val plain=net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText()
            if("{label}" in s && "label" !in values)auto["label"]=original.itemMeta?.displayName()?.let(plain::serialize)?:original.type.name
            if("{body}" in s && "body" !in values)auto["body"]=original.itemMeta?.lore().orEmpty().joinToString("\n",transform=plain::serialize)
            return (auto+values).entries.fold(s) {text,(key,value)->text.replace("{$key}",value)}
        }
        style.icon?.let {result.type=it}
        if(style.texture!=null)result.type=Material.PLAYER_HEAD
        style.amount?.let {result.amount=it.coerceAtMost(result.maxStackSize)}
        result.editMeta { meta ->
            style.name?.let {meta.displayName(Colors.colorize(expand(it)))}
            style.lore?.let {meta.lore(it.flatMap {line->expand(line).split('\n')}.map(Colors::colorize))}
            style.glow?.let(meta::setEnchantmentGlintOverride)
            style.customModelData?.let(meta::setCustomModelData)
            style.model?.let {meta.setItemModel(NamespacedKey.fromString(it))}
            if(meta is SkullMeta)style.texture?.let { texture ->
                if(texture=="{player}")viewerSkin(meta,player)
                else {
                    val profile=plugin.server.createPlayerProfile(UUID.nameUUIDFromBytes(texture.toByteArray(Charsets.UTF_8)))
                    profile.setTextures(profile.textures.apply {setSkin(MenuConfiguration.skinUrl(texture))});meta.ownerProfile=profile
                }
            }
        }
        com.alytanta.oneblock.items.CustomItems.ensureVisibleId(result)
        return result
    }
    fun background(plugin:Plugin,player:Player,inventory:org.bukkit.inventory.Inventory,layout:MarketWindow,fallback:Material?=null,
        factory:(Material)->ItemStack={material->quiet(ItemStack(material).apply {editMeta {it.displayName(Colors.colorize(" "))}})},
        styled:(ItemStack,MarketMenuButton?)->ItemStack={item,style->copy(plugin,player,item,style)}) {
        val style=layout.background
        val material=style?.icon ?: (if(layout.inheritFiller)fallback else layout.filler)
        if(material!=null && style?.enabled!=false) {
            val raw=factory(material)
            val fill=styled(raw,style)
            repeat(inventory.size) {inventory.setItem(it,fill.clone())}
        }
        layout.decorations.forEach { (slot,spec)->
            val raw=factory(spec.material)
            inventory.setItem(slot,styled(raw,MarketMenuButton(icon=spec.material,name=spec.name,lore=spec.lore,amount=spec.amount,
                glow=spec.glow,customModelData=spec.customModel,texture=spec.texture,model=spec.model)))
        }
    }
}

fun MarketWindow.guiStyle(source:Int,template:String="content",state:String="available"):MarketMenuButton? {
    val base=(templates[template] ?: if(template in setOf("buys","sells","quantities"))templates["content"] else null)?.state(state)
    val control=buttons[source]?.state(state) ?: return base
    return control.copy(icon=control.icon?:base?.icon,name=control.name?:base?.name,lore=control.lore?:base?.lore,
        amount=control.amount?:base?.amount,glow=control.glow?:base?.glow,customModelData=control.customModelData?:base?.customModelData,
        texture=control.texture?:base?.texture,model=control.model?:base?.model,enabled=control.enabled && base?.enabled!=false)
}
