package com.alytanta.oneblock.hub

import com.alytanta.oneblock.config.PluginConfig
import com.alytanta.oneblock.island.IslandService
import com.alytanta.oneblock.locale.LocaleService
import com.alytanta.oneblock.menu.MenuFixture.Companion.stub
import com.alytanta.oneblock.player.PlayerService
import com.alytanta.oneblock.storage.StorageService
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.plugin.Plugin
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.logging.Logger

class HubPersistenceTest {
    @TempDir lateinit var folder:Path
    private fun fixture():Pair<HubService,YamlConfiguration> {
        val yaml=YamlConfiguration().apply {set("hub.world","test:previous");set("hub.use-world-spawn",false);set("hub.rules.pvp",false);set("custom.text","keep");set("market.custom-price",123)}
        yaml.save(folder.resolve("config.yml").toFile())
        val storage=stub(StorageService::class.java) {name,_->when(name){"loadIslands","loadProfiles"->emptyList<Any>();else->null}}
        val logger=Logger.getAnonymousLogger()
        val plugin=stub(Plugin::class.java) {name,_->when(name){"getDataFolder"->folder.toFile();"getConfig"->yaml;"getLogger"->logger;else->null}}
        val islands=IslandService(plugin,PluginConfig.load(yaml,logger),storage)
        return HubService(plugin,HubSettings.load(yaml),islands,LocaleService(plugin,"en",PlayerService(storage))) to yaml
    }
    @Test fun `explicit hub persistence preserves externally edited unrelated configuration and publishes only after success`() {
        val (hub,memory)=fixture();val disk=folder.resolve("config.yml").toFile()
        val changed=YamlConfiguration.loadConfiguration(disk).apply {set("custom.text","external-new-value")};changed.save(disk)
        val next=HubSettings("test:explicit",false,10.5,101.0,-20.5,90f,0f)
        hub.persist(next)
        val stored=YamlConfiguration.loadConfiguration(disk)
        assertEquals(next,hub.currentSettings());assertEquals("test:explicit",memory.getString("hub.world"))
        assertEquals("external-new-value",stored.getString("custom.text"));assertEquals(123,stored.getInt("market.custom-price"));assertFalse(stored.getBoolean("hub.rules.pvp"))
    }
    @Test fun `failed config write and invalid destination leave previous hub and file intact`() {
        val (hub,memory)=fixture();val before=Files.readString(folder.resolve("config.yml"));val original=hub.currentSettings()
        assertThrows(IllegalArgumentException::class.java) {hub.persist(original.copy(x=Double.NaN))}
        Files.createDirectory(folder.resolve("config.yml.hub.tmp"))
        assertThrows(java.io.IOException::class.java) {hub.persist(original.copy(world="test:new"))}
        assertEquals(original,hub.currentSettings());assertEquals("test:previous",memory.getString("hub.world"));assertEquals(before,Files.readString(folder.resolve("config.yml")))
    }
    @Test fun `new hub guide pages supplement old configs in memory and can be disabled`() {
        val source=javaClass.getResourceAsStream("/guides.yml")!!.reader().use {it.readText()}
        val file=folder.resolve("guides.yml").toFile();file.writeText(source.replace("hub-generator-help: true\n",""))
        val before=file.readText();val guide=com.alytanta.oneblock.guide.GuideSettings.read(file).first!!
        assertTrue(guide.topics.single {it.id=="hub"}.pages.contains("guides.body.hub-generation"));assertEquals(before,file.readText())
        file.writeText(source.replace("hub-generator-help: true","hub-generator-help: false"))
        assertEquals(listOf("guides.body.hub","guides.body.mine-hub"),com.alytanta.oneblock.guide.GuideSettings.read(file).first!!.topics.single {it.id=="hub"}.pages)
        file.appendText("\nmine-help: false\n")
        assertEquals(listOf("guides.body.hub"),com.alytanta.oneblock.guide.GuideSettings.read(file).first!!.topics.single {it.id=="hub"}.pages)
        val full=YamlConfiguration().apply {loadFromString(source);set("topics.hub.pages",List(16) {"guides.body.custom_$it"})}
        full.save(file)
        assertEquals(16,com.alytanta.oneblock.guide.GuideSettings.read(file).first!!.topics.single {it.id=="hub"}.pages.size)
    }
    @Test fun `rollback permission cannot be bypassed by command casing`() {
        val (hub,_)=fixture();val replies=mutableListOf<net.kyori.adventure.text.Component>()
        val plugin=stub(Plugin::class.java) {name,_->if(name=="getDataFolder")folder.toFile() else null}
        val settings=com.alytanta.oneblock.hub.generator.HubGeneratorSettings.read(folder.resolve("absent.yml").toFile()).first
        val controller=com.alytanta.oneblock.hub.generator.HubGeneratorController(plugin,settings,hub,{error("unused")},{_,_->})
        val sender=stub(org.bukkit.command.CommandSender::class.java) {name,args->when(name) {
            "isOp"->true;"hasPermission"->args!![0]!="oneblock.admin.reset";"getName"->"console-test"
            "sendMessage"->{replies+=args!![0] as net.kyori.adventure.text.Component;null};else->null
        } }
        try {
            controller.engine.ready.get(5,java.util.concurrent.TimeUnit.SECONDS)
            listOf("rollback","ROLLBACK","Rollback").forEach {controller.handle(sender,listOf(it,java.util.UUID.randomUUID().toString(),"confirm"))}
            assertEquals(List(3) {com.alytanta.oneblock.util.Colors.colorize(settings.message("denied"))},replies)
            assertTrue(controller.engine.all().isEmpty())
        } finally {controller.close()}
    }
}
