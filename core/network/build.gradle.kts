plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.hilt.android)
    alias(libs.plugins.kotlin.kapt)
}

android {
    namespace = "com.otakustream.core.network"
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
    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.coroutines.android)
    // api, not implementation: Call.await() is an extension on okhttp3.Call and Response is its
    // return type, so both are part of this module's ABI rather than an internal detail.
    api(libs.okhttp)
    implementation(libs.okhttp.urlconnection)

    implementation(libs.hilt.android)
    kapt(libs.hilt.compiler)

    testImplementation(libs.junit)
    testImplementation(libs.mockwebserver)
    testImplementation(libs.kotlinx.coroutines.test)
}
