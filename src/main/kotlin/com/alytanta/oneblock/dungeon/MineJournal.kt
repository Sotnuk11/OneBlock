package com.alytanta.oneblock.dungeon

import com.google.gson.Gson
import java.nio.file.*
import java.nio.channels.FileChannel
import java.nio.ByteBuffer
import java.util.UUID

/** Only called on the dungeon I/O worker. Never stores inventory copies or credentials. */
class MineJournal(private val directory:Path,private val beforePublish:(MineRun)->Unit={}) {
    private val gson=Gson()
    fun save(run:MineRun) {
        Files.createDirectories(directory)
        val file=directory.resolve("${run.id}.json");val part=directory.resolve("${run.id}.partial")
        val bytes=gson.toJson(run).toByteArray(Charsets.UTF_8);require(bytes.size<=8*1024*1024)
        FileChannel.open(part,StandardOpenOption.CREATE,StandardOpenOption.TRUNCATE_EXISTING,StandardOpenOption.WRITE).use {ch->
            val b=ByteBuffer.wrap(bytes);while(b.hasRemaining())ch.write(b);ch.force(true)
        }
        beforePublish(run)
        // Windows AV/indexing may briefly hold an already closed destination. Retry only publication,
        // on this I/O worker, without rewriting/rerolling the already forced payload.
        var attempt=0
        while(true) {
            try {Files.move(part,file,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);break}
            catch(e:AccessDeniedException) {if(++attempt>=8)throw e;Thread.sleep(10L*attempt)}
        }
    }
    fun load():List<MineRun> {
        if(!Files.exists(directory))return emptyList()
        return Files.list(directory).use {paths->paths.filter {it.fileName.toString().endsWith(".json")}.map {p->
            require(!Files.isSymbolicLink(p) && Files.size(p)<=8*1024*1024)
            val run=gson.fromJson(Files.readString(p),MineRun::class.java)
            require(p.fileName.toString()=="${run.id}.json" && run.world==world(run.id) && run.members.size in 1..4 && run.members.all {(id,m)->id==m.actor})
            run.rewards.values.forEach {it.validate()}
            run.wave?.let {w->
                val rules=requireNotNull(run.settings.waves) {"Wave journal without frozen rules"}
                require(w.completed>=0 && w.elapsed>=0 && w.plan.number>0 && w.plan.arena in rules.arenas.indices)
                require(w.plan.spawns.size in 1..rules.limits.entities && w.plan.spawns.all {s->
                    s.health.isFinite() && s.health in .01..rules.limits.health && s.damage.isFinite() && s.damage in .01..rules.limits.damage && s.speed.isFinite() && s.speed in .01..rules.limits.speed
                }) {"Invalid frozen wave stats"}
                require(w.completed==0L || w.started!=null && w.reached!=null) {"Missing completed wave clock"}
            };run
        }.toList()}
    }
    fun remove(id:UUID) {
        val file=directory.resolve("$id.json");require(!Files.isSymbolicLink(file));Files.deleteIfExists(file)
        Files.deleteIfExists(directory.resolve("$id.partial"))
    }
    companion object {fun world(id:UUID)="oneblock_dungeons:mine_${id.toString().replace('-','_')}"}
}
