package com.alytanta.oneblock.command

import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import org.bukkit.configuration.file.YamlConfiguration
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.lang.reflect.Proxy

class CommandAccessTest {
    private fun sender(op:Boolean,permissions:Set<String>,player:Boolean=true):CommandSender = Proxy.newProxyInstance(
        javaClass.classLoader,arrayOf(if(player)Player::class.java else CommandSender::class.java)
    ) {_,method,args->when(method.name) {
        "isOp"->op
        "hasPermission"->args!![0] in permissions
        "toString"->"test sender"
        else->throw UnsupportedOperationException(method.name)
    }} as CommandSender
    @Test fun `ordinary player and non OP permission grants cannot enter admin routes`() {
        for(p in listOf(emptySet(),setOf("oneblock.admin","oneblock.admin.manage","oneblock.admin.reset"))) {
            val user=sender(false,p)
            for(route in listOf("hub","hubgen","dungeon","save","reload","menu","help"))assertFalse(CommandAccess.allowed(user,listOf(route)))
            assertTrue(CommandAccess.visibleAdmin(user).isEmpty())
        }
    }
    @Test fun `read only OP sees inspection but cannot mutate hub players or islands`() {
        val user=sender(true,setOf("oneblock.admin","oneblock.admin.inspect","oneblock.command"))
        assertTrue(CommandAccess.allowed(user,listOf("dungeon","info")))
        assertTrue(CommandAccess.allowed(user,listOf("island","info")))
        for(route in listOf(listOf("hub","preview"),listOf("hubgen","world","create"),listOf("dungeon","stop"),listOf("island","set"),listOf("save")))assertFalse(CommandAccess.allowed(user,route))
        assertFalse("hub" in CommandAccess.visibleAdmin(user))
        assertTrue("players" in CommandAccess.visibleAdmin(user))
    }
    @Test fun `manager without inspect still discovers permitted actions and cannot rollback`() {
        val user=sender(true,setOf("oneblock.admin","oneblock.admin.manage"))
        assertTrue("island" in CommandAccess.visibleAdmin(user))
        assertTrue("dungeon" in CommandAccess.visibleAdmin(user))
        assertTrue(CommandAccess.allowed(user,listOf("hub","build")))
        assertFalse(CommandAccess.allowed(user,listOf("hub","rollback")))
        assertFalse(CommandAccess.allowed(user,listOf("island","tp")))
        assertFalse(CommandAccess.allowed(user,listOf("reload")))
    }
    @Test fun `reset alone cannot roll back hub and recovery remains console only`() {
        val reset=sender(true,setOf("oneblock.admin","oneblock.admin.reset"))
        assertFalse(CommandAccess.allowed(reset,listOf("hubgen","rollback")))
        val permissions=setOf("oneblock.admin","oneblock.admin.manage","oneblock.admin.reset")
        assertTrue(CommandAccess.allowed(sender(true,permissions),listOf("hub","rollback")))
        assertFalse(CommandAccess.allowed(sender(true,permissions),listOf("profiles","resolve")))
        assertFalse(CommandAccess.allowed(sender(true,permissions),listOf("trades","review")))
        assertTrue(CommandAccess.allowed(sender(true,permissions,false),listOf("trades","review")))
    }
    @Test fun `player suggestions follow command and trade grants and never contain admin routes`() {
        val configured=setOf("home","help","trade","admin","reload","hubgen","collections")
        assertEquals(listOf("help","home"),CommandAccess.playerRoutes(configured,sender(false,setOf("oneblock.command"))))
        assertTrue(CommandAccess.playerRoutes(configured,sender(false,emptySet())).isEmpty())
        assertEquals(listOf("help","home","trade"),CommandAccess.playerRoutes(configured,sender(false,setOf("oneblock.command","oneblock.trade"))))
    }
    @Test fun `registered admin command is permission guarded while player root remains public`() {
        val yaml=YamlConfiguration().apply {loadFromString(javaClass.getResourceAsStream("/plugin.yml")!!.reader().readText())}
        assertEquals("oneblock.admin",yaml.getString("commands.obadmin.permission"))
        assertEquals("oneblock.command",yaml.getString("commands.oneblock.permission"))
        assertEquals("op",yaml.getString("permissions.oneblock.admin.default"))
        assertEquals(true,yaml.getBoolean("permissions.oneblock.command.default"))
    }
    @Test fun `root visibility filters own namespace and aliases without touching other plugins`() {
        val user=sender(false,setOf("oneblock.command","oneblock.admin","oneblock.admin.reload"))
        for(label in listOf("obadmin","oba","one-block:obadmin","obreload","oneblockreload","obrestart"))assertFalse(CommandAccess.visibleRoot(user,label),label)
        for(label in listOf("ob","home","hub","dungeon","mine","other:obadmin","other:reload"))assertTrue(CommandAccess.visibleRoot(user,label),label)
        val inspector=sender(true,setOf("oneblock.admin","oneblock.admin.inspect"))
        assertTrue(CommandAccess.visibleRoot(inspector,"obadmin"));assertFalse(CommandAccess.visibleRoot(inspector,"obreload"))
        assertFalse(CommandAccess.visibleRoot(user,"trade"))
    }
    @Test fun `real completer denies manual hidden branches before enumerating private accounts`() {
        fun <T> proxy(type:Class<T>,answer:(String)->Any?):T = type.cast(Proxy.newProxyInstance(javaClass.classLoader,arrayOf(type)) {_,m,_->answer(m.name)})
        val plugin=proxy(org.bukkit.plugin.Plugin::class.java) {error("Unexpected plugin access: $it")}
        val storage=proxy(com.alytanta.oneblock.storage.StorageService::class.java) {if(it=="loadIslands")emptyList<com.alytanta.oneblock.island.Island>() else error("Unexpected storage access: $it")}
        val yaml=YamlConfiguration().apply {loadFromString(javaClass.getResourceAsStream("/config.yml")!!.reader().readText())}
        val config=com.alytanta.oneblock.config.PluginConfig.load(yaml,java.util.logging.Logger.getAnonymousLogger())
        val islands=com.alytanta.oneblock.island.IslandService(plugin,config,storage)
        val tab=OneBlockTabCompleter(config,islands)
        val command=object:org.bukkit.command.Command("obadmin") {override fun execute(s:CommandSender,l:String,a:Array<out String>)=true}
        val user=sender(true,setOf("oneblock.admin","oneblock.admin.inspect","oneblock.command"))
        for(route in listOf("market","delete","kick","profiles","trades","hub","hubgen"))assertTrue(tab.onTabComplete(user,command,"obadmin",arrayOf(route,"")).isEmpty(),route)
        val visible=tab.onTabComplete(user,command,"obadmin",arrayOf("island",""))
        assertTrue("info" in visible);assertFalse("set" in visible)
        val nonOp=sender(false,setOf("oneblock.command","oneblock.admin"))
        assertTrue(tab.onTabComplete(nonOp,command,"obadmin",arrayOf("")).isEmpty())
    }
}
