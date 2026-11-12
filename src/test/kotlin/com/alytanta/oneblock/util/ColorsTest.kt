package com.alytanta.oneblock.util

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.TextDecoration
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class ColorsTest {
    @Test fun `normal text overrides inherited and explicit italics throughout a component tree`() {
        val component = Colors.normal(Component.text("Price ").decorate(TextDecoration.ITALIC)
            .append(Colors.colorize("&a100 &oCoins")))
        fun inspect(value: Component) {
            assertEquals(TextDecoration.State.FALSE, value.decoration(TextDecoration.ITALIC))
            assertEquals("minecraft:default", value.font().toString())
            value.children().forEach(::inspect)
        }
        inspect(component)
    }
}
