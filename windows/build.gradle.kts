import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// The Windows app: a Compose Desktop app over its own core (windows/core). The Android app and
// :shared are never changed for Windows.
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.kotlin.compose)
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

dependencies {
    testImplementation(kotlin("test"))
    implementation(project(":windows-core"))
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    implementation(compose.materialIconsExtended)
    implementation(libs.kotlinx.coroutines.swing)
    // Artwork (Coil 3, the multiplatform version of the phone's image loader) and the JSON library
    // that Android has built in.
    implementation(libs.coil3.compose)
    implementation(libs.coil3.network.okhttp)
    implementation(libs.org.json)
    implementation("org.xerial:sqlite-jdbc:3.46.1.0")
    // Windows media overlay through the Windows Runtime (same JNA build VLCJ uses).
    implementation("net.java.dev.jna:jna-jpms:5.14.0")
    implementation("net.java.dev.jna:jna-platform-jpms:5.14.0")
}

// Creates two temporary anonymous participants, checks the room protocol, then removes both.
tasks.register<JavaExec>("verifyRoomService") {
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("com.auralis.music.windows.together.RoomServiceCheckKt")
}

// libVLC ships inside the installer, so users don't need VLC installed. It is copied at build
// time from a local 64-bit VLC 3 (AURALIS_VLC_DIR, default the standard install folder), keeping
// only what audio playback over HTTPS needs; no VLC binaries live in git. VLC is LGPL/GPL:
// its COPYING.txt and README.txt go with it. No plugins.dat: VLC's plugin cache stores file
// times, which packaging and installing change, and scanning the ~80 plugins takes ~0.3s.
val vlcSourceDir = providers.environmentVariable("AURALIS_VLC_DIR").orElse("C:/Program Files/VideoLAN/VLC")
val bundledVlcRoot = layout.buildDirectory.dir("vlc-resources")
val prepareVlc by tasks.registering(Sync::class) {
    val source = file(vlcSourceDir.get())
    from(source) {
        include("libvlc.dll", "libvlccore.dll", "COPYING.txt", "README.txt", "AUTHORS.txt")
        // Audio output, filters, mixing, packetizers and stream filters: all of them.
        include("plugins/audio_output/**", "plugins/audio_filter/**", "plugins/audio_mixer/**")
        include("plugins/packetizer/**", "plugins/stream_filter/**", "plugins/demux/**")
        include("plugins/logger/**", "plugins/keystore/**")
        // HTTPS streams (TLS through GnuTLS) and local files.
        include(
            "plugins/access/libhttp_plugin.dll", "plugins/access/libhttps_plugin.dll",
            "plugins/access/libfilesystem_plugin.dll", "plugins/access/libidummy_plugin.dll",
            "plugins/access/libimem_plugin.dll", "plugins/access/libaccess_concat_plugin.dll",
            "plugins/misc/libgnutls_plugin.dll", "plugins/misc/liblogger_plugin.dll", "plugins/misc/libxml_plugin.dll"
        )
        // Audio codecs (FFmpeg's avcodec covers AAC; Opus is YouTube's usual format).
        include(
            listOf("avcodec", "opus", "vorbis", "mpg123", "flac", "faad", "araw", "lpcm", "adpcm", "aes3", "g711", "speex")
                .map { "plugins/codec/lib${it}_plugin.dll" }
        )
    }
    into(bundledVlcRoot.map { it.dir("windows-x64/vlc") })
    doFirst {
        if (!File(source, "libvlc.dll").isFile) {
            throw GradleException("64-bit VLC 3 not found at $source. Install VLC or set AURALIS_VLC_DIR to its folder.")
        }
    }
}

compose.desktop {
    application {
        mainClass = "com.auralis.music.windows.MainKt"

        // packageReleaseMsi / packageReleaseExe shrink unused library code (see proguard-rules.pro).
        buildTypes.release.proguard {
            // 7.5 reads Java 21 class files; the 7.2 that Compose 1.7 defaults to cannot.
            version.set("7.5.0")
            obfuscate.set(false)
            optimize.set(false)
            configurationFiles.from(project.file("proguard-rules.pro"))
        }

        nativeDistributions {
            appResourcesRootDir.set(bundledVlcRoot)
            targetFormats(TargetFormat.Msi, TargetFormat.Exe)
            // From suggestRuntimeModules, plus jdk.crypto.ec (TLS with elliptic-curve ciphers, which
            // jdeps can't see) and java.naming.
            // jdk.httpserver: the local stream relay (LocalStreamRelay).
            modules("java.instrument", "java.net.http", "java.sql", "jdk.dynalink", "jdk.unsupported", "jdk.crypto.ec", "java.naming", "jdk.httpserver")
            packageName = "Auralis"
            // The owner controls version bumps; keep Windows independent of Android's version.
            packageVersion = "1.0.0"
            description = "Auralis music player"
            vendor = "Auralis"
            windows {
                menuGroup = "Auralis"
                shortcut = true
                perUserInstall = true
                // Fixed forever: Windows uses it to recognise newer installers as upgrades.
                upgradeUuid = "caf1f182-19ab-4e7d-951d-aeeab415f8ae"
                iconFile.set(project.file("icons/auralis.ico"))
            }
        }
    }
}

tasks.matching { it.name == "prepareAppResources" }.configureEach { dependsOn(prepareVlc) }

// Development check: draws Windows screens to PNGs without a window (see dev/RenderScreens.kt).
// ./gradlew :windows:renderScreens [-Pscreens=profile,settings]
tasks.register<JavaExec>("renderScreens") {
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("com.auralis.music.windows.dev.RenderScreensKt")
    val outDir = layout.buildDirectory.dir("screens").get().asFile
    args = listOf(outDir.absolutePath) + (findProperty("screens")?.toString()?.split(",") ?: emptyList())
}

// Explicit authenticated, read-only account/library check; no browser databases are read.
tasks.register<JavaExec>("verifyYouTubeLibrary") {
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("com.auralis.music.windows.dev.VerifyYouTubeLibraryKt")
}

// Owner-provided desktop OAuth client, outside version control.
tasks.named<ProcessResources>("processResources") { from("local-resources") }

tasks.register<JavaExec>("verifyRoomPlayback") {
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("com.auralis.music.windows.together.RoomPlaybackCheckKt")
}

// Opens only its own disposable test window; never controls the installed app.
tasks.register<JavaExec>("verifyWindowFrame") {
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("com.auralis.music.windows.dev.WindowFrameCheckKt")
}
