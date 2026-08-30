plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.hilt.android)
    alias(libs.plugins.kotlin.kapt)
}

android {
    namespace = "com.otakustream.core.download"
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
}

dependencies {
    implementation(project(":core:database"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.coroutines.android)

    // No new dependency for any of this. DownloadManager, DownloadService, DownloadHelper and
    // DownloadNotificationHelper all live in media3-exoplayer, and the cache classes come in
    // transitively with it — the same artifacts the player already uses.
    //
    // api rather than implementation: the download cache and Download itself appear in this
    // module's public surface, because :core:player has to read the same cache to play what was
    // downloaded, and the UI renders Download state.
    api(libs.media3.exoplayer)
    api(libs.media3.common)
    implementation(libs.media3.exoplayer.hls)
    implementation(libs.media3.database)
    implementation(libs.media3.datasource)

    implementation(libs.hilt.android)
    kapt(libs.hilt.compiler)

    testImplementation(libs.junit)
}
