package com.alytanta.oneblock.dungeon

import com.alytanta.oneblock.market.*
import com.alytanta.oneblock.menu.MenuItemSpec
import com.alytanta.oneblock.storage.OperationLog
import com.alytanta.oneblock.util.*
import org.bukkit.Material
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import java.util.UUID
import java.util.concurrent.*

class MineAdministration(private val mine:MineController,private val editor:MineSettingsEditor):AutoCloseable {
    val signs=VirtualSignInput(mine.plugin)
    private val io=Executors.newSingleThreadExecutor {r->Thread(r,"oneblock-mine-admin").apply {isDaemon=true}}
    private data class Confirmation(val command:String,val until:Long)
    private val confirmations=ConcurrentHashMap<String,Confirmation>()
    private val pages=ConcurrentHashMap<UUID,Int>()
    var openProfile:(Player,UUID)->Unit={_,_->}
    private fun permitted(sender:CommandSender,permission:String="manage")=sender.isOp && sender.hasPermission("oneblock.admin") && sender.hasPermission("oneblock.admin.$permission")
    private fun reply(sender:CommandSender,key:String,vararg args:Pair<String,Any?>) {
        if(sender is Player)FoliaTasks.entity(mine.plugin,sender) {mine.say(sender,key,*args)}
        else sender.sendMessage(Colors.colorize(mine.settings.text(key,*args)))
    }
    private fun audit(sender:CommandSender,action:String,details:Map<String,Any?> = emptyMap())=mine.storage.audit(OperationLog(operation="MINE_ADMIN_$action",actor=(sender as? Player)?.uniqueId,account=null,success=true,status="SUCCESS",details=details+("operator" to sender.name)))
    private fun confirm(sender:CommandSender,args:List<String>):Boolean {
        val key=(sender as? Player)?.uniqueId?.toString()?:"console:${sender.name}"
        val command=args.filterNot {it=="CONFIRM"}.joinToString(" ")
        val previous=confirmations.remove(key)
        if(args.lastOrNull()=="CONFIRM" && previous?.command==command && System.currentTimeMillis()<=previous.until)return true
        confirmations[key]=Confirmation(command,System.currentTimeMillis()+30000);reply(sender,"admin-confirm","command" to "/ob admin dungeon $command")
        if(sender is Player)mine.menu.show(sender,"mine-admin-confirm")
        return false
    }
    fun handle(sender:CommandSender,args:List<String>) {
        if(!permitted(sender,if(args.firstOrNull() in listOf(null,"list","info","players","player"))"inspect" else "manage")) {reply(sender,"denied");return}
        try {
            when(args.firstOrNull()?.lowercase()) {
                null->if(sender is Player)mine.menu.show(sender,"mine-admin") else reply(sender,"admin-usage")
                "list"->if(sender is Player)sessions(sender) else mine.engine.all().forEach {s->reply(sender,"status","name" to s.id,"phase" to s.phase,"deaths" to s.deaths,"attempts" to s.remaining,"world" to s.world)}
                "info"->{val run=requireNotNull(mine.engine.get(UUID.fromString(args[1])));reply(sender,"status","name" to run.id,"phase" to run.phase,"deaths" to run.deaths,"attempts" to run.remaining,"world" to run.world)}
                "stop","stop-all"->{if(!confirm(sender,args))return
                    val runs=if(args[0]=="stop-all")mine.engine.all().filter {!it.worldRemoved} else listOf(requireNotNull(mine.engine.get(UUID.fromString(args[1]))))
                    runs.forEach {s->mine.engine.abort(s.id,"administrator").thenAccept {audit(sender,"STOP",mapOf("session" to s.id,"world" to s.world));reply(sender,"admin-stopped")}}
                }
                "settings"->if(sender is Player)settings(sender) else reply(sender,"admin-usage")
                "set"->{require(args.size>=3);val field=args[1];val value=args.drop(2).joinToString(" ")
                    CompletableFuture.supplyAsync({val view=editor.view();editor.edit(view,field,value)},io).whenComplete {next,error->
                        if(error==null) {mine.settings=next;audit(sender,"SET",mapOf("field" to field));reply(sender,"admin-saved")} else reply(sender,"unavailable")
                    }}
                "entry"->{val player=sender as? Player?:error("Player required");require(mine.hub.isHubWorld(player.world) && !mine.managed(player.world));val at=player.location
                    val point=MineEntry(player.world.key().asString(),MinePoint(at.x,at.y,at.z,at.yaw))
                    CompletableFuture.supplyAsync({val view=editor.view();editor.entry(view.source,point)},io).whenComplete {next,error->if(error==null){mine.settings=next;audit(sender,"ENTRY");reply(sender,"admin-saved")}else reply(sender,"unavailable")}}
                "players"->if(sender is Player)players(sender) else reply(sender,"admin-usage")
                "player"->{val id=resolve(args[1])?:error("Unknown player");if(sender is Player)player(sender,id) else info(sender,id)}
                "sendhub","heal","tp"->{val id=resolve(args[1])?:error("Unknown player");val target=mine.plugin.server.getPlayer(id)?:error("Offline")
                    if(args[0]=="sendhub" && !confirm(sender,args))return
                    mine.entity(target) {
                        require(!InventoryGuard.busy(id))
                        when(args[0]) {
                            "heal"->{require(!target.isDead);target.health=target.getAttribute(org.bukkit.attribute.Attribute.MAX_HEALTH)!!.value;target.foodLevel=20;target.saturation=10f}
                            "sendhub"->{val s=mine.binding(id)
                                if(s!=null)mine.engine.update(s.id) {r->val dead=MineState.death(r,id,true,System.currentTimeMillis());val m=dead.members.getValue(id);MineState.wipe(dead.copy(members=dead.members+(id to m.copy(life=MineLife.LEFT,inWorld=false))))}.thenAccept {r->mine.entity(target) {mine.returnPlayer(target,r)}}
                                else mine.hub.teleport(target)}
                            "tp"->{val viewer=sender as? Player?:error("Player required");require(!mine.managed(target.world)) {"Use session information; non-members cannot enter an instance"};val location=target.location.clone();mine.entity(viewer) {require(!mine.profileLocked(viewer.uniqueId));viewer.teleportAsync(location)}}
                        }
                        audit(sender,args[0].uppercase(),mapOf("player" to id))
                    }.exceptionally {reply(sender,"unavailable");null}
                }
                else->reply(sender,"admin-usage")
            }
        } catch(e:Exception) {reply(sender,"admin-usage")}
    }
    private fun resolve(raw:String):UUID?=runCatching {UUID.fromString(raw)}.getOrNull()?:mine.storage.loadProfiles().firstOrNull {it.lastKnownName?.equals(raw,true)==true}?.uniqueId
    fun sessions(player:Player,requested:Int=0) {
        if(!permitted(player,"inspect"))return
        val identity=mine.menu.configurationIdentity
        val runs=mine.engine.all().sortedByDescending {it.created}
        val page=page(player,"mine-admin-sessions",requested,runs.size)
        mine.menu.page(player,"mine-admin-sessions",runs.map {s->s.id.toString() to MenuItemSpec(Material.IRON_PICKAXE,s.settings.name,listOf(s.world,"${s.phase} | ${s.members.size} | ${s.deaths} | ${s.remaining}",mine.settings.text("admin-stop-hint")))},page) {id->
            if(identity===mine.menu.configurationIdentity && permitted(player))handle(player,listOf("stop",id))
        }
    }
    fun settings(player:Player,requested:Int=0) {
        if(!permitted(player))return
        val identity=mine.menu.configurationIdentity
        CompletableFuture.supplyAsync(editor::view,io).thenAccept {view->mine.entity(player) {
            if(identity!==mine.menu.configurationIdentity)return@entity
            val page=page(player,"mine-admin-settings",requested,view.fields.size)
            mine.menu.page(player,"mine-admin-settings",view.fields.map {f->f.path to MenuItemSpec(Material.PAPER,f.path,listOf(f.value,mine.settings.text("admin-edit-hint")))},page) {path->
                if(!permitted(player) || identity!==mine.menu.configurationIdentity)return@page
                val field=view.fields.single {it.path==path}
                if(field.value.length>90 || '\n' in field.value) {reply(player,"admin-long-value","field" to path);return@page}
                signs.openLines(player,listOf(net.kyori.adventure.text.Component.text(field.value),net.kyori.adventure.text.Component.text(path.take(90)),Colors.colorize(mine.settings.text("admin-edit-hint")),Colors.colorize(mine.settings.text("admin-new-sessions"))),0,1200) {value->
                    if(value==null || !permitted(player) || identity!==mine.menu.configurationIdentity)return@openLines
                    CompletableFuture.supplyAsync({editor.edit(view,path,value)},io).whenComplete {next,error->
                        if(error==null) {mine.settings=next;audit(player,"SET",mapOf("field" to path));reply(player,"admin-saved")} else reply(player,"unavailable")
                    }
                }
            }
        }}.exceptionally {reply(player,"unavailable");null}
    }
    fun players(player:Player,requested:Int=0) {
        if(!permitted(player,"inspect"))return
        val identity=mine.menu.configurationIdentity
        val entries=mine.storage.loadProfiles().sortedBy {it.lastKnownName}.map {p->p.uniqueId.toString() to MenuItemSpec(Material.PLAYER_HEAD,p.lastKnownName?:p.uniqueId.toString(),listOf(p.uniqueId.toString(),p.activeAccount?.toString()?:"—"))}
        val page=page(player,"mine-admin-players",requested,entries.size)
        mine.menu.page(player,"mine-admin-players",entries,page) {id->if(identity===mine.menu.configurationIdentity)player(player,UUID.fromString(id))}
    }
    private val selected=ConcurrentHashMap<UUID,UUID>()
    private fun info(sender:CommandSender,id:UUID) {
        val p=mine.players.find(id);val s=mine.binding(id)
        reply(sender,"admin-player","player" to (p?.lastKnownName?:id),"account" to (p?.activeAccount?:"—"),"session" to (s?.id?:"—"),"phase" to (s?.phase?:"—"),"guard" to InventoryGuard.busy(id))
    }
    fun player(viewer:Player,id:UUID) {
        if(!permitted(viewer,"inspect"))return
        selected[viewer.uniqueId]=id;info(viewer,id);mine.menu.show(viewer,"mine-admin-player")
    }
    private fun page(player:Player,id:String,requested:Int,size:Int):Int=requested.coerceIn(0,(size-1).coerceAtLeast(0)/(mine.menu.definition(id)?.content?.size?.coerceAtLeast(1)?:28)).also {pages[player.uniqueId]=it}
    fun navigation(viewer:Player,delta:Int) {
        val page=(pages[viewer.uniqueId]?:0)+delta
        when(mine.menu.currentMenuId(viewer)) {"mine-admin-settings"->settings(viewer,page);"mine-admin-players"->players(viewer,page);"mine-admin-sessions"->sessions(viewer,page)}
    }
    fun confirmed(viewer:Player) {
        val command=confirmations[viewer.uniqueId.toString()]?.command?:return
        handle(viewer,command.split(' ')+"CONFIRM")
    }
    fun selectedAction(viewer:Player,action:String) {selected[viewer.uniqueId]?.let {id->
        if(action=="profile") {if(permitted(viewer,"inspect"))mine.players.find(id)?.activeAccount?.let {openProfile(viewer,it)}}
        else handle(viewer,listOf(action,id.toString()))
    }}
    fun invalidate() {signs.cancelAll();confirmations.clear();selected.clear();pages.clear()}
    override fun close() {invalidate();io.shutdown();check(io.awaitTermination(10,TimeUnit.SECONDS))}
}
