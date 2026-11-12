package com.alytanta.oneblock.command

import com.alytanta.oneblock.config.PluginConfig
import com.alytanta.oneblock.admin.AdminEdits
import com.alytanta.oneblock.island.IslandService
import com.alytanta.oneblock.player.PlayerNames
import org.bukkit.command.Command
import org.bukkit.command.CommandSender
import org.bukkit.command.TabCompleter

class OneBlockTabCompleter(@Volatile private var config: PluginConfig, private val islands: IslandService,
    private val names: PlayerNames? = null) : TabCompleter {
    var hubSuggestions: (CommandSender,List<String>)->List<String> = { _,_ -> emptyList() }
    fun applySettings(next: PluginConfig) { config = next }
    override fun onTabComplete(sender: CommandSender, command: Command, alias: String, rawArgs: Array<out String>): List<String> {
        val args: Array<out String> = if(command.name.equals("obadmin",true)) arrayOf("admin",*rawArgs) else rawArgs
        if (args.isEmpty()) return emptyList()
        val admin = CommandAccess.admin(sender)
        val people by lazy { (names?.suggestions().orEmpty() + org.bukkit.Bukkit.getOnlinePlayers().map { it.name }).distinct() }
        val accounts by lazy { islands.all().map { it.publicId.orEmpty() } + people + islands.all().mapNotNull { island -> names?.known(island.ownerPlayer)?.let { name ->
            val slot = islands.profilesFor(island.ownerPlayer).indexOfFirst { it.owner == island.owner }
            if (slot >= 0) "$name#${slot + 1}" else null
        } } }
        val route = args.map { it.lowercase() }
        val result = when {
            route[0] == "admin" && !admin -> emptyList()
            route[0] == "admin" -> when {
                args.size == 1 -> listOf("admin")
                args.size == 2 -> CommandAccess.visibleAdmin(sender)
                (args.size>3 || route[1] !in setOf("island","dungeon")) && !CommandAccess.allowed(sender,args.drop(1).dropLast(1)) -> emptyList()
                route[1] in setOf("hub","hubgen") -> hubSuggestions(sender,args.drop(2))
                route[1]=="dungeon" && args.size==3 -> listOf("list","info","stop","stop-all","settings","set","entry","players","player","sendhub","heal","tp").filter {CommandAccess.allowed(sender,listOf("dungeon",it))}
                route[1]=="items" && args.size==3 -> listOf("list","give","status").filter {CommandAccess.allowed(sender,listOf("items",it))}
                route[1]=="items" && route[2]=="give" && args.size==4 -> people
                route[1]=="items" && route[2]=="give" && args.size==5 -> com.alytanta.oneblock.items.CustomItems.current()?.items?.keys?.toList().orEmpty()
                route[1] in setOf("player","players") && args.size==3 -> people
                route[1] in setOf("delete", "kick") && args.size == 3 -> accounts
                route[1] == "kick" && args.size == 4 -> people
                route[1] == "island" && args.size == 3 -> listOf("list", "info", "menu", "tp", "send", "member", "kick", "delete", "set", "create", "respawn", "evacuate", "backup", "reset-progress", "reset-world").filter {CommandAccess.allowed(sender,listOf("island",it))}
                route[1] == "island" && args.size == 4 && route[2] != "list" -> if (route[2] == "send" || route[2] == "create") people else accounts
                route[1] == "island" && args.size == 5 && route[2] == "set" -> AdminEdits.fields
                route[1] == "island" && args.size == 5 && route[2] == "send" -> accounts
                route[1] == "island" && args.size == 5 && route[2] == "member" -> people
                route[1] == "island" && args.size == 5 && route[2] == "kick" -> people
                route[1] == "island" && args.size == 6 && route[2] == "member" -> listOf("add", "remove")
                route[1] == "market" && args.size == 3 -> listOf("npc", "review", "resolve", "reload")
                route[1] == "market" && args.size == 4 && route[2] == "npc" -> listOf("shop", "bazaar", "auction", "guide", "dungeon", "guild")
                route[1] == "trades" && args.size == 3 -> listOf("review", "resolve")
                route[1] == "profiles" && args.size == 3 -> listOf("review", "resolve")
                else -> emptyList()
            }
            args.size == 1 -> CommandAccess.playerRoutes(config.playerCommands,sender) + if (admin) listOf("admin") else emptyList()
            route[0] !in CommandAccess.playerRoutes(config.playerCommands,sender) -> emptyList()
            route[0] == "profiles" && args.size == 2 -> listOf("create", "switch", "rename", "delete")
            route[0] == "profiles" && args.size == 3 && route[1] in setOf("switch", "delete") -> (1..islands.profileSettings.visibleSlots).map { it.toString() }
            route[0] == "coop" && args.size == 2 -> listOf("invite", "accept", "leave", "kick", "salvage")
            route[0] == "coop" && args.size == 3 && route[1] == "invite" -> org.bukkit.Bukkit.getOnlinePlayers().map { it.name }
            route[0] == "coop" && args.size == 3 && route[1] == "kick" -> people
            route[0] == "trade" && args.size == 2 -> people + listOf("accept", "deny", "cancel", "collect")
            route[0] == "trade" && args.size == 3 && route[1] == "accept" -> people
            route[0] == "visit" && args.size == 2 -> people
            route[0] == "settings" && args.size == 2 -> listOf("all", "none", "selected", "allow", "deny", "inventory-notifications")
            route[0] == "settings" && args.size == 3 && route[1] in setOf("allow", "deny") -> people
            else -> emptyList()
        }
        return result.filter { it.startsWith(args.last(), true) }.distinct().sortedBy { it.lowercase() }
    }
}
