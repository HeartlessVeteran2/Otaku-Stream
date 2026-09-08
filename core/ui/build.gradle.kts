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

    testOptions {
        unitTests {
            // Robolectric needs the merged manifest and resources to build its simulated app —
            // without this a Compose test fails looking for a theme rather than failing an
            // assertion, which is the confusing kind of red.
            isIncludeAndroidResources = true
        }
    }
}

dependencies {
    // InFlightCache — the shared in-flight/TTL cache the accent extractor is built on. Its own
    // header calls duplicating it a standing hazard, and this module had duplicated it.
    implementation(project(":core:common"))

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

    // Compose UI tests on the JVM, via Robolectric. The shared components in this module are the
    // ones whose contracts every feature depends on — an EmptyState that silently stops drawing its
    // action button breaks a first-run flow in four screens at once — and nothing here was covered
    // because the only way to exercise Compose used to be an emulator this project's CI doesn't
    // have.
    testImplementation(platform(libs.androidx.compose.bom))
    testImplementation(libs.androidx.ui.test.junit4)
    // testImplementation, not the documented debugImplementation: this artifact exists to
    // contribute a manifest entry, and on debugImplementation a library module hands it to
    // every consumer's debug APK. The unit-test classpath is the only place it is wanted, and
    // Robolectric reads it from there — dropping it entirely makes all four tests fail on a
    // missing activity rather than on an assertion, so it is genuinely load-bearing.
    testImplementation(libs.androidx.ui.test.manifest)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
}
