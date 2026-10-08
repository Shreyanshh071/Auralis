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
            implementation(libs.androidx.collection)
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.okhttp)
            implementation("com.github.promeg:tinypinyin:2.0.3")
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
