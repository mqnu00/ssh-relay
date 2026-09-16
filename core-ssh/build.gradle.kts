plugins {
    alias(libs.plugins.kotlin.jvm)
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
    api(project(":core-forward"))
    // mwiede/jsch: pure Java, zero transitive dependencies, no native code.
    // Running it as a JVM module means the SSH engine is testable without an
    // emulator, and its unit tests live next to the code.
    api(libs.jsch)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    // A real SSH server, used only from tests: integration coverage that would
    // otherwise require a live remote host.
    testImplementation(libs.sshd.core)
    // MINA needs this optional provider to verify ssh-ed25519 client keys, which is
    // the key type the private-key auth tests use.
    testImplementation(libs.eddsa)
    testImplementation(libs.slf4j.simple)
}

tasks.withType<Test>().configureEach {
    testLogging {
        events("passed", "failed", "skipped")
        showStandardStreams = true
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}
