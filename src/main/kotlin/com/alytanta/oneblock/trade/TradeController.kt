package com.alytanta.oneblock.trade

import com.alytanta.oneblock.island.IslandService
import com.alytanta.oneblock.locale.*
import com.alytanta.oneblock.market.*
import com.alytanta.oneblock.menu.MenuInventory
import com.alytanta.oneblock.storage.*
import com.alytanta.oneblock.util.*
import org.bukkit.*
import org.bukkit.command.*
import org.bukkit.entity.Player
import org.bukkit.event.*
import org.bukkit.event.entity.PlayerDeathEvent
import org.bukkit.event.inventory.*
import org.bukkit.event.player.*
import org.bukkit.inventory.*
import org.bukkit.persistence.PersistentDataType
import org.bukkit.plugin.Plugin
import java.util.UUID
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicBoolean

/** Virtual offers, durable escrow, then personal claims. All inventory access belongs to the entity's scheduler. */
class TradeController(private val plugin: Plugin, initial: TradeSettings, private val storage: PostgresStorageService,
    private val islands: IslandService, private val locales: LocaleService, market: () -> MarketEngine) : Listener, CommandExecutor, TabCompleter {
    @Volatile private var settings = initial
    val signInput = VirtualSignInput(plugin)
    private val handoffs = ItemHandoffs(plugin, storage, islands, market, locales)
    private val marker = NamespacedKey(plugin, "player_trade")
    private val mutex = Any()
    private val sessions = ConcurrentHashMap<UUID, Session>()
    private val requests = ConcurrentHashMap<UUID, Request>()
    private val collecting = ConcurrentHashMap.newKeySet<UUID>()
    private val resolving = ConcurrentHashMap.newKeySet<UUID>()
    private val lastRequest = ConcurrentHashMap<UUID, Long>()
    private enum class Phase { OPEN, PREPARING, COMMITTING, CLOSED }
    private data class Request(val from: Player, val name: String, val expires: Long)
    private data class Offer(val slot: Int, val original: ItemStack, val amount: Int) {
        fun stack() = original.clone().apply { amount = this@Offer.amount }
    }
    private class Peer(val player: Player, val name: String, val account: UUID) {
        val id = player.uniqueId
        val offers = linkedMapOf<Int, Offer>()
        var coins = 0L
        var ready = false
        var input = false
        var position: Location? = null
        var holder: Holder? = null
        var task: io.papermc.paper.threadedregions.scheduler.ScheduledTask? = null
    }
    private class Session(val peers: List<Peer>, val config: TradeSettings) {
        val id = UUID.randomUUID()
        val started = System.currentTimeMillis()
        var changed = started
        var countdown = 0L
        var phase = Phase.OPEN
        var cancelled = false
        var databaseGeneration = 0L
        val inventoryTokens = mutableMapOf<UUID, UUID>()
    }
    private class Holder(val session: Session, val peer: Peer) : InventoryHolder {
        lateinit var contents: Inventory
        override fun getInventory() = contents
    }
    private data class Capture(val peer: Peer, val after: Array<ItemStack?>, val leg: TradeLeg, val position: Location)

    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        if (sender is Player) command(sender, args.toList()) else locales.send(sender, Lang.ONLY_PLAYER)
        return true
    }
    override fun onTabComplete(sender: CommandSender, command: Command, alias: String, args: Array<out String>): List<String> {
        if (!sender.hasPermission("oneblock.trade")) return emptyList()
        val names = plugin.server.onlinePlayers.filter { it.uniqueId != (sender as? Player)?.uniqueId }.map { it.name }
        val choices = if (args.size <= 1) names + listOf("accept", "deny", "cancel", "collect") else if (args.size == 2 && args[0] == "accept") names else emptyList()
        return choices.filter { it.startsWith(args.lastOrNull().orEmpty(), true) }
    }
    fun command(player: Player, args: List<String>) {
        if(!InventoryGuard.canStart(player.uniqueId)) {locales.send(player,Lang.MARKET_BUSY);return}
        if (!player.hasPermission("oneblock.trade")) { locales.send(player, Lang.NO_PERMISSION); return }
        if (!storage.healthy) { locales.send(player, Lang.MARKET_UNAVAILABLE); return }
        when (args.firstOrNull()?.lowercase()) {
            "cancel" -> {
                sessions[player.uniqueId]?.let(::cancel)
                requests.remove(player.uniqueId)?.let { notify(it.from, Lang.P2P_CANCELLED) }
                requests.forEach { (target,request) -> if(request.from.uniqueId==player.uniqueId && requests.remove(target,request)) notifyActor(target,Lang.P2P_CANCELLED) }
            }
            "deny" -> requests.remove(player.uniqueId)?.let { notify(it.from, Lang.P2P_CANCELLED); locales.send(player, Lang.P2P_CANCELLED) }
            "collect" -> collect(player)
            "accept" -> accept(player, args.getOrNull(1))
            null -> locales.send(player, Lang.P2P_USAGE)
            else -> {
                val target = plugin.server.getPlayerExact(args[0])
                if (target == null) locales.send(player, Lang.P2P_UNAVAILABLE) else request(player, target)
            }
        }
    }
    private fun allowedMode(player: Player, config: TradeSettings) = player.gameMode == GameMode.CREATIVE || player.gameMode in config.allowedModes
    private fun available(player: Player, config: TradeSettings) = config.enabled && storage.healthy &&
        player.hasPermission("oneblock.trade") && !player.isDead && !player.isInsideVehicle && allowedMode(player,config) &&
        player.itemOnCursor.isEmpty && !InventoryGuard.busy(player.uniqueId) && !sessions.containsKey(player.uniqueId) &&
        !PlayerTradeLedger.pending(storage.marketState(), player.uniqueId) &&
        storage.loadProfiles().firstOrNull { it.uniqueId == player.uniqueId }?.profileSwitch == null &&
        storage.marketState().transfers.values.none { it.actor == player.uniqueId && it.status in setOf(TransferStatus.PREPARED, TransferStatus.REVIEW) } &&
        islands.findForPlayer(player.uniqueId) != null

    private fun request(player: Player, target: Player) {
        if(!InventoryGuard.canStart(player.uniqueId) || !InventoryGuard.canStart(target.uniqueId)) {locales.send(player,Lang.MARKET_BUSY);return}
        val config = settings
        if (player.uniqueId == target.uniqueId || !available(player, config)) { locales.send(player, Lang.P2P_UNAVAILABLE); return }
        val now = System.currentTimeMillis()
        val previous = lastRequest.put(player.uniqueId, now)
        if (previous != null && now - previous < 2000) return
        val position = player.location.clone()
        FoliaTasks.entity(plugin, target) {
            if (!available(target, config) || !near(position, target.location, config.distance)) { notify(player, Lang.P2P_UNAVAILABLE); return@entity }
            val old = requests[target.uniqueId]
            if (old != null && old.expires > now) { notify(player, Lang.P2P_UNAVAILABLE); return@entity }
            requests[target.uniqueId] = Request(player, player.name, now + config.requestSeconds * 1000)
            target.sendMessage(Colors.colorize(locales.text(target, Lang.P2P_RECEIVED, "player" to player.name))
                .clickEvent(net.kyori.adventure.text.event.ClickEvent.runCommand("/trade accept ${player.name}")))
            notify(player, Lang.P2P_SENT, "player" to target.name)
        }
    }
    private fun accept(player: Player, name: String?) {
        val request = requests.remove(player.uniqueId)
        if (request == null || request.expires < System.currentTimeMillis() || name != null && !request.name.equals(name, true) || !available(player, settings)) {
            locales.send(player, Lang.P2P_UNAVAILABLE); return
        }
        val position = player.location.clone()
        val own = Peer(player, player.name, islands.findForPlayer(player.uniqueId)!!.owner)
        FoliaTasks.entity(plugin, request.from) {
            val other = request.from
            if (!available(other, settings) || !near(position, other.location, settings.distance)) { notify(player, Lang.P2P_UNAVAILABLE); return@entity }
            val partner = Peer(other, request.name, islands.findForPlayer(other.uniqueId)!!.owner)
            val session = Session(listOf(own, partner), settings)
            val installed = synchronized(mutex) {
                if (session.peers.any { sessions.containsKey(it.id) }) false else { session.peers.forEach { sessions[it.id] = session }; true }
            }
            if (installed) session.peers.forEach { peer -> FoliaTasks.entity(plugin, peer.player, retired = { cancel(session) }) { open(session, peer) } }
        }
    }
    private fun near(a: Location, b: Location, distance: Double) = a.world?.uid == b.world?.uid && a.distanceSquared(b) <= distance * distance
    private fun open(session: Session, peer: Peer) = synchronized(mutex) {
        if (session.phase != Phase.OPEN || sessions[peer.id] !== session) return@synchronized
        val player = peer.player
        if (!session.config.enabled || !InventoryGuard.canStart(peer.id) || player.isDead || islands.findForPlayer(peer.id)?.owner != peer.account || InventoryGuard.busy(peer.id)) { cancel(session); return@synchronized }
        val holder = Holder(session, peer)
        val partner = session.peers.first { it !== peer }.name
        val title = session.config.title?.replace("{player}", partner)?.replace("{viewer}", player.name)
            ?: locales.text(player, Lang.P2P_TITLE, "player" to partner)
        holder.contents = plugin.server.createInventory(holder, session.config.rows * 9, Colors.title(title))
        peer.holder = holder
        render(session, peer)
        player.openInventory(holder.contents)
        if (peer.task == null) peer.task = player.scheduler.runAtFixedRate(plugin, { heartbeat(session, peer) }, { cancel(session) }, 1L, 20L)
    }
    private fun heartbeat(session: Session, peer: Peer) = synchronized(mutex) {
        if (session.phase != Phase.OPEN) return@synchronized
        val player = peer.player
        peer.position = player.location.clone()
        if (!storage.healthy || !InventoryGuard.canStart(peer.id) || player.isDead || player.isInsideVehicle || !allowedMode(player,session.config) ||
            islands.findForPlayer(peer.id)?.owner != peer.account || System.currentTimeMillis() - session.started > session.config.sessionSeconds * 1000 ||
            (!peer.input && player.openInventory.topInventory.holder !== peer.holder) ||
            peer.offers.values.any { player.inventory.getItem(it.slot) != it.original }) { cancel(session); return@synchronized }
        val other = session.peers.first { it !== peer }
        if (other.position?.let { !near(peer.position!!, it, session.config.distance) } == true) { cancel(session); return@synchronized }
        if (session.countdown > 0 && System.currentTimeMillis() >= session.countdown) prepare(session)
        else if (!peer.input) render(session, peer)
    }
    private fun icon(config: TradeSettings, id: String, player: Player, material: Material, key: Lang?, vararg args: Pair<String, Any>): ItemStack? {
        if (!config.visible(id)) return null
        val style = config.style(id)
        val original = key?.let { locales.text(player, it, *args).lines() } ?: listOf(" ")
        fun expand(text: String) = (args.toMap() + ("viewer" to player.name)).entries.fold(text) { value, (name, replacement) -> value.replace("{$name}", replacement.toString()) }
        val raw=ItemStack(material).apply {
            editMeta { meta ->
                meta.displayName(Colors.colorize(expand(original.first())))
                meta.lore(original.drop(1).map { Colors.colorize(expand(it)) })
            }
        }
        return com.alytanta.oneblock.menu.GuiItems.copy(plugin,player,raw,style,args.associate {it.first to it.second.toString()})
    }
    private fun render(session: Session, peer: Peer) {
        val holder = peer.holder ?: return
        val config = session.config; val player = peer.player; val other = session.peers.first { it !== peer }
        val inventory = holder.contents
        val filler = icon(config, "filler", player, config.filler, null)
        repeat(inventory.size) { inventory.setItem(it, filler?.clone()?.let(com.alytanta.oneblock.menu.GuiItems::quiet)) }
        config.dividerSlots.forEach { inventory.setItem(it, icon(config, "divider", player, config.divider, null)?.let(com.alytanta.oneblock.menu.GuiItems::quiet)) }
        config.ownSlots.forEach { inventory.setItem(it, null) };config.otherSlots.forEach { inventory.setItem(it, null) }
        fun offers(owner: Peer, slots: List<Int>, own: Boolean) = owner.offers.values.forEachIndexed { index, offer ->
            val preview=offer.stack().apply { editMeta { meta ->
                meta.displayName()?.let { meta.displayName(Colors.normal(it)) }
                val hint = (if (own) config.ownItemLore else config.otherItemLore)
                    ?: locales.text(player, if (own) Lang.P2P_REMOVE else Lang.P2P_THEIR_ITEM).lines()
                meta.lore((meta.lore().orEmpty() + hint.map(Colors::colorize)).map(Colors::normal))
            } }
            val style=config.style(if(own)"own-item" else "other-item")
            if(style?.enabled!=false)inventory.setItem(slots[index],com.alytanta.oneblock.menu.GuiItems.copy(plugin,player,preview,style,
                mapOf("amount" to offer.amount.toString(),"owner" to owner.name)))
        }
        offers(peer, config.ownSlots, true);offers(other, config.otherSlots, false)
        inventory.setItem(config.ownName, icon(config, "own-name", player, config.icons.getValue("own-name"), Lang.P2P_OWN, "player" to peer.name))
        inventory.setItem(config.otherName, icon(config, "other-name", player, config.icons.getValue("other-name"), Lang.P2P_OTHER, "player" to other.name))
        inventory.setItem(config.ownCoins, icon(config, "own-coins", player, config.icons.getValue("own-coins"), Lang.P2P_COINS, "coins" to peer.coins))
        inventory.setItem(config.otherCoins, icon(config, "other-coins", player, config.icons.getValue("other-coins"), Lang.P2P_OTHER_COINS, "coins" to other.coins))
        inventory.setItem(config.ownReady, icon(config, if (peer.ready) "own-ready" else "own-wait", player, config.icons.getValue(if (peer.ready) "ready" else "not-ready"), if (peer.ready) Lang.P2P_READY else Lang.P2P_ACCEPT))
        inventory.setItem(config.otherReady, icon(config, if (other.ready) "other-ready" else "other-wait", player, config.icons.getValue(if (other.ready) "ready" else "not-ready"), if (other.ready) Lang.P2P_READY else Lang.P2P_WAIT))
        val seconds = if (session.countdown > 0) ((session.countdown-System.currentTimeMillis()+999)/1000).coerceAtLeast(0) else 0
        inventory.setItem(config.status, icon(config, if (session.countdown > 0) "countdown" else "status", player, config.icons.getValue("status"), if (session.countdown > 0) Lang.P2P_COUNTDOWN else Lang.P2P_HELP, "seconds" to seconds))
        inventory.setItem(config.cancel, icon(config, "cancel", player, config.icons.getValue("cancel"), Lang.P2P_CANCEL))
    }
    private fun changed(session: Session) { session.peers.forEach { it.ready = false };session.countdown = 0;session.changed = System.currentTimeMillis() }
    @EventHandler(priority = EventPriority.HIGHEST) fun click(event: InventoryClickEvent) {
        val holder = event.view.topInventory.holder as? Holder ?: return
        event.isCancelled = true
        val player = event.whoClicked as? Player ?: return
        val session = holder.session; val peer = holder.peer
        synchronized(mutex) {
            if (player.uniqueId != peer.id || sessions[peer.id] !== session || peer.holder !== holder || session.phase != Phase.OPEN || peer.input || InventoryGuard.busy(peer.id)) return
            if (event.click !in setOf(ClickType.LEFT, ClickType.RIGHT, ClickType.SHIFT_LEFT, ClickType.SHIFT_RIGHT)) return
            val config = session.config
            val slot = event.rawSlot
            if (slot >= holder.contents.size && event.clickedInventory === player.inventory) {
                val source = event.slot; val item = player.inventory.getItem(source) ?: return
                if (source !in 0..35 || source == MenuInventory.SLOT || item.isEmpty || item.type.name in config.blocked || !com.alytanta.oneblock.items.CustomItems.marketTransferAllowed(item)) return
                try { TradeInventory.item(item, config.maxItemBytes) } catch (_: Exception) { locales.send(player, Lang.P2P_INVALID_ITEM); return }
                val existing = peer.offers[source]
                if (existing == null && peer.offers.size >= config.ownSlots.size) return
                val amount = if (event.isRightClick) ((existing?.amount ?: 0)+1).coerceAtMost(item.amount) else item.amount
                if (amount !in 1..64) { locales.send(player, Lang.P2P_INVALID_ITEM); return }
                peer.offers[source] = Offer(source, item.clone(), amount);changed(session)
            } else if (slot in config.ownSlots) {
                peer.offers.keys.toList().getOrNull(config.ownSlots.indexOf(slot))?.let { peer.offers.remove(it);changed(session) }
            } else when (config.actionAt(slot,peer.ready)) {
                "cancel" -> cancel(session)
                "coins" -> {
                    if (!config.visible("own-coins")) return
                    if (session.peers.map { it.account }.distinct().size == 1) { locales.send(player, Lang.P2P_SHARED_WALLET); return }
                    changed(session);peer.input = true
                    signInput.open(player, peer.coins, listOf(Lang.P2P_SIGN_1,Lang.P2P_SIGN_2,Lang.P2P_SIGN_3).map { Colors.colorize(locales.text(player,it)) },600) { text ->
                        synchronized(mutex) {
                            peer.input = false
                            if (session.phase != Phase.OPEN) return@open
                            val value = VirtualSignInput.number(text,0,config.maxCoins)
                            val balance = islands.findByOwner(peer.account)?.coins ?: 0
                            if (value != null && value <= balance) peer.coins = value else if (text != null) locales.send(player, Lang.P2P_INVALID_COINS)
                            changed(session);open(session,peer)
                        }
                    }
                }
                "ready" -> {
                    if (!config.visible(if (peer.ready) "own-ready" else "own-wait")) return
                    if (session.peers.any { it.input } || System.currentTimeMillis()-session.changed < config.changeCooldownSeconds*1000) { locales.send(player, Lang.P2P_COOLDOWN); return }
                    if (session.peers.all { it.offers.isEmpty() && it.coins==0L }) return
                    peer.ready = !peer.ready
                    session.countdown = if (session.peers.all { it.ready }) System.currentTimeMillis()+config.countdownSeconds*1000 else 0
                }
            }
            if (session.phase == Phase.OPEN && !peer.input) render(session, peer)
        }
    }
    private fun capture(session: Session, peer: Peer): Capture {
        val player = peer.player
        check(InventoryGuard.canStart(peer.id) && !player.isDead && allowedMode(player,session.config) && !player.isInsideVehicle && player.itemOnCursor.isEmpty)
        check(islands.findForPlayer(peer.id)?.owner == peer.account)
        val before = player.inventory.storageContents.map { it?.clone() }.toTypedArray()
        val after = before.map { it?.clone() }.toTypedArray()
        val offers = synchronized(mutex) { peer.offers.values.toList() }
        offers.forEach { offer ->
            check(after[offer.slot] == offer.original)
            after[offer.slot] = if (offer.amount == offer.original.amount) null else offer.original.clone().apply { amount -= offer.amount }
        }
        // Capacity preflight is only a simulation. Receipt occurs through journaled personal claims.
        var simulated = after
        val other = session.peers.first { it !== peer }
        other.offers.values.forEach { simulated = ItemTransferPlan.withdraw(simulated, it.stack(), player.inventory.maxStackSize) }
        return Capture(peer,after,TradeLeg(peer.id,peer.name,peer.account,peer.coins,offers.map { TradeInventory.item(it.stack(),session.config.maxItemBytes) },
            TradeInventory.hash(before),TradeInventory.hash(after)),player.location.clone())
    }
    private fun prepare(session: Session) {
        if (session.phase != Phase.OPEN) return
        if(session.peers.any {!InventoryGuard.canStart(it.id)}) {cancel(session);return}
        session.phase = Phase.PREPARING
        session.databaseGeneration = storage.generation
        val acquired = session.peers.filter { peer -> InventoryGuard.acquireToken(peer.id)?.let { session.inventoryTokens[peer.id] = it; true } ?: false }
        fun releaseAcquired() { acquired.forEach { InventoryGuard.release(it.id, session.inventoryTokens.getValue(it.id)) } }
        if (acquired.size != 2) { releaseAcquired();cancel(session);return }
        val futures = session.peers.map { peer ->
            val future = CompletableFuture<Capture>()
            FoliaTasks.entity(plugin,peer.player,retired = { future.completeExceptionally(IllegalStateException("Player left")) }) {
                runCatching { peer.player.closeInventory();capture(session,peer) }.fold(future::complete,future::completeExceptionally)
            };future
        }
        CompletableFuture.allOf(*futures.toTypedArray()).whenComplete { _,error ->
            if (error != null) {
                releaseAcquired();cancel(session)
                var cause: Throwable = error
                while(cause.cause != null) cause = cause.cause!!
                if(cause is MarketException && cause.reason=="inventory-full") session.peers.forEach { notify(it.player,Lang.MARKET_NO_SPACE) }
                return@whenComplete
            }
            val captures = futures.map { it.join() }
            try {
                synchronized(mutex) {
                    check(storage.healthy && storage.generation==session.databaseGeneration && !session.cancelled && near(captures[0].position,captures[1].position,session.config.distance))
                    val record = PlayerTrade(session.id,captures.map { it.leg })
                    islands.playerTradeUpdate(record.legs.map { it.account }.toSet()) { wallets,state -> PlayerTradeLedger.prepare(record,wallets,state,session.config.historyLimit) }
                    session.phase = Phase.COMMITTING
                }
                storage.flushAsync().whenComplete { _,failure ->
                    if (failure != null) { journalFailure(session,failure);return@whenComplete }
                    captures.forEach { apply(session,it) }
                }
            } catch (e: Exception) {
                releaseAcquired();cancel(session)
                storage.audit(OperationLog(operation="PLAYER_TRADE_REJECTED",actor=session.peers[0].id,account=session.peers[0].account,
                    success=false,status="FAILED",reason=e.message?.take(120)?:e.javaClass.simpleName,correlation=session.id,
                    details=mapOf("before_deposit" to true)))
                if(e.message=="coins")session.peers.forEach { notify(it.player,Lang.TOO_POOR) }
            }
        }
    }
    private fun apply(session: Session, capture: Capture) {
        val peer = capture.peer;val once = AtomicBoolean()
        fun resolveOnce(status: DepositStatus, reason: String?=null) {
            if (once.compareAndSet(false,true)) resolve(session.id,peer.id,status,reason,session.inventoryTokens.getValue(peer.id))
        }
        FoliaTasks.entity(plugin,peer.player,retired = { if(storage.healthy && storage.generation==session.databaseGeneration) resolveOnce(DepositStatus.DECLINED,"player-left-before-deposit") }) {
            if (!storage.healthy || storage.generation!=session.databaseGeneration) return@entity
            val player = peer.player
            if (player.isDead || islands.findForPlayer(peer.id)?.owner != peer.account || TradeInventory.hash(player.inventory.storageContents)!=capture.leg.beforeHash) {
                resolveOnce(DepositStatus.DECLINED,"inventory-or-profile-changed");return@entity
            }
            try {
                player.inventory.storageContents = capture.after
                player.persistentDataContainer.set(marker,PersistentDataType.STRING,session.id.toString())
                player.saveData()
                resolveOnce(DepositStatus.APPLIED)
            } catch (e: Exception) { resolveOnce(DepositStatus.REVIEW,"inventory-save-${e.javaClass.simpleName}") }
        }
    }
    private fun resolve(id: UUID, actor: UUID, status: DepositStatus, reason: String?, token: UUID) {
        try {
            val old = requireNotNull(storage.marketState().playerTrades[id])
            val result = islands.playerTradeUpdate(old.legs.map { it.account }.toSet()) { wallets,state -> PlayerTradeLedger.resolve(id,actor,status,wallets,state,reason) }
            storage.flushAsync().whenComplete { _,failure ->
                if (failure != null) { logFailure(id,failure);notifyActor(actor,Lang.MARKET_UNAVAILABLE);return@whenComplete }
                if (status != DepositStatus.REVIEW) InventoryGuard.release(actor, token)
                else notifyActor(actor,Lang.P2P_REVIEW)
                val current = storage.marketState().playerTrades.getValue(id)
                if (current.status in setOf(TradeStatus.DONE,TradeStatus.ABORTED)) {
                    val session = sessions[actor]
                    if (session?.id == id) synchronized(mutex) { finishSession(session,if (current.status==TradeStatus.DONE) Lang.P2P_DONE else Lang.P2P_CANCELLED) }
                    if (plugin.isEnabled) current.legs.forEach { leg -> plugin.server.getPlayer(leg.actor)?.let { player -> FoliaTasks.entity(plugin,player) { collect(player) } } }
                } else if (result.status == TradeStatus.REVIEW) sessions[actor]?.takeIf { it.id==id }?.let { synchronized(mutex) { finishSession(it,Lang.P2P_REVIEW) } }
            }
        } catch (e: Exception) { logFailure(id,e);notifyActor(actor,Lang.P2P_REVIEW) }
    }
    private fun journalFailure(session: Session, error: Throwable) {
        logFailure(session.id,error)
        synchronized(mutex) { finishSession(session,Lang.MARKET_UNAVAILABLE) }
        // Keep both inventories frozen: a failed commit can have an unknown durable outcome.
    }
    private fun logFailure(id: UUID,error: Throwable) {
        plugin.logger.log(java.util.logging.Level.SEVERE,"Player trade $id requires attention",error)
        storage.audit(OperationLog(operation="PLAYER_TRADE_ERROR",actor=null,account=null,success=false,status="REVIEW",reason=error.javaClass.simpleName,correlation=id))
    }
    private fun finishSession(session: Session,message: Lang) {
        session.phase = Phase.CLOSED
        session.peers.forEach { peer ->
            sessions.remove(peer.id,session);peer.task?.cancel()
            if (!plugin.isEnabled) signInput.forget(peer.player) else FoliaTasks.entity(plugin,peer.player) {
                signInput.cancel(peer.player)
                if ((peer.player.openInventory.topInventory.holder as? Holder)?.session === session) peer.player.closeInventory()
                locales.send(peer.player,message)
            }
        }
    }
    private fun cancel(session: Session) = synchronized(mutex) {
        when(session.phase) {
            Phase.OPEN -> finishSession(session,Lang.P2P_CANCELLED)
            Phase.PREPARING -> { session.cancelled=true;finishSession(session,Lang.P2P_CANCELLED) }
            else -> Unit // Durable escrow can only be settled by deposit results/recovery.
        }
    }
    private fun notify(player: Player,key: Lang,vararg args: Pair<String,Any>) { if(plugin.isEnabled) FoliaTasks.entity(plugin,player) { locales.send(player,key,*args) } }
    private fun notifyActor(actor: UUID,key: Lang) { plugin.server.getPlayer(actor)?.let { notify(it,key) } }
    fun collect(player: Player) {
        if(!InventoryGuard.canStart(player.uniqueId))return
        if (!storage.healthy || InventoryGuard.busy(player.uniqueId) || !collecting.add(player.uniqueId)) return
        fun next() {
            val account = islands.findForPlayer(player.uniqueId)?.owner
            val claim = storage.marketState().claims.values.firstOrNull { it.account==account && it.recipient==player.uniqueId && it.product!=null }
            if (claim == null || player.isDead || account==null) { collecting.remove(player.uniqueId);return }
            handoffs.transfer(player,account,requireNotNull(claim.product),claim.amount.toInt(),TransferKind.WITHDRAW,claimId=claim.id,
                failed={ collecting.remove(player.uniqueId) },silent=true) { next() }
        }
        next()
    }
    fun recover(player: Player) {
        if (!storage.healthy || InventoryGuard.busy(player.uniqueId)) return
        handoffs.recover(player)
        val pending = storage.marketState().playerTrades.values.filter { it.status in setOf(TradeStatus.PREPARED,TradeStatus.REVIEW) && it.legs.any { leg -> leg.actor==player.uniqueId } }
        pending.forEach { record ->
            val leg=record.legs.first { it.actor==player.uniqueId }
            if (leg.status == DepositStatus.REVIEW) { InventoryGuard.acquire(player.uniqueId);locales.send(player,Lang.P2P_REVIEW);return@forEach }
            if (leg.status != DepositStatus.PENDING) return@forEach
            val token = InventoryGuard.acquireToken(player.uniqueId) ?: return@forEach
            val deposit = when {
                player.persistentDataContainer.get(marker,PersistentDataType.STRING)==record.id.toString() -> DepositStatus.APPLIED
                TradeInventory.hash(player.inventory.storageContents)==leg.beforeHash -> DepositStatus.DECLINED
                else -> DepositStatus.REVIEW
            }
            resolve(record.id,player.uniqueId,deposit,if(deposit==DepositStatus.REVIEW)"ambiguous-reconnect" else null,token)
        }
        if (pending.isEmpty()) collect(player)
    }
    fun admin(sender: CommandSender,args: List<String>) {
        if (sender is Player || !sender.isOp || !sender.hasPermission("oneblock.admin.manage")) { locales.send(sender,Lang.NO_PERMISSION);return }
        val records=storage.marketState().playerTrades
        if(args.firstOrNull()=="review") {
            sender.sendMessage(records.values.filter { it.status in setOf(TradeStatus.PREPARED,TradeStatus.REVIEW) }.joinToString("\n") {
                "${it.id} ${it.status}: "+it.legs.joinToString { leg -> "${leg.nickname}=${leg.status}" }
            }.ifEmpty { "No pending player trades" });return
        }
        val record=args.getOrNull(1)?.let { runCatching { UUID.fromString(it) }.getOrNull() }?.let(records::get)
        val leg=record?.legs?.firstOrNull { it.nickname.equals(args.getOrNull(2),true) }
        if(args.size!=5||args[0]!="resolve"||record==null||leg==null||args[3] !in setOf("applied","not-applied")||args[4]!="CONFIRM") {
            sender.sendMessage("/ob admin trades review | resolve <trade-id> <nickname> <applied|not-applied> CONFIRM");return
        }
        if(record.status !in setOf(TradeStatus.PREPARED,TradeStatus.REVIEW)||leg.status !in setOf(DepositStatus.PENDING,DepositStatus.REVIEW)) { sender.sendMessage("Deposit already resolved");return }
        if(plugin.server.getPlayer(leg.actor)!=null||!resolving.add(leg.actor)) { sender.sendMessage("Target player must be offline");return }
        FoliaTasks.async(plugin) {
            runCatching {
                val backup=storage.backup()
                check(plugin.server.getPlayer(leg.actor)==null)
                val status=if(args[3]=="applied")DepositStatus.APPLIED else DepositStatus.DECLINED
                islands.playerTradeUpdate(record.legs.map { it.account }.toSet()) { wallets,state -> PlayerTradeLedger.resolve(record.id,leg.actor,status,wallets,state,"manual-${sender.name}") }
                check(storage.flush());InventoryGuard.release(leg.actor)
                sender.sendMessage("Trade deposit resolved. Backup: ${backup.name}")
            }.onFailure { logFailure(record.id,it);sender.sendMessage("Resolution failed: ${it.javaClass.simpleName}") }
            resolving.remove(leg.actor)
        }
    }
    fun applySettings(next: TradeSettings) { sessions.values.toSet().forEach(::cancel);settings=next;requests.clear() }
    fun resetForRecovery() = synchronized(mutex) { sessions.values.toSet().forEach { finishSession(it,Lang.MARKET_UNAVAILABLE) };requests.clear() }
    fun close() { sessions.values.toSet().forEach(::cancel);requests.clear();if(plugin.isEnabled)signInput.cancelAll() }
    @EventHandler fun login(event: AsyncPlayerPreLoginEvent) {
        if(event.uniqueId in resolving)event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_OTHER,Colors.colorize("Trade recovery is in progress. Please reconnect shortly."))
    }
    @EventHandler(priority=EventPriority.MONITOR) fun join(event: PlayerJoinEvent) { FoliaTasks.entity(plugin,event.player) { recover(event.player) } }
    @EventHandler fun quit(event: PlayerQuitEvent) {
        val id=event.player.uniqueId
        sessions[id]?.let(::cancel);requests.remove(id);requests.entries.removeIf { it.value.from.uniqueId==id };lastRequest.remove(id);collecting.remove(id);signInput.forget(event.player)
        // Persistent journals restore any unresolved freeze on reconnect.
        if(PlayerTradeLedger.pending(storage.marketState(),id))InventoryGuard.release(id)
    }
    @EventHandler fun death(event: PlayerDeathEvent) { sessions[event.entity.uniqueId]?.let(::cancel) }
    @EventHandler(priority=EventPriority.MONITOR,ignoreCancelled=true) fun teleport(event: PlayerTeleportEvent) { sessions[event.player.uniqueId]?.let(::cancel) }
    @EventHandler fun close(event: InventoryCloseEvent) {
        val holder=event.inventory.holder as? Holder?:return
        synchronized(mutex) { if(holder.peer.holder===holder && !holder.peer.input && holder.session.phase==Phase.OPEN)cancel(holder.session) }
    }
    @EventHandler(priority=EventPriority.HIGHEST) fun drag(event: InventoryDragEvent) { if(event.view.topInventory.holder is Holder)event.isCancelled=true }
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true) fun interact(event: PlayerInteractEntityEvent) {
        val target=event.rightClicked as? Player?:return
        if(event.hand==EquipmentSlot.HAND && event.player.isSneaking) { event.isCancelled=true;request(event.player,target) }
    }
}
