package com.alytanta.oneblock.hub.generator

import com.alytanta.oneblock.hub.HubService
import com.alytanta.oneblock.island.IslandGeneration
import com.alytanta.oneblock.market.MarketSettings
import com.alytanta.oneblock.util.*
import net.thenextlvl.worlds.WorldsAccess
import org.bukkit.*
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import org.bukkit.plugin.Plugin
import java.nio.file.Files
import java.util.UUID
import java.util.concurrent.CompletableFuture
import com.alytanta.oneblock.command.CommandAccess

class HubGeneratorController(private val plugin:Plugin,initial:HubGeneratorSettings,private val hub:HubService,
    private val market:()->MarketSettings,private val guide:(Player,String)->Unit) {
    val port:FoliaHubPort=FoliaHubPort(plugin,{engine.settings},hub) {plan,id,apply->
        if(!plugin.server.pluginManager.isPluginEnabled("FancyNpcs"))CompletableFuture.completedFuture(HubServices(false,listOf("FancyNpcs unavailable; building remains intact")))
        else HubNpcServices.sync(plugin,plan,id,apply,market(),engine.settings)
    }
    val engine:HubGenerator=HubGenerator(initial,HubJournal(plugin.dataFolder.toPath().resolve("hub-jobs")),port)
    private val creating=java.util.concurrent.atomic.AtomicBoolean()
    fun applySettings(next:HubGeneratorSettings)=engine.applySettings(next)
    fun close()=engine.close()
    fun serviceLocation(role:String):String {
        val destination=hub.currentSettings()
        val job=engine.all().lastOrNull {it.state==HubJobState.ACTIVE && it.target.key==destination.world && it.target.contains(destination.x,destination.y,destination.z)}?:return "${engine.settings.sign(role).first()} — /hub"
        val p=engine.plan(job.id).anchors.singleOrNull {it.role==role}?.point?:return "/hub"
        return "${engine.settings.sign(role).first()}: ${job.target.key} ${p.x}/${p.y}/${p.z}"
    }
    private fun actor(sender:CommandSender)=(sender as? Player)?.uniqueId?.toString()?:"console:${sender.name}"
    private fun reply(sender:CommandSender,key:String,vararg values:Pair<String,Any?>) {
        val text=Colors.colorize(engine.settings.message(key,*values))
        if(sender is Player)FoliaTasks.entity(plugin,sender) {sender.sendMessage(text)} else sender.sendMessage(text)
    }
    private fun status(sender:CommandSender,j:HubJob) {
        reply(sender,"status","id" to j.id,"state" to j.state,"placed" to j.placed,"total" to j.total,"world" to j.target.key,"backup" to j.backup,"error" to j.error)
        if(j.state==HubJobState.BUILT || j.state==HubJobState.SERVICES_READY)reply(sender,"next-job","job" to j.id,"world" to j.target.key)
    }
    fun suggestions(sender:CommandSender,args:List<String>):List<String> {
        if(!CommandAccess.allowed(sender,listOf("hub")+args.dropLast(1)))return emptyList()
        val options=when {
            args.size<=1->CommandAccess.hubActions.filter {CommandAccess.allowed(sender,listOf("hub",it))}
            args.size==2 && args[0].equals("preview",true)->plugin.server.worlds.filter {!com.alytanta.oneblock.hub.HubWorlds.island(it) && it.environment==World.Environment.NORMAL && it.key().namespace()!="oneblock_dungeons"}.map {it.key().asString()}
            args.size==2 && args[0].equals("build",true)->engine.previewIds(actor(sender)).map {it.toString()}
            args.size==2 && args[0].lowercase() in setOf("status","services","activate","stop","cancel","resume","rollback")->engine.all().filter {it.state!=HubJobState.ROLLED_BACK}.flatMap {listOf(it.target.key,it.id.toString())}
            args.size==2 && args[0].equals("world",true)->listOf("create","load")
            args.size==3 && args[0].equals("services",true)->listOf("apply")
            args.size==3 && args[0].equals("rollback",true)->listOf("confirm")
            else->emptyList()
        }
        return options.distinct().filter {it.startsWith(args.lastOrNull().orEmpty(),true)}
    }
    fun handle(sender:CommandSender,rawArgs:List<String>) {
        val args=when(rawArgs.firstOrNull()?.lowercase()) {
            "create-world"->listOf("world","create")+rawArgs.drop(1)
            "load-world"->listOf("world","load")+rawArgs.drop(1)
            "stop"->listOf("cancel")+rawArgs.drop(1)
            else->rawArgs
        }
        if(!CommandAccess.allowed(sender,listOf("hub")+args)) {reply(sender,"denied");return}
        val who=actor(sender)
        val operation=runCatching {
            when(args.firstOrNull()?.lowercase()) {
                "preview"->{require(args.size in 5..6);val x=args[2].toInt();val y=args[3].toInt();val z=args[4].toInt();val rotation=args.getOrNull(5)?.toInt()?:0
                    port.global {val world=port.resolveStrict(args[1]);engine.preview(who,HubTarget(world.key().asString(),world.uid,x,y,z,rotation))}.thenAccept {preview->
                        val t=preview.plan.target;reply(sender,"preview","id" to preview.id,"world" to t.key,"x" to t.x,"y" to t.y,"z" to t.z,"blocks" to preview.plan.blocks.size)
                        reply(sender,"next-preview","world" to t.key)
                        if(sender is Player)FoliaTasks.entity(plugin,sender) {
                            if(sender.world.uid==t.worldId)for(a in -40..40 step 4)for(edge in listOf(-40,40)) {
                                sender.spawnParticle(Particle.END_ROD,t.x+a+0.5,t.y+1.0,t.z+edge+0.5,1)
                                sender.spawnParticle(Particle.END_ROD,t.x+edge+0.5,t.y+1.0,t.z+a+0.5,1)
                            }
                        }
                    }
                }
                "build"->{require(args.size in 1..2);engine.build(who,args.getOrNull(1)?.let(UUID::fromString)).thenAccept {status(sender,it)}}
                "status"->{require(args.size<=2);if(args.size==2)status(sender,engine.job(engine.reference(args[1]))) else engine.all().forEach {status(sender,it)};CompletableFuture.completedFuture(Unit)}
                "cancel","resume","activate"->{require(args.size==2);val id=engine.reference(args[1]);when(args[0].lowercase()) {"cancel"->engine.cancel(id);"resume"->engine.resume(id);else->engine.activate(id)}.thenAccept {status(sender,it)}}
                "services"->{require(args.size in 2..3 && (args.size==2 || args[2]=="apply"));val id=engine.reference(args[1]);engine.services(id,args.size==3).thenAccept {reply(sender,"services","id" to id,"value" to it.details.joinToString("; "))}}
                "rollback"->{require(args.size in 2..3 && (args.size==2 || args[2]=="confirm"));engine.rollback(who,engine.reference(args[1]),args.size==3).thenAccept {status(sender,it);if(args.size==2)reply(sender,"done","action" to "rollback preview","value" to "${it.id} confirm (5 min; conflicts checked again)")}}
                "world"->{require(args.size==3 && args[1] in setOf("create","load"));namedWorld(args[2],args[1]=="create").thenAccept {reply(sender,"done","action" to args[1],"value" to it.key().asString())}}
                else->{reply(sender,"command-help");CompletableFuture.completedFuture(Unit)}
            }
        }.getOrElse {CompletableFuture.failedFuture<Any>(it)}
        operation.whenComplete {_,error->if(error!=null) {
            val root=generateSequence(error as Throwable?) {it.cause}.last()
            val key=when(root.message) {
                "Unknown/consumed preview", "No current personal preview; run preview first"->"preview-missing"
                "Expired/foreign/config-stale preview"->"preview-stale"
                "Unknown/ambiguous hub world; use a job ID from status", "Unknown hub job"->"job-ambiguous"
                else->null
            }
            if(key!=null)reply(sender,key) else if(root is IllegalArgumentException && root.message=="Failed requirement.")reply(sender,"command-help")
            else reply(sender,"error","error" to (root.message?:root.javaClass.simpleName))
        }}
    }
    fun namedWorld(raw:String,create:Boolean):CompletableFuture<World> {
        val key=net.kyori.adventure.key.Key.key(if(':' in raw)raw else "oneblock_hubs:$raw")
        require(key.namespace() !in setOf("minecraft","oneblock") && key.value().matches(Regex("[a-z0-9_-]{1,48}"))) {"Use an explicit non-reserved world key/name"}
        if(create)require(engine.settings.allowVoid) {"allow-void-worlds is false"}
        check(creating.compareAndSet(false,true)) {"A world lifecycle operation is already running"}
        val result=port.global {
            val api=WorldsAccess.access();check(plugin.server.getWorld(key)==null) {"World already loaded"}
            val directory=api.resolveLevelDirectory(key)
            CompletableFuture.supplyAsync {Files.isDirectory(directory) && Files.list(directory).use {it.findAny().isPresent}}.thenCompose {exists->
                check(if(create)!exists && !api.worldRegistry.isRegistered(key) else exists) {"Create requires an absent world; load requires existing files"}
                val intent=if(create)engine.worldIntent(key.asString()) else CompletableFuture.completedFuture(Unit)
                intent.thenCompose {port.global {
                    check(!create || engine.settings.allowVoid) {"World creation disabled during request"}
                    if(create) {
                        val level=net.thenextlvl.worlds.Level.builder(key).generatorType(net.thenextlvl.worlds.generator.GeneratorType.FLAT.with(IslandGeneration.voidPreset())).structures(false).bonusChest(false).build()
                        api.create(level).thenCompose {w->port.global {if(!api.worldRegistry.isRegistered(key))api.worldRegistry.register(level,true);CompletableFuture.completedFuture(w)}}
                    } else {check(api.worldRegistry.isRegistered(key)) {"Register the existing world in Worlds with its original generator/settings before loading"};api.load(key)}
                }}
            }
        }
        result.whenComplete {_,_->creating.set(false)};return result
    }
    fun interact(player:Player,world:World,x:Int,y:Int,z:Int):Boolean {
        val job=engine.all().firstOrNull {it.target.key==world.key().asString() && it.target.contains(x.toDouble(),y.toDouble(),z.toDouble()) && it.state in setOf(HubJobState.BUILT,HubJobState.SERVICES_READY,HubJobState.ACTIVE)} ?: return false
        // Read-only guides use the existing menu renderer and its permission/parent contracts.
        val point=HubPoint(x,y,z);val plan=engine.plan(job.id)
        if(plan.blocks.none {it.point==point && it.lines!=null})return false
        val role=plan.anchors.filter {it.role!="spawn"}.minBy {
            val p=it.point;(p.x-x).toLong()*(p.x-x)+(p.z-z).toLong()*(p.z-z)
        }.role
        if(role.startsWith("dungeon"))return false // Legacy gate signs are decorative; the NPC owns dungeon selection.
        val topic=when(role) {"merchant","bazaar","auction","workshop"->role;"guide"->"start";else->"hub"}
        FoliaTasks.entity(plugin,player) {guide(player,topic)};return true
    }
}
