import java.util.Properties

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.hilt.android)
    alias(libs.plugins.kotlin.kapt)
}

// AniList sign-in needs a client id registered by whoever builds the app (see
// docs/anilist-setup.md). It is a public OAuth client id, not a secret — but it's still kept out of
// git so forks don't ship someone else's client. Resolution order: local.properties, then a Gradle
// property (-PanilistClientId=...), then the ANILIST_CLIENT_ID env var (for CI). Absent, the app
// builds fine and Settings explains that sign-in isn't configured in this build.
val anilistClientId: String = run {
    val local = rootProject.file("local.properties")
    val fromLocal = if (local.exists()) {
        Properties().apply { local.inputStream().use(::load) }.getProperty("anilistClientId")
    } else {
        null
    }
    fromLocal
        ?: providers.gradleProperty("anilistClientId").orNull
        ?: System.getenv("ANILIST_CLIENT_ID")
        ?: ""
}

android {
    namespace = "com.otakustream.feature.tracking"
    compileSdk = 35

    defaultConfig {
        minSdk = 24
        buildConfigField("String", "ANILIST_CLIENT_ID", "\"$anilistClientId\"")
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
        buildConfig = true
    }

    testOptions {
        unitTests {
            // Robolectric needs the merged manifest and resources to build its simulated app.
            isIncludeAndroidResources = true
        }
    }
}

dependencies {
    api(project(":core:database"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    // For the @AccountHttpClient qualifier: this module talks to the user's own account, so it
    // asks for the client that does not share a cookie jar with installed sources.
    implementation(project(":core:network"))
    implementation(libs.okhttp)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.material3)

    implementation(libs.hilt.android)
    implementation(libs.hilt.navigation.compose)
    kapt(libs.hilt.compiler)

    testImplementation(libs.junit)
    // Real org.json for JVM unit tests (the android.jar stub throws "not mocked").
    testImplementation(libs.json)

    // AniListAuthState persists its OAuth nonce in SharedPreferences, which the stub android.jar
    // cannot provide.
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
}
