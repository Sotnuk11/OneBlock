package com.alytanta.oneblock.release

import com.alytanta.oneblock.island.IslandService
import com.alytanta.oneblock.util.Colors
import org.bukkit.NamespacedKey
import org.bukkit.entity.Player
import org.bukkit.event.*
import org.bukkit.event.player.PlayerItemDamageEvent
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataType
import org.bukkit.plugin.Plugin
import kotlin.random.Random

class ReleaseTools(plugin: Plugin, private val islands: IslandService, private val current: () -> ReleaseRules?,
    private val text: (Player,String) -> String = { _,key -> key }, private val blocked: (java.util.UUID) -> Boolean = { false }) : Listener {
    private val identity=NamespacedKey(plugin,"release_tool")
    private val version=NamespacedKey(plugin,"release_tool_version")
    private val hinted=java.util.concurrent.ConcurrentHashMap<java.util.UUID,Long>()
    var worldRole:(Player)->String={"island"}
    fun create(item: ReleaseItem, actor: Player? = null, rules:ReleaseRules? = current()): ItemStack {
        val id=requireNotNull(item.tool)
        rules?.customItems?.find(id)?.let {d->require(d.material==item.material);return com.alytanta.oneblock.items.CustomItems.create(d,item.amount)}
        rules?.combat?.items?.get(id)?.let { require(it.material==item.material);return com.alytanta.oneblock.combat.CombatItems.create(it,item.amount) }
        return ItemStack(org.bukkit.Material.valueOf(item.material),item.amount).apply {
        val r=rules?.recipes?.get(id)
        editMeta { meta ->
            meta.persistentDataContainer.set(identity,PersistentDataType.STRING,id)
            meta.persistentDataContainer.set(version,PersistentDataType.INTEGER,1)
            meta.displayName(Colors.colorize(if(actor==null)"&aOneBlock ${id.replace('_',' ')}" else "&a"+text(actor,"release.name.$id")))
            if(actor!=null && r!=null)meta.lore(listOf(
                Colors.colorize(text(actor,"release.recipe.requirements").replace("{chapter}",text(actor,"release.name.${r.chapter}")).replace("{skill}",text(actor,"release.name.${r.skill}")).replace("{level}",r.level.toString())),
                Colors.colorize(text(actor,"release.effect.$id").replace("{durability}","${(r.durability*100).toInt()}").replace("{sapling}","${(r.sapling*100).toInt()}"))))
            meta.lore(meta.lore().orEmpty()+Colors.colorize((com.alytanta.oneblock.items.CustomItems.current()?.visibleIdFormat?:"&8ID: {id}").replace("{id}","oneblock:$id")))
        }
        }
    }
    fun recipe(player: Player, item: ItemStack): RecipeRule? {
        val rules=current()?.takeIf { it.enabled } ?: return null
        if(!item.hasItemMeta())return null
        val pdc=item.itemMeta?.persistentDataContainer ?: return null
        if(pdc.get(version,PersistentDataType.INTEGER)!=1)return null
        val id=pdc.get(identity,PersistentDataType.STRING) ?: return null
        if(rules.customItems!=null && com.alytanta.oneblock.items.CustomItems.resolve(item,rules.customItems)?.id!="oneblock:$id")return null
        val recipe=rules.recipes[id] ?: return null
        if(recipe.items.none { it.tool==id && it.material==item.type.name })return null
        val island=islands.findForPlayer(player.uniqueId) ?: return null
        if(!islands.persistenceAvailable() || blocked(island.owner))return null
        val progress=island.releaseProgress ?: return null
        if(!ReleaseDomain(rules).active(island,player.uniqueId) || !rules.atLeast(progress.chapter,recipe.chapter) ||
            rules.level(progress.member(player.uniqueId).xp[recipe.skill]?:0)<recipe.level)return null
        val abilities=rules.customItems?.find(id)?.effects("tool_traits").orEmpty().filter {a->a.permission?.let {!player.hasPermission(it)}!=true && worldRole(player) in a.worlds}
        if(rules.customItems==null)return recipe
        if(abilities.isEmpty())
            return recipe.copy(durability=0.0,sapling=0.0,replant=emptySet())
        return recipe.copy(durability=abilities.sumOf {it.number("durability")},sapling=abilities.sumOf {it.number("sapling")})
    }
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true)
    fun damage(event: PlayerItemDamageEvent) {
        if(event.damage<=0 || event.player.gameMode!=org.bukkit.GameMode.SURVIVAL)return
        val rule=recipe(event.player,event.item) ?: return
        if(Random.nextDouble()<rule.durability)event.isCancelled=true
    }
    @EventHandler(priority=EventPriority.MONITOR,ignoreCancelled=true)
    fun use(event: org.bukkit.event.player.PlayerInteractEvent) {
        val item=event.item ?: return
        val pdc=item.itemMeta?.persistentDataContainer ?: return
        if(pdc.get(version,PersistentDataType.INTEGER)!=1 || pdc.get(identity,PersistentDataType.STRING)==null || recipe(event.player,item)!=null)return
        val now=System.currentTimeMillis();val previous=hinted[event.player.uniqueId]?:0
        if(now-previous<5000)return
        hinted[event.player.uniqueId]=now
        event.player.sendMessage(Colors.colorize(text(event.player,"release.message.requirements")))
    }
    @EventHandler fun quit(event: org.bukkit.event.player.PlayerQuitEvent) { hinted.remove(event.player.uniqueId) }
}
