plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.hilt.android)
    alias(libs.plugins.kotlin.kapt)
}

android {
    namespace = "com.otakustream.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.otakustream.app"
        minSdk = 24
        targetSdk = 35
        // Version scheme: versionName is semver; versionCode is MAJOR*10000 + MINOR*100 + PATCH,
        // so 1.0.0 -> 10000 and it always increases monotonically across releases. Tag a release
        // as v<versionName> (e.g. v1.0.0) to publish it — see .github/workflows/release.yml.
        versionCode = 10000
        versionName = "1.0.0"
    }

    signingConfigs {
        create("release") {
            // Sourced from Gradle properties or env vars so release signing keys never live in the
            // repo. When unset (local/CI without secrets), the release build falls back to the debug
            // key below so assembleRelease still produces an installable, signed APK.
            val storeFilePath = providers.gradleProperty("RELEASE_STORE_FILE").orNull
                ?: System.getenv("RELEASE_STORE_FILE")
            if (storeFilePath != null) {
                storeFile = file(storeFilePath)
                storePassword = providers.gradleProperty("RELEASE_STORE_PASSWORD").orNull
                    ?: System.getenv("RELEASE_STORE_PASSWORD")
                keyAlias = providers.gradleProperty("RELEASE_KEY_ALIAS").orNull
                    ?: System.getenv("RELEASE_KEY_ALIAS")
                keyPassword = providers.gradleProperty("RELEASE_KEY_PASSWORD").orNull
                    ?: System.getenv("RELEASE_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            // No minification, and the reason is not the one this used to give.
            //
            // It said extensions call app code by name via reflection. They do not — there is no
            // reflection anywhere in this codebase. Both engines resolve names inside their own
            // interpreter's scope, against JS objects the script itself defined; R8 renames Kotlin
            // symbols, which are not involved. Anyone re-evaluating this setting on the strength of
            // that sentence would have been reasoning from a premise that does not hold.
            //
            // The setting still stands, on the exposure that is real: Rhino's interpreter, Hilt and
            // Room all reach for types by name at runtime, and there is not a single keep rule in
            // the repository to protect them. Turning minification on means writing and testing
            // those first. The payoff is a smaller APK, which matters least here — the app is not
            // distributed through the Play Store — and obfuscation is not a security boundary, so
            // nothing in the threat model depends on it either way.
            isMinifyEnabled = false
            val releaseSigning = signingConfigs.getByName("release")
            signingConfig = if (releaseSigning.storeFile != null) {
                releaseSigning
            } else {
                signingConfigs.getByName("debug")
            }
        }
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
            // Robolectric needs the merged manifest and resources to build its simulated app.
            // AppearancePrefs reads and writes a real SharedPreferences file, and without this the
            // test fails looking for an application rather than failing an assertion.
            isIncludeAndroidResources = true
        }
    }
}

dependencies {
    // For the @IoDispatcher qualifier that DispatcherModule satisfies.
    implementation(project(":core:common"))
    implementation(project(":core:player"))
    implementation(project(":core:network"))
    implementation(project(":core:download"))
    implementation(project(":core:ui"))
    implementation(project(":core:sources-api"))
    implementation(project(":core:sources-mangayomi"))
    implementation(project(":core:torrent"))
    implementation(project(":feature:sources"))
    implementation(project(":feature:library"))
    implementation(project(":feature:tracking"))
    debugImplementation(project(":sources:example"))
    implementation(libs.androidx.material.icons.extended)
    implementation(libs.coil.compose)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.core.splashscreen)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.navigation.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    debugImplementation(libs.androidx.ui.tooling)

    implementation(libs.hilt.android)
    implementation(libs.hilt.navigation.compose)
    kapt(libs.hilt.compiler)

    // The theme's colour tables are plain Kotlin values, so ThemeTest asserts role coverage and
    // contrast on the JVM with no device and no Robolectric.
    testImplementation(libs.junit)

    // AppearancePrefs is the exception: it is the one piece of app-module logic that talks to
    // SharedPreferences, and the stub android.jar's version throws "not mocked" on every call.
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.kotlinx.coroutines.test)
}
