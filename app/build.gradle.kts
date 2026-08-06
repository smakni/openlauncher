import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

/**
 * Build timestamp, carried in both the version name and the copied APK's file
 * name. Several test builds end up side by side on the way to a head unit, and
 * without this they are the same size, the same package and the same version —
 * with no way to tell which one is actually installed.
 */
val buildStamp: String = SimpleDateFormat("yyyyMMdd-HHmm", Locale.US)
    .apply { timeZone = TimeZone.getDefault() }
    .format(Date())

val baseVersionName = "0.0.5"

android {
    namespace  = "com.openlauncher.app"
    compileSdk {
        version = release(36) { minorApiLevel = 1 }
    }

    defaultConfig {
        applicationId  = "com.openlauncher.app"
        minSdk         = 21
        targetSdk      = 36
        versionCode    = 6
        versionName    = "$baseVersionName-$buildStamp"
    }

    buildTypes {
        debug {
            // Default signing config for normal device testing (restores app visibility)
        }
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }
}

/**
 * Drops a stamped copy of the debug APK in apk/ after every build.
 *
 * The Gradle output keeps its fixed app-debug.apk name, so this is what makes
 * successive builds distinguishable once they leave the machine. Copying rather
 * than renaming the variant output avoids the internal AGP APIs that renaming
 * requires, which change between plugin versions.
 */
val copyDebugApk by tasks.registering(Copy::class) {
    from(layout.buildDirectory.file("outputs/apk/debug/app-debug.apk"))
    into(rootProject.layout.projectDirectory.dir("apk"))
    rename { "openlauncher-$buildStamp.apk" }
}

tasks.matching { it.name == "assembleDebug" }.configureEach {
    finalizedBy(copyDebugApk)
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.09.00")
    implementation(composeBom)

    // Core
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")

    // Compose
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.foundation:foundation")

    // DataStore
    implementation("androidx.datastore:datastore-preferences:1.1.7")

    // Network — weather
    implementation("com.squareup.retrofit2:retrofit:2.12.0")
    implementation("com.squareup.retrofit2:converter-gson:2.12.0")

    // Image loading
    implementation("io.coil-kt:coil-compose:2.7.0")

    // Map rendering. OpenStreetMap tiles, which are meant to be consumed this
    // way — unlike the Google endpoints an earlier map widget pulled from, which
    // is what had it reverted upstream.
    implementation("org.osmdroid:osmdroid-android:6.1.20")

    // Permissions
    implementation("com.google.accompanist:accompanist-permissions:0.37.3")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")

    // JSON serialization
    implementation("com.google.code.gson:gson:2.13.1")

    debugImplementation("androidx.compose.ui:ui-tooling")
}
