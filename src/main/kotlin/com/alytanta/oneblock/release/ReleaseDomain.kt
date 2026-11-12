package com.alytanta.oneblock.release

import com.alytanta.oneblock.island.*
import com.alytanta.oneblock.market.*
import org.bukkit.Material
import java.util.UUID

/** Pure plans and immutable ledger transitions. No Bukkit inventory, scheduler or JDBC access. */
class ReleaseDomain(val rules: ReleaseRules) {
    fun active(island: Island, actor: UUID) = island.retiredAt == null &&
        (island.ownerPlayer == actor || island.members[actor] == IslandRole.MEMBER)
    private fun progress(island: Island, actor: UUID): ReleaseProgress {
        require(rules.enabled && active(island,actor)) { "access" }
        return requireNotNull(island.releaseProgress) { "legacy" }
    }
    fun upgradeCosts(value: String) = when(value) { "fortune" -> rules.fortuneCosts; "territory" -> rules.territoryCosts; "focus" -> listOf(rules.focusCost); else -> error("upgrade") }
    fun upgradeChapter(value: String, current: Int) = when(value) { "fortune" -> if(current<2)"workshop" else if(current<4)"depths" else "developed"
        "territory" -> if(current<2)"living" else if(current<4)"workshop" else "depths"; else -> "developed" }
    fun plan(island: Island, state: MarketState, actor: UUID, action: String, value: String = ""): ReleaseIntent {
        val p = progress(island,actor)
        require(state.transfers.values.none { it.account == island.owner && it.kind == TransferKind.RELEASE && it.status in setOf(TransferStatus.PREPARED,TransferStatus.REVIEW) }) { "busy" }
        val intent = when(action) {
            "chapter" -> {
                val gate = rules.chapters.getOrNull(rules.index(p.chapter)+1) ?: error("maxed")
                require(value == gate.id && p.eligibleCoreBreaks >= gate.breaks && p.grownLogs >= gate.logs) { "requirements" }
                require(p.members.values.any { m -> m.xp.values.any { rules.level(it) >= gate.level } } || gate.level == 0) { "level" }
                require(!gate.tool || p.crafted.any { it in setOf("pickaxe_1","axe_1","hoe_1") }) { "tool" }
                require(p.upgrades.values.sum() >= gate.upgrades) { "upgrades" }
                val m=gate.milestones
                require((m.allSkills==0 || ReleaseRules.SKILLS.all {skill->p.members.values.any {rules.level(it.xp[skill]?:0)>=m.allSkills}}) && p.activity.completed>=m.orders &&
                    m.events.all {"activity:$it" in p.facts} && (m.arenaWave==0L || p.facts.any {it.startsWith("arena:wave:") && (it.substringAfterLast(':').toLongOrNull()?:0)>=m.arenaWave}) &&
                    (m.recipesAny.isEmpty() || p.crafted.any {it in m.recipesAny})) {"requirements"}
                val items = if (gate.id == "living") rules.livingItems else emptyList()
                ReleaseIntent(action,value,gate.coins,0,gate.ingredients,items,false)
            }
            "recipe" -> {
                val r = rules.recipes.getValue(value)
                require(p.activity.discovery >= (rules.activities?.unlocks?.get(value)?:0)) { "discovery" }
                require(rules.atLeast(p.chapter,r.chapter)) { "chapter" }
                require(r.skill == null || rules.level(p.member(actor).xp[r.skill] ?: 0) >= r.level) { "level" }
                ReleaseIntent(action,value,r.coins,0,r.ingredients,r.items,true)
            }
            "upgrade" -> {
                val current = p.upgrades[value] ?: 0
                val costs = upgradeCosts(value)
                require(current < costs.size) { "maxed" }
                require(rules.atLeast(p.chapter,upgradeChapter(value,current))) { "chapter" }
                ReleaseIntent(action,"$value:${current+1}",costs[current],0,emptyList(),emptyList(),false)
            }
            "focus" -> {
                require(value in ReleaseRules.FOCUSES && p.upgrades["focus"] == 1 && p.chapter == "developed") { "requirements" }
                ReleaseIntent(action,value,0,0,emptyList(),emptyList(),false)
            }
            "pack" -> {
                val pack = rules.packs.getValue(value)
                require(rules.atLeast(p.chapter,pack.chapter)) { "chapter" }
                val items = pack.items + if(value == "mineral" && rules.atLeast(p.chapter,"depths")) pack.advancedItems else emptyList()
                ReleaseIntent(action,value,0,pack.points,emptyList(),items,false)
            }
            "guild-deliver","activity-reward" -> com.alytanta.oneblock.activities.ActivityDomain.plan(island,actor,action,value)
            "tutorial" -> tutorial(island,state,actor,value)
            else -> error("action")
        }
        require(island.coins >= intent.coins && p.charge >= intent.points) { "funds" }
        return intent
    }
    fun tutorialReady(p: ReleaseProgress): Boolean = when(p.tutorial) {
        0 -> "menu" in p.facts
        1 -> p.eligibleCoreBreaks >= 25
        2 -> setOf("craft:CRAFTING_TABLE","craft:STONE_PICKAXE").all { it in p.facts }
        3 -> p.grownLogs >= 16
        4 -> rules.atLeast(p.chapter,"living")
        5 -> p.matureCrops >= 32 && p.cropActor != null
        6 -> setOf("hub","merchant","bazaar").all { it in p.facts }
        7 -> p.merchantNet >= 100
        8 -> rules.atLeast(p.chapter,"workshop")
        9 -> p.crafted.any { it in setOf("pickaxe_1","axe_1","hoe_1") }
        10 -> p.upgrades.values.any { it > 0 }
        11 -> rules.atLeast(p.chapter,"depths")
        else -> false
    }
    private fun tutorial(island: Island, state: MarketState, actor: UUID, choice: String): ReleaseIntent {
        val p = island.releaseProgress!!
        require(tutorialReady(p)) { "requirements" }
        require(choice.startsWith("${p.tutorial}:")) { "stale" }
        val skill = choice.substringAfter(':')
        if(p.tutorial == 10) require(skill in ReleaseRules.SKILLS) { "skill" }
        val reward=rules.tutorialRewards[p.tutorial]
        return ReleaseIntent("tutorial",p.tutorial.toString(),0,0,emptyList(),reward.items,false,
            if(p.tutorial==5)"farming" else if(p.tutorial==10)skill else null,
            if(p.tutorial==5)p.cropActor else if(p.tutorial==10)actor else null,reward.xp,reward.coins)
    }
    fun prepare(island: Island, state: MarketState, transfer: ItemTransfer): ReleaseChange {
        require(transfer.kind == TransferKind.RELEASE && transfer.status == TransferStatus.PREPARED)
        require(!state.transfers.containsKey(transfer.id))
        require(state.transfers.values.none { it.actor == transfer.actor && it.status in setOf(TransferStatus.PREPARED,TransferStatus.REVIEW) }) { "busy" }
        val wanted = requireNotNull(transfer.release)
        // The menu's preview is a quote: reject any changed cost, gates or output before escrow.
        val requestValue = when(wanted.action) { "upgrade" -> wanted.value.substringBefore(':'); "tutorial" -> "${island.releaseProgress!!.tutorial}:${wanted.xpSkill ?: "mining"}"; else -> wanted.value }
        require(plan(island,state,transfer.actor,wanted.action,requestValue) == wanted) { "stale" }
        val next = island.copy(coins = island.coins-wanted.coins,releaseProgress = island.releaseProgress!!.copy(charge = island.releaseProgress.charge-wanted.points))
        return ReleaseChange(next,state.copy(transfers=state.transfers+(transfer.id to transfer)))
    }
    fun settle(island: Island, state: MarketState, id: UUID, status: TransferStatus): ReleaseChange {
        val t = requireNotNull(state.transfers[id]); require(t.account==island.owner && t.kind==TransferKind.RELEASE)
        if(t.status in setOf(TransferStatus.DONE,TransferStatus.ABORTED)) return ReleaseChange(island,state)
        require(t.status in setOf(TransferStatus.PREPARED,TransferStatus.REVIEW))
        if(status==TransferStatus.REVIEW) return ReleaseChange(island,state.copy(transfers=state.transfers+(id to t.copy(status=status))))
        val intent = requireNotNull(t.release)
        var next = island
        val claims = state.claims.toMutableMap()
        if(status==TransferStatus.ABORTED) {
            next = island.copy(releaseProgress=island.releaseProgress!!.copy(charge=Math.addExact(island.releaseProgress.charge,intent.points)))
            if(intent.coins>0) { val claim = MarketClaim(UUID.randomUUID(),island.owner,null,intent.coins,"release-refund:$id");claims[claim.id]=claim }
        } else {
            require(status==TransferStatus.DONE)
            var p = island.releaseProgress!!
            when(intent.action) {
                "guild-deliver","activity-reward" -> p=com.alytanta.oneblock.activities.ActivityDomain.settle(p,intent,this)
                "chapter" -> { require(rules.index(intent.value) == rules.index(p.chapter)+1);p=p.copy(chapter=intent.value) }
                "upgrade" -> { val key=intent.value.substringBefore(':');val level=intent.value.substringAfter(':').toInt();require(level==(p.upgrades[key]?:0)+1);p=p.copy(upgrades=p.upgrades+(key to level)) }
                "recipe" -> p=p.copy(crafted=p.crafted+intent.value)
                "focus" -> p=p.copy(focus=intent.value)
                "tutorial" -> {
                    require(p.tutorial==intent.value.toInt())
                    p=p.copy(tutorial=p.tutorial+1)
                    if(intent.xpActor!=null && intent.xpSkill!=null) p=xp(p,intent.xpActor,intent.xpSkill,intent.xpAmount)
                    if(intent.value=="11")p=p.copy(facts=p.facts+"title:growing")
                    val coins = intent.rewardCoins
                    if(coins>0) { val c=MarketClaim(UUID.randomUUID(),island.owner,null,coins,"release-tutorial:$id");claims[c.id]=c }
                }
            }
            next = island.copy(releaseProgress=achievements(p))
            intent.items.forEachIndexed { index,item ->
                val claimId=UUID.nameUUIDFromBytes("release:$id:$index".toByteArray(Charsets.UTF_8))
                claims[claimId]=MarketClaim(claimId,island.owner,item.material,item.amount.toLong(),"release:$id",t.releaseItems?.get(index),if(intent.personal)t.actor else null)
            }
        }
        return ReleaseChange(next,state.copy(claims=claims,transfers=state.transfers+(id to t.copy(status=status,releaseItems=null))))
    }
    fun xp(p: ReleaseProgress, actor: UUID, skill: String, amount: Long): ReleaseProgress {
        require(skill in ReleaseRules.SKILLS && amount>=0)
        val m=p.member(actor); val old=m.xp[skill]?:0
        val value=(old+minOf(amount,Long.MAX_VALUE-old)).coerceAtMost(rules.threshold(30))
        return p.copy(members=p.members+(actor to m.copy(xp=m.xp+(skill to value))))
    }
    fun credit(p: ReleaseProgress, actor: UUID, block: Material, core: Boolean, silk: Boolean, grown: Boolean, mature: Boolean): ReleaseProgress {
        var next=p
        val skill = when { ReleaseRules.treeLog(block) -> "forestry"
            !silk && (ReleaseRules.ore(block)||block in setOf(Material.STONE,Material.COBBLESTONE)) -> "mining"
            !core && mature && block in setOf(Material.WHEAT,Material.CARROTS,Material.POTATOES) -> "farming"
            else -> null }
        if(skill!=null) next=xp(next,actor,skill,rules.xpRates.getValue(if(skill=="farming")"crop" else if(skill=="forestry")"log" else if(ReleaseRules.ore(block))"ore" else "stone"))
        if(core) next=next.copy(eligibleCoreBreaks=saturate(next.eligibleCoreBreaks,1),charge=if(rules.atLeast(p.chapter,"workshop"))saturate(p.charge,rules.chargePerBreak) else p.charge)
        if(!core && grown && ReleaseRules.treeLog(block)) next=next.copy(grownLogs=saturate(next.grownLogs,1))
        if(skill=="farming") next=next.copy(matureCrops=saturate(next.matureCrops,1),cropActor=if(next.matureCrops<32)actor else next.cropActor)
        return achievements(next)
    }
    fun achievements(p: ReleaseProgress): ReleaseProgress {
        val earned=p.achievements.toMutableSet()
        listOf(1000L to "core_1k",10000L to "core_10k",50000L to "core_50k",250000L to "core_250k").forEach { (n,id)->if(p.eligibleCoreBreaks>=n)earned+=id }
        ReleaseRules.CHAPTERS.drop(1).forEach { if(rules.atLeast(p.chapter,it))earned+="chapter_$it" }
        if(p.upgrades["fortune"]==5)earned+="fortune_max"
        if(p.upgrades["territory"]==5)earned+="territory_max"
        if(p.upgrades["focus"]==1)earned+="focus_unlocked"
        if(setOf("pickaxe","axe","hoe").all { f -> p.crafted.any { it.startsWith("${f}_") } })earned+="crafted_all_families"
        return p.copy(achievements=earned,members=p.members.mapValues { (_,m)->m.copy(achievements=m.achievements+ReleaseRules.SKILLS.flatMap { skill -> listOf(10,30).filter { rules.level(m.xp[skill]?:0)>=it }.map { "${skill}_$it" } }) })
    }
    companion object { fun saturate(value: Long, add: Long) = value+minOf(add,Long.MAX_VALUE-value) }
}
data class ReleaseChange(val island: Island, val state: MarketState)
