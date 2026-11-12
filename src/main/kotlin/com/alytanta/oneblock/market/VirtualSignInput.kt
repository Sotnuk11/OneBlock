package com.alytanta.oneblock.market

import com.alytanta.oneblock.util.FoliaTasks
import io.papermc.paper.event.packet.UncheckedSignChangeEvent
import io.papermc.paper.math.BlockPosition
import io.papermc.paper.math.Position
import io.papermc.paper.threadedregions.scheduler.ScheduledTask
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.block.TileState
import org.bukkit.block.Sign
import org.bukkit.block.sign.Side
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.plugin.Plugin
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** Client-only sign. Never places or edits a block in the hub. Every reply is bound to one request. */
class VirtualSignInput(private val plugin: Plugin) : Listener {
    private data class Pending(val position: BlockPosition, val location: Location, val inputLine: Int, val reply: (String?) -> Unit) {
        @Volatile var expiry: ScheduledTask? = null
    }
    private val pending = ConcurrentHashMap<UUID, Pending>()

    fun open(player: Player, value: Long, hints: List<Component>, timeoutTicks: Long, reply: (String?) -> Unit) {
        openLines(player, (listOf(Component.text(value)) + hints).take(4), 0, timeoutTicks, reply)
    }

    fun openLines(player: Player, lines: List<Component>, inputLine: Int, timeoutTicks: Long, reply: (String?) -> Unit) {
        require(lines.size == 4 && inputLine in 0..3)
        cancel(player)
        // Same chunk as the entity: all world reads/writes below belong to its Folia region.
        val location = player.location.toBlockLocation().apply { y = (blockY + 1).coerceAtMost(world!!.maxHeight - 1).toDouble() }
        val request = Pending(Position.block(location), location, inputLine, reply)
        pending[player.uniqueId] = request
        try {
            player.closeInventory()
            val data = Material.OAK_SIGN.createBlockData()
            val sign = data.createBlockState() as Sign
            lines.forEachIndexed { index, line -> sign.getSide(Side.FRONT).line(index, line) }
            player.sendBlockChange(location, data)
            player.sendBlockUpdate(location, sign)
            player.openVirtualSign(request.position, Side.FRONT)
            request.expiry = player.scheduler.runDelayed(plugin, {
                if (pending.remove(player.uniqueId, request)) { restore(player, request); request.reply(null) }
            }, { pending.remove(player.uniqueId, request) }, timeoutTicks.coerceAtLeast(1))
        } catch (e: Exception) {
            if (pending.remove(player.uniqueId, request)) restore(player, request)
            throw e
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    fun onSign(event: UncheckedSignChangeEvent) {
        val request = pending[event.player.uniqueId] ?: return
        if (!matches(request.location.world!!.key().asString(), request.position, event.player.world.key().asString(), event.editedBlockPosition, event.side)) return
        // Prevent the fake sign packet from ever reaching a real block or SignChangeEvent.
        event.isCancelled = true
        if (!pending.remove(event.player.uniqueId, request)) return
        request.expiry?.cancel()
        val text = event.lines().getOrNull(request.inputLine)?.let { PlainTextComponentSerializer.plainText().serialize(it) }.orEmpty().trim()
        restore(event.player, request)
        FoliaTasks.entity(plugin, event.player) { request.reply(text.takeUnless(String::isBlank)) }
    }

    fun cancel(player: Player) { pending.remove(player.uniqueId)?.let { it.expiry?.cancel(); restore(player, it) } }
    fun forget(player: Player) { pending.remove(player.uniqueId)?.expiry?.cancel() }
    fun cancelAll() {
        val requests = pending.toMap()
        requests.forEach { (id, request) ->
            if (pending.remove(id, request)) {
                request.expiry?.cancel()
                plugin.server.getPlayer(id)?.let { player -> FoliaTasks.entity(plugin, player) { restore(player, request) } }
            }
        }
    }

    private fun restore(player: Player, request: Pending) {
        // Fetch the CURRENT block state, so simultaneous building cannot be reverted even visually.
        FoliaTasks.region(plugin, request.location) {
            val state = request.location.block.state
            FoliaTasks.entity(plugin, player) {
                if (player.world.key() == request.location.world?.key()) {
                    // An older cancellation must not erase a newly opened sign at the same position.
                    if (pending[player.uniqueId]?.location == request.location) return@entity
                    player.sendBlockChange(request.location, state.blockData)
                    if (state is TileState) player.sendBlockUpdate(request.location, state)
                }
            }
        }
    }

    companion object {
        internal fun matches(expectedWorld: String, expected: BlockPosition, world: String, actual: BlockPosition, side: Side) =
            expectedWorld == world && expected.blockX() == actual.blockX() && expected.blockY() == actual.blockY() &&
                expected.blockZ() == actual.blockZ() && side == Side.FRONT

        internal fun number(text: String?, min: Long, max: Long): Long? {
            val match = Regex("^([0-9]+(?:[.,][0-9]+)?)(k{1,3}|m|b)?$", RegexOption.IGNORE_CASE)
                .matchEntire(text?.trim() ?: return null) ?: return null
            val power = when (match.groupValues[2].lowercase()) { "k" -> 3; "kk", "m" -> 6; "kkk", "b" -> 9; else -> 0 }
            return runCatching {
                java.math.BigDecimal(match.groupValues[1].replace(',', '.')).scaleByPowerOfTen(power).longValueExact()
            }.getOrNull()?.takeIf { it in min..max }
        }
    }
}
