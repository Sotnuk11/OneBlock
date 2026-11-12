package com.alytanta.oneblock.smoke

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/** Explicit Gradle entry point; never runs as part of normal unit tests. */
object FoliaStorageSmokeRunner {
    @JvmStatic fun main(args:Array<String>) {
        var runRoot:Path?=null
        try {execute(args) {runRoot=it}} finally {
            runRoot?.let {root->
                val keep=java.lang.Boolean.getBoolean("oneblock.keep-smoke-runtime") || java.lang.Boolean.getBoolean("oneblock.visual-hold") || java.lang.Boolean.getBoolean("oneblock.combat-visual")
                runCatching {SmokeRetention.prune(root,keep)}.onSuccess {removed->
                    if(removed.isNotEmpty())println("Pruned reusable smoke runtime: ${removed.joinToString()}; logs, tested plugin and journals retained")
                }.onFailure {System.err.println("Smoke runtime retained: ${it.message}")}
            }
        }
    }
    private fun execute(args:Array<String>,remember:(Path)->Unit) {
        require(args.size in 4..5 && args[0].isNotBlank()) { "Supply -PfoliaRuntime=<directory containing Folia runtime binaries>" }
        val hub=args.getOrNull(4)=="hub"
        val items=args.getOrNull(4)=="items"
        val combat=args.getOrNull(4)=="combat" || items
        val mine=args.getOrNull(4)=="mine"
        val mineClientCount=System.getProperty("oneblock.smoke-clients","6").toInt().also {require(it in 6..150)}
        val smokeHeap=System.getProperty("oneblock.smoke-heap-mb","1536").toInt().also {require(it in 768..1536)}
        val client=hub||combat||mine
        val windCombat=java.lang.Boolean.getBoolean("oneblock.wind-combat")
        val configRecovery=java.lang.Boolean.getBoolean("oneblock.config-recovery")
        require(!configRecovery || !client) {"Configuration recovery fixture requires storage smoke"}
        val source=Path.of(args[0]).toAbsolutePath().normalize()
        val root=Path.of(args[1]).toAbsolutePath().normalize().resolve("run-${System.currentTimeMillis()}")
        Files.createDirectories(root)
        SmokeRetention.mark(root);remember(root)
        val folia=Files.list(source).use { paths -> paths.filter { it.fileName.toString().matches(Regex("folia-.*\\.jar")) }.findFirst().orElseThrow() }
        val worlds=source.resolve("plugins/worlds-4.5.0-all.jar")
        fun hash(path:Path)=MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path)).joinToString("") { "%02x".format(it) }
        val originals=mutableMapOf(folia to hash(folia),worlds to hash(worlds))
        Files.copy(folia,root.resolve("folia.jar"))
        for(directory in listOf("libraries","versions")) {
            Files.walk(source.resolve(directory)).use { paths -> paths.forEach { path ->
                val target=root.resolve(source.relativize(path))
                if(Files.isDirectory(path))Files.createDirectories(target) else Files.copy(path,target)
            } }
        }
        val cache=source.resolve("cache")
        if(Files.isDirectory(cache))Files.list(cache).use { paths -> paths.filter { it.fileName.toString().endsWith(".jar") }.forEach {
            Files.createDirectories(root.resolve("cache"));Files.copy(it,root.resolve("cache").resolve(it.fileName))
        } }
        Files.createDirectories(root.resolve("plugins/one-block"))
        Files.copy(worlds,root.resolve("plugins/worlds.jar"))
        if(hub||mine) {
            val fancy=source.resolve("plugins/FancyNpcs-2.12.1+379.jar");originals[fancy]=hash(fancy);Files.copy(fancy,root.resolve("plugins/fancy.jar"))
        }
        if(hub) {
            Files.writeString(root.resolve("plugins/one-block/hub-generator.yml"),"version: 1\nallow-void-worlds: true\nlanguage: en\nblocks-per-batch: 64\nin-flight-chunks: 2\nscan-per-tick: 8192\nbatch-delay-ticks: 1\n")
        }
        Files.copy(Path.of(args[2]),root.resolve("plugins/one-block.jar"))
        java.util.zip.ZipFile(args[2]).use {jar->for(name in listOf("items.yml","resource-pack.yml"))
            Files.write(root.resolve("plugins/one-block/$name"),jar.getInputStream(jar.getEntry(name)).readBytes())}
        val packHost=if(items)com.sun.net.httpserver.HttpServer.create(java.net.InetSocketAddress("127.0.0.1",0),0).apply {
            val bytes=Files.readAllBytes(Path.of("build/resource-pack/oneblock-26.1.2.zip"))
            createContext("/oneblock.zip") {exchange->exchange.responseHeaders.add("Content-Type","application/zip");exchange.sendResponseHeaders(200,bytes.size.toLong());exchange.responseBody.use {it.write(bytes)}}
            createContext("/missing.zip") {exchange->exchange.sendResponseHeaders(404,-1);exchange.close()};start()
        } else null
        try {
        if(items) {
            val sha=MessageDigest.getInstance("SHA-1").digest(Files.readAllBytes(Path.of("build/resource-pack/oneblock-26.1.2.zip"))).joinToString("") {"%02x".format(it)}
            Files.writeString(root.resolve("plugins/one-block/resource-pack.yml"),"version: 1\nenabled: true\nrequired: true\nurl: 'http://127.0.0.1:${packHost!!.address.port}/oneblock.zip'\nsha1: '$sha'\nallow-http: true\ntimeout-seconds: 5\n")
        }
        if(combat||mine)java.util.zip.ZipFile(args[2]).use { jar ->
            val config=jar.getInputStream(jar.getEntry("config.yml")).reader(Charsets.UTF_8).use { it.readText() }
            Files.writeString(root.resolve("plugins/one-block/config.yml"),config.replace("mob-spawning: false","mob-spawning: true").let {if(combat)it.replace("  world: world","  world: minecraft:the_nether") else it})
        }
        // Explicit public-config regression fixture; never copy .env, journals, worlds or player data.
        System.getProperty("oneblock.smoke-configs")?.let { configured ->
            val data=Path.of(configured).toAbsolutePath().normalize()
            require(data!=root.resolve("plugins/one-block"))
            Files.list(data).use { paths -> paths.filter {Files.isRegularFile(it) && it.fileName.toString().endsWith(".yml")}.forEach {
                Files.copy(it,root.resolve("plugins/one-block").resolve(it.fileName),java.nio.file.StandardCopyOption.REPLACE_EXISTING)
            } }
            for((from,to) in listOf(data.resolve("lang") to root.resolve("plugins/one-block/lang"),data.parent.resolve("menus") to root.resolve("plugins/menus"))) {
                if(Files.isDirectory(from))Files.walk(from).use { paths -> paths.filter {Files.isRegularFile(it) && it.fileName.toString().endsWith(".yml")}.forEach {
                    val target=to.resolve(from.relativize(it));Files.createDirectories(target.parent)
                    Files.copy(it,target,java.nio.file.StandardCopyOption.REPLACE_EXISTING)
                } }
            }
            println("Copied explicit public YAML regression fixture; no secrets or production database access.")
        }
        Files.copy(Path.of(args[3]),root.resolve("plugins/storage-smoke.jar"))
        if(configRecovery) {
            Files.delete(root.resolve("plugins/one-block/items.yml"));Files.delete(root.resolve("plugins/one-block/resource-pack.yml"))
            java.util.zip.ZipFile(args[2]).use {jar->
                val statistics=root.resolve("plugins/menus/combat/statistics.yml");Files.createDirectories(statistics.parent)
                Files.write(statistics,jar.getInputStream(jar.getEntry("menus/combat/statistics.yml")).readBytes()+"\n# retain custom menu sibling\n".toByteArray())
            }
            Files.writeString(root.resolve("plugins/one-block/auction.yml"),"auction:\n  allowed-items: []\n")
        }
        Files.writeString(root.resolve("eula.txt"),"eula=true\n")
        val listenPort=if(client)java.net.ServerSocket(0).use {it.localPort} else 0
        Files.writeString(root.resolve("server.properties"),"server-ip=127.0.0.1\nserver-port=$listenPort\nonline-mode=false\nview-distance=${if(hub && java.lang.Boolean.getBoolean("oneblock.visual-hold"))8 else 2}\nsimulation-distance=2\nallow-nether=false\npause-when-empty-seconds=-1\nmax-players=${if(mine)mineClientCount+2 else if(items)8 else if(windCombat || java.lang.Boolean.getBoolean("oneblock.combat-visual") || hub && java.lang.Boolean.getBoolean("oneblock.visual-hold"))4 else 2}\nspawn-protection=0\n")
        if(mine) {Files.createDirectories(root.resolve("config"));Files.writeString(root.resolve("config/folia-global.yml"),"_version: 1\nthreaded-regions:\n  threads: 4\n")}
        EmbeddedPostgres.start().use { postgres ->
            Files.writeString(root.resolve("plugins/one-block/.env"),"ONEBLOCK_DB_HOST=127.0.0.1\nONEBLOCK_DB_PORT=${postgres.port}\nONEBLOCK_DB_NAME=postgres\nONEBLOCK_DB_USER=postgres\nONEBLOCK_DB_PASSWORD=test-only\nONEBLOCK_DB_SSLMODE=disable\nONEBLOCK_DB_CHANNEL_BINDING=disable\n")
            for(phase in 1..2) {
                if(configRecovery && phase==2)for(name in listOf("one-block/items.yml","one-block/resource-pack.yml","one-block/lang/en.yml","menus/combat/layout.yml"))Files.delete(root.resolve("plugins/$name"))
                val log=root.resolve("phase-$phase.log")
                val smokeFolder=root.resolve(if(mine)"plugins/OneBlockMineSmoke" else if(hub)"plugins/OneBlockHubSmoke" else if(combat)"plugins/OneBlockCombatSmoke" else "plugins/OneBlockStorageSmoke")
                if(client)listOf("bot-ready","bot-mine.json","bot-failure.txt").forEach {Files.deleteIfExists(smokeFolder.resolve(it))}
                if(items)for(name in listOf("HubSmokeBot","ItemPeer","PackDecline","PackFail","PackTimeout","PackPremature"))for(suffix in listOf("failure.txt","pack-applied","pack-accepted","pack-download-error"))Files.deleteIfExists(smokeFolder.resolve("$name-$suffix"))
                val command=mutableListOf(Path.of(System.getProperty("java.home"),"bin","java.exe").toString(),"-Xms512M","-Xmx${smokeHeap}M")
                command+="-Doneblock.smoke-clients=$mineClientCount"
                if(configRecovery)command+="-Doneblock.config-recovery=true"
                if(hub && java.lang.Boolean.getBoolean("oneblock.visual-hold"))command+="-Doneblock.visual-hold=true"
                if(combat && java.lang.Boolean.getBoolean("oneblock.combat-visual"))command+="-Doneblock.combat-visual=true"
                command+=listOf("-jar","folia.jar","--nogui")
                val builder=ProcessBuilder(command)
                    .directory(root.toFile()).redirectErrorStream(true).redirectOutput(log.toFile())
                builder.environment().keys.removeIf { it.startsWith("ONEBLOCK_DB_") }
                val process=builder.start()
                Files.writeString(root.resolve("phase-$phase.pid"),process.pid().toString())
                val mineBots=java.util.concurrent.ConcurrentHashMap<String,MinecraftWireBot>()
                fun consume(path:Path)=try {Files.deleteIfExists(path)} catch(_:java.nio.file.FileSystemException) {false} // Windows may observe a just-created signal before its writer closes.
                // Test-only fault control uses this runner's own EmbeddedPostgres handle, never a production URL.
                val faultControl=if(mine)Thread({
                    try {while(process.isAlive) {
                        val request=smokeFolder.resolve("test-db-disconnect")
                        if(consume(request)) {
                            postgres.postgresDatabase.connection.use {c->c.createStatement().use {st->
                                st.executeQuery("SELECT pg_terminate_backend(pid) FROM pg_stat_activity WHERE application_name='one-block' AND pid<>pg_backend_pid()").close()
                            }}
                            Files.writeString(smokeFolder.resolve("test-db-disconnected"),"own isolated database backend terminated")
                        }
                        for(index in 1..mineClientCount) {
                            val name="MineBot$index"
                            if(consume(smokeFolder.resolve("$name-disconnect")))mineBots.remove(name)?.close()
                            if(consume(smokeFolder.resolve("$name-connect")) && !mineBots.containsKey(name))
                                mineBots[name]=MinecraftWireBot(listenPort,smokeFolder,name).apply {start()}
                        }
                        Thread.sleep(50)
                    }} catch(_:InterruptedException) { /* Runner owns and joins this helper. */ }
                },"mine-smoke-db-fault").apply {isDaemon=true;start()} else null
                if(items) {
                    Files.createDirectories(smokeFolder)
                    for((name,policy) in mapOf("PackDecline" to "decline","PackFail" to "fail","PackTimeout" to "timeout","PackPremature" to "premature"))Files.writeString(smokeFolder.resolve("$name-pack-policy.txt"),policy)
                    Files.writeString(smokeFolder.resolve("HubSmokeBot-pack-hold"),"test ACCEPTED does not unlock")
                }
                val bots=if(mine)(1..mineClientCount).map {index->val name="MineBot$index";MinecraftWireBot(listenPort,smokeFolder,name,if(mineClientCount>8)(index-1)*600L else 0L).apply {mineBots[name]=this;start()}} else if(items)
                    listOf("HubSmokeBot","ItemPeer","PackDecline","PackFail","PackTimeout","PackPremature").map {MinecraftWireBot(listenPort,smokeFolder,it).apply {start()}}
                else if(client)(if(windCombat)listOf("HubSmokeBot","CombatPeer") else listOf("HubSmokeBot")).map {MinecraftWireBot(listenPort,smokeFolder,it).apply {start()}} else emptyList()
                if(!process.waitFor(if(combat && java.lang.Boolean.getBoolean("oneblock.combat-visual"))2100 else if(hub && java.lang.Boolean.getBoolean("oneblock.visual-hold"))3900 else if(mine)600 else if(combat)480 else if(client)300 else 180,TimeUnit.SECONDS)) { bots.forEach {it.close()};mineBots.values.forEach {it.close()};process.destroy();if(!process.waitFor(10,TimeUnit.SECONDS))process.destroyForcibly();error("Folia phase $phase timeout; inspect $log") }
                faultControl?.interrupt();faultControl?.join(1000)
                bots.forEach {it.close()};mineBots.values.forEach {it.close()}
                check(process.exitValue()==0 && Files.exists(smokeFolder.resolve("phase$phase.ok"))) {
                    "Folia phase $phase failed; inspect $log and test plugin failed.txt"
                }
                val nativeLog=Files.readString(log,Charsets.ISO_8859_1)
                check(!nativeLog.contains("Thread failed main thread check") && !nativeLog.contains("Could not pass event")) {
                    "Folia phase $phase logged an unsafe thread access or event exception; inspect $log"
                }
                println("Folia phase $phase PASS; log=$log")
            }
        }
        } finally {packHost?.stop(0)}
        originals.forEach { (path,before) -> check(hash(path)==before) { "Source runtime binary changed" } }
        Files.writeString(root.resolve("result.txt"),if(mine)"PASS: two native Folia boots with six loopback bots; see mine-smoke.json for assertions and limitations. No human duration playtest or 150-online claim. Source runtime hashes unchanged.\n" else if(combat)"PASS: two real Folia boots and loopback player; native combat PDC, component crafting, duplicate confirmation, full inventory claim refusal/collection, exact equipment restart, reward receipt replay, melee/ranged/magic and armor. Source runtime JAR hashes unchanged. No load or visual client test.\n" else if(hub)"PASS: two real Folia boots, real loopback Minecraft client mining during hub generation, pause/restart/resume, conflict refusal, NPC services, activation and targeted rollback. Source runtime JAR hashes unchanged.\n" else "PASS: two real Folia boots; native PDC bytes; progress restart; Worlds save/backup/unload/reload; missing-world refusal; source runtime JAR hashes unchanged. No clients or 150-player load.\n")
        println("Isolated Folia result: ${root.resolve("result.txt")}")
    }
}
