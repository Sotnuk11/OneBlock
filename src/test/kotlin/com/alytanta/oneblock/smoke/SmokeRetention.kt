package com.alytanta.oneblock.smoke

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.LinkOption.NOFOLLOW_LINKS

/** Test fixtures only: keep evidence, discard redundant runtime copies once child processes stop. */
object SmokeRetention {
    private const val marker=".oneblock-smoke-runtime"
    private const val owner="OneBlock isolated smoke fixture"
    private fun owned(root:Path,base:Path):Path {
        val directory=root.toAbsolutePath().normalize()
        val boundary=base.toAbsolutePath().normalize()
        require(directory.startsWith(boundary) && directory!=boundary && directory.fileName.toString().matches(Regex("run-\\d+"))) {"Not an isolated build run: $directory"}
        require(boundary.toRealPath()==boundary && directory.toRealPath()==directory && !Files.isSymbolicLink(directory)) {"Unsafe smoke directory: $directory"}
        return directory
    }
    fun mark(root:Path,base:Path=Path.of("build")) {
        Files.writeString(owned(root,base).resolve(marker),owner)
    }
    fun prune(root:Path,keep:Boolean=false,base:Path=Path.of("build")):List<String> {
        val directory=owned(root,base)
        require(Files.readString(directory.resolve(marker))==owner) {"Unowned smoke fixture"}
        if(keep)return emptyList()
        for(phase in 1..2) {
            val pid=directory.resolve("phase-$phase.pid")
            if(Files.exists(pid))check(!ProcessHandle.of(Files.readString(pid).trim().toLong()).map {it.isAlive}.orElse(false)) {"Smoke process still alive; runtime retained"}
        }
        val passed=directory.resolve("result.txt").let {Files.exists(it) && Files.readString(it).startsWith("PASS:")}
        val names=listOf("libraries","versions","cache","folia.jar","plugins/worlds.jar","plugins/fancy.jar")+
            if(passed)listOf("world","world_nether","world_the_end") else emptyList()
        val existing=names.filter {Files.exists(directory.resolve(it),NOFOLLOW_LINKS)}
        val paths=existing.flatMap {name->Files.walk(directory.resolve(name)).use {it.toList()}}
        // Validate the complete deletion plan before changing any file; never follow links.
        require(paths.all {it.normalize().startsWith(directory) && !Files.isSymbolicLink(it) && it.toRealPath().startsWith(directory)}) {"Unsafe runtime link; retained"}
        paths.distinct().sortedByDescending {it.nameCount}.forEach(Files::delete)
        Files.writeString(directory.resolve("retention.json"),com.google.gson.Gson().toJson(mapOf(
            "removed" to existing,"passed" to passed,"preserved" to listOf("logs","plugins/one-block","plugins/one-block.jar","test outputs and journals"))))
        return existing
    }
}
