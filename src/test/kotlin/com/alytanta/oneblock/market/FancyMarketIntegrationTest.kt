package com.alytanta.oneblock.market

import org.bukkit.Server
import org.bukkit.Bukkit
import org.bukkit.event.Listener
import org.bukkit.event.server.PluginDisableEvent
import org.bukkit.event.server.PluginEnableEvent
import org.bukkit.plugin.Plugin
import org.bukkit.plugin.PluginManager
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.lang.reflect.Proxy
import java.util.logging.Logger

class FancyMarketIntegrationTest {
    private class Fixture(installed: Boolean = true, var enabled: Boolean = false) {
        val registered = mutableListOf<Listener>()
        var creations = 0
        private val logger = Logger.getAnonymousLogger().apply { useParentHandlers = false }
        val fancy: Plugin = stub(Plugin::class.java) { name, _ ->
            when (name) { "getName" -> "FancyNpcs"; "isEnabled" -> enabled; else -> null }
        }
        private val manager: PluginManager = stub(PluginManager::class.java) { name, args ->
            when (name) {
                "getPlugin" -> if (installed) fancy else null
                "registerEvents" -> { registered += args!![0] as Listener; null }
                else -> null
            }
        }
        private val server: Server = stub(Server::class.java) { name, _ ->
            when (name) { "getPluginManager" -> manager; "isPrimaryThread" -> true; else -> null }
        }
        private val owner: Plugin = stub(Plugin::class.java) { name, _ ->
            when (name) { "getName" -> "one-block"; "getServer" -> server; "getLogger" -> logger; else -> null }
        }
        val integration = FancyMarketIntegration(owner) { creations++; object : Listener {} }

        // Paper's ServerEvent constructor consults Bukkit even when the manager is injected.
        private fun event(action: () -> Unit) {
            val field = Bukkit::class.java.getDeclaredField("server").apply { isAccessible = true }
            val previous = field.get(null)
            field.set(null, server)
            try { action() } finally { field.set(null, previous) }
        }
        fun enable() = event { integration.onPluginEnable(PluginEnableEvent(fancy)) }
        fun disable() = event { integration.onPluginDisable(PluginDisableEvent(fancy)) }
    }

    @Test fun `POSTWORLD dependency attaches after STARTUP and duplicate enable does not duplicate clicks`() {
        val fixture = Fixture()
        fixture.integration.start()
        assertEquals(0, fixture.creations)
        fixture.enabled = true
        fixture.enable()
        fixture.enable()
        fixture.integration.start()
        assertEquals(1, fixture.creations)
        assertEquals(2, fixture.registered.size)
    }

    @Test fun `already enabled dependency attaches immediately and can reattach after disable`() {
        val fixture = Fixture(enabled = true)
        fixture.integration.start()
        assertEquals(1, fixture.creations)
        fixture.disable()
        fixture.enable()
        assertEquals(2, fixture.creations)
    }

    @Test fun `absent dependency does not load optional NPC API`() {
        val fixture = Fixture(installed = false)
        fixture.integration.start()
        assertEquals(0, fixture.creations)
        assertEquals(1, fixture.registered.size)
    }

    companion object {
        private fun <T> stub(type: Class<T>, handler: (String, Array<out Any?>?) -> Any?): T = type.cast(
            Proxy.newProxyInstance(type.classLoader, arrayOf(type)) { proxy, method, args ->
                when (method.name) {
                    "hashCode" -> System.identityHashCode(proxy)
                    "equals" -> proxy === args?.get(0)
                    "toString" -> type.simpleName
                    else -> handler(method.name, args)
                }
            }
        )
    }
}
