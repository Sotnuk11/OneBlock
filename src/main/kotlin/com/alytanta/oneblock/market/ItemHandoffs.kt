package com.alytanta.oneblock.market

import com.alytanta.oneblock.island.IslandService
import com.alytanta.oneblock.locale.Lang
import com.alytanta.oneblock.locale.LocaleService
import com.alytanta.oneblock.storage.PostgresStorageService
import com.alytanta.oneblock.util.FoliaTasks
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataType
import org.bukkit.plugin.Plugin
import java.nio.ByteBuffer
import com.alytanta.oneblock.items.CustomProducts
import java.security.MessageDigest
import java.util.UUID

/** DB intent -> entity-owned inventory+PDC save -> DB finalize. Ambiguous recovery is quarantined. */
class ItemHandoffs(private val plugin: Plugin, private val storage: PostgresStorageService,
    private val islands: IslandService, private val engine: () -> MarketEngine, private val locales: LocaleService,
    private val plainStack: (Material, Int) -> ItemStack = { material, amount -> ItemStack(material, amount) },
    private val decodeItem: (String) -> ItemStack = AuctionItems::decode,
    private val changed: () -> Unit = {}) {
    private val marker = NamespacedKey(plugin, "market_handoff")
    private fun hash(contents: Array<ItemStack?>): String {
        val digest = MessageDigest.getInstance("SHA-256")
        contents.forEach { item ->
            val bytes = if (item == null || item.isEmpty) ByteArray(0) else item.serializeAsBytes()
            digest.update(ByteBuffer.allocate(4).putInt(bytes.size).array()); digest.update(bytes)
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
    private fun plan(player: Player, key: String, amount: Int, withdraw: Boolean,
        itemData: String? = null, sourceSlot: Int? = null): Array<ItemStack?> {
        val material = requireNotNull(CustomProducts.material(key))
        val custom = CustomProducts.custom(key)
        val contents = player.inventory.storageContents.map { it?.clone() }.toTypedArray()
        val exact = itemData?.let(decodeItem)
        if (exact != null) require(exact.type == material && exact.amount == amount)
        var remaining = amount
        if (!withdraw) {
            if (exact != null) {
                val slot = sourceSlot ?: throw MarketException("items")
                return ItemTransferPlan.deposit(contents, slot, exact)
            }
            for (slot in contents.indices) {
                val item = contents[slot] ?: continue
                if (slot == com.alytanta.oneblock.menu.MenuInventory.SLOT || if (custom) !CustomProducts.matches(key, item) else item.type != material || item.hasItemMeta()) continue
                val take = minOf(item.amount, remaining); remaining -= take
                if (take == item.amount) contents[slot] = null else item.amount -= take
                if (remaining == 0) break
            }
        } else {
            val plain = exact ?: if (custom) CustomProducts.stack(key, amount) else plainStack(material, amount)
            return ItemTransferPlan.withdraw(contents, plain, player.inventory.maxStackSize)
        }
        if (remaining != 0) throw MarketException(if (withdraw) "inventory-full" else "items")
        return contents
    }

    fun transfer(player: Player, account: UUID, key: String, quantity: Int, kind: TransferKind,
        price: Long = 0, claimId: UUID? = null, minimumProceeds: Long = 0,
        auction: AuctionDraft? = null, sourceSlot: Int? = null, merchantLines: List<MerchantLine>? = null,
        saleId: UUID? = null, bazaarLines: List<QuickSellLine>? = null, failed: (() -> Unit)? = null, silent: Boolean = false, done: () -> Unit) {
        if(!InventoryGuard.canStart(player.uniqueId)) {failed?.invoke();locales.send(player,Lang.MARKET_BUSY);return}
        if (islands.findForPlayer(player.uniqueId) == null) { locales.send(player,Lang.ISLAND_NOT_FOUND); failed?.invoke(); return }
        if (storage.marketState().transfers.values.any { it.actor == player.uniqueId && it.status in setOf(TransferStatus.PREPARED, TransferStatus.REVIEW) }) {
            locales.send(player, Lang.MARKET_REVIEW); failed?.invoke(); return
        }
        val token = InventoryGuard.acquireToken(player.uniqueId) ?: run { locales.send(player, Lang.MARKET_BUSY); failed?.invoke(); return }
        val generation = storage.generation
        try {
            require(islands.findForPlayer(player.uniqueId)?.owner == account) { "Active profile changed" }
            require(!player.isDead && player.gameMode in setOf(org.bukkit.GameMode.SURVIVAL, org.bukkit.GameMode.ADVENTURE, org.bukkit.GameMode.CREATIVE)) { "An active player inventory is required" }
            require(player.itemOnCursor.isEmpty) { "Clear the cursor first" }
            val before = hash(player.inventory.storageContents)
            val itemData = if (kind == TransferKind.WITHDRAW) storage.marketState().claims[claimId]?.itemData else auction?.itemData
            val buyback = if (kind == TransferKind.SHOP_BUYBACK) storage.marketState().merchantSales[saleId] ?: throw MarketException("buyback") else null
            val after = if (kind == TransferKind.INSTANT_SELL_BATCH)
                MerchantBasket.remove(player.inventory.storageContents, requireNotNull(bazaarLines).map { MerchantLine(it.product, it.quantity, 0) })
            else if (kind == TransferKind.SHOP_SELL_BATCH) MerchantBasket.remove(player.inventory.storageContents, requireNotNull(merchantLines)) else
                plan(player, key, quantity, kind in setOf(TransferKind.WITHDRAW, TransferKind.SHOP_BUYBACK), itemData, sourceSlot)
            val transfer = ItemTransfer(UUID.randomUUID(), account, player.uniqueId, key, quantity, kind, price, claimId, before, hash(after),
                minimumProceeds = minimumProceeds, itemData = itemData, auction = auction, merchantLines = merchantLines, buyback = buyback, bazaarLines = bazaarLines,
                recipient = if (kind == TransferKind.WITHDRAW) storage.marketState().claims[claimId]?.recipient else null,
                releaseEligible = player.gameMode in setOf(org.bukkit.GameMode.SURVIVAL,org.bukkit.GameMode.ADVENTURE) && islands.findForPlayer(player.uniqueId)?.releaseProgress!=null,
                originSource=if(kind==TransferKind.WITHDRAW)storage.marketState().claims[claimId]?.source else null)
            islands.marketUpdate(account) { island, state -> engine().transaction(account, player.uniqueId, island.coins, state) { prepare(transfer) } }
            player.closeInventory()
            storage.flushAsync().whenComplete { _, prepareError ->
                if (prepareError != null) { failed?.invoke(); report(player, token); return@whenComplete }
                FoliaTasks.entity(plugin, player, retired = { if(storage.healthy && storage.generation == generation) abortOffline(transfer, token) }) {
                    if (!storage.healthy || storage.generation != generation) { failed?.invoke(); return@entity }
                    if (player.isDead || transfer.releaseEligible && player.gameMode !in setOf(org.bukkit.GameMode.SURVIVAL,org.bukkit.GameMode.ADVENTURE) || islands.findForPlayer(player.uniqueId)?.owner != account || hash(player.inventory.storageContents) != before) { failed?.invoke(); abortOffline(transfer, token); return@entity }
                    try {
                        player.inventory.storageContents = after
                        player.persistentDataContainer.set(marker, PersistentDataType.STRING, transfer.id.toString())
                        player.saveData()
                    } catch (e: Exception) {
                        review(transfer); failed?.invoke(); report(player, token); return@entity
                    }
                    try {
                        val outcome = islands.marketUpdate(account) { island, state ->
                            engine().transaction(account, player.uniqueId, island.coins, state) {
                                finish(transfer.id)
                                claims.values.any { it.source == "unexecuted:${transfer.id}" || it.source == "overflow:${transfer.id}" } to
                                    (coins - island.coins)
                            }
                        }
                        storage.flushAsync().whenComplete { _, finishError ->
                            if (storage.generation != generation) { failed?.invoke(); return@whenComplete }
                            InventoryGuard.release(player.uniqueId, token)
                            FoliaTasks.entity(plugin, player) {
                                val message = when {
                                    finishError != null -> Lang.MARKET_UNAVAILABLE
                                    outcome.first -> Lang.MARKET_SHOP_RETURNED
                                    kind == TransferKind.INSTANT_SELL_BATCH -> Lang.QUICK_SELL_DONE
                                    kind == TransferKind.SHOP_SELL_BATCH -> Lang.MERCHANT_BASKET_DONE
                                    kind != TransferKind.SHOP_SELL -> Lang.MARKET_DONE
                                    else -> Lang.MARKET_SHOP_DONE
                                }
                                if (!silent || finishError != null) locales.send(player, message, "amount" to quantity, "item" to key,
                                    "coins" to (if (kind == TransferKind.INSTANT_SELL_BATCH) outcome.second else
                                        merchantLines?.let(MerchantBasket::total) ?: Math.multiplyExact(price, quantity.toLong())))
                                if (finishError == null) { done(); changed() } else failed?.invoke()
                            }
                        }
                    } catch (e: Exception) { failed?.invoke(); report(player, token) }
                }
            }
        } catch (e: Exception) {
            InventoryGuard.release(player.uniqueId, token)
            failed?.invoke()
            if ((e as? MarketException)?.audit == null) storage.audit(com.alytanta.oneblock.storage.OperationLog(
                operation = kind.name, actor = player.uniqueId, account = account, success = false, status = "FAILED",
                reason = (e as? MarketException)?.reason ?: e.javaClass.simpleName,
                details = mapOf("product" to key, "quantity" to quantity, "unit_price" to price, "lines" to merchantLines, "bazaar_lines" to bazaarLines, "sale" to saleId)))
            locales.send(player, when ((e as? MarketException)?.reason) {
                "coins" -> Lang.TOO_POOR
                "buyback" -> Lang.MERCHANT_BUYBACK_EXPIRED
                "inventory-full" -> Lang.MARKET_NO_SPACE
                "items" -> Lang.MARKET_NO_ITEMS
                "liquidity" -> Lang.QUICK_SELL_CHANGED
                else -> Lang.MARKET_ERROR
            })
        }
    }
    private fun report(player: Player, token: UUID) {
        InventoryGuard.release(player.uniqueId, token)
        FoliaTasks.entity(plugin, player) { locales.send(player, Lang.MARKET_UNAVAILABLE) }
    }
    private fun abortOffline(transfer: ItemTransfer, token: UUID) {
        try {
            islands.marketUpdate(transfer.account) { island, state -> engine().transaction(transfer.account, transfer.actor, island.coins, state) { abort(transfer.id) } }
            storage.flushAsync().whenComplete { _, error ->
                InventoryGuard.release(transfer.actor, token)
                if (error == null) changed()
            }
        } catch (_: Exception) { InventoryGuard.release(transfer.actor, token) }
    }
    private fun review(transfer: ItemTransfer) {
        runCatching {
            islands.marketUpdate(transfer.account) { island, state -> engine().transaction(transfer.account, transfer.actor, island.coins, state) { review(transfer.id) } }
            storage.flushAsync()
        }
        plugin.logger.severe("Market handoff ${transfer.id} needs review; no automatic reissue.")
    }

    /** On join or before NPC access. Never replays inventory mutations. */
    fun recover(player: Player) {
        if (InventoryGuard.busy(player.uniqueId)) return
        val pending = storage.marketState().transfers.values.filter { it.actor == player.uniqueId && it.status == TransferStatus.PREPARED }
        for (transfer in pending) {
            if (transfer.kind == TransferKind.RELEASE) continue
            try {
                val applied = player.persistentDataContainer.get(marker, PersistentDataType.STRING) == transfer.id.toString()
                val unchanged = hash(player.inventory.storageContents) == transfer.beforeHash
                islands.marketUpdate(transfer.account) { island, state -> engine().transaction(transfer.account, player.uniqueId, island.coins, state) {
                    when { applied -> finish(transfer.id); unchanged -> abort(transfer.id); else -> review(transfer.id) }
                } }
                storage.flushAsync().thenRun(changed)
                if (!applied && !unchanged) locales.send(player, Lang.MARKET_REVIEW)
            } catch (_: Exception) { locales.send(player, Lang.MARKET_UNAVAILABLE) }
        }
    }
}
