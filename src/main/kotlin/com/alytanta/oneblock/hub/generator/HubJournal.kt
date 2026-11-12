package com.alytanta.oneblock.hub.generator

import com.google.gson.Gson
import java.nio.channels.FileChannel
import java.nio.file.*
import java.util.UUID

enum class HubJobState { PREPARED,BACKING_UP,BUILDING,PAUSED,CONFLICT,BUILT,SERVICES_READY,ACTIVE,ROLLING_BACK,ROLLED_BACK }
data class HubJob(val id:UUID,val actor:String,val target:HubTarget,val planHash:String,val configHash:String,
    val total:Int,val state:HubJobState=HubJobState.PREPARED,val placed:Int=0,val backup:String?=null,
    val operation:String="build",val intent:Int=0,val error:String="",val servicesAttempted:Boolean=false) {
    val reserved get()=state in setOf(HubJobState.PREPARED,HubJobState.BACKING_UP,HubJobState.BUILDING,HubJobState.PAUSED,HubJobState.CONFLICT,HubJobState.ROLLING_BACK)
}
/** File IO only on the generator's serialized executor. Intent is forced before the corresponding region edits. */
class HubJournal(private val directory:Path,
    private val publish:(Path,Path)->Unit={from,to->Files.move(from,to,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);Unit},
    private val retryPause:(Long)->Unit={Thread.sleep(it)}) {
    private val gson=Gson()
    private fun write(name:String,text:String) {
        Files.createDirectories(directory)
        val file=directory.resolve(name);val tmp=directory.resolve("$name.tmp")
        Files.writeString(tmp,text,Charsets.UTF_8)
        FileChannel.open(tmp,StandardOpenOption.WRITE).use {it.force(true)}
        // Windows may briefly deny replacement while another process holds the destination.
        // Retry the SAME forced file on this IO worker; never acknowledge intent before atomic publish.
        for(attempt in 0..5)try {publish(tmp,file);return} catch(error:AccessDeniedException) {
            if(attempt==5)throw error
            retryPause(20L shl attempt)
        }
    }
    fun create(job:HubJob,plan:HubPlan) { require(job.planHash==plan.hash());write("plan-${job.id}.json",gson.toJson(plan));save(job) }
    fun save(job:HubJob) { write("job-${job.id}.json",gson.toJson(job)) }
    fun plan(job:HubJob):HubPlan = gson.fromJson(Files.readString(directory.resolve("plan-${job.id}.json")),HubPlan::class.java).also {
        require(it.version==1 && it.target==job.target && it.configHash==job.configHash && it.blocks.size==job.total && it.hash()==job.planHash) {"Hub journal/blueprint mismatch"}
    }
    fun load():List<HubJob> {
        if(!Files.exists(directory))return emptyList()
        return Files.list(directory).use { stream->stream.filter {it.fileName.toString().startsWith("job-") && it.fileName.toString().endsWith(".json")}.map {path->
            gson.fromJson(Files.readString(path),HubJob::class.java).also {job->
                require(path.fileName.toString()=="job-${job.id}.json" && job.total in 1..50_000 && job.state!=null)
                plan(job)
            }
        }.toList() }
    }
    fun worldIntent(key:String) { val id=UUID.nameUUIDFromBytes(key.toByteArray());val path=directory.resolve("world-$id.json")
        check(!Files.exists(path)) {"World creation intent already exists; inspect/load the existing world instead of retrying create"}
        write(path.fileName.toString(),gson.toJson(mapOf("key" to key,"state" to "CREATING")))
    }
}
