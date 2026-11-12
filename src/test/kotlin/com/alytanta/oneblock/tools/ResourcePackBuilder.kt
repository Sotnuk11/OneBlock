package com.alytanta.oneblock.tools

import com.alytanta.oneblock.items.*
import com.google.gson.GsonBuilder
import java.awt.Color
import java.awt.Font
import java.awt.image.BufferedImage
import java.nio.file.*
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.imageio.ImageIO

/** Original pixel artwork and deterministic vanilla-format assets; no downloaded textures or Minecraft binary copies. */
object ResourcePackBuilder {
    private val json=GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create()
    @JvmStatic fun main(args:Array<String>) {
        val refresh=args.contains("--refresh-original-artwork")
        val catalog=ItemCatalog.read(Path.of(args.firstOrNull {!it.startsWith("--")}?:"src/main/resources/items.yml").toFile()).first!!
        val root=Path.of("resource-pack");Files.createDirectories(root)
        fun write(path:String,value:Any) {val p=root.resolve(path);Files.createDirectories(p.parent);if(!Files.exists(p))Files.writeString(p,json.toJson(value)+"\n")}
        write("pack.mcmeta",mapOf("pack" to mapOf("description" to "OneBlock · Рунне спорядження · Java 26.1–26.1.2","min_format" to listOf(84,0),"max_format" to listOf(84,0))))
        val preview=BufferedImage(8*112,((catalog.items.size+7)/8)*132,BufferedImage.TYPE_INT_ARGB)
        val g=preview.createGraphics();g.color=Color(0x151b29);g.fillRect(0,0,preview.width,preview.height);g.font=Font("Dialog",Font.PLAIN,11)
        catalog.items.values.sortedBy {it.id}.forEachIndexed {index,d->
            val id=d.legacyId
            val png=root.resolve("assets/oneblock/textures/item/$id.png");Files.createDirectories(png.parent)
            val texture=if(Files.exists(png) && !refresh)ImageIO.read(png.toFile()) else icon(d).also {ImageIO.write(it,"png",png.toFile())}
            if(d.model!=null) {
                val modelId=d.model.removePrefix("oneblock:")
                write("assets/oneblock/models/item/$modelId.json",mapOf("parent" to if(d.kind==ItemKind.COMPONENT)"minecraft:item/generated" else "minecraft:item/handheld",
                    "textures" to mapOf("layer0" to "oneblock:item/$id")))
                write("assets/oneblock/items/$modelId.json",mapOf("model" to mapOf("type" to "minecraft:model","model" to d.model)))
            }
            val x=(index%8)*112;val y=(index/8)*132
            g.drawImage(texture,x+8,y+4,96,96,null);g.color=Color(0xc4d8eb);g.drawString(d.legacyId,x+2,y+115)
        }
        catalog.items.values.mapNotNull {it.equipmentModel}.distinct().forEach {model->
            val id=model.removePrefix("oneblock:");val purple=id.contains("weaver")
            write("assets/oneblock/equipment/$id.json",mapOf("layers" to mapOf(
                "humanoid" to listOf(mapOf("texture" to "oneblock:$id")),"humanoid_leggings" to listOf(mapOf("texture" to "oneblock:$id")))))
            for(leggings in listOf(false,true)) {
                val path=root.resolve("assets/oneblock/textures/entity/equipment/${if(leggings)"humanoid_leggings" else "humanoid"}/$id.png")
                Files.createDirectories(path.parent);if(refresh || !Files.exists(path))ImageIO.write(armor(purple,leggings),"png",path.toFile())
            }
        }
        g.dispose();val output=Path.of("build/resource-pack");Files.createDirectories(output)
        ImageIO.write(preview,"png",output.resolve("items-preview.png").toFile())
        if(!Files.exists(root.resolve("pack.png")))ImageIO.write(icon(catalog.items.values.first()),"png",root.resolve("pack.png").toFile())
        val zip=output.resolve("oneblock-26.1.2.zip")
        ZipOutputStream(Files.newOutputStream(zip)).use {z->Files.walk(root).use {paths->paths.filter(Files::isRegularFile).sorted().forEach {p->
            val name=root.relativize(p).toString().replace('\\','/');val entry=ZipEntry(name).apply {time=0L};z.putNextEntry(entry);Files.copy(p,z);z.closeEntry()
        }}}
        fun hash(algorithm:String)=MessageDigest.getInstance(algorithm).digest(Files.readAllBytes(zip)).joinToString("") {"%02x".format(it)}
        val manifest=mapOf("minecraft" to listOf("26.1","26.1.1","26.1.2"),"resource-pack-format" to "84.0","sha1" to hash("SHA-1"),"sha256" to hash("SHA-256"),
            "bytes" to Files.size(zip),"items" to catalog.items.values.map {mapOf("id" to it.id,"name" to it.name,"model" to it.model,"equipment" to it.equipmentModel,"abilities" to it.abilities.map {a->a.id})},
            "artwork" to "Original OneBlock pixel artwork; no external textures copied", "public-url" to null)
        Files.writeString(output.resolve("manifest.json"),json.toJson(manifest)+"\n")
        Files.writeString(output.resolve("oneblock-26.1.2.zip.sha1"),hash("SHA-1")+"\n")
        Files.writeString(output.resolve("oneblock-26.1.2.zip.sha256"),hash("SHA-256")+"\n")
        println("Prepared $zip (${Files.size(zip)} bytes), ${catalog.items.values.count {it.model!=null}} custom item models, SHA-1 ${hash("SHA-1")}")
    }
    private class Pixels(val w:Int,val h:Int) {
        val image=BufferedImage(w,h,BufferedImage.TYPE_INT_ARGB)
        fun dot(x:Int,y:Int,c:Int) {if(x in 0 until w && y in 0 until h)image.setRGB(x,y,0xff000000.toInt() or c)}
        fun clear(x:Int,y:Int,width:Int,height:Int) {for(a in x until x+width)for(b in y until y+height)if(a in 0 until w && b in 0 until h)image.setRGB(a,b,0)}
        fun rect(x:Int,y:Int,width:Int,height:Int,c:Int) {for(a in x until x+width)for(b in y until y+height)dot(a,b,c)}
        fun line(x:Int,y:Int,xx:Int,yy:Int,c:Int,r:Int=0) {val steps=maxOf(kotlin.math.abs(xx-x),kotlin.math.abs(yy-y),1);for(i in 0..steps)rect(x+(xx-x)*i/steps-r,y+(yy-y)*i/steps-r,r*2+1,r*2+1,c)}
        fun outline() {val pixels=(0 until w*h).map {image.getRGB(it%w,it/w)};for(y in 0 until h)for(x in 0 until w)if(pixels[y*w+x] ushr 24==0 && listOf(-1 to 0,1 to 0,0 to -1,0 to 1).any {(dx,dy)->x+dx in 0 until w && y+dy in 0 until h && pixels[(y+dy)*w+x+dx] ushr 24!=0})dot(x,y,0x111725)}
    }
    private fun icon(d:ItemDefinition):BufferedImage {
        val p=Pixels(32,32);val id=d.legacyId
        val purple=id.contains("weaver") || id.contains("rift")
        val red=id.contains("nether") || id.contains("ember") || id=="tempered_core"
        val green=id.startsWith("axe") || id.startsWith("hoe") || id.contains("ranger")
        val glow=when {red->0xff9b36;purple->0xd6a0ff;green->0x7ce6a1;id.contains("apprentice")->0xf0ce76;else->0x73e5f2}
        val metal=when {id.startsWith("ranger_") && d.kind==ItemKind.ARMOR->0x436b43;red->0x694255;purple->0x66528d;id.endsWith("_1")->0x8ea9ae;id.endsWith("_2")->0x65bfc9;else->0x8cacc8}
        val bright=when {red->0xe9b16f;purple->0xb8a6df;else->0xd7edeb}
        when(d.kind) {
            ItemKind.ARMOR->when(d.slot) {
                "HEAD"->{p.rect(6,6,20,17,metal);p.rect(8,4,16,4,bright);p.rect(9,14,14,8,0x192735);p.line(8,13,23,13,glow);p.rect(5,22,6,4,metal);p.rect(21,22,6,4,metal);p.rect(14,7,4,4,glow)}
                "CHEST"->{p.rect(8,5,16,22,metal);p.rect(3,6,7,9,bright);p.rect(22,6,7,9,bright);p.rect(12,4,8,5,0x192735);p.line(10,12,16,19,bright);p.line(22,12,16,19,bright);p.rect(14,12,4,6,glow);p.rect(8,25,16,3,0xbfa16c)}
                "LEGS"->{p.rect(7,5,18,7,bright);p.rect(7,12,8,16,metal);p.rect(17,12,8,16,metal);p.rect(10,18,4,5,glow);p.rect(18,18,4,5,glow);p.rect(7,7,18,2,0xbfa16c)}
                else->{p.rect(6,5,8,20,metal);p.rect(18,5,8,20,metal);p.rect(3,22,11,6,bright);p.rect(16,22,11,6,bright);p.rect(7,12,6,3,glow);p.rect(19,12,6,3,glow)}
            }
            ItemKind.COMPONENT->{for(y in 5..26)for(x in 5..26)if(kotlin.math.abs(x-16)+kotlin.math.abs(y-16)<13)p.dot(x,y,if((x+y)%6==0)bright else metal)
                p.line(12,20,20,11,glow,1);p.line(11,13,18,21,bright);p.dot(22,7,glow);p.dot(7,23,glow)
                if("enchanted" in id)p.line(9,9,23,23,glow,1)}
            ItemKind.RANGED->{if(d.material.contains("CROSSBOW")) {p.line(5,7,26,26,metal,2);p.line(8,24,22,9,0x795d53,2);p.line(7,11,20,24,bright);p.rect(11,13,6,5,glow);p.line(4,20,21,4,0xbfa16c)}
                else if(id.contains("ranger")) {p.line(6,4,16,8,0x9f764d,1);p.line(16,8,23,16,0x6c8b52,1);p.line(23,16,26,27,0x9f764d,1)
                    p.line(6,4,26,27,0xd5c99f);p.line(4,27,24,7,0xbfa16c);p.rect(14,11,4,5,0x426f45);p.line(6,4,10,3,0x8db675)}
                else {p.line(5,3,12,9,bright,1);p.line(12,9,23,18,0x5195ac,1);p.line(23,18,28,27,bright,1);p.line(5,3,28,27,0x9ce5e7)
                    p.line(3,28,25,6,0xd5c99f);p.line(6,4,14,3,glow);p.line(25,26,28,19,glow);p.rect(13,10,5,4,glow)}}
            ItemKind.MAGIC->{p.line(5,27,24,8,0x705769,1);p.line(8,26,24,10,0xc6aa79)
                when {
                    id.contains("rift")-> {p.line(19,12,20,2,metal,1);p.line(22,13,29,6,metal,1);p.line(18,12,13,5,metal,1);p.rect(20,6,5,7,glow);p.rect(21,7,2,4,0xf4e9ff);p.line(12,19,15,22,0x977ab7)}
                    id.contains("frost")-> {p.line(16,5,26,13,glow);p.line(17,13,26,4,glow);p.line(21,2,21,15,bright);p.line(14,8,29,8,bright);p.rect(19,6,5,5,0xe6ffff);p.line(7,23,10,26,0x61a2b7)}
                    else->{p.rect(18,4,8,8,metal);p.rect(20,3,5,10,glow);p.rect(17,6,11,4,glow);p.rect(20,5,4,4,0xf8f2df);p.rect(17,13,4,2,0xd8b46d)}
                }}
            ItemKind.TOOL->{p.line(6,27,23,10,0x775e50,1);p.line(8,26,24,10,0xd4ac77)
                when {id.startsWith("pickaxe")-> {p.line(8,6,26,6,metal,2);p.line(24,7,27,14,bright,1);p.rect(12,5,5,3,glow)}
                    id.startsWith("axe")-> {p.rect(7,5,14,12,metal);p.rect(4,8,5,8,bright);p.rect(14,8,6,5,glow);p.rect(21,8,5,4,metal)}
                    else->{p.line(8,6,26,6,metal,2);p.rect(5,7,6,10,bright);p.rect(14,5,5,4,glow)}}
                val tier=id.last().digitToIntOrNull()?:1
                if(tier>=2) {p.line(12,18,15,21,0xd8b46d);p.rect(23,5,3,3,bright)}
                if(tier==3) {if(id.startsWith("axe"))p.line(6,5,4,17,bright) else p.line(26,6,29,12,bright,1);p.line(18,8,22,8,glow)}
                repeat(tier) {p.rect(6+it*3,27-it*3,2,2,glow)}}
            else->{if(id.contains("cleaver")) {for(y in 4..19)for(x in 14..27)if(x+y<39)p.dot(x,y,metal);p.line(14,18,25,5,bright,1);p.rect(21,9,4,4,glow)}
                else {p.line(12,20,26,6,metal,2);p.line(14,18,27,5,bright);p.line(15,17,22,10,glow)}
                p.line(5,27,13,19,0x796056,1);p.line(9,17,16,24,0xd9b775,1);p.rect(4,26,3,3,glow);p.dot(25,3,glow)}
        }
        // Cloth and plate must read as different shapes even with colour removed.
        if(d.kind==ItemKind.ARMOR) {
            if(purple)when(d.slot) {
                "HEAD"->{p.clear(5,4,3,5);p.clear(24,4,3,5);p.rect(12,2,8,3,metal);p.rect(8,13,3,13,0x40315a);p.rect(21,13,3,13,0x40315a);p.rect(14,6,4,3,0x78ded2)}
                "CHEST"->{p.clear(3,6,3,4);p.clear(26,6,3,4);p.line(10,12,16,24,0x78ded2);p.line(22,12,16,24,0x78ded2);p.rect(9,26,14,3,0x40315a)}
                "LEGS"->{p.line(8,10,6,26,metal,1);p.line(24,10,26,26,metal,1);p.rect(13,12,6,13,0x40315a);p.rect(7,27,19,2,0x78ded2)}
                else->{p.clear(3,22,3,6);p.clear(16,22,3,6);p.rect(7,6,6,2,0x78ded2);p.rect(19,6,6,2,0x78ded2)}
            } else when(d.slot) {
                "HEAD"->{p.rect(4,9,3,8,bright);p.rect(25,9,3,8,bright);p.rect(14,14,3,10,metal);p.dot(8,10,0xffffff);p.dot(23,10,0xffffff)}
                "CHEST"->{p.rect(3,7,7,3,0x526c83);p.rect(22,7,7,3,0x526c83);p.line(8,22,23,22,0x526c83);p.rect(15,18,2,5,0x73e5f2)}
                "LEGS"->{p.rect(8,17,6,3,bright);p.rect(18,17,6,3,bright)}
                else->{p.rect(5,22,9,2,0x526c83);p.rect(17,22,9,2,0x526c83)}
            }
        }
        p.outline();return p.image
    }
    private fun armor(purple:Boolean,leggings:Boolean):BufferedImage {
        val p=Pixels(64,32);val base=if(purple)0x4e3a75 else 0x344e66;val shade=if(purple)0x322645 else 0x233343
        val edge=if(purple)0xc49be7 else 0xabbac9;val rune=if(purple)0x74ddda else 0x75d7ef
        // Standard humanoid UV layout, authored from rectangles. No vanilla texture pixels are copied.
        if(!leggings) {
            p.rect(0,0,32,16,base);p.rect(8,8,8,8,shade);p.rect(10,11,4,4,0x101b26)
            p.rect(0,8,32,2,edge);p.rect(8,8,8,2,rune);p.rect(11,0,2,8,rune)
            p.rect(16,16,24,16,base);p.rect(40,16,16,16,base)
            p.rect(20,20,8,2,edge);p.rect(22,22,4,7,shade);p.rect(23,22,2,5,rune)
            if(purple) {p.line(20,21,24,28,rune);p.line(27,21,24,28,rune);p.rect(8,8,2,8,shade);p.rect(14,8,2,8,shade)}
            else {p.rect(20,25,2,3,edge);p.rect(26,25,2,3,edge);p.rect(11,8,2,8,base)}
            p.rect(16,29,40,2,edge);p.rect(44,20,4,3,edge);p.rect(45,24,2,3,rune)
            p.rect(0,16,16,16,shade);p.rect(0,28,16,4,base);p.rect(4,28,4,1,rune)
        } else {
            p.rect(16,16,24,16,base);p.rect(16,20,24,3,edge);p.rect(0,16,16,16,base)
            p.rect(4,24,4,4,shade);p.rect(5,24,2,3,rune);p.rect(0,30,16,2,edge)
        }
        return p.image
    }
}
