package com.alytanta.oneblock.smoke

import com.google.gson.JsonParser
import java.io.*
import java.net.Socket
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.*

/** Loopback-only test client, pinned to protocol 775 from the local 26.1.2 Game/ConfigurationProtocols. */
class MinecraftWireBot(private val port:Int,private val folder:Path,private val name:String="HubSmokeBot",private val connectDelayMillis:Long=0):AutoCloseable {
    init {require(name.matches(Regex("[A-Za-z0-9_]{1,16}")))}
    private val stopped=AtomicBoolean()
    @Volatile private var socket:Socket?=null
    @Volatile private var playing=false
    @Volatile private var compression=-1
    @Volatile var failure:Throwable?=null;private set
    private val outputLock=Any()
    private fun varInt(input:InputStream):Int {var value=0;var shift=0;while(shift<35) {val b=input.read();if(b<0)throw EOFException();value=value or ((b and 127) shl shift);if(b and 128==0)return value;shift+=7};error("Invalid VarInt")}
    private fun variable(out:OutputStream,value:Int) {var v=value;do {var b=v and 127;v=v ushr 7;if(v!=0)b=b or 128;out.write(b)}while(v!=0)}
    private fun string(out:OutputStream,text:String) {val bytes=text.toByteArray(Charsets.UTF_8);variable(out,bytes.size);out.write(bytes)}
    private fun readString(input:InputStream):String {val n=varInt(input);require(n in 0..1048576);return String(input.readNBytes(n),Charsets.UTF_8)}
    private fun pack(data:DataInputStream,responsePacket:Int) {
        val id=UUID(data.readLong(),data.readLong());val url=readString(data);val hash=readString(data);data.readBoolean()
        Thread({
            fun status(value:Int)=send(responsePacket) {it.writeLong(id.mostSignificantBits);it.writeLong(id.leastSignificantBits);variable(it,value)}
            try {
                val policy=folder.resolve("$name-pack-policy.txt").takeIf(Files::exists)?.let(Files::readString)?.trim()?:"auto"
                if(policy=="decline") {status(1);return@Thread}
                if(policy=="premature") {status(0);return@Thread}
                status(3);Files.writeString(folder.resolve("$name-pack-accepted"),id.toString())
                if(policy=="timeout")return@Thread
                while(Files.exists(folder.resolve("$name-pack-hold"))) {if(stopped.get())return@Thread;Thread.sleep(20)}
                if(policy=="fail") {status(2);return@Thread}
                val uri=java.net.URI(url);require(uri.host=="127.0.0.1" && uri.scheme=="http") {"Test client only downloads its own loopback-hosted pack"}
                val cache=folder.resolve("pack-cache/$hash.zip");Files.createDirectories(cache.parent)
                val cached=Files.exists(cache)
                val bytes=if(cached)Files.readAllBytes(cache) else uri.toURL().openConnection().apply {connectTimeout=3000;readTimeout=3000}.getInputStream().use {it.readBytes()}
                val actual=java.security.MessageDigest.getInstance("SHA-1").digest(bytes).joinToString("") {"%02x".format(it)};require(actual==hash)
                if(!cached)Files.write(cache,bytes)
                status(4);status(0)
                Files.writeString(folder.resolve("$name-pack-applied"),"$id|$hash|cached=$cached")
            } catch(e:Exception) {runCatching {status(2)};Files.writeString(folder.resolve("$name-pack-download-error"),e.javaClass.simpleName)}
        },"pack-download-$name").apply {isDaemon=true;start()}
    }
    private fun send(id:Int,body:(DataOutputStream)->Unit={}) {
        val bytes=ByteArrayOutputStream();variable(bytes,id);body(DataOutputStream(bytes));val raw=bytes.toByteArray()
        val frame=ByteArrayOutputStream()
        if(compression>=0) {
            if(raw.size>=compression) {variable(frame,raw.size);DeflaterOutputStream(frame).use {it.write(raw)}} else {variable(frame,0);frame.write(raw)}
        } else frame.write(raw)
        synchronized(outputLock) {val out=requireNotNull(socket).getOutputStream();variable(out,frame.size());out.write(frame.toByteArray());out.flush()}
    }
    private fun packet(input:InputStream):DataInputStream {
        val n=varInt(input);require(n in 1..33_554_432);val raw=input.readNBytes(n);check(raw.size==n)
        if(compression<0)return DataInputStream(ByteArrayInputStream(raw))
        val data=ByteArrayInputStream(raw);val length=varInt(data)
        return DataInputStream(if(length==0)data else ByteArrayInputStream(InflaterInputStream(data).readBytes().also {check(it.size==length)}))
    }
    fun start() {
        Thread({
            try {
                val deadline=System.nanoTime()+120_000_000_000L
                while(!Files.exists(folder.resolve("bot-ready"))) {check(System.nanoTime()<deadline);if(stopped.get())return@Thread;Thread.sleep(50)}
                if(connectDelayMillis>0)Thread.sleep(connectDelayMillis)
                socket=Socket("127.0.0.1",port).apply {tcpNoDelay=true}
                send(0) {variable(it,775);string(it,"127.0.0.1");it.writeShort(port);variable(it,2)}
                val id=UUID.nameUUIDFromBytes("OfflinePlayer:$name".toByteArray(Charsets.UTF_8))
                send(0) {string(it,name);it.writeLong(id.mostSignificantBits);it.writeLong(id.leastSignificantBits)}
                var state=0
                val input=requireNotNull(socket).getInputStream()
                while(!stopped.get()) {
                    val data=packet(input);val type=varInt(data)
                    when(state) {
                        0->when(type) {3->compression=varInt(data);2->{send(3);state=1};0->error("Login disconnected")}
                        1->when(type) {
                            0->{val n=varInt(data);val key=String(data.readNBytes(n),Charsets.UTF_8);send(1) {string(it,key);it.writeBoolean(false)}}
                            2->error("Configuration disconnected")
                            3->{send(3);state=2;playing=true}
                            4->{val value=data.readLong();send(4) {it.writeLong(value)}}
                            5->{val value=data.readInt();send(5) {it.writeInt(value)}}
                            9->pack(data,6)
                            14->send(7) {variable(it,0)}
                            19->send(9)
                        }
                        2->when(type) {
                            32->error("Play disconnected")
                            44->{val value=data.readLong();send(28) {it.writeLong(value)}}
                            61->{val value=data.readInt();send(45) {it.writeInt(value)}}
                            72->{val teleport=varInt(data);send(0) {variable(it,teleport)};send(44)}
                            81->pack(data,49)
                        }
                    }
                }
            } catch(e:Throwable) {if(!stopped.get()) {failure=e;Files.writeString(folder.resolve(if(name=="HubSmokeBot")"bot-failure.txt" else "$name-failure.txt"),e.stackTraceToString())}}
        },"smoke-client-$name").apply {isDaemon=true;start()}
        Thread({
            var sequence=-1
            while(!stopped.get()) {
                try {
                    val path=folder.resolve(if(name=="HubSmokeBot")"bot-mine.json" else "$name-mine.json")
                    if(playing && Files.exists(path)) {
                        val task=JsonParser.parseString(Files.readString(path)).asJsonObject;val next=task.get("sequence").asInt
                        if(next!=sequence) {
                            sequence=next;val x=task.get("x").asLong;val y=task.get("y").asLong;val z=task.get("z").asLong
                            send(41) {variable(it,0);it.writeLong(((x and 0x3ffffff) shl 38) or ((z and 0x3ffffff) shl 12) or (y and 0xfff));it.writeByte(1);variable(it,next)}
                        }
                    }
                }catch(_:Exception) { /* The next atomic instruction or socket reader supplies the final diagnostic. */ }
                Thread.sleep(20)
            }
        },"hub-smoke-actions").apply {isDaemon=true;start()}
    }
    override fun close() {stopped.set(true);socket?.close()}
}
