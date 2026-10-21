import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.Properties

// The Windows app: a Compose Desktop app over its own core (windows/core). The Android app and
// :shared are never changed for Windows.
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.kotlin.compose)
}

// Windows has its own release sequence. Never derive this from the Android app version.
val windowsVersion = Properties().apply { file("version.properties").inputStream().use(::load) }
    .getProperty("version").also { require(it.matches(Regex("[0-9]+\\.[0-9]+\\.[0-9]+"))) }

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
// its COPYING.txt and README.txt go with it. No plugins.dat here: VLC's plugin cache stores file
// times, which packaging and installing change. VlcAudioPlayer writes it on the installed copy
// instead (scanning every plugin at each start took 5-20s inside the app).
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
        // Animated covers: decoded video handed to the app as frames (memory output + pixel conversion).
        include("plugins/video_output/libvmem_plugin.dll", "plugins/video_chroma/**")
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

// One class-data archive per build (see the jvmArgs below).
val cdsArchive = "auralis-cds-${System.currentTimeMillis().toString(36)}.jsa"

compose.desktop {
    application {
        mainClass = "com.auralis.music.windows.MainKt"
        jvmArgs(
            // Skia draws through its own Direct3D device; Java2D's separate Direct3D pipeline
            // only cost ~0.7s of the first frame (measured with the startup sampler).
            "-Dsun.java2d.d3d=false",
            // Direct3D, not OpenGL. OpenGL let Windows' maximize/restore animations capture the real
            // window (Direct3D ghosts the old layout for ~150ms), but under OpenGL NVIDIA's overlay
            // never showed its screenshot/recording popups, and Snipping Tool froze an earlier page
            // instead of the current one. The user tested both on 2026-10-21 and chose Direct3D.
            "-Dskiko.renderApi=DIRECT3D",
            // Class data sharing: the first frame spent ~1.2s loading classes from the jars. Each
            // start records the classes it loads; when this build's archive is missing, the app
            // builds it from that list in a background process (DesktopClassArchive), and later
            // starts map it. Each build names its own: JDK 21 ignores, never rebuilds, a stale one.
            // (Its own record-at-exit mode made that one launch take 20-27s, so it isn't used.)
            "-XX:SharedArchiveFile=\$APPDIR/$cdsArchive",
            "-XX:DumpLoadedClassList=\$APPDIR/auralis-classes.lst",
            "-Dauralis.cdsArchive=$cdsArchive"
        )
        // Software rendering is exercised by verifySoftwareRendering before enabling it
        // in the installer; GPU rendering remains until that performance check passes.

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
            modules("java.instrument", "java.net.http", "java.sql", "jdk.dynalink", "jdk.unsupported", "jdk.crypto.ec", "java.naming", "jdk.httpserver", "jdk.localedata")
            packageName = "Auralis"
            // The owner controls version bumps; keep Windows independent of Android's version.
            packageVersion = windowsVersion
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

// The installers keep gaming overlays (NVIDIA, RivaTuner) off Auralis (see scripts/add-msi-overlay-step.ps1).
tasks.matching { it.name == "packageMsi" || it.name == "packageReleaseMsi" }.configureEach {
    val script = rootProject.file("scripts/add-msi-overlay-step.ps1")
    doLast {
        outputs.files.asFileTree.matching { include("**/*.msi") }.forEach { msi ->
            val process = ProcessBuilder("powershell", "-NoProfile", "-ExecutionPolicy", "Bypass", "-File", script.path, "-Msi", msi.path)
                .inheritIO().start()
            check(process.waitFor() == 0) { "Adding the gaming-overlay step to ${msi.name} failed" }
        }
    }
}

// Development check: draws Windows screens to PNGs without a window (see dev/RenderScreens.kt).
// ./gradlew :windows:renderScreens [-Pscreens=profile,settings]
tasks.register<JavaExec>("renderScreens") {
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("com.auralis.music.windows.dev.RenderScreensKt")
    val outDir = layout.buildDirectory.dir("screens").get().asFile
    args = listOf(outDir.absolutePath) + (findProperty("screens")?.toString()?.split(",") ?: emptyList())
}

tasks.register<JavaExec>("renderPlayerTabs") {
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("com.auralis.music.windows.dev.RenderPlayerTabsKt")
    workingDir(rootProject.projectDir)
}

// Explicit authenticated, read-only account/library check; no browser databases are read.
tasks.register<JavaExec>("verifyYouTubeLibrary") {
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("com.auralis.music.windows.dev.VerifyYouTubeLibraryKt")
}

// Owner-provided desktop OAuth client, outside version control.
tasks.named<ProcessResources>("processResources") {
    from("local-resources")
    from("version.properties") { rename { "windows-version.properties" } }
}

tasks.register<JavaExec>("verifyRoomPlayback") {
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("com.auralis.music.windows.together.RoomPlaybackCheckKt")
}

// Opens only its own disposable test window; never controls the installed app.
tasks.register<JavaExec>("verifyWindowFrame") {
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("com.auralis.music.windows.dev.WindowFrameCheckKt")
}

tasks.register<JavaExec>("checkWindowsRelease") {
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("com.auralis.music.windows.dev.CheckWindowsReleaseKt")
}

tasks.register<JavaExec>("verifyPlaybackControls") {
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("com.auralis.music.windows.dev.PlaybackControlsCheckKt")
}

tasks.register<JavaExec>("verifySoftwareRendering") {
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("com.auralis.music.windows.dev.SoftwareRenderingCheckKt")
    jvmArgs("-Dskiko.renderApi=${findProperty("softwareBackend") ?: "SOFTWARE_FAST"}", "-Dsun.java2d.d3d=false")
    if (findProperty("basicRendererCheck") != null) jvmArgs("-Dauralis.check.basic=true")
    findProperty("rendererPart")?.let { jvmArgs("-Dauralis.check.part=$it") }
}

// Development check: ./gradlew :windows:probeLyrics -Pargs="Title::Artist::durationSec[::videoId]"
tasks.register<JavaExec>("probeLyrics") {
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("com.auralis.music.windows.dev.ProbeLyricsKt")
    args = (findProperty("args")?.toString() ?: "").split("::")
}

// Development check: ./gradlew :windows:probeStream -Pargs="videoId"
tasks.register<JavaExec>("probeStream") {
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("com.auralis.music.windows.dev.ProbeStreamKt")
    args = listOfNotNull(findProperty("args")?.toString())
}

// Development check: ./gradlew :windows:probeStartup -Pargs="vlcDir::videoId::videoId..." (silent; time to sound)
tasks.register<JavaExec>("probeStartup") {
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("com.auralis.music.windows.dev.ProbeStartupKt")
    args = listOfNotNull(findProperty("args")?.toString())
    findProperty("relayCachingMs")?.let { systemProperty("auralis.relayCachingMs", it.toString()) }
}

// Development check: ./gradlew :windows:probeStall -Pargs="vlcDir::videoId" (silent; a stalled stream recovers by itself)
tasks.register<JavaExec>("probeStall") {
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("com.auralis.music.windows.dev.ProbeStallKt")
    args = listOfNotNull(findProperty("args")?.toString())
}

tasks.register<JavaExec>("probeGuest") {
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("com.auralis.music.windows.dev.ProbeGuestKt")
}

// Development check: ./gradlew :windows:probeHome (signed-in Home shelves and their covers; read only)
tasks.register<JavaExec>("probeHome") {
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("com.auralis.music.windows.dev.ProbeHomeKt")
    args = listOfNotNull(findProperty("args")?.toString())
}

tasks.register<JavaExec>("probeAlbum") {
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("com.auralis.music.windows.dev.ProbeAlbumKt")
    args = listOfNotNull(findProperty("args")?.toString())
}

// Development check: ./gradlew :windows:probeCanvas -Pargs="Title::Artist" (animated covers; no window or sound)
tasks.register<JavaExec>("probeCanvas") {
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("com.auralis.music.windows.dev.ProbeCanvasKt")
    args = listOfNotNull(findProperty("args")?.toString())
    findProperty("dataDir")?.let { systemProperty("auralis.dataDir", it.toString()) }
}

// The app builds its class-data archive with java.exe (see the jvmArgs above); jlink strips the
// launcher from the runtime image, so the same JDK's one is put back.
tasks.matching { it.name == "createRuntimeImage" }.configureEach {
    doLast {
        val image = layout.buildDirectory.dir("compose/tmp/main/runtime").get().asFile
        val jdk = File(compose.desktop.application.javaHome ?: System.getProperty("java.home"))
        fun version(home: File) = File(home, "release").takeIf { it.isFile }?.readLines()
            ?.firstOrNull { it.startsWith("JAVA_VERSION=") }
        check(version(image) != null && version(image) == version(jdk)) {
            "Runtime ${version(image)} and JDK ${version(jdk)} differ; java.exe must match its jvm.dll"
        }
        File(jdk, "bin/java.exe").copyTo(File(image, "bin/java.exe"), overwrite = true)
    }
}
