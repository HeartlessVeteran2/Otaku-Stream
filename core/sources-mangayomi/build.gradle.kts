plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.hilt.android)
    alias(libs.plugins.kotlin.kapt)
}

android {
    namespace = "com.otakustream.core.sources.mangayomi"
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
    api(project(":core:sources-api"))
    api(project(":core:database"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.coroutines.android)
    // QuickJS (via the Harlon Wang wrapper) is the JS engine for Mangayomi/AnymeX-format
    // extensions — unlike Rhino it parses modern JS (classes, async/await) that real
    // extensions rely on. jsoup backs the extensions' HTML/DOM parsing bridge.
    implementation(libs.quickjs.android)
    implementation(libs.jsoup)
    implementation(libs.okhttp)

    implementation(libs.hilt.android)
    kapt(libs.hilt.compiler)
}
