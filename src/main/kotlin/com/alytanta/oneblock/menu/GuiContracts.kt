package com.alytanta.oneblock.menu

import com.alytanta.oneblock.market.*
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

/** Stable server action IDs. Numeric sources are compatibility aliases for pre-v2 configurations only. */
object GuiContracts {
    data class Control(val source:Int,val slot:Int,val enabled:Boolean=true)
    data class Contract(val rows:Int,val controls:Map<String,Control>,val grids:Map<String,List<Int>>)
    val all:Map<String,Contract> by lazy {
        GuiContracts::class.java.getResourceAsStream("/gui-contracts.json")!!.reader(Charsets.UTF_8).use {
            Gson().fromJson(it,object:TypeToken<Map<String,Contract>>(){}.type)
        }
    }
    fun identity(window:String,source:Int):String = all[window.substringBefore('/')]?.controls?.entries
        ?.firstOrNull {it.value.source==source}?.key ?: "entry:$source"
    fun source(window:String,action:String):Int = all[window.substringBefore('/')]?.controls?.get(action)?.source
        ?: error("$window: unknown action ID $action")
    fun template(id:String,base:MarketWindow=MarketWindow()):MarketWindow {
        val c=all[id] ?: return AuctionLayouts.window(id,base)
        val inherited=base.buttons.mapValues {(source,style)->if(c.rows<6 && (style.slot?:c.controls.values.firstOrNull {it.source==source}?.slot?:source)>=c.rows*9)style.copy(enabled=false) else style}
        return base.copy(id=id,rows=c.rows,buttons=c.controls.values.associate { it.source to MarketMenuButton(slot=it.slot,enabled=it.enabled) }+inherited,
            grids=c.grids+base.grids)
    }
}
