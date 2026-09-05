plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.hilt.android)
    alias(libs.plugins.kotlin.kapt)
}

android {
    namespace = "com.otakustream.core.sources.stremio"
    compileSdk = 35

    defaultConfig {
        minSdk = 24
    }

    // The shipped res/raw is the unit tests' resource root too, so BundledCommunityAddonsTest reads
    // the very file the app loads rather than a copy of it. A copy would pass while the real
    // catalogue drifted — and drifting is precisely what a checked-in harvest does.
    sourceSets {
        getByName("test") {
            resources.srcDir("src/main/res/raw")
        }
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
    implementation(project(":core:torrent"))
    implementation(project(":core:common"))

    implementation(libs.androidx.core.ktx)
    // For the @AccountHttpClient qualifier: this module talks to the user's own account, so it
    // asks for the client that does not share a cookie jar with installed sources.
    implementation(project(":core:network"))
    implementation(libs.okhttp)

    implementation(libs.hilt.android)
    kapt(libs.hilt.compiler)

    testImplementation(libs.junit)
    // Real org.json for JVM unit tests (the android.jar stub throws "not mocked").
    testImplementation(libs.json)
}
