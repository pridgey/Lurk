plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "app.lurk"
    compileSdk = 35

    defaultConfig {
        applicationId = "app.lurk"
        minSdk = 26
        targetSdk = 35
        // CI stamps each build with its run number (the N in Lurk-N.apk), which the in-app
        // updater compares against the latest release. Local builds are build 1.
        val build = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 1
        versionCode = build
        versionName = "build $build"
    }

    // Permanent release key, supplied by CI (see .github/workflows/build-apk.yml).
    val releaseKeystore = System.getenv("LURK_KEYSTORE_PATH")?.let { file(it) }?.takeIf { it.exists() }
    signingConfigs {
        if (releaseKeystore != null) {
            create("release") {
                storeFile = releaseKeystore
                storePassword = System.getenv("LURK_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("LURK_KEY_ALIAS")
                keyPassword = System.getenv("LURK_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // Without the release key (e.g. a local build), fall back to the debug key so it still installs.
            signingConfig = if (releaseKeystore != null) signingConfigs.getByName("release")
                else signingConfigs.getByName("debug")
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
        unitTests.isIncludeAndroidResources = true
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.webkit:webkit:1.12.1")
    implementation("androidx.browser:browser:1.8.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("io.coil-kt:coil-compose:2.7.0")
    implementation("io.coil-kt:coil-gif:2.7.0")
    // Lemmy sends Markdown; this renders it to the same HTML the Reddit screens show.
    implementation("org.commonmark:commonmark:0.24.0")
    implementation("org.commonmark:commonmark-ext-gfm-strikethrough:0.24.0")
    implementation("org.commonmark:commonmark-ext-gfm-tables:0.24.0")
    implementation("org.commonmark:commonmark-ext-autolink:0.24.0")
    // Video playback (Reddit serves HLS with sound, plus a silent MP4 fallback).
    implementation("androidx.media3:media3-exoplayer:1.4.1")
    implementation("androidx.media3:media3-exoplayer-hls:1.4.1")
    implementation("androidx.media3:media3-ui:1.4.1")

    testImplementation("junit:junit:4.13.2")
    // Android's built-in org.json is a stub in local unit tests; use the real one.
    testImplementation("org.json:json:20240303")
    // Launch smoke test: runs the real activity on the JVM.
    testImplementation("org.robolectric:robolectric:4.14.1")
    testImplementation("androidx.test.ext:junit:1.2.1")
    testImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
