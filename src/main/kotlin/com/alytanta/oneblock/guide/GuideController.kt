package com.alytanta.oneblock.guide

import com.alytanta.oneblock.island.IslandService
import com.alytanta.oneblock.menu.*
import com.alytanta.oneblock.locale.LocaleService
import com.alytanta.oneblock.player.PlayerService
import com.alytanta.oneblock.market.InventoryGuard
import com.alytanta.oneblock.release.ReleaseRules
import com.alytanta.oneblock.util.*
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.event.*
import org.bukkit.event.player.*
import org.bukkit.plugin.Plugin
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** Read-only help uses the ordinary PlayerMenu renderer; links go through its permission/parent checks. */
class GuideController(private val plugin:Plugin,private val menus:PlayerMenu,private val islands:IslandService,private val players:PlayerService,
    private val locales:LocaleService,initial:GuideSettings,private val rules:()->ReleaseRules?,private val location:(String)->String) : Listener {
    @Volatile var settings=initial
    var context:()->Map<String,String> = {emptyMap()}
    private data class View(val topic:String,val page:Int,val back:String,val account:UUID?,val config:Any)
    private val views=ConcurrentHashMap<UUID,View>()
    private fun text(p:Player,key:String)=locales.textKey(p,"guides.$key")
    fun command(p:Player,args:List<String>) { if(args.size>1)return;open(p,args.firstOrNull()?:"index") }
    fun openTopic(p:Player,topic:String) = open(p,topic)
    fun action(p:Player,raw:String) {
        val v=views[p.uniqueId]
        when(raw) {
            "index"->open(p,"index")
            "next"->v?.let {open(p,it.topic,it.page+1,it.back)}
            "previous"->v?.let {open(p,it.topic,it.page-1,it.back)}
            "back"->if(v!=null && v.account==islands.findForPlayer(p.uniqueId)?.owner && v.config===menus.configurationIdentity)menus.show(p,v.back) else open(p,"index")
            "link"->{
                if(v==null || v.config!==menus.configurationIdentity || v.account!=islands.findForPlayer(p.uniqueId)?.owner)return
                val t=settings.topics.firstOrNull { it.id==v.topic } ?: return
                if(!islands.persistenceAvailable()) { p.sendMessage(Colors.colorize(text(p,"readonly")));return }
                when(t.action) {
                    "merchant","bazaar","auction"->p.sendMessage(Colors.colorize(text(p,"location").replace("{value}",location(t.action))))
                    else->menus.execute(p,when(t.action) {"profiles"->"profiles";"home"->"home";"core","charge"->"release-core";"skills"->"release-skills";"claims"->"release-claims";"coop"->"coop";"hub"->"hub";else->"release-workshop"})
                }
            }
            else->open(p,raw.removePrefix("topic "),back=menus.currentMenuId(p)?.takeUnless {it in setOf("guide-page","guides-index")}?:"main-menu")
        }
    }
    fun open(p:Player,topic:String="index",requested:Int=0,back:String="main-menu") {
        if(!p.isOnline || p.isDead || InventoryGuard.busy(p.uniqueId) || !p.hasPermission("oneblock.command"))return
        if(topic=="index") {
            views[p.uniqueId]=View(topic,0,back,islands.findForPlayer(p.uniqueId)?.owner,menus.configurationIdentity)
            val entries=settings.topics.filter {it.visible && (it.permission==null || p.hasPermission(it.permission))}.map {
                it.id to MenuItemSpec(it.icon,text(p,"topic.${it.id}"),listOf(text(p,"choose"))) }
            menus.page(p,"guides-index",entries,0) { open(p,it,0,back) };return
        }
        val t=settings.topics.firstOrNull {it.id==topic && it.visible && (it.permission==null || p.hasPermission(it.permission))} ?: run { p.sendMessage(Colors.colorize(text(p,"unknown")));return }
        val values=context()
        val cards=t.pages.mapIndexed { index,key-> index.toString() to MenuItemSpec(t.icon,text(p,"topic.$topic"),values.entries.fold(locales.textKey(p,key)) {line,(k,v)->line.replace("{$k}",v)}.split('\n')) }.toMutableList()
        val r=rules();val island=islands.findForPlayer(p.uniqueId);val state=island?.releaseProgress
        if(!islands.persistenceAvailable())cards+="status" to MenuItemSpec(Material.PAPER,text(p,"state"),listOf(text(p,"readonly")))
        else if(state==null)cards+="status" to MenuItemSpec(Material.PAPER,text(p,"state"),listOf(text(p,"no_profile")))
        else if(r!=null) {
            cards+="status" to MenuItemSpec(Material.BEACON,text(p,"state"),listOf(
                "${locales.textKey(p,"release.name.${state.chapter}")} • ${state.eligibleCoreBreaks}",
                text(p,"coins").replace("{value}",island.coins.toString()),text(p,"charge").replace("{value}",state.charge.toString())))
            if(topic in setOf("skills","workshop","survival"))r.recipes.values
                .filter {topic!="survival" || it.skill==null && it.items.none {output->output.tool!=null}}
                .forEach { recipe ->
                val level=recipe.skill?.let {r.level(state.member(p.uniqueId).xp[it]?:0)} ?: 0
                cards+=recipe.id to recipePreview(recipe,r,island.coins,level) {locales.textKey(p,it)}
            }
            if(topic=="core")r.chapters.drop(1).forEach { gate -> cards+=gate.id to MenuItemSpec(Material.BEACON,locales.textKey(p,"release.name.${gate.id}"),
                listOf("${state.eligibleCoreBreaks}/${gate.breaks} ${text(p,"break_label")}","${state.grownLogs}/${gate.logs} ${text(p,"log_label")}","${island.coins}/${gate.coins} ${text(p,"coin_label")}","${gate.level} ${text(p,"level_label")}")+gate.ingredients.map { "${it.amount} × ${it.materials.joinToString(" / ") {material->com.alytanta.oneblock.release.ReleaseLabels.material(material) {locales.textKey(p,it)}}}" }) }
            if(topic=="charge")r.packs.values.forEach { pack->
                val items=pack.items+if(pack.id=="mineral" && r.atLeast(state.chapter,"depths"))pack.advancedItems else emptyList()
                cards+=pack.id to MenuItemSpec(Material.CHEST,locales.textKey(p,"release.pack.${pack.id}"),listOf("${state.charge}/${pack.points}")+items.map {"${it.amount} × ${com.alytanta.oneblock.release.ReleaseLabels.material(it.material) {locales.textKey(p,it)}}"})
            }
        }
        val size=menus.definition("guide-page")?.content?.size?.takeIf {it>0} ?: return
        val page=requested.coerceIn(0,(cards.size-1).coerceAtLeast(0)/size)
        views[p.uniqueId]=View(topic,page,back,island?.owner,menus.configurationIdentity)
        menus.page(p,"guide-page",cards,page) {}
    }
    @EventHandler fun join(e:PlayerJoinEvent) {
        if(settings.intro && islands.persistenceAvailable() && players.find(e.player.uniqueId)?.guideIntroSeen!=true) {
            players.update(e.player.uniqueId) {it.copy(guideIntroSeen=true)}
            FoliaTasks.entity(plugin,e.player) { e.player.sendMessage(Colors.colorize(text(e.player,"intro"))) }
        }
    }
    @EventHandler fun quit(e:PlayerQuitEvent) {views.remove(e.player.uniqueId)}
    companion object {
        internal fun recipePreview(recipe:com.alytanta.oneblock.release.RecipeRule,rules:ReleaseRules,coins:Long,level:Int,text:(String)->String):MenuItemSpec {
            val output=recipe.items.first()
            val custom=output.tool?.let {rules.customItems?.find(it)}
            fun name(id:String)=com.alytanta.oneblock.release.ReleaseLabels.name(rules,id,text)
            val lore=listOf("${name(recipe.chapter)} • ${recipe.skill?.let(::name)?:"—"} $level/${recipe.level}",
                "$coins/${recipe.coins} ${text("guides.coin_label")}")+recipe.ingredients.map {ingredient->
                "${ingredient.amount} × "+when {
                    ingredient.combatId!=null->name(ingredient.combatId)
                    ingredient.materials==ReleaseRules.LOGS->name("logs")
                    else->ingredient.materials.joinToString(" / ") {com.alytanta.oneblock.release.ReleaseLabels.material(it,text)}
                }
            }
            return MenuItemSpec(Material.valueOf(output.material),custom?.name?:name(recipe.id),lore,customItem=custom?.id)
        }
    }
}
