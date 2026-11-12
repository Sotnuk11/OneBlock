package com.alytanta.oneblock.trade

import org.bukkit.GameMode
import org.bukkit.Material
import org.bukkit.configuration.file.YamlConfiguration
import java.io.File
import com.alytanta.oneblock.market.MarketMenuButton
import com.alytanta.oneblock.market.MarketMenuLayouts

data class TradeSettings(val enabled:Boolean=true,val distance:Double=6.0,val requestSeconds:Long=30,val sessionSeconds:Long=120,
    val countdownSeconds:Int=5,val changeCooldownSeconds:Int=5,val maxCoins:Long=1_000_000_000,val maxItemBytes:Int=262144,
    val allowedModes:Set<GameMode> = setOf(GameMode.SURVIVAL,GameMode.ADVENTURE,GameMode.CREATIVE),val blocked:Set<String> = emptySet(),
    val ownSlots:List<Int> = (0 until 16).map { it/4*9+it%4 },val otherSlots:List<Int> = (0 until 16).map { 5+it/4*9+it%4 },
    val ownCoins:Int=36,val otherCoins:Int=44,val ownReady:Int=39,val otherReady:Int=41,val cancel:Int=49,
    val ownName:Int=37,val otherName:Int=43,val status:Int=40,val filler:Material=Material.BLACK_STAINED_GLASS_PANE,
    val divider:Material=Material.GRAY_STAINED_GLASS_PANE,val historyLimit:Int=1000,
    val dividerSlots:List<Int> = listOf(4,13,22,31), val icons:Map<String,Material> = mapOf("own-name" to Material.PLAYER_HEAD,
        "other-name" to Material.PLAYER_HEAD,"own-coins" to Material.GOLD_NUGGET,"other-coins" to Material.GOLD_INGOT,
        "ready" to Material.LIME_TERRACOTTA,"not-ready" to Material.RED_TERRACOTTA,"status" to Material.CLOCK,"cancel" to Material.BARRIER),
    val rows:Int=6, val title:String?=null, val styles:Map<String,MarketMenuButton> = emptyMap(),
    val ownItemLore:List<String>?=null, val otherItemLore:List<String>?=null) {
    fun style(id:String)=styles[id]?.let {s->s.state(if(id in s.states)id else if(id in setOf("own-ready","other-ready"))"selected" else "available")}
    fun visible(id:String) = style(id)?.enabled != false
    fun actionAt(slot:Int,ready:Boolean):String?=when {
        slot==cancel && visible("cancel")->"cancel"
        slot==ownCoins && visible("own-coins")->"coins"
        slot==ownReady && visible(if(ready)"own-ready" else "own-wait")->"ready"
        else->null
    }
    companion object {
        val styleIds = setOf("own-name","other-name","own-coins","other-coins","own-ready","other-ready",
            "own-wait","other-wait","status","countdown","cancel","filler","divider","own-item","other-item")
        fun read(file:File):Pair<TradeSettings,String?> {
            if(!file.exists())return TradeSettings() to null
            val source=file.readText(Charsets.UTF_8);com.alytanta.oneblock.util.StrictYaml.validate(source);val y=YamlConfiguration().apply { loadFromString(source) };val d=TradeSettings()
            fun value(key:String,default:Long,min:Long=0,max:Long=1_000_000_000_000):Long =
                (if(y.contains(key))requireNotNull(y.get(key)?.toString()?.toLongOrNull()) { "$key must be integer" } else default).also { require(it in min..max) }
            require(!y.contains("enabled") || y.isBoolean("enabled"))
            require(!y.contains("distance") || y.get("distance") is Number)
            listOf("menu","menu.icons","menu.styles").forEach { require(!y.contains(it)||y.isConfigurationSection(it)) }
            val rows=value("menu.rows",6,1,6).toInt()
            require(!y.contains("menu.title") || y.isString("menu.title"))
            require(y.getString("menu.title")?.none { it == '\r' || it == '\n' } != false) { "menu.title must be one line" }
            val styles=y.getConfigurationSection("menu.styles")?.getKeys(false)?.associateWith { key ->
                require(key in styleIds) { "Unknown trade style: $key" }
                val part=requireNotNull(y.getConfigurationSection("menu.styles.$key"))
                require(!part.contains("slot") || key in setOf("own-name","other-name","own-coins","other-coins","own-ready","other-ready","cancel","status")) { "State/item styles do not move controls" }
                MarketMenuLayouts.readStyle(part).copy(slot=if(part.contains("slot"))part.getInt("slot").also {require(part.isInt("slot") && it in 0..53)} else null)
            }.orEmpty()
            require(listOf("own-item","other-item").all {styles[it]?.state("available")?.enabled!=false}) {"Offered items must stay visible to both participants"}
            fun lore(key:String):List<String>? = if(!y.contains(key)) null else {
                require(y.isList(key) && y.getList(key)!!.all { it is String }) { "$key must be text lines" }
                y.getStringList(key)
            }
            fun integers(key:String):List<Int> = requireNotNull(y.getList(key)) { "$key must be a list" }.map {
                requireNotNull(it.toString().toIntOrNull()) { "$key must contain integers" }
            }
            fun slots(key:String,default:List<Int>)=if(!y.contains(key))default else integers(key).also { require(it.isNotEmpty()&&it.size<=16&&it.distinct().size==it.size&&it.all { slot->slot in 0..53 }) }
            val own=slots("menu.own-items",d.ownSlots);val other=slots("menu.other-items",d.otherSlots)
            require(own.size==other.size)
            val controls=listOf("own-coins","other-coins","own-ready","other-ready","cancel","own-name","other-name","status")
            val defaults=listOf(36,44,39,41,49,37,43,40)
            val positions=controls.mapIndexed { i,key->styles[key]?.slot?:value("menu.$key",defaults[i].toLong(),0,53).toInt() }
            require((own+other+positions).distinct().size==own.size+other.size+positions.size)
            fun material(key:String,fallback:Material)=y.getString(key)?.let { requireNotNull(Material.matchMaterial(it)) }?:fallback
            val dividers=if(y.contains("menu.divider-slots"))integers("menu.divider-slots") else d.dividerSlots
            require(dividers.distinct().size==dividers.size && dividers.all { it in 0..53 && it !in own+other+positions })
            require((own+other+positions+dividers).all { it < rows*9 }) { "Trade slot outside menu.rows" }
            require(y.getConfigurationSection("menu.icons")?.getKeys(false).orEmpty().all { it in d.icons })
            val icons=d.icons.mapValues { (key,fallback)->material("menu.icons.$key",fallback) }
            val modes=if(y.contains("allowed-game-modes"))y.getStringList("allowed-game-modes").map { GameMode.valueOf(it.uppercase()) }.toSet() else d.allowedModes
            require(modes.isNotEmpty()&&GameMode.SPECTATOR !in modes)
            val blocked=if(y.contains("blocked-items")) requireNotNull(y.getList("blocked-items")).map {
                requireNotNull(Material.matchMaterial(it.toString())).name
            }.toSet() else emptySet()
            return TradeSettings(y.getBoolean("enabled",true),y.getDouble("distance",6.0).also { require(it.isFinite()&&it in 1.0..32.0) },
                value("request-seconds",30,5,120),value("session-seconds",120,15,600),value("countdown-seconds",5,1,30).toInt(),
                value("change-cooldown-seconds",5,1,30).toInt(),value("max-coins",1_000_000_000,1),value("max-item-bytes",262144,1024,1048576).toInt(),
                modes,blocked,own,other,positions[0],positions[1],positions[2],positions[3],positions[4],positions[5],positions[6],positions[7],
                material("menu.filler",d.filler),material("menu.divider",d.divider),value("history-limit",1000,100,100000).toInt(),dividers,icons,
                rows,y.getString("menu.title"),styles,lore("menu.own-item-lore"),lore("menu.other-item-lore")) to source
        }
    }
}
