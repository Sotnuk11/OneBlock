package com.alytanta.oneblock.release

import com.alytanta.oneblock.island.IslandService
import com.alytanta.oneblock.market.*
import com.alytanta.oneblock.storage.*
import com.alytanta.oneblock.util.FoliaTasks
import org.bukkit.NamespacedKey
import org.bukkit.entity.Player
import org.bukkit.persistence.PersistentDataType
import org.bukkit.plugin.Plugin
import java.util.UUID

class ReleaseOperations(private val plugin: Plugin, private val storage: PostgresStorageService,
    private val islands: IslandService, private val tools: ReleaseTools, private val current: () -> ReleaseRules?,
    private val feedback: (Player,String) -> Unit) {
    private val marker=NamespacedKey(plugin,"release_handoff")
    private val busyProfiles=java.util.concurrent.ConcurrentHashMap<UUID,UUID>()
    private val operationAccounts=java.util.concurrent.ConcurrentHashMap<UUID,UUID>()
    fun busy(account: UUID)=busyProfiles.containsKey(account)
    fun resetForRecovery() { busyProfiles.clear();operationAccounts.clear() }
    private fun reserveProfile(account: UUID, token: UUID): Boolean {
        if(busyProfiles.putIfAbsent(account,token)!=null)return false
        operationAccounts[token]=account
        return true
    }
    private fun releaseGuards(actor: UUID, token: UUID) {
        operationAccounts.remove(token)?.let { account -> busyProfiles.remove(account,token) }
        InventoryGuard.release(actor,token)
    }
    /** Used only by the existing console CONFIRM recovery command, never by automatic recovery. */
    fun resolve(sender: org.bukkit.command.CommandSender, transfer: ItemTransfer, applied: Boolean) {
        if(sender is Player || !sender.isOp || !sender.hasPermission("oneblock.admin.manage") || transfer.status!=TransferStatus.REVIEW)return
        val rules=current() ?: return
        if(plugin.server.getPlayer(transfer.actor)?.isOnline==true)return
        val token=InventoryGuard.acquireToken(transfer.actor) ?: return
        if(!reserveProfile(transfer.account,token)) { InventoryGuard.release(transfer.actor,token);return }
        FoliaTasks.async(plugin) {
            try {
                val backup=storage.backup()
                check(plugin.server.getPlayer(transfer.actor)?.isOnline!=true) { "Actor joined during review" }
                islands.releaseUpdate(transfer.account) { island,state ->
                    require(state.transfers[transfer.id]?.status==TransferStatus.REVIEW)
                    ReleaseDomain(rules).settle(island,state,transfer.id,if(applied)TransferStatus.DONE else TransferStatus.ABORTED)
                }
                storage.audit(OperationLog(operation="RELEASE_RESOLVE",actor=null,account=transfer.account,success=true,status="SUCCESS",correlation=transfer.id,
                    details=mapOf("applied" to applied,"backup" to backup.name,"operator" to sender.name)))
                check(storage.flush());sender.sendMessage("Release operation resolved. Backup: ${backup.name}")
            } catch(e:Exception) { plugin.logger.warning("Release review ${transfer.id} failed: ${e.javaClass.simpleName}") }
            finally { releaseGuards(transfer.actor,token) }
        }
    }
    fun submit(player: Player, account: UUID, quoted: ReleaseIntent, rules: ReleaseRules, done: () -> Unit) {
        if(!InventoryGuard.canStart(player.uniqueId))return feedback(player,"busy")
        val token=InventoryGuard.acquireToken(player.uniqueId) ?: return feedback(player,"busy")
        if(!reserveProfile(account,token)) { InventoryGuard.release(player.uniqueId,token);return feedback(player,"busy") }
        val generation=storage.generation
        val completion = {
            if(quoted.action=="upgrade" && quoted.value.startsWith("territory:"))islands.findByOwner(account)?.let(islands::refreshBorders)
            done()
        }
        var intent: ItemTransfer?=null
        try {
            require(current()===rules && storage.healthy && player.isOnline && !player.isDead && player.itemOnCursor.isEmpty) { "stale" }
            require(islands.findForPlayer(player.uniqueId)?.owner==account) { "stale" }
            val after=ReleaseInventoryPlan.remove(player.inventory.storageContents,quoted.ingredients)
            val encoded=quoted.items.map { if(it.tool!=null)AuctionItems.encode(tools.create(it,player,rules),65536) else null }
            val transfer=ItemTransfer(UUID.randomUUID(),account,player.uniqueId,"STONE",1,TransferKind.RELEASE,quoted.coins,null,
                ReleaseInventoryPlan.hash(player.inventory.storageContents),ReleaseInventoryPlan.hash(after),release=quoted,releaseItems=encoded)
            val domain=ReleaseDomain(rules)
            islands.releaseUpdate(account) { island,state ->
                val prepared=domain.prepare(island,state,transfer)
                if(quoted.ingredients.isEmpty())domain.settle(prepared.island,prepared.state,transfer.id,TransferStatus.DONE) else prepared
            }
            intent=transfer
            audit(transfer,if(quoted.ingredients.isEmpty())"DONE" else "PREPARED")
            player.closeInventory()
            storage.flushAsync().whenComplete { _,error ->
                if(error!=null) { release(player,token,"unavailable");return@whenComplete }
                if(quoted.ingredients.isEmpty()) { complete(player,token,generation,completion);return@whenComplete }
                FoliaTasks.entity(plugin,player,retired={ abort(transfer,rules,token) }) {
                    if(!storage.healthy || storage.generation!=generation) { release(player,token,"unavailable");return@entity }
                    if(player.isDead || islands.findForPlayer(player.uniqueId)?.owner!=account || current()!==rules ||
                        ReleaseInventoryPlan.hash(player.inventory.storageContents)!=transfer.beforeHash) { abort(transfer,rules,token);return@entity }
                    try {
                        player.inventory.storageContents=after
                        player.persistentDataContainer.set(marker,PersistentDataType.STRING,transfer.id.toString())
                        player.saveData()
                    } catch(e:Exception) {
                        settle(transfer,rules,TransferStatus.REVIEW)
                        release(player,token,"review");return@entity
                    }
                    try {
                        settle(transfer,rules,TransferStatus.DONE).whenComplete { _,finishError ->
                            if(finishError!=null)release(player,token,"unavailable") else complete(player,token,generation,completion)
                        }
                    } catch(e:Exception) { release(player,token,"review") }
                }
            }
        } catch(e:Exception) {
            releaseGuards(player.uniqueId,token)
            feedback(player,if(intent!=null)"review" else e.message?.takeIf { it in setOf("busy","legacy","requirements","funds","items","level","tool","upgrades","maxed","chapter","stale") } ?: "requirements")
        }
    }
    private fun complete(player: Player, token: UUID, generation: Long, done: () -> Unit) {
        releaseGuards(player.uniqueId,token)
        FoliaTasks.entity(plugin,player) {
            if(storage.healthy && storage.generation==generation && !InventoryGuard.busy(player.uniqueId)) {
                feedback(player,"done")
                done()
            }
        }
    }
    private fun release(player: Player, token: UUID, message: String) {
        releaseGuards(player.uniqueId,token)
        FoliaTasks.entity(plugin,player) { feedback(player,message) }
    }
    private fun audit(t: ItemTransfer, status: String) = storage.audit(OperationLog(operation="RELEASE_${t.release?.action?.uppercase()}",
        actor=t.actor,account=t.account,success=if(status=="DONE")true else null,status=status,correlation=t.id,
        details=mapOf("target" to t.release?.value,"coins" to t.release?.coins,"points" to t.release?.points)))
    private fun settle(t: ItemTransfer, rules: ReleaseRules, status: TransferStatus): java.util.concurrent.CompletableFuture<Unit> {
        islands.releaseUpdate(t.account) { island,state -> ReleaseDomain(rules).settle(island,state,t.id,status) }
        audit(t,status.name)
        return storage.flushAsync()
    }
    private fun abort(t: ItemTransfer, rules: ReleaseRules, token: UUID) {
        runCatching { settle(t,rules,TransferStatus.ABORTED).whenComplete { _,_ -> releaseGuards(t.actor,token) } }
            .onFailure { releaseGuards(t.actor,token) }
    }
    /** Recovery uses the persisted operation's outputs; no fresh config pricing or inventory replay. */
    fun recover(player: Player) {
        val rules=current() ?: return
        if(InventoryGuard.busy(player.uniqueId)||!storage.healthy)return
        val pending=storage.marketState().transfers.values.filter { it.actor==player.uniqueId && it.kind==TransferKind.RELEASE && it.status==TransferStatus.PREPARED }
        pending.forEach { t ->
            val token=InventoryGuard.acquireToken(player.uniqueId) ?: return
            if(!reserveProfile(t.account,token)) { InventoryGuard.release(player.uniqueId,token);return }
            runCatching {
                val applied=player.persistentDataContainer.get(marker,PersistentDataType.STRING)==t.id.toString()
                val unchanged=ReleaseInventoryPlan.hash(player.inventory.storageContents)==t.beforeHash
                val status=if(applied)TransferStatus.DONE else if(unchanged)TransferStatus.ABORTED else TransferStatus.REVIEW
                settle(t,rules,status).whenComplete { _,error ->
                    releaseGuards(player.uniqueId,token)
                    if(error!=null || status==TransferStatus.REVIEW)FoliaTasks.entity(plugin,player) { feedback(player,if(error!=null)"unavailable" else "review") }
                }
            }.onFailure { releaseGuards(player.uniqueId,token);feedback(player,"review") }
        }
    }
}
