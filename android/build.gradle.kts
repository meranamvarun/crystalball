// The Android Gradle Plugin (and KSP) must share a classloader with the Kotlin Gradle Plugin, so
// all of them live on the root classpath. AGP/KSP are only added when an Android SDK is present
// (see settings.gradle.kts): the pure-Kotlin :core module must build without Google Maven.
// Keep these versions in sync with gradle/libs.versions.toml (agp, ksp).
buildscript {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
    }
    dependencies {
        if (System.getProperty("hearth.sdkAvailable") == "true") {
            classpath("com.android.tools.build:gradle:8.7.3")
            classpath("com.google.devtools.ksp:symbol-processing-gradle-plugin:2.0.21-1.0.28")
        }
    }
}

plugins {
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.kotlin.compose) apply false
}

// ---- ktlint: parses + style-checks every Kotlin file, including :app (needs no Android SDK) ----
val ktlint: Configuration by configurations.creating

dependencies {
    ktlint("com.pinterest.ktlint:ktlint-cli:1.5.0") {
        attributes { attribute(Bundling.BUNDLING_ATTRIBUTE, objects.named(Bundling.EXTERNAL)) }
    }
}

val kotlinSources = listOf("core/src/**/*.kt", "app/src/**/*.kt", "**/*.kts", "!**/build/**")

tasks.register<JavaExec>("ktlintCheck") {
    group = "verification"
    description = "Parse and style-check all Kotlin sources (core and app)."
    classpath = ktlint
    mainClass.set("com.pinterest.ktlint.Main")
    args(kotlinSources)
}

tasks.register<JavaExec>("ktlintFormat") {
    group = "formatting"
    description = "Auto-format all Kotlin sources."
    classpath = ktlint
    mainClass.set("com.pinterest.ktlint.Main")
    args(listOf("-F") + kotlinSources)
}
