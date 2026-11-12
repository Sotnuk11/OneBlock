package com.alytanta.oneblock.locale

import com.alytanta.oneblock.player.PlayerService
import com.alytanta.oneblock.util.Colors
import org.bukkit.command.CommandSender
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.entity.Player
import org.bukkit.plugin.Plugin
import java.io.File
import java.io.InputStreamReader

class LocaleService(
    private val plugin: Plugin,
    @Volatile private var defaultLanguage: String,
    private val players: PlayerService,
) {
    // Read from many region threads; replaced wholesale on load.
    @Volatile
    private var messages: Map<String, Map<String, String>> = emptyMap()

    fun load() { applyMessages(defaultLanguage, prepareMessages()) }

    /** Parse all locale files before publishing any changes, including when reloading config.yml. */
    fun prepareMessages(): Map<String, Map<String, String>> {
        val loaded = mutableMapOf<String, Map<String, String>>()
        for (language in SUPPORTED) {
            val path = "lang/$language.yml"
            val file = File(plugin.dataFolder, path)
            if (!file.exists()) plugin.saveResource(path, false)

            com.alytanta.oneblock.util.StrictYaml.validate(file.readText(Charsets.UTF_8))
            val yaml = YamlConfiguration().apply { load(file) }
            // Bundled file fills keys missing from an older copy on disk.
            val bundled = plugin.getResource(path)?.use { stream ->
                YamlConfiguration.loadConfiguration(InputStreamReader(stream, Charsets.UTF_8))
            } ?: YamlConfiguration()
            if (migrateLegacyMessages(language, yaml, bundled)) yaml.save(file)
            loaded[language] = messageTable(yaml, bundled)
        }
        return loaded
    }

    fun applyMessages(language: String, loaded: Map<String, Map<String, String>>) {
        defaultLanguage = language
        messages = loaded
        if (defaultLanguage !in messages) {
            plugin.logger.warning("Unsupported language.default '$defaultLanguage', using $FALLBACK")
        }
    }

    fun send(sender: CommandSender, lang: Lang, vararg placeholders: Pair<String, Any>) {
        auditResponse?.invoke(sender, lang)
        // An empty text in lang/*.yml switches the message off.
        val text = format(languageOf(sender), lang, *placeholders)
        if (text.isNotBlank()) sender.sendMessage(Colors.colorize(text))
    }
    var auditResponse: ((CommandSender, Lang) -> Unit)? = null

    fun text(sender: CommandSender, lang: Lang, vararg placeholders: Pair<String, Any>): String =
        format(languageOf(sender), lang, *placeholders)

    fun format(language: String, lang: Lang, vararg placeholders: Pair<String, Any>): String {
        val all = messages
        val table = all[language] ?: all[FALLBACK].orEmpty()
        var text = table[lang.key] ?: all[FALLBACK]?.get(lang.key) ?: lang.key
        text = text.replace("{prefix}", table["prefix"].orEmpty())
        for ((name, value) in placeholders) text = text.replace("{$name}", value.toString())
        return text
    }

    /** Profile override -> configured default. The client language is ignored: one language for everyone. */
    fun languageOf(sender: CommandSender): String {
        val fallback = defaultLanguage.takeIf { it in messages } ?: FALLBACK
        if (sender !is Player) return fallback
        return players.find(sender.uniqueId)?.language?.takeIf { it in messages } ?: fallback
    }
    fun language(player: Player): String = languageOf(player)
    fun textKey(sender: CommandSender, key: String): String = messages[languageOf(sender)]?.get(key) ?: messages[FALLBACK]?.get(key) ?:
        key.takeIf {it.startsWith("release.name.")}?.removePrefix("release.name.")?.let {com.alytanta.oneblock.items.CustomItems.current()?.find(it)?.name} ?: key

    companion object {
        internal fun migrateLegacyMessages(language: String, custom: YamlConfiguration, bundled: YamlConfiguration): Boolean {
            val old = if (language == "uk") mapOf(
                "progress.core-reward" to "{prefix}&a+{item} &8| &6+{coins} монет",
                "progress.worker-status" to "{prefix}&7Працівники: {workers}/5. Доступно: {coins} монет (5 за працівника/годину, максимум 24 год).",
            ) else mapOf(
                "progress.core-reward" to "{prefix}&a+{item} &8| &6+{coins} coins",
                "progress.worker-status" to "{prefix}&7Workers: {workers}/5. Collectable: {coins} coins (5 per worker/hour, capped at 24h).",
            )
            var changed = false
            val auctionHints = if (language == "uk") mapOf(
                "auction.duration" to "&eТривалість: &f{hours} год\n&7Натисни для наступного варіанта.",
                "auction.rarity" to "&dРідкість: &f{rarity}\n&7Натисни, щоб змінити.",
                "market.sort" to "&eСортування: &f{sort}\n&7Натисни, щоб змінити.",
                "market-ui.auction-select-help" to "&eОбери предмет\n&7Тут копії дозволених речей із твого інвентаря.\n&7До підтвердження публікації речі залишаються в тебе.",
            ) else mapOf(
                "auction.duration" to "&eDuration: &f{hours} hours\n&7Click for the next option.",
                "auction.rarity" to "&dItem tier: &f{rarity}\n&7Click to cycle.",
                "market.sort" to "&eSort: &f{sort}\n&7Click to cycle.",
                "market-ui.auction-select-help" to "&eChoose an item\n&7These are previews of eligible inventory items.\n&7Nothing moves until you confirm publication.",
            )
            auctionHints.forEach { (key, prior) ->
                if (custom.getString(key) == prior && bundled.isString(key)) {
                    custom.set(key, bundled.getString(key)); changed = true
                }
            }
            // Keep custom wording while removing the retired admin confirmation argument.
            listOf("admin.confirm", "admin.cleanup-confirm", "admin.usage").forEach { key ->
                custom.getString(key)?.let { prior ->
                    val cleaned = prior.replace("confirm {token}", "confirm")
                        .replace("confirm <код>", "confirm").replace("confirm <code>", "confirm")
                    if (cleaned != prior) { custom.set(key, cleaned); changed = true }
                }
            }
            val oldConfirmation = if (language == "uk") "{prefix}&cКод підтвердження неправильний, використаний або прострочений."
                else "{prefix}&cInvalid, consumed or expired confirmation code."
            if (custom.getString("admin.confirm-expired") == oldConfirmation && bundled.isString("admin.confirm-expired")) {
                custom.set("admin.confirm-expired", bundled.getString("admin.confirm-expired")); changed = true
            }
            custom.getString("dashboard.admin-info")?.let { prior ->
                val cleaned = prior.replace(", рівень {tier}", "").replace(", tier {tier}", "")
                if (cleaned != prior) { custom.set("dashboard.admin-info", cleaned); changed = true }
            }
            old.forEach { (key, prior) ->
                if (custom.getString(key) == prior && bundled.isString(key)) {
                    custom.set(key, bundled.getString(key)); changed = true
                }
            }
            return changed
        }
        internal fun messageTable(custom: YamlConfiguration, bundled: YamlConfiguration): Map<String, String> {
            custom.setDefaults(bundled)
            custom.options().copyDefaults(true) // getKeys must include new keys absent from old files on disk.
            return custom.getKeys(true).filter(custom::isString).associateWith { custom.getString(it)!! }
        }

        val SUPPORTED = listOf("uk", "en")
        private const val FALLBACK = "en"
    }
}
