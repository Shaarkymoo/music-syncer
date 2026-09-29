plugins {
    kotlin("jvm")
}

repositories { mavenCentral() }

dependencies {
    implementation("org.xerial:sqlite-jdbc:3.46.0.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.google.code.gson:gson:2.11.0")
    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")
}

kotlin { jvmToolchain(21) }

tasks.test { useJUnitPlatform() }
