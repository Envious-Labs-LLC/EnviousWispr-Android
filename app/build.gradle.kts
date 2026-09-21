plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
}

android {
    namespace = "com.envi.wispr"
    compileSdk = 36
    ndkVersion = "29.0.13113456"

    defaultConfig {
        applicationId = "com.envi.wispr"
        manifestPlaceholders["applicationLabel"] = "@string/app_name"
        // Android 13: the accessibility input-method route needs API 33, and a phone still on 11 or 12
        // in 2026 cannot run the on-device models comfortably (founder decision 2026-09-13, #141).
        minSdk = 33
        targetSdk = 36
        versionCode = providers.gradleProperty("playVersionCode").orNull?.toInt() ?: 3
        versionName = "0.1.0"
        // Telemetry keys (issue #176). Both are public client-side identifiers, not secrets: the PostHog
        // project key and the Sentry DSN are embedded in every client that uses them. They come from
        // gradle properties (`-PtelemetryPostHogKey=... -PtelemetrySentryDsn=...`, set by the Play
        // workflow from repository variables) and default to EMPTY, which turns telemetry off: a local
        // build sends nothing. Compiled in, so removing a variable later changes nothing on installed phones.
        buildConfigField("String", "TELEMETRY_POSTHOG_KEY", "\"${providers.gradleProperty("telemetryPostHogKey").orNull.orEmpty()}\"")
        buildConfigField("String", "TELEMETRY_SENTRY_DSN", "\"${providers.gradleProperty("telemetrySentryDsn").orNull.orEmpty()}\"")
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        ndk {
            abiFilters += "arm64-v8a"
        }
        externalNativeBuild {
            cmake {
                cppFlags += "-std=c++17"
            }
        }
    }

    // Opt-in separate app for fresh onboarding UAT without wiping a Play-signed daily installation.
    buildTypes.getByName("debug") {
        if (providers.gradleProperty("onboardingPreview").orNull == "true") {
            applicationIdSuffix = ".onboardingpreview"
            manifestPlaceholders["applicationLabel"] = "EnviousWispr Preview"
        }
    }

    androidResources {
        // The model must be STORED, not deflated. Measured on this app's own APK: our text asset is
        // Defl:N while ML Kit's model asset is Stored, and the only reason theirs is stored is that they
        // renamed it to .jpg to reach AAPT's built-in never-compress list. .onnx is not on that list.
        noCompress += "onnx"
    }

    buildFeatures {
        compose = true
        aidl = true
        // The drawer footer and the What's New page both name the shipped version, and
        // `ReleaseNotesTest` compares that version against the bundled notes. Generating the constant
        // keeps `versionName` above the one source of that string.
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    kotlinOptions {
        jvmTarget = "21"
    }

    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
    }

    testOptions {
        // The audio owners (#188) subclass `AudioDeviceCallback` and read `AudioDeviceInfo` inside
        // their start paths; `AudioLimbCloseTest` constructs the REAL owners on the JVM and counts what
        // `close` releases, which needs the SDK stubs' constructors to return rather than throw
        // "Stub!". DISCLOSED COST: an unmocked Android call in any JVM test now returns 0/null/false
        // instead of failing, so a JVM test must never assert on a value an Android stub produced.
        unitTests.isReturnDefaultValues = true
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
        }
    }

    sourceSets {
        getByName("androidTest").assets.srcDir("$projectDir/schemas")
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    implementation("com.qualcomm.qti:geniex-android:0.4.0")
    implementation(files("libs/sherpa-onnx.aar"))
    implementation(platform("androidx.compose:compose-bom:2026.02.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.core:core-ktx:1.16.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.10.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.10.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.10.0")
    implementation("androidx.datastore:datastore-preferences:1.2.1")
    implementation("androidx.room:room-runtime:2.8.4")
    implementation("androidx.room:room-ktx:2.8.4")
    implementation("androidx.work:work-runtime-ktx:2.10.1")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.1")
    // On-device language identification, model BUNDLED in the APK. Deliberately not the
    // `play-services-mlkit-language-id` variant, which downloads its model at runtime: stage 1 has no
    // accepted model-acquisition path and dictation must work with no network. Used by
    // `polish/MlKitLanguageDetector.kt` (#107).
    implementation("com.google.mlkit:language-id:17.0.6")
    // Telemetry (issue #176), PINNED: every option the bootstrap sets was read against these exact
    // sources. Sentry runs in all five processes (its own cache dir per process); PostHog in main only.
    // Neither is initialised by a manifest provider: `io.sentry.auto-init` is false and both are started
    // from `ModelBootstrapApplication` through `telemetry/Telemetry.bootstrap`.
    implementation("io.sentry:sentry-android:8.57.0")
    implementation("com.posthog:posthog-android:3.67.0")
    ksp("androidx.room:room-compiler:2.8.4")
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.room:room-testing:2.8.4")
    androidTestImplementation(platform("androidx.compose:compose-bom:2026.02.01"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
}
