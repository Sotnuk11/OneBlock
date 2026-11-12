package com.alytanta.oneblock.ui

import org.bukkit.entity.Player
import java.util.Optional

/** Per-viewer packets only: never accesses the global Bukkit/NMS server scoreboard on Folia. */
class ClientDisplayPackets {
    private fun type(name:String)=Class.forName(name)
    private val board=type("net.minecraft.world.scores.Scoreboard")
    private val objective=type("net.minecraft.world.scores.Objective")
    private val component=type("net.minecraft.network.chat.Component")
    private val criteria=type("net.minecraft.world.scores.criteria.ObjectiveCriteria")
    private val render=type("net.minecraft.world.scores.criteria.ObjectiveCriteria\$RenderType")
    private val format=type("net.minecraft.network.chat.numbers.NumberFormat")
    private val blank=type("net.minecraft.network.chat.numbers.BlankFormat").getField("INSTANCE").get(null)
    private val slot=type("net.minecraft.world.scores.DisplaySlot")
    private val team=type("net.minecraft.world.scores.PlayerTeam")
    private val collision=type("net.minecraft.world.scores.Team\$CollisionRule")
    private val chat=type("org.bukkit.craftbukkit.util.CraftChatMessage").getMethod("fromStringOrEmpty",String::class.java)
    private val craftPlayer=type("org.bukkit.craftbukkit.entity.CraftPlayer")
    private val handle=craftPlayer.getMethod("getHandle")
    private val connection=handle.returnType.getField("connection")
    private val send=connection.type.getMethod("send",type("net.minecraft.network.protocol.Packet"))
    private val objectivePacket=type("net.minecraft.network.protocol.game.ClientboundSetObjectivePacket").getConstructor(objective,Int::class.javaPrimitiveType)
    private val displayPacket=type("net.minecraft.network.protocol.game.ClientboundSetDisplayObjectivePacket").getConstructor(slot,objective)
    private val scorePacket=type("net.minecraft.network.protocol.game.ClientboundSetScorePacket").getConstructor(String::class.java,String::class.java,Int::class.javaPrimitiveType,Optional::class.java,Optional::class.java)
    private val resetPacket=type("net.minecraft.network.protocol.game.ClientboundResetScorePacket").getConstructor(String::class.java,String::class.java)
    private val teamPacket=type("net.minecraft.network.protocol.game.ClientboundSetPlayerTeamPacket")
    private val addTeam=teamPacket.getMethod("createAddOrModifyPacket",team,Boolean::class.javaPrimitiveType)
    private val removeTeam=teamPacket.getMethod("createRemovePacket",team)
    private fun text(value:String)=chat.invoke(null,org.bukkit.ChatColor.translateAlternateColorCodes('&',value))
    private fun send(player:Player,packet:Any) { send.invoke(connection.get(handle.invoke(player)),packet) }
    private fun newBoard()=board.getConstructor().newInstance()
    private fun objective(title:String)=objective.getConstructor(board,String::class.java,criteria,component,render,Boolean::class.javaPrimitiveType,format)
        .newInstance(newBoard(),"ob_sidebar",criteria.getField("DUMMY").get(null),text(title),render.getField("INTEGER").get(null),false,blank)
    class View { internal var objective:Any?=null;internal var title="";internal var lines=emptyList<String>();internal var team:Any?=null }
    fun sidebar(player:Player,view:View,title:String,lines:List<String>) {
        require(lines.size<=15)
        if(view.objective==null) {
            view.objective=objective(title);view.title=title
            send(player,objectivePacket.newInstance(view.objective,0))
            send(player,displayPacket.newInstance(slot.getField("SIDEBAR").get(null),view.objective))
        } else if(view.title!=title) {
            objective.getMethod("setDisplayName",component).invoke(view.objective,text(title));view.title=title
            send(player,objectivePacket.newInstance(view.objective,2))
        }
        lines.forEachIndexed { index,line -> if(view.lines.getOrNull(index)!=line)send(player,scorePacket.newInstance("ob_line_$index","ob_sidebar",15-index,Optional.of(text(line)),Optional.of(blank))) }
        for(index in lines.size until view.lines.size)send(player,resetPacket.newInstance("ob_line_$index","ob_sidebar"))
        view.lines=lines.toList()
    }
    fun hide(player:Player,view:View) {
        view.objective?.let { send(player,objectivePacket.newInstance(it,1)) };view.objective=null;view.lines=emptyList()
    }
    fun collision(player:Player,view:View,disabled:Boolean) {
        if(disabled&&view.team==null) {
            val value=team.getConstructor(board,String::class.java).newInstance(newBoard(),"ob_hub_nopush")
            team.getMethod("setCollisionRule",collision).invoke(value,collision.getField("NEVER").get(null))
            @Suppress("UNCHECKED_CAST") val members=team.getMethod("getPlayers").invoke(value) as MutableCollection<String>
            members.add(player.name);view.team=value;send(player,addTeam.invoke(null,value,true))
        } else if(!disabled&&view.team!=null) { send(player,removeTeam.invoke(null,view.team));view.team=null }
    }
}
