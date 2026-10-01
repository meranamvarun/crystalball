// Plugins are applied per module so that :core never needs the Android Gradle Plugin.
plugins {
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.serialization) apply false
}
