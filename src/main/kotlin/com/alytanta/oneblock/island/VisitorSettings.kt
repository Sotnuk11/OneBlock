package com.alytanta.oneblock.island

import java.util.UUID

enum class VisitMode { ALL, NONE, SELECTED }
data class VisitorSettings(val mode: VisitMode = VisitMode.ALL, val allowed: Set<UUID> = emptySet(), val allowedNames: Set<String>? = null)
