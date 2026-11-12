package com.alytanta.oneblock.tools

import com.alytanta.oneblock.items.*
import com.alytanta.oneblock.combat.CombatRules
import com.alytanta.oneblock.util.StrictYaml
import org.bukkit.configuration.file.YamlConfiguration
import java.nio.file.*
import java.security.MessageDigest
import com.google.gson.GsonBuilder

/** Explicit offline preparation. Only public item/menu definitions are read; output is always in this checkout's build/. */
object CustomItemsInstallBuilder {
    @JvmStatic fun main(args:Array<String>) {
        require(args.size==1 && args[0].isNotBlank()) {"Supply -PitemConfigs=<plugins/one-block directory>; no server writes are performed"}
        val source=Path.of(args[0]).toAbsolutePath().normalize()
        val output=Path.of("build/custom-items-install").toAbsolutePath().normalize()
        require(!output.startsWith(source) && !source.startsWith(output))
        val domain=output.resolve("plugins/one-block");Files.createDirectories(domain)
        fun read(path:Path):YamlConfiguration {val text=Files.readString(path);StrictYaml.validate(text);return YamlConfiguration().apply {loadFromString(text)}}
        val originals=linkedMapOf<String,String>()
        fun hash(path:Path)=MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path)).joinToString("") {"%02x".format(it)}
        fun input(path:Path):YamlConfiguration {originals[path.toString()]=hash(path);return read(path)}
        val combat=input(source.resolve("combat.yml"));val progression=input(source.resolve("progression.yml"));val locale=input(source.resolve("lang/uk.yml"))
        val existingItems=input(source.resolve("items.yml"))
        val hubSettings=input(source.resolve("hub-generator.yml"))
        val catalog=read(Path.of("src/main/resources/items.yml"))
        val ids=catalog.getConfigurationSection("items")!!.getKeys(false)
        val oldIds=combat.getConfigurationSection("items")!!.getKeys(false)
        require(oldIds.all {"oneblock:$it" in ids}) {"Existing combat ID has no bundled artwork; add explicit definition/assets first"}
        for(id in oldIds) {
            val path="items.oneblock:$id";val old=combat.getConfigurationSection("items.$id")!!
            val armor=old.getString("kind")=="ARMOR"
            // Armor carriers are intentionally migrated to leather/PLAYER_HEAD; never copy the old metal carrier back.
            for(key in listOf("material","kind","name","lore","slot","set"))if(old.contains(key) && !(armor && key=="material"))catalog.set("$path.$key",old.get(key))
            catalog.getConfigurationSection("$path.stats")?.getKeys(false)?.forEach {key->catalog.set("$path.stats.$key",old.getDouble(key))}
            if(armor)for(key in listOf("defense","mana","magic"))if(old.contains(key))catalog.set("$path.stats.$key",old.getDouble(key))
        }
        // Preserve the current catalog's public settings, while always replacing the old armor carrier/model presentation.
        for(key in listOf("models","equipment-models")) {
            val merged=(catalog.getStringList(key)+existingItems.getStringList(key)).distinct()
            catalog.set(key,merged)
        }
        existingItems.getConfigurationSection("abilities")?.getKeys(false)?.forEach {ability->
            catalog.set("abilities.$ability",existingItems.get("abilities.$ability"))
        }
        existingItems.getConfigurationSection("items")?.getKeys(false)?.forEach {rawId->
            val id=ItemCatalog.canonical(rawId);if(id !in ids)return@forEach
            val old=existingItems.getConfigurationSection("items.$rawId")!!;val path="items.$id"
            val armor=old.getString("kind")=="ARMOR"
            val fields=if(armor)listOf("kind","name","lore","rarity","tags","stats","abilities","slot","set","stack-size","market-transfer","vanilla-repair")
                else listOf("material","kind","name","lore","model","rarity","tags","stats","abilities","slot","set","equipment-model","stack-size","market-transfer","vanilla-repair")
            fields.filter { key -> old.contains(key) }.forEach {key->catalog.set("$path.$key",old.get("$key"))}
        }
        existingItems.getConfigurationSection("sets")?.getKeys(false)?.forEach {set->
            val values=existingItems.getConfigurationSection("sets.$set")!!
            for(key in listOf("defense","mana","magic"))if(values.contains(key))catalog.set("sets.$set.$key",values.getDouble(key))
        }
        if(existingItems.getConfigurationSection("sets")==null)combat.getConfigurationSection("sets")?.getKeys(false)?.forEach {set->
            val values=combat.getConfigurationSection("sets.$set")!!
            for(key in listOf("defense","mana","magic"))if(values.contains(key))catalog.set("sets.$set.$key",values.getDouble(key))
        }
        val tools=linkedSetOf<String>()
        progression.getConfigurationSection("recipes")!!.getKeys(false).forEach {recipe->
            val r=progression.getConfigurationSection("recipes.$recipe")!!
            r.getMapList("items").filter {it["tool"]!=null}.forEach {item->
                val id=item["tool"].toString();require("oneblock:$id" in ids) {"Existing tool $id needs a definition and artwork"};tools+=id
                val path="items.oneblock:$id";catalog.set("$path.material",item["material"].toString())
                locale.getString("release.name.$id")?.let {catalog.set("$path.name","&a$it")}
                for(stat in listOf("durability","sapling"))catalog.set("$path.stats.$stat",r.getDouble(stat))
            }
        }
        val catalogFile=domain.resolve("items.yml").toFile();catalog.save(catalogFile)
        val parsed=ItemCatalog.read(catalogFile).first!!
        val old=CombatRules.read(source.resolve("combat.yml").toFile()).first!!
        val merged=CombatRules.read(source.resolve("combat.yml").toFile(),parsed,tools).first!!
        for((id,item) in old.items) {val next=merged.items.getValue(id)
            require((item.kind==com.alytanta.oneblock.combat.CombatKind.ARMOR || item.material==next.material) && item.kind==next.kind && item.damage==next.damage && item.multiplier==next.multiplier && item.mana==next.mana && item.cooldown==next.cooldown && item.speed==next.speed && item.defense==next.defense && item.manaBonus==next.manaBonus && item.magicBonus==next.magicBonus) {"Balance changed for $id"}}
        require(old.recipes.keys==merged.recipes.keys && old.recipes.all {(id,r)->r.copy(items=merged.recipes.getValue(id).items)==merged.recipes.getValue(id) && r.items.map {it.tool to it.amount}==merged.recipes.getValue(id).items.map {it.tool to it.amount}})
        val pack=read(Path.of("src/main/resources/resource-pack.yml"));pack.set("sha1",Files.readString(Path.of("build/resource-pack/oneblock-26.1.2.zip.sha1")).trim())
        pack.save(domain.resolve("resource-pack.yml").toFile());ResourcePackSettings.read(domain.resolve("resource-pack.yml").toFile())
        for(group in listOf("combat","release")) {
            val path=source.parent.resolve("menus/$group/layout.yml");val y=input(path)
            y.getConfigurationSection("menus")!!.getKeys(false).forEach {menu->
                val slots=y.getConfigurationSection("menus.$menu.slots")?:return@forEach
                slots.getKeys(false).forEach {slot->val s=slots.getConfigurationSection(slot)?:return@forEach
                    val cmd=s.getString("command").orEmpty()
                    val id=when {cmd.startsWith("/obmenu combat-craft-")->cmd.removePrefix("/obmenu combat-craft-")
                        cmd.startsWith("/obmenu release-recipe-")->cmd.removePrefix("/obmenu release-recipe-");else->null}
                    if(id!=null && parsed.find(id)!=null)s.set("custom-item",ItemCatalog.canonical(id))
                }
            }
            val target=output.resolve("plugins/menus/$group/layout.yml");Files.createDirectories(target.parent);y.save(target.toFile());read(target)
        }
        val combatPrepared=read(source.resolve("combat.yml"));combatPrepared.set("sets",null);combatPrepared.set("items",null)
        combatPrepared.save(domain.resolve("combat.yml").toFile());CombatRules.read(domain.resolve("combat.yml").toFile(),parsed,tools)
        hubSettings.set("messages.uk.next-job","&aСпоруду завершено. Перевір /obadmin hub services {job}; активуй /obadmin hub activate {job}. NPC розмісти вручну: /obadmin market npc shop|bazaar|auction|guide|dungeon.")
        hubSettings.set("messages.en.next-job","&aBuilding finished. Check /obadmin hub services {job}, then /obadmin hub activate {job}. Place NPCs manually: /obadmin market npc shop|bazaar|auction|guide|dungeon.")
        hubSettings.set("messages.uk.services","&eСервіси: {value}. Генератор не створює і не переміщує NPC; їх ставить адміністратор через /obadmin market npc <роль>.")
        hubSettings.set("messages.en.services","&eServices: {value}. Hub generation never creates or moves NPCs; an administrator places them with /obadmin market npc <role>.")
        val bundledHub=read(Path.of("src/main/resources/hub-generator.yml"))
        for(role in listOf("merchant","bazaar","auction","guide"))if(!hubSettings.isConfigurationSection("npcs.$role"))
            hubSettings.set("npcs.$role",bundledHub.get("npcs.$role"))
        hubSettings.save(domain.resolve("hub-generator.yml").toFile())
        require(com.alytanta.oneblock.hub.generator.HubGeneratorSettings.read(domain.resolve("hub-generator.yml").toFile()).first!=null)
        Files.copy(Path.of("build/resource-pack/oneblock-26.1.2.zip"),output.resolve("oneblock-26.1.2.zip"),StandardCopyOption.REPLACE_EXISTING)
        val json=GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create()
        Files.writeString(output.resolve("source-manifest.json"),json.toJson(mapOf("read-only-sources" to originals,"items" to parsed.items.size,"balance-preserved" to true,"vanilla-armor" to true,"delivery-enabled" to false,"required" to false))+"\n")
        originals.forEach {(path,before)->require(hash(Path.of(path))==before)}
        println("Prepared $output: ${parsed.items.size} items, armor carriers migrated with configured compensation, balance/recipes verified, optional resource pack. Server files unchanged.")
    }
}
