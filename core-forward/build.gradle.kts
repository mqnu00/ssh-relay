plugins {
    alias(libs.plugins.kotlin.jvm)
}

// Pure-JVM module on purpose: the whole forwarding engine can be unit- and
// integration-tested with `./gradlew :core-forward:test` on a machine with no
// Android device and no emulator.
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
    api(libs.kotlinx.coroutines.core)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}

tasks.withType<Test>().configureEach {
    testLogging {
        events("passed", "failed", "skipped")
        showStandardStreams = true
    }
}
