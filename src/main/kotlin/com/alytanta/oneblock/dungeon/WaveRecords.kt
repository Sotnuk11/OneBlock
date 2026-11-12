package com.alytanta.oneblock.dungeon

import com.google.gson.Gson
import java.security.MessageDigest
import java.util.UUID

data class WaveParticipant(val uuid:UUID,val profile:UUID,val nickname:String)
data class WaveRecord(val run:UUID,val wave:Long,val elapsed:Long,val reached:Long?,val created:Long,val started:Long?,
    val ended:Long?,val reason:String?,val participants:List<WaveParticipant>,val size:Int,val mode:String,val version:String,val rulesHash:String)
object WaveRecords {
    const val PAGE=10
    const val PAGES=5
    /** Input is already sorted best first, so the first row of each player or group is its best. Older rows stay stored. */
    fun leaderboard(sorted:List<WaveRecord>)=sorted.distinctBy {r->r.participants.map {it.uuid}.toSet()}.take(PAGE*PAGES)
    fun record(s:MineRun):WaveRecord?=s.wave?.let {w->WaveRecord(s.id,w.completed,w.elapsed,w.reached,s.created,w.started,w.ended,s.reason,
        s.members.values.sortedBy {it.actor.toString()}.map {WaveParticipant(it.actor,it.account,it.name)},s.members.size,"endless",s.settings.waves!!.version,
        hash(Gson().toJson(s.settings.waves)))}
    fun hash(text:String)=MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8)).joinToString("") {"%02x".format(it)}
    fun stamp(s:MineRun)=record(s)?.let {hash(Gson().toJson(it))}
    fun synced(s:MineRun)=s.wave==null || s.recordAck==stamp(s)
}
