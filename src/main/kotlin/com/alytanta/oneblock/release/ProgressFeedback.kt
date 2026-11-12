package com.alytanta.oneblock.release

import com.alytanta.oneblock.island.IslandService
import com.alytanta.oneblock.locale.LocaleService
import com.alytanta.oneblock.storage.PostgresStorageService
import com.alytanta.oneblock.util.Colors
import com.alytanta.oneblock.util.FoliaTasks
import net.kyori.adventure.bossbar.BossBar
import net.kyori.adventure.title.Title
import org.bukkit.Material
import org.bukkit.Sound
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.plugin.Plugin
import java.time.Duration
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** Shows progress while playing: XP above the hotbar, the phase bar, level and chapter titles, rare finds and core chests. */
class ProgressFeedback(private val plugin: Plugin, private val islands: IslandService, private val storage: PostgresStorageService,
    private val locales: LocaleService, private val current: () -> ReleaseRules?) : Listener {
    private class Bar(val bar: BossBar) { @Volatile var stamp = 0L }
    private val bars = ConcurrentHashMap<UUID, Bar>()
    private val lastXp = ConcurrentHashMap<UUID, Long>()
    private val pendingXp = ConcurrentHashMap<UUID, Long>()
    private var feelCache: Pair<ReleaseRules, CoreFeel>? = null
    private fun feel(rules: ReleaseRules) = feelCache?.takeIf { it.first === rules }?.second
        ?: CoreFeel.read(plugin.dataFolder.resolve("progression.yml")).also { feelCache = rules to it }
    private fun text(player: Player, key: String, vararg args: Pair<String, Any>) =
        args.fold(locales.textKey(player, "release.$key")) { line, (name, value) -> line.replace("{$name}", value.toString()) }
    private fun label(player: Player, rules: ReleaseRules, id: String) = ReleaseLabels.name(rules, id) { locales.textKey(player, it) }
    private fun title(player: Player, main: String, sub: String) = player.showTitle(Title.title(Colors.colorize(main), Colors.colorize(sub),
        Title.Times.times(Duration.ofMillis(200), Duration.ofMillis(2000), Duration.ofMillis(500))))

    fun changed(player: Player, before: ReleaseProgress?, after: ReleaseProgress?, core: Boolean) {
        val rules = current()?.takeIf { it.enabled } ?: return
        if (before == null || after == null || before === after) return
        FoliaTasks.entity(plugin, player) {
            xp(player, rules, before, after)
            if (core) {
                phase(player, rules, after)
                chest(player, rules)
            }
        }
    }
    private fun xp(player: Player, rules: ReleaseRules, before: ReleaseProgress, after: ReleaseProgress) {
        val id = player.uniqueId
        val skill = ReleaseRules.SKILLS.firstOrNull { (after.member(id).xp[it] ?: 0) > (before.member(id).xp[it] ?: 0) } ?: return
        val total = after.member(id).xp[skill] ?: 0
        val old = before.member(id).xp[skill] ?: 0
        val level = rules.level(total)
        if (level > rules.level(old)) levelUp(player, rules, skill, level)
        val gained = pendingXp.merge(id, total - old, Long::plus)!!
        val now = System.nanoTime()
        // Live event hints use the same line and matter more.
        if (after.activity.event != null || now - (lastXp[id] ?: 0) < 1_000_000_000) return
        lastXp[id] = now;pendingXp.remove(id)
        val from = rules.threshold(level)
        val to = rules.threshold((level + 1).coerceAtMost(30))
        player.sendActionBar(Colors.colorize(text(player, "xp_gain", "xp" to gained, "skill" to label(player, rules, skill),
            "bar" to ProgressBar.render(total - from, (to - from).coerceAtLeast(1)))))
    }
    private fun levelUp(player: Player, rules: ReleaseRules, skill: String, level: Int) {
        title(player, text(player, "level_title", "level" to level), label(player, rules, skill))
        player.playSound(player.location, Sound.ENTITY_PLAYER_LEVELUP, .7f, 1f)
        rules.recipes.values.filter { it.skill == skill && it.level == level }.forEach {
            player.sendMessage(Colors.colorize(text(player, "recipe_unlocked", "name" to label(player, rules, it.id))))
        }
    }
    private fun phase(player: Player, rules: ReleaseRules, p: ReleaseProgress) {
        val next = rules.chapters.getOrNull(rules.index(p.chapter) + 1)
        val feel = feel(rules)
        val breaks = p.eligibleCoreBreaks
        val chest = feel.chestEvery - breaks % feel.chestEvery
        val name = if (next == null) text(player, "phase_bar_max", "chapter" to label(player, rules, p.chapter), "breaks" to breaks, "chest" to chest)
            else text(player, "phase_bar", "chapter" to label(player, rules, next.id), "breaks" to breaks, "need" to next.breaks, "chest" to chest)
        val part = if (next == null || next.breaks <= 0) 1f else (breaks.toFloat() / next.breaks).coerceIn(0f, 1f)
        val state = bars.computeIfAbsent(player.uniqueId) { Bar(BossBar.bossBar(Colors.colorize(name), part, BossBar.Color.GREEN, BossBar.Overlay.NOTCHED_10)) }
        state.bar.name(Colors.colorize(name));state.bar.progress(part)
        player.showBossBar(state.bar)
        val stamp = System.nanoTime();state.stamp = stamp
        player.scheduler.runDelayed(plugin, { if (state.stamp == stamp) player.hideBossBar(state.bar) }, null, 100L)
    }
    private fun chest(player: Player, rules: ReleaseRules) {
        val owner = islands.findForPlayer(player.uniqueId)?.owner ?: return
        val feel = feel(rules)
        val p = islands.findByOwner(owner)?.releaseProgress ?: return
        if (p.eligibleCoreBreaks / feel.chestEvery <= p.coreChests) return
        var opened: CoreChest.Opened? = null
        try {
            islands.releaseUpdate(owner) { live, state ->
                val result = CoreChest.open(live, state, rules, feel)
                opened = result
                result?.change ?: ReleaseChange(live, state)
            }
            storage.flushAsync()
        } catch (e: Exception) {
            // Stays due and is retried on the next break.
            return
        }
        val done = opened?.takeIf { it.rewarded } ?: return
        player.sendMessage(Colors.colorize(text(player, if (done.big) "chest_big" else "chest_opened")))
        player.playSound(player.location, if (done.big) Sound.UI_TOAST_CHALLENGE_COMPLETE else Sound.BLOCK_CHEST_OPEN, .7f, 1f)
    }
    fun rareFind(player: Player, material: Material) {
        val rules = current()?.takeIf { it.enabled } ?: return
        if (material !in feel(rules).rare) return
        player.sendMessage(Colors.colorize(text(player, "rare_find", "item" to locales.textKey(player, "release.item.${material.name.lowercase()}"))))
        player.playSound(player.location, Sound.BLOCK_NOTE_BLOCK_CHIME, .8f, 1.5f)
    }
    fun chapter(player: Player, id: String) {
        val rules = current() ?: return
        title(player, text(player, "chapter_title"), label(player, rules, id))
        player.playSound(player.location, Sound.UI_TOAST_CHALLENGE_COMPLETE, .7f, 1f)
    }
    @EventHandler fun quit(event: PlayerQuitEvent) {
        val id = event.player.uniqueId
        bars.remove(id);lastXp.remove(id);pendingXp.remove(id)
    }
}
