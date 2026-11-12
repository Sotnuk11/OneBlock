package com.alytanta.oneblock.tools

import com.alytanta.oneblock.hub.generator.*
import com.alytanta.oneblock.dungeon.*
import com.google.gson.GsonBuilder
import java.nio.file.*
import java.util.UUID

/** Exports the real pure templates. Result is a blueprint preview, never a Minecraft client screenshot. */
object VisualPreviewBuilder {
    @JvmStatic fun main(args:Array<String>) {
        val output=Path.of("build/visual-polish");Files.createDirectories(output)
        val hub=HubTemplate.plan(HubTarget("preview:settlement",UUID(0,1),0,100,0),HubGeneratorSettings.read(Path.of("src/main/resources/hub-generator.yml").toFile()).first)
        val mine=MineSettings.read(Path.of("src/main/resources/dungeon.yml").toFile()).first!!
        val json=GsonBuilder().setPrettyPrinting().create()
        Files.writeString(output.resolve("hub-plan.json"),json.toJson(hub))
        Files.writeString(output.resolve("mine-plan.json"),json.toJson(MineLayout.blocks(mine.appearance)))
        println("Exported ${hub.blocks.size} hub blocks; ${MineLayout.blocks(mine.appearance).size} mine blocks; hub hash ${hub.hash()}")
    }
}
