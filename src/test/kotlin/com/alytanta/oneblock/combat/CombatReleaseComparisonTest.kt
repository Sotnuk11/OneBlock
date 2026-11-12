package com.alytanta.oneblock.combat

import com.alytanta.oneblock.items.ItemCatalog
import com.google.gson.GsonBuilder
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.math.*

/** Explicit stationary-target envelope, not a gameplay/latency/accuracy benchmark. */
class CombatReleaseComparisonTest {
    @TempDir lateinit var root:Path
    private fun resource(name:String)=root.resolve(name).toFile().apply {writeText(this@CombatReleaseComparisonTest.javaClass.getResourceAsStream("/$name")!!.reader().readText())}
    @Test fun `same targets include combined bonuses finite mana multishot and opportunity cost`() {
        val catalog=ItemCatalog.read(resource("items.yml")).first!!
        val r=CombatRules.read(resource("combat.yml"),catalog,(1..3).flatMap {listOf("pickaxe_$it","axe_$it","hoe_$it")}.toSet()).first!!
        for(set in listOf("warden","ranger","weaver")) {
            val parts=r.items.values.filter {it.kind==CombatKind.ARMOR && it.set==set}
            assertEquals(24.0,parts.sumOf {it.definition!!.armorCompensation})
            assertEquals(16.0,parts.sumOf {it.definition!!.armorToughnessCompensation})
        }
        fun stats(set:String,final:Boolean)=CombatMath.stats(r,listOf("HEAD" to "helmet","CHEST" to "chestplate","LEGS" to "leggings","FEET" to "boots").take(if(final)4 else 2).associate {(slot,part)->slot to "${set}_$part"})
        val rows=mutableListOf<Map<String,Any>>()
        for(final in listOf(false,true))for(duration in listOf(20.0,120.0))for(targets in listOf(1,4)) {
            val sharp=if(final)3.0 else 2.0 // Sharpness V / III; normal, no incompatible enchantments
            fun row(weapon:String,damage:Double,actions:Int,resource:String,extra:String="") {
                rows+=mapOf("tier" to if(final)"final" else "middle_two_armor_pieces","seconds" to duration,"targets" to targets,"weapon" to weapon,"totalDamageBeforeTargetArmor" to damage,"dps" to damage/duration,"actions" to actions,"resources" to resource,"conditions" to extra)
            }
            val w=stats("warden",final);val rr=stats("ranger",final);val m=stats("weaver",final)
            val sword=r.items.getValue(if(final)"nether_edge" else "warden_blade")
            val hits=ceil(duration*1.6).toInt();val primary=(if(final)8.0 else 6.0)+sharp+sword.damage
            val precision=if(final)floor(hits/3.0)*primary*.25 else 0.0
            val direct=(hits*primary+precision)*w.meleeMultiplier
            // Standing Sweeping Edge III (75%) or II (2/3); no critical/sprint sweep stacking.
            // Native Player.doSweepAttack: (1 + raw attack attribute * sweep ratio) then enchant damage.
            // Custom melee/precision handlers only modify ENTITY_ATTACK, not ENTITY_SWEEP_ATTACK.
            val sweep=if(targets>1)hits*(1+(if(final)8.0 else 6.0)*(if(final).75 else 2.0/3)+sharp)*(targets-1) else 0.0
            row(sword.id,direct+sweep,hits,"$hits durability uses before Unbreaking; melee exposure", "standing full-charge hits; sweep targets within native close radius; precision only primary")
            val critPrimary=(if(final)8.0 else 6.0)*1.5+sharp+sword.damage
            row("${sword.id}_critical",(hits*critPrimary+if(final)floor(hits/3.0)*critPrimary*.25 else 0.0)*w.meleeMultiplier,hits,"same durability, jumping melee exposure", "all hits meet vanilla critical conditions; no sweep damage, including against four targets")
            val cleave=catalog.require("rift_cleaver").effects("wide_cleave").single()
            val casts=ceil(duration/cleave.number("cooldown-seconds")).toInt()
            // Switching to axe resets precision and occupies a full 1-second axe attack opportunity.
            val axeHits=(floor(duration).toInt()-casts).coerceAtLeast(0)
            val axe=(9.0+sharp+r.items.getValue("rift_cleaver").damage)*w.meleeMultiplier
            row("rift_cleaver",axeHits*axe+casts*(cleave.number("damage")+sharp)*w.meleeMultiplier*targets,axeHits+casts,"no mana; native melee durability; close frontal cone", "one cleave replaces a normal axe hit; six-second ability cooldown; no sword precision retained")
            val bow=r.items.getValue("ranger_bow");val shots=ceil(duration/1.1).toInt()
            // Native full-charge critical arrow mean envelope at the near target, Power III / V.
            // Actual random velocity/critical damage samples are retained in native-balance.json.
            val nativeBow=if(final)19.0 else 15.0
            val focus=catalog.require("ranger_bow").effects("ranged_focus").single()
            val bowDamage=(0 until shots).sumOf {n->nativeBow*bow.multiplier*rr.rangedMultiplier*(1+(n*focus.number("step")).coerceAtMost(focus.number("cap")+if(final)r.sets.getValue("ranger").strength else 0.0))}
            row("ranger_bow",bowDamage,shots,"$shots arrows; Infinity can save arrows, excludes Mending", "single maintained target; other targets are not hit; switching resets focus")
            val crossShots=ceil(duration/(if(final).5 else .75)).toInt() // Quick Charge III / II
            val crossBase=9.0 // explicit 7..11 native arrow envelope, not Power (crossbow cannot use it)
            val mark=catalog.require("ember_crossbow").effects("ember_mark").single()
            val markCount=floor((duration-mark.number("delay-ticks")/20).coerceAtLeast(0.0)/mark.number("cooldown-seconds")).toInt()+1
            val multishotTargets=min(targets,3)
            val cross=r.items.getValue("ember_crossbow")
            val shotDamage=crossBase*cross.multiplier*rr.rangedMultiplier
            val crossFocus=if(final)(0 until crossShots).sumOf {min(it*.01,.04)} else 0.0
            val markPotential=markCount*shotDamage*(if(final)1.04 else 1.0)*mark.number("bonus")*min(targets,mark.number("targets").toInt())
            // On an uninterrupted 0.5 / 0.75-second schedule, the 1.2-second delayed small
            // hit falls inside a stronger arrow's native hurt window. It is not free added DPS.
            row("ember_crossbow",shotDamage*(crossShots*multishotTargets+crossFocus),crossShots,"$crossShots arrows; multishot consumes one arrow, three projectile durability uses", "Quick Charge + Multishot; full-set focus on maintained primary only; native hurt immunity suppresses small marks during this continuous schedule; a firing pause or fresh target can receive one burst")
            rows[rows.lastIndex]=rows.last()+mapOf("markPotentialDamageBeforeNativeHurtWindows" to markPotential,"markPotentialDpsUpperBoundBeforeNativeHurtWindows" to markPotential/duration)
            for(id in listOf("frost_wand","rift_staff")) {
                val item=r.items.getValue(id);val mana=ManaPool(m.maxMana,0);var count=0;var spent=0.0;var refunded=0.0
                // Use the production pool on a 50 ms grid. Successful completed spells only.
                for(tick in 0 until (duration*20).toInt())if(mana.spend(tick*50_000_000L,m,r.manaPerSecond*m.regenMultiplier,item.mana,item.cooldown)) {
                    count++;spent+=item.mana
                    if(final && count%4==0){val amount=item.mana*r.sets.getValue("weaver").strength;mana.value=(mana.value+amount).coerceAtMost(m.maxMana);refunded+=amount}
                }
                val multiplier=if(id=="frost_wand")min(targets,4).toDouble() else if(targets==1)1.0 else 1.75
                row(id,count*item.damage*m.magicMultiplier*multiplier,count,"mana gross=$spent refund=$refunded; regen=${r.manaPerSecond*m.regenMultiplier}/s; no ammunition",if(id=="frost_wand")"four targets within radius 3; slow II, boss duration zero" else "primary + two chain targets within 3 blocks and line of sight; falloff 0.5; fourth target untouched")
            }
            val vanillaHits=ceil(duration*1.6).toInt();val vanillaBase=(if(final)8.0 else 7.0)+sharp
            row("vanilla_${if(final)"netherite" else "diamond"}_sword",vanillaHits*(vanillaBase+if(targets>1)(1+(if(final)8.0 else 7.0)*(if(final).75 else 2.0/3)+sharp)*(targets-1) else 0.0),vanillaHits,"native durability; no mana", "same Sharpness and Sweeping; no custom damage, defense or precision")
            row("vanilla_${if(final)"netherite" else "diamond"}_sword_critical",vanillaHits*((if(final)8.0 else 7.0)*1.5+sharp),vanillaHits,"native durability; no mana", "jumping critical, no simultaneous sweep")
            row("vanilla_bow",shots*nativeBow,shots,"same ammunition/enchantments", "Power III / V, same 1.1-second opportunity; native shot RNG")
            row("vanilla_crossbow",crossShots*crossBase*multishotTargets,crossShots,"same ammunition and durability", "same Quick Charge and Multishot; no mark")
        }
        fun damageAfter(raw:Double,toughness:Double,defense:Double):Double {
            val custom=raw*(1-defense)
            val armorReduction=min(20.0,max(20.0/5,20.0-custom/(2+toughness/4)))/25
            return custom*(1-armorReduction)*.36 // four Protection IV: 16 EPF
        }
        val survival=listOf(10.0,20.0,40.0).map {raw->mapOf("incoming" to raw,"diamondProtectionIV" to damageAfter(raw,8.0,0.0),"netheriteProtectionIV" to damageAfter(raw,12.0,0.0),"wardenProtectionIV" to damageAfter(raw,8.0,stats("warden",true).defense),"wardenActiveGuard" to damageAfter(raw*.96,8.0,stats("warden",true).defense),"rangerAndWeaverProtectionIV" to damageAfter(raw,8.0,0.0))}
        assertTrue(damageAfter(40.0,12.0,0.0)<damageAfter(40.0,8.0,.04),"Netherite must retain heavy-hit protection advantage")
        val recovery=ActionRecovery();recovery.used(0,1.4);assertTrue(recovery.blocksNative(1_000_000_000));assertFalse(recovery.canUse(1_000_000_000));assertTrue(recovery.canUse(1_400_000_000))
        val out=Path.of("build/reports/release10-combat.json").toFile();out.parentFile.mkdirs()
        out.writeText(GsonBuilder().setPrettyPrinting().create().toJson(mapOf("rows" to rows,"survival" to survival,"assumptions" to listOf("stationary, unarmored targets, 100 percent accuracy, 20 TPS; optimistic envelopes, not human DPS","middle gear: two matching armor pieces, Sharpness III/Power III, Quick Charge II; final full set, level V damage enchants and Quick Charge III","standing native sweep and jumping critical are alternatives; single-target critical can improve melee without sweep","four clustered targets meet each ability geometry; scattered enemies lower area damage, boss control scale zero","crossbow critical/velocity and bow means are explicit model inputs; only native-balance.json contains actual measured damage","hybrid actions consume shared recovery; no free spell plus melee/bow; switching resets target sequences","incoming model checked separately with actual native damage events; Netherite also retains vanilla knockback resistance","no Thorns, potions, beacons or external plugin buffs; identical additions require another comparison"))))
    }
}
