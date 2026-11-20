import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

// Release signing. Credentials live in keystore.properties at the repository root,
// which is gitignored -- see scripts/make-keystore.sh. When the file is absent the
// release build still runs, it just produces an unsigned APK, so a fresh clone or a
// CI job without secrets is not broken by it.
val keystorePropertiesFile = rootProject.file("keystore.properties")
val keystoreProperties = Properties().apply {
    if (keystorePropertiesFile.exists()) {
        keystorePropertiesFile.inputStream().use { load(it) }
    }
}
val releaseSigningConfigured = keystoreProperties.getProperty("storeFile") != null

android {
    namespace = "dev.sshrelay"
    compileSdk = 35
    // AGP 8.7 would otherwise default to build-tools 34.0.0 and try to fetch it;
    // 35.0.0 is already provisioned locally (and this network cannot reach
    // dl.google.com for a download anyway).
    buildToolsVersion = "35.0.0"

    defaultConfig {
        applicationId = "dev.sshrelay"
        // Android 15+ as requested.
        minSdk = 35
        targetSdk = 35
        versionCode = 2
        versionName = "0.1.1"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // The instrumented end-to-end test reaches the host-side test SSH server
        // through the emulator's host alias 10.0.2.2 (WSL's loopback).
        buildConfigField("String", "E2E_SSH_HOST", "\"10.0.2.2\"")
        buildConfigField("int", "E2E_SSH_PORT", "2222")
        buildConfigField("String", "E2E_SSH_USER", "\"tester\"")
        buildConfigField("String", "E2E_SSH_PASSWORD", "\"testpass\"")
    }

    signingConfigs {
        if (releaseSigningConfigured) {
            create("release") {
                // Paths in keystore.properties are relative to the repository root.
                storeFile = rootProject.file(keystoreProperties.getProperty("storeFile"))
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
                // APK Signature Scheme v3 rather than AGP's v2 default. Measured
                // behaviour: AGP emits one or the other, not both -- with this flag
                // unset the APK verifies as v2-only, with it set as v3-only. v3 is the
                // better of the two (it is what lets the signing key be rotated later)
                // and every device this app supports runs Android 15, well past the
                // Android 9 that introduced v3.
                enableV3Signing = true
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            if (releaseSigningConfigured) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        resources.excludes += setOf("/META-INF/{AL2.0,LGPL2.1}", "/META-INF/DEPENDENCIES")
    }

    lint {
        abortOnError = false
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

// Only worth saying when a release artifact is actually being built.
if (!releaseSigningConfigured &&
    gradle.startParameter.taskNames.any { it.contains("Release", ignoreCase = true) }
) {
    logger.lifecycle(
        """
        |
        |keystore.properties not found: the release APK/AAB will be UNSIGNED and cannot
        |be installed. Create a signing key once with:
        |
        |    scripts/make-keystore.sh
        |
        """.trimMargin()
    )
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.kotlinx.coroutines.android)

    implementation(project(":core-forward"))
    implementation(project(":core-ssh"))

    debugImplementation(libs.androidx.compose.ui.tooling)

    testImplementation(libs.junit)

    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.kotlinx.coroutines.test)
}
