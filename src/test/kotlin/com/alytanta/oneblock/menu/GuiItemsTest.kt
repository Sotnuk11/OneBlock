package com.alytanta.oneblock.menu

import com.alytanta.oneblock.market.MarketMenuButton
import net.kyori.adventure.text.Component
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.meta.ItemMeta
import org.bukkit.plugin.Plugin
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.util.function.Consumer

/** Display stand-in; native component serialization stays covered separately by handoff tests/live QA. */
class GuiItemsTest {
    private data class Data(var name:Component=Component.text("Exact tool"),var lore:List<Component> = listOf(Component.text("Original lore")),
        val pdc:MutableMap<String,String> = mutableMapOf("oneblock:tool" to "bound-profile:UUID"),var glow:Boolean=false,var model:Int?=null)
    private class Display(var material:Material=Material.DIAMOND_SWORD,var count:Int=1,val data:Data=Data()):ItemStack() {
        private val metadata=MenuFixture.stub(ItemMeta::class.java) {name,args->when(name) {
            "displayName"->if(args.isNullOrEmpty())data.name else {data.name=args[0] as Component;null}
            "lore"->if(args.isNullOrEmpty())data.lore else { @Suppress("UNCHECKED_CAST") val lines=args[0] as List<Component>;data.lore=lines;null }
            "setEnchantmentGlintOverride"->{data.glow=args!![0] as Boolean;null}
            "setCustomModelData"->{data.model=args!![0] as Int;null}
            else->null
        } }
        override fun getType()=material
        override fun setType(type:Material) {material=type}
        override fun getAmount()=count
        override fun setAmount(amount:Int) {count=amount}
        override fun getMaxStackSize()=64
        override fun getItemMeta():ItemMeta=metadata
        override fun editMeta(consumer:Consumer<in ItemMeta>):Boolean {consumer.accept(metadata);return true}
        override fun clone():ItemStack=Display(material,count,data.copy(lore=data.lore.toList(),pdc=data.pdc.toMutableMap()))
    }
    @Test fun `cosmetics modify only a cloned display and preserve original amount metadata and profile tag`() {
        val plugin=MenuFixture.stub(Plugin::class.java) {_,_->null}
        val player=MenuFixture.stub(Player::class.java) {method,_->if(method=="getName")"Viewer" else null}
        val original=Display();val before=original.data.copy(pdc=original.data.pdc.toMutableMap())
        val shown=GuiItems.copy(plugin,player,original,MarketMenuButton(icon=Material.PAPER,amount=16,name="Styled {label}",lore=listOf("{body}","Owner {viewer}"),glow=true,customModelData=42)) as Display
        assertNotSame(original,shown);assertEquals(Material.PAPER,shown.type);assertEquals(16,shown.amount)
        assertEquals(before,original.data);assertEquals(Material.DIAMOND_SWORD,original.type);assertEquals(1,original.amount)
        assertEquals(before.pdc,shown.data.pdc);assertNotSame(original.data.pdc,shown.data.pdc)
        assertEquals(42,shown.data.model);assertTrue(shown.data.glow)
        assertEquals("Styled Exact tool",net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().serialize(shown.data.name))
    }
}
