// Vendored copy of zemer-cipher (https://github.com/ZemerTeam/zemer-cipher, GPL-3.0), commit eccd43e.
// YouTube signature/n deciphering via the player's own JS (remote-updated player configs) and
// PO token minting. Sources are unmodified; see NOTICE.md.
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.zemer.cipher"
    compileSdk = 35

    defaultConfig {
        minSdk = 24
        consumerProguardFiles("consumer-rules.pro")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.okhttp)
    implementation("androidx.collection:collection-ktx:1.4.5")
    implementation("androidx.annotation:annotation:1.9.1")
    // The library logs through Timber; the app provides it.
    compileOnly("com.jakewharton.timber:timber:5.0.1")
}
