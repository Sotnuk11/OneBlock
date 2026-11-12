package com.alytanta.oneblock.tools

import com.alytanta.oneblock.config.ConfigSnapshot
import com.alytanta.oneblock.util.StrictYaml
import com.google.gson.GsonBuilder
import org.bukkit.configuration.file.YamlConfiguration
import java.nio.file.*
import java.security.MessageDigest
import java.util.logging.Logger

/** Merge presentation changes into public configuration copies. Never copies secrets or writes to the server. */
object VisualPolishInstallBuilder {
    @JvmStatic fun main(args:Array<String>) {
        require(args.size==1 && args[0].isNotBlank())
        val source=Path.of(args[0]).toAbsolutePath().normalize()
        val output=Path.of("build/visual-polish/install").toAbsolutePath().normalize()
        require(!output.startsWith(source) && !source.startsWith(output))
        val domain=output.resolve("plugins/one-block");Files.createDirectories(domain)
        val originals=linkedMapOf<Path,String>();val changed=mutableListOf<String>();val custom=mutableListOf<String>()
        fun hash(p:Path)=MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(p)).joinToString("") {"%02x".format(it)}
        fun copy(p:Path,to:Path) {originals[p]=hash(p);Files.createDirectories(to.parent);Files.copy(p,to,StandardCopyOption.REPLACE_EXISTING)}
        for(name in listOf("config","auction","combat","dungeon","guides","harvest","hub-generator","interface-menus","items","market-menus","profiles","progression","resource-pack","sidebar","trades")) {
            val p=source.resolve("$name.yml");if(Files.exists(p))copy(p,domain.resolve(p.fileName))
        }
        for((from,to) in listOf(source.resolve("lang") to domain.resolve("lang"),source.parent.resolve("menus") to output.resolve("plugins/menus"))) {
            Files.walk(from).use {paths->paths.filter {Files.isRegularFile(it) && it.toString().endsWith(".yml")}.forEach {copy(it,to.resolve(from.relativize(it)))}}
        }
        fun read(p:Path)=Files.readString(p).let {StrictYaml.validate(it);YamlConfiguration().apply {loadFromString(it)}}
        val backup=Path.of("build/backups/visual-polish-20261027/src/main/resources")
        for(relative in listOf("hub-generator.yml","dungeon.yml","items.yml","lang/uk.yml","lang/en.yml","menus/main-menu/layout.yml","menus/main-menu/statistics.yml")) {
            val old=read(backup.resolve(relative));val next=read(Path.of("src/main/resources").resolve(relative))
            val target=if(relative.startsWith("menus/"))output.resolve("plugins/$relative") else domain.resolve(relative)
            val candidate=read(target)
            for(key in next.getKeys(true).filter {!next.isConfigurationSection(it)}) {
                if(next.get(key)==old.get(key))continue
                if(candidate.get(key)==old.get(key) || !old.contains(key) && !candidate.contains(key)) {
                    candidate.set(key,next.get(key));changed+="$relative:$key"
                } else custom+="$relative:$key"
            }
            candidate.save(target.toFile());read(target)
        }
        val pack=read(domain.resolve("resource-pack.yml"));pack.set("sha1",Files.readString(Path.of("build/resource-pack/oneblock-26.1.2.zip.sha1")).trim())
        pack.save(domain.resolve("resource-pack.yml").toFile())
        val snapshot=ConfigSnapshot.read(domain.resolve("config.yml").toFile(),Logger.getAnonymousLogger())
        require(snapshot.inputsUnchanged(domain.resolve("config.yml").toFile()))
        require(snapshot.mainMenus!=null && snapshot.release?.customItems?.items?.size==28)
        // Every unrelated public leaf stays exactly the same; formatting may change in the selected files.
        val immutable=listOf("enabled","allow-instance-worlds","entry","boss","mobs","rewards","death-rewards","scaling","puzzle","npc")
        val before=read(source.resolve("dungeon.yml"));val after=read(domain.resolve("dungeon.yml"))
        for(root in immutable) {
            val keys=before.getKeys(true).filter {it==root || it.startsWith("$root.")}.filter {!before.isConfigurationSection(it)}
            keys.forEach {require(before.get(it)==after.get(it)) {"Non-presentation mine change: $it"}}
        }
        originals.forEach {(p,value)->require(hash(p)==value) {"Source changed during staging: $p"}}
        Files.copy(Path.of("build/resource-pack/oneblock-26.1.2.zip"),output.resolve("oneblock-26.1.2.zip"),StandardCopyOption.REPLACE_EXISTING)
        val report=mapOf("read-only-sources" to originals.mapKeys {it.key.toString()},"changed-presentation-leaves" to changed,
            "preserved-custom-leaves-review" to custom,"menus" to snapshot.mainMenus.menus.size,"items" to 28,"snapshot-valid" to true,"mine-mechanics-preserved" to true)
        Files.writeString(output.resolve("manifest.json"),GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create().toJson(report))
        println("Staged ${changed.size} presentation leaves, ${custom.size} custom leaves preserved, ${snapshot.mainMenus.menus.size} menus / 28 items validated. No server writes.")
    }
}
