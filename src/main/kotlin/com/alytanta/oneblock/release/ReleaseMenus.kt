package com.alytanta.oneblock.release

import com.alytanta.oneblock.island.IslandService
import com.alytanta.oneblock.locale.LocaleService
import com.alytanta.oneblock.market.*
import com.alytanta.oneblock.menu.PlayerMenu
import com.alytanta.oneblock.storage.PostgresStorageService
import com.alytanta.oneblock.util.*
import org.bukkit.entity.Player
import org.bukkit.plugin.Plugin
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class ReleaseMenus(private val plugin: Plugin, private val menu: PlayerMenu, private val islands: IslandService,
    private val storage: PostgresStorageService, private val locales: LocaleService, private val market: MarketController,
    private val operations: ReleaseOperations, private val current: () -> ReleaseRules?) : org.bukkit.event.Listener {
    val rewardMenus=com.alytanta.oneblock.rewards.RewardMenus(plugin,menu,islands,storage,market)
    private data class Quote(val account: UUID, val rules: ReleaseRules, val menus: Any, val generation: Long, val intent: ReleaseIntent)
    private val quotes=ConcurrentHashMap<UUID,Quote>()
    private val claimViews=ConcurrentHashMap<UUID,ClaimView>()
    private fun text(player: Player, key: String)=locales.textKey(player,"release.$key")
    fun message(player: Player, key: String) {
        player.sendMessage(Colors.colorize(text(player,"message.$key")))
        if(key=="done")player.playSound(player.location,org.bukkit.Sound.ENTITY_EXPERIENCE_ORB_PICKUP,.5f,1.2f)
        else player.playSound(player.location,org.bukkit.Sound.BLOCK_NOTE_BLOCK_BASS,.5f,.8f)
    }
    var onChapter:(Player,String)->Unit={_,_->}
    /** The one thing to do next: tutorial step, then the next chapter, then the arena record. */
    fun goal(player:Player):String {
        val p=islands.findForPlayer(player.uniqueId)?.releaseProgress?:return name(player,"legacy")
        if(p.tutorial<12)return text(player,"tutorial.${p.tutorial}")
        val r=current()?:return text(player,"goal_final")
        val next=r.chapters.getOrNull(r.index(p.chapter)+1)?:return text(player,"goal_final")
        return text(player,"goal_chapter").replace("{chapter}",name(player,next.id)).replace("{breaks}",p.eligibleCoreBreaks.toString()).replace("{need}",next.breaks.toString())
    }
    /** Opens the place where the current goal is done; steps done in the world just close the menu. */
    private fun goToGoal(player:Player) {
        val p=islands.findForPlayer(player.uniqueId)?.releaseProgress?:return
        val target=if(p.tutorial>=12)"release-core" else when(p.tutorial) {
            1,2,3,5->null
            4,10,11->"release-core"
            6,7->"economy"
            8,9->"release-workshop"
            else->"main-menu"
        }
        if(target==null)player.closeInventory() else menu.show(player,target)
    }
    /** The core level is the chapter number, so it says the same thing as the core menu. */
    fun coreLevel(player:Player):Int {
        val r=current()?:return 0
        return islands.findForPlayer(player.uniqueId)?.releaseProgress?.let {r.index(it.chapter)+1}?:0
    }
    private fun mark(ok:Boolean,line:String)=(if(ok)"&a✓ &7" else "&c✗ &7")+line
    private fun filled(player:Player,key:String,vararg args:Pair<String,Any>)=args.fold(text(player,key)) {line,(k,v)->line.replace("{$k}",v.toString())}
    private fun gateLines(player:Player,r:ReleaseRules,island:com.alytanta.oneblock.island.Island,p:ReleaseProgress,gate:ChapterRule):List<Pair<Boolean,String>> {
        val m=gate.milestones;val inventory=player.inventory.storageContents
        return buildList {
            add((p.eligibleCoreBreaks>=gate.breaks) to filled(player,"gate.breaks","value" to "${p.eligibleCoreBreaks}/${gate.breaks}"))
            if(gate.logs>0)add((p.grownLogs>=gate.logs) to filled(player,"gate.logs","value" to "${p.grownLogs}/${gate.logs}"))
            if(gate.level>0)add(p.members.values.any {it.xp.values.any {xp->r.level(xp)>=gate.level}} to filled(player,"gate.level","level" to gate.level))
            if(gate.upgrades>0)add((p.upgrades.values.sum()>=gate.upgrades) to filled(player,"gate.upgrades","value" to "${p.upgrades.values.sum()}/${gate.upgrades}"))
            if(m.allSkills>0)add(ReleaseRules.SKILLS.all {skill->p.members.values.any {r.level(it.xp[skill]?:0)>=m.allSkills}} to filled(player,"gate.all_skills","level" to m.allSkills))
            if(m.orders>0)add((p.activity.completed>=m.orders) to filled(player,"gate.orders","value" to "${p.activity.completed}/${m.orders}"))
            m.events.forEach {kind->add(("activity:$kind" in p.facts) to filled(player,"gate.events","value" to name(player,kind)))}
            if(m.arenaWave>0)add(p.facts.any {it.startsWith("arena:wave:") && (it.substringAfterLast(':').toLongOrNull()?:0)>=m.arenaWave} to filled(player,"gate.arena","value" to m.arenaWave))
            if(m.recipesAny.isNotEmpty())add(p.crafted.any {it in m.recipesAny} to filled(player,"gate.style","value" to m.recipesAny.joinToString {name(player,it)}))
            if(gate.tool)add(p.crafted.any {it in setOf("pickaxe_1","axe_1","hoe_1")} to text(player,"gate.tool"))
            if(gate.coins>0)add((island.coins>=gate.coins) to "${gate.coins} "+name(player,"coins"))
            gate.ingredients.forEach {add(ReleaseInventoryPlan.canRemove(inventory,listOf(it)) to ingredient(player,it))}
        }
    }
    /** What exactly is missing, for the chat line after a click on something still closed. */
    private fun explain(player:Player,island:com.alytanta.oneblock.island.Island,r:ReleaseRules,action:String,value:String,reason:String):List<String> {
        val p=island.releaseProgress?:return emptyList()
        return when(action) {
            "chapter"->r.chapters.getOrNull(r.index(p.chapter)+1)?.let {gate->gateLines(player,r,island,p,gate).filterNot {it.first}.map {mark(false,it.second)}}.orEmpty()
            "recipe"->r.recipes[value]?.let {listOf(recipeStatus(player,it))}.orEmpty()
            "upgrade"->upgradeNeed(player,r,value,p.upgrades[value]?:0,reason,island.coins)?.let {listOf(it)}.orEmpty()
            "pack"->r.packs[value]?.let {pack->when(reason) {
                "chapter"->listOf(filled(player,"recipe_need_chapter","chapter" to name(player,pack.chapter)))
                "funds"->listOf(filled(player,"need_charge","need" to pack.points,"have" to p.charge))
                else->emptyList()
            }}.orEmpty()
            "focus"->listOf(filled(player,"need_focus","chapter" to name(player,"developed")))
            else->emptyList()
        }
    }
    private fun upgradeNeed(player:Player,r:ReleaseRules,upgrade:String,level:Int,reason:String,coins:Long):String? {
        val domain=ReleaseDomain(r);val cost=domain.upgradeCosts(upgrade).getOrNull(level)
        return when(reason) {
            "chapter"->filled(player,"recipe_need_chapter","chapter" to name(player,domain.upgradeChapter(upgrade,level)))+if(cost!=null)"\n"+filled(player,"upgrade_cost","coins" to cost) else ""
            "funds"->filled(player,"need_coins","need" to (cost?:0),"have" to coins)
            else->null
        }
    }
    private fun name(player: Player, id: String) = ReleaseLabels.name(current(),id) {locales.textKey(player,it)}
    private fun item(player: Player, material: String): String {
        val key="item.${material.lowercase()}";val translated=text(player,key)
        return if(translated=="release.$key")material.lowercase().replace('_',' ').replaceFirstChar { it.uppercase() } else translated
    }
    private fun ingredient(player: Player, value: Ingredient) = "${value.amount} × "+if(value.combatId!=null)name(player,value.combatId) else if(value.materials==ReleaseRules.LOGS)name(player,"logs") else item(player,value.materials.single())
    private fun describe(player: Player, intent: ReleaseIntent): String = buildList {
        add(text(player,"preview.coins").replace("{value}",intent.coins.toString()))
        if(intent.rewardCoins>0)add(text(player,"preview.reward_coins").replace("{value}",intent.rewardCoins.toString()))
        if(intent.points>0)add(text(player,"preview.points").replace("{value}",intent.points.toString()))
        if(intent.action=="upgrade")current()?.let { r -> add(upgradeEffect(player,intent.value.substringBefore(':'),intent.value.substringAfter(':').toInt()-1,r)) }
        if(intent.action=="focus")current()?.let { r ->
            val p=islands.findForPlayer(player.uniqueId)?.releaseProgress
            if(p!=null) {
                add(text(player,"focus_preview"))
                val table=r.table(p.copy(focus=intent.value),r.tables.getValue("start"))
                table.entries.groupBy { it.reward.name }.forEach { (material,rows) -> add("%.2f%% × %s".format(java.util.Locale.ROOT,rows.sumOf { it.weight }*100.0/table.totalWeight,item(player,material))) }
            }
        }
        addAll(intent.ingredients.map { ingredient(player,it) })
        if(intent.items.isNotEmpty()) { add(text(player,if(intent.personal)"preview.personal" else "preview.shared"));addAll(intent.items.map { "${it.amount} × ${it.tool?.let { id -> name(player,id) } ?: item(player,it.material)}" }) }
        if(intent.xpActor!=null)add(text(player,"preview.xp").replace("{value}",intent.xpAmount.toString()).replace("{skill}",name(player,intent.xpSkill!!)).replace("{actor}",plugin.server.getPlayer(intent.xpActor)?.name ?: storage.nameLookup(intent.xpActor) ?: name(player,"recorded_actor")))
        add(text(player,"preview.claims"))
        if(intent.action=="recipe")current()?.recipes?.get(intent.value)?.let { r ->
            add(text(player,"recipe.requirements").replace("{chapter}",name(player,r.chapter)).replace("{skill}",r.skill?.let { name(player,it) }?:"—").replace("{level}",r.level.toString()))
            add(effect(player,r))
        }
    }.joinToString("\n")
    private fun visibleClaims(player: Player)=islands.findForPlayer(player.uniqueId)?.let { island -> storage.marketState().claims.values.filter {
        it.account==island.owner && (it.recipient==null || it.recipient==player.uniqueId)
    }.sortedWith(compareBy<MarketClaim> { it.created }.thenBy { it.id }) }.orEmpty()
    fun install() {
        plugin.server.pluginManager.registerEvents(rewardMenus,plugin)
        menu.releaseAction=::action
        menu.dynamicOpen={ player,id -> if(activityOpen(player,id))true else if(id=="release-claims") { openClaims(player,claimViews[player.uniqueId]?.page?:0);true } else false }
        fun register(id: String, getter: (Player) -> Any?)=menu.statistics.register("release.$id",getter)
        register("chapter") { player -> islands.findForPlayer(player.uniqueId)?.releaseProgress?.let { name(player,it.chapter) } ?: name(player,"legacy") }
        register("core") { player -> islands.findForPlayer(player.uniqueId)?.releaseProgress?.eligibleCoreBreaks ?: 0 }
        register("charge") { player -> islands.findForPlayer(player.uniqueId)?.releaseProgress?.charge ?: 0 }
        register("focus") { player -> islands.findForPlayer(player.uniqueId)?.releaseProgress?.focus?.let { name(player,it) } ?: "—" }
        register("title") { player -> if("title:growing" in islands.findForPlayer(player.uniqueId)?.releaseProgress?.facts.orEmpty())text(player,"growing_title") else "" }
        register("next") { player -> goal(player) }
        register("core_level") { player -> coreLevel(player) }
        register("skill_avg") { player ->
            val xp=islands.findForPlayer(player.uniqueId)?.releaseProgress?.member(player.uniqueId)?.xp.orEmpty();val r=current()
            if(r==null)0 else ReleaseRules.SKILLS.sumOf {r.level(xp[it]?:0)}/ReleaseRules.SKILLS.size
        }
        val tools=(1..3).flatMap {n->listOf("pickaxe_$n","axe_$n","hoe_$n")}+listOf("water","lava","farm")
        register("ready_workshop") { player -> current()?.recipes?.keys.orEmpty().count {it in tools && recipeReady(player,it)} }
        register("ready_combat") { player -> current()?.recipes?.keys.orEmpty().count {it !in tools && recipeReady(player,it)} }
        ReleaseRules.CHAPTERS.forEach { id -> register("chapter.$id") { player ->
            val r=current();val island=islands.findForPlayer(player.uniqueId);val p=island?.releaseProgress
            if(r==null||p==null)"locked" else {
                val index=r.index(id);val at=r.index(p.chapter)
                when {
                    index<at->"done"
                    index==at->"current"
                    index>at+1->"locked"
                    runCatching {require(ReleaseInventoryPlan.canRemove(player.inventory.storageContents,ReleaseDomain(r).plan(island,storage.marketState(),player.uniqueId,"chapter",id).ingredients))}.isSuccess->"ready"
                    else->"next"
                }
            }
        } }
        listOf("warden","ranger","weaver").forEach { set -> register("set.$set") { player ->
            val crafted=islands.findForPlayer(player.uniqueId)?.releaseProgress?.crafted.orEmpty()
            listOf("helmet","chestplate","leggings","boots").count {"${set}_$it" in crafted}
        } }
        register("tutorial_state") { player ->
            val r=current();val island=islands.findForPlayer(player.uniqueId)
            if(r!=null && island?.releaseProgress!=null && runCatching {ReleaseDomain(r).plan(island,storage.marketState(),player.uniqueId,"tutorial","${island.releaseProgress.tutorial}:mining")}.isSuccess)"ready" else "other"
        }
        register("phase") { player ->
            val r=current();val p=islands.findForPlayer(player.uniqueId)?.releaseProgress
            val next=if(r!=null&&p!=null)r.chapters.getOrNull(r.index(p.chapter)+1) else null
            if(next==null)"" else name(player,next.id)+"\n"+ProgressBar.render(p!!.eligibleCoreBreaks,next.breaks)
        }
        register("alternatives") { player -> val p=islands.findForPlayer(player.uniqueId)?.releaseProgress
            text(player,if(p?.chapter=="start")"alternatives_start" else if(p?.chapter=="living")"alternatives_living" else "alternatives")
        }
        register("gate") { player ->
            val r=current();val p=islands.findForPlayer(player.uniqueId)?.releaseProgress
            val gate=if(r!=null&&p!=null)r.chapters.getOrNull(r.index(p.chapter)+1) else null
            val island=islands.findForPlayer(player.uniqueId)
            if(gate==null||island==null)name(player,if(p==null)"legacy" else "developed") else (listOf(name(player,gate.id))+gateLines(player,r!!,island,p!!,gate).map {mark(it.first,it.second)}).joinToString("\n")
        }
        register("quote") { player -> quotes[player.uniqueId]?.let { describe(player,it.intent) } ?: "" }
        ReleaseRules.SKILLS.forEach { skill -> register("skill.$skill") { player ->
            val r=current();val xp=islands.findForPlayer(player.uniqueId)?.releaseProgress?.member(player.uniqueId)?.xp?.get(skill) ?: 0
            if(r==null)"0" else text(player,"skill").replace("{level}",r.level(xp).toString()).replace("{xp}",xp.toString()).replace("{next}",r.threshold((r.level(xp)+1).coerceAtMost(30)).toString())+
                "\n"+ProgressBar.render(xp-r.threshold(r.level(xp)),(r.threshold((r.level(xp)+1).coerceAtMost(30))-r.threshold(r.level(xp))).coerceAtLeast(1))+
                "\n"+text(player,"skill_unlock").replace("{levels}",r.recipes.values.filter { it.skill==skill }.map { it.level }.distinct().sorted().joinToString(" / ")).replace("{step}","${r.passiveStep*100}").replace("{max}","${r.passiveStep*600}")+if(r.level(xp)>=20)"\n"+name(player,if(r.level(xp)>=30)"master" else "expert") else ""
        } }
        listOf("fortune","territory","focus").forEach { upgrade -> register("upgrade.$upgrade") { player ->
            val island=islands.findForPlayer(player.uniqueId);val r=current();val level=island?.releaseProgress?.upgrades?.get(upgrade)?:0
            "$level / ${if(upgrade=="focus")1 else 5}"+if(island==null||r==null)"" else "\n"+upgradeEffect(player,upgrade,level,r)+"\n"+runCatching { describe(player,ReleaseDomain(r).plan(island,storage.marketState(),player.uniqueId,"upgrade",upgrade)) }.getOrElse { upgradeNeed(player,r,upgrade,level,it.message.orEmpty(),island.coins)?:text(player,"message.${it.message?.takeIf { m -> m in setOf("maxed","legacy","busy") } ?: "requirements"}") }
        } }
        registerRecipes()
        register("achievements") { player -> val p=islands.findForPlayer(player.uniqueId)?.releaseProgress
            (p?.achievements.orEmpty()+p?.member(player.uniqueId)?.achievements.orEmpty()).sorted().joinToString("\n") { name(player,it) }.ifEmpty { text(player,"none") }
        }
        listOf("building","nature","mineral","ancient").forEach { id -> register("pack.$id") { player ->
            val r=current();val pack=r?.packs?.get(id)
            if(r==null || pack==null)"" else {
                val p=islands.findForPlayer(player.uniqueId)?.releaseProgress
                val items=pack.items+if(id=="mineral" && p!=null && r.atLeast(p.chapter,"depths"))pack.advancedItems else emptyList()
                val open=p!=null && r.atLeast(p.chapter,pack.chapter)
                mark(open,filled(player,"check.chapter","chapter" to name(player,pack.chapter)))+"\n"+
                    mark((p?.charge?:0)>=pack.points,filled(player,"check.charge","need" to pack.points,"have" to (p?.charge?:0)))+"\n"+describe(player,ReleaseIntent("pack",id,0,pack.points,emptyList(),items,false))
            }
        } }
        (0..6).forEach { index -> register("claim.$index") { player -> visibleClaims(player).getOrNull(index)?.let { "${it.amount} × ${it.product?.let { product -> item(player,product) } ?: name(player,"coins")}" } ?: text(player,"none") } }
        register("claim_count") { player -> visibleClaims(player).size }
        register("daily") { player ->
            val rules=current();val p=islands.findForPlayer(player.uniqueId)?.releaseProgress
            if(rules==null || p==null)"" else {
                val daily=dailyRules(rules);val s=DailyReward.status(p,daily,DailyReward.today())
                val state=when {s.claimedToday->dailyText(player,"done");s.ready->dailyText(player,"ready");else->dailyText(player,"progress","progress" to s.progress,"needed" to s.needed)}
                dailyText(player,"streak","streak" to s.streak,"max" to daily.coins.size,"coins" to s.coins)+"\n"+state
            }
        }
    }
    private val registeredRecipes=ConcurrentHashMap.newKeySet<String>()
    private fun recipeReady(player:Player,id:String):Boolean=runCatching {
        val island=requireNotNull(islands.findForPlayer(player.uniqueId));val rules=requireNotNull(current())
        val intent=ReleaseDomain(rules).plan(island,storage.marketState(),player.uniqueId,"recipe",id)
        require(ReleaseInventoryPlan.canRemove(player.inventory.storageContents,intent.ingredients))
    }.isSuccess
    private fun recipeStatus(player:Player,r:RecipeRule):String {
        val island=islands.findForPlayer(player.uniqueId)?:return text(player,"unavailable")
        val rules=current()?:return text(player,"unavailable")
        val plan=runCatching {ReleaseDomain(rules).plan(island,storage.marketState(),player.uniqueId,"recipe",r.id)}
        return when(plan.exceptionOrNull()?.message) {
            null->if(ReleaseInventoryPlan.canRemove(player.inventory.storageContents,plan.getOrThrow().ingredients))text(player,"available") else text(player,"recipe_need_items")
            "chapter"->text(player,"recipe_need_chapter").replace("{chapter}",name(player,r.chapter))
            "level"->text(player,"recipe_need_level").replace("{skill}",r.skill?.let {name(player,it)}?:"—").replace("{level}",r.level.toString())
            "funds"->text(player,"recipe_need_coins").replace("{coins}",r.coins.toString())
            "discovery"->filled(player,"need_discovery","need" to (rules.activities?.unlocks?.get(r.id)?:0),"have" to (island.releaseProgress?.activity?.discovery?:0))
            "busy","legacy"->text(player,"message.${plan.exceptionOrNull()?.message}")
            else->text(player,"unavailable")
        }
    }
    fun registerRecipes() {
        current()?.recipes?.keys?.forEach { id -> if(registeredRecipes.add(id)) {
            menu.statistics.register("release.recipe.$id") { player ->
                val r=current()?.recipes?.get(id) ?: return@register ""
                val inventory=player.inventory.storageContents
                val island=islands.findForPlayer(player.uniqueId);val p=island?.releaseProgress;val rules=current()
                buildList {
                    effect(player,r).takeIf {it.isNotBlank()}?.let {add(it);add("")}
                    if(rules!=null && rules.index(r.chapter)>0)add(mark(p!=null && rules.atLeast(p.chapter,r.chapter),filled(player,"check.chapter","chapter" to name(player,r.chapter))))
                    if(rules!=null && r.skill!=null && r.level>0) {
                        val level=rules.level(p?.member(player.uniqueId)?.xp?.get(r.skill)?:0)
                        add(mark(level>=r.level,filled(player,"check.level","skill" to name(player,r.skill),"level" to r.level,"current" to level)))
                    }
                    if(r.coins>0)add(mark((island?.coins?:0)>=r.coins,text(player,"recipe_coins").replace("{coins}",r.coins.toString())))
                    r.ingredients.forEach {g->add(mark(ReleaseInventoryPlan.canRemove(inventory,listOf(g)),ingredient(player,g)))}
                    add("");add(recipeStatus(player,r))
                }.joinToString("\n")
            }
            menu.statistics.register("release.recipe_state.$id") { player -> if(recipeReady(player,id))"ready" else "other" }
        } }
    }
    var activityAction:(Player,String)->Boolean={_,_->false}
    var activityOpen:(Player,String)->Boolean={_,_->false}
    private fun action(player: Player, raw: String) {
        if(raw=="daily")return claimDaily(player)
        if(raw=="go")return goToGoal(player)
        if(activityAction(player,raw))return
        if(rewardMenus.action(player,raw))return
        val args=raw.split(' ');val action=args.first();val value=args.getOrElse(1) { "" }
        val rules=current()?.takeIf { it.enabled } ?: return message(player,"legacy")
        val island=islands.findForPlayer(player.uniqueId) ?: return
        if(action=="claims" || action=="claims-next" || action=="claims-previous") {
            val old=claimViews[player.uniqueId]?.page ?: 0
            openClaims(player,if(action=="claims-next")old+1 else if(action=="claims-previous")old-1 else old);return
        }
        if(action=="confirm") {
            val q=quotes.remove(player.uniqueId) ?: return message(player,"stale")
            if(q.account!=island.owner || q.rules!==rules || q.menus!==menu.configurationIdentity || q.generation!=storage.generation)return message(player,"stale")
            operations.submit(player,q.account,q.intent,rules) {
                if(q.intent.action=="chapter")onChapter(player,q.intent.value)
                if(islands.findForPlayer(player.uniqueId)?.owner==q.account)menu.show(player,"release-core")
            }
            return
        }
        if(action=="claim") {
            val view=claimViews[player.uniqueId] ?: return
            val id=runCatching { UUID.fromString(value) }.getOrNull() ?: return
            if(view.account!=island.owner || view.configuration!==menu.configurationIdentity || view.generation!=storage.generation)return message(player,"stale")
            val claim=view.resolve(id,player.uniqueId,storage.marketState().claims) ?: run { message(player,"stale");openClaims(player,view.page);return }
            if(claim.product==null) {
                try {
                    islands.marketUpdate(island.owner) { live,state -> MarketEngine(market.settings).transaction(island.owner,player.uniqueId,live.coins,state) { collectCoins(claim.id) } }
                    storage.flushAsync().whenComplete { _,error -> FoliaTasks.entity(plugin,player) { if(error==null && islands.findForPlayer(player.uniqueId)?.owner==island.owner)menu.show(player,"release-claims") else message(player,"unavailable") } }
                } catch(e:Exception) { message(player,"requirements") }
            } else market.handoffs.transfer(player,island.owner,claim.product,minOf(claim.amount,64).toInt(),TransferKind.WITHDRAW,claimId=claim.id) {
                if(islands.findForPlayer(player.uniqueId)?.owner==island.owner)menu.show(player,"release-claims")
            }
            return
        }
        try {
            val target=when(action) { "chapter" -> rules.chapters.getOrNull(rules.index(island.releaseProgress?.chapter?:"start")+1)?.id ?: error("maxed")
                "tutorial" -> "${island.releaseProgress?.tutorial?:0}:${value.ifEmpty { "mining" }}";else -> value }
            val intent=ReleaseDomain(rules).plan(island,storage.marketState(),player.uniqueId,action,target)
            quotes[player.uniqueId]=Quote(island.owner,rules,menu.configurationIdentity,storage.generation,intent)
            menu.show(player,"release-confirm")
        } catch(e:Exception) {
            val reason=e.message?.takeIf { it in setOf("busy","legacy","requirements","funds","items","level","tool","upgrades","maxed","chapter","stale","discovery") } ?: "requirements"
            val exact=if(reason in setOf("busy","legacy","stale","maxed"))emptyList() else runCatching {explain(player,island,rules,action,value,reason)}.getOrDefault(emptyList())
            if(exact.isEmpty())message(player,reason) else {
                exact.forEach {player.sendMessage(Colors.colorize(it))}
                player.playSound(player.location,org.bukkit.Sound.BLOCK_NOTE_BLOCK_BASS,.5f,.8f)
            }
        }
    }
    private var dailyCache:Pair<ReleaseRules,DailyRules>?=null
    private fun dailyRules(rules:ReleaseRules)=dailyCache?.takeIf {it.first===rules}?.second
        ?:DailyRules.read(plugin.dataFolder.resolve("progression.yml")).also {dailyCache=rules to it}
    private fun dailyText(player:Player,key:String,vararg args:Pair<String,Any>)=
        args.fold(text(player,"daily_$key")) {line,(name,value)->line.replace("{$name}",value.toString())}
    private fun claimDaily(player:Player) {
        val rules=current()?.takeIf {it.enabled}?:return message(player,"legacy")
        val island=islands.findForPlayer(player.uniqueId)?:return
        val daily=dailyRules(rules);val day=DailyReward.today()
        try {
            val before=island.coins
            val next=islands.releaseUpdate(island.owner) {live,state->ReleaseChange(DailyReward.claim(live,daily,day),state)}.island
            storage.flushAsync()
            player.sendMessage(Colors.colorize(dailyText(player,"claimed","coins" to next.coins-before,"streak" to next.releaseProgress!!.dailyStreak)))
        } catch(e:IllegalArgumentException) {
            val s=island.releaseProgress?.let {DailyReward.status(it,daily,day)}
            player.sendMessage(Colors.colorize(if(e.message=="daily-wait" && s!=null)dailyText(player,"wait","progress" to s.progress,"needed" to s.needed) else dailyText(player,"repeat")))
        } catch(e:Exception) {message(player,"unavailable")}
        menu.refreshControls(player)
    }
    fun openClaims(player:Player,requested:Int=0)=rewardMenus.open(player,requested)
    private fun upgradeEffect(player: Player, key: String, level: Int, r: ReleaseRules): String {
        val override=islands.findForPlayer(player.uniqueId)?.worldSize
        if(key=="territory" && override!=null)return text(player,"upgrade.territory_override").replace("{value}",override.toString())
        return text(player,"upgrade.$key").replace("{value}",when(key) { "fortune" -> "${(level+1).coerceAtMost(5)*r.fortuneStep*100}%";"territory" -> maxOf(r.widths[(level+1).coerceAtMost(5)].toDouble(),islands.findForPlayer(player.uniqueId)?.let(islands::worldSize)?:0.0).toString();else -> "" })
    }
    private fun effect(player: Player,r: RecipeRule)=current()?.combat?.let { combat -> if(r.id in combat.recipes)r.items.mapNotNull {it.tool}.joinToString("\n") { combat.items.getValue(it).lore.joinToString("\n") } else null }
        ?: text(player,"effect.${r.id}").replace("{durability}","${(r.durability*100).toInt()}").replace("{sapling}","${(r.sapling*100).toInt()}")
    @org.bukkit.event.EventHandler fun quit(event: org.bukkit.event.player.PlayerQuitEvent) { quotes.remove(event.player.uniqueId);claimViews.remove(event.player.uniqueId) }
    @org.bukkit.event.EventHandler fun death(event: org.bukkit.event.entity.PlayerDeathEvent) { quotes.remove(event.entity.uniqueId);claimViews.remove(event.entity.uniqueId) }
}
