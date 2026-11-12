package com.alytanta.oneblock.release

import com.alytanta.oneblock.island.*
import com.alytanta.oneblock.market.*
import com.alytanta.oneblock.storage.*
import org.bukkit.Material
import org.bukkit.entity.Player
import kotlin.random.Random

class ReleaseRuntime(private val islands: IslandService, private val tools: ReleaseTools, private val current: () -> ReleaseRules?) {
    var activityCredit:(com.alytanta.oneblock.island.Island,java.util.UUID,Material,Boolean)->com.alytanta.oneblock.island.Island={i,_,_,_->i}
    var progressChanged:(org.bukkit.entity.Player,ReleaseProgress?,ReleaseProgress?)->Unit={_,_,_->}
    fun fact(player: Player, fact: String) {
        val rules=current()?.takeIf { it.enabled } ?: return
        val island=islands.findForPlayer(player.uniqueId) ?: return
        if(island.releaseProgress==null || fact in island.releaseProgress.facts || !islands.persistenceAvailable())return
        OperationContext.with(OperationContext.Context(player.uniqueId,"COLLECTION_HARVEST")) {
            islands.update(island.owner) { live ->
                if(!ReleaseDomain(rules).active(live,player.uniqueId)||live.releaseProgress==null)live
                else live.copy(releaseProgress=live.releaseProgress.copy(facts=live.releaseProgress.facts+fact))
            }
        }
    }
    fun harvest(player: Player, account: java.util.UUID, block: Material, silk: Boolean, placed: Boolean, grown: Boolean,
        mature: Boolean, drops: MutableList<org.bukkit.inventory.ItemStack>, tool: org.bukkit.inventory.ItemStack) {
        val rules=current()?.takeIf { it.enabled } ?: return
        val island=islands.findForPlayer(player.uniqueId) ?: return
        if(island.owner!=account || !ReleaseDomain(rules).active(island,player.uniqueId) || island.releaseProgress==null ||
            player.gameMode!=org.bukkit.GameMode.SURVIVAL || drops.none { !it.isEmpty } || !islands.persistenceAvailable())return
        val crop=block in setOf(Material.WHEAT,Material.CARROTS,Material.POTATOES)
        if(placed && !crop || crop && !mature)return
        val before=island.releaseProgress
        OperationContext.with(OperationContext.Context(player.uniqueId,"COLLECTION_HARVEST")) {
            islands.update(account) { live ->
                if(!ReleaseDomain(rules).active(live,player.uniqueId)||live.releaseProgress==null)live else
                    activityCredit(live.copy(releaseProgress=ReleaseDomain(rules).credit(live.releaseProgress,player.uniqueId,block,false,silk,grown,mature)),player.uniqueId,block,grown || crop && mature)
            }
        }
        progressChanged(player,before,islands.findByOwner(account)?.releaseProgress)
        val skill = when { ReleaseRules.treeLog(block) -> "forestry"; crop -> "farming"; ReleaseRules.ore(block)&&!silk -> "mining"; else -> return }
        if(skill=="forestry" && placed || skill=="mining" && silk)return
        val recipe=tools.recipe(player,tool)
        val chance=rules.bonus(before.member(player.uniqueId).xp[skill]?:0)+(if(skill=="forestry")recipe?.sapling?:0.0 else 0.0)
        if(Random.nextDouble()>=chance)return
        val extra=when(skill) {
            "forestry" -> ReleaseRules.saplingFor(block) ?: return
            "farming" -> when(block) { Material.WHEAT->Material.WHEAT;Material.CARROTS->Material.CARROT;else->Material.POTATO }
            else -> drops.firstOrNull { !it.hasItemMeta() && it.type in com.alytanta.oneblock.harvest.HarvestSettings.ORES[block].orEmpty() }?.type ?: return
        }
        drops+=org.bukkit.inventory.ItemStack(extra,1)
    }
    fun merchant(island: Island, before: MarketState, after: MarketState): ReleaseProgress? {
        val p=island.releaseProgress ?: return null
        if(current()?.enabled!=true)return p
        var delta=0L
        val credits=p.merchantCredits.filterKeys { it in after.merchantSales || after.transfers.values.any { t -> t.buyback?.id==it && t.status in setOf(TransferStatus.PREPARED,TransferStatus.REVIEW) } }.toMutableMap()
        after.merchantSales.values.filter { it.account==island.owner && it.progressionCredit && it.id !in before.merchantSales }.forEach { sale ->
            // A returned cancelled buyback restores its original credit, not a fresh sale.
            if(before.transfers.values.none { it.buyback?.id==sale.id }) { credits[sale.id]=sale.total();delta=Math.addExact(delta,sale.total()) }
        }
        after.transfers.values.filter { it.account==island.owner }.forEach { t ->
            val prior=before.transfers[t.id]
            if(t.kind==TransferKind.SHOP_BUYBACK && prior==null && t.status==TransferStatus.PREPARED)delta=Math.subtractExact(delta,p.merchantCredits[t.buyback?.id]?:0)
            if(t.kind==TransferKind.SHOP_BUYBACK && t.status==TransferStatus.ABORTED && prior?.status!=TransferStatus.ABORTED)delta=Math.addExact(delta,p.merchantCredits[prior?.buyback?.id]?:0)
        }
        return p.copy(merchantNet=Math.addExact(p.merchantNet,delta),merchantCredits=credits)
    }
}
