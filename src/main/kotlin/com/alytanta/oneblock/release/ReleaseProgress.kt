package com.alytanta.oneblock.release

import java.util.UUID
import com.google.gson.*

/** Account owns this state. Former members retain history but cannot act without active membership. */
data class ReleaseProgress(
    val version: Int = 1,
    val chapter: String = "start",
    val eligibleCoreBreaks: Long = 0,
    val charge: Long = 0,
    val grownLogs: Long = 0,
    val matureCrops: Long = 0,
    val merchantNet: Long = 0,
    val merchantCredits: Map<UUID, Long> = emptyMap(),
    val cropActor: UUID? = null,
    val members: Map<UUID, MemberProgress> = emptyMap(),
    val upgrades: Map<String, Int> = emptyMap(),
    val focus: String = "balanced",
    val crafted: Set<String> = emptySet(),
    val facts: Set<String> = emptySet(),
    val tutorial: Int = 0,
    val achievements: Set<String> = emptySet(),
    val adoptedAt: Long = System.currentTimeMillis(),
    val activity: com.alytanta.oneblock.activities.ActivityProgress = com.alytanta.oneblock.activities.ActivityProgress(),
    val dailyDay: Long = 0,
    val dailyStreak: Int = 0,
    val dailyBreaks: Long = 0,
    val coreChests: Long = 0,
    val dailyOrders: Long = 0,
    val dailyArena: Long = 0,
    val arenaRuns: Long = 0,
) {
    fun member(actor: UUID) = members[actor] ?: MemberProgress()
}
data class MemberProgress(val xp: Map<String, Long> = emptyMap(), val achievements: Set<String> = emptySet())
data class Ingredient(val materials: Set<String>, val amount: Int, val combatId: String? = null)
data class ReleaseItem(val material: String, val amount: Int, val tool: String? = null)
data class ChapterRule(val id: String, val breaks: Long, val logs: Long, val level: Int, val tool: Boolean,
    val upgrades: Int, val coins: Long, val ingredients: List<Ingredient>,val milestones:ChapterMilestones=ChapterMilestones())

data class ChapterMilestones(val allSkills:Int=0,val orders:Long=0,val events:Set<String> = emptySet(),val arenaWave:Long=0,val recipesAny:Set<String> = emptySet())
data class RecipeRule(val id: String, val chapter: String, val skill: String?, val level: Int,
    val coins: Long, val ingredients: List<Ingredient>, val items: List<ReleaseItem>,
    val durability: Double = 0.0, val sapling: Double = 0.0, val replant: Set<String> = emptySet())
data class PackRule(val id: String, val chapter: String, val points: Long, val items: List<ReleaseItem>, val advancedItems: List<ReleaseItem> = emptyList())
data class TutorialReward(val items: List<ReleaseItem>, val coins: Long = 0, val xp: Long = 0)
/** Persist the validated operation, including outputs, so reload/restart never reinterpret an escrow. */
data class ReleaseIntent(val action: String, val value: String, val coins: Long, val points: Long,
    val ingredients: List<Ingredient>, val items: List<ReleaseItem>, val personal: Boolean,
    val xpSkill: String? = null, val xpActor: UUID? = null, val xpAmount: Long = 0, val rewardCoins: Long = 0)

object ReleaseStateCodec {
    private val gson = Gson()
    fun normalize(value: JsonElement?): JsonElement? {
        if (value == null || value.isJsonNull) return null
        require(value.isJsonObject) { "releaseProgress must be an object" }
        val root = value.asJsonObject
        require(root.get("version")?.asString?.toIntOrNull() == 1) { "Unsupported release state version" }
        val defaults = gson.toJsonTree(ReleaseProgress()).asJsonObject
        root.entrySet().forEach { (key, field) -> if (!field.isJsonNull) defaults.add(key, field) }
        val members = defaults.getAsJsonObject("members")
        members.entrySet().toList().forEach { (id, field) ->
            UUID.fromString(id)
            val m = gson.toJsonTree(MemberProgress()).asJsonObject
            if (!field.isJsonNull) field.asJsonObject.entrySet().forEach { (k,v) -> if (!v.isJsonNull) m.add(k,v) }
            members.add(id,m)
        }
        val decoded = gson.fromJson(defaults, ReleaseProgress::class.java)
        require(decoded.chapter in ReleaseRules.CHAPTERS && decoded.focus in ReleaseRules.FOCUSES)
        require(listOf(decoded.eligibleCoreBreaks,decoded.charge,decoded.grownLogs,decoded.matureCrops).all { it >= 0 })
        require(decoded.merchantNet>=0 && decoded.merchantCredits.values.all { it>0 })
        require(decoded.tutorial in 0..12 && decoded.upgrades.all { (k,v) -> k in setOf("fortune","territory","focus") && v in 0..if(k=="focus")1 else 5 })
        require(decoded.members.values.all { m -> m.xp.all { (k,v) -> k in ReleaseRules.SKILLS && v >= 0 } })
        return defaults
    }
}
