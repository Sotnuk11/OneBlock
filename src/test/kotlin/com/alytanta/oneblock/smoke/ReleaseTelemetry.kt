package com.alytanta.oneblock.smoke

import com.alytanta.oneblock.storage.PostgresStorageService
import org.bukkit.Bukkit
import org.bukkit.entity.Player
import java.lang.management.ManagementFactory

/** Native Folia 26.1.2 build 8 TickData, inspected locally. Called on the sampled player's owner. */
object ReleaseTelemetry {
    private fun call(o:Any,name:String,vararg args:Any):Any?=o.javaClass.methods.first {it.name==name && it.parameterCount==args.size}.invoke(o,*args)
    fun capture(p:Player):()->Map<String,Any> {
        check(Bukkit.isOwnedByCurrentRegion(p))
        val region=Class.forName("io.papermc.paper.threadedregions.TickRegionScheduler").getMethod("getCurrentRegion").invoke(null)!!
        val data=call(region,"getData")!!;val handle=call(data,"getRegionSchedulingHandle")!!
        val stats=call(data,"getRegionStats")!!
        val base=mapOf<String,Any>("world" to p.world.key().asString(),"regionId" to region.javaClass.getField("id").getLong(region),"entities" to (call(stats,"getEntityCount") as Int),"players" to (call(stats,"getPlayerCount") as Int),"chunks" to (call(stats,"getChunkCount") as Int))
        // This public synchronized TickData API is designed for external readers. Do not acquire
        // its monitor while holding a region tick/entity scheduler callback.
        return {report(handle,base)}
    }
    private fun report(handle:Any,base:Map<String,Any>):Map<String,Any> {
        val report=call(handle,"getTickReport5s",System.nanoTime())?:return base+mapOf("warming" to true)
        val times=call(call(report,"timePerTickData")!!,"rawData") as LongArray
        val ms=times.map {it/1e6}.sorted()
        fun percentile(f:Double)=if(ms.isEmpty())0.0 else ms[((ms.size-1)*f).toInt()]
        return base+mapOf(
            "windowSeconds" to 5,"tickSamples" to ms.size,"meanTickMs" to if(ms.isEmpty())0.0 else ms.average(),"p50TickMs" to percentile(.5),"p95TickMs" to percentile(.95),"p99TickMs" to percentile(.99),"maxTickMs" to (ms.lastOrNull()?:0.0),
            "tpsMean" to (call(call(call(report,"tpsData")!!,"segmentAll")!!,"average") as Double))
    }
    fun process(storage:PostgresStorageService):Map<String,Any> {
        val memory=ManagementFactory.getMemoryMXBean();val heap=memory.heapMemoryUsage
        val queue=storage.javaClass.declaredMethods.first {it.name.startsWith("getQueuedOperations")}.apply {isAccessible=true}.invoke(storage)
        return mapOf("at" to System.currentTimeMillis(),"heapUsedMiB" to heap.used/1048576.0,"heapCommittedMiB" to heap.committed/1048576.0,"heapMaxMiB" to heap.max/1048576.0,"nonHeapUsedMiB" to memory.nonHeapMemoryUsage.used/1048576.0,
            "gc" to ManagementFactory.getGarbageCollectorMXBeans().map {mapOf("name" to it.name,"collections" to it.collectionCount,"milliseconds" to it.collectionTime)},
            "threads" to ManagementFactory.getThreadMXBean().threadCount,"storageQueuedTasks" to (queue as Int),"storageHealthy" to storage.healthy)
    }
}
