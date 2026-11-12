package com.alytanta.oneblock.island

import com.alytanta.oneblock.config.PluginConfig
import com.alytanta.oneblock.oneblock.CoreMode
import com.alytanta.oneblock.oneblock.CoreState
import com.alytanta.oneblock.storage.StorageService
import com.alytanta.oneblock.storage.PostgresStorageService
import com.alytanta.oneblock.market.MarketState
import com.alytanta.oneblock.market.MarketChange
import net.kyori.adventure.key.Key
import net.thenextlvl.worlds.WorldsAccess
import net.thenextlvl.worlds.Backup
import net.thenextlvl.worlds.generator.GeneratorType
import net.thenextlvl.worlds.Level as WorldsLevel
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.World
import org.bukkit.entity.Player
import org.bukkit.plugin.Plugin
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.logging.Level as JulLevel
import java.nio.file.Files
import java.util.concurrent.CompletableFuture

sealed interface IslandCreateResult {
    data object AlreadyExists : IslandCreateResult
    data class Created(val island: Island) : IslandCreateResult
}

class IslandService(
    private val plugin: Plugin,
    @Volatile private var settings: PluginConfig,
    private val storage: StorageService,
    private val players: com.alytanta.oneblock.player.PlayerService? = null,
    private val worlds: () -> net.thenextlvl.worlds.WorldsAccess = WorldsAccess::access,
) : IslandProgressStore, IslandRespawns {
    private val requestLock = Any()
    private val worldRequests = mutableMapOf<UUID, MutableList<(World?) -> Unit>>()
    private val platformRequests = mutableMapOf<UUID, MutableList<(Boolean) -> Unit>>()
    private val islands = loadIslands()
    private val islandWorldOwners = ConcurrentHashMap<String, UUID>().apply {
        islands.values.forEach { put(it.worldName, it.owner); put(IslandWorldIdentity.key(it.owner).asString(), it.owner) }
    }
    private val lastWorldAccess = ConcurrentHashMap<UUID, Long>()
    private val unloadingWorlds = mutableSetOf<UUID>()
    private val maintenance = ConcurrentHashMap.newKeySet<UUID>()
    private val activeTeleports = mutableMapOf<UUID, Int>() // requestLock

    @Volatile var profileSettings = com.alytanta.oneblock.player.ProfileSettings()
    @Volatile var releaseRules: com.alytanta.oneblock.release.ReleaseRules? = null
    var marketProgress: ((Island, MarketState, MarketState) -> com.alytanta.oneblock.release.ReleaseProgress?)? = null
    @Volatile var liveNaming: Map<UUID,com.alytanta.oneblock.player.ProfileNaming> = emptyMap()
    @Volatile var namingVersion = 0L; private set
    @Synchronized fun applyProfileNaming(version: Long, values: Map<UUID,com.alytanta.oneblock.player.ProfileNaming>) {
        if(version==namingVersion)liveNaming=values
    }
    fun nameNumber(island: Island): Int? = liveNaming[island.owner]?.let { return it.number } ?: island.nameNumber
    fun profileName(island: Island, language: String = "uk"): String {
        val naming = liveNaming[island.owner] ?: com.alytanta.oneblock.player.ProfileNaming(island.nameNumber,island.profileName)
        return naming.custom ?: profileSettings.name(naming.number,language) ?: "Island ${island.publicId.orEmpty()}"
    }
    @Synchronized fun ensurePublicIds() {
        val used = islands.values.mapNotNull { it.publicId }.toMutableSet()
        islands.values.sortedWith(compareBy<Island> { it.createdAt }.thenBy { it.owner.toString() }).forEach { island ->
            if (island.publicId == null) {
                val id = com.alytanta.oneblock.player.ProfileIdentity.nextId(used) ?: error("No free island IDs")
                used += id
                update(island.owner) { it.copy(publicId = id) }
            }
        }
    }
    fun applySettings(next: PluginConfig) { settings = next }

    fun refreshLoadedBorders() {
        all().forEach(::refreshBorders)
        plugin.server.onlinePlayers.forEach { player -> com.alytanta.oneblock.util.FoliaTasks.entity(plugin, player) {
            val island = findByWorld(player.world)
            if (island != null && !canVisit(island, player)) deniedVisit?.invoke(player)
        } }
    }

    fun underMaintenance(owner: UUID): Boolean = owner in maintenance || synchronized(this) { islands[owner]?.retiredAt != null }
    fun persistenceAvailable(): Boolean = storage.healthy
    var deniedVisit: ((Player) -> Unit)? = null
    fun visitorSettings(island: Island): VisitorSettings = island.visitorSettings ?: VisitorSettings(settings.visitorDefault)
    fun canManageVisitors(island: Island, player: UUID): Boolean = island.ownerPlayer == player ||
        settings.coopManageVisitors && island.members[player] == IslandRole.MEMBER
    fun canVisit(island: Island, player: UUID): Boolean {
        if (island.retiredAt != null) return false
        if (island.ownerPlayer == player || island.members[player] == IslandRole.MEMBER) return true
        val access = visitorSettings(island)
        return access.mode == VisitMode.ALL || access.mode == VisitMode.SELECTED && (player in access.allowed ||
            players?.find(player)?.lastKnownName?.let { name -> access.allowedNames.orEmpty().any { it.equals(name, true) } } == true)
    }
    fun canVisit(island: Island, player: Player): Boolean = canVisit(island, player.uniqueId) ||
        island.retiredAt == null && visitorSettings(island).let { access -> access.mode == VisitMode.SELECTED && access.allowedNames.orEmpty().any { it.equals(player.name, true) } } ||
        player.isOp && player.hasPermission("oneblock.admin")
    fun changeVisitors(account: UUID, actor: UUID, mode: VisitMode? = null, add: UUID? = null, remove: UUID? = null,
        addName: String? = null, removeName: String? = null): Island? {
        val result = update(account) { current ->
            require(canManageVisitors(current, actor)) { "Only the owner can change visitor settings" }
            val previous = visitorSettings(current)
            val allowed = previous.allowed.toMutableSet()
            val allowedNames = previous.allowedNames.orEmpty().toMutableSet()
            add?.let(allowed::add); remove?.let(allowed::remove)
            addName?.let { name -> require(name.matches(Regex("[A-Za-z0-9_]{1,16}"))); allowedNames.removeIf { it.equals(name, true) }; allowedNames.add(name) }
            removeName?.let { name -> allowedNames.removeIf { it.equals(name, true) } }
            require(allowed.size + allowedNames.size <= settings.visitorLimit ||
                allowed.size + allowedNames.size <= previous.allowed.size + previous.allowedNames.orEmpty().size) { "Visitor list limit reached" }
            current.copy(visitorSettings = VisitorSettings(mode ?: previous.mode, allowed, allowedNames))
        }
        if (result != null) plugin.server.onlinePlayers.forEach { player ->
            com.alytanta.oneblock.util.FoliaTasks.entity(plugin, player) {
                val current = findByOwner(account) ?: return@entity
                if (sameWorld(current, player.world) && !canVisit(current, player)) deniedVisit?.invoke(player)
            }
        }
        return result
    }

    override fun beginTeleport(owner: UUID): Boolean = synchronized(requestLock) {
        if (underMaintenance(owner)) false else {
            activeTeleports[owner] = (activeTeleports[owner] ?: 0) + 1
            true
        }
    }

    override fun endTeleport(owner: UUID) = synchronized(requestLock) {
        val remaining = (activeTeleports[owner] ?: 1) - 1
        if (remaining <= 0) activeTeleports.remove(owner) else activeTeleports[owner] = remaining
        Unit
    }

    @Synchronized
    fun create(owner: UUID): IslandCreateResult {
        if (profilesFor(owner).isNotEmpty()) {
            return IslandCreateResult.AlreadyExists
        }
        check(storage.healthy) { "Database unavailable" }
        val island = requireNotNull(createProfile(owner, ""))
        return IslandCreateResult.Created(island)
    }

    @Synchronized
    fun createProfile(player: UUID, name: String, replacing: UUID? = null): Island? {
        val previous = profilesFor(player)
        require(replacing == null || previous.any { it.owner == replacing })
        if (previous.count { it.owner != replacing } >= profileSettings.availableSlots || !storage.healthy) return null
        require(name.length <= 24 && name.none { it == '&' || it == '§' || it.isISOControl() })
        val usedIds = islands.values.mapNotNull { it.publicId }.toSet()
        val publicId = com.alytanta.oneblock.player.ProfileIdentity.nextId(usedIds) ?: return null
        val nameNumber = profileSettings.choose(islands.values.map(::nameNumber))
        val account = UUID.randomUUID() // Never reuse an orphaned world from a deleted database/profile.
        val island = Island(account, worldName(account), 0, settings.islandY, 0,
            CoreState(settings.startingMode, stability = settings.defaultStability), ownerPlayerId = player,
            publicId = publicId, nameNumber = nameNumber, profileName = name.trim().takeIf { it.isNotBlank() }, createdAt = System.currentTimeMillis(),
            releaseProgress = if(releaseRules?.enabled == true) com.alytanta.oneblock.release.ReleaseProgress() else null,
            worldCreationPending = true)
        players?.getOrCreate(player) // The relational owner must exist even if a flush runs during profile allocation.
        storage.saveIsland(island)
        islands[account] = island
        islandWorldOwners[island.worldName] = account
        islandWorldOwners[IslandWorldIdentity.key(account).asString()] = account
        players?.update(player) { it.copy(profileOrder = previous.map { old -> old.owner } + account) }
        return island
    }

    @Synchronized fun relatedAuctionAccounts(first: UUID, second: UUID): Boolean {
        fun people(id: UUID): Set<UUID> = islands[id]?.let { island ->
            island.members.filterValues { it == IslandRole.MEMBER }.keys + island.ownerPlayer
        }.orEmpty()
        return first == second || people(first).intersect(people(second)).isNotEmpty()
    }
    fun profilesFor(player: UUID): List<Island> = synchronized(this) {
        val order = players?.find(player)?.profileOrder.orEmpty()
        islands.values.filter { it.retiredAt == null && (it.ownerPlayer == player || it.members[player] == IslandRole.MEMBER) }
            .sortedWith(compareBy<Island> { order.indexOf(it.owner).takeIf { index -> index >= 0 } ?: Int.MAX_VALUE }
                .thenBy { it.createdAt }.thenBy { it.owner.toString() })
    }

    fun ensureWorld(island: Island, callback: (World?) -> Unit) {
        val shouldLookup = synchronized(requestLock) {
            if (underMaintenance(island.owner)) { callback(null); return }
            lastWorldAccess[island.owner] = System.nanoTime()
            val requests = worldRequests.getOrPut(island.owner) { mutableListOf() }
            requests.add(callback)
            requests.size == 1 && island.owner !in unloadingWorlds
        }
        if (shouldLookup) {
            plugin.server.globalRegionScheduler.execute(plugin) {
                try {
                    val world = loadedWorld(island)
                    if (world != null) {
                        configureWorld(island, world)
                    } else {
                        loadOrCreateWorld(island)
                    }
                } catch (exception: Exception) {
                    plugin.logger.log(JulLevel.SEVERE, "Could not prepare world '${island.worldName}'", exception)
                    completeWorldRequest(island.owner, null)
                }
            }
        }
    }

    fun initializeIsland(island: Island, callback: (Boolean) -> Unit) {
        val start = synchronized(requestLock) {
            val requests = platformRequests[island.owner]
            if (requests != null) {
                requests.add(callback)
                false
            } else {
                platformRequests[island.owner] = mutableListOf(callback)
                true
            }
        }
        if (!start) return
        ensureWorld(island) { world ->
            if (world == null) {
                completePlatformRequest(island.owner, false)
                return@ensureWorld
            }
            val current = findByOwner(island.owner)
            if (current == null) { completePlatformRequest(island.owner, false); return@ensureWorld }
            if (current.hasPlatformIn(world.uid)) { completePlatformRequest(island.owner, true); return@ensureWorld }
            // Old records without a world UUID are repaired once, filling only air. A future world
            // recreation is detected even if PostgreSQL still contains platformInitialized=true.
            initializePlatform(current, world) { initialized ->
                try {
                    if (initialized) update(island.owner) { it.copy(platformInitialized = true, platformWorldId = world.uid) }
                    completePlatformRequest(island.owner, initialized)
                } catch (e: Exception) {
                    plugin.logger.log(JulLevel.SEVERE, "Could not persist platform initialization for ${island.owner}", e)
                    completePlatformRequest(island.owner, false)
                }
            }
        }
    }

    fun findByOwner(owner: UUID): Island? = synchronized(this) { islands[owner] }

    @Synchronized
    fun claimStartingKit(owner: UUID): Boolean {
        val island = islands[owner] ?: return false
        if (island.startingKitClaimed) return false
        val updated = island.copy(startingKitClaimed = true)
        storage.saveIsland(updated)
        islands[owner] = updated
        return true
    }

    fun findByMember(member: UUID): Island? = synchronized(this) {
        islands.values.firstOrNull { it.retiredAt == null && it.members[member] == IslandRole.MEMBER }
    }

    fun findForPlayer(player: UUID): Island? {
        val profile = players?.find(player)
        if (profile?.inventoryDetached == true || profile?.eviction != null) return null
        val active = profile?.activeAccount
        // An explicitly selected account never silently becomes a different wallet after a kick.
        if (active != null) return findByOwner(active)?.takeIf { it.retiredAt == null && (it.ownerPlayer == player || it.members[player] == IslandRole.MEMBER) }
        return findByMember(player) ?: findByOwner(player)?.takeIf { it.retiredAt == null } ?: profilesFor(player).firstOrNull()
    }

    fun all(): List<Island> = synchronized(this) { islands.values.filter { it.retiredAt == null } }

    fun roleOf(island: Island, player: UUID): IslandRole? =
        if (island.retiredAt != null) null else if (island.ownerPlayer == player) IslandRole.OWNER else island.members[player]

    fun canBuild(island: Island, player: UUID): Boolean =
        findForPlayer(player)?.owner == island.owner &&
            (roleOf(island, player) == IslandRole.OWNER || roleOf(island, player) == IslandRole.MEMBER)

    fun isIslandWorld(worldName: String?): Boolean = worldName != null && islandWorldOwners.containsKey(worldName)

    fun canBuild(island: Island, player: Player): Boolean =
        canBuild(island, player.uniqueId) || player.isOp && player.hasPermission("oneblock.admin.bypass")

    fun worldSize(island: Island): Double {
        island.worldSize?.let { return it } // Explicit administrator override retains priority.
        val base = island.worldSize ?: settings.islandWorldSize
        val level = island.releaseProgress?.upgrades?.get("territory") ?: 0
        return if(level > 0) maxOf(base,releaseRules?.widths?.getOrNull(level)?.toDouble() ?: base) else base
    }

    fun bounds(island: Island) = IslandBounds(island.x.toDouble(), island.z.toDouble(), worldSize(island))

    fun isInsideIsland(island: Island, location: Location): Boolean =
        location.world?.let { sameWorld(island, it) } == true && bounds(island).contains(location.x, location.z)

    override fun findByWorld(worldName: String): Island? = islandWorldOwners[worldName]?.let(::findByOwner)

    fun refreshBorders(island: Island) {
        plugin.server.globalRegionScheduler.execute(plugin) {
            loadedWorld(island)?.let { configureWorld(island, it) }
            plugin.server.onlinePlayers.forEach { player ->
                com.alytanta.oneblock.util.FoliaTasks.entity(plugin, player) {
                    if (sameWorld(island, player.world)) {
                        val latest = findByOwner(island.owner) ?: island
                        applyPlayerBorder(player, latest)
                        if (!bounds(latest).contains(player.x, player.z, 0.3)) player.teleportAsync(homeLocation(latest, player.world))
                    }
                }
            }
        }
    }

    fun backupWorld(world: World, callback: (Boolean) -> Unit) {
        try {
            savedWorldBackup(world, "oneblock-${System.currentTimeMillis()}").whenComplete { _, error ->
                if (error != null) plugin.logger.log(JulLevel.SEVERE, "Island world backup failed", error)
                callback(error == null)
            }
        } catch (exception: Exception) {
            plugin.logger.log(JulLevel.SEVERE, "Island world backup failed", exception)
            callback(false)
        }
    }

    /** Destructive operation: caller must obtain confirmation and back up data first. Occupied worlds are refused. */
    fun resetEmptyWorld(owner: UUID, callback: (Boolean) -> Unit) {
        val island = findByOwner(owner) ?: run { callback(false); return }
        ensureWorld(island) { world ->
            if (world == null) { callback(false); return@ensureWorld }
            plugin.server.globalRegionScheduler.execute(plugin) {
                val acquired = synchronized(requestLock) {
                    if (owner in worldRequests || owner in platformRequests || owner in unloadingWorlds || owner in activeTeleports) false
                    else maintenance.add(owner)
                }
                if (!acquired) { callback(false); return@execute }
                fun complete(success: Boolean) {
                    try { callback(success) } finally { maintenance.remove(owner) }
                }
                if (world.players.isNotEmpty()) { complete(false); return@execute }
                try {
                    val access = WorldsAccess.access()
                    savedWorldBackup(world, "before-reset-${System.currentTimeMillis()}")
                        .thenCompose {
                            globalOperation {
                                check(world.players.isEmpty()) { "Island became occupied" }
                                access.regenerate(world) { builder ->
                                    builder.generatorType(GeneratorType.FLAT.with(IslandGeneration.voidPreset())).structures(false).bonusChest(false)
                                }
                            }
                        }.whenComplete { _, error ->
                            if (error != null) plugin.logger.log(JulLevel.SEVERE, "Island regeneration failed; retain backups", error)
                            complete(error == null)
                        }
                } catch (exception: Exception) {
                    plugin.logger.log(JulLevel.SEVERE, "Island regeneration failed", exception)
                    complete(false)
                }
            }
        }
    }

    private fun <T> globalOperation(action: () -> CompletableFuture<T>): CompletableFuture<T> {
        val result = CompletableFuture<T>()
        try { plugin.server.globalRegionScheduler.execute(plugin) {
            try { action().whenComplete { value, error ->
                if (error != null) result.completeExceptionally(error) else result.complete(value)
            } } catch (exception: Exception) { result.completeExceptionally(exception) }
        } } catch(exception:Exception) { result.completeExceptionally(exception) }
        return result
    }

    /** No regeneration: back up, recognize and remove only an intact lower template in an empty world. */
    fun cleanLegacyPlatform(owner: UUID, callback: (Boolean) -> Unit) {
        val island = findByOwner(owner) ?: run { callback(false); return }
        ensureWorld(island) { world ->
            if (world == null) { callback(false); return@ensureWorld }
            plugin.server.globalRegionScheduler.execute(plugin) {
                val acquired = synchronized(requestLock) {
                    if (owner in worldRequests || owner in platformRequests || owner in unloadingWorlds || owner in activeTeleports) false
                    else maintenance.add(owner)
                }
                if (!acquired) { callback(false); return@execute }
                fun finish(success: Boolean) {
                    try { callback(success) } finally { maintenance.remove(owner) }
                }
                if (world.players.isNotEmpty()) { finish(false); return@execute }
                try {
                    savedWorldBackup(world, "before-platform-cleanup-${System.currentTimeMillis()}").whenComplete { _, error ->
                        if (error != null) {
                            plugin.logger.log(JulLevel.WARNING, "Platform cleanup backup failed", error)
                            finish(false)
                        } else plugin.server.globalRegionScheduler.execute(plugin) {
                            if (world.players.isNotEmpty()) finish(false)
                            else LegacyPlatformCleaner(plugin).clean(world) { cleaned ->
                                if (!cleaned) finish(false) else globalOperation { WorldsAccess.access().save(world, true) }
                                    .whenComplete { saved, saveError ->
                                        if (saveError != null) plugin.logger.log(JulLevel.WARNING, "Could not flush platform cleanup", saveError)
                                        finish(saveError == null && saved == true)
                                    }
                            }
                        }
                    }
                } catch (e: Exception) { plugin.logger.log(JulLevel.WARNING, "Platform cleanup failed", e); finish(false) }
            }
        }
    }

    private fun savedWorldBackup(world: World, name: String): CompletableFuture<Backup> =
        globalOperation { WorldsAccess.access().save(world, true) }.thenCompose { saved ->
            check(saved == true) { "World flush failed; refusing backup/reset" }
            globalOperation { WorldsAccess.access().createBackup(world, name) }
        }

    /** Must be called on the player's entity thread. */
    override fun applyPlayerBorder(player: Player, island: Island?) {
        if (island == null || !settings.visibleBorder) {
            player.setWorldBorder(null)
        } else {
            val border = plugin.server.createWorldBorder()
            border.setCenter(island.x.toDouble(), island.z.toDouble())
            border.size = worldSize(island)
            border.warningDistance = 0
            border.warningTimeTicks = 0
            border.damageAmount = 0.0
            player.setWorldBorder(border)
        }
    }

    fun findAt(location: Location): Island? {
        val worldName = location.world?.key()?.asString() ?: return null
        val owner = islandWorldOwners[worldName] ?: return null
        return findByOwner(owner)?.takeIf { isInsideIsland(it, location) }
    }

    fun findCoreAt(location: Location): Island? {
        val worldName = location.world?.key()?.asString() ?: return null
        val owner = islandWorldOwners[worldName] ?: return null
        return findByOwner(owner)?.takeIf {
            it.x == location.blockX && it.y == location.blockY && it.z == location.blockZ
        }
    }

    @Synchronized
    fun updateCoreMode(owner: UUID, mode: CoreMode): Island? {
        val island = islands[owner] ?: return null
        val updated = island.copy(core = island.core.copy(mode = mode, currentDrop = null))
        storage.saveIsland(updated)
        islands[owner] = updated
        return updated
    }

    @Synchronized
    override fun update(owner: UUID, change: (Island) -> Island): Island? {
        check(storage.healthy) { "Database is unavailable" }
        val current = islands[owner] ?: return null
        val updated = change(current)
        require(updated.owner == current.owner && updated.worldName == current.worldName && updated.ownerPlayer == current.ownerPlayer) { "Island identity is immutable" }
        storage.saveIsland(updated)
        islands[owner] = updated
        if(updated.nameNumber != current.nameNumber || updated.profileName != current.profileName) {
            namingVersion++
            liveNaming = liveNaming + (owner to com.alytanta.oneblock.player.ProfileNaming(updated.nameNumber,updated.profileName))
        }
        return updated
    }

    /** Memory-only critical section. Ledger + wallet are captured together by the async DB writer. */
    @Synchronized
    fun <T> marketUpdate(owner: UUID, change: (Island, MarketState) -> MarketChange<T>): T {
        check(!underMaintenance(owner)) { "Island is under maintenance" }
        val island = requireNotNull(islands[owner]) { "Island not found" }
        return (storage as PostgresStorageService).changeMarket(island) { state ->
            val result = change(island,state)
            result.copy(releaseProgress = marketProgress?.invoke(island,state,result.state) ?: island.releaseProgress).also {
                require(it.coins >= 0); islands[owner] = island.copy(coins=it.coins,releaseProgress=it.releaseProgress)
            }
        }
    }

    /** Memory-only account + claims + journal change, committed by the serialized async writer. */
    @Synchronized fun releaseUpdate(owner: UUID, operation: (Island, MarketState) -> com.alytanta.oneblock.release.ReleaseChange): com.alytanta.oneblock.release.ReleaseChange {
        check(!underMaintenance(owner))
        val current = requireNotNull(islands[owner])
        val result = (storage as PostgresStorageService).releaseChange(current) { state -> operation(current,state) }
        islands[owner] = result.island
        return result
    }

    @Synchronized
    fun <T> playerTradeUpdate(accounts: Set<UUID>, change: (Map<UUID, Island>, MarketState) -> com.alytanta.oneblock.trade.TradeChange<T>): T {
        require(accounts.none(::underMaintenance))
        val current = accounts.associateWith { requireNotNull(islands[it]) }
        return (storage as PostgresStorageService).changeMany(current) { state ->
            change(current, state).also { result ->
                require(result.coins.keys == accounts && result.coins.values.all { it >= 0 })
                result.coins.forEach { (account, coins) -> islands[account] = current.getValue(account).copy(coins = coins) }
            }
        }
    }

    @Synchronized
    fun setMemberRole(owner: UUID, member: UUID, role: IslandRole?, administrative: Boolean = false): Island? {
        val island = islands[owner] ?: return null
        check(storage.healthy) { "Database is unavailable" }
        if (member == island.ownerPlayer || role == IslandRole.OWNER) return island
        if (!administrative && role == IslandRole.MEMBER && island.members[member] != IslandRole.MEMBER && profilesFor(member).size >= profileSettings.availableSlots) return null
        if (role == IslandRole.MEMBER && players?.find(member)?.activeAccount == null) {
            val previous = findForPlayer(member)
            if (previous != null) players?.update(member) { it.copy(activeAccount = previous.owner) }
        }
        if (role == IslandRole.MEMBER && island.members[member] != IslandRole.MEMBER) {
            val order = profilesFor(member).map { it.owner } + owner
            players?.update(member) { it.copy(profileOrder = order, profileActivity = it.profileActivity.orEmpty() + (owner to System.currentTimeMillis())) }
        }
        // Leave/kick the active profile only after switching inventory to another account.
        if (role != IslandRole.MEMBER && island.members[member] == IslandRole.MEMBER && findForPlayer(member)?.owner == owner) return null
        if (role != IslandRole.MEMBER && players?.find(member)?.profileSwitch?.let { it.from == owner || it.to == owner } == true) return null
        val members = island.members.toMutableMap()
        if (role == null) members.remove(member) else members[member] = role
        val updated = island.copy(members = members)
        storage.saveIsland(updated)
        islands[owner] = updated
        return updated
    }

    @Synchronized fun removeProfileMember(account: UUID, who: UUID, requester: UUID,
        decode: (String) -> org.bukkit.inventory.ItemStack = com.alytanta.oneblock.market.AuctionItems::decode): com.alytanta.oneblock.player.ProfileRemoval {
        check(!underMaintenance(account))
        val service = requireNotNull(players)
        val plan = service.removeMembership(who) { player ->
            (storage as PostgresStorageService).removeProfileMember(requireNotNull(islands[account]), player, requester, System.currentTimeMillis(), decode)
        }
        islands[account] = plan.island
        return plan
    }

    @Synchronized fun removeProfileMembers(account: UUID, who: Set<UUID>, requester: UUID, administrative: Boolean,
        whole: Boolean, captures: Map<UUID, com.alytanta.oneblock.player.RemovalCapture>): List<com.alytanta.oneblock.player.ProfileRemoval> {
        check(!underMaintenance(account))
        val plans = requireNotNull(players).removeMemberships(who) { profiles ->
            (storage as PostgresStorageService).removeProfileMembers(requireNotNull(islands[account]), profiles, requester,
                System.currentTimeMillis(), administrative, whole, captures)
        }
        islands[account] = plans.last().island
        return plans
    }

    @Synchronized fun retired(): List<Island> = islands.values.filter { it.retiredAt != null }
    fun worldOperationsPending(owner: UUID): Boolean = synchronized(requestLock) {
        owner in worldRequests || owner in platformRequests || owner in unloadingWorlds || owner in activeTeleports || owner in maintenance
    }

    @Synchronized fun captureEviction(who: UUID, snapshot: com.alytanta.oneblock.player.ProfileInventory) {
        requireNotNull(players).update(who) { player ->
            (storage as PostgresStorageService).captureEviction(player, snapshot, com.alytanta.oneblock.market.AuctionItems::decode)
        }
    }

    @Synchronized
    fun removeMember(member: UUID): Island? {
        val island = findForPlayer(member)?.takeIf { it.members[member] == IslandRole.MEMBER } ?: return null
        if (players != null) return null // live profile departures use the journaled switch first
        val updated = island.copy(members = island.members - member)
        storage.saveIsland(updated)
        islands[island.owner] = updated
        return updated
    }

    fun coreLocation(island: Island): Location? {
        val world = loadedWorld(island) ?: return null
        return Location(world, island.x.toDouble(), island.y.toDouble(), island.z.toDouble())
    }

    fun homeLocation(island: Island, world: World): Location =
        Location(world, island.x + 0.5, island.y + 1.0, island.z + 0.5)

    private fun initializePlatform(island: Island, world: World, callback: (Boolean) -> Unit) {
        val radius = (settings.islandSize + (island.upgrades["island_size"] ?: 0) * settings.platformUpgradeStep)
            .coerceAtMost(maxPlatformRadius(island))
        val chunkMinX = Math.floorDiv(island.x - radius, CHUNK_SIZE)
        val chunkMaxX = Math.floorDiv(island.x + radius, CHUNK_SIZE)
        val chunkMinZ = Math.floorDiv(island.z - radius, CHUNK_SIZE)
        val chunkMaxZ = Math.floorDiv(island.z + radius, CHUNK_SIZE)
        val chunks = (chunkMinX..chunkMaxX).flatMap { chunkX ->
            (chunkMinZ..chunkMaxZ).map { chunkZ -> chunkX to chunkZ }
        }
        val pending = AtomicInteger(chunks.size)
        val failed = AtomicBoolean(false)

        chunks.forEach { (chunkX, chunkZ) ->
            val location = Location(
                world,
                chunkX * CHUNK_SIZE + CHUNK_SIZE / 2.0,
                island.y.toDouble(),
                chunkZ * CHUNK_SIZE + CHUNK_SIZE / 2.0,
            )
            plugin.server.regionScheduler.execute(plugin, location) {
                try {
                    val minX = maxOf(island.x - radius, chunkX * CHUNK_SIZE)
                    val maxX = minOf(island.x + radius, chunkX * CHUNK_SIZE + CHUNK_SIZE - 1)
                    val minZ = maxOf(island.z - radius, chunkZ * CHUNK_SIZE)
                    val maxZ = minOf(island.z + radius, chunkZ * CHUNK_SIZE + CHUNK_SIZE - 1)
                    for (x in minX..maxX) {
                        for (z in minZ..maxZ) {
                            val floor = world.getBlockAt(x, island.y - 1, z)
                            if (floor.isEmpty) floor.setType(Material.DIRT, false)
                        }
                    }
                    if (island.x in minX..maxX && island.z in minZ..maxZ) {
                        val core = world.getBlockAt(island.x, island.y, island.z)
                        if (core.isEmpty) core.setType((island.core.currentDrop?.block ?: island.core.mode.initialMaterial), false)
                    }
                } catch (exception: Exception) {
                    failed.set(true)
                    plugin.logger.log(
                        JulLevel.SEVERE,
                        "Could not initialize chunk ($chunkX, $chunkZ) for island '${island.worldName}'",
                        exception,
                    )
                }
                if (pending.decrementAndGet() == 0) callback(!failed.get())
            }
        }
    }

    fun restoreCore(island: Island) {
        val world = loadedWorld(island)
            ?: error("Island world '${island.worldName}' is not loaded")
        world.getBlockAt(island.x, island.y, island.z).setType((island.core.currentDrop?.block ?: island.core.mode.initialMaterial))
    }

    /** Shared safe landing for create, home, visits and respawn. Never teleports above an unchecked void. */
    override fun prepareRespawn(island: Island, callback: (Location?) -> Unit) {
        ensureWorld(island) { world ->
            if (world == null) { callback(null); return@ensureWorld }
            initializeIsland(island) { initialized ->
                if (!initialized) { callback(null); return@initializeIsland }
                val preferred = Location(world, island.x + 1.5, island.y.toDouble(), island.z + 0.5)
                plugin.server.regionScheduler.execute(plugin, preferred) {
                    try {
                        if (underMaintenance(island.owner)) { callback(null); return@execute }
                        val side = IslandLanding.find(world, island.x + 1, island.z, island.y - 1, island.y - 1)
                        if (side != null) { callback(side); return@execute }
                        plugin.server.regionScheduler.execute(plugin, homeLocation(island, world)) coreLanding@{
                            try {
                                if (underMaintenance(island.owner)) { callback(null); return@coreLanding }
                                val base = world.getBlockAt(island.x, island.y, island.z)
                                if (!IslandLanding.safeFloor(base)) base.setType((island.core.currentDrop?.block ?: island.core.mode.initialMaterial), false)
                                callback(IslandLanding.find(world, island.x, island.z, island.y, world.maxHeight - 3))
                            } catch (e: Exception) {
                                plugin.logger.log(JulLevel.WARNING, "Could not prepare core landing ${island.owner}", e)
                                callback(null)
                            }
                        }
                    } catch (e: Exception) {
                        plugin.logger.log(JulLevel.WARNING, "Could not prepare island respawn ${island.owner}", e)
                        callback(null)
                    }
                }
            }
        }
    }

    fun expandPlatform(island: Island, newLevel: Int) {
        val world = loadedWorld(island)
            ?: error("Island world '${island.worldName}' is not loaded")
        val oldRadius = (settings.islandSize + (newLevel - 1).coerceAtLeast(0) * settings.platformUpgradeStep)
            .coerceAtMost(maxPlatformRadius(island))
        val newRadius = (settings.islandSize + newLevel.coerceAtLeast(0) * settings.platformUpgradeStep)
            .coerceAtMost(maxPlatformRadius(island))
        val chunkMinX = Math.floorDiv(island.x - newRadius, CHUNK_SIZE)
        val chunkMaxX = Math.floorDiv(island.x + newRadius, CHUNK_SIZE)
        val chunkMinZ = Math.floorDiv(island.z - newRadius, CHUNK_SIZE)
        val chunkMaxZ = Math.floorDiv(island.z + newRadius, CHUNK_SIZE)
        for (chunkX in chunkMinX..chunkMaxX) {
            for (chunkZ in chunkMinZ..chunkMaxZ) {
                val location = Location(
                    world,
                    chunkX * CHUNK_SIZE + CHUNK_SIZE / 2.0,
                    island.y.toDouble(),
                    chunkZ * CHUNK_SIZE + CHUNK_SIZE / 2.0,
                )
                plugin.server.regionScheduler.execute(plugin, location) {
                    val minX = maxOf(island.x - newRadius, chunkX * CHUNK_SIZE)
                    val maxX = minOf(island.x + newRadius, chunkX * CHUNK_SIZE + CHUNK_SIZE - 1)
                    val minZ = maxOf(island.z - newRadius, chunkZ * CHUNK_SIZE)
                    val maxZ = minOf(island.z + newRadius, chunkZ * CHUNK_SIZE + CHUNK_SIZE - 1)
                    for (x in minX..maxX) {
                        for (z in minZ..maxZ) {
                            if (kotlin.math.max(kotlin.math.abs(x - island.x), kotlin.math.abs(z - island.z)) <= oldRadius) {
                                continue
                            }
                            val floor = world.getBlockAt(x, island.y - 1, z)
                            if (floor.type.isAir) floor.type = Material.DIRT
                        }
                    }
                }
            }
        }
    }

    fun unloadIdleWorlds() {
        val now = System.nanoTime()
        val idleNanos = java.util.concurrent.TimeUnit.MINUTES.toNanos(settings.idleWorldUnloadMinutes)
        plugin.server.worlds.forEach { world ->
            val owner = islandWorldOwners[world.key().asString()] ?: return@forEach
            lastWorldAccess.putIfAbsent(owner, now)
        }
        for ((owner, lastAccess) in lastWorldAccess.entries) {
            val island = findByOwner(owner) ?: continue
            if (island.retiredAt != null) continue
            if (now - lastAccess < idleNanos) continue
            val shouldUnload = synchronized(requestLock) {
                if (island.owner in unloadingWorlds || island.owner in worldRequests || island.owner in maintenance || island.owner in platformRequests || island.owner in activeTeleports) {
                    false
                } else {
                    unloadingWorlds.add(island.owner)
                }
            }
            if (!shouldUnload) continue
            val world = loadedWorld(island)
            if (world == null) {
                synchronized(requestLock) { unloadingWorlds.remove(island.owner) }
                lastWorldAccess.remove(island.owner)
                continue
            }
            if (world.players.isNotEmpty()) {
                synchronized(requestLock) { unloadingWorlds.remove(island.owner) }
                lastWorldAccess[island.owner] = now
                continue
            }
            try {
                WorldsAccess.access().unload(world, true).whenComplete { unloaded, error ->
                    if (error != null) {
                        plugin.logger.log(JulLevel.WARNING, "Could not unload idle island world '${island.worldName}'", error)
                    }
                    lastWorldAccess[island.owner] = System.nanoTime()
                    val retryRequest = synchronized(requestLock) {
                        unloadingWorlds.remove(island.owner)
                        island.owner in worldRequests
                    }
                    if (retryRequest) {
                        plugin.server.globalRegionScheduler.execute(plugin) {
                            try {
                                val stillLoaded = loadedWorld(island)
                                if (stillLoaded != null) configureWorld(island, stillLoaded)
                                else loadOrCreateWorld(island)
                            } catch(e:Exception) {
                                plugin.logger.log(JulLevel.WARNING,"Could not reload island after unload",e)
                                completeWorldRequest(island.owner,null)
                            }
                        }
                    } else if (error == null && unloaded == true) {
                        plugin.logger.fine("Unloaded idle island world '${island.worldName}'")
                    }
                }
            } catch (exception: Exception) {
                synchronized(requestLock) { unloadingWorlds.remove(island.owner) }
                lastWorldAccess[island.owner] = System.nanoTime()
                plugin.logger.log(JulLevel.WARNING, "Could not unload idle island world '${island.worldName}'", exception)
                completeWorldRequest(island.owner,null)
            }
        }
    }

    private fun completeWorldRequest(owner: UUID, world: World?) {
        val callbacks = synchronized(requestLock) {
            worldRequests.remove(owner).orEmpty()
        }
        callbacks.forEach { callback ->
            try {
                callback(world)
            } catch (exception: Exception) {
                plugin.logger.log(JulLevel.SEVERE, "Island world callback failed", exception)
            }
        }
    }

    private fun configureWorld(island: Island, world: World) {
        if (findByOwner(island.owner)?.retiredAt != null) { completeWorldRequest(island.owner, null); return }
        if (!sameWorld(island, world)) {
            plugin.logger.severe("World key mismatch for island ${island.owner}: ${world.key()}; refusing platform/teleport")
            completeWorldRequest(island.owner, null)
            return
        }
        val spawn = Location(world, island.x.toDouble(), island.y.toDouble(), island.z.toDouble())
        plugin.server.regionScheduler.execute(plugin, spawn) {
            try {
                world.worldBorder.setCenter(island.x.toDouble(), island.z.toDouble())
                val latest = findByOwner(island.owner) ?: island
                world.worldBorder.size = if (settings.visibleBorder) worldSize(latest) else 59_999_968.0
                world.worldBorder.warningDistance = 0
                world.worldBorder.warningTimeTicks = 0
                world.worldBorder.damageAmount = 0.0
                completeWorldRequest(island.owner, world)
            } catch (exception: Exception) {
                plugin.logger.log(JulLevel.SEVERE, "Could not configure island world '${island.worldName}'", exception)
                completeWorldRequest(island.owner, null)
            }
        }
    }

    private fun completePlatformRequest(owner: UUID, initialized: Boolean) {
        val callbacks = synchronized(requestLock) { platformRequests.remove(owner).orEmpty() }
        callbacks.forEach { callback ->
            try {
                callback(initialized)
            } catch (exception: Exception) {
                plugin.logger.log(JulLevel.SEVERE, "Island platform callback failed for $owner", exception)
            }
        }
    }

    private fun loadIslands(): LinkedHashMap<UUID, Island> {
        val result = LinkedHashMap<UUID, Island>()
        val saved = storage.loadIslands()
        saved.forEach { island ->
            check(island.worldName == worldName(island.owner)) {
                "Legacy island world '${island.worldName}' requires an explicit backed-up migration; refusing to reset its identity or platform."
            }
            result[island.owner] = island
        }
        return result
    }

    private fun loadOrCreateWorld(island: Island) {
        val access = worlds()
        val key = IslandWorldIdentity.key(island.owner)
        val level by lazy { WorldsLevel.builder(key)
            .generatorType(GeneratorType.FLAT.with(IslandGeneration.voidPreset()))
            .structures(false)
            .bonusChest(false)
            .forcedSpawnPosition(io.papermc.paper.math.Position.block(island.x, island.y, island.z), null)
            .build() }
        // 26.x dimensions do not necessarily have their own level.dat; retain their saved chunks/settings.
        val directory = access.resolveLevelDirectory(key)
        val generation=storage.generation
        val worldFuture = CompletableFuture.supplyAsync {
            Files.isDirectory(directory) && Files.list(directory).use { it.findAny().isPresent }
        }.thenCompose { directoryExists ->
            globalOperation {
                check(storage.healthy && storage.generation==generation && !underMaintenance(island.owner))
                val current=requireNotNull(findByOwner(island.owner))
                check(directoryExists || current.worldCreationPending) {
                    "Saved island world is missing: $directory. Restore its backup; refusing to create an empty replacement."
                }
                val permitted = if(current.worldCreationPending) {
                    update(current.owner) { it.copy(worldCreationPending=false) }
                    storage.flushAsync()
                } else CompletableFuture.completedFuture(Unit)
                permitted.thenCompose { globalOperation {
                    check(storage.healthy && storage.generation==generation && !underMaintenance(island.owner))
                    if(directoryExists) {
                        if(!access.worldRegistry.isRegistered(key))access.worldRegistry.register(level,true)
                        access.load(key)
                    } else access.create(level.toBuilder().ignoreLevelData(true).build()).thenCompose { world -> globalOperation {
                        if(!access.worldRegistry.isRegistered(key))access.worldRegistry.register(level,true)
                        CompletableFuture.completedFuture(world)
                    }
                    }
                } }
            }
        }
        worldFuture.whenComplete { world, error ->
            if (error != null) {
                plugin.logger.log(JulLevel.SEVERE, "Could not load or create island world '${island.worldName}'", error)
                completeWorldRequest(island.owner, null)
            } else {
                try { plugin.server.globalRegionScheduler.execute(plugin) { configureWorld(island, world) } }
                catch(e:Exception) { completeWorldRequest(island.owner,null) }
            }
        }
    }

    private fun worldName(owner: UUID): String = IslandWorldIdentity.worldName(owner)

    fun loadedWorld(island: Island): World? = plugin.server.getWorld(IslandWorldIdentity.key(island.owner))
    fun sameWorld(island: Island, world: World): Boolean = IslandWorldIdentity.matches(world, island.owner)
    fun isIslandWorld(world: World): Boolean = findByWorld(world.key().asString()) != null

    private fun maxPlatformRadius(island: Island): Int =
        (worldSize(island) / 2).toInt() - PLATFORM_EDGE_MARGIN

    companion object {
        private const val CHUNK_SIZE = 16
        private const val PLATFORM_EDGE_MARGIN = 2
    }
}
