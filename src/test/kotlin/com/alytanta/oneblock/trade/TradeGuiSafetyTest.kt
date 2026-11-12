package com.alytanta.oneblock.trade

import com.alytanta.oneblock.island.*
import com.alytanta.oneblock.market.*
import com.alytanta.oneblock.menu.Stack
import com.alytanta.oneblock.oneblock.CoreMode
import com.alytanta.oneblock.oneblock.CoreState
import org.bukkit.Material
import org.bukkit.inventory.ItemStack
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.util.UUID

class TradeGuiSafetyTest {
    private val a=UUID.randomUUID();private val b=UUID.randomUUID()
    private fun wallets()=mapOf(a to Island(a,"a",0,60,0,CoreState(CoreMode.WOOD),coins=1000),b to Island(b,"b",0,60,0,CoreState(CoreMode.WOOD),coins=500))
    private fun tool()=TradeInventory.item(Stack(Material.DIAMOND_SWORD,1,tag="exact-PDC-profile=$a;custom-name;damage=21"),262144)
    private fun record()=PlayerTrade(UUID.randomUUID(),listOf(TradeLeg(a,"A",a,100,listOf(tool()),"before-a","after-a"),TradeLeg(b,"B",b,30,emptyList(),"before-b","after-b")))
    private fun balances(values:Map<UUID,Long>)=wallets().mapValues {(id,island)->island.copy(coins=values.getValue(id))}
    @Test fun `two confirmed deposits exchange exact serialized items once and keep claims personal to the recipient profile`() {
        val record=record();val prepared=PlayerTradeLedger.prepare(record,wallets(),MarketState())
        assertEquals(900L,prepared.coins[a]);assertEquals(470L,prepared.coins[b])
        val first=PlayerTradeLedger.resolve(record.id,a,DepositStatus.APPLIED,balances(prepared.coins),prepared.state,null)
        assertTrue(first.state.claims.isEmpty())
        val done=PlayerTradeLedger.resolve(record.id,b,DepositStatus.APPLIED,balances(first.coins),first.state,null)
        assertEquals(TradeStatus.DONE,done.result.status);assertEquals(930L,done.coins[a]);assertEquals(570L,done.coins[b])
        val claim=done.state.claims.values.single();assertEquals(b,claim.account);assertEquals(b,claim.recipient)
        assertEquals(record.legs[0].items[0].itemData,claim.itemData)
        val repeat=PlayerTradeLedger.resolve(record.id,b,DepositStatus.APPLIED,balances(done.coins),done.state,null)
        assertEquals(done.state,repeat.state);assertEquals(done.coins,repeat.coins)
    }
    @Test fun `one failed deposit refunds both wallets and returns only the actually removed exact item`() {
        val r=record();val prepared=PlayerTradeLedger.prepare(r,wallets(),MarketState())
        val first=PlayerTradeLedger.resolve(r.id,a,DepositStatus.APPLIED,balances(prepared.coins),prepared.state,null)
        val abort=PlayerTradeLedger.resolve(r.id,b,DepositStatus.DECLINED,balances(first.coins),first.state,"inventory changed")
        assertEquals(TradeStatus.ABORTED,abort.result.status);assertEquals(mapOf(a to 1000L,b to 500L),abort.coins)
        val returned=abort.state.claims.values.single();assertEquals(a,returned.account);assertEquals(a,returned.recipient)
        assertEquals(r.legs[0].items[0].itemData,returned.itemData)
        assertEquals(abort.state,PlayerTradeLedger.resolve(r.id,a,DepositStatus.APPLIED,balances(abort.coins),abort.state,null).state)
    }
    @Test fun `ambiguous inventory persistence freezes the trade and recovery cannot issue rewards twice`() {
        val r=record();val p=PlayerTradeLedger.prepare(r,wallets(),MarketState())
        val review=PlayerTradeLedger.resolve(r.id,a,DepositStatus.REVIEW,balances(p.coins),p.state,"saveData uncertain")
        assertEquals(TradeStatus.REVIEW,review.result.status);assertTrue(PlayerTradeLedger.pending(review.state,a));assertTrue(review.state.claims.isEmpty())
        assertThrows(Exception::class.java) {PlayerTradeLedger.prepare(record(),balances(review.coins),review.state)}
        val fixed=PlayerTradeLedger.resolve(r.id,a,DepositStatus.APPLIED,balances(review.coins),review.state,null)
        val done=PlayerTradeLedger.resolve(r.id,b,DepositStatus.APPLIED,balances(fixed.coins),fixed.state,null)
        assertEquals(1,done.state.claims.size);assertFalse(PlayerTradeLedger.pending(done.state,a))
    }
    @Test fun `duplicate prepare and a changed profile membership cannot reserve another wallet`() {
        val r=record();val p=PlayerTradeLedger.prepare(r,wallets(),MarketState())
        assertThrows(Exception::class.java) {PlayerTradeLedger.prepare(r,balances(p.coins),p.state)}
        assertThrows(Exception::class.java) {PlayerTradeLedger.prepare(r.copy(legs=r.legs.map {if(it.actor==a)it.copy(account=b) else it}),wallets(),MarketState())}
        assertEquals(1000L,wallets().getValue(a).coins);assertEquals(900L,p.coins[a])
    }
    @Test fun `co-op cannot trade its shared coins to itself and exact item claims stay personal`() {
        val shared=wallets().getValue(a).copy(members=mapOf(b to IslandRole.MEMBER));val r=record().copy(legs=record().legs.map {it.copy(account=a)})
        assertThrows(Exception::class.java) {PlayerTradeLedger.prepare(r,mapOf(a to shared),MarketState())}
        val itemsOnly=r.copy(legs=r.legs.map {it.copy(coins=0)})
        val p=PlayerTradeLedger.prepare(itemsOnly,mapOf(a to shared),MarketState())
        val one=PlayerTradeLedger.resolve(r.id,a,DepositStatus.APPLIED,mapOf(a to shared),p.state,null)
        val two=PlayerTradeLedger.resolve(r.id,b,DepositStatus.APPLIED,mapOf(a to shared),one.state,null)
        assertEquals(1000L,two.coins[a]);assertEquals(b,two.state.claims.values.single().recipient)
    }
    @Test fun `inventory fingerprints ignore only the reserved menu slot and exact payload limits fail before escrow`() {
        val before=arrayOfNulls<ItemStack>(36);before[0]=Stack(Material.DIAMOND_SWORD,1,tag="profile-bound-PDC");before[8]=Stack(Material.NETHER_STAR,1,menu=true)
        val changed=before.map {it?.clone()}.toTypedArray();changed[8]=Stack(Material.NETHER_STAR,1,menu=true,tag="new GUI icon")
        assertEquals(TradeInventory.hash(before),TradeInventory.hash(changed))
        changed[0]=Stack(Material.DIAMOND_SWORD,1,tag="different-profile-PDC")
        assertNotEquals(TradeInventory.hash(before),TradeInventory.hash(changed))
        assertThrows(MarketException::class.java) {TradeInventory.item(before[0]!!,1)}
        assertEquals(Stack(Material.DIAMOND_SWORD,1,tag="profile-bound-PDC"),before[0])
    }
}
