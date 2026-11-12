package com.alytanta.oneblock.dungeon

import com.alytanta.oneblock.combat.*
import com.alytanta.oneblock.util.StrictYaml
import org.bukkit.configuration.file.YamlConfiguration
import java.io.File

data class MinePoint(val x:Double,val y:Double,val z:Double,val yaw:Float=0f)
data class MineEntry(val world:String,val point:MinePoint,val radius:Double=5.0)
/** Permanent, explicit hub position. Empty world keeps the guide unplaced; never use a default world. */
data class DungeonGuideSettings(val enabled:Boolean=false,val name:String="oneblock_dungeon_guide",
    val displayName:String="<gold>Провідник данжів",val type:String="VILLAGER",val skin:String="",
    val distance:Double=6.0) {
    companion object {
        fun read(y:YamlConfiguration):DungeonGuideSettings {
            if(!y.contains("npc"))return DungeonGuideSettings()
            require(y.isConfigurationSection("npc")) {"npc must be a mapping"}
            require(y.isBoolean("npc.enabled")) {"npc.enabled must be boolean"}
            val name=y.getString("npc.name","oneblock_dungeon_guide")!!
            require(name.matches(Regex("[a-z][a-z0-9_-]{0,47}"))) {"npc.name"}
            val display=y.getString("npc.display-name","<gold>Провідник данжів")!!
            require(display.isNotBlank() && display.length<=128 && display.none(Char::isISOControl)) {"npc.display-name"}
            val type=y.getString("npc.type","VILLAGER")!!
            require(type in setOf("VILLAGER","WANDERING_TRADER","PLAYER")) {"npc.type: VILLAGER, WANDERING_TRADER or PLAYER"}
            val skin=y.getString("npc.skin","")!!
            require(skin.isEmpty() || skin.matches(Regex("[A-Za-z0-9_]{1,16}|[0-9a-fA-F-]{36}")) ||
                skin.matches(Regex("https://textures\\.minecraft\\.net/texture/[0-9a-fA-F]+"))) {"npc.skin: player name, UUID or https://textures.minecraft.net/texture/..."}
            require(type=="PLAYER" || skin.isEmpty()) {"npc.skin is only supported when npc.type is PLAYER"}
            val distance=y.getDouble("npc.interaction-distance",6.0).also {require(it.isFinite() && it in 1.0..16.0) {"npc.interaction-distance must be 1..16"}}
            return DungeonGuideSettings(y.getBoolean("npc.enabled"),name,display,type,skin,distance)
        }
    }
}
data class DeathRewardTier(val deaths:Int,val guaranteed:Double,val chance:Double)
data class MineSettings(val enabled:Boolean,val allowWorlds:Boolean,val name:String,val description:String,
    val maxSessions:Int,val attempts:Int,val reconnectSeconds:Int,val maxSeconds:Int,val blocksPerBatch:Int,
    val entry:MineEntry,val mobCount:Int,val mobHealth:Double,val bossHealth:Double,val hpPerExtra:Double,
    val damagePerExtra:Double,val cleaveDamage:Double,val areaDamage:Double,val warningTicks:Long,
    val attackTicks:Long,val puzzle:List<Int>,val tiers:List<DeathRewardTier>,val bossReward:CombatReward,
    val secretReward:CombatReward,val texts:Map<String,String>,val sidebar:List<String>,
    val mobsPerExtra:Int=2,val cleaveLength:Double=5.5,val cleaveWidth:Double=1.8,val areaRadius:Double=3.0,
    val npc:DungeonGuideSettings=DungeonGuideSettings(),val estimatedMinutes:String="10–15",val presentation:MinePresentation?=null,val waves:WaveRules?=null,val roomRewards:com.alytanta.oneblock.rewards.RoomRewardRules?=null) {
    val appearance:MinePresentation get()=presentation?:MinePresentation()
    fun tier(deaths:Int)=tiers.last {deaths>=it.deaths}
    fun reward(base:CombatReward,deaths:Int):CombatReward {
        val tier=tier(deaths)
        return CombatReward(base.guaranteed.map {it.copy(amount=maxOf(1,(it.amount*tier.guaranteed).toInt()))},
            base.trophies.map {it.copy(chance=it.chance*tier.chance)})
    }
    fun text(key:String,vararg args:Pair<String,Any?>):String=(mapOf<String,Any?>("name" to name,"attempts" to attempts,"seconds" to reconnectSeconds,"order" to puzzle.joinToString(" → "),
        "world" to entry.world,"x" to entry.point.x,"y" to entry.point.y,"z" to entry.point.z,"radius" to entry.radius)+args.toMap())
        .entries.fold(texts[key]?:defaultMessages[key]?:key) {s,(k,v)->s.replace("{$k}",v.toString())}
    companion object {
        private val defaultMessages:Map<String,String> by lazy {
            val y=YamlConfiguration().apply {loadFromString(MineSettings::class.java.getResourceAsStream("/dungeon.yml")!!.reader(Charsets.UTF_8).use {it.readText()})}
            y.getConfigurationSection("messages")!!.getValues(false).mapValues {it.value.toString()}
        }
        fun read(file:File):Pair<MineSettings?,String?> {
            if(!file.exists())return null to null
            val source=file.readText(Charsets.UTF_8);StrictYaml.validate(source)
            val y=YamlConfiguration().apply {loadFromString(source)}
            require(y.getInt("version")==1 && y.isBoolean("enabled") && y.isBoolean("allow-instance-worlds"))
            fun integer(k:String,min:Int,max:Int):Int=y.get(k).toString().toIntOrNull()?.also {require(it in min..max) {k}}?:error("Invalid integer $k")
            fun number(k:String,min:Double,max:Double):Double=y.getDouble(k).also {require(y.get(k) is Number && it.isFinite() && it in min..max) {k}}
            fun rewards(k:String):CombatReward {
                fun entries(part:String)=y.getMapList("$k.$part").map {row->RewardEntry(row["item"].toString().also {require(it.matches(Regex("[a-z][a-z0-9_]{0,47}")))},
                    row["amount"].toString().toInt().also {require(it in 1..16)},row["chance"]?.toString()?.toDouble()?:1.0).also {require(it.chance.isFinite() && it.chance in 0.0..1.0)}}
                return CombatReward(entries("guaranteed"),entries("trophies")).also {require(it.guaranteed.isNotEmpty() && it.guaranteed.all {e->e.chance==1.0});require(it.guaranteed.sumOf {e->e.amount}+it.trophies.sumOf {e->e.amount}<=64)}
            }
            val tiers=y.getMapList("death-rewards").map {row->DeathRewardTier(row["deaths"].toString().toInt(),row["guaranteed"].toString().toDouble(),row["chance"].toString().toDouble())}
            require(tiers.isNotEmpty() && tiers.first().deaths==0 && tiers.all {it.deaths>=0 && it.guaranteed.isFinite() && it.guaranteed in 0.0..1.0 && it.chance.isFinite() && it.chance in 0.0..1.0} && tiers.zipWithNext().all {(a,b)->a.deaths<b.deaths && a.guaranteed>=b.guaranteed && a.chance>=b.chance})
            val world=y.getString("entry.world","")!!
            require(!y.getBoolean("enabled") || world.isNotBlank());if(world.isNotBlank())require(net.kyori.adventure.key.Key.key(world).namespace() !in setOf("oneblock","oneblock_dungeons")) {"Entry must be a permanent hub world"}
            val puzzle=y.getIntegerList("puzzle.sequence");require(puzzle.size in 2..6 && puzzle.all {it in 1..3})
            val texts=requireNotNull(y.getConfigurationSection("messages")).getKeys(false).associateWith {y.getString("messages.$it")!!}
            val sidebar=y.getStringList("sidebar");require(sidebar.size in 1..15)
            return MineSettings(y.getBoolean("enabled"),y.getBoolean("allow-instance-worlds"),y.getString("name")!!,y.getString("description")!!,
                integer("max-sessions",1,64),integer("attempts",1,20),integer("reconnect-seconds",5,600),integer("max-seconds",60,7200),integer("blocks-per-batch",32,4096),
                MineEntry(world,MinePoint(number("entry.x",-29999900.0,29999900.0),number("entry.y",-64.0,320.0),number("entry.z",-29999900.0,29999900.0)),number("entry.radius",1.0,32.0)),
                integer("mobs.count",1,20),number("mobs.health",1.0,200.0),number("boss.health",20.0,3000.0),number("scaling.health-per-extra",0.0,2.0),number("scaling.damage-per-extra",0.0,1.0),
                number("boss.cleave-damage",1.0,40.0),number("boss.area-damage",1.0,40.0),integer("boss.warning-ticks",10,100).toLong(),integer("boss.attack-ticks",40,400).toLong(),
                puzzle,tiers,rewards("rewards.boss"),rewards("rewards.secret"),texts,sidebar,
                if(y.contains("mobs.per-extra"))integer("mobs.per-extra",0,10) else 2,
                if(y.contains("boss.cleave-length"))number("boss.cleave-length",1.0,12.0) else 5.5,
                if(y.contains("boss.cleave-width"))number("boss.cleave-width",.5,5.0) else 1.8,
                if(y.contains("boss.area-radius"))number("boss.area-radius",.5,8.0) else 3.0,
                DungeonGuideSettings.read(y),y.getString("estimated-minutes","10–15")!!.also {require(it.isNotBlank() && it.length<=40 && it.none(Char::isISOControl))},MinePresentation.read(y),WaveRules.read(y),com.alytanta.oneblock.rewards.RoomRewardRules.read(y)) to source
        }
    }
    fun validateCatalog(rules:CombatRules) {
        roomRewards?.validate(rules)
        (bossReward.guaranteed+bossReward.trophies+secretReward.guaranteed+secretReward.trophies).forEach {e->
            val item=rules.items.getValue(e.item);require(item.kind==CombatKind.COMPONENT || e.amount==1)
        }
    }
}
