package com.alytanta.oneblock.config

import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.plugin.Plugin
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** Dedicated authoritative auction file. Legacy settings migrate once, without touching prices of live lots. */
object AuctionConfig {
    fun ensure(plugin: Plugin) {
        val target = File(plugin.dataFolder, "auction.yml")
        if (target.exists()) return
        ensure(plugin.dataFolder, requireNotNull(plugin.getResource("auction.yml")).reader(Charsets.UTF_8).use { it.readText() })
    }
    fun ensure(folder: File, bundled: String) {
        val target = File(folder, "auction.yml")
        if (target.exists()) return
        val yaml = YamlConfiguration().apply {
            loadFromString(bundled)
        }
        val legacy = File(folder, "config.yml").takeIf(File::exists)?.let { file ->
            YamlConfiguration().apply { load(file) }.getConfigurationSection("market.auction")
        }
        legacy?.getKeys(true)?.filterNot(legacy::isConfigurationSection)?.forEach { yaml.set("auction.$it", legacy.get(it)) }
        val staged = Files.createTempFile(folder.toPath(), "auction-", ".tmp")
        try {
            Files.writeString(staged, yaml.saveToString())
            try { Files.move(staged, target.toPath(), StandardCopyOption.ATOMIC_MOVE) }
            catch (_: java.nio.file.AtomicMoveNotSupportedException) { Files.move(staged, target.toPath()) }
        } finally { Files.deleteIfExists(staged) }
    }
    fun overlay(main: YamlConfiguration, file: File): Pair<YamlConfiguration, String?> {
        val merged = YamlConfiguration().apply { loadFromString(main.saveToString()) }
        if (!file.exists()) return merged to null
        val source = file.readText(Charsets.UTF_8)
        val auction = YamlConfiguration().apply { loadFromString(source) }
        require(auction.isConfigurationSection("auction")) { "auction.yml must contain an auction mapping" }
        require(auction.isList("auction.allowed-items")) { "auction.yml requires an explicit allowed-items list; [] disables new listings" }
        merged.set("market.auction", null)
        auction.getConfigurationSection("auction")!!.let { section ->
            for (key in section.getKeys(true)) {
                if (section.isConfigurationSection(key)) merged.createSection("market.auction.$key")
                else merged.set("market.auction.$key", section.get(key))
            }
        }
        return merged to source
    }
}
