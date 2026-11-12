package com.alytanta.oneblock.command

import org.bukkit.command.CommandSender
import org.bukkit.entity.Player

/** Shared by execution, help and completion; hiding a suggestion is never authorization. */
object CommandAccess {
    val adminRoutes = listOf("help", "menu", "hub", "dungeon", "items", "combatdemo", "players", "player", "island", "market", "profiles", "trades", "release", "save", "backup", "reload", "sethub", "confirm")
    val hubActions = listOf("help", "create-world", "load-world", "preview", "build", "status", "services", "activate", "stop", "resume", "rollback")
    val dungeonActions = listOf("menu", "help", "guide", "info", "invite", "accept", "ready", "start", "reconnect", "leave", "continue", "finish", "records")
    fun admin(sender: CommandSender) = sender.isOp && sender.hasPermission("oneblock.admin")
    fun visibleRoot(sender:CommandSender,label:String):Boolean {
        val command=label.lowercase()
        if(':' in command && command.substringBefore(':')!="one-block")return true
        return when(command.substringAfter(':')) {
            "obadmin","oba"->admin(sender)
            "obreload","oneblockreload","obrestart"->allowed(sender,listOf("reload"))
            "oneblock","ob","home","hub","obmenu","dungeon","mine"->sender.hasPermission("oneblock.command")
            "trade"->sender.hasPermission("oneblock.trade")
            else->true
        }
    }
    fun allowed(sender: CommandSender, arguments: List<String>): Boolean {
        if (!admin(sender)) return false
        val args = arguments.map(String::lowercase)
        val route = args.firstOrNull()
        if (route == "help") return true
        if (route in setOf("profiles", "trades") && sender is Player) return false // Console-only recovery.
        val action = args.getOrNull(1)
        val permission = when (route) {
            null, "menu", "players", "player" -> "inspect"
            "reload" -> "reload"
            "save", "backup" -> "save"
            "confirm" -> "reset"
            "island" -> when (action) {
                null, "list", "info", "menu" -> "inspect"
                "tp", "send", "evacuate" -> "teleport"
                "save", "backup" -> "save"
                "reset-progress", "reset-world", "cleanup-platform", "confirm" -> "reset"
                else -> "manage"
            }
            "dungeon" -> if (action in setOf(null, "list", "info", "players", "player")) "inspect" else "manage"
            "items" -> if(action in setOf(null,"list","status"))"inspect" else "manage"
            else -> "manage"
        }
        return sender.hasPermission("oneblock.admin.$permission") &&
            (route !in setOf("hub", "hubgen") || action != "rollback" || sender.hasPermission("oneblock.admin.reset"))
    }
    fun visibleAdmin(sender: CommandSender) = adminRoutes.filter { route ->
        allowed(sender, listOf(route)) || route=="island" && listOf("info","set","tp","backup","reset-world").any {allowed(sender,listOf(route,it))} ||
            route=="dungeon" && allowed(sender,listOf(route,"settings"))
    }
    fun playerRoutes(configured: Set<String>, sender: CommandSender) =
        if (!sender.hasPermission("oneblock.command")) emptyList() else configured.filter {
            it in setOf("help","home","hub","menu","profile","profiles","skills","skill","profession","professions","settings","coop","visit","trade") &&
                (it != "trade" || sender.hasPermission("oneblock.trade"))
        }.sorted()
}
