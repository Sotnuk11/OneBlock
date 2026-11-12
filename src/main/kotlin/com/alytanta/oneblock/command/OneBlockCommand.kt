package com.alytanta.oneblock.command

import com.alytanta.oneblock.config.PluginConfig
import com.alytanta.oneblock.admin.AdminController
import com.alytanta.oneblock.hub.HubService
import com.alytanta.oneblock.menu.PlayerMenu
import com.alytanta.oneblock.util.OwnedItems
import com.alytanta.oneblock.storage.StorageService
import com.alytanta.oneblock.island.Island
import com.alytanta.oneblock.island.IslandCreateResult
import com.alytanta.oneblock.island.IslandProgressionService
import com.alytanta.oneblock.island.IslandRole
import com.alytanta.oneblock.island.IslandService
import com.alytanta.oneblock.locale.Lang
import com.alytanta.oneblock.locale.LocaleService
import com.alytanta.oneblock.player.PlayerService
import com.alytanta.oneblock.util.FoliaTasks
import org.bukkit.Material
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import org.bukkit.plugin.java.JavaPlugin
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class OneBlockCommand(
    private val plugin: JavaPlugin,
    @Volatile private var config: PluginConfig,
    private val islands: IslandService,
    private val progression: IslandProgressionService,
    private val players: PlayerService,
    private val locales: LocaleService,
    private val storage: StorageService,
    private val hub: HubService,
    private val ownedItems: OwnedItems,
    menuConfiguration: com.alytanta.oneblock.menu.MenuConfiguration,
    private val names: com.alytanta.oneblock.player.PlayerNames? = null,
) : CommandExecutor {
    var tradeCommand: ((Player, List<String>) -> Unit)? = null
    var onWorldArrival: ((Player) -> Unit)? = null
    var tradeAdmin: ((CommandSender, List<String>) -> Unit)? = null
    var releaseAdmin: ((CommandSender, List<String>) -> Unit)? = null
    var hubgenAdmin: ((CommandSender,List<String>) -> Unit)? = null
    var mineAdmin:((CommandSender,List<String>)->Unit)?=null
    var itemAdmin:((CommandSender,List<String>)->Unit)?=null
    var combatDemoAdmin:((CommandSender,List<String>)->Unit)?=null
    var mineExit:((Player,()->Unit)->Boolean)?=null
    var guideCommand: ((Player,List<String>) -> Unit)? = null
    var marketAdmin: ((CommandSender, List<String>) -> Unit)? = null
    var reloadConfiguration: ((CommandSender) -> Unit)? = null
    val profiles = com.alytanta.oneblock.player.ProfileController(plugin, islands, players, storage, hub, locales) { player, island ->
        teleportToIsland(player, island, newlyCreated = false)
    }.apply { resolvePlayerName = { names?.resolve(it) }; displayPlayerName = { names?.known(it) } }
    fun applySettings(next: PluginConfig) { config = next; administration.applySettings(next) }
    val playerMenu = PlayerMenu(plugin, islands, locales, ownedItems, menuConfiguration, names)
    val administration = AdminController(plugin, config, islands, storage, locales, hub, names) { player, island ->
        teleportToIsland(player, island, newlyCreated = false)
    }.apply { removeProfile = profiles::administrativeRemoval }

    private fun mainCommand(command: Command) = command.name.lowercase() in setOf("oneblock", "obadmin")
    override fun onCommand(sender: CommandSender, command: Command, label: String, rawArgs: Array<out String>): Boolean {
        val args: Array<out String> = if (command.name.equals("obadmin", true)) arrayOf("admin", *(rawArgs.ifEmpty { arrayOf("help") })) else rawArgs
        if (mainCommand(command) && args.firstOrNull()?.equals("admin", true) == true) {
            if (!CommandAccess.allowed(sender, args.drop(1))) { locales.send(sender, Lang.NO_PERMISSION); return true }
            if (args.getOrNull(1)?.equals("help", true) == true) {
                locales.send(sender, Lang.COMMAND_ADMIN_HELP, "commands" to CommandAccess.visibleAdmin(sender).joinToString(" | "));return true
            }
        }
        if (command.name.equals("oneblock",true) && (args.isEmpty() || args[0].equals("help",true))) {
            if(!sender.hasPermission("oneblock.command"))locales.send(sender,Lang.NO_PERMISSION)
            else locales.send(sender,Lang.COMMAND_PLAYER_HELP,"commands" to CommandAccess.playerRoutes(config.playerCommands,sender).joinToString(" | "))
            return true
        }
        if (mainCommand(command) && (args.firstOrNull()?.equals("reload", true)==true ||
                args.firstOrNull()?.equals("admin", true)==true && args.getOrNull(1)?.equals("reload", true)==true)) {
            reloadConfiguration?.invoke(sender);return true
        }
        val context = com.alytanta.oneblock.storage.OperationContext.Context((sender as? Player)?.uniqueId, "COMMAND",
            details = mapOf("command" to command.name, "route" to args.take(2).map { it.take(64) }, "sender" to sender.name))
        storage.audit(com.alytanta.oneblock.storage.OperationLog(operation = "COMMAND", actor = context.actor,
            account = (sender as? Player)?.uniqueId?.let(islands::findForPlayer)?.owner, success = null, status = "REQUESTED",
            correlation = context.request, details = context.details))
        return com.alytanta.oneblock.storage.OperationContext.with(context) {
            try { executeCommand(sender, command, label, args) }
            catch (error: Exception) {
                storage.audit(com.alytanta.oneblock.storage.OperationLog(operation = "COMMAND", actor = context.actor, account = null,
                    success = false, status = "FAILED", reason = error.javaClass.simpleName, correlation = context.request, details = context.details))
                throw error
            }
        }
    }

    private fun executeCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        if(mainCommand(command) && args.firstOrNull()?.equals("admin",true)==true && args.getOrNull(1)?.equals("combatdemo",true)==true) {
            combatDemoAdmin?.invoke(sender,args.drop(2));return true
        }
        if(mainCommand(command) && args.firstOrNull()?.equals("admin",true)==true && args.getOrNull(1)?.lowercase() in setOf("dungeon","players","player")) {
            val route=if(args[1].equals("dungeon",true))args.drop(2) else listOf(args[1].lowercase())+args.drop(2)
            mineAdmin?.invoke(sender,route)?:locales.send(sender,Lang.MARKET_UNAVAILABLE);return true
        }
        if(mainCommand(command) && args.firstOrNull()?.equals("admin",true)==true && args.getOrNull(1)?.equals("items",true)==true) {
            itemAdmin?.invoke(sender,args.drop(2));return true
        }
        if (sender is Player && com.alytanta.oneblock.market.InventoryGuard.busy(sender.uniqueId)) {
            locales.send(sender, Lang.MARKET_BUSY); return true
        }
        if(sender is Player && sender.hasPermission("oneblock.command")) {
            val travel=if(command.name.lowercase() in setOf("home","hub"))command.name.lowercase()
                else if(mainCommand(command))args.firstOrNull()?.lowercase()?.takeIf {it in setOf("home","hub")} else null
            if(travel!=null && travel in config.playerCommands && mineExit?.invoke(sender) {if(travel=="home" && storage.healthy)home(sender) else hub.teleport(sender)}==true)return true
        }
        if (mainCommand(command) && (args.firstOrNull()?.equals("reload", true) == true ||
                (args.firstOrNull()?.equals("admin", true) == true && args.getOrNull(1)?.equals("reload", true) == true))) {
            reloadConfiguration?.invoke(sender)
            return true
        }
        if(args.firstOrNull()?.equals("guide",true)==true && sender is Player && sender.hasPermission("oneblock.command")) { guideCommand?.invoke(sender,args.drop(1));return true }
        if(mainCommand(command) && args.firstOrNull()?.equals("admin",true)==true && args.getOrNull(1)?.lowercase() in setOf("hub", "hubgen")) {
            hubgenAdmin?.invoke(sender,args.drop(2))?:locales.send(sender,Lang.MARKET_UNAVAILABLE);return true
        }
        if (!storage.healthy) {
            val wantsHub = command.name.equals("hub", true) || (command.name.equals("oneblock", true) && args.firstOrNull()?.equals("hub", true) == true)
            if (sender is Player && wantsHub && sender.hasPermission("oneblock.command")) hub.teleport(sender)
            else locales.send(sender, Lang.MARKET_UNAVAILABLE)
            return true
        }
        if (mainCommand(command) && args.firstOrNull()?.equals("admin", ignoreCase = true) == true) {
            if (!sender.isOp || !sender.hasPermission("oneblock.admin")) { locales.send(sender, Lang.NO_PERMISSION); return true }
            if (args.getOrNull(1)?.equals("profiles", true) == true) {
                if (sender.hasPermission("oneblock.admin")) profiles.admin(sender, args.drop(2)) else locales.send(sender, Lang.NO_PERMISSION)
                return true
            }
            if (args.getOrNull(1)?.equals("trades", true) == true) { tradeAdmin?.invoke(sender, args.drop(2)); return true }
            if (args.getOrNull(1)?.equals("release", true) == true) { releaseAdmin?.invoke(sender,args.drop(2));return true }
            if (args.getOrNull(1)?.equals("market", true) == true) { marketAdmin?.invoke(sender, args.drop(2)); return true }
            val route = args.drop(1)
            if (route.firstOrNull()?.equals("menu",true)==true) { administration.handle(sender,emptyList());return true }
            if (route.firstOrNull()?.equals("island", true) == true) {
                val action = route.getOrNull(1)?.lowercase()
                if (action == null || action in setOf("list", "info", "menu", "tp", "send", "member", "kick", "delete", "set", "create", "respawn", "evacuate", "backup", "reset-progress", "reset-world"))
                    administration.handle(sender, route.drop(1)) else locales.send(sender, Lang.UI2_ADMIN_USAGE)
            }
            else if (route.isEmpty() || route[0] in setOf("save", "backup", "sethub", "confirm", "delete", "kick")) administration.handle(sender, route)
            else locales.send(sender, Lang.UI2_ADMIN_USAGE)
            return true
        }
        if (!sender.hasPermission("oneblock.command")) {
            locales.send(sender, Lang.NO_PERMISSION)
            return true
        }
        val player = sender as? Player ?: run {
            locales.send(sender, Lang.ONLY_PLAYER)
            return true
        }
        if (command.name.equals("home", ignoreCase = true)) {
            if ("home" in config.playerCommands) home(player) else locales.send(player, Lang.UNKNOWN_COMMAND)
            return true
        }
        if (command.name.equals("hub", ignoreCase = true)) { if ("hub" in config.playerCommands) hub.teleport(player) else locales.send(player, Lang.UNKNOWN_COMMAND); return true }
        if (args.isEmpty() || args[0].equals("help", true)) {
            locales.send(player, Lang.COMMAND_PLAYER_HELP, "commands" to CommandAccess.playerRoutes(config.playerCommands, player).joinToString(" | "))
            return true
        }
        if (args[0].lowercase() !in config.playerCommands) { locales.send(player, Lang.UNKNOWN_COMMAND); return true }
        when (args[0].lowercase()) {
            "trade" -> tradeCommand?.invoke(player, args.drop(1))
            "home" -> home(player)
            "hub" -> hub.teleport(player)
            "menu" -> playerMenu.open(player)
            "profile" -> playerMenu.open(player)
            "profiles" -> if (args.size == 1 && islands.findForPlayer(player.uniqueId) == null && !playerMenu.hasMenu(player)) playerMenu.open(player) else profiles.command(player, args.drop(1))
            "skills", "skill", "profession", "professions" -> playerMenu.open(player, "skills")
            "settings" -> playerMenu.settingsCommand(player, args.drop(1))

            "coop" -> coop(player, args.drop(1))
            "visit" -> visit(player, args.getOrNull(1))
            else -> locales.send(player, Lang.UNKNOWN_COMMAND)
        }
        return true
    }

    private fun coop(player: Player, arguments: List<String>) {
        when (arguments.firstOrNull()?.lowercase()) {
            "invite" -> {
                val island = islands.findForPlayer(player.uniqueId)
                if (island == null || island.ownerPlayer != player.uniqueId) {
                    locales.send(player, Lang.COOP_OWNER_ONLY)
                    return
                }
                val target = arguments.getOrNull(1)?.let(plugin.server::getPlayerExact)
                if (target == null) {
                    locales.send(player, Lang.COOP_TARGET_NOT_FOUND)
                    return
                }
                if (target.uniqueId == player.uniqueId) {
                    locales.send(player, Lang.COOP_INVITE_SELF)
                    return
                }
                if (island.members.containsKey(target.uniqueId)) {
                    locales.send(player, Lang.COOP_ALREADY_MEMBER)
                    return
                }
                players.invite(target.uniqueId, island.owner, System.currentTimeMillis() + INVITE_LIFETIME)
                locales.send(player, Lang.COOP_INVITED, "player" to target.name)
                FoliaTasks.entity(plugin, target) {
                    locales.send(target, Lang.COOP_INVITE_RECEIVED, "owner" to player.name)
                }
            }
            "accept" -> {
                val invite = players.takeInvitation(player.uniqueId)
                if (invite == null || invite.inviteExpiresAt < System.currentTimeMillis()) {
                    locales.send(player, Lang.COOP_INVITE_EXPIRED)
                    return
                }
                if (islands.profilesFor(player.uniqueId).size >= islands.profileSettings.availableSlots) {
                    locales.send(player, Lang.PROFILES_LIMIT)
                    return
                }
                val island = islands.setMemberRole(requireNotNull(invite.inviteOwner), player.uniqueId, IslandRole.MEMBER)
                if (island == null) {
                    locales.send(player, Lang.COOP_INVITE_EXPIRED)
                    return
                }
                locales.send(player, Lang.COOP_JOINED)
                profiles.switch(player, island.owner)
                plugin.server.getPlayer(island.ownerPlayer)?.let { owner ->
                    FoliaTasks.entity(plugin, owner) {
                        locales.send(owner, Lang.COOP_MEMBER_JOINED, "player" to player.name)
                    }
                }
            }
            "leave" -> {
                profiles.leave(player)
            }
            "kick" -> profiles.kick(player, arguments.getOrNull(1))
            "salvage" -> profiles.salvage(player)
            else -> locales.send(player, Lang.COOP_USAGE)
        }
    }

    private fun visit(player: Player, ownerName: String?) {
        if (ownerName == null) {
            locales.send(player, Lang.VISIT_USAGE)
            return
        }
        val owner = names?.resolve(ownerName) ?: plugin.server.getPlayerExact(ownerName)?.uniqueId
            ?: plugin.server.getOfflinePlayerIfCached(ownerName)?.uniqueId
        if (owner == null) {
            locales.send(player, Lang.COOP_TARGET_NOT_FOUND)
            return
        }
        val island = islands.findForPlayer(owner) ?: islands.findByOwner(owner)
        if (island == null) {
            locales.send(player, Lang.ISLAND_NOT_FOUND)
            return
        }
        if (!islands.canVisit(island, player)) { locales.send(player, Lang.UI2_VISIT_DENIED); return }
        islands.prepareRespawn(island) { destination ->
            if (destination == null) {
                FoliaTasks.entity(plugin, player) { locales.send(player, Lang.ISLAND_WORLD_FAILED) }
                return@prepareRespawn
            }
            FoliaTasks.entity(plugin, player) {
                teleportSafely(player, island, destination) { success, error ->
                    FoliaTasks.entity(plugin, player) {
                        when {
                            error != null -> {
                                plugin.logger.warning("Could not teleport ${player.uniqueId} to $owner's island: ${error.message}")
                                locales.send(player, Lang.ISLAND_TELEPORT_FAILED)
                            }
                            success == true -> {
                                player.fallDistance = 0f
                                applyIslandBorder(player, island)
                                locales.send(player, Lang.VISIT_SUCCESS, "owner" to ownerName)
                            }
                            else -> locales.send(player, Lang.ISLAND_TELEPORT_FAILED)
                        }
                    }
                }
            }
        }
    }

    private fun home(player: Player) {
        val island = islands.findForPlayer(player.uniqueId)
        if (island == null) { playerMenu.open(player); return }
        teleportToIsland(player, island, newlyCreated = false)
    }

    private fun teleportToIsland(player: Player, island: Island, newlyCreated: Boolean) {
        islands.prepareRespawn(island) { destination ->
            if (destination == null) {
                FoliaTasks.entity(plugin, player) { locales.send(player, Lang.ISLAND_WORLD_FAILED) }
                return@prepareRespawn
            }
            FoliaTasks.entity(plugin, player) {
                teleportSafely(player, island, destination) { success, error ->
                    FoliaTasks.entity(plugin, player) {
                        when {
                            error != null -> {
                                plugin.logger.warning("Could not teleport ${player.uniqueId} to island ${island.owner}: ${error.message}")
                                locales.send(player, Lang.ISLAND_TELEPORT_FAILED)
                            }
                            success == true -> {
                                player.fallDistance = 0f
                                applyIslandBorder(player, island)
                                if (islands.canBuild(islands.findByOwner(island.owner) ?: island, player.uniqueId) && islands.claimStartingKit(island.owner)) {
                                    giveStartingItems(player, destination)
                                }
                                locales.send(player, if (newlyCreated) Lang.ISLAND_CREATED else Lang.ISLAND_TELEPORTED)
                            }
                            else -> locales.send(player, Lang.ISLAND_TELEPORT_FAILED)
                        }
                    }
                }
            }
        }
    }

    private fun teleportSafely(player: Player, island: Island, destination: org.bukkit.Location, result: (Boolean?, Throwable?) -> Unit) {
        if (!islands.beginTeleport(island.owner)) { result(false, null); return }
        try {
            player.teleportAsync(destination).whenComplete { success, error ->
                islands.endTeleport(island.owner)
                if(success==true&&error==null)FoliaTasks.entity(plugin,player) {
                    if(islands.sameWorld(island,player.world))onWorldArrival?.invoke(player)
                    result(success,error)
                } else result(success, error)
            }
        } catch (exception: Exception) {
            islands.endTeleport(island.owner)
            result(false, exception)
        }
    }

    private fun giveStartingItems(player: Player, dropLocation: org.bukkit.Location) {
        if (config.startingItems.isEmpty()) return
        val items = config.startingItems.mapNotNull { entry ->
            val parts = entry.split(':', limit = 2)
            val materialName = parts[0].trim()
            val amount = parts.getOrNull(1)?.trim()?.toIntOrNull()?.coerceIn(1, 64) ?: 1
            val material = Material.matchMaterial(materialName)
            if (material == null || material.isAir) {
                plugin.logger.warning("Ignoring invalid starting item '$entry' in islands.starting-items")
                null
            } else {
                ItemStack(material, amount)
            }
        }
        if (items.isNotEmpty()) {
            val overflow = if (player.isDead || com.alytanta.oneblock.market.InventoryGuard.busy(player.uniqueId)) items
                else com.alytanta.oneblock.menu.MenuInventory.add(player.inventory, *items.toTypedArray())
            overflow.forEach { ownedItems.drop(dropLocation.clone(), it, player.uniqueId) }
            locales.send(player, Lang.STARTING_ITEMS)
        }
    }

    private fun applyIslandBorder(player: Player, island: Island) {
        islands.applyPlayerBorder(player, islands.findByOwner(island.owner) ?: island)
    }

    private fun ownIsland(player: Player): Island? = islands.findForPlayer(player.uniqueId).also {
        if (it == null) locales.send(player, Lang.ISLAND_NOT_FOUND)
    }

    private fun buy(player: Player, key: String?, catalog: Map<String, Pair<Int, Long>>, skill: Boolean) {
        val island = ownIsland(player) ?: return
        if (island.ownerPlayer != player.uniqueId) {
            locales.send(player, Lang.MODE_REQUIRE_OWNER)
            return
        }
        val name = key?.lowercase()
        if (name == null || name !in catalog) {
            locales.send(player, Lang.INVALID_OPTION)
            return
        }
        when (progression.purchase(island.owner, catalog, name, skill)) {
            IslandProgressionService.PurchaseResult.BOUGHT -> {
                val updated = islands.findByOwner(island.owner) ?: island
                val level = (if (skill) updated.skills else updated.upgrades)[name] ?: 0
                val baseCost = catalog.getValue(name).second * level
                if (!skill && name == "island_size") {
                    islands.ensureWorld(updated) { world ->
                        if (world == null) {
                            FoliaTasks.entity(plugin, player) { locales.send(player, Lang.ISLAND_WORLD_FAILED) }
                        } else {
                            islands.expandPlatform(updated, level)
                        }
                    }
                }
                locales.send(player, Lang.BOUGHT, "name" to name, "level" to level, "cost" to baseCost)
            }
            IslandProgressionService.PurchaseResult.POOR -> locales.send(player, Lang.TOO_POOR)
            IslandProgressionService.PurchaseResult.MAXED -> locales.send(player, Lang.MAX_LEVEL)
            IslandProgressionService.PurchaseResult.MISSING -> locales.send(player, Lang.INVALID_OPTION)
        }
    }

    companion object {
        private const val INVITE_LIFETIME = 10 * 60 * 1000L
    }
}
