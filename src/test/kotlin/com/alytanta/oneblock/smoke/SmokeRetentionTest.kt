package com.alytanta.oneblock.smoke

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class SmokeRetentionTest {
    @TempDir lateinit var temporary:Path
    private val base get()=temporary.resolve("build")
    private fun fixture():Path {
        val run=Files.createDirectories(base.resolve("folia-smoke/run-123"))
        for(name in listOf("libraries/a.jar","versions/server.jar","cache/a.jar","world/level.dat","plugins/one-block/mine-sessions/session.json","plugins/one-block.jar","phase-1.log")) {
            val file=run.resolve(name);Files.createDirectories(file.parent);Files.writeString(file,"keep exact bytes: $name")
        }
        SmokeRetention.mark(run,base);return run
    }
    @Test fun `successful run drops heavy runtime and worlds while evidence and journals remain exact`() {
        val run=fixture();Files.writeString(run.resolve("result.txt"),"PASS: completed")
        val journal=Files.readAllBytes(run.resolve("plugins/one-block/mine-sessions/session.json"))
        assertTrue(SmokeRetention.prune(run,base=base).contains("world"))
        assertFalse(Files.exists(run.resolve("libraries")));assertFalse(Files.exists(run.resolve("world")))
        assertArrayEquals(journal,Files.readAllBytes(run.resolve("plugins/one-block/mine-sessions/session.json")))
        assertTrue(Files.exists(run.resolve("phase-1.log")));assertTrue(Files.exists(run.resolve("plugins/one-block.jar")))
        assertTrue(SmokeRetention.prune(run,base=base).isEmpty())
    }
    @Test fun `failed run preserves worlds for diagnosis and explicit keep preserves all runtime`() {
        val run=fixture()
        assertTrue(SmokeRetention.prune(run,true,base).isEmpty())
        assertTrue(Files.exists(run.resolve("libraries/a.jar")))
        SmokeRetention.prune(run,base=base)
        assertFalse(Files.exists(run.resolve("libraries")));assertTrue(Files.exists(run.resolve("world/level.dat")))
    }
    @Test fun `active child and unowned or external folders refuse deletion`() {
        val run=fixture();Files.writeString(run.resolve("phase-1.pid"),ProcessHandle.current().pid().toString())
        assertThrows(IllegalStateException::class.java) {SmokeRetention.prune(run,base=base)}
        assertTrue(Files.exists(run.resolve("libraries/a.jar")))
        Files.delete(run.resolve("phase-1.pid"));Files.delete(run.resolve(".oneblock-smoke-runtime"))
        assertThrows(java.nio.file.NoSuchFileException::class.java) {SmokeRetention.prune(run,base=base)}
        assertThrows(IllegalArgumentException::class.java) {SmokeRetention.prune(temporary,base=base)}
        assertTrue(Files.exists(run.resolve("libraries/a.jar")))
    }
}
