package com.alytanta.oneblock.items

import com.google.gson.JsonParser
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipFile
import javax.imageio.ImageIO

class ItemAssetsTest {
    @Test fun `release ZIP has exact model texture and equipped armor references without vanilla overrides`() {
        val catalog=ItemCatalog.read(File("src/main/resources/items.yml")).first!!
        val zip=File("build/resource-pack/oneblock-26.1.2.zip")
        assumeTrue(zip.isFile,"Optional resource pack not generated; run buildResourcePack to verify its assets")
        val hashes=mutableSetOf<String>()
        ZipFile(zip).use {z->
            fun json(path:String)=JsonParser.parseString(z.getInputStream(requireNotNull(z.getEntry(path)) {path}).reader().readText()).asJsonObject
            val pack=json("pack.mcmeta").getAsJsonObject("pack")
            assertEquals(84,pack.getAsJsonArray("min_format")[0].asInt);assertEquals(84,pack.getAsJsonArray("max_format")[0].asInt)
            assertTrue(z.entries().asSequence().none {it.name.startsWith("assets/minecraft/") || it.name.contains("..")})
            for(d in catalog.items.values) {
                val id=d.legacyId
                if(d.model!=null) {
                    val modelId=d.model.removePrefix("oneblock:")
                    assertEquals("oneblock:item/$modelId",json("assets/oneblock/items/$modelId.json").getAsJsonObject("model").get("model").asString)
                } else assertEquals(ItemKind.ARMOR,d.kind)
                val entry=requireNotNull(z.getEntry("assets/oneblock/textures/item/$id.png"))
                val bytes=z.getInputStream(entry).readBytes();val image=ImageIO.read(bytes.inputStream())
                assertEquals(32,image.width);assertEquals(32,image.height)
                hashes+=MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") {"%02x".format(it)}
                d.equipmentModel?.let {model->
                    val name=model.removePrefix("oneblock:");val layers=json("assets/oneblock/equipment/$name.json").getAsJsonObject("layers")
                    for(layer in listOf("humanoid","humanoid_leggings")) {
                        val reference=layers.getAsJsonArray(layer)[0].asJsonObject.get("texture").asString.substringAfter(':')
                        val equipped=ImageIO.read(z.getInputStream(requireNotNull(z.getEntry("assets/oneblock/textures/entity/equipment/$layer/$reference.png"))))
                        assertEquals(64,equipped.width);assertEquals(32,equipped.height)
                    }
                }
            }
            assertEquals(catalog.items.size,hashes.size,"Every shipped item has distinct original pixel artwork")
        }
        val manifest=JsonParser.parseString(File("build/resource-pack/manifest.json").readText()).asJsonObject
        val hash=MessageDigest.getInstance("SHA-1").digest(zip.readBytes()).joinToString("") {"%02x".format(it)}
        assertEquals(hash,manifest.get("sha1").asString)
    }
}
