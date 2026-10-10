import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Code shared by the Android app and the Windows app (and later macOS).
// commonMain: platform-free Kotlin. androidMain / desktopMain: the few platform hooks it needs.
plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.library)
    // Applied so models here get the same Compose stability inference they had inside :app.
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    androidTarget {
        compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
    }
    jvm("desktop") {
        compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
    }

    sourceSets {
        commonMain.dependencies {
            implementation(libs.compose.runtime)
            // Compose UI shared by the phone and Windows screens (on Android these resolve to the
            // androidx artifacts, versions still pinned by the app's BOM).
            implementation(libs.compose.mp.ui)
            implementation(libs.compose.mp.foundation)
            implementation(libs.compose.mp.material3)
            implementation(libs.compose.mp.icons.extended)
            implementation(libs.androidx.collection)
            // Morphing shapes for the lyrics' wavy interval indicators.
            implementation(libs.androidx.graphics.shapes)
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.okhttp)
            implementation("com.github.promeg:tinypinyin:2.0.3")
            // YouTube stream extraction (the phone's playback resolver; desktop playback).
            implementation("com.github.TeamNewPipe:NewPipeExtractor:v0.26.5") {
                exclude(group = "com.google.protobuf")
            }
            // Android ships org.json in the framework, so the app keeps using that copy; only
            // the desktop app bundles one.
            compileOnly(libs.org.json)
        }
        androidMain.dependencies {
            // Compose versions on Android stay pinned by the app's BOM.
            implementation(project.dependencies.platform(libs.androidx.compose.bom))
        }
        val desktopMain by getting {
            dependencies {
                implementation(libs.org.json)
                // Desktop audio playback through libVLC.
                implementation("uk.co.caprica:vlcj:4.8.3")
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

android {
    namespace = "com.auralis.music.shared"
    compileSdk = 35
    defaultConfig {
        minSdk = 24
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
