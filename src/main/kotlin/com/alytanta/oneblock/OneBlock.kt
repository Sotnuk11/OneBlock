package com.alytanta.oneblock

import com.alytanta.oneblock.command.OneBlockCommand
import com.alytanta.oneblock.command.OneBlockTabCompleter
import com.alytanta.oneblock.config.ConfigSnapshot
import com.alytanta.oneblock.locale.Lang
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import java.util.concurrent.atomic.AtomicBoolean
import com.alytanta.oneblock.island.IslandService
import com.alytanta.oneblock.island.IslandProtectionListener
import com.alytanta.oneblock.island.IslandProgressionService
import com.alytanta.oneblock.locale.LocaleService
import com.alytanta.oneblock.oneblock.OneBlockListener
import com.alytanta.oneblock.oneblock.OneBlockService
import com.alytanta.oneblock.player.PlayerService
import com.alytanta.oneblock.storage.PostgresStorageService
import com.alytanta.oneblock.storage.DatabaseSettings
import com.alytanta.oneblock.market.MarketController
import com.alytanta.oneblock.market.FancyMarketBridge
import com.alytanta.oneblock.market.FancyMarketIntegration
import com.alytanta.oneblock.util.FoliaTasks
import com.alytanta.oneblock.util.OwnedItems
import com.alytanta.oneblock.hub.HubService
import com.alytanta.oneblock.island.IslandRespawnListener
import org.bukkit.plugin.java.JavaPlugin
import java.util.concurrent.TimeUnit

class OneBlock : JavaPlugin() {

    private lateinit var storage: PostgresStorageService
    private var trades: com.alytanta.oneblock.trade.TradeController? = null
    private var menuSlot: com.alytanta.oneblock.menu.ReservedMenuSlot? = null
    private var coreItemGathering: com.alytanta.oneblock.oneblock.CoreItemGathering? = null
    private var inventoryTracker: com.alytanta.oneblock.player.InventoryStatus? = null
    private var mainMenu: com.alytanta.oneblock.menu.PlayerMenu? = null
    private var flushTask: io.papermc.paper.threadedregions.scheduler.ScheduledTask? = null
    private var activities:com.alytanta.oneblock.activities.ActivityController?=null
    private val reloadInProgress = AtomicBoolean()
    private val bootInProgress = AtomicBoolean()
    @Volatile private var runtimeReady=false
    @Volatile private var activationStarted=false
    @Volatile private var reloadAction:((CommandSender)->Unit)?=null
    private var display:com.alytanta.oneblock.ui.PlayerDisplay?=null
    private var hubGenerator:com.alytanta.oneblock.hub.generator.HubGeneratorController?=null
    private var hubBuildProtection:com.alytanta.oneblock.hub.generator.HubBuildProtection?=null
    @Volatile private var activeHub:HubService?=null
    @Volatile private var activeIslands:IslandService?=null
    @Volatile private var combatCatalog:com.alytanta.oneblock.combat.CombatRewardCatalog?=null
    /** Server-side reward adapter. Producers persist and retry the same CombatRewardRequest until acknowledged. */
    @Volatile var combatRewards:com.alytanta.oneblock.combat.CombatRewardService?=null
        private set
    @Volatile var combatStats:com.alytanta.oneblock.combat.CombatStatProvider?=null
    private var combatDemo:com.alytanta.oneblock.combat.CombatDemo?=null
        private set
    private var mine:com.alytanta.oneblock.dungeon.MineController?=null
    private var mineAdministration:com.alytanta.oneblock.dungeon.MineAdministration?=null
    private var dungeonNpc:AutoCloseable?=null
    private var packGate:com.alytanta.oneblock.items.ResourcePackGate?=null
    private var itemGrants:com.alytanta.oneblock.items.ItemGrants?=null
    @Volatile private var hubSelection="world"
    private lateinit var hubRules:com.alytanta.oneblock.hub.HubRulesListener
    @Volatile private var retainedConfigFiles:Map<java.nio.file.Path,ByteArray> = emptyMap()
    private data class Prepared(val snapshot:ConfigSnapshot,val storage:PostgresStorageService,val files:Map<java.nio.file.Path,ByteArray>)

    override fun onEnable() {
        server.pluginManager.registerEvents(com.alytanta.oneblock.command.CommandVisibilityListener(),this)
        hubRules=com.alytanta.oneblock.hub.HubRulesListener(this,{ world -> activeHub?.isHubWorld(world)?:
            (com.alytanta.oneblock.hub.HubWorlds.resolve(this,hubSelection)?.key()==world.key()) },
            { world -> com.alytanta.oneblock.hub.HubWorlds.island(world)||activeIslands?.isIslandWorld(world)==true },com.alytanta.oneblock.hub.HubRules())
        server.pluginManager.registerEvents(hubRules,this)
        val control=org.bukkit.command.CommandExecutor { sender,_,_,_ -> controlReload(sender);true }
        getCommand("obreload")!!.apply { setExecutor(control);tabCompleter=org.bukkit.command.TabCompleter { _,_,_,_->emptyList() } }
        listOf("oneblock","obadmin","home","hub","trade","obmenu","dungeon").forEach { name -> getCommand(name)?.apply {tabCompleter=org.bukkit.command.TabCompleter {_,_,_,_->emptyList()};setExecutor { sender,_,_,args ->
            if(name=="obadmin" && !com.alytanta.oneblock.command.CommandAccess.admin(sender))controlReply(sender,"&cНемає дозволу.")
            else if(name=="obadmin" && args.firstOrNull()?.equals("reload",true)==true || name=="oneblock"&&(args.firstOrNull()?.equals("reload",true)==true||args.firstOrNull()?.equals("admin",true)==true&&args.getOrNull(1)?.equals("reload",true)==true))controlReload(sender)
            else controlReply(sender,if(com.alytanta.oneblock.command.CommandAccess.admin(sender))"&eOneBlock тимчасово недоступний. /obadmin reload — повторити підключення." else "&eOneBlock тимчасово недоступний. Спробуй пізніше.")
            true
        } } }
        try { activateRuntime(prepareRuntime());runtimeReady=true }
        catch(e:Exception) { startupFailure(e) }
    }
    private fun controlReply(sender:CommandSender,text:String) {
        if(sender is Player)FoliaTasks.entity(this,sender) { sender.sendMessage(com.alytanta.oneblock.util.Colors.colorize(text)) }
        else sender.sendMessage(com.alytanta.oneblock.util.Colors.colorize(text))
    }
    private fun controlReload(sender:CommandSender) {
        if(!com.alytanta.oneblock.command.CommandAccess.allowed(sender,listOf("reload"))) { controlReply(sender,"&cНемає дозволу.");return }
        if(runtimeReady) { reloadAction?.invoke(sender);return }
        if(activationStarted) { controlReply(sender,"&cПомилка активації служб. Перевірте журнал; потрібен повний запуск сервера.");return }
        if(!bootInProgress.compareAndSet(false,true)) { controlReply(sender,"&eВідновлення вже триває.");return }
        controlReply(sender,"&eПовторне завантаження OneBlock і підключення до БД…")
        FoliaTasks.async(this) {
            try {
                val prepared=prepareRuntime()
                if(!isEnabled) { prepared.storage.close();bootInProgress.set(false);return@async }
                server.globalRegionScheduler.execute(this) {
                    try { activateRuntime(prepared);runtimeReady=true;controlReply(sender,"&aOneBlock запущено без перезапуску сервера.") }
                    catch(e:Exception) { startupFailure(e);controlReply(sender,"&cНе вдалося активувати OneBlock. Перевірте журнал.") }
                    finally { bootInProgress.set(false) }
                }
            } catch(e:Exception) { startupFailure(e);bootInProgress.set(false);controlReply(sender,"&cВідновлення не вдалося. Виправте конфіг або доступ до БД та повторіть /obreload.") }
        }
    }
    private fun startupFailure(error:Exception) {
        runtimeReady=false
        if(::storage.isInitialized)runCatching { storage.close() }
        logger.log(java.util.logging.Level.SEVERE,"OneBlock startup failed; /obreload remains available to OP administrators",error)
    }

    private fun prepareRuntime():Prepared {
        com.alytanta.oneblock.config.DefaultConfigs.ensure(this)
        com.alytanta.oneblock.config.InterfaceConfigUpdates.apply(this)
        migrateLegacyStartingKit()
        val snapshot = ConfigSnapshot.read(dataFolder.resolve("config.yml"), logger)
        snapshot.validateMaterials()
        hubSelection=snapshot.plugin.hub.world
        hubRules.settings=snapshot.plugin.hubRules
        val worldsPlugin = checkNotNull(server.pluginManager.getPlugin("Worlds")) {
            "Worlds 4.5.0 or newer is required to manage isolated island worlds."
        }
        val worldsVersion = worldsPlugin.pluginMeta.version
        check(worldsPlugin.isEnabled && isSupportedWorldsVersion(worldsVersion)) {
            "Worlds 4.5.0 or newer is required to manage isolated island worlds; found $worldsVersion."
        }

        val env = dataFolder.toPath().resolve(".env")
        if (!java.nio.file.Files.exists(env)) {
            getResource(".env.example")!!.use { java.nio.file.Files.copy(it, env) }
            error("Created plugins/one-block/.env. Fill in the existing PostgreSQL database/user (the plugin uses a separate schema), then run /obreload.")
        }
        val candidate = PostgresStorageService(this, DatabaseSettings.load(env))
        try {
        candidate.load()
        val missingProgress=candidate.loadIslands().filter { it.retiredAt==null && it.releaseProgress==null }
        if(missingProgress.isNotEmpty()) {
            val backup=candidate.backup()
            missingProgress.forEach { candidate.saveIsland(it.copy(releaseProgress=com.alytanta.oneblock.release.ReleaseProgress())) }
            check(candidate.flush()) { "Cannot persist universal progression initialization" }
            logger.info("Initialized new progression for ${missingProgress.size} existing profiles; backup=${backup.name}; historical XP not imported")
        }
        return Prepared(snapshot,candidate,com.alytanta.oneblock.config.DefaultConfigs.capture(dataFolder))
        } catch(e:Exception) { candidate.close();throw e }
    }

    private fun activateRuntime(prepared:Prepared) {
        activationStarted=true
        val snapshot=prepared.snapshot
        val settings=snapshot.plugin
        storage=prepared.storage

        val players = PlayerService(storage)
        val names = com.alytanta.oneblock.player.PlayerNames(this, storage)
        storage.nameLookup = names::known
        storage.loadProfiles().forEach { profile -> names.known(profile.uniqueId)?.let { name ->
            if (profile.lastKnownName != name) players.update(profile.uniqueId) { it.copy(lastKnownName = name) }
        } }
        val islands = IslandService(this, settings, storage, players)
        activeIslands=islands
        islands.profileSettings = snapshot.profiles
        islands.releaseRules = snapshot.release
        com.alytanta.oneblock.items.CustomItems.current={islands.releaseRules?.customItems}
        com.alytanta.oneblock.items.CustomItems.customModelsEnabled=snapshot.resourcePack?.let {it.enabled && it.required}==true
        val packs=com.alytanta.oneblock.items.ResourcePackGate(this,snapshot.resourcePack?:com.alytanta.oneblock.items.ResourcePackSettings())
        packGate=packs
        com.alytanta.oneblock.market.InventoryGuard.interactionAllowed={actor->server.getPlayer(actor)==null || packs.allowed(actor)}
        server.pluginManager.registerEvents(packs,this)
        val itemRefresh=com.alytanta.oneblock.items.ItemRefresh(this)
        server.pluginManager.registerEvents(itemRefresh,this)
        com.alytanta.oneblock.items.CustomItems.onHeadProfileResolved={
            server.globalRegionScheduler.execute(this) {server.onlinePlayers.forEach(itemRefresh::refresh)}
        }
        islands.ensurePublicIds()
        storage.profileNameLookup = { islands.profileName(it,"en") }
        FoliaTasks.asyncRepeating(this,30,TimeUnit.SECONDS) {
            val version=islands.namingVersion
            storage.readProfileNaming().thenAccept { islands.applyProfileNaming(version,it) }
        }
        val harvest = com.alytanta.oneblock.harvest.HarvestService(players, snapshot.harvest)
        val inventoryStatus = com.alytanta.oneblock.player.InventoryStatus(this, players, snapshot.harvest)
        val progression = IslandProgressionService(islands, settings.economy)
        inventoryTracker = inventoryStatus
        val locales = LocaleService(this, settings.defaultLanguage, players)
        locales.load()
        val loadedConfigFiles=prepared.files+LocaleService.SUPPORTED.associate {language->
            val file=dataFolder.resolve("lang/$language.yml").toPath().toAbsolutePath().normalize()
            file to java.nio.file.Files.readAllBytes(file)
        }
        locales.auditResponse = { sender, message ->
            val context = com.alytanta.oneblock.storage.OperationContext.current()
            val denied = message in setOf(Lang.TOO_POOR, Lang.CORE_COINS_REQUIRED, Lang.NO_PERMISSION, Lang.ONLY_PLAYER,
                Lang.UNKNOWN_COMMAND, Lang.MARKET_ERROR, Lang.MARKET_UNAVAILABLE, Lang.MARKET_BUSY, Lang.MARKET_ACCESS,
                Lang.MARKET_NO_SPACE, Lang.MARKET_NO_ITEMS, Lang.MARKET_LIQUIDITY, Lang.MARKET_ORDER_LIMIT,
                Lang.MERCHANT_BUYBACK_EXPIRED, Lang.AUCTION_INVALID_ITEM, Lang.AUCTION_BID_CHANGED, Lang.AUCTION_ENDED,
                Lang.AUCTION_WAIT, Lang.AUCTION_HAS_BIDS, Lang.MERCHANT_NPC_EXISTS) ||
                listOf("failed", "invalid", "not-found", "protected", "owner-only", "already-exists", "no-permission", "too-poor").any { it in message.key }
            storage.audit(com.alytanta.oneblock.storage.OperationLog(operation = "RESPONSE", actor = (sender as? Player)?.uniqueId,
                account = (sender as? Player)?.uniqueId?.let(islands::findForPlayer)?.owner, success = if (denied) false else null,
                status = if (denied) "FAILED" else "INFO", reason = if (denied) message.key else null, correlation = context?.request,
                details = (context?.details ?: emptyMap()) + mapOf("message_key" to message.key)))
        }

        val hub = HubService(this, settings.hub, islands, locales)
        activeHub=hub
        hub.onArrival=hubRules::applyEntryMode
        val playerDisplay=com.alytanta.oneblock.ui.PlayerDisplay(this,snapshot.sidebar,islands,hub,hubRules,storage,locales)
        display=playerDisplay
        islands.deniedVisit = { player -> locales.send(player, Lang.UI2_VISIT_DENIED); hub.teleport(player) }
        val ownedItems = OwnedItems(this,islands)
        val command = OneBlockCommand(this, settings, islands, progression, players, locales, storage, hub, ownedItems, requireNotNull(snapshot.mainMenus), names)
        command.profiles.interfaces = snapshot.interfaces
        command.administration.menu.layouts = snapshot.interfaces
        mainMenu=command.playerMenu
        command.onWorldArrival=hubRules::applyEntryMode
        menuSlot = command.playerMenu.reservedSlot
        val market = MarketController(this, snapshot.market, storage, islands, hub, locales)
        val trading = com.alytanta.oneblock.trade.TradeController(this, snapshot.trades, storage, islands, locales) { com.alytanta.oneblock.market.MarketEngine(market.settings) }
        trades = trading
        command.tradeCommand = trading::command
        command.tradeAdmin = trading::admin
        val core = OneBlockService(progression, settings.drops, harvest)
        core.releaseRules = snapshot.release
        lateinit var releaseOperations: com.alytanta.oneblock.release.ReleaseOperations
        val releaseTools = com.alytanta.oneblock.release.ReleaseTools(this,islands,{ islands.releaseRules },locales::textKey) { account -> releaseOperations.busy(account) }
        releaseTools.worldRole={p->when {hub.isHubWorld(p.world)->"hub";p.world.key().namespace()=="oneblock_dungeons"->"dungeon";islands.isIslandWorld(p.world)->"island";else->"other"}}
        val releaseRuntime = com.alytanta.oneblock.release.ReleaseRuntime(islands,releaseTools) { islands.releaseRules }
        lateinit var releaseMenus: com.alytanta.oneblock.release.ReleaseMenus
        releaseOperations = com.alytanta.oneblock.release.ReleaseOperations(this,storage,islands,releaseTools,{ islands.releaseRules }) { player,key -> releaseMenus.message(player,key) }
        releaseMenus = com.alytanta.oneblock.release.ReleaseMenus(this,command.playerMenu,islands,storage,locales,market,releaseOperations) { islands.releaseRules }
        releaseMenus.install()
        val activity=com.alytanta.oneblock.activities.ActivityController(this,islands,storage,command.playerMenu,releaseOperations,locales)
        activities=activity
        releaseMenus.activityAction=activity::action;releaseMenus.activityOpen=activity::dynamic
        core.activityCredit={i,actor,block->activity.credit(i,actor,block,false,true)}
        val progressFeedback=com.alytanta.oneblock.release.ProgressFeedback(this,islands,storage,locales) { islands.releaseRules }
        server.pluginManager.registerEvents(progressFeedback,this)
        server.pluginManager.registerEvents(com.alytanta.oneblock.menu.MenuSounds(),this)
        core.progressChanged={id,before,after->server.getPlayer(id)?.let {progressFeedback.changed(it,before,after,true)}}
        core.rareFind=progressFeedback::rareFind
        releaseRuntime.progressChanged={player,before,after->progressFeedback.changed(player,before,after,false)}
        releaseMenus.onChapter=progressFeedback::chapter
        playerDisplay.extra={player->mapOf("goal" to releaseMenus.goal(player).take(40),"core_level" to releaseMenus.coreLevel(player).toString())}
        releaseRuntime.activityCredit={i,actor,block,grown->activity.credit(i,actor,block,grown,false)}
        combatCatalog=snapshot.release?.takeIf { it.enabled }?.combat?.let(com.alytanta.oneblock.combat.CombatRewardCatalog::compile)
        combatRewards=com.alytanta.oneblock.combat.CombatRewardService(this,storage,islands) { combatCatalog }
        val combat=com.alytanta.oneblock.combat.CombatRuntime(this,islands) { islands.releaseRules?.takeIf { it.enabled }?.combat }
        combat.worldRole={p->when {hub.isHubWorld(p.world)->"hub";p.world.key().namespace()=="oneblock_dungeons"->"dungeon";islands.isIslandWorld(p.world)->"island";else->"other"}}
        combat.playerDamageAllowed={p->com.alytanta.oneblock.combat.CombatAccess.pvp(p.world.pvp,hubRules.settings.enabled && hub.isHubWorld(p.world),hubRules.settings.pvp)}
        combatStats=combat
        server.pluginManager.registerEvents(combat,this)
        server.pluginManager.registerEvents(combat.techniques,this)
        server.pluginManager.registerEvents(combat.techniques.wind,this)
        val demo=com.alytanta.oneblock.combat.CombatDemo(this,combat);combatDemo=demo
        server.pluginManager.registerEvents(demo,this)
        command.combatDemoAdmin={sender,args->
            val player=sender as? org.bukkit.entity.Player
            if(player==null)sender.sendMessage("/obadmin combatdemo start|stop (player only)")
            else com.alytanta.oneblock.util.FoliaTasks.entity(this,player) {
                if(!com.alytanta.oneblock.command.CommandAccess.allowed(player,listOf("combatdemo")+args))return@entity
                runCatching {when(args.firstOrNull()?.lowercase()) {
                    "start"->demo.start(player)
                    "stop"->demo.stop(player.uniqueId)
                    else->player.sendMessage("/obadmin combatdemo start|stop")
                }}.onFailure {player.sendMessage(it.message?:"Combat demo unavailable")}
            }
        }
        server.pluginManager.registerEvents(com.alytanta.oneblock.combat.CombatItemProtection {islands.releaseRules?.combat},this)
        val dungeon=com.alytanta.oneblock.dungeon.MineController(this,requireNotNull(snapshot.mine),islands,players,storage,hub,command.playerMenu,requireNotNull(combatRewards),{combatCatalog},combat)
        releaseMenus.rewardMenus.rules={actor->dungeon.binding(actor)?.settings?.roomRewards?:dungeon.settings.roomRewards}
        mine=dungeon
        command.playerMenu.statistics.register("player.place") { player ->
            val place=when {
                dungeon.binding(player.uniqueId)?.world==player.world.key().asString()->"mine"
                hub.isHubWorld(player.world)->"hub"
                islands.findByWorld(player.world)!=null->"island"
                else->"elsewhere"
            }
            locales.textKey(player,"gui.location.$place")
        }
        val grants=com.alytanta.oneblock.items.ItemGrants(this,islands,storage,requireNotNull(combatRewards));itemGrants=grants
        command.itemAdmin=grants::handle
        playerDisplay.contextualLines=dungeon::sidebar
        val dungeonAdmin=com.alytanta.oneblock.dungeon.MineAdministration(dungeon,com.alytanta.oneblock.dungeon.MineSettingsEditor(dataFolder.toPath().resolve("dungeon.yml")) {combatCatalog?.rules})
        mineAdministration=dungeonAdmin
        dungeonAdmin.openProfile={viewer,account->command.administration.menu.openIsland(viewer,account)}
        command.mineAdmin=dungeonAdmin::handle
        command.mineExit={player,after->dungeon.departed(player,after)}
        command.profiles.changeAllowed={actor->!dungeon.profileLocked(actor) && !grants.pending(actor)}
        com.alytanta.oneblock.market.InventoryGuard.canStart={actor->!dungeon.engaged(actor) && packs.allowed(actor)}
        command.playerMenu.operationAllowed={player,route->
            val blocked=dungeon.engaged(player.uniqueId) && route.type in setOf("release","profiles","profile-action","player-command")
            if(blocked)dungeon.say(player,"blocked")
            !blocked
        }
        command.playerMenu.dungeonAction={player,action->when(action) {
            "admin"->dungeonAdmin.handle(player,emptyList())
            "admin-settings"->dungeonAdmin.settings(player)
            "admin-sessions"->dungeonAdmin.sessions(player)
            "admin-players"->dungeonAdmin.players(player)
            "admin-next"->dungeonAdmin.navigation(player,1)
            "admin-previous"->dungeonAdmin.navigation(player,-1)
            "admin-confirm"->dungeonAdmin.confirmed(player)
            "admin-stop-all"->dungeonAdmin.handle(player,listOf("stop-all"))
            "admin-entry"->dungeonAdmin.handle(player,listOf("entry"))
            else->if(action.startsWith("admin-player-"))dungeonAdmin.selectedAction(player,action.removePrefix("admin-player-")) else dungeon.command(player,listOf(action))
        }}
        command.playerMenu.statistics.register("mine.summary") {player->dungeon.binding(player.uniqueId)?.let {s->s.settings.text("status","name" to s.settings.name,"phase" to s.phase,"deaths" to s.deaths,"attempts" to s.remaining,"world" to s.world)}?:dungeon.settings.description}
        command.playerMenu.statistics.register("mine.name") {dungeon.settings.name}
        command.playerMenu.statistics.register("mine.description") {dungeon.settings.description}
        command.playerMenu.statistics.register("mine.duration") {dungeon.settings.estimatedMinutes}
        command.playerMenu.statistics.register("mine.entry") {dungeon.settings.text("entry-location")}
        command.playerMenu.statistics.register("mine.availability",dungeon::availability)
        command.playerMenu.statistics.register("mine.state") {player->
            val run=dungeon.binding(player.uniqueId)
            when {run==null || run.terminal->"idle";run.phase==com.alytanta.oneblock.dungeon.MinePhase.INTERMISSION->"intermission";else->"active"}
        }
        command.playerMenu.statistics.register("mine.party",dungeon::lobbySummary)
        command.administration.menu.dungeonOpen={p->dungeonAdmin.handle(p,emptyList())}
        command.administration.menu.playerOpen=dungeonAdmin::players
        command.administration.menu.dungeonLabel={dungeon.settings.text("admin-dungeons")}
        command.administration.menu.playerLabel={dungeon.settings.text("admin-players")}
        getCommand("dungeon")?.apply {setExecutor(dungeon);tabCompleter=org.bukkit.command.TabCompleter {sender,_,_,args->
            if(!sender.hasPermission("oneblock.command"))emptyList() else when(args.size) {
                1->com.alytanta.oneblock.command.CommandAccess.dungeonActions.filter {it.startsWith(args.last(),true)}
                2->if(args[0].lowercase() in setOf("invite","accept"))server.onlinePlayers.map {it.name}.filter {it.startsWith(args.last(),true)} else emptyList()
                else->emptyList()
            }
        }}?:error("Dungeon command missing")
        server.pluginManager.registerEvents(dungeonAdmin.signs,this)
        server.pluginManager.registerEvents(com.alytanta.oneblock.dungeon.MineProtection(dungeon),this)
        dungeon.initialize(snapshot.minePending)
        command.playerMenu.generation={ storage.generation }
        val guides=com.alytanta.oneblock.guide.GuideController(this,command.playerMenu,islands,players,locales,
            requireNotNull(snapshot.guides),{islands.releaseRules}) { role -> hubGenerator?.serviceLocation(role)?:role }
        guides.context={mapOf("mine_attempts" to dungeon.settings.attempts.toString(),"mine_reconnect" to dungeon.settings.reconnectSeconds.toString(),"mine_name" to dungeon.settings.name)}
        command.guideCommand=guides::command
        val generator=com.alytanta.oneblock.hub.generator.HubGeneratorController(this,requireNotNull(snapshot.hubGenerator),hub,{market.settings},guides::openTopic)
        hubGenerator=generator
        market.npcAdministration={sender,role->
            val player=sender as? Player
            if(player==null)sender.sendMessage("NPC placement is player-only; stand at the destination and face the desired direction.") else FoliaTasks.entity(this,player) {
                if(!hub.isHubWorld(player.world) || !server.pluginManager.isPluginEnabled("FancyNpcs")) {locales.send(player,Lang.MARKET_ACCESS);return@entity}
                fun configuredHubNpc(role:String,name:String)=generator.engine.settings.npcs.getValue(role).let {appearance->
                    Triple(name,generator.engine.settings.sign(role).first(),appearance.type) to appearance.skin
                }
                val configured=when(role) {
                    "shop"->if(market.settings.enabled)configuredHubNpc("merchant",market.settings.shopNpc) else null
                    "bazaar"->if(market.settings.enabled)configuredHubNpc("bazaar",market.settings.bazaarNpc) else null
                    "auction"->if(market.settings.auction.enabled)configuredHubNpc("auction",market.settings.auction.npc) else null
                    "guild"->islands.releaseRules?.activities?.takeIf {it.enabled}?.let {Triple(it.npc,it.display,"VILLAGER") to ""}
                    "guide"->configuredHubNpc("guide",generator.engine.settings.guideNpc)
                    "dungeon"->dungeon.settings.npc.takeIf {it.enabled && dungeon.settings.enabled}?.let {Triple(it.name,it.displayName,it.type) to it.skin}
                    else->null
                }
                if(configured==null){player.sendMessage("NPC role '$role' is disabled or missing its configuration.");return@entity}
                val (appearance,skin)=configured
                com.alytanta.oneblock.market.FancyMarketNpc.create(this,storage,player,appearance.first,locales,appearance.second,appearance.third,skin)
            }
        }
        // Old generated dungeon signs are inert. Only the guide NPC and ordinary menu routes open the selection.
        command.hubgenAdmin=generator::handle
        val buildProtection=com.alytanta.oneblock.hub.generator.HubBuildProtection(generator,this)
        hubBuildProtection=buildProtection
        server.pluginManager.registerEvents(buildProtection,this)
        command.playerMenu.guideAction=guides::action
        server.pluginManager.registerEvents(guides,this)
        server.pluginManager.registerEvents(releaseMenus,this)
        command.releaseAdmin = com.alytanta.oneblock.release.ReleaseAdministration(this,islands,storage) { islands.releaseRules }::handle
        command.playerMenu.onOpen = { player,id -> if(id=="main-menu")releaseRuntime.fact(player,"menu") }
        islands.marketProgress = releaseRuntime::merchant
        market.releaseRecovery = releaseOperations::recover
        core.releaseBusy=releaseOperations::busy
        market.releaseResolution = releaseOperations::resolve
        market.onCatalog = releaseRuntime::fact
        hub.onArrival = { player -> hubRules.applyEntryMode(player);releaseRuntime.fact(player,"hub") }
        server.pluginManager.registerEvents(releaseTools,this)
        server.pluginManager.registerEvents(com.alytanta.oneblock.release.ReleaseEvents(releaseRuntime),this)
        val gathering = com.alytanta.oneblock.oneblock.CoreItemGathering(this,settings.itemGathering,islands::findByWorld)
        coreItemGathering = gathering
        val coreListener = OneBlockListener(this, islands, core, locales, ownedItems, inventoryStatus, settings.coreToolDamage)
        coreListener.releaseBusy=releaseOperations::busy
        val completer = OneBlockTabCompleter(settings, islands, names)
        completer.hubSuggestions=generator::suggestions
        val reload: (CommandSender) -> Unit = reload@{ sender ->
            if (!sender.isOp || !sender.hasPermission("oneblock.admin") || !sender.hasPermission("oneblock.admin.reload")) {
                locales.send(sender, Lang.NO_PERMISSION); return@reload
            }
            if (!reloadInProgress.compareAndSet(false, true)) { locales.send(sender, Lang.CONFIG_RELOAD_BUSY); return@reload }
            FoliaTasks.async(this) {
                try {
                    com.alytanta.oneblock.config.DefaultConfigs.ensure(this,retainedConfigFiles)
                    val next = ConfigSnapshot.read(dataFolder.resolve("config.yml"), logger)
                    next.validateMaterials()
                    val messages = locales.prepareMessages()
                    val nextFiles=com.alytanta.oneblock.config.DefaultConfigs.capture(dataFolder)
                    controlReply(sender,"&eПерезавантаження налаштувань і відновлення з'єднання…")
                    storage.reconnectAsync(DatabaseSettings.load(dataFolder.toPath().resolve(".env"))).join()
                    server.globalRegionScheduler.execute(this) {
                        try {
                            synchronized(this) { run {
                                check(next.inputsUnchanged(dataFolder.resolve("config.yml"))) {
                                    "Configuration changed while reloading; run /ob reload again"
                                }
                                command.playerMenu.validateAliases(requireNotNull(next.mainMenus))
                                next.customItems?.compatible(islands.releaseRules?.customItems)
                                val nextCombat=next.release?.takeIf { it.enabled }?.combat?.let(com.alytanta.oneblock.combat.CombatRewardCatalog::compile)
                                generator.applySettings(requireNotNull(next.hubGenerator))
                                val nextPack=next.resourcePack?:com.alytanta.oneblock.items.ResourcePackSettings()
                                com.alytanta.oneblock.items.CustomItems.customModelsEnabled=nextPack.enabled && nextPack.required
                                packs.apply(nextPack)
                                command.playerMenu.apply(requireNotNull(next.mainMenus))
                                // Keep Bukkit's copy current for /ob admin sethub, without saving over the edited file.
                                config.loadFromString(next.yaml.saveToString())
                                config.options().copyDefaults(false)
                                islands.applySettings(next.plugin)
                                progression.economy = next.plugin.economy
                                core.drops = next.plugin.drops
                                islands.releaseRules = next.release
                                server.onlinePlayers.forEach(itemRefresh::refresh)
                                combatCatalog=nextCombat
                                dungeonAdmin.invalidate();dungeon.settings=requireNotNull(next.mine)
                                releaseMenus.registerRecipes()
                                guides.settings = requireNotNull(next.guides)
                                core.releaseRules = next.release
                                coreListener.toolDamage = next.plugin.coreToolDamage
                                gathering.apply(next.plugin.itemGathering)
                                harvest.settings = next.harvest
                                inventoryStatus.settings = next.harvest
                                islands.profileSettings = next.profiles
                                command.profiles.closeMenus()
                                command.profiles.interfaces = next.interfaces
                                command.administration.menu.applyLayouts(next.interfaces)
                                command.applySettings(next.plugin)
                                completer.applySettings(next.plugin)
                                hub.applySettings(next.plugin.hub)
                                hubRules.settings=next.plugin.hubRules
                                playerDisplay.apply(next.sidebar)
                                locales.applyMessages(next.plugin.defaultLanguage, messages)
                                market.applySettings(next.market)
                                trading.applySettings(next.trades)
                                scheduleFlush(next.plugin.flushIntervalSeconds)
                                islands.refreshLoadedBorders()
                                retainedConfigFiles=nextFiles
                            } }
                            logger.info("[ADMIN] ${sender.name}: reloaded config.yml and locales")
                            storage.audit(com.alytanta.oneblock.storage.OperationLog(operation = "CONFIG_RELOAD", actor = (sender as? Player)?.uniqueId,
                                account = null, success = true, status = "SUCCESS", details = mapOf("files" to listOf("config.yml", "market-menus.yml", "trades.yml", "sidebar.yml", "auction.yml", "harvest.yml", "profiles.yml", "interface-menus.yml", "lang/uk.yml", "lang/en.yml") + requireNotNull(next.mainMenus).sources.keys.map { "../menus/$it" })))
                            reply(sender, locales, Lang.CONFIG_RELOADED)
                        } catch (e: Exception) {
                            storage.audit(com.alytanta.oneblock.storage.OperationLog(operation = "CONFIG_RELOAD", actor = (sender as? Player)?.uniqueId,
                                account = null, success = false, status = "FAILED", reason = e.javaClass.simpleName))
                            logger.log(java.util.logging.Level.SEVERE, "Could not apply OneBlock settings", e)
                            reply(sender, locales, Lang.CONFIG_RELOAD_FAILED)
                        } finally { reloadInProgress.set(false) }
                    }
                } catch (e: Exception) {
                    storage.audit(com.alytanta.oneblock.storage.OperationLog(operation = "CONFIG_RELOAD", actor = (sender as? Player)?.uniqueId,
                        account = null, success = false, status = "FAILED", reason = e.javaClass.simpleName))
                    logger.log(java.util.logging.Level.WARNING, "OneBlock reload rejected; previous settings retained", e)
                    reloadInProgress.set(false)
                    reply(sender, locales, Lang.CONFIG_RELOAD_FAILED)
                }
            }
        }
        reloadAction=reload
        storage.beforeRecoveryPublish={ if(isEnabled)server.onlinePlayers.forEach { com.alytanta.oneblock.market.InventoryGuard.acquire(it.uniqueId) } }
        storage.onRecovered={
            if(isEnabled) {
                val recoveryGeneration=storage.generation
                trading.resetForRecovery()
                releaseOperations.resetForRecovery()
                server.onlinePlayers.forEach { player -> FoliaTasks.entity(this,player) {
                    if(storage.healthy && storage.generation==recoveryGeneration) {
                        com.alytanta.oneblock.market.InventoryGuard.release(player.uniqueId)
                        command.profiles.recover(player)
                        market.recoverPlayer(player)
                        trading.recover(player)
                        command.playerMenu.reservedSlot.ensureItem(player)
                    }
                } }
            }
        }
        server.pluginManager.registerEvents(playerDisplay,this)
        getCommand("trade")?.apply { setExecutor(trading); tabCompleter = trading } ?: error("trade command missing")
        server.pluginManager.registerEvents(trading, this)
        server.pluginManager.registerEvents(trading.signInput, this)
        command.reloadConfiguration = reload
        market.reloadConfiguration = reload
        command.marketAdmin = market::admin
        getCommand("oneblock")?.apply {
            setExecutor(command)
            tabCompleter = completer
        } ?: error("Command 'oneblock' is missing from plugin.yml")
        getCommand("obadmin")?.apply {setExecutor(command);tabCompleter=completer} ?: error("Command 'obadmin' is missing from plugin.yml")
        getCommand("home")?.apply {setExecutor(command);tabCompleter=org.bukkit.command.TabCompleter {_,_,_,_->emptyList()}} ?: error("Command 'home' is missing from plugin.yml")
        getCommand("hub")?.apply {
            setExecutor(command)
            tabCompleter = org.bukkit.command.TabCompleter { _, _, _, _ -> emptyList() }
        } ?: error("Command 'hub' is missing from plugin.yml")

        server.pluginManager.registerEvents(coreListener, this)
        server.pluginManager.registerEvents(com.alytanta.oneblock.harvest.HarvestListener(this, islands, harvest, inventoryStatus, ownedItems).apply {
            release=releaseRuntime;this.releaseTools=releaseTools
        }, this)
        server.pluginManager.registerEvents(inventoryStatus, this)
        com.alytanta.oneblock.menu.MenuInventory.slotChanged = inventoryStatus::slotChanged
        command.playerMenu.inventoryStatus = inventoryStatus
        command.playerMenu.playerState = players
        command.playerMenu.statistics.playerState = players
        command.playerMenu.externalMenuId = { player -> if(command.profiles.isOpen(player)) "profiles" else null }
        command.profiles.authorize = command.playerMenu::authorize
        command.profiles.makeItem = command.playerMenu::makeItem
        command.playerMenu.openProfiles = command.profiles::open
        command.profiles.backToMenu={player->command.playerMenu.execute(player,"main-menu")}
        command.playerMenu.profileAction = command.profiles::command
        server.onlinePlayers.forEach { player -> FoliaTasks.entity(this, player) { inventoryStatus.watch(player) } }
        server.pluginManager.registerEvents(IslandProtectionListener(this, islands, ownedItems), this)
        server.pluginManager.registerEvents(IslandRespawnListener(this, islands, hub::resolveWorldSpawn).apply {externalRespawn=dungeon::ownsRespawn}, this)
        server.pluginManager.registerEvents(gathering, this)
        gathering.start()
        server.pluginManager.registerEvents(ownedItems, this)
        server.pluginManager.registerEvents(names, this)
        server.pluginManager.registerEvents(players, this)
        server.pluginManager.registerEvents(command.profiles, this)
        server.pluginManager.registerEvents(market, this)
        market.startSettlement()
        val retiredWorlds = com.alytanta.oneblock.island.WorldRetirementService(this, islands, storage, hub)
        command.profiles.onProfileRetired = { retiredWorlds.process(it) }
        retiredWorlds.sweep()
        server.globalRegionScheduler.runAtFixedRate(this, { retiredWorlds.sweep() }, 20L * 60L, 20L * 60L)
        server.pluginManager.registerEvents(market.signInput, this)
        command.playerMenu.askInput={player,reply->market.signInput.openLines(player,listOf(net.kyori.adventure.text.Component.empty(),net.kyori.adventure.text.Component.text("^^^^^^^^"),net.kyori.adventure.text.Component.text("Нік гравця"),net.kyori.adventure.text.Component.empty()),0,20L*60L,reply)}
        server.pluginManager.registerEvents(com.alytanta.oneblock.market.InventoryGuard, this)
        server.pluginManager.registerEvents(command.playerMenu, this)
        command.playerMenu.installCommands()
        server.pluginManager.registerEvents(command.playerMenu.reservedSlot, this)
        server.pluginManager.registerEvents(command.administration.menu, this)
        FancyMarketIntegration(this) {
            dungeonNpc?.close()
            val guide=com.alytanta.oneblock.dungeon.DungeonNpcGuide(dungeon);dungeonNpc=guide
            FancyMarketBridge(this, market) {event->activities?.npc(event.npc.data.name,event.player,{event.npc.data.location},{event.npc.isShownFor(event.player)})==true || guide.interact(event) || com.alytanta.oneblock.hub.generator.HubNpcServices.guide(event,generator,guides::openTopic,this)}
        }.start()
        packs.onReady={player->command.profiles.recover(player);trading.recover(player);itemRefresh.refresh(player)}
        server.onlinePlayers.forEach { player -> FoliaTasks.entity(this, player) { packs.offer(player);command.profiles.recover(player); trading.recover(player); command.playerMenu.reservedSlot.watch(player);playerDisplay.watch(player) } }
        scheduleFlush(settings.flushIntervalSeconds)
        server.globalRegionScheduler.runAtFixedRate(this, { islands.unloadIdleWorlds() }, 20L * 60L, 20L * 60L)
        retainedConfigFiles=loadedConfigFiles
    }

    override fun onDisable() {
        runtimeReady=false
        com.alytanta.oneblock.menu.MenuInventory.slotChanged = null
        reloadAction=null
        fun cleanup(name:String,action:()->Unit) { runCatching(action).onFailure {
            logger.log(java.util.logging.Level.WARNING,"Shutdown cleanup failed: $name",it)
        } }
        cleanup("combat rewards") { combatRewards?.close() }
        cleanup("combat demo") {combatDemo?.close()}
        cleanup("item grants") {itemGrants?.close()}
        cleanup("resource pack") {packGate?.close()}
        com.alytanta.oneblock.items.CustomItems.current={null}
        com.alytanta.oneblock.items.CustomItems.customModelsEnabled=false
        com.alytanta.oneblock.items.CustomItems.onHeadProfileResolved=null
        com.alytanta.oneblock.market.InventoryGuard.interactionAllowed={true}
        cleanup("dungeon NPC") {dungeonNpc?.close()}
        cleanup("mine") {mine?.close()}
        cleanup("mine admin") {mineAdministration?.close()}
        com.alytanta.oneblock.market.InventoryGuard.canStart={true}
        cleanup("display") { display?.close() }
        cleanup("hub generator") { hubGenerator?.close() }
        cleanup("hub protection") {hubBuildProtection?.close()}
        cleanup("trades") { trades?.close() }
        cleanup("menu slot") { menuSlot?.close() }
        cleanup("inventory tracker") { inventoryTracker?.close() }
        cleanup("item gathering") { coreItemGathering?.close() }
        cleanup("menus") { mainMenu?.close() }
        cleanup("schedulers") { FoliaTasks.cancelAll(this) }
        if (::storage.isInitialized) storage.close()
    }

    private fun scheduleFlush(seconds: Long) {
        val next = FoliaTasks.asyncRepeating(this, seconds, TimeUnit.SECONDS) { storage.saveNow() }
        flushTask?.cancel()
        flushTask = next
    }

    private fun reply(sender: CommandSender, locales: LocaleService, message: Lang) {
        if (sender is Player) FoliaTasks.entity(this, sender) { locales.send(sender, message) }
        else locales.send(sender, message)
    }

    private fun migrateLegacyStartingKit() {
        val oldDefault = listOf("DIAMOND_SWORD", "DIAMOND_PICKAXE", "DIAMOND_AXE", "DIAMOND_SHOVEL", "STICK", "COBBLESTONE")
        if (config.getStringList("islands.starting-items") != oldDefault) return
        val balancedKit = listOf(
            "WOODEN_SWORD",
            "WOODEN_PICKAXE",
            "WOODEN_AXE",
            "WOODEN_SHOVEL",
            "BREAD:8",
            "OAK_SAPLING:4",
            "TORCH:16",
        )
        config.set("islands.starting-items", balancedKit)
        saveConfig()
        logger.info("Migrated the default starting kit to the balanced OneBlock kit.")
    }

    private fun isSupportedWorldsVersion(version: String): Boolean {
        val numbers = version.substringBefore('-').split('.').map { it.toIntOrNull() ?: return false }
        val required = listOf(4, 5, 0)
        for (index in required.indices) {
            val current = numbers.getOrElse(index) { 0 }
            if (current != required[index]) return current > required[index]
        }
        return true
    }
}
