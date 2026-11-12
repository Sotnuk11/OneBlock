package com.alytanta.oneblock.market

import java.text.NumberFormat
import java.util.Locale

internal object MarketText {
    fun arguments(values: Array<out Pair<String, Any>>): Array<Pair<String, Any>> =
        values.map { (key, value) -> key to if (value is Number) number(value) else value }.toTypedArray()

    fun number(value: Number): String = when (value) {
        is Byte, is Short, is Int, is Long -> NumberFormat.getIntegerInstance(Locale.US).format(value)
        else -> value.toString()
    }

    fun duration(seconds: Long): String {
        val value = seconds.coerceAtLeast(0)
        return when {
            value >= 86400 -> "${value / 86400}d ${(value % 86400) / 3600}h"
            value >= 3600 -> "${value / 3600}h ${(value % 3600) / 60}m"
            value >= 60 -> "${value / 60}m ${value % 60}s"
            else -> "${value}s"
        }
    }
}
