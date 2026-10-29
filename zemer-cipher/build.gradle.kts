import java.util.Properties

// Vendored copy of zemer-cipher (https://github.com/ZemerTeam/zemer-cipher, GPL-3.0), commit eccd43e.
// YouTube signature/n deciphering via the player's own JS (remote-updated player configs) and
// PO token minting. Local adaptations are documented in NOTICE.md.
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.serialization)
}

val localSecrets = Properties().apply {
    rootProject.file("secrets.properties").takeIf { it.isFile }?.inputStream()?.use { load(it) }
}
val poTokenApiKey = providers.environmentVariable("AURALIS_POTOKEN_API_KEY")
    .orElse(providers.gradleProperty("auralisPotokenApiKey"))
    .getOrElse(localSecrets.getProperty("POTOKEN_API_KEY", "")).trim()
require(poTokenApiKey.isEmpty() || Regex("AIza[0-9A-Za-z_-]{35}").matches(poTokenApiKey)) {
    "PoToken API key must be a valid Google API key; configure AURALIS_POTOKEN_API_KEY or POTOKEN_API_KEY in secrets.properties"
}

android {
    namespace = "com.zemer.cipher"
    compileSdk = 35

    defaultConfig {
        minSdk = 24
        consumerProguardFiles("consumer-rules.pro")
        buildConfigField("String", "POTOKEN_API_KEY", "\"$poTokenApiKey\"")
    }

    buildFeatures { buildConfig = true }

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
