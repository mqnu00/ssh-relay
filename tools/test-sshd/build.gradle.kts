plugins {
    alias(libs.plugins.kotlin.jvm)
    application
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
    implementation(libs.sshd.core)
    implementation(libs.eddsa)
    implementation(libs.slf4j.simple)
}

application {
    mainClass.set("dev.sshrelay.testsshd.MainKt")
}
