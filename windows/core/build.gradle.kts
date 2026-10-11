import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// The Windows app's core: its own copy of Auralis's Kotlin code (started from the phone's), so the
// Windows app can change freely without touching the Android app. The Android app never builds
// from this module. commonMain: platform-free Kotlin; desktopMain: the desktop implementations.
plugins {
    alias(libs.plugins.kotlin.multiplatform)
    // Applied so models here get the same Compose stability inference they had inside :app.
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

// UI strings live in the phone's res/values*/strings.xml. Shared UI gets a generated ID per string
// (Strings.history), and the desktop app bundles the same files (DesktopStrings reads them).
val phoneRes = rootProject.file("android/app/src/main/res")
val generateStringIds by tasks.registering {
    val source = File(phoneRes, "values/strings.xml")
    val outDir = layout.buildDirectory.dir("generated/auralisStrings/kotlin")
    inputs.file(source)
    outputs.dir(outDir)
    doLast {
        val names = Regex("""<string\s+name="([A-Za-z0-9_]+)"""").findAll(source.readText()).map { it.groupValues[1] }.distinct().toList()
        val file = outDir.get().file("com/auralis/music/ui/i18n/Strings.kt").asFile
        file.parentFile.mkdirs()
        file.writeText(buildString {
            appendLine("// Generated from android/app/src/main/res/values/strings.xml by :windows-core:generateStringIds. Do not edit.")
            appendLine("package com.auralis.music.ui.i18n")
            appendLine()
            appendLine("@Suppress(\"unused\", \"ObjectPropertyName\")")
            appendLine("object Strings {")
            names.forEach { appendLine("    val `$it` = StringId(\"$it\")") }
            appendLine("}")
        })
    }
}
val bundleDesktopStrings by tasks.registering(Sync::class) {
    from(phoneRes) {
        include("values*/strings.xml")
        into("i18n")
    }
    // The logo drawables shared UI draws (DesktopBrandImages).
    from(File(phoneRes, "drawable")) {
        include("ic_auralis_header_logo.png", "ic_auralis_logo.png", "ic_github.xml", "ic_discord.xml")
        into("brand")
    }
    into(layout.buildDirectory.dir("generated/auralisStrings/desktopResources"))
}

kotlin {
    jvm("desktop") {
        compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
    }

    // The same opt-ins as the app, so moved UI compiles unchanged.
    sourceSets.all {
        languageSettings.optIn("androidx.compose.material3.ExperimentalMaterial3Api")
        languageSettings.optIn("androidx.compose.foundation.ExperimentalFoundationApi")
        languageSettings.optIn("androidx.compose.animation.ExperimentalAnimationApi")
        languageSettings.optIn("kotlinx.coroutines.ExperimentalCoroutinesApi")
    }

    sourceSets {
        commonMain {
            kotlin.srcDir(generateStringIds.map { layout.buildDirectory.dir("generated/auralisStrings/kotlin").get() })
        }
        commonMain.dependencies {
            implementation(libs.compose.runtime)
            // Compose UI (Compose Multiplatform, the versions the phone's screens were written for).
            implementation(libs.compose.mp.ui)
            implementation(libs.compose.mp.foundation)
            implementation(libs.compose.mp.material3)
            implementation(libs.compose.mp.icons.extended)
            implementation(libs.androidx.collection)
            // Morphing shapes for the lyrics' wavy interval indicators.
            implementation(libs.androidx.graphics.shapes)
            // Artwork colour schemes (HCT tonal palettes), the version the app uses.
            implementation("com.materialkolor:material-kolor:2.0.2")
            // Backdrop blur (multiplatform), the version the app uses.
            implementation("dev.chrisbanes.haze:haze:1.3.1")
            // ViewModel (multiplatform; the app's AndroidX lifecycle on Android).
            api("org.jetbrains.androidx.lifecycle:lifecycle-viewmodel:2.8.4")
            // Settings files (Jetpack DataStore Preferences; the version the app uses, multiplatform).
            api("androidx.datastore:datastore-preferences-core:1.1.1")
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.okhttp)
            implementation("com.github.promeg:tinypinyin:2.0.3")
            // YouTube stream extraction (the phone's playback resolver; desktop playback).
            implementation("com.github.TeamNewPipe:NewPipeExtractor:v0.26.5") {
                exclude(group = "com.google.protobuf")
            }
            compileOnly(libs.org.json)
        }
        val desktopMain by getting {
            resources.srcDir(bundleDesktopStrings.map { layout.buildDirectory.dir("generated/auralisStrings/desktopResources").get() })
            dependencies {
                implementation(libs.org.json)
                // Desktop audio playback through libVLC.
                // Artwork (Coil 3, the multiplatform version of the phone's image loader).
                implementation(libs.coil3.compose)
                implementation(libs.coil3.network.okhttp)
                implementation("uk.co.caprica:vlcj:4.8.3")
                implementation("net.java.dev.jna:jna-platform-jpms:5.14.0")
                // NewPipe's protobuf runtime: excluded above because the Android app already gets
                // this version through Firebase.
                implementation("com.google.protobuf:protobuf-javalite:3.25.1")
            }
        }
        val desktopTest by getting {
            dependencies {
                implementation(kotlin("test"))
            }
        }
    }
}

