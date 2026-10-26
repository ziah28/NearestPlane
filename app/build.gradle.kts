import java.io.File

import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    // AGP 9 compiles Kotlin itself; applying org.jetbrains.kotlin.android too
    // is an error there.
    id("org.jetbrains.kotlin.plugin.compose")
}

/**
 * A stable signing key, supplied by CI from repository secrets.
 *
 * Without one, every build is signed with whatever debug keystore happens to
 * exist on the machine — and a CI runner is a fresh machine each time, so each
 * build got a *different* key. Android refuses to install an update whose
 * signature doesn't match what's already there, which surfaces on the phone as
 * a bare "App not installed" with no explanation.
 *
 * Absent (a fork without the secrets, or a local build) this falls back to the
 * debug key, which is at least stable per-machine.
 */
val signingKeystore: File? = System.getenv("SIGNING_KEYSTORE_PATH")
    ?.takeIf { it.isNotBlank() }
    ?.let { File(it) }
    ?.takeIf { it.exists() }

android {
    namespace = "com.connor.nearestplane"
    // Compiling against 37.2 is what the current Compose libraries require.
    // It only makes newer APIs available; targetSdk below is what opts the app
    // into new runtime behaviour, and that moves separately.
    compileSdk {
        version = release(37) {
            minorApiLevel = 2
        }
    }

    defaultConfig {
        applicationId = "uk.co.zhaq.overhead"
        minSdk = 26
        targetSdk = 36
        versionCode = 10
        versionName = "0.10"
    }

    signingConfigs {
        if (signingKeystore != null) {
            create("stable") {
                storeFile = signingKeystore
                storePassword = System.getenv("SIGNING_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("SIGNING_KEY_ALIAS") ?: "nearestplane"
                keyPassword = System.getenv("SIGNING_KEYSTORE_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig =
                if (signingKeystore != null) signingConfigs.getByName("stable")
                else signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }


    buildFeatures {
        compose = true
        // VERSION_NAME, so the app can tell whether a release is newer than it.
        buildConfig = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.19.1")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation(platform("androidx.compose:compose-bom:2026.09.00"))
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")

    // Home-screen widget
    implementation("androidx.glance:glance-appwidget:1.2.0")
    implementation("androidx.glance:glance-material3:1.2.0")

    // Background refresh
    implementation("androidx.work:work-runtime-ktx:2.12.0")

    // App-wide settings shared between the app and both widgets
    implementation("androidx.datastore:datastore-preferences:1.2.1")

    // GPS
    implementation("com.google.android.gms:play-services-location:21.4.0")

    // Unit tests run on the JVM, where Android's org.json is a stub that throws;
    // the real library stands in for it so the feed parsing can be tested.
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20260814")
}
