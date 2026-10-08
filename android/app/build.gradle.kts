plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

import java.util.Properties

val envProperties = Properties().apply {
    // 1. Check workspace root .env
    val rootEnv = rootProject.file(".env")
    if (rootEnv.exists()) {
        rootEnv.inputStream().use { load(it) }
    }
    // 2. Check android folder .env
    val androidEnv = rootProject.file("android/.env")
    if (androidEnv.exists()) {
        androidEnv.inputStream().use { load(it) }
    }
    // 3. Check local.properties
    val localProps = rootProject.file("local.properties")
    if (localProps.exists()) {
        localProps.inputStream().use { load(it) }
    }
}

fun getEnv(key: String, default: String = ""): String {
    return System.getenv(key) ?: envProperties.getProperty(key)?.trim() ?: default
}

android {
    namespace = "com.auralis.music"
    compileSdk = 35
    ndkVersion = "27.2.12479018"

    defaultConfig {
        applicationId = "com.auralis.music"
        minSdk = 24
        targetSdk = 35
        versionCode = 4
        versionName = "1.1.2"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }

        val webClientId = getEnv("GOOGLE_WEB_CLIENT_ID", "30030184374-pe7h8deq7qp2josb62junld16udgnnin.apps.googleusercontent.com")
        val youtubeApiKey = getEnv("YOUTUBE_API_KEY", "")
        val spotifyClientId = getEnv("SPOTIFY_CLIENT_ID", "")
        val spotifyClientSecret = getEnv("SPOTIFY_CLIENT_SECRET", "")

        buildConfigField("String", "GOOGLE_WEB_CLIENT_ID", "\"$webClientId\"")
        buildConfigField("String", "YOUTUBE_API_KEY", "\"$youtubeApiKey\"")
        buildConfigField("String", "SPOTIFY_CLIENT_ID", "\"$spotifyClientId\"")
        buildConfigField("String", "SPOTIFY_CLIENT_SECRET", "\"$spotifyClientSecret\"")
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.getByName("debug")
        }
        debug {
            isDebuggable = true
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
        freeCompilerArgs = freeCompilerArgs + listOf(
            "-opt-in=androidx.compose.material3.ExperimentalMaterial3Api",
            "-opt-in=androidx.compose.foundation.ExperimentalFoundationApi",
            "-opt-in=androidx.compose.animation.ExperimentalAnimationApi",
            "-opt-in=kotlinx.coroutines.ExperimentalCoroutinesApi"
        )
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            excludes += "META-INF/LICENSE.md"
            excludes += "META-INF/LICENSE-notice.md"
        }
        // Compress native libraries in the APK (Android unpacks them at install). The Discord Social
        // SDK is ~33 MB across the four ABIs and was stored uncompressed, making the APK ~45 MB;
        // compressed it's ~25 MB. Costs a slightly slower install and ~9 MB more once installed.
        jniLibs {
            useLegacyPackaging = true
        }
    }
}

dependencies {
    // AndroidX Core & Lifecycle
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.core.splashscreen)

    // Jetpack Compose
    val composeBom = platform(libs.androidx.compose.bom)
    implementation(composeBom)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.compose.ui.text.google.fonts)
    debugImplementation(libs.androidx.compose.ui.tooling)
    implementation(libs.androidx.graphics.shapes)
    implementation("sh.calvin.reorderable:reorderable:2.4.3")

    // AndroidX Media3 & ExoPlayer
    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.session)
    implementation(libs.androidx.media3.ui)
    implementation(libs.androidx.media3.common)
    implementation(libs.androidx.media3.datasource.okhttp)
    implementation("androidx.media:media:1.7.0")
    implementation("androidx.browser:browser:1.8.0")

    // Discord's official Android Social SDK. Its Prefab package supplies the native
    // rich-presence client used by DiscordSocialClient.
    implementation(files("libs/discord_partner_sdk.jar", "libs/libwebrtc.jar"))

    // Room Database
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    // DataStore Preferences
    implementation(libs.androidx.datastore.preferences)

    // Networking & Async
    implementation(libs.okhttp)
    implementation(libs.okhttp.logging)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)

    // Coil & Palette
    implementation(libs.coil.compose)
    implementation(libs.androidx.palette.ktx)

    // Firebase (Auth, Firestore, Realtime DB, Cloud Messaging)
    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.auth)
    implementation(libs.firebase.firestore)
    implementation(libs.firebase.database)
    implementation(libs.firebase.messaging)
    implementation(libs.androidx.credentials)
    implementation(libs.androidx.credentials.play.services.auth)
    implementation(libs.google.id)

    // Background WorkManager
    implementation(libs.androidx.work.runtime.ktx)

    // Unit Testing
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.mockk)
    testImplementation(libs.turbine)
    testImplementation(libs.androidx.room.testing)
    testImplementation("org.json:json:20240303")

    // Mozilla Rhino JavaScript engine for high-speed cipher evaluation
    implementation("org.mozilla:rhino:1.7.15")

    // Pinyin for Settings → Content → Romanize lyrics (Chinese lines); ~150 KB, no dictionary files.
    implementation("com.github.promeg:tinypinyin:2.0.3")

    // NewPipeExtractor for native YouTube stream extraction and cipher deobfuscation
    implementation("com.github.TeamNewPipe:NewPipeExtractor:v0.26.5") {
        exclude(group = "com.google.protobuf")
    }

    // YouTube signature/n deciphering and PO tokens for signed-in (age-restricted) downloads.
    // Vendored module, see zemer-cipher/NOTICE.md. It logs through Timber.
    implementation(project(":shared"))
    implementation(project(":zemer-cipher"))
    implementation("com.jakewharton.timber:timber:5.0.1")

    // High-performance live backdrop blur (Frosted Glass like Photo 2)
    implementation("dev.chrisbanes.haze:haze:1.3.1")
    implementation("dev.chrisbanes.haze:haze-materials:1.3.1")

    // Material 3 Dynamic Color Theming (Google Monet / Material Color Utilities)
    implementation("com.materialkolor:material-kolor:2.0.2")

    // Android Instrumented Testing
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(composeBom)
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
}

val servicesJson = file("google-services.json")
if (servicesJson.exists() && servicesJson.length() > 0) {
    apply(plugin = "com.google.gms.google-services")
}
