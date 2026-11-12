plugins {
    kotlin("jvm") version "2.5.0-Beta1"
    id("com.gradleup.shadow") version "9.6.1"
    id("xyz.jpenilla.run-paper") version "3.1.0"
}

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
    maven("https://repo.thenextlvl.net/releases")
    maven("https://repo.fancyinnovations.com/releases")
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:26.1.2.build.+")
    compileOnly("net.thenextlvl:worlds:4.5.0")
    compileOnly("de.oliver:FancyNpcs:2.11.0")
    implementation("org.postgresql:postgresql:42.7.14")
    implementation("com.google.code.gson:gson:2.13.2")
    implementation("org.jetbrains.kotlin:kotlin-stdlib-jdk8")
    testImplementation("io.papermc.paper:paper-api:26.1.2.build.+")
    testImplementation("net.thenextlvl:worlds:4.5.0")
    testImplementation("de.oliver:FancyNpcs:2.11.0") {isTransitive=false}
    testImplementation("org.jetbrains.kotlin:kotlin-test")
    testImplementation("org.junit.jupiter:junit-jupiter:5.12.2")
    testImplementation("io.zonky.test:embedded-postgres:2.1.1")
}

kotlin {
    jvmToolchain(25)
}

tasks {
    test {
        useJUnitPlatform()
    }

    build {
        dependsOn(shadowJar)
    }

    jar {
        archiveClassifier.set("plain")
    }

    shadowJar {
        mergeServiceFiles()
        archiveClassifier.set("")
        filesMatching("META-INF/*.kotlin_module") {
            duplicatesStrategy = DuplicatesStrategy.INCLUDE
        }
        filesMatching("META-INF/services/**") { duplicatesStrategy = DuplicatesStrategy.INCLUDE }
        exclude("META-INF/*.SF", "META-INF/*.RSA", "META-INF/*.DSA")
    }

    runServer {
        // Configure the Minecraft version for our task.
        // This is the only required configuration besides applying the plugin.
        // Your plugin's jar (or shadowJar if present) will be used automatically.
        minecraftVersion("26.1.2")
        jvmArgs("-Xms2G", "-Xmx2G")
    }

    processResources {
        val props = mapOf("version" to version)
        inputs.properties(props)
        filesMatching("plugin.yml") {
            expand(props)
        }
    }
}

val foliaStorageSmokeJar = tasks.register<Jar>("foliaStorageSmokeJar") {
    dependsOn(tasks.testClasses)
    from(sourceSets.test.get().output) { include("com/alytanta/oneblock/smoke/FoliaStorageSmokePlugin*") }
    from("src/test/folia") { include("plugin.yml") }
    archiveClassifier.set("folia-storage-smoke")
    destinationDirectory.set(layout.buildDirectory.dir("folia-smoke-artifacts"))
}

tasks.register<JavaExec>("buildResourcePack") {
    description = "Build original OneBlock item/equipment assets and a deterministic Java 26.1.2 resource pack ZIP. No publishing."
    dependsOn(tasks.testClasses)
    classpath=sourceSets.test.get().runtimeClasspath
    mainClass.set("com.alytanta.oneblock.tools.ResourcePackBuilder")
    if(providers.gradleProperty("refreshOriginalArtwork").orNull=="true")args("--refresh-original-artwork")
    jvmArgs("-Djava.awt.headless=true")
}
tasks.register<JavaExec>("prepareVisualPreview") {
    description="Export current hub and mine blueprints for offline visual review; no server or client access."
    dependsOn(tasks.testClasses)
    classpath=sourceSets.test.get().runtimeClasspath
    mainClass.set("com.alytanta.oneblock.tools.VisualPreviewBuilder")
}
tasks.register<JavaExec>("prepareCombatStage2") {
    description="Stage combat changes on copies of public YAML without installation or resource-pack generation."
    dependsOn(tasks.testClasses)
    classpath=sourceSets.test.get().runtimeClasspath
    mainClass.set("com.alytanta.oneblock.tools.CombatStage2InstallBuilder")
    args(providers.gradleProperty("combatConfigs").getOrElse(""))
}
tasks.register<JavaExec>("prepareVisualPolish") {
    description="Stage and validate presentation updates on copies of public server YAML; no installation."
    dependsOn(tasks.testClasses,"buildResourcePack")
    classpath=sourceSets.test.get().runtimeClasspath
    mainClass.set("com.alytanta.oneblock.tools.VisualPolishInstallBuilder")
    args(providers.gradleProperty("polishConfigs").getOrElse(""))
}
tasks.register<JavaExec>("prepareCustomItems") {
    description="Read public server item/menu definitions and stage installation files under build/custom-items-install; never modifies the server."
    dependsOn("buildResourcePack")
    classpath=sourceSets.test.get().runtimeClasspath
    mainClass.set("com.alytanta.oneblock.tools.CustomItemsInstallBuilder")
    args(providers.gradleProperty("itemConfigs").getOrElse(""))
}

tasks.register<JavaExec>("foliaStorageSmoke") {
    description = "Opt-in isolated real Folia/Worlds persistence smoke test; reads only runtime JARs/libraries from -PfoliaRuntime."
    dependsOn(tasks.shadowJar, foliaStorageSmokeJar)
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass.set("com.alytanta.oneblock.smoke.FoliaStorageSmokeRunner")
    if(providers.gradleProperty("configRecovery").orNull=="true")jvmArgs("-Doneblock.config-recovery=true")
    providers.gradleProperty("smokeConfigs").orNull?.let {jvmArgs("-Doneblock.smoke-configs=$it")}
    val runtime = providers.gradleProperty("foliaRuntime")
    args(runtime.getOrElse(""), layout.buildDirectory.dir("folia-storage-smoke").get().asFile.absolutePath,
        tasks.shadowJar.get().archiveFile.get().asFile.absolutePath, foliaStorageSmokeJar.get().archiveFile.get().asFile.absolutePath)
}

val foliaHubSmokeJar=tasks.register<Jar>("foliaHubSmokeJar") {
    dependsOn(tasks.testClasses)
    from(sourceSets.test.get().output) {include("com/alytanta/oneblock/smoke/FoliaHubSmokePlugin*")}
    from("src/test/folia-hub") {include("plugin.yml")}
    archiveClassifier.set("folia-hub-smoke")
    destinationDirectory.set(layout.buildDirectory.dir("folia-smoke-artifacts"))
}
tasks.register<JavaExec>("foliaHubSmoke") {
    dependsOn(tasks.shadowJar,foliaHubSmokeJar)
    classpath=sourceSets.test.get().runtimeClasspath
    mainClass.set("com.alytanta.oneblock.smoke.FoliaStorageSmokeRunner")
    if(providers.gradleProperty("visualReview").orNull=="true")jvmArgs("-Doneblock.visual-hold=true")
    args(providers.gradleProperty("foliaRuntime").getOrElse(""),layout.buildDirectory.dir("folia-hub-smoke").get().asFile.absolutePath,
        tasks.shadowJar.get().archiveFile.get().asFile.absolutePath,foliaHubSmokeJar.get().archiveFile.get().asFile.absolutePath,"hub")
}

val foliaCombatSmokeJar=tasks.register<Jar>("foliaCombatSmokeJar") {
    dependsOn(tasks.testClasses)
    from(sourceSets.test.get().output) {include("com/alytanta/oneblock/smoke/FoliaCombatSmokePlugin*")}
    from("src/test/folia-combat") {include("plugin.yml")}
    archiveClassifier.set("folia-combat-smoke")
    destinationDirectory.set(layout.buildDirectory.dir("folia-smoke-artifacts"))
}
tasks.register<JavaExec>("foliaCombatSmoke") {
    dependsOn(tasks.shadowJar,foliaCombatSmokeJar)
    classpath=sourceSets.test.get().runtimeClasspath
    mainClass.set("com.alytanta.oneblock.smoke.FoliaStorageSmokeRunner")
    jvmArgs("-Doneblock.wind-combat=true")
    if(providers.gradleProperty("combatVisual").orNull=="true")jvmArgs("-Doneblock.combat-visual=true")
    args(providers.gradleProperty("foliaRuntime").getOrElse(""),layout.buildDirectory.dir("folia-combat-smoke").get().asFile.absolutePath,
        tasks.shadowJar.get().archiveFile.get().asFile.absolutePath,foliaCombatSmokeJar.get().archiveFile.get().asFile.absolutePath,"combat")
}

tasks.register<JavaExec>("foliaItemsSmoke") {
    description="Isolated Folia item/mandatory pack integration with loopback HTTP and six protocol clients; no client rendering."
    dependsOn(tasks.shadowJar,foliaCombatSmokeJar,"buildResourcePack")
    classpath=sourceSets.test.get().runtimeClasspath
    mainClass.set("com.alytanta.oneblock.smoke.FoliaStorageSmokeRunner")
    args(providers.gradleProperty("foliaRuntime").getOrElse(""),layout.buildDirectory.dir("folia-items-smoke").get().asFile.absolutePath,
        tasks.shadowJar.get().archiveFile.get().asFile.absolutePath,foliaCombatSmokeJar.get().archiveFile.get().asFile.absolutePath,"items")
}

val foliaMineSmokeJar=tasks.register<Jar>("foliaMineSmokeJar") {
    dependsOn(tasks.testClasses)
    from(sourceSets.test.get().output) {include("com/alytanta/oneblock/smoke/FoliaMineSmokePlugin*")}
    from("src/test/folia-mine") {include("plugin.yml")}
    archiveClassifier.set("folia-mine-smoke")
    destinationDirectory.set(layout.buildDirectory.dir("folia-smoke-artifacts"))
}
tasks.register<JavaExec>("foliaMineSmoke") {
    dependsOn(tasks.shadowJar,foliaMineSmokeJar)
    classpath=sourceSets.test.get().runtimeClasspath
    mainClass.set("com.alytanta.oneblock.smoke.FoliaStorageSmokeRunner")
    args(providers.gradleProperty("foliaRuntime").getOrElse(""),layout.buildDirectory.dir("folia-mine-smoke").get().asFile.absolutePath,
        tasks.shadowJar.get().archiveFile.get().asFile.absolutePath,foliaMineSmokeJar.get().archiveFile.get().asFile.absolutePath,"mine")
}

// Shared by every native runner. Keep full runtime only for explicit debugging.
val foliaEndlessSmokeJar=tasks.register<Jar>("foliaEndlessSmokeJar") {
    dependsOn(tasks.testClasses)
    from(sourceSets.test.get().output) {include("com/alytanta/oneblock/smoke/FoliaEndlessSmokePlugin*")}
    from("src/test/folia-endless") {include("plugin.yml")}
    archiveClassifier.set("folia-endless-smoke")
    destinationDirectory.set(layout.buildDirectory.dir("folia-smoke-artifacts"))
}
tasks.register<JavaExec>("foliaEndlessSmoke") {
    dependsOn(tasks.shadowJar,foliaEndlessSmokeJar)
    classpath=sourceSets.test.get().runtimeClasspath
    mainClass.set("com.alytanta.oneblock.smoke.FoliaStorageSmokeRunner")
    args(providers.gradleProperty("foliaRuntime").getOrElse(""),layout.buildDirectory.dir("folia-endless-smoke").get().asFile.absolutePath,
        tasks.shadowJar.get().archiveFile.get().asFile.absolutePath,foliaEndlessSmokeJar.get().archiveFile.get().asFile.absolutePath,"mine")
}
val foliaRewardsSmokeJar=tasks.register<Jar>("foliaRewardsSmokeJar") {
    dependsOn(tasks.testClasses)
    from(sourceSets.test.get().output) {include("com/alytanta/oneblock/smoke/FoliaRewardsSmokePlugin*")}
    from("src/test/folia-rewards") {include("plugin.yml")}
    archiveClassifier.set("folia-rewards-smoke")
    destinationDirectory.set(layout.buildDirectory.dir("folia-smoke-artifacts"))
}
tasks.register<JavaExec>("foliaRewardsSmoke") {
    dependsOn(tasks.shadowJar,foliaRewardsSmokeJar)
    classpath=sourceSets.test.get().runtimeClasspath
    mainClass.set("com.alytanta.oneblock.smoke.FoliaStorageSmokeRunner")
    args(providers.gradleProperty("foliaRuntime").getOrElse(""),layout.buildDirectory.dir("folia-rewards-smoke").get().asFile.absolutePath,
        tasks.shadowJar.get().archiveFile.get().asFile.absolutePath,foliaRewardsSmokeJar.get().archiveFile.get().asFile.absolutePath,"mine")
}
val foliaActivitiesSmokeJar=tasks.register<Jar>("foliaActivitiesSmokeJar") {
    dependsOn(tasks.testClasses)
    from(sourceSets.test.get().output) {include("com/alytanta/oneblock/smoke/FoliaActivitiesSmokePlugin*")}
    from("src/test/folia-activities") {include("plugin.yml")}
    archiveClassifier.set("folia-activities-smoke")
    destinationDirectory.set(layout.buildDirectory.dir("folia-smoke-artifacts"))
}
tasks.register<JavaExec>("foliaActivitiesSmoke") {
    dependsOn(tasks.shadowJar,foliaActivitiesSmokeJar)
    classpath=sourceSets.test.get().runtimeClasspath
    mainClass.set("com.alytanta.oneblock.smoke.FoliaStorageSmokeRunner")
    args(providers.gradleProperty("foliaRuntime").getOrElse(""),layout.buildDirectory.dir("folia-activities-smoke").get().asFile.absolutePath,
        tasks.shadowJar.get().archiveFile.get().asFile.absolutePath,foliaActivitiesSmokeJar.get().archiveFile.get().asFile.absolutePath,"mine")
}
val foliaReleaseSmokeJar=tasks.register<Jar>("foliaReleaseSmokeJar") {
    dependsOn(tasks.testClasses)
    from(sourceSets.test.get().output) {include("com/alytanta/oneblock/smoke/FoliaReleaseSmokePlugin*","com/alytanta/oneblock/smoke/ReleaseTelemetry*")}
    from("src/test/folia-release") {include("plugin.yml")}
    archiveClassifier.set("folia-release-smoke")
    destinationDirectory.set(layout.buildDirectory.dir("folia-smoke-artifacts"))
}
tasks.register<JavaExec>("foliaReleaseSmoke") {
    dependsOn(tasks.shadowJar,foliaReleaseSmokeJar)
    classpath=sourceSets.test.get().runtimeClasspath
    mainClass.set("com.alytanta.oneblock.smoke.FoliaStorageSmokeRunner")
    systemProperty("oneblock.smoke-clients",providers.gradleProperty("releaseClients").getOrElse("150"))
    systemProperty("oneblock.smoke-heap-mb","1024")
    args(providers.gradleProperty("foliaRuntime").getOrElse(""),layout.buildDirectory.dir("folia-release-smoke").get().asFile.absolutePath,
        tasks.shadowJar.get().archiveFile.get().asFile.absolutePath,foliaReleaseSmokeJar.get().archiveFile.get().asFile.absolutePath,"mine")
}
listOf("foliaStorageSmoke","foliaHubSmoke","foliaCombatSmoke","foliaItemsSmoke","foliaMineSmoke","foliaEndlessSmoke","foliaRewardsSmoke","foliaActivitiesSmoke","foliaReleaseSmoke").forEach {name->
    tasks.named<JavaExec>(name) {
        systemProperty("oneblock.keep-smoke-runtime",providers.gradleProperty("keepSmokeRuntime").getOrElse("false"))
    }
}
