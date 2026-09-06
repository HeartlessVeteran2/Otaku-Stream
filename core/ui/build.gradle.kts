plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.otakustream.core.ui"
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
}

dependencies {
    // Reads the dominant colours out of a poster. Small (~50 KB) and worth it over hand-rolling:
    // the colour quantiser and the swatch-population bookkeeping are the fiddly parts, and this
    // one is well-tested. The part that decides whether a colour is *usable* is ours, in
    // AccentMath.kt, where it can be unit-tested without a device.
    implementation(libs.androidx.palette)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.extended)
    implementation(libs.coil.compose)

    // AccentMath is pure arithmetic on ARGB ints, so its tests need nothing but the JVM.
    testImplementation(libs.junit)
}
