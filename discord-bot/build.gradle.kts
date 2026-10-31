import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// The Auralis Discord music bot: Auralis's search, stream lookup and lyrics (from :windows-core),
// played into voice channels. Build with botJar, run the jar on Java 25 or newer (see README.md).
plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

// JDAVE (Discord's required voice encryption) is published for Java 25 only. The compiler still
// targets 17 like the rest of the repo; this only lets Gradle accept that artifact.
listOf("compileClasspath", "runtimeClasspath", "testCompileClasspath", "testRuntimeClasspath").forEach { name ->
    configurations.named(name) { attributes.attribute(TargetJvmVersion.TARGET_JVM_VERSION_ATTRIBUTE, 25) }
}

// JDA brings a newer Kotlin standard library than the repo's compiler can read. Compile against the
// repo's version; the newer one stays at runtime, where it is backward compatible.
listOf("compileClasspath", "testCompileClasspath").forEach { name ->
    configurations.named(name) {
        resolutionStrategy.eachDependency {
            if (requested.group == "org.jetbrains.kotlin" && requested.name.startsWith("kotlin-stdlib")) useVersion(libs.versions.kotlin.get())
        }
    }
}

// The bot draws nothing, so it doesn't need the UI renderer that :windows-core brings.
configurations.named("runtimeClasspath") { exclude(group = "org.jetbrains.skiko") }

val jdaveVersion = "0.1.8"

dependencies {
    implementation(project(":windows-core"))
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.okhttp)
    implementation(libs.org.json)
    implementation("net.dv8tion:JDA:6.7.0") { exclude(module = "opus-java") }
    implementation("club.minnced:jdave-api:$jdaveVersion")
    listOf("win-x86-64", "linux-x86-64", "linux-aarch64").forEach {
        runtimeOnly("club.minnced:jdave-native-$it:$jdaveVersion")
    }
    implementation("dev.arbjerg:lavaplayer:2.2.7")
    runtimeOnly("org.slf4j:slf4j-simple:2.0.16")
    testImplementation(kotlin("test"))
}

tasks.test { useJUnitPlatform() }

// One runnable jar: java -jar discord-bot/build/libs/auralis-discord-bot.jar
tasks.register<Jar>("botJar") {
    archiveFileName.set("auralis-discord-bot.jar")
    isZip64 = true
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    manifest {
        attributes("Main-Class" to "com.auralis.music.bot.MainKt", "Enable-Native-Access" to "ALL-UNNAMED")
    }
    from(sourceSets.main.get().output)
    dependsOn(configurations.runtimeClasspath)
    from({ configurations.runtimeClasspath.get().map { if (it.isDirectory) it else zipTree(it) } })
    exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA", "module-info.class")
}
