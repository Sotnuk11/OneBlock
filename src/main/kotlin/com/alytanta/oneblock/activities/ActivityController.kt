package com.alytanta.oneblock.activities

import com.alytanta.oneblock.island.*
import com.alytanta.oneblock.release.*
import com.alytanta.oneblock.market.*
import com.alytanta.oneblock.menu.*
import com.alytanta.oneblock.storage.PostgresStorageService
import com.alytanta.oneblock.util.*
import org.bukkit.*
import org.bukkit.entity.*
import org.bukkit.event.*
import org.bukkit.event.entity.*
import org.bukkit.event.player.*
import org.bukkit.plugin.Plugin
import com.alytanta.oneblock.release.ProgressBar
import org.bukkit.persistence.PersistentDataType
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class ActivityController(private val plugin:Plugin,private val islands:IslandService,private val storage:PostgresStorageService,
    private val menu:PlayerMenu,private val operations:ReleaseOperations,private val locales:com.alytanta.oneblock.locale.LocaleService):Listener {
    private data class View(val account:UUID,val generation:Long,val config:Any,val rules:ReleaseRules,val offers:List<String>,val order:UUID?,val event:UUID?)
    private data class Quote(val view:View,val intent:ReleaseIntent)
    data class Enemy(val account:UUID,val event:UUID,val step:Int,val entity:LivingEntity)
    val enemies=ConcurrentHashMap<UUID,Enemy>()
    private val spawning=ConcurrentHashMap.newKeySet<UUID>()
    private val motion=ConcurrentHashMap<UUID,Long>();private val views=ConcurrentHashMap<UUID,View>();private val quotes=ConcurrentHashMap<UUID,Quote>()
    private val marker=NamespacedKey(plugin,"island_activity_mob")
    private fun rules()=islands.releaseRules?.activities
    private fun say(p:Player,key:String)=rules()?.let {p.sendMessage(Colors.colorize(it.text(key)))}
    init {
        plugin.server.pluginManager.registerEvents(this,plugin)
        // Active fights are paused at boot. No attack restarts without a new explicit click.
        if(storage.healthy)islands.all().filter {it.releaseProgress?.activity?.event?.running==true}.forEach {i->islands.update(i.owner) {ActivityDomain.pause(it)}}
        menu.statistics.register("activity.status",::summary)
        menu.statistics.register("activity.quote") {p->quotes[p.uniqueId]?.intent?.let {q->
            rules()?.text("quote","ingredients" to q.ingredients.joinToString(", ") {"${it.amount} × ${it.materials.joinToString(" / ") {m->material(p,m)}}"}.ifEmpty {"—"},"items" to rewardItems(p,q.items),"xp" to q.xpAmount,"skill" to (q.xpSkill?.let {label(p,it)}?:"—")) ?: ""
        }.orEmpty()}
        (0..2).forEach {index->menu.statistics.register("activity.offer.$index") {p->
            val i=islands.findForPlayer(p.uniqueId);val r=rules();val a=i?.releaseProgress
            if(r==null || a==null)"" else describe(p,r,r.offers(a.chapter,a.activity.completed)[index],0,"offer")+cooldown(r,a.activity)?.let {"\n\n"+it}.orEmpty()
        }}
        (0..2).forEach {index->
            menu.statistics.register("activity.offer_name.$index") {p->
                val r=rules();val a=islands.findForPlayer(p.uniqueId)?.releaseProgress
                if(r==null || a==null)"" else r.offers(a.chapter,a.activity.completed)[index].name
            }
            menu.statistics.register("activity.slot.$index") {p->
                val r=rules();val a=islands.findForPlayer(p.uniqueId)?.releaseProgress
                if(r==null || a==null)"mine" else slotState(a.activity,r,a.chapter,index)
            }
        }
        menu.statistics.register("activity.order") {p->
            val r=rules();val o=islands.findForPlayer(p.uniqueId)?.releaseProgress?.activity?.order
            if(r==null || o==null)"" else describe(p,r,o.rule,o.progress)+"\n"+ProgressBar.render(o.progress.toLong(),o.rule.target.toLong())
        }
        menu.statistics.register("activity.order_name") {p->islands.findForPlayer(p.uniqueId)?.releaseProgress?.activity?.order?.rule?.name.orEmpty()}
        menu.statistics.register("activity.event_info") {p->
            val r=rules();val a=islands.findForPlayer(p.uniqueId)?.releaseProgress?.activity
            if(r==null || a==null)"" else eventInfo(r,a)
        }
        menu.statistics.register("activity.head") {p->
            val r=rules();val a=islands.findForPlayer(p.uniqueId)?.releaseProgress?.activity
            if(r==null || a==null)"" else r.text("head","done" to a.completed,"discovery" to a.discovery)+cooldown(r,a)?.let {"\n"+it}.orEmpty()
        }
        listOf("resonance","echo").forEach {kind->menu.statistics.register("activity.event_state.$kind") {p->
            islands.findForPlayer(p.uniqueId)?.releaseProgress?.activity?.let {eventState(it,kind)}?:"locked"
        }}
        FoliaTasks.asyncRepeating(plugin,1,java.util.concurrent.TimeUnit.SECONDS,::tick)
    }
    private fun cooldown(r:ActivityRules,a:ActivityProgress):String? {
        val left=a.orderReadyAt-System.currentTimeMillis();if(a.order!=null || left<=0)return null
        val minutes=(left+59_999)/60_000
        return r.text("cooldown","time" to "${minutes/60} год ${minutes%60} хв")
    }
    private fun bind(p:Player) {
        val i=islands.findForPlayer(p.uniqueId)?:return;val r=islands.releaseRules?:return;val a=i.releaseProgress?:return
        val policy=r.activities?.takeIf {it.enabled}?:return
        views[p.uniqueId]=View(i.owner,storage.generation,menu.configurationIdentity,r,policy.offers(a.chapter,a.activity.completed).map {it.id},a.activity.order?.id,a.activity.event?.id)
    }
    fun open(p:Player) {bind(p);menu.show(p,"island-activities")}
    fun dynamic(p:Player,id:String):Boolean {if(id=="island-activities")bind(p);return false}
    private fun valid(p:Player,v:View)=p.hasPermission("oneblock.command") && !p.isDead && storage.healthy && storage.generation==v.generation && menu.configurationIdentity===v.config && islands.releaseRules===v.rules &&
        islands.findForPlayer(p.uniqueId)?.let {it.owner==v.account && islands.canBuild(it,p.uniqueId)}==true && !InventoryGuard.busy(p.uniqueId) && InventoryGuard.canStart(p.uniqueId)
    private fun label(p:Player,id:String)=com.alytanta.oneblock.release.ReleaseLabels.name(islands.releaseRules,id) {locales.textKey(p,it)}
    private fun material(p:Player,name:String)=com.alytanta.oneblock.release.ReleaseLabels.material(name) {locales.textKey(p,it)}
    private fun rewardItems(p:Player,items:List<ReleaseItem>)=items.joinToString(", ") {"${it.amount} × ${it.tool?.let {id->label(p,id)}?:material(p,it.material)}"}
    private fun requirement(p:Player,o:OrderRule)=o.text.takeIf {!it.isNullOrBlank()}?:when(o.type) {
        "mine"->"Добудь ${o.target} породи з ядра"
        "grow"->"Виконай ${o.target} дій вирощування"
        else->"Здай "+o.ingredients.joinToString(", ") {"${it.amount} × ${it.materials.joinToString(" / ") {m->material(p,m)}}"}
    }
    private fun describe(p:Player,r:ActivityRules,o:OrderRule,progress:Int,key:String="order")=r.text(key,"name" to o.name,"progress" to progress,"goal" to o.target,"requirement" to requirement(p,o),
        "xp" to o.reward.xp,"discovery" to o.reward.discovery,"skill" to label(p,o.reward.skill),"items" to rewardItems(p,o.reward.items))
    fun summary(p:Player):String {
        val r=rules()?:return "";val a=islands.findForPlayer(p.uniqueId)?.releaseProgress?.activity?:return ""
        val order=a.order?.let {r.text("active","name" to it.rule.name,"bar" to ProgressBar.render(it.progress.toLong(),it.rule.target.toLong()))}?:r.text("none")
        return order+"\n"+eventInfo(r,a)
    }
    private fun eventInfo(r:ActivityRules,a:ActivityProgress):String {
        val e=a.event?:return if(a.ready)r.text("ready") else r.text("charging","bar" to ProgressBar.render(a.charge.toLong(),r.readiness.toLong()),"progress" to a.charge,"goal" to r.readiness)
        return r.text("status","event" to e.rule.name,"bar" to ProgressBar.render(e.progress.toLong(),e.rule.goal.toLong()),"progress" to e.progress,"goal" to e.rule.goal,
            "state" to if(e.running)r.text("running") else r.text("paused"),"seconds" to (e.rule.durationMs-e.elapsed).coerceAtLeast(0)/1000)
    }
    private fun eventState(a:ActivityProgress,kind:String):String {
        val e=a.event?:return if(a.ready)"ready" else "locked"
        return when {e.rule.kind!=kind->"other";e.progress>=e.rule.goal->"done";e.running->"running";else->"paused"}
    }
    private fun slotState(a:ActivityProgress,r:ActivityRules,chapter:String,index:Int):String {
        val o=a.order?:return r.offers(chapter,a.completed)[index].type
        return when(index) {0->"active";1->if(o.rule.type=="supply"||o.progress>=o.rule.target)"deliver" else "wait";else->"cancel"}
    }
    fun action(p:Player,raw:String):Boolean {
        if(raw!="activities" && !raw.startsWith("guild-") && !raw.startsWith("activity-"))return false
        if(raw=="activities") {open(p);return true}
        val v=views[p.uniqueId]?:return true;if(!valid(p,v))return true
        val r=v.rules.activities!!
        try {
            when {
                raw.startsWith("guild-accept-") && islands.findByOwner(v.account)?.releaseProgress?.activity?.let {cooldown(r,it)}!=null->
                    p.sendMessage(Colors.colorize(cooldown(r,islands.findByOwner(v.account)!!.releaseProgress!!.activity)!!))
                raw.startsWith("guild-accept-")->mutate(p,v) {i,s->
                    require(v.order==null && r.offers(i.releaseProgress!!.chapter,i.releaseProgress.activity.completed).map {it.id}==v.offers)
                    ActivityDomain.accept(i,s,p.uniqueId,v.offers[raw.last().digitToInt()],System.currentTimeMillis(),r)
                }
                raw=="guild-cancel"->mutate(p,v) {i,s->ActivityDomain.cancel(i,s,p.uniqueId,requireNotNull(v.order),System.currentTimeMillis(),r)}
                raw=="activity-pause"->mutate(p,v) {i,s->ActivityDomain.idle(i,s);require(i.releaseProgress?.activity?.event?.id==v.event);ActivityDomain.pause(i)}
                raw=="activity-resonance" || raw=="activity-echo"->{
                    val kind=raw.removePrefix("activity-");val i=islands.findByOwner(v.account)!!
                    require(islands.sameWorld(i,p.world))
                    val old=i.releaseProgress!!.activity.event
                    val site=if(kind=="echo")old?.site?.takeIf {safe(i,p.world,it,r.radius)}?:findSite(i,p,r.radius) else null
                    if(kind=="echo" && site==null) {say(p,"unsafe");return true}
                    mutate(p,v) {live,s->
                        require(live.releaseProgress?.activity?.event?.id==v.event)
                        val next=ActivityDomain.start(live,s,p.uniqueId,kind,System.currentTimeMillis(),r)
                        next.copy(releaseProgress=next.releaseProgress!!.let {rp->rp.copy(activity=rp.activity.copy(event=rp.activity.event!!.copy(site=site)))})
                    }
                }
                raw=="guild-deliver" || raw=="activity-reward"->{
                    val id=if(raw=="guild-deliver")v.order else v.event
                    val intent=ReleaseDomain(v.rules).plan(islands.findByOwner(v.account)!!,storage.marketState(),p.uniqueId,raw,requireNotNull(id).toString())
                    quotes[p.uniqueId]=Quote(v,intent);menu.show(p,"activity-confirm")
                }
                raw=="activity-confirm"->{
                    val q=quotes.remove(p.uniqueId)?:return true
                    require(q.view==v && menu.currentMenuId(p)=="activity-confirm")
                    operations.submit(p,v.account,q.intent,v.rules) {open(p)}
                }
            }
        } catch(e:Exception) {say(p,"unavailable")}
        return true
    }
    private fun mutate(p:Player,v:View,f:(Island,MarketState)->Island) {
        val token=InventoryGuard.acquireToken(p.uniqueId)?:return
        try {
            islands.releaseUpdate(v.account) {i,s->require(islands.canBuild(i,p.uniqueId));ReleaseChange(f(i,s),s)}
            storage.flushAsync().whenComplete {_,error->InventoryGuard.release(p.uniqueId,token);FoliaTasks.entity(plugin,p) {
                if(error==null && valid(p,v)) {say(p,"updated");open(p)} else say(p,"unavailable")
            }}
        } catch(e:Exception) {InventoryGuard.release(p.uniqueId,token);throw e}
    }
    @EventHandler(priority=EventPriority.MONITOR,ignoreCancelled=true) fun moved(e:PlayerMoveEvent) {
        if(e is PlayerTeleportEvent)return
        if(e.from.distanceSquared(e.to)>.0025 || kotlin.math.abs(e.from.yaw-e.to.yaw)>=3 || kotlin.math.abs(e.from.pitch-e.to.pitch)>=3)motion[e.player.uniqueId]=System.currentTimeMillis()
    }
    fun credit(i:Island,actor:UUID,block:Material,grown:Boolean,core:Boolean):Island {
        val r=rules()?.takeIf {it.enabled}?:return i;val now=System.currentTimeMillis()
        if(!storage.healthy || now-(motion[actor]?:0)>r.motionMs || !islands.canBuild(i,actor) || i.releaseProgress==null)return i
        return i.copy(releaseProgress=ActivityDomain.credit(i.releaseProgress,block.name,grown,core,now,r))
    }
    private fun safe(i:Island,w:World,s:ActivitySite,radius:Int):Boolean {
        if((s.x-i.x)*(s.x-i.x)+(s.z-i.z)*(s.z-i.z)<64)return false
        for(x in s.x-radius..s.x+radius)for(z in s.z-radius..s.z+radius) {
            val pos=Location(w,x+.5,s.y.toDouble(),z+.5)
            if(!Bukkit.isOwnedByCurrentRegion(pos) || islands.findAt(pos)?.owner!=i.owner)return false
            val floor=w.getBlockAt(x,s.y-1,z);if(!floor.type.isSolid || floor.state is org.bukkit.block.TileState || floor.type in setOf(Material.MAGMA_BLOCK,Material.CAMPFIRE,Material.CACTUS))return false
            if(!w.getBlockAt(x,s.y,z).type.isAir || !w.getBlockAt(x,s.y+1,z).type.isAir)return false
        }
        return w.getNearbyEntities(Location(w,s.x+.5,s.y.toDouble(),s.z+.5),radius.toDouble(),2.0,radius.toDouble()).none {it is LivingEntity && it !is Player}
    }
    private fun findSite(i:Island,p:Player,r:Int):ActivitySite? {
        val at=p.location
        return listOf(0 to 0,8 to 0,-8 to 0,0 to 8,0 to -8).map {ActivitySite(at.blockX+it.first,at.blockY,at.blockZ+it.second)}.firstOrNull {safe(i,p.world,it,r)}
    }
    private fun clear(account:UUID) {enemies.entries.filter {it.value.account==account}.forEach {(id,m)->if(enemies.remove(id,m))FoliaTasks.entity(plugin,m.entity) {m.entity.remove()}}}
    private fun tick() {
        if(!storage.healthy) {enemies.values.map {it.account}.distinct().forEach(::clear);return}
        islands.all().forEach {i->
            val e=i.releaseProgress?.activity?.event
            if(e==null || !e.running || rules()?.enabled!=true) {clear(i.owner);return@forEach}
            val actor=plugin.server.onlinePlayers.firstOrNull {p->islands.findForPlayer(p.uniqueId)?.owner==i.owner && p.uniqueId==e.actor}
                ?:plugin.server.onlinePlayers.firstOrNull {p->islands.findForPlayer(p.uniqueId)?.owner==i.owner}
            if(actor==null) {runCatching {islands.update(i.owner) {ActivityDomain.pause(it)}};clear(i.owner);return@forEach}
            FoliaTasks.entity(plugin,actor) {
                try {
                    val live=islands.findByOwner(i.owner)?:return@entity
                    if(live.releaseProgress?.activity?.event?.id!=e.id)return@entity
                    val site=e.site
                    val active=islands.canBuild(live,actor.uniqueId) && islands.sameWorld(live,actor.world) && !actor.isDead &&
                        (site==null || actor.location.distanceSquared(Location(actor.world,site.x+.5,site.y.toDouble(),site.z+.5))<=144)
                    val now=System.currentTimeMillis()
                    val updated=islands.update(i.owner) {ActivityDomain.tick(it,e.id,now,active)}?:return@entity
                    val current=updated.releaseProgress?.activity?.event
                    if(!active || current==null) {clear(i.owner);if(current==null)say(actor,"failed");return@entity}
                    if(menu.currentMenuId(actor) in setOf("island-activities","activity-confirm"))menu.refreshControls(actor)
                    if(current.progress<current.rule.goal)actor.sendActionBar(Colors.colorize(rules()!!.text("hint","hint" to current.rule.hints[(current.progress*current.rule.hints.size/current.rule.goal).coerceAtMost(current.rule.hints.lastIndex)],"progress" to current.progress,"goal" to current.rule.goal)))
                    if(current.rule.kind=="echo" && current.progress<current.rule.goal && now-current.lastStep>=current.rule.intervalMs && enemies.values.none {it.account==i.owner} && spawning.add(i.owner)) {
                        spawn(actor,updated,current)
                    }
                } catch(_:Exception) {clear(i.owner)}
            }
        }
    }
    private fun spawn(actor:Player,i:Island,e:IslandActivityEvent) {
        val s=e.site?:run {spawning.remove(i.owner);return}
        val at=Location(actor.world,s.x+.5,s.y.toDouble(),s.z+.5)
        actor.world.spawnParticle(Particle.DUST,at.clone().add(0.0,.3,0.0),24,.7,.1,.7,Particle.DustOptions(Color.ORANGE,1.3f))
        actor.scheduler.runDelayed(plugin,{
            val live=islands.findByOwner(i.owner)
            if(storage.healthy && live?.releaseProgress?.activity?.event?.let {it.id==e.id && it.running && it.progress==e.progress && it.rule.kind=="echo" && it.site==e.site}==true && islands.sameWorld(live,actor.world) && islands.canBuild(live,actor.uniqueId) && !actor.isDead)placeEnemy(actor,live,e)
            else spawning.remove(i.owner)
        },{spawning.remove(i.owner)},20)
    }
    private fun placeEnemy(actor:Player,i:Island,e:IslandActivityEvent) {
        try {
            val s=e.site?:return;val r=rules()?:return
            if(!safe(i,actor.world,s,r.radius)) {islands.update(i.owner) {ActivityDomain.pause(it)};say(actor,"unsafe");return}
            val at=Location(actor.world,s.x+.5,s.y.toDouble(),s.z+.5)
            if(actor.location.distanceSquared(at)<9) {actor.world.spawnParticle(Particle.DUST,at.clone().add(0.0,.3,0.0),16,.7,.1,.7,Particle.DustOptions(Color.ORANGE,1.3f));return}
            // Existing native zombie/husk behavior; no terrain-changing enemy or new combat engine.
            val mob=actor.world.spawnEntity(at,EntityType.valueOf(e.rule.mob)) as Zombie
            mob.isPersistent=false;mob.removeWhenFarAway=false;mob.setAdult();mob.setShouldBurnInDay(false);mob.setCanBreakDoors(false);mob.canPickupItems=false
            mob.equipment?.clear();mob.getAttribute(org.bukkit.attribute.Attribute.MAX_HEALTH)?.baseValue=e.rule.health;mob.health=e.rule.health
            mob.getAttribute(org.bukkit.attribute.Attribute.ATTACK_DAMAGE)?.baseValue=e.rule.damage
            mob.persistentDataContainer.set(marker,PersistentDataType.STRING,e.id.toString())
            enemies[mob.uniqueId]=Enemy(i.owner,e.id,e.progress,mob);mob.target=actor
        } finally {spawning.remove(i.owner)}
    }
    @EventHandler(priority=EventPriority.LOWEST) fun died(e:EntityDeathEvent) {
        if(!e.entity.persistentDataContainer.has(marker))return
        e.drops.clear();e.droppedExp=0
        val m=enemies.remove(e.entity.uniqueId)?:return;val killer=e.entity.killer?:return
        if(!storage.healthy || !Bukkit.isOwnedByCurrentRegion(killer))return
        runCatching {islands.update(m.account) {i->if(islands.canBuild(i,killer.uniqueId) && islands.sameWorld(i,killer.world))ActivityDomain.killed(i,m.event,m.step,System.currentTimeMillis()) else i}}
    }
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true) fun target(e:EntityTargetLivingEntityEvent) {
        val m=enemies[e.entity.uniqueId]?:return
        val i=islands.findByOwner(m.account);val p=e.target as? Player
        if(i==null || p==null || !Bukkit.isOwnedByCurrentRegion(p) || !islands.canBuild(i,p.uniqueId) || p.location.distanceSquared(e.entity.location)>144)e.isCancelled=true
    }
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true) fun damage(e:EntityDamageByEntityEvent) {
        val mob=enemies[e.damager.uniqueId]?:return;val i=islands.findByOwner(mob.account);val p=e.entity as? Player
        if(!storage.healthy || i==null || p==null || !islands.canBuild(i,p.uniqueId) || i.releaseProgress?.activity?.event?.running!=true)e.isCancelled=true
    }
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true) fun terrain(e:EntityChangeBlockEvent) {if(e.entity.persistentDataContainer.has(marker))e.isCancelled=true}
    @EventHandler(priority=EventPriority.MONITOR) fun quit(e:PlayerQuitEvent) {views.remove(e.player.uniqueId);quotes.remove(e.player.uniqueId);motion.remove(e.player.uniqueId)}
    // Bukkit reflects every Listener method, including methods without EventHandler.
    fun npc(name:String,p:Player,location:()->Location,shown:()->Boolean):Boolean {
        val r=rules()?.takeIf {it.enabled}?:return false;if(name!=r.npc)return false
        FoliaTasks.entity(plugin,p) {val at=location();if(p.world==at.world && p.location.distanceSquared(at)<=36 && shown() && p.hasPermission("oneblock.command") && InventoryGuard.interactionAllowed(p.uniqueId))open(p)}
        return true
    }
}
