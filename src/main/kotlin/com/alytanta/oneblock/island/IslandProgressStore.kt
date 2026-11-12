package com.alytanta.oneblock.island

import java.util.UUID

interface IslandProgressStore {
    fun update(owner: UUID, change: (Island) -> Island): Island?
}
