plugins {
    // Auto-provisions a JDK for jvmToolchain(21) on any machine (no
    // machine-specific org.gradle.java.installations.paths needed).
    id("org.gradle.toolchains.foojay-resolver-convention") version "0.8.0"
}

rootProject.name = "music-syncer-android"
include(":engine")
