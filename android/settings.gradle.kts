pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

plugins {
    // Auto-provisions a JDK for jvmToolchain(21) on any machine (no
    // machine-specific org.gradle.java.installations.paths needed).
    id("org.gradle.toolchains.foojay-resolver-convention") version "0.8.0"
}

dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "music-syncer-android"
include(":engine")
include(":app")
