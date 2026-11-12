package com.alytanta.oneblock.market

import org.bukkit.event.EventHandler
import org.bukkit.event.HandlerList
import org.bukkit.event.Listener
import org.bukkit.event.server.PluginDisableEvent
import org.bukkit.event.server.PluginEnableEvent
import org.bukkit.plugin.Plugin
import java.util.logging.Level

/** OneBlock loads at STARTUP; FancyNpcs may only become available at POSTWORLD. */
class FancyMarketIntegration(private val plugin: Plugin, private val createBridge: () -> Listener) : Listener {
    private var started = false
    private var dependency: Plugin? = null
    private var bridge: Listener? = null

    fun start() {
        if (started) return
        started = true
        val manager = plugin.server.pluginManager
        manager.registerEvents(this, plugin)
        val fancy = manager.getPlugin("FancyNpcs")
        when {
            fancy == null -> plugin.logger.warning("FancyNpcs is not installed: trading stays closed.")
            fancy.isEnabled -> attach(fancy)
            else -> plugin.logger.info("Waiting for FancyNpcs to enable before connecting NPC trading.")
        }
    }

    @EventHandler
    fun onPluginEnable(event: PluginEnableEvent) {
        if (event.plugin.name == "FancyNpcs") attach(event.plugin)
    }

    @EventHandler
    fun onPluginDisable(event: PluginDisableEvent) {
        if (event.plugin !== dependency) return
        bridge?.let { HandlerList.unregisterAll(it) }
        bridge = null
        dependency = null
        plugin.logger.warning("FancyNpcs disabled: NPC trading disconnected.")
    }

    private fun attach(fancy: Plugin) {
        if (bridge != null) return
        try {
            val listener = createBridge()
            plugin.server.pluginManager.registerEvents(listener, plugin)
            bridge = listener
            dependency = fancy
            plugin.logger.info("FancyNpcs integration ready: NPC clicks open trading menus.")
        } catch (error: LinkageError) {
            plugin.logger.log(Level.SEVERE, "Cannot connect FancyNpcs trading: incompatible NPC API.", error)
        } catch (error: Exception) {
            plugin.logger.log(Level.SEVERE, "Cannot register FancyNpcs trading listener.", error)
        }
    }
}
