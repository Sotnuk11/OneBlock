package com.alytanta.oneblock.oneblock

import com.alytanta.oneblock.island.Island
import com.alytanta.oneblock.island.IslandProgressionService
import com.alytanta.oneblock.island.MineReward
import org.bukkit.block.Block

class OneBlockService(private val progression: IslandProgressionService, @Volatile var drops: DropSettings,
    private val harvest: com.alytanta.oneblock.harvest.HarvestService? = null) {
    @Volatile var releaseRules: com.alytanta.oneblock.release.ReleaseRules? = null
    var activityCredit:(Island,java.util.UUID,org.bukkit.Material)->Island={i,_,_->i}
    var releaseBusy: (java.util.UUID) -> Boolean = { false }
    var progressChanged: (java.util.UUID, com.alytanta.oneblock.release.ReleaseProgress?, com.alytanta.oneblock.release.ReleaseProgress?) -> Unit = { _, _, _ -> }
    var rareFind: (org.bukkit.entity.Player, org.bukkit.Material) -> Unit = { _, _ -> }

    fun regenerateCore(block: Block, island: Island, actor: java.util.UUID? = null, tool: org.bukkit.inventory.ItemStack? = null, creative: Boolean = false, eligible: Boolean = true, silkDrop: org.bukkit.Material? = null, survival: Boolean = !creative): MineReward? {
        if (!com.alytanta.oneblock.island.IslandWorldIdentity.matches(block.world, island.owner) || block.x != island.x || block.y != island.y || block.z != island.z) return null
        val settings = drops
        var amount = 1
        val broken = block.type
        val rules=releaseRules?.takeIf { it.enabled }
        val silk=silkDrop!=null || (tool?.getEnchantmentLevel(org.bukkit.enchantments.Enchantment.SILK_TOUCH)?:0)>0
        var progressBefore: com.alytanta.oneblock.release.ReleaseProgress? = null
        var progressAfter: com.alytanta.oneblock.release.ReleaseProgress? = null
        val mined = progression.mineBlock(island.owner, credit = { current, material ->
            if (actor != null && !creative && eligible && (current.ownerPlayer == actor || current.members[actor] == com.alytanta.oneblock.island.IslandRole.MEMBER))
                harvest?.credit(actor, broken, listOf(material to amount), (tool?.getEnchantmentLevel(org.bukkit.enchantments.Enchantment.SILK_TOUCH) ?: 0) > 0)
            if(rules!=null && current.releaseProgress!=null && actor!=null && survival && eligible && amount>0 && com.alytanta.oneblock.release.ReleaseDomain(rules).active(current,actor))
                activityCredit(current.copy(releaseProgress=com.alytanta.oneblock.release.ReleaseDomain(rules).credit(current.releaseProgress,actor,broken,true,silk,false,false)),actor,broken).also {
                    progressBefore=current.releaseProgress;progressAfter=it.releaseProgress
                }
            else current
        },available = { current -> !releaseBusy(current.owner) }) { current ->
            val drop = current.core.currentDrop?.takeIf { it.block == block.type }
                ?: (settings.tableFor(current.core.breaks).entries + settings.starter.entries + settings.main.entries)
                    .firstOrNull { it.block == block.type }
                ?: CoreDrop(block.type, when (block.type) {
                    org.bukkit.Material.STONE -> org.bukkit.Material.COBBLESTONE
                    org.bukkit.Material.GRASS_BLOCK -> org.bukkit.Material.DIRT
                    else -> block.type
                }, 1)
            // Routing through a magnet cannot create loot that the break would not normally drop.
            // Creative may regenerate the core, but its block breaks do not yield resource items.
            amount = if (eligible && !creative) settings.amountFor(drop, current.core, current.upgrades["fortune"] ?: 0) else 0
            val release=current.releaseProgress
            if(rules!=null && release!=null && eligible && !creative) {
                val mining=if(!silk && com.alytanta.oneblock.release.ReleaseRules.ore(broken) && actor!=null)rules.bonus(release.member(actor).xp["mining"]?:0) else 0.0
                val chance=((release.upgrades["fortune"]?:0)*rules.fortuneStep+mining).coerceAtMost(rules.coreBonusCap)
                amount=1+if(!silk && kotlin.random.Random.nextDouble()<chance)1 else 0
            }
            val earns=release!=null && rules!=null && actor!=null && survival && eligible && amount>0 && com.alytanta.oneblock.release.ReleaseDomain(rules).active(current,actor)
            val upcoming=if(earns)release!!.copy(eligibleCoreBreaks=com.alytanta.oneblock.release.ReleaseDomain.saturate(release.eligibleCoreBreaks,1)) else release
            val next = if(rules!=null && upcoming!=null)rules.table(upcoming,settings.starter).randomDrop()
                else settings.tableFor((current.core.breaks + 1).coerceAtLeast(current.core.breaks)).randomDrop()
            block.setType(next.block, false)
            (silkDrop ?: drop.reward) to next
        }?.copy(amount = amount, notifyReward = settings.rewardMessages)
        if(mined!=null && actor!=null)progressChanged(actor,progressBefore,progressAfter)
        return mined
    }
}
