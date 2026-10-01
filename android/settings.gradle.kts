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
        // Google Maven only serves Android artifacts; keep JVM lookups away from it so the
        // pure-Kotlin :core module builds even where dl.google.com is unreachable.
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
    }
}

rootProject.name = "hearth"

include(":core")

// The Android app needs the Android SDK (ANDROID_HOME / ANDROID_SDK_ROOT or local.properties sdk.dir).
val sdkAvailable = System.getenv("ANDROID_HOME") != null ||
    System.getenv("ANDROID_SDK_ROOT") != null ||
    file("local.properties").takeIf { it.exists() }?.readText()?.contains("sdk.dir") == true
if (sdkAvailable) {
    include(":app")
} else {
    logger.lifecycle("Hearth: Android SDK not found — building :core only (set ANDROID_HOME to include :app).")
}
