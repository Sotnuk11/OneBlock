package com.alytanta.oneblock.items

import com.alytanta.oneblock.combat.*
import com.alytanta.oneblock.island.IslandService
import com.alytanta.oneblock.market.*
import com.alytanta.oneblock.storage.PostgresStorageService
import com.alytanta.oneblock.util.*
import com.google.gson.Gson
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import org.bukkit.plugin.Plugin
import java.nio.file.*
import java.nio.channels.FileChannel
import java.nio.ByteBuffer
import java.util.UUID
import java.util.concurrent.*

data class ItemGrantRecord(val id:UUID,val item:String,val amount:Int,val operator:String,val prepared:PreparedCombatReward)
/** A tiny producer journal, feeding the existing CombatRewardService and exact personal claims. No inventory give path. */
class ItemGrantJournal(private val folder:Path) {
    private val gson=Gson()
    fun file(id:UUID,done:Boolean=false)=folder.resolve("$id.${if(done)"done" else "pending"}.json")
    fun read(path:Path):ItemGrantRecord {
        require(!Files.isSymbolicLink(path) && Files.size(path)<=8*1024*1024)
        return gson.fromJson(Files.readString(path),ItemGrantRecord::class.java).also {r->
            require(path==file(r.id) || path==file(r.id,true));require(ItemCatalog.validId(r.item) && r.amount in 1..64)
            require(r.prepared.request.event==r.id && r.prepared.request.source=="admin_items" && r.prepared.items.size==r.amount);r.prepared.validate()
        }
    }
    fun pending():List<ItemGrantRecord> = if(!Files.exists(folder))emptyList() else Files.list(folder).use {stream->
        stream.filter {it.fileName.toString().endsWith(".pending.json")}.map(::read).toList().also {require(it.size<=10000) {"Too many pending item grants"}}
    }
    fun existing(id:UUID)=listOf(file(id),file(id,true)).firstOrNull(Files::exists)?.let(::read)
    fun save(record:ItemGrantRecord) {
        Files.createDirectories(folder);val part=folder.resolve("${record.id}.partial")
        val bytes=gson.toJson(record).toByteArray(Charsets.UTF_8)
        FileChannel.open(part,StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE).use {channel->
            val data=ByteBuffer.wrap(bytes);while(data.hasRemaining())channel.write(data);channel.force(true)
        }
        Files.move(part,file(record.id),StandardCopyOption.ATOMIC_MOVE)
    }
    fun acknowledge(id:UUID) {if(Files.exists(file(id)))Files.move(file(id),file(id,true),StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING)}
}
class ItemGrants(private val plugin:Plugin,private val islands:IslandService,private val storage:PostgresStorageService,
    private val rewards:CombatRewardService):AutoCloseable {
    private val journal=ItemGrantJournal(plugin.dataFolder.toPath().resolve("item-grants"))
    private val io=Executors.newSingleThreadScheduledExecutor {r->Thread(r,"oneblock-item-grants").apply {isDaemon=true}}
    private val pending=ConcurrentHashMap<UUID,ItemGrantRecord>()
    private val sending=ConcurrentHashMap.newKeySet<UUID>()
    @Volatile private var ready=false
    init {io.execute {try {journal.pending().forEach {pending[it.id]=it};ready=true} catch(e:Exception) {plugin.logger.severe("Item grant journal unavailable: ${e.javaClass.simpleName}")}}
        io.scheduleWithFixedDelay({if(ready)pending.values.forEach(::dispatch)},1,5,TimeUnit.SECONDS)}
    fun pending(actor:UUID)=!ready || pending.values.any {it.prepared.request.recipient==actor}
    private fun text(key:String,vararg values:Pair<String,Any?>)=values.fold(CustomItems.current()?.messages?.get(key)?:ItemCatalog.defaultMessages[key]?:key) {line,(name,value)->line.replace("{$name}",value.toString())}
    private fun reply(sender:CommandSender,key:String,vararg values:Pair<String,Any?>) {
        val value=text(key,*values)
        if(sender is Player)FoliaTasks.entity(plugin,sender) {sender.sendMessage(Colors.colorize(value))} else sender.sendMessage(Colors.colorize(value))
    }
    fun handle(sender:CommandSender,args:List<String>) {
        if(!com.alytanta.oneblock.command.CommandAccess.allowed(sender,listOf("items")+args))return reply(sender,"denied")
        val catalog=CustomItems.current()?:return reply(sender,"unavailable")
        if(args.firstOrNull() in listOf(null,"list")) {catalog.items.values.forEach {reply(sender,"entry","id" to it.id,"name" to it.name)};return}
        if(args.firstOrNull()=="status" && args.size==2) {
            io.execute {runCatching {val id=UUID.fromString(args[1]);val record=journal.existing(id)?:error("Видачу не знайдено")
                reply(sender,"status","id" to id,"state" to text(if(Files.exists(journal.file(id)))"pending" else "done"),"item" to record.item,"amount" to record.amount,"account" to record.prepared.request.account)
            }.onFailure {reply(sender,"failed","reason" to it.message)}};return
        }
        if(args.firstOrNull()!="give" || args.size !in 3..5)return reply(sender,"usage")
        try {
            check(ready && storage.healthy) {"Сховище або журнал ще не готові"}
            val target=plugin.server.getPlayerExact(args[1])?:error("Гравець має бути онлайн")
            val definition=catalog.require(args[2]);val amount=args.getOrNull(3)?.toInt()?:1;require(amount in 1..64) {"Кількість: 1–64"}
            val event=args.getOrNull(4)?.let(UUID::fromString)?:UUID.randomUUID()
            FoliaTasks.entity(plugin,target) {
                val token=InventoryGuard.acquireToken(target.uniqueId)?:return@entity reply(sender,"busy")
                try {
                    val account=islands.findForPlayer(target.uniqueId)?:error("Потрібен активний профіль")
                    require(account.releaseProgress!=null && account.retiredAt==null)
                    val bytes=AuctionItems.encode(CustomItems.create(definition),65536)
                    val items=List(amount) {FrozenCombatItem(definition.material,bytes)}
                    val request=CombatRewardRequest("admin_items",event,account.owner,target.uniqueId,"admin",PreparedCombatReward.hash(items))
                    val record=ItemGrantRecord(event,definition.id,amount,sender.name,PreparedCombatReward(request,items))
                    io.execute {
                        try {
                            val old=journal.existing(event)
                            require(old==null || old.item==record.item && old.amount==record.amount && old.prepared.request.account==request.account && old.prepared.request.recipient==request.recipient) {"request-UUID уже належить іншій видачі"}
                            if(old==null) {check(pending.size<10000);journal.save(record);pending[event]=record}
                            else if(Files.exists(journal.file(event)))pending[event]=old
                            reply(sender,"queued","id" to event)
                            pending[event]?.let(::dispatch)
                        } catch(e:Exception) {reply(sender,"failed","reason" to e.message)}
                        finally {InventoryGuard.release(target.uniqueId,token)}
                    }
                } catch(e:Exception) {InventoryGuard.release(target.uniqueId,token);reply(sender,"failed","reason" to e.message)}
            }
        } catch(e:Exception) {reply(sender,"failed","reason" to e.message)}
    }
    private fun dispatch(record:ItemGrantRecord) {
        if(!storage.healthy || !sending.add(record.id))return
        rewards.awardPrepared(record.prepared).whenComplete {_,error->
            if(error==null)io.execute {try {journal.acknowledge(record.id);pending.remove(record.id,record)}finally {sending.remove(record.id)}}
            else sending.remove(record.id)
        }
    }
    override fun close() {io.shutdown();io.awaitTermination(20,TimeUnit.SECONDS)}
}
