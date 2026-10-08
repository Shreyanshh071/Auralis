import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// The Windows app: a Compose Desktop shell over shared/. Anything a future macOS app would also
// need belongs in shared/ (desktopMain), not here.
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
    implementation(project(":shared"))
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    implementation(compose.materialIconsExtended)
    implementation(libs.kotlinx.coroutines.swing)
}

compose.desktop {
    application {
        mainClass = "com.auralis.music.windows.MainKt"

        nativeDistributions {
            targetFormats(TargetFormat.Msi, TargetFormat.Exe)
            packageName = "Auralis"
            packageVersion = "1.1.2"
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
