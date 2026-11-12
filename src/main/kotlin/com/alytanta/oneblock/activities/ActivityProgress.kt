package com.alytanta.oneblock.activities

import com.alytanta.oneblock.release.*
import java.util.UUID

data class ActivityReward(val skill:String,val xp:Long,val items:List<ReleaseItem>,val discovery:Long=0)
data class OrderRule(val id:String,val name:String,val chapter:String,val type:String,val blocks:Set<String>,val target:Int,
    val ingredients:List<Ingredient>,val reward:ActivityReward,val text:String="")
data class GuildOrder(val id:UUID,val rule:OrderRule,val actor:UUID,val accepted:Long,val progress:Int=0)
data class IslandEventRule(val kind:String,val name:String,val goal:Int,val intervalMs:Long,val durationMs:Long,
    val blocks:Set<String>,val hints:List<String>,val reward:ActivityReward,val mob:String="ZOMBIE",val health:Double=30.0,val damage:Double=4.0)
data class IslandActivityEvent(val id:UUID,val rule:IslandEventRule,val actor:UUID,val progress:Int=0,val running:Boolean=true,
    val lastStep:Long=0,val elapsed:Long=0,val checkpoint:Long=0,val site:ActivitySite?=null)
data class ActivitySite(val x:Int,val y:Int,val z:Int)
data class ActivityProgress(val order:GuildOrder?=null,val completed:Long=0,val acceptedSerial:Long=0,val lastChange:Long=0,
    val discovery:Long=0,val charge:Int=0,val ready:Boolean=false,val eventSerial:Long=0,val event:IslandActivityEvent?=null,
    val lastCredit:Long=0,val orderReadyAt:Long=0)
