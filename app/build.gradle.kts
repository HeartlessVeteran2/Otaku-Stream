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
            // No minification. The app runs untrusted JS source extensions that call app code by
            // name via reflection, which R8 would rename or strip; and it isn't distributed through
            // the Play Store, so the smaller-APK payoff isn't worth that risk.
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
}

dependencies {
    implementation(project(":core:player"))
    implementation(project(":core:network"))
    implementation(project(":core:download"))
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
}
