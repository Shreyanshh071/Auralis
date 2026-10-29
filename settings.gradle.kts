pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven { url = uri("https://jitpack.io") }
        // TinyPinyin (lyrics romanization) only survives on this JCenter mirror; it may serve
        // nothing else, so no other dependency can ever resolve from it.
        exclusiveContent {
            forRepository { maven { url = uri("https://maven.aliyun.com/repository/public") } }
            filter { includeModule("com.github.promeg", "tinypinyin") }
        }
    }
}

rootProject.name = "Auralis"
// The Gradle build lives at the repo root so the Android, Windows (and later Mac) apps and the
// shared code can sit side by side. Modules keep their old paths for now.
val hasAndroidSdk = run {
    val localProps = file("local.properties")
    val sdkDir = if (localProps.isFile) {
        val props = java.util.Properties().apply { localProps.inputStream().use(::load) }
        props.getProperty("sdk.dir")
    } else null
    val envSdk = System.getenv("ANDROID_HOME") ?: System.getenv("ANDROID_SDK_ROOT")
    (sdkDir?.let { file(it) }?.isDirectory == true) || (envSdk?.let { file(it) }?.isDirectory == true)
}
if (hasAndroidSdk) {
    include(":app")
    project(":app").projectDir = file("android/app")
    include(":zemer-cipher")
    project(":zemer-cipher").projectDir = file("zemer-cipher")
    include(":shared")
}
include(":windows")
// The Windows app's own copy of the core code; the Android app builds only from :shared.
include(":windows-core")
project(":windows-core").projectDir = file("windows/core")
