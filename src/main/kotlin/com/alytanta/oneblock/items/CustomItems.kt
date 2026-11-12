package com.alytanta.oneblock.items

import com.alytanta.oneblock.util.Colors
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.inventory.EquipmentSlot
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataType
import org.bukkit.Bukkit
import org.bukkit.attribute.Attribute
import org.bukkit.attribute.AttributeModifier
import org.bukkit.inventory.meta.LeatherArmorMeta
import org.bukkit.inventory.meta.SkullMeta
import org.bukkit.profile.PlayerProfile
import java.net.URI
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap

/** Canonical identity plus preserved schema-1 aliases. Names, lore, model and tags never authenticate an item. */
object CustomItems {
    private fun key(value:String)=NamespacedKey("one-block",value)
    private val identity=key("item_id");private val schema=key("item_schema");private val appearance=key("item_appearance")
    private val combat=key("combat_item");private val combatSchema=key("combat_schema")
    private val visibleLine=key("item_visible_id_line")
    private val tool=key("release_tool");private val toolSchema=key("release_tool_version")
    @Volatile var current:()->ItemCatalog?={null}
    /** Lets loaded player inventories retry pending profile-backed helmets when their one-time lookup completes. */
    @Volatile var onHeadProfileResolved:(()->Unit)?=null
    /** Custom item/equipment model components are safe only when every joining client must have the pack. */
    @Volatile var customModelsEnabled:Boolean=false
    /** Profile-name lookups happen once on the common pool; a miss is cached and items keep their vanilla head fallback. */
    private val headProfileUrls=ConcurrentHashMap<String,CompletableFuture<String?>>()
    private data class HeadResolution(val url:String?,val pending:Boolean)
    fun id(stack:ItemStack?):String? {
        if(stack==null || stack.isEmpty || !stack.hasItemMeta())return null
        val p=stack.itemMeta?.persistentDataContainer?:return null
        val canonical=p.get(identity,PersistentDataType.STRING)
        if(p.has(identity) || p.has(schema))return canonical?.takeIf {p.get(schema,PersistentDataType.INTEGER)==2 && ItemCatalog.validId(it) &&
            (!p.has(combat) || p.get(combatSchema,PersistentDataType.INTEGER)==1 && p.get(combat,PersistentDataType.STRING)?.let(ItemCatalog::canonical)==it) &&
            (!p.has(tool) || p.get(toolSchema,PersistentDataType.INTEGER)==1 && p.get(tool,PersistentDataType.STRING)?.let(ItemCatalog::canonical)==it)}
        val legacy=p.get(combat,PersistentDataType.STRING)?.takeIf {p.get(combatSchema,PersistentDataType.INTEGER)==1}
        val release=p.get(tool,PersistentDataType.STRING)?.takeIf {p.get(toolSchema,PersistentDataType.INTEGER)==1}
        if(legacy!=null && release!=null && legacy!=release)return null
        return (legacy?:release)?.let(ItemCatalog::canonical)?.takeIf(ItemCatalog::validId)
    }
    fun marked(stack:ItemStack?)=stack?.hasItemMeta()==true && stack.itemMeta?.persistentDataContainer?.let {p->listOf(identity,schema,combat,combatSchema,tool,toolSchema).any(p::has)}==true
    fun resolve(stack:ItemStack?,catalog:ItemCatalog?=current()):ItemDefinition?=id(stack)?.let {catalog?.find(it)}?.takeIf {it.material==stack?.type?.name}
    /** Only auction/P2P admission. This flag does not create ownership or prohibit ordinary container/drop operations. */
    fun marketTransferAllowed(stack:ItemStack?)=!marked(stack) || current()==null || resolve(stack)?.marketTransfer==true
    fun create(definition:ItemDefinition,amount:Int=1):ItemStack {
        require(amount in 1..definition.stackSize) {"${definition.id}: amount exceeds configured stack size"}
        return ItemStack(Material.valueOf(definition.material),amount).apply {style(this,definition,resolveHead(definition))}
    }
    /** Only called on entity-owned inventory copies outside a pending handoff. Preserves damage, enchantments, amount and unrelated PDC. */
    fun refresh(stack:ItemStack,catalog:ItemCatalog?=current()):Boolean {
        val id=id(stack)?:return false
        val definition=catalog?.find(id)?.takeIf { it.material==stack.type.name || stack.type.name in it.migrationFrom }?:return false
        val idChanged=ensureVisibleId(stack,catalog)
        // Keep the captured state consistent through styling if an async profile lookup completes in between.
        val head=resolveHead(definition)
        if(stack.itemMeta.persistentDataContainer.get(appearance,PersistentDataType.STRING)==presentationHash(definition,head))return idChanged
        // A reload must not truncate stacks or split durable inventory intents. Refuse incompatible definitions instead.
        if(stack.amount>definition.stackSize)return false
        val oldMax=stack.type.maxDurability.toInt().coerceAtLeast(0)
        val oldDamage=(stack.itemMeta as? org.bukkit.inventory.meta.Damageable)?.damage?:0
        if(stack.type.name!=definition.material) {
            // Bukkit's stack carrier mutation retains vanilla enchantments and ItemMeta/PDC; only damage is normalized below.
            stack.type=Material.valueOf(definition.material)
            val meta=stack.itemMeta
            if(meta is org.bukkit.inventory.meta.Damageable) {
                val newMax=stack.type.maxDurability.toInt().coerceAtLeast(0)
                meta.damage=if(newMax==0 || oldMax==0)0 else ((oldDamage.toDouble()/oldMax)*newMax).toInt().coerceIn(0,(newMax-1).coerceAtLeast(0))
                stack.itemMeta=meta
            }
        }
        val previous=stack.itemMeta.persistentDataContainer.get(appearance,PersistentDataType.STRING)
        val preservePersonal=previous==null || previous.substringBeforeLast(':')==presentationHash(definition,head).substringBeforeLast(':')
        val personalName=stack.itemMeta.displayName();val personalLore=stack.itemMeta.lore()
        style(stack,definition,head)
        // First legacy migration and cached skin resolution retain the player's rename and foreign lore.
        if(preservePersonal)stack.editMeta {meta->meta.displayName(personalName);meta.lore(personalLore)}
        else stack.editMeta {meta->
            // Balance/stat changes also change the appearance hash. They must not erase personal annotations.
            if(personalName!=Colors.colorize(definition.name))meta.displayName(personalName)
            val generated=(definition.lore.map(Colors::colorize)+Colors.colorize(catalog!!.visibleIdFormat.replace("{id}",definition.id))).toSet()
            val foreign=personalLore.orEmpty().filter {it !in generated}
            meta.lore((meta.lore().orEmpty()+foreign).distinct())
        }
        return true
    }
    /** Lore-only migration: keeps names, enchantments, damage, skin, attributes and foreign lore/PDC. */
    fun ensureVisibleId(stack:ItemStack,catalog:ItemCatalog?=current()):Boolean {
        if(catalog==null)return false
        val actual=id(stack)?:return false
        if(catalog?.find(actual)==null)return false
        val meta=stack.itemMeta;val line=catalog.visibleIdFormat.replace("{id}",actual)
        val old=meta.persistentDataContainer.get(visibleLine,PersistentDataType.STRING)
        val component=Colors.colorize(line);val lore=meta.lore().orEmpty()
        if(old==line && lore.count {it==component}==1)return false
        val clean=if(old==null)lore else lore.filterNot {it==Colors.colorize(old)}
        meta.lore(clean+component);meta.persistentDataContainer.set(visibleLine,PersistentDataType.STRING,line)
        stack.itemMeta=meta;return true
    }
    private fun presentationHash(d:ItemDefinition,head:HeadResolution):String {
        val mode=if(customModelsEnabled) "pack" else "vanilla"
        val skin=when {d.headProfile==null && d.headTexture==null->"none";head.pending->"profile-pending";head.url!=null->"textured";else->"profile-fallback"}
        return "$mode:${d.appearanceHash}:$skin"
    }
    private fun resolveHead(d:ItemDefinition):HeadResolution {
        d.headTexture?.let {return HeadResolution(HeadTextures.url(it),false)}
        val name=d.headProfile?:return HeadResolution(null,false)
        val future=headProfileUrls.computeIfAbsent(name.lowercase()) {
            CompletableFuture.supplyAsync {
                runCatching {Bukkit.createPlayerProfile(name).update().get().textures.skin?.toExternalForm()}.getOrNull()
            }.whenComplete { _,_->runCatching {onHeadProfileResolved?.invoke()} }
        }
        if(!future.isDone || future.isCompletedExceptionally)return HeadResolution(null,true)
        return HeadResolution(future.getNow(null),false)
    }
    private fun style(stack:ItemStack,d:ItemDefinition,head:HeadResolution) {
        stack.editMeta {meta->
            meta.persistentDataContainer.set(identity,PersistentDataType.STRING,d.id)
            meta.persistentDataContainer.set(schema,PersistentDataType.INTEGER,2)
            meta.persistentDataContainer.set(appearance,PersistentDataType.STRING,presentationHash(d,head))
            if(d.kind==ItemKind.TOOL) {
                meta.persistentDataContainer.set(tool,PersistentDataType.STRING,d.legacyId);meta.persistentDataContainer.set(toolSchema,PersistentDataType.INTEGER,1)
            } else {
                meta.persistentDataContainer.set(combat,PersistentDataType.STRING,d.legacyId);meta.persistentDataContainer.set(combatSchema,PersistentDataType.INTEGER,1)
            }
            fun expand(line:String)=d.stats.entries.fold(line) {text,(key,value)->
                text.replace("{$key}","%.2f".format(java.util.Locale.ROOT,value).trimEnd('0').trimEnd('.'))
                    .replace("{${key}_percent}","%.1f".format(java.util.Locale.ROOT,value*100).trimEnd('0').trimEnd('.'))}
            val rarity=mapOf("COMMON" to "Звичайний","UNCOMMON" to "Незвичайний","RARE" to "Рідкісний","EPIC" to "Епічний","LEGENDARY" to "Легендарний").getValue(d.rarity)
            val idLine=(current()?.visibleIdFormat?:"&8ID: {id}").replace("{id}",d.id)
            val tint=mapOf("COMMON" to "&f","UNCOMMON" to "&a","RARE" to "&9","EPIC" to "&d","LEGENDARY" to "&6").getValue(d.rarity)
            meta.displayName(Colors.colorize(tint+d.name.replace(Regex("^(&[0-9a-fA-Fk-orK-OR])+"),"")));meta.lore((d.lore.map(::expand)+"$tint$rarity"+idLine).map(Colors::colorize))
            meta.persistentDataContainer.set(visibleLine,PersistentDataType.STRING,idLine)
            if(d.model!=null && customModelsEnabled)meta.setItemModel(NamespacedKey.fromString(d.model))
            else if(meta.itemModel?.namespace=="oneblock")meta.setItemModel(null)
            meta.setMaxStackSize(d.stackSize)
            if(d.leatherColor!=null && meta is LeatherArmorMeta)meta.setColor(org.bukkit.Color.fromRGB(d.leatherColor.removePrefix("#").toInt(16)))
            if(d.headTexture!=null || d.headProfile!=null) {
                if(head.url!=null && meta is SkullMeta)runCatching {
                    val profile=Bukkit.createPlayerProfile(UUID.nameUUIDFromBytes(head.url.toByteArray()))
                    profile.textures.skin=URI(head.url).toURL();meta.ownerProfile=profile
                }
            }
            if(d.kind==ItemKind.ARMOR) {
                meta.isUnbreakable=true
                val key=NamespacedKey("one-block","armor_${d.legacyId}")
                meta.getAttributeModifiers(Attribute.ARMOR)?.filter {it.key==key}?.forEach {meta.removeAttributeModifier(Attribute.ARMOR,it)}
                if(d.armorCompensation>0.0)meta.addAttributeModifier(Attribute.ARMOR,AttributeModifier(key,d.armorCompensation,
                    AttributeModifier.Operation.ADD_NUMBER,when(requireNotNull(d.slot)) {
                        "HEAD"->org.bukkit.inventory.EquipmentSlotGroup.HEAD
                        "CHEST"->org.bukkit.inventory.EquipmentSlotGroup.CHEST
                        "LEGS"->org.bukkit.inventory.EquipmentSlotGroup.LEGS
                        "FEET"->org.bukkit.inventory.EquipmentSlotGroup.FEET
                        else->error("${d.id} has an invalid armor slot")
                    }))
                val toughnessKey=NamespacedKey("one-block","armor_toughness_${d.legacyId}")
                meta.getAttributeModifiers(Attribute.ARMOR_TOUGHNESS)?.filter {it.key==toughnessKey}?.forEach {meta.removeAttributeModifier(Attribute.ARMOR_TOUGHNESS,it)}
                if(d.armorToughnessCompensation>0.0)meta.addAttributeModifier(Attribute.ARMOR_TOUGHNESS,AttributeModifier(toughnessKey,d.armorToughnessCompensation,
                    AttributeModifier.Operation.ADD_NUMBER,when(requireNotNull(d.slot)) {
                        "HEAD"->org.bukkit.inventory.EquipmentSlotGroup.HEAD
                        "CHEST"->org.bukkit.inventory.EquipmentSlotGroup.CHEST
                        "LEGS"->org.bukkit.inventory.EquipmentSlotGroup.LEGS
                        "FEET"->org.bukkit.inventory.EquipmentSlotGroup.FEET
                        else->error("${d.id} has an invalid armor slot")
                    }))
            }
            if(d.equipmentModel!=null || d.kind==ItemKind.ARMOR) {
                val equipped=meta.equippable
                if(d.kind==ItemKind.ARMOR)equipped.slot=EquipmentSlot.valueOf(requireNotNull(d.slot))
                if(d.equipmentModel!=null && customModelsEnabled)equipped.model=NamespacedKey.fromString(d.equipmentModel)
                else if(equipped.model?.namespace=="oneblock")equipped.model=null
                meta.setEquippable(equipped)
            }
        }
    }
}
