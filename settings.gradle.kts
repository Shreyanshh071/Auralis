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
include(":app")
project(":app").projectDir = file("android/app")
include(":zemer-cipher")
project(":zemer-cipher").projectDir = file("android/zemer-cipher")
include(":shared")
