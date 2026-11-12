package com.alytanta.oneblock.admin

import com.alytanta.oneblock.config.PluginConfig
import com.alytanta.oneblock.island.*
import com.alytanta.oneblock.locale.Lang
import com.alytanta.oneblock.locale.LocaleService
import com.alytanta.oneblock.storage.StorageService
import com.alytanta.oneblock.util.FoliaTasks
import com.alytanta.oneblock.hub.HubService
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import org.bukkit.plugin.java.JavaPlugin
import java.util.UUID

class AdminController(
    private val plugin: JavaPlugin,
    @Volatile private var settings: PluginConfig,
    private val islands: IslandService,
    private val storage: StorageService,
    private val locales: LocaleService,
    private val hub: HubService,
    private val names: com.alytanta.oneblock.player.PlayerNames? = null,
    private val teleport: (Player, Island) -> Unit,
) {
    val menu = AdminMenu(plugin, islands, locales, this)
    private val confirmations = AdminConfirmations()
    var removeProfile: ((CommandSender, UUID, UUID?) -> Unit)? = null

    fun applySettings(next: PluginConfig) { settings = next }

    private fun actor(sender: CommandSender) = (sender as? Player)?.uniqueId?.toString() ?: "console:${sender.name}"

    fun name(owner: UUID): String {
        val island = islands.findByOwner(owner)
        val player = island?.ownerPlayer ?: owner
        val name = names?.known(player) ?: plugin.server.getOfflinePlayer(player).name ?: locales.format("uk", Lang.UI2_UNKNOWN_PLAYER)
        return island?.let { "${islands.profileName(it)} / " +
            (listOf(it.ownerPlayer) + it.members.filterValues { role -> role == IslandRole.MEMBER }.keys).joinToString(", ") { id -> names?.known(id) ?: plugin.server.getOfflinePlayer(id).name ?: "?" } + " / ${it.publicId.orEmpty()}" } ?: name
    }

    private fun resolve(text: String): UUID? = runCatching { UUID.fromString(text) }.getOrNull()
        ?: names?.resolve(text) ?: plugin.server.getPlayerExact(text)?.uniqueId ?: plugin.server.getOfflinePlayerIfCached(text)?.uniqueId

    private fun resolveAccount(text: String): UUID? {
        if (text.matches(Regex("[0-9]{4}"))) return islands.all().firstOrNull { it.publicId == text }?.owner
        if ('#' in text) {
            val player = resolve(text.substringBefore('#')) ?: return null
            val slot = text.substringAfter('#').toIntOrNull()?.takeIf { it in 1..18 } ?: return null
            return islands.profilesFor(player).getOrNull(slot - 1)?.owner
        }
        val player = resolve(text) ?: return null
        if (runCatching { UUID.fromString(text) }.isSuccess && islands.findByOwner(player) != null) return player
        return islands.findForPlayer(player)?.owner ?: player
    }
    fun reference(account: UUID): String {
        val island = islands.findByOwner(account) ?: return name(account)
        island.publicId?.let { return it }
        val nick = names?.known(island.ownerPlayer) ?: plugin.server.getOfflinePlayer(island.ownerPlayer).name
            ?: return locales.format("uk", Lang.UI2_UNKNOWN_PLAYER)
        val index = islands.profilesFor(island.ownerPlayer).indexOfFirst { it.owner == account }
        return if (index >= 0) "$nick#${index + 1}" else nick
    }

    fun handle(sender: CommandSender, args: List<String>) {
        if ((!sender.isOp || !sender.hasPermission("oneblock.admin"))) { locales.send(sender, Lang.NO_PERMISSION); return }
        val action = args.firstOrNull()?.lowercase()
        if (action == null) {
            if (sender is Player) menu.openList(sender) else locales.send(sender, Lang.UI2_ADMIN_USAGE)
            return
        }
        val permission = when (action) {
            "info", "list", "menu" -> "inspect"
            "tp", "send", "evacuate" -> "teleport"
            "save", "backup" -> "save"
            "reset-progress", "reset-world", "cleanup-platform", "confirm" -> "reset"
            else -> "manage"
        }
        if (!sender.hasPermission("oneblock.admin.$permission")) { locales.send(sender, Lang.NO_PERMISSION); return }
        try {
            if (action == "sethub") {
                if (sender is Player) hub.setHere(sender) else locales.send(sender, Lang.ONLY_PLAYER)
                return
            }
            if (action == "list") {
                val page = (args.getOrNull(1)?.toIntOrNull() ?: 1).coerceAtLeast(1)
                val all = islands.all().sortedBy { it.publicId }
                val start = ((page.toLong() - 1) * 10).coerceAtMost(all.size.toLong()).toInt()
                val entries = all.drop(start).take(10).joinToString(", ") { name(it.owner) }
                locales.send(sender, Lang.ADMIN_LIST, "page" to page, "total" to all.size, "entries" to entries)
                return
            }
            if (action == "save" || (action == "backup" && args.size == 1)) {
                FoliaTasks.async(plugin) {
                    try {
                        val result = if (action == "backup") storage.backup().name else {
                            check(storage.flush()) { "Save failed" }; "PostgreSQL"
                        }
                        reply(sender, Lang.ADMIN_SAVED, "file" to result)
                        audit(sender, "$action data $result")
                    } catch (e: Exception) { failure(sender, e) }
                }
                return
            }
            if (action == "confirm") { require(args.size == 1); confirm(sender); return }
            if (action == "send") {
                require(args.size == 3)
                val target = requireNotNull(plugin.server.getPlayerExact(args[1]))
                val destination = requireNotNull(resolveAccount(args[2])?.let(islands::findByOwner))
                FoliaTasks.entity(plugin, target) { teleport(target, destination) }
                audit(sender, "send ${target.uniqueId} ${destination.owner}")
                locales.send(sender, Lang.ADMIN_UPDATED, "player" to args[1], "field" to "destination", "value" to args[2])
                return
            }
            val owner = args.getOrNull(1)?.let { if (action == "create") resolve(it) else resolveAccount(it) } ?: run {
                locales.send(sender, Lang.ADMIN_PLAYER_NOT_FOUND, "player" to args.getOrNull(1).orEmpty()); return
            }
            if (action == "create") {
                val created = islands.create(owner)
                audit(sender, "create $owner")
                locales.send(sender, Lang.ADMIN_UPDATED, "player" to name(owner), "field" to "island", "value" to created.javaClass.simpleName)
                return
            }
            val island = islands.findByOwner(owner) ?: run {
                locales.send(sender, Lang.ADMIN_ISLAND_NOT_FOUND, "player" to name(owner)); return
            }
            check(!islands.underMaintenance(owner) || action in setOf("info", "menu")) { "Island is undergoing maintenance" }
            when (action) {
                "delete" -> {
                    require(args.size == 2)
                    requireNotNull(removeProfile)(sender, owner, null)
                }
                "kick" -> {
                    require(args.size == 3)
                    requireNotNull(removeProfile)(sender, owner, requireNotNull(resolve(args[2])))
                }
                "info" -> locales.send(sender, Lang.UI2_ADMIN_INFO, "profile" to reference(owner),
                    "player" to name(owner), "uuid" to owner, "world" to island.worldName,
                    "breaks" to island.core.breaks, "mode" to island.core.mode.id,
                    "coins" to island.coins, "size" to islands.worldSize(island),
                    "members" to island.members.entries.joinToString { "${name(it.key)}:${it.value}" })
                "menu" -> if (sender is Player) menu.openIsland(sender, owner) else locales.send(sender, Lang.ONLY_PLAYER)
                "tp" -> if (sender is Player) teleport(sender, island) else locales.send(sender, Lang.ONLY_PLAYER)
                "evacuate" -> hub.resolve { spawn ->
                    if (spawn == null) reply(sender, Lang.HUB_UNAVAILABLE) else {
                        plugin.server.onlinePlayers.forEach { player -> FoliaTasks.entity(plugin, player) {
                            if (islands.sameWorld(island, player.world)) player.teleportAsync(spawn)
                        } }
                        audit(sender, "evacuate $owner")
                        reply(sender, Lang.ADMIN_EVACUATED)
                    }
                }
                "respawn" -> islands.ensureWorld(island) { world ->
                    if (world == null) reply(sender, Lang.ISLAND_WORLD_FAILED) else {
                        val location = islands.coreLocation(island)
                        if (location != null) FoliaTasks.region(plugin, location) {
                            islands.restoreCore(islands.findByOwner(owner) ?: island)
                            reply(sender, Lang.ADMIN_CORE_RESPAWNED, "player" to name(owner))
                            audit(sender, "respawn $owner")
                        }
                    }
                }
                "set" -> {
                    require(args.size == 4)
                    val updated = islands.update(owner) { AdminEdits.set(it, args[2], args[3], settings) } ?: return
                    if (args[2].equals("world-size", true)) islands.refreshBorders(updated)
                    audit(sender, "set $owner ${args[2]} ${args[3]}")
                    locales.send(sender, Lang.ADMIN_UPDATED, "player" to name(owner), "field" to args[2], "value" to args[3])
                }
                "member" -> {
                    require(args.size == 4 && args[3].lowercase() in setOf("add", "remove"))
                    val member = requireNotNull(resolve(args[2]))
                    val role = if (args[3].equals("add", true)) IslandRole.MEMBER else null
                    if (role == null && member in com.alytanta.oneblock.player.ProfileRemoval.participants(island)) {
                        requireNotNull(removeProfile)(sender, owner, member)
                        return
                    }
                    requireNotNull(islands.setMemberRole(owner, member, role, administrative = true))
                    audit(sender, "member $owner $member ${args[3]}")
                    locales.send(sender, Lang.ADMIN_UPDATED, "player" to name(owner), "field" to "member:${name(member)}", "value" to args[3])
                }
                "backup" -> {
                    islands.ensureWorld(island) { world ->
                        if (world == null) reply(sender, Lang.ISLAND_WORLD_FAILED) else islands.backupWorld(world) { success ->
                            if (success) audit(sender, "backup-world $owner")
                            reply(sender, if (success) Lang.ADMIN_WORLD_BACKUP else Lang.ADMIN_FAILED)
                        }
                    }
                }
                "reset-progress", "reset-world" -> {
                    confirmations.issue(actor(sender), owner, action == "reset-world")
                    locales.send(sender, Lang.ADMIN_CONFIRM, "action" to action, "player" to name(owner))
                }
                "cleanup-platform" -> {
                    confirmations.issue(actor(sender), owner, world = false, cleanup = true)
                    locales.send(sender, Lang.ADMIN_CLEANUP_CONFIRM, "player" to name(owner))
                }
                else -> locales.send(sender, Lang.UI2_ADMIN_USAGE)
            }
        } catch (_: IllegalArgumentException) {
            locales.send(sender, Lang.ADMIN_INVALID)
        } catch (e: Exception) { failure(sender, e) }
    }

    private fun confirm(sender: CommandSender) {
        val request = confirmations.consume(actor(sender))
        if (request == null) {
            locales.send(sender, Lang.ADMIN_CONFIRM_EXPIRED); return
        }
        FoliaTasks.async(plugin) {
            try {
                val backup = storage.backup()
                onSender(sender) {
                    if ((!sender.isOp || !sender.hasPermission("oneblock.admin")) || !sender.hasPermission("oneblock.admin.reset")) { locales.send(sender, Lang.NO_PERMISSION); return@onSender }
                    if (islands.underMaintenance(request.owner)) { locales.send(sender, Lang.ADMIN_WORLD_BUSY); return@onSender }
                    if (request.cleanup) {
                        islands.cleanLegacyPlatform(request.owner) { success ->
                            if (success) audit(sender, "cleanup-platform ${request.owner}; data backup ${backup.name}")
                            reply(sender, if (success) Lang.ADMIN_CLEANUP_DONE else Lang.ADMIN_CLEANUP_REFUSED)
                        }
                    } else if (request.world) {
                        islands.resetEmptyWorld(request.owner) { success ->
                            if (success) {
                                islands.update(request.owner) { AdminEdits.resetProgress(it, settings).copy(platformInitialized = false) }
                                audit(sender, "reset-world ${request.owner}; data backup ${backup.name}")
                            }
                            reply(sender, if (success) Lang.ADMIN_WORLD_RESET else Lang.ADMIN_WORLD_BUSY)
                        }
                    } else {
                        islands.update(request.owner) { AdminEdits.resetProgress(it, settings) }
                        audit(sender, "reset-progress ${request.owner}; data backup ${backup.name}")
                        locales.send(sender, Lang.ADMIN_RESET_DONE, "file" to backup.name)
                    }
                }
            } catch (e: Exception) { failure(sender, e) }
        }
    }

    private fun audit(sender: CommandSender, action: String) {
        plugin.logger.info("[ADMIN] ${sender.name}: $action")
        storage.audit(com.alytanta.oneblock.storage.OperationLog(operation = "ADMIN_ACTION", actor = (sender as? Player)?.uniqueId,
            account = null, success = true, status = "SUCCESS", details = mapOf("sender" to sender.name, "action" to action)))
    }
    private fun failure(sender: CommandSender, e: Exception) {
        storage.audit(com.alytanta.oneblock.storage.OperationLog(operation = "ADMIN_ACTION", actor = (sender as? Player)?.uniqueId,
            account = null, success = false, status = "FAILED", reason = e.javaClass.simpleName, details = mapOf("sender" to sender.name)))
        plugin.logger.log(java.util.logging.Level.WARNING, "Administrative action failed", e)
        reply(sender, Lang.ADMIN_FAILED)
    }
    private fun onSender(sender: CommandSender, action: () -> Unit) {
        if (sender is Player) FoliaTasks.entity(plugin, sender, task = action)
        else FoliaTasks.globalDelayed(plugin, 1, action)
    }
    private fun reply(sender: CommandSender, message: Lang, vararg values: Pair<String, Any>) =
        onSender(sender) { locales.send(sender, message, *values) }
}
