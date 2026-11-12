package com.alytanta.oneblock.config

import org.bukkit.plugin.Plugin
import java.io.File
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.FileAlreadyExistsException

/** Creates absent bundled configuration files only. Existing bytes, including invalid YAML, remain authoritative. */
object DefaultConfigs {
    val dataFiles=listOf("config.yml","hub-generator.yml","sidebar.yml","trades.yml","market-menus.yml",
        "harvest.yml","profiles.yml","interface-menus.yml","progression.yml","guides.yml","combat.yml","dungeon.yml",
        "items.yml","resource-pack.yml","activities.yml","lang/uk.yml","lang/en.yml")
    val menuFiles=listOf("main-menu","release","guides","combat","dungeon").flatMap {group->
        listOf("commands.yml","layout.yml","statistics.yml").map {"menus/$group/$it"}}
    private fun target(folder:File,resource:String)=
        (if(resource.startsWith("menus/"))folder.parentFile.resolve(resource) else folder.resolve(resource)).toPath().toAbsolutePath().normalize()

    fun ensure(plugin:Plugin,retained:Map<Path,ByteArray> = emptyMap()) {
        val created=ensure(plugin.dataFolder,plugin::getResource,retained)
        if(created.isNotEmpty())plugin.logger.info("Created missing configuration files: ${created.joinToString()}")
    }
    @Synchronized
    fun ensure(folder:File,resource:(String)->InputStream?,retained:Map<Path,ByteArray> = emptyMap()):List<Path> {
        val created=mutableListOf<Path>()
        for(name in dataFiles+menuFiles) {
            val file=target(folder,name)
            if(writeMissing(file) {retained[file]?.inputStream()?:requireNotNull(resource(name)) {"Missing bundled resource: $name"}})created.add(file)
        }
        val auction=target(folder,"auction.yml")
        if(retained.containsKey(auction)) {
            if(writeMissing(auction) {retained.getValue(auction).inputStream()})created.add(auction)
        } else if(!Files.exists(auction,NOFOLLOW_LINKS)) {
            // Preserve the existing legacy market.auction migration on first creation.
            AuctionConfig.ensure(folder,requireNotNull(resource("auction.yml")).reader(Charsets.UTF_8).use {it.readText()})
            created.add(auction)
        }
        require(Files.isRegularFile(auction,NOFOLLOW_LINKS)) {"Configuration path must be a regular file: $auction"}
        return created
    }
    fun ensureMenus(folder:File,resource:(String)->InputStream?) {
        menuFiles.forEach {name->writeMissing(target(folder,name)) {requireNotNull(resource(name)) {"Missing bundled resource: $name"}}}
    }
    /** Last successful load, used only for absent files during reload; no domain journals or secrets are captured. */
    fun capture(folder:File):Map<Path,ByteArray> = (dataFiles+menuFiles+"auction.yml").associate {name->
        val file=target(folder,name);file to Files.readAllBytes(file)
    }
    @Synchronized
    internal fun writeMissing(file:Path,source:()->InputStream):Boolean {
        if(Files.exists(file,NOFOLLOW_LINKS)) {
            require(Files.isRegularFile(file,NOFOLLOW_LINKS)) {"Configuration path must be a regular file: $file"}
            return false
        }
        Files.createDirectories(file.parent)
        val staged=Files.createTempFile(file.parent,".oneblock-config-",".tmp")
        try {
            source().use {input->Files.newOutputStream(staged).use {input.copyTo(it)}}
            try {Files.move(staged,file)} catch(_:FileAlreadyExistsException) {
                require(Files.isRegularFile(file,NOFOLLOW_LINKS)) {"Configuration path must be a regular file: $file"}
                return false
            }
            return true
        } finally {Files.deleteIfExists(staged)}
    }
}
