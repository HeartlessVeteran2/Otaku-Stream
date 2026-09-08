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

    // For @Qualifier on IoDispatcher, and nothing else — no Hilt gradle plugin, no kapt. This
    // module declares no @Module: one here would need the Hilt processor to emit the aggregation
    // metadata the app reads, and that processor is the cost this file's other choices are about
    // avoiding. The binding that satisfies the qualifier lives in the app module instead.
    //
    // JSR-330 itself, not hilt-android, which is where this started. @Qualifier is a javax.inject
    // annotation that Hilt happens to re-export; depending on Hilt for it put the entire Hilt
    // Android runtime — Dagger, the component machinery, the Android integration — on the compile
    // and test classpath of a module whose whole point above is that it does not use any of it.
    // This artifact is one jar of five annotations and one interface.
    //
    // api, by the same test as coroutines above: @Qualifier is RUNTIME-retained and sits on the
    // public IoDispatcher, so it is not an internal detail — anything reading that annotation, the
    // Hilt processor included, has to resolve it. It happens to work as `implementation` only
    // because every module that uses the qualifier also depends on Hilt directly and picks it up
    // that way, which is a coincidence of the current graph rather than something this file states.
    api(libs.javax.inject)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    // Real org.json for JVM unit tests — the stub android.jar throws "not mocked", which would make
    // the JSON helpers in this module untestable.
    testImplementation(libs.json)
}
