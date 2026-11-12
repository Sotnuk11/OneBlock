package com.alytanta.oneblock.release

import com.alytanta.oneblock.config.PluginConfig
import com.alytanta.oneblock.island.*
import com.alytanta.oneblock.market.*
import com.alytanta.oneblock.menu.*
import com.alytanta.oneblock.oneblock.*
import com.alytanta.oneblock.player.*
import com.alytanta.oneblock.storage.StorageService
import org.bukkit.*
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import org.bukkit.plugin.Plugin
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.UUID
import java.util.logging.Logger

class ReleaseEligibilityTest {
    @TempDir lateinit var dir: Path
    @Test fun `entry guard excludes creative foreign account placed sources and immature crops with one XP credit per drop event`() {
        val r=dir.resolve("rules.yml").toFile().let { file -> file.writeText(javaClass.getResourceAsStream("/progression.yml")!!.reader().use { it.readText() });ReleaseRules.read(file,true).first!! }
        val actor=UUID.randomUUID();val account=UUID.randomUUID();var mode=GameMode.SURVIVAL
        val original=Island(account,IslandWorldIdentity.worldName(account),0,60,0,CoreState(CoreMode.WOOD),ownerPlayerId=actor,releaseProgress=ReleaseProgress())
        var saved=original
        val storage=object: StorageService {
            override fun loadIslands()=listOf(saved)
            override fun loadProfiles()=listOf(PlayerProfile(actor,activeAccount=account))
            override fun saveIsland(island: Island) { saved=island }
            override fun saveProfile(profile: PlayerProfile) {}
            override fun saveNow() {};override fun flush()=true;override fun close() {}
            override fun backup()=error("no IO")
        }
        val plugin=MenuFixture.stub(Plugin::class.java) { name,_ -> when(name) { "getName","namespace" -> "one-block";"getLogger" -> Logger.getAnonymousLogger();else->null } }
        val players=PlayerService(storage);val islands=IslandService(plugin,PluginConfig.load(YamlConfiguration(),Logger.getAnonymousLogger()),storage,players)
        val player=MenuFixture.stub(Player::class.java) { name,_ -> when(name) { "getUniqueId" -> actor;"getGameMode" -> mode;else->null } }
        val runtime=ReleaseRuntime(islands,ReleaseTools(plugin,islands,{r}),{r})
        fun harvest(block: Material, placed: Boolean=false, grown: Boolean=false, mature: Boolean=false, silk: Boolean=false, owner: UUID=account, amount: Int=64) {
            runtime.harvest(player,owner,block,silk,placed,grown,mature,mutableListOf(Stack(if(block==Material.WHEAT)Material.WHEAT else Material.RAW_IRON,amount)),Stack(Material.IRON_PICKAXE))
        }
        harvest(Material.IRON_ORE,placed=true);harvest(Material.WHEAT,mature=false);harvest(Material.IRON_ORE,owner=UUID.randomUUID());mode=GameMode.CREATIVE;harvest(Material.IRON_ORE)
        assertTrue(saved.releaseProgress!!.members.isEmpty())
        mode=GameMode.SURVIVAL;harvest(Material.IRON_ORE);assertEquals(4,saved.releaseProgress!!.member(actor).xp["mining"])
        harvest(Material.IRON_ORE,silk=true);assertEquals(4,saved.releaseProgress!!.member(actor).xp["mining"])
        harvest(Material.WHEAT,placed=true,mature=true);assertEquals(2,saved.releaseProgress!!.member(actor).xp["farming"]);assertEquals(1,saved.releaseProgress!!.matureCrops)
        harvest(Material.OAK_LOG,placed=true,grown=true);assertEquals(0,saved.releaseProgress!!.grownLogs)
        harvest(Material.OAK_LOG,grown=true);assertEquals(4,saved.releaseProgress!!.member(actor).xp["forestry"]);assertEquals(1,saved.releaseProgress!!.grownLogs)
        harvest(Material.JUNGLE_LOG,grown=true);assertEquals(8,saved.releaseProgress!!.member(actor).xp["forestry"]);assertEquals(2,saved.releaseProgress!!.grownLogs)
        assertEquals(Material.MANGROVE_PROPAGULE,ReleaseRules.saplingFor(Material.MANGROVE_LOG))
        assertEquals(Material.JUNGLE_SAPLING,ReleaseRules.saplingFor(Material.STRIPPED_JUNGLE_LOG))
        assertEquals(0,saved.releaseProgress!!.charge);assertEquals(0,saved.releaseProgress!!.eligibleCoreBreaks)
    }
    @Test fun `net merchant tutorial counts only eligible new receipts and buyback abort restores only the original credited sale`() {
        val r=dir.resolve("rules.yml").toFile().let { file -> file.writeText(javaClass.getResourceAsStream("/progression.yml")!!.reader().use { it.readText() });ReleaseRules.read(file,true).first!! }
        val id=UUID.randomUUID();val plugin=MenuFixture.stub(Plugin::class.java) { name,_ -> if(name in setOf("getName","namespace"))"one-block" else null }
        val original=Island(id,IslandWorldIdentity.worldName(id),0,60,0,CoreState(CoreMode.WOOD),releaseProgress=ReleaseProgress())
        val fake=object:StorageService {
            override fun loadIslands()=listOf(original);override fun loadProfiles()=emptyList<PlayerProfile>();override fun saveIsland(island:Island){};override fun saveProfile(profile:PlayerProfile){}
            override fun saveNow(){};override fun flush()=true;override fun backup()=error("no IO");override fun close(){}
        }
        val islands=IslandService(plugin,PluginConfig.load(YamlConfiguration(),Logger.getAnonymousLogger()),fake)
        val runtime=ReleaseRuntime(islands,ReleaseTools(plugin,islands,{r}),{r})
        val sale=MerchantSale(UUID.randomUUID(),id,id,"OAK_LOG",25,4,1,100000,1,progressionCredit=true)
        val receipt=MarketState(merchantSales=mapOf(sale.id to sale))
        var p=runtime.merchant(original,MarketState(),receipt)!!;assertEquals(100,p.merchantNet)
        val buyback=ItemTransfer(UUID.randomUUID(),id,id,"OAK_LOG",25,TransferKind.SHOP_BUYBACK,4,null,"b","a",buyback=sale)
        val prepared=receipt.copy(merchantSales=emptyMap(),transfers=mapOf(buyback.id to buyback))
        p=runtime.merchant(original.copy(releaseProgress=p),receipt,prepared)!!;assertEquals(0,p.merchantNet)
        val aborted=receipt.copy(transfers=mapOf(buyback.id to buyback.copy(status=TransferStatus.ABORTED,buyback=null)))
        p=runtime.merchant(original.copy(releaseProgress=p),prepared,aborted)!!;assertEquals(100,p.merchantNet)
        val creative=sale.copy(id=UUID.randomUUID(),progressionCredit=false)
        assertEquals(0,runtime.merchant(original,MarketState(),MarketState(merchantSales=mapOf(creative.id to creative)))!!.merchantNet)
        assertEquals(0,runtime.merchant(original,receipt,prepared)!!.merchantNet) // Adoption must not borrow credit from an old receipt.
    }
}
