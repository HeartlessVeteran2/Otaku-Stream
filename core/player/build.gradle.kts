plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.hilt.android)
    alias(libs.plugins.kotlin.kapt)
}

android {
    namespace = "com.otakustream.core.player"
    compileSdk = 35

    defaultConfig {
        minSdk = 24
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
    }

    testOptions {
        unitTests {
            // Robolectric needs the merged manifest and resources to build its simulated app.
            isIncludeAndroidResources = true
        }
    }
}

dependencies {
    implementation(project(":core:database"))
    implementation(project(":core:sources-api"))
    implementation(project(":core:download"))
    implementation(project(":core:torrent"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.extended)

    implementation(libs.media3.exoplayer)
    implementation(libs.media3.exoplayer.hls)
    implementation(libs.media3.exoplayer.dash)
    implementation(libs.media3.exoplayer.rtsp)
    implementation(libs.media3.ui)
    implementation(libs.media3.session)
    implementation(libs.media3.common)
    implementation(libs.media3.cast)
    implementation(libs.play.services.cast.framework)
    implementation(libs.androidx.mediarouter)
    // Cast's MediaRouteButton dialogs require an AppCompat-descended theme; the app theme isn't
    // AppCompat, so the button's context is wrapped in an AppCompat theme at the call site.
    implementation(libs.androidx.appcompat)

    implementation(libs.hilt.android)
    implementation(libs.hilt.navigation.compose)
    kapt(libs.hilt.compiler)

    // This module had no tests at all, which is how a seek clamp that sent every seek to 0:00
    // while the duration was unknown survived. The parts of playback that are pure arithmetic now
    // live in PlaybackRules.kt so they can be exercised on the JVM runners CI already has.
    testImplementation(libs.junit)
    // Robolectric for the settings stores. They are the other half of "playback works": the values
    // that decide how a video starts. Their bugs are all about *when* a value is available — a load
    // that lands after a tap, a read taken before the load — which is exactly what a fake in-memory
    // map cannot reproduce and a real SharedPreferences on a real dispatcher can.
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.kotlinx.coroutines.test)
}
