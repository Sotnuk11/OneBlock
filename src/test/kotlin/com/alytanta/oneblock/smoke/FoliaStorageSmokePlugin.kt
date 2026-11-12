package com.alytanta.oneblock.smoke

import com.alytanta.oneblock.island.*
import com.alytanta.oneblock.oneblock.CoreMode
import com.alytanta.oneblock.player.*
import com.alytanta.oneblock.storage.PostgresStorageService
import net.thenextlvl.worlds.WorldsAccess
import org.bukkit.*
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataType
import org.bukkit.plugin.java.JavaPlugin
import java.nio.file.Files
import java.nio.file.Path
import java.util.*
import java.util.concurrent.CompletableFuture

/** Test-only plugin. Never packaged in the production JAR. Runs without a Minecraft client. */
class FoliaStorageSmokePlugin : JavaPlugin() {
    private val human=UUID.fromString("4e6a077f-62cb-4e18-b5df-e3eb437d8818")
    private lateinit var islands:IslandService
    private lateinit var storage:PostgresStorageService
    private lateinit var players:PlayerService
    private val nativeKey=NamespacedKey("oneblocksmoke","exact")
    override fun onEnable() {
        dataFolder.mkdirs()
        server.globalRegionScheduler.runDelayed(this,{
            val flow=runCatching { start() }.getOrElse { CompletableFuture.failedFuture(it) }
            flow.whenComplete { _,error ->
                CompletableFuture.runAsync {
                    if(error!=null) { logger.log(java.util.logging.Level.SEVERE,"SMOKE FAILED",error);dataFolder.resolve("failed.txt").writeText(error.stackTraceToString()) }
                    else dataFolder.resolve(if(dataFolder.resolve("phase1.ok").exists())"phase2.ok" else "phase1.ok").writeText("PASS")
                }.whenComplete { _,_ -> server.globalRegionScheduler.execute(this) { server.shutdown() } }
            }
        },40)
    }
    private fun start():CompletableFuture<Unit> {
        val plugin=requireNotNull(server.pluginManager.getPlugin("one-block"))
        val snapshot=com.alytanta.oneblock.config.ConfigSnapshot.read(plugin.dataFolder.resolve("config.yml"),logger)
        snapshot.validateMaterials()
        val combat=requireNotNull(snapshot.release?.combat)
        val head=combat.items.values.first {it.kind==com.alytanta.oneblock.combat.CombatKind.ARMOR && it.armorSlot=="HEAD"}
        check(head.material=="PLAYER_HEAD")
        combat.copy(items=mapOf(head.id to head)).validateMaterials()
        for(invalid in listOf(head.copy(kind=com.alytanta.oneblock.combat.CombatKind.MELEE),head.copy(armorSlot="CHEST"))) {
            check(runCatching {combat.copy(items=mapOf(invalid.id to invalid)).validateMaterials()}.exceptionOrNull() is IllegalArgumentException)
        }
        logger.info("STARTUP CONFIG PASS: full snapshot/material validation, PLAYER_HEAD armor accepted; invalid head carrier roles rejected")
        fun field(target:Any,name:String):Any = target.javaClass.getDeclaredField(name).apply { isAccessible=true }.get(target)
        storage=field(plugin,"storage") as PostgresStorageService
        islands=field(plugin,"activeIslands") as IslandService
        players=field(islands,"players") as PlayerService
        check(storage.healthy)
        val second=dataFolder.resolve("phase1.ok").exists()
        val island=if(second)islands.profilesFor(human).single() else requireNotNull(islands.createProfile(human,"Smoke"))
        return storage.flushAsync().thenCompose { request(island) }.thenCompose { world ->
            check(world!=null)
            if(second) {
                check(world.getBlockAt(3,island.y,3).type==Material.DIAMOND_BLOCK)
                check(islands.findByOwner(island.owner)?.core?.mode==CoreMode.STONE)
                val encoded=players.find(human)!!.savedInventories!![island.owner]!!.items[0]!!
                val decoded=ItemStack.deserializeBytes(Base64.getDecoder().decode(encoded))
                check(decoded.type==Material.DIAMOND_SWORD && decoded.itemMeta.persistentDataContainer.get(nativeKey,PersistentDataType.STRING)=="exact-native-pdc")
                missingWorldCheck(island,world)
            } else {
                world.getBlockAt(3,island.y,3).setType(Material.DIAMOND_BLOCK,false)
                val stack=ItemStack.of(Material.DIAMOND_SWORD)
                val meta=stack.itemMeta;meta.persistentDataContainer.set(nativeKey,PersistentDataType.STRING,"exact-native-pdc");stack.itemMeta=meta
                val encoded=Base64.getEncoder().encodeToString(stack.serializeAsBytes())
                players.update(human) { it.copy(savedInventories=mapOf(island.owner to ProfileInventory(items=listOf(encoded)))) }
                islands.updateCoreMode(island.owner,CoreMode.STONE)
                storage.flushAsync().thenCompose { backup(world) }.thenCompose { global { WorldsAccess.access().unload(world,true) } }
                    .thenCompose { check(it);request(island) }.thenApply { reloaded ->
                        check(reloaded!=null && reloaded.getBlockAt(3,island.y,3).type==Material.DIAMOND_BLOCK);Unit
                    }
            }
        }.thenCompose { CompletableFuture.supplyAsync { val file=storage.backup();check(file.length()>0);Unit } }
            .thenCompose {if(java.lang.Boolean.getBoolean("oneblock.config-recovery"))configurationRecovery(plugin) else CompletableFuture.completedFuture(Unit)}
    }
    private fun configurationRecovery(plugin:org.bukkit.plugin.Plugin):CompletableFuture<Unit> {
        val data=plugin.dataFolder
        lateinit var before:Map<Path,ByteArray>
        val inProgress=plugin.javaClass.getDeclaredField("reloadInProgress").apply {isAccessible=true}.get(plugin) as java.util.concurrent.atomic.AtomicBoolean
        return CompletableFuture.runAsync {
            before=com.alytanta.oneblock.config.DefaultConfigs.capture(data)
            check(before.size==32)
            check(Files.readString(data.parentFile.resolve("menus/combat/statistics.yml").toPath()).contains("retain custom menu sibling"))
            check(org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(data.resolve("auction.yml")).getStringList("auction.allowed-items").isEmpty())
            for(name in listOf("items.yml","resource-pack.yml","auction.yml","lang/uk.yml"))Files.delete(data.resolve(name).toPath())
            Files.delete(data.parentFile.resolve("menus/combat/layout.yml").toPath())
        }.thenCompose {global {
            check(server.dispatchCommand(server.consoleSender,"obreload"));check(inProgress.get())
            CompletableFuture<Unit>().also {done->
                var ticks=0
                server.globalRegionScheduler.runAtFixedRate(this,{task->
                    if(!inProgress.get()) {task.cancel();done.complete(Unit)}
                    else if(++ticks>400) {task.cancel();done.completeExceptionally(IllegalStateException("Config recovery reload timeout"))}
                },1,1)
            }
        }}.thenCompose {CompletableFuture.supplyAsync {
            val after=com.alytanta.oneblock.config.DefaultConfigs.capture(data)
            check(before.keys==after.keys && before.all {(path,bytes)->bytes.contentEquals(after.getValue(path))}) {"Reload changed retained configuration bytes"}
            val pack=org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(data.resolve("resource-pack.yml"))
            check(!pack.getBoolean("enabled") && !pack.getBoolean("required"))
            logger.info("CONFIG RECOVERY PASS: all32 files, partial menu directory, restart deletions, reload five missing files, custom sibling and empty auction allowlist preserved; no resource pack request")
            Unit
        }}
    }
    private fun request(island:Island)=CompletableFuture<World?>().also { result -> islands.ensureWorld(island) { result.complete(it) } }
    private fun backup(world:World)=CompletableFuture<Unit>().also { result ->
        islands.backupWorld(world) { if(it)result.complete(Unit) else result.completeExceptionally(IllegalStateException("World backup failed")) }
    }
    private fun missingWorldCheck(island:Island,world:World):CompletableFuture<Unit> {
        val api=WorldsAccess.access()
        val original=api.resolveLevelDirectory(IslandWorldIdentity.key(island.owner)).toAbsolutePath().normalize()
        val held=original.resolveSibling(original.fileName.toString()+".smoke-held")
        val sandbox=Path.of("").toAbsolutePath().normalize()
        check(original.startsWith(sandbox) && held.startsWith(sandbox) && original!=sandbox && !Files.exists(held))
        return global { api.unload(world,true) }.thenCompose { check(it);CompletableFuture.runAsync { Files.move(original,held) } }
            .thenCompose { request(island) }.thenApply { check(it==null);Unit }
            .handleAsync { _,failure ->
                if(Files.exists(held))Files.move(held,original)
                if(failure!=null)throw failure
                Unit
            }.thenCompose { request(island) }.thenApply { loaded ->
                check(loaded!=null && loaded.getBlockAt(3,island.y,3).type==Material.DIAMOND_BLOCK);Unit
            }
    }
    private fun <T> global(action:()->CompletableFuture<T>):CompletableFuture<T> = CompletableFuture<T>().also { result ->
        server.globalRegionScheduler.execute(this) {
            try { action().whenComplete { value,error -> if(error==null)result.complete(value) else result.completeExceptionally(error) } }
            catch(e:Throwable) { result.completeExceptionally(e) }
        }
    }
}
