plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.hilt.android)
    alias(libs.plugins.kotlin.kapt)
}

android {
    namespace = "com.otakustream.feature.library"
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
    // IoDispatcher, so the ViewModel's flowOn is substitutable in tests.
    implementation(project(":core:common"))

    implementation(project(":core:ui"))
    implementation(project(":core:database"))
    implementation(project(":core:download"))
    implementation(project(":core:sources-api"))
    implementation(project(":feature:tracking"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.extended)
    implementation(libs.coil.compose)
    implementation(libs.coil.video)

    implementation(libs.hilt.android)
    implementation(libs.hilt.navigation.compose)
    kapt(libs.hilt.compiler)

    // This module had no tests, which is how the same download-bookkeeping bug shipped three times:
    // a failure message keyed wrongly, then a fix that cleared the wrong entry, then a fix that
    // could throw. None of it was testable while two of LibraryViewModel's four collaborators were
    // concrete classes built on Media3 and the network — hence the interfaces they are now.
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)

    // Compose on the JVM, via Robolectric — the same four lines core/ui uses, comment included,
    // because the only way to exercise Compose used to be an emulator this project's CI does not
    // have. LibraryScreen decides what a user sees when a list comes back empty, and getting that
    // wrong shows someone "nothing saved yet" over a library that is full.
    testImplementation(platform(libs.androidx.compose.bom))
    testImplementation(libs.androidx.ui.test.junit4)
    // testImplementation, not the documented debugImplementation: this artifact exists to
    // contribute a manifest entry, and on debugImplementation a library module hands it to
    // consumers rather than to its own unit tests. The unit-test APK merges test dependencies and
    // Robolectric reads it from there — without it the tests fail on a missing activity rather
    // than on an assertion.
    testImplementation(libs.androidx.ui.test.manifest)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
}
