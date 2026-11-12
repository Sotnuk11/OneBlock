package com.alytanta.oneblock.items

import com.alytanta.oneblock.util.Colors
import com.alytanta.oneblock.util.FoliaTasks
import com.alytanta.oneblock.util.StrictYaml
import org.bukkit.entity.Player
import org.bukkit.event.*
import org.bukkit.event.player.*
import org.bukkit.event.block.*
import org.bukkit.event.entity.*
import org.bukkit.event.inventory.*
import org.bukkit.plugin.Plugin
import org.bukkit.configuration.file.YamlConfiguration
import java.io.File
import java.net.URI
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

data class ResourcePackSettings(val enabled:Boolean=false,val required:Boolean=true,val url:String="",val sha1:String="",val timeoutSeconds:Int=60,
    val messages:Map<String,String> = defaults) {
    val configured get()=url.isNotBlank() && sha1.matches(Regex("[0-9a-f]{40}"))
    companion object {
        val defaults=mapOf("prompt" to "&6Для OneBlock потрібен серверний ресурспак. Він додає вигляд предметів і броні. Підтвердь завантаження.",
            "declined" to "&cДля гри потрібен ресурспак OneBlock. У списку серверів увімкни «Серверні ресурспаки: Запитувати/Увімкнено» й увійди знову.",
            "failed" to "&cНе вдалося застосувати ресурспак OneBlock. Перевір з’єднання та повтори вхід; якщо помилка триває, повідом адміністратора.",
            "timeout" to "&cЧас очікування ресурспаку OneBlock вичерпано. Перевір з’єднання й налаштування серверних ресурспаків, потім увійди знову.",
            "unconfigured" to "&cРесурспак OneBlock ще не налаштований адміністратором. Спробуй пізніше.")
        fun read(file:File):Pair<ResourcePackSettings?,String?> {
            if(!file.exists())return null to null
            val source=file.readText(Charsets.UTF_8);StrictYaml.validate(source)
            val y=YamlConfiguration().apply {loadFromString(source)}
            require(y.getInt("version")==1 && y.isBoolean("enabled") && y.isBoolean("required")) {"resource-pack.yml: version=1 and boolean enabled/required required"}
            val url=y.getString("url","")!!;val sha=y.getString("sha1","")!!
            require(sha.isEmpty() || sha.matches(Regex("[0-9a-f]{40}"))) {"resource-pack.yml sha1: lowercase SHA-1 (40 hex characters) required"}
            if(url.isNotBlank()) {
                val uri=runCatching {URI(url)}.getOrElse {error("resource-pack.yml url: invalid URI")}
                require(uri.scheme=="https" || uri.scheme=="http" && y.getBoolean("allow-http",false)) {"resource-pack.yml url: HTTPS required (HTTP only with allow-http: true)"}
                require(!uri.host.isNullOrBlank() && uri.userInfo==null && uri.fragment==null && url.all {it.code in 33..126} && url.length<=2048) {"resource-pack.yml url: direct ASCII URL without credentials/fragment required"}
                require(sha.isNotBlank()) {"resource-pack.yml sha1 required for a configured URL"}
            }
            require(y.isInt("timeout-seconds") && y.getInt("timeout-seconds") in 5..300) {"resource-pack.yml timeout-seconds: integer 5..300"}
            require(!y.getBoolean("enabled") || url.isNotBlank() && sha.isNotBlank()) {"resource-pack.yml: configure the hosted ZIP URL and SHA-1 before enabled: true"}
            val messages=defaults+y.getConfigurationSection("messages")?.getValues(false).orEmpty().mapValues {(key,value)->
                require(key in defaults && value is String && value.isNotBlank()) {"resource-pack.yml messages.$key: unknown key/empty text"};value.toString()}
            return ResourcePackSettings(y.getBoolean("enabled"),y.getBoolean("required"),url,sha,y.getInt("timeout-seconds"),messages) to source
        }
    }
}
/** Per-request state, separate from inventory recovery tokens. Only the matching applied-status unlocks play. */
class PackAdmission {
    enum class State { WAITING, ACCEPTED, DOWNLOADED, READY, FAILED }
    data class Ticket(val id:UUID,val connection:Player?,val settings:ResourcePackSettings,@Volatile var state:State=State.WAITING)
    private val tickets=ConcurrentHashMap<UUID,Ticket>()
    @Volatile var settings=ResourcePackSettings();private set
    fun apply(next:ResourcePackSettings) {settings=next}
    fun offer(actor:UUID,connection:Player?=null):Ticket=Ticket(UUID.randomUUID(),connection,settings).also {tickets[actor]=it}
    fun allowed(actor:UUID)=!settings.enabled || !settings.required || tickets[actor]?.let {it.settings==settings && it.state==State.READY}==true
    fun current(actor:UUID)=tickets[actor]
    fun remove(actor:UUID)=tickets.remove(actor)
    fun status(actor:UUID,id:UUID,status:String):Boolean {
        val ticket=tickets[actor]?:return false
        if(ticket.id!=id || ticket.settings!=settings || ticket.state==State.FAILED)return false
        ticket.state=when(status) {"ACCEPTED"->if(ticket.state==State.READY)State.READY else State.ACCEPTED
            "DOWNLOADED"->if(ticket.state==State.READY)State.READY else State.DOWNLOADED
            "SUCCESSFULLY_LOADED"->if(ticket.state in setOf(State.ACCEPTED,State.DOWNLOADED,State.READY))State.READY else return false
            "DECLINED","FAILED_DOWNLOAD","INVALID_URL","FAILED_RELOAD","DISCARDED"->State.FAILED
            else->return false}
        return true
    }
}
class ResourcePackGate(private val plugin:Plugin,initial:ResourcePackSettings):Listener,AutoCloseable {
    val admission=PackAdmission().apply {apply(initial)}
    private val timers=ConcurrentHashMap<UUID,io.papermc.paper.threadedregions.scheduler.ScheduledTask>()
    var onReady:(Player)->Unit={}
    fun allowed(actor:UUID)=admission.allowed(actor)
    fun apply(next:ResourcePackSettings) {
        if(next==admission.settings)return
        admission.apply(next) // New config immediately invalidates old readiness before any inventory/gameplay work.
        plugin.server.onlinePlayers.forEach {p->FoliaTasks.entity(plugin,p) {offer(p)}}
    }
    fun offer(p:Player) {
        timers.remove(p.uniqueId)?.cancel()
        admission.current(p.uniqueId)?.let {previous->p.removeResourcePack(previous.id)}
        val config=admission.settings
        if(!config.enabled) {admission.remove(p.uniqueId);onReady(p);return}
        val ticket=admission.offer(p.uniqueId,p)
        if(!config.configured) {
            if(config.required)kick(p,"unconfigured")
            plugin.logger.warning("OneBlock resource pack is not configured: host the prepared ZIP, set resource-pack.yml url + its SHA-1, then /obadmin reload. No fallback URL is used.")
            return
        }
        p.closeInventory()
        try {
            val hash=java.util.HexFormat.of().parseHex(config.sha1)
            p.setResourcePack(ticket.id,config.url,hash,Colors.colorize(config.messages.getValue("prompt")),config.required)
            p.scheduler.runDelayed(plugin,{
                if(admission.current(p.uniqueId)===ticket && ticket.state!=PackAdmission.State.READY) {
                    ticket.state=PackAdmission.State.FAILED;if(config.required)kick(p,"timeout")
                };timers.remove(p.uniqueId)
            },{timers.remove(p.uniqueId)},config.timeoutSeconds*20L)?.let {timers[p.uniqueId]=it}
        } catch(_:Exception) {ticket.state=PackAdmission.State.FAILED;if(config.required)kick(p,"failed")}
    }
    private fun kick(p:Player,key:String)=p.kick(Colors.colorize(admission.settings.messages.getValue(key)))
    @EventHandler(priority=EventPriority.LOWEST) fun join(e:PlayerJoinEvent) {FoliaTasks.entity(plugin,e.player) {offer(e.player)}}
    @EventHandler fun status(e:PlayerResourcePackStatusEvent) {
        FoliaTasks.entity(plugin,e.player) {
            val ticket=admission.current(e.player.uniqueId)?:return@entity
            val before=ticket.state
            if(ticket.connection!==e.player || !admission.status(e.player.uniqueId,e.getID(),e.status.name))return@entity
            if(ticket.state==PackAdmission.State.READY && before!=PackAdmission.State.READY) {timers.remove(e.player.uniqueId)?.cancel();onReady(e.player)}
            else if(ticket.state==PackAdmission.State.FAILED) {timers.remove(e.player.uniqueId)?.cancel();if(ticket.settings.required)kick(e.player,if(e.status.name=="DECLINED")"declined" else "failed")}
        }
    }
    @EventHandler fun quit(e:PlayerQuitEvent) {admission.remove(e.player.uniqueId);timers.remove(e.player.uniqueId)?.cancel()}
    @EventHandler(priority=EventPriority.HIGHEST) fun move(e:PlayerMoveEvent) {if(!allowed(e.player.uniqueId) && (e.from.x!=e.to.x || e.from.y!=e.to.y || e.from.z!=e.to.z))e.isCancelled=true}
    @EventHandler(priority=EventPriority.HIGHEST) fun command(e:PlayerCommandPreprocessEvent) {if(!allowed(e.player.uniqueId))e.isCancelled=true}
    @EventHandler(priority=EventPriority.HIGHEST) fun interact(e:PlayerInteractEvent) {if(!allowed(e.player.uniqueId)) {e.setUseItemInHand(Event.Result.DENY);e.setUseInteractedBlock(Event.Result.DENY)}}
    @EventHandler(priority=EventPriority.HIGHEST) fun interactEntity(e:PlayerInteractEntityEvent) {if(!allowed(e.player.uniqueId))e.isCancelled=true}
    @EventHandler(priority=EventPriority.HIGHEST) fun atEntity(e:PlayerInteractAtEntityEvent) {if(!allowed(e.player.uniqueId))e.isCancelled=true}
    @EventHandler(priority=EventPriority.HIGHEST) fun consume(e:PlayerItemConsumeEvent) {if(!allowed(e.player.uniqueId))e.isCancelled=true}
    @EventHandler(priority=EventPriority.HIGHEST) fun fish(e:PlayerFishEvent) {if(!allowed(e.player.uniqueId))e.isCancelled=true}
    @EventHandler(priority=EventPriority.HIGHEST) fun bucket(e:PlayerBucketEmptyEvent) {if(!allowed(e.player.uniqueId))e.isCancelled=true}
    @EventHandler(priority=EventPriority.HIGHEST) fun fill(e:PlayerBucketFillEvent) {if(!allowed(e.player.uniqueId))e.isCancelled=true}
    @EventHandler(priority=EventPriority.HIGHEST) fun launch(e:ProjectileLaunchEvent) {if((e.entity.shooter as? Player)?.let {!allowed(it.uniqueId)}==true)e.isCancelled=true}
    override fun close() {timers.values.forEach {it.cancel()};timers.clear()}
}
