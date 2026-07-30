plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.hilt.android)
    alias(libs.plugins.kotlin.kapt)
}

android {
    namespace = "com.otakustream.core.torrent"
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

    testOptions {
        unitTests {
            // TorrentCacheSweeper is ordinary java.io code that only touches Android for android.util.Log.
            // Without this the stub android.jar throws "not mocked" on the first log line, which would
            // leave the one class that actually deletes the user's files untestable on a JVM runner.
            isReturnDefaultValues = true
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.coroutines.android)

    // libtorrent4j splits its Java API from its native binaries, one artifact per ABI. Depending on
    // the arm64 artifact alone is what keeps the other three architectures out of the APK — no
    // abiFilters needed, and deliberately not used: an app-level abiFilters would also strip
    // QuickJS's armeabi-v7a/x86/x86_64 libraries and break Mangayomi extensions on those devices.
    // The tradeoff this buys: ~16 MB instead of ~64 MB, at the cost of no torrent support on 32-bit
    // devices, where TorrentEngine.isAvailable reports the feature unavailable rather than crashing.
    implementation(libs.libtorrent4j)
    implementation(libs.libtorrent4j.android.arm64)

    implementation(libs.hilt.android)
    kapt(libs.hilt.compiler)

    testImplementation(libs.junit)
}
