package com.alytanta.oneblock.storage

import com.alytanta.oneblock.island.Island
import com.alytanta.oneblock.player.PlayerProfile
import java.io.File
import java.util.concurrent.CompletableFuture

interface StorageService {
    fun audit(event: OperationLog) {}
    fun loadIslands(): List<Island>
    fun loadProfiles(): List<PlayerProfile>
    fun saveIsland(island: Island)
    fun saveProfile(profile: PlayerProfile)
    fun saveNow()
    fun flush(): Boolean
    fun flushAsync(): CompletableFuture<Unit> = CompletableFuture.supplyAsync { check(flush()) }
    fun backup(): File
    fun close()
    val healthy: Boolean get() = true
    val generation: Long get() = 0
}
