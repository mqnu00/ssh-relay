plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

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
        versionCode = 1
        versionName = "0.1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // The instrumented end-to-end test reaches the host-side test SSH server
        // through the emulator's host alias 10.0.2.2 (WSL's loopback).
        buildConfigField("String", "E2E_SSH_HOST", "\"10.0.2.2\"")
        buildConfigField("int", "E2E_SSH_PORT", "2222")
        buildConfigField("String", "E2E_SSH_USER", "\"tester\"")
        buildConfigField("String", "E2E_SSH_PASSWORD", "\"testpass\"")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
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
