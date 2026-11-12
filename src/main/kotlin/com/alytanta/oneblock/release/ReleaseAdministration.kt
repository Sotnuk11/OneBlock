package com.alytanta.oneblock.release

import com.alytanta.oneblock.island.IslandService
import com.alytanta.oneblock.market.*
import com.alytanta.oneblock.storage.*
import com.alytanta.oneblock.util.*
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import org.bukkit.plugin.Plugin
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** Explicit admin preview + backup, as required by release plan section 13. Never runs on startup. */
class ReleaseAdministration(private val plugin: Plugin, private val islands: IslandService,
    private val storage: PostgresStorageService, private val current: () -> ReleaseRules?) {
    private data class Preview(val account: UUID, val generation: Long, val rules: ReleaseRules)
    private val previews=ConcurrentHashMap<String,Preview>()
    private fun reply(sender: CommandSender, value: String) {
        if(sender is Player)FoliaTasks.entity(plugin,sender) { sender.sendMessage(Colors.colorize(value)) } else sender.sendMessage(Colors.colorize(value))
    }
    fun handle(sender: CommandSender, args: List<String>) {
        if(!sender.isOp || !sender.hasPermission("oneblock.admin") || !sender.hasPermission("oneblock.admin.manage"))return reply(sender,"&cНемає дозволу / Permission denied")
        val rules=current() ?: return reply(sender,"&cprogression.yml недоступний / unavailable")
        if(args.firstOrNull()=="review") {
            storage.marketState().transfers.values.filter { it.kind==TransferKind.RELEASE && it.status in setOf(TransferStatus.PREPARED,TransferStatus.REVIEW) }
                .forEach { reply(sender,"${it.id} | ${it.status} | ${it.release?.action} ${it.release?.value}") };return
        }
        val reference=args.getOrNull(1)
        val island=islands.all().firstOrNull { it.publicId==reference || it.owner.toString()==reference }
        if(island==null || args.firstOrNull() !in setOf("preview","adopt") || args.size!=2) return reply(sender,"ob admin release preview|adopt <profile ID>; review")
        if(!rules.enabled || island.releaseProgress!=null)return reply(sender,"&cПотрібні enabled 1.0 та legacy профіль / Requires enabled 1.0 and legacy profile")
        if(args[0]=="preview") {
            previews[sender.name]=Preview(island.owner,storage.generation,rules)
            reply(sender,"&e${island.publicId}: start, XP=0, eligibleCoreBreaks=0. Coins=${island.coins}, blocks=${island.core.breaks}, currentDrop/world/items preserved. Automatic backup before adopt.")
            return
        }
        val preview=previews.remove(sender.name)
        if(preview?.account!=island.owner || preview.rules!==rules || preview.generation!=storage.generation)return reply(sender,"&cСпочатку preview / Run preview first")
        FoliaTasks.async(plugin) {
            runCatching {
                val backup=storage.backup()
                islands.releaseUpdate(island.owner) { live,state ->
                    require(live.releaseProgress==null && current()===rules && storage.generation==preview.generation)
                    require(state.transfers.values.none { it.account==live.owner && it.status in setOf(TransferStatus.PREPARED,TransferStatus.REVIEW) })
                    ReleaseChange(live.copy(releaseProgress=ReleaseProgress()),state)
                }
                storage.audit(OperationLog(operation="RELEASE_ADOPT",actor=(sender as? Player)?.uniqueId,account=island.owner,success=true,status="SUCCESS",details=mapOf("backup" to backup.name)))
                check(storage.flush());reply(sender,"&aAdopted ${island.publicId}. Backup: ${backup.name}")
            }.onFailure { reply(sender,"&cНе виконано / Failed: ${it.javaClass.simpleName}") }
        }
    }
}
