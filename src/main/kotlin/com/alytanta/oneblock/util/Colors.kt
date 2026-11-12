package com.alytanta.oneblock.util

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.TextDecoration
import net.kyori.adventure.key.Key
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer

object Colors {

    private val serializer = LegacyComponentSerializer.builder().character('&').hexColors().build()

    /** Softer shades for the old 16 colours; applies to custom server YAML too. Explicit `&#RRGGBB` stays as written. */
    private val palette = mapOf(
        '1' to "4F6BD6", '2' to "4CAF7A", '3' to "4FB3BF", '4' to "D9534F", '5' to "A77BD6",
        '6' to "F5B95F", '7' to "B4BCC8", '8' to "7C8594", '9' to "7AA7F5", 'a' to "7BE3A0",
        'b' to "82D8F5", 'c' to "F2777A", 'd' to "D3A6F7", 'e' to "F6DC83", 'f' to "F4F6F8",
    )
    private val code = Regex("&([0-9a-fA-F])")

    private fun soften(text: String) = code.replace(text) { match ->
        palette[match.groupValues[1].lowercase()[0]]?.let { "&#$it" } ?: match.value
    }

    /** Converts `&`-coded text into an Adventure component. */
    fun colorize(text: String): Component = normal(serializer.deserialize(soften(text)))

    /** Inventory titles sit on a light background, so they keep the dark vanilla colours. */
    fun title(text: String): Component = normal(serializer.deserialize(text))

    /** Explicitly overrides Minecraft's italic item-name/lore defaults, including legacy &o children. */
    fun normal(component: Component): Component = component
        .decoration(TextDecoration.ITALIC, false)
        .font(Key.key("minecraft:default"))
        .children(component.children().map(::normal))
}
