// JS8 modem, protocol and station engine in plain Kotlin (no Android), ported
// from the Swift JS8Core package of JS8 Firefly for iPhone. The app uses it and
// the unit tests run it on the JVM, including cross-checks against a reference
// harness built from the original JS8Call C++ source.
plugins {
    id("org.jetbrains.kotlin.jvm")
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
    testImplementation("junit:junit:4.13.2")
}

tasks.test {
    // Path to the js8ref binary built from tools/refharness in the iPhone repo.
    // Reference cross-check tests are skipped when it is missing.
    systemProperty(
        "js8ref",
        System.getenv("JS8REF")
            ?: "${System.getProperty("user.home")}/Desktop/js8calliphone/tools/refharness/build/js8ref",
    )
    maxHeapSize = "2g"
    testLogging { events("failed", "skipped"); exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL }
}
