package com.alytanta.oneblock.market

import com.alytanta.oneblock.menu.MenuFixture.Companion.stub
import io.papermc.paper.event.packet.UncheckedSignChangeEvent
import io.papermc.paper.math.Position
import io.papermc.paper.threadedregions.scheduler.EntityScheduler
import io.papermc.paper.threadedregions.scheduler.RegionScheduler
import io.papermc.paper.threadedregions.scheduler.ScheduledTask
import net.kyori.adventure.text.Component
import org.bukkit.*
import org.bukkit.block.Sign
import org.bukkit.block.data.BlockData
import org.bukkit.block.sign.Side
import org.bukkit.block.sign.SignSide
import org.bukkit.entity.Player
import org.bukkit.plugin.Plugin
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.util.UUID
import java.util.function.Consumer

class VirtualSignInputTest {
    private class Fixture {
        private val id = UUID.randomUUID()
        val worldId = UUID.randomUUID()
        private val world = stub(World::class.java) { name, _ -> when (name) {
            "getUID" -> worldId
            "getMaxHeight" -> 320
            "getName" -> "hub"
            "key", "getKey" -> NamespacedKey.minecraft("overworld")
            else -> error("Unexpected world access: $name")
        } }
        var currentWorld = world
        val lines = mutableMapOf<Int, Component>()
        private val side = stub(SignSide::class.java) { name, args ->
            if (name == "line" && args!!.size == 2) lines[args[0] as Int] = args[1] as Component
            null
        }
        private val sign = stub(Sign::class.java) { name, _ -> if (name == "getSide") side else null }
        private val data = stub(BlockData::class.java) { name, _ -> if (name == "createBlockState") sign else null }
        val packets = mutableListOf<String>()
        val replies = mutableListOf<String?>()
        private val callbacks = mutableListOf<Runnable>()
        private val timeouts = mutableListOf<Consumer<ScheduledTask>>()
        private val task = stub(ScheduledTask::class.java) { _, _ -> null }
        private val scheduler = stub(EntityScheduler::class.java) { name, args -> when (name) {
            "execute" -> { callbacks += args!![1] as Runnable; true }
            "runDelayed" -> {
                @Suppress("UNCHECKED_CAST")
                timeouts += args!![1] as Consumer<ScheduledTask>
                task
            }
            else -> null
        } }
        private val region = stub(RegionScheduler::class.java) { _, _ -> null } // Restoration is queued in the region, never a world mutation.
        private val server = stub(Server::class.java) { name, _ -> when (name) {
            "createBlockData" -> data
            "getRegionScheduler" -> region
            "isPrimaryThread" -> true
            else -> null
        } }
        private val plugin = stub(Plugin::class.java) { name, _ -> if (name == "getServer") server else null }
        val player = stub(Player::class.java) { name, _ -> when (name) {
            "getUniqueId" -> id
            "getLocation" -> Location(world, 10.5, 100.0, 20.5)
            "getWorld" -> currentWorld
            "getScheduler" -> scheduler
            "closeInventory", "sendBlockChange", "sendBlockUpdate", "openVirtualSign" -> { packets += name; null }
            else -> null
        } }
        val input = VirtualSignInput(plugin)
        fun open(customLines: List<Component>? = null, inputLine: Int = 0) {
            val field = Bukkit::class.java.getDeclaredField("server").apply { isAccessible = true }
            val previous = field.get(null)
            field.set(null, server)
            try {
                if (customLines == null) input.open(player, 12, listOf(Component.text("Price"), Component.text("1..100"), Component.text("Blank cancels")), 60, replies::add)
                else input.openLines(player, customLines, inputLine, 60, replies::add)
            }
            finally { field.set(null, previous) }
        }
        fun event(text: String, x: Int = 10, side: Side = Side.FRONT) = UncheckedSignChangeEvent(player,
            Position.block(x, 101, 20), side, listOf(Component.text(text), Component.text("999999"), Component.empty(), Component.empty()))
        fun drain() { callbacks.toList().also { callbacks.clear() }.forEach(Runnable::run) }
        fun expire() { timeouts.toList().forEach { it.accept(task) }; drain() }
    }

    @Test fun `client sign submits the first line once and never needs chat or real sign blocks`() {
        val fixture = Fixture()
        fixture.open()
        assertEquals(listOf("closeInventory", "sendBlockChange", "sendBlockUpdate", "openVirtualSign"), fixture.packets)
        assertEquals(Component.text(12), fixture.lines[0])
        val event = fixture.event(" 47 ")
        fixture.input.onSign(event)
        assertTrue(event.isCancelled)
        fixture.input.onSign(fixture.event("99"))
        fixture.drain()
        assertEquals(listOf("47"), fixture.replies)
        fixture.expire()
        assertEquals(listOf("47"), fixture.replies)
    }

    @Test fun `wrong coordinates side or world cannot submit a pending price`() {
        val fixture = Fixture()
        fixture.open()
        for (event in listOf(fixture.event("99", x = 11), fixture.event("99", side = Side.BACK))) {
            fixture.input.onSign(event); assertFalse(event.isCancelled)
        }
        fixture.currentWorld = stub(World::class.java) { name, _ -> when (name) {
            "getUID" -> fixture.worldId // Separate dimensions may share a UUID.
            "key", "getKey" -> NamespacedKey("oneblock", "island_test")
            else -> null
        } }
        val wrongWorld = fixture.event("99")
        fixture.input.onSign(wrongWorld); fixture.drain()
        assertFalse(wrongWorld.isCancelled)
        assertTrue(fixture.replies.isEmpty())
        fixture.currentWorld = fixture.player.location.world!!
        fixture.input.onSign(fixture.event("15")); fixture.drain()
        assertEquals(listOf("15"), fixture.replies)
    }

    @Test fun `empty reply timeout quit and explicit cancellation cannot leave a reusable prompt`() {
        val blank = Fixture().apply { open(); input.onSign(event(" ")); drain() }
        assertEquals(listOf<String?>(null), blank.replies)
        val timedOut = Fixture().apply { open(); expire(); input.onSign(event("50")); drain() }
        assertEquals(listOf<String?>(null), timedOut.replies)
        for (forget in listOf(false, true)) {
            val fixture = Fixture().apply { open() }
            if (forget) fixture.input.forget(fixture.player) else fixture.input.cancel(fixture.player)
            fixture.input.onSign(fixture.event("50")); fixture.expire()
            assertTrue(fixture.replies.isEmpty())
        }
    }

    @Test fun `only bounded whole numbers are accepted without rounding or overflow`() {
        assertEquals(1L, VirtualSignInput.number(" 1 ", 1, 100))
        assertEquals(100L, VirtualSignInput.number("100", 1, 100))
        for (text in listOf(null, "", "0", "101", "-1", "1.5", "1e2", "1 000", "cancel", "999999999999999999999999")) {
            assertNull(VirtualSignInput.number(text, 1, 100), text)
        }
    }

    @Test fun `configured editable row is read while all explanatory rows are ignored`() {
        val f = Fixture()
        f.open(listOf(Component.text("Label"), Component.empty(), Component.text("12"), Component.empty()), 2)
        assertEquals(Component.text("Label"), f.lines[0])
        assertEquals(Component.text("12"), f.lines[2])
        val event = UncheckedSignChangeEvent(f.player, Position.block(10, 101, 20), Side.FRONT,
            listOf(Component.text("Do not parse"), Component.text("999"), Component.text("45"), Component.empty()))
        f.input.onSign(event); f.drain()
        assertTrue(event.isCancelled)
        assertEquals(listOf("45"), f.replies)
    }
}
