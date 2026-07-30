plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.hilt.android)
    alias(libs.plugins.kotlin.kapt)
}

android {
    namespace = "com.otakustream.core.database"
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

    testOptions {
        unitTests {
            // Robolectric needs the merged manifest and resources to build its simulated app.
            isIncludeAndroidResources = true
        }
    }

    // Room's MigrationTestHelper reads the exported schema JSONs from the test APK's assets, so the
    // committed schemas have to be on the unit-test asset path. Pointing at the same directory kapt
    // exports to means there is one copy, and a schema can never be stale relative to the migration
    // being tested — the alternative, copying them into a second location, is exactly how a migration
    // test ends up validating last release's schema.
    sourceSets.getByName("test") {
        assets.srcDir("$projectDir/schemas")
    }
}

kapt {
    arguments {
        // Export schema JSONs (committed) so future migrations are authored against a baseline.
        arg("room.schemaLocation", "$projectDir/schemas")
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.security.crypto)

    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    kapt(libs.room.compiler)

    implementation(libs.hilt.android)
    kapt(libs.hilt.compiler)

    testImplementation(libs.junit)
    // Real org.json for JVM unit tests (the android.jar stub throws "not mocked").
    testImplementation(libs.json)

    // The executable half of the migration story. MigrationSchemaGuardTest diffs the exported schema
    // JSONs, which proves the *shape* a migration produces; these run the migration against a real
    // SQLite database on the JVM, which is the only way to prove the rows survive it.
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.room.testing)
    testImplementation(libs.kotlinx.coroutines.test)
}
