plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.hilt.android)
    alias(libs.plugins.kotlin.kapt)
}

android {
    namespace = "com.otakustream.core.sources.scripting"
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
    implementation(libs.rhino)
    implementation(libs.okhttp)

    implementation(libs.hilt.android)
    kapt(libs.hilt.compiler)

    testImplementation(libs.junit)
    // Real org.json for JVM unit tests. HttpBridge parses the headers argument with JSONObject, and
    // the stub android.jar throws "not mocked" on it — so without this the httpGet capability could
    // not be exercised end to end at all.
    testImplementation(libs.json)
}
