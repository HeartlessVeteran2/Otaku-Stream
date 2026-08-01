plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.otakustream.core.common"
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
    // api, not implementation: InFlightCache takes a CoroutineScope in its public constructor, so
    // coroutines is part of this module's ABI, not an internal detail.
    //
    // -core, not -android: nothing here touches Dispatchers.Main or the Android dispatcher
    // integration, and exposing -android would push it onto every consumer of this module.
    api(libs.kotlinx.coroutines.core)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    // Real org.json for JVM unit tests — the stub android.jar throws "not mocked", which would make
    // the JSON helpers in this module untestable.
    testImplementation(libs.json)
}
