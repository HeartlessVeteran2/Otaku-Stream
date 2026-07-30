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
            // Robolectric needs the merged manifest and resources to build its simulated app.
            //
            // Note what is deliberately *not* here: isReturnDefaultValues. That would make every
            // unmocked Android call in the module quietly return zero, which is fine for the one log
            // line TorrentCacheSweeper emits and dangerous for everything else — a test could exercise
            // a path that returns null on a real device and still pass. TorrentCacheSweeperTest runs
            // under Robolectric instead, which supplies a real android.util.Log, so the strict stub
            // android.jar stays in force for the module's pure-logic tests.
            isIncludeAndroidResources = true
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
    // Real Android framework implementations on the JVM runners CI already has — no emulator. Used
    // by TorrentCacheSweeperTest so the class that deletes the user's files can be tested without
    // relaxing the Android stubs for the whole module.
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
}
