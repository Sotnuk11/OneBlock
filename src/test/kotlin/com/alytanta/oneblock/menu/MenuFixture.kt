package com.alytanta.oneblock.menu

import io.papermc.paper.threadedregions.scheduler.EntityScheduler
import io.papermc.paper.threadedregions.scheduler.ScheduledTask
import org.bukkit.Material
import org.bukkit.Server
import org.bukkit.entity.Player
import org.bukkit.inventory.*
import org.bukkit.plugin.Plugin
import java.lang.reflect.Proxy
import java.util.UUID
import java.util.function.Consumer

/** Server-free item stand-in; inventory logic uses the same Bukkit methods as real stacks. */
internal class Stack(private val material: Material, private var count: Int = 1, val menu: Boolean = false,
    private val tag: String = "", private val limit: Int = 64) : ItemStack() {
    override fun getType() = material
    override fun getAmount() = count
    override fun setAmount(amount: Int) { count = amount }
    override fun getMaxStackSize() = limit
    override fun hasItemMeta() = menu || tag.isNotEmpty()
    override fun serializeAsBytes() = toString().toByteArray(Charsets.UTF_8)
    override fun isEmpty() = material in setOf(Material.AIR, Material.CAVE_AIR, Material.VOID_AIR) || count <= 0
    override fun clone() = Stack(material, count, menu, tag, limit)
    override fun isSimilar(other: ItemStack?) = other is Stack && material == other.material && menu == other.menu && tag == other.tag && limit == other.limit
    override fun equals(other: Any?) = other is Stack && isSimilar(other) && count == other.count
    override fun hashCode() = listOf(material, count, menu, tag, limit).hashCode()
    override fun toString() = "$material:$count:menu=$menu:$tag:limit=$limit"
}

internal class MenuFixture {
    val id: UUID = UUID.randomUUID()
    val slots = arrayOfNulls<ItemStack>(41)
    var cursor: ItemStack = Stack(Material.AIR, 0)
    var dead = false
    var online = true
    var held = 0
    var opened = 0
    var recipe: Recipe? = null
    val overflow = mutableListOf<ItemStack>()
    val scheduledDelays = mutableListOf<Long>()
    private val queued = mutableListOf<Runnable>()
    private inner class Repeating(val action: Consumer<ScheduledTask>) {
        var cancelled = false
        val task = stub(ScheduledTask::class.java) { name, _ -> if (name == "cancel") cancelled = true; null }
    }
    private val repeating = mutableListOf<Repeating>()
    val inventory: PlayerInventory = stub(PlayerInventory::class.java) { name, args ->
        fun index() = when (val slot = args!![0]) { is Int -> slot; EquipmentSlot.OFF_HAND -> 40; else -> held }
        when (name) {
            "getItem" -> slots[index()]
            "setItem" -> { slots[index()] = args!![1] as? ItemStack; null }
            "getSize" -> 41
            "getMaxStackSize" -> 64
            "getStorageContents" -> slots.copyOf(36)
            "getHolder" -> player
            "getHeldItemSlot" -> held
            "getItemInMainHand" -> slots[held] ?: Stack(Material.AIR, 0)
            "getItemInOffHand" -> slots[40] ?: Stack(Material.AIR, 0)
            else -> null
        }
    }
    private val scheduler: EntityScheduler = stub(EntityScheduler::class.java) { name, args ->
        when (name) {
            "execute" -> { queued += args!![1] as Runnable; true }
            "runDelayed" -> {
                @Suppress("UNCHECKED_CAST")
                val action = args!![1] as Consumer<ScheduledTask>
                scheduledDelays += args[3] as Long
                val task = stub(ScheduledTask::class.java) { _, _ -> null }
                queued += Runnable { action.accept(task) }
                task
            }
            "runAtFixedRate" -> {
                @Suppress("UNCHECKED_CAST")
                val repeat = Repeating(args!![1] as Consumer<ScheduledTask>)
                repeating += repeat
                repeat.task
            }
            else -> null
        }
    }
    val player: Player = stub(Player::class.java) { name, args ->
        when (name) {
            "getUniqueId" -> id
            "getInventory" -> inventory
            "isOnline" -> online
            "isDead" -> dead
            "getItemOnCursor" -> cursor
            "setItemOnCursor" -> { cursor = args!![0] as? ItemStack ?: Stack(Material.AIR, 0); null }
            "getScheduler" -> scheduler
            else -> null
        }
    }
    private val server = stub(Server::class.java) { name, _ -> if (name == "getRecipe") recipe else null }
    private val plugin = stub(Plugin::class.java) { name, _ -> if (name == "getServer") server else null }
    val controller = ReservedMenuSlot(plugin, ::isMenu, { icon() }, { _, item -> overflow += item }, { opened++ })

    fun drain() { val tasks = queued.toList(); queued.clear(); tasks.forEach(Runnable::run) }
    fun tick() { repeating.toList().filterNot { it.cancelled }.forEach { it.action.accept(it.task) }; drain() }
    fun activeWatches() = repeating.count { !it.cancelled }
    fun count(material: Material) = slots.filterNotNull().filter { it.type == material && !isMenu(it) }.sumOf { it.amount } +
        overflow.filter { it.type == material && !isMenu(it) }.sumOf { it.amount }
    fun fill() { for (slot in 0 until 36) slots[slot] = Stack(Material.COBBLESTONE, 64) }
    fun repair() { overflow += MenuInventory.repair(inventory, icon(), ::isMenu) }

    fun view(): InventoryView {
        val top = stub(Inventory::class.java) { _, _ -> null }
        return stub(InventoryView::class.java) { name, args ->
            when (name) {
                "getPlayer" -> player
                "getTopInventory" -> top
                "getBottomInventory" -> inventory
                "getInventory" -> if ((args!![0] as Int) >= 100) inventory else top
                "convertSlot" -> (args!![0] as Int).let { if (it >= 100) it - 100 else it }
                "getItem" -> (args!![0] as Int).let { if (it >= 100) slots[it - 100] else null }
                "getCursor" -> cursor
                else -> null
            }
        }
    }

    companion object {
        fun icon(amount: Int = 1) = Stack(Material.NETHER_STAR, amount, menu = true)
        fun isMenu(stack: ItemStack?) = (stack as? Stack)?.menu == true
        fun <T> stub(type: Class<T>, handler: (String, Array<out Any?>?) -> Any?): T = type.cast(
            Proxy.newProxyInstance(type.classLoader, arrayOf(type)) { proxy, method, args ->
                when (method.name) {
                    "equals" -> proxy === args?.get(0)
                    "hashCode" -> System.identityHashCode(proxy)
                    "toString" -> type.simpleName
                    else -> handler(method.name, args)
                }
            }
        )
    }
}
