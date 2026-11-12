package com.alytanta.oneblock.island

import net.thenextlvl.worlds.preset.Preset
import net.kyori.adventure.key.Key

object IslandGeneration {
    // Worlds' THE_VOID enables features, including Minecraft's stone start platform.
    // Store an explicit featureless preset so it also survives Worlds registry reloads.
    fun voidPreset(): Preset = Preset.THE_VOID.toBuilder()
        // THE_VOID biome itself contains the start-platform feature. Avoid it entirely.
        .biome(Key.key("minecraft:plains"))
        .features(false).decoration(false).lakes(false).structures(emptySet()).build()
}
