plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

// EG-1 Micro's engine (#531): Alibaba MNN built from the pinned `third_party/MNN` submodule. S1-mini stays on
// GenieX and `:llama-android`; nothing here is shared with either.
android {
    namespace = "com.envi.wispr.mnn"
    compileSdk = 36
    ndkVersion = "29.0.13113456"

    defaultConfig {
        minSdk = 33
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        ndk {
            abiFilters += "arm64-v8a"
        }

        externalNativeBuild {
            cmake {
                arguments += listOf("-DCMAKE_BUILD_TYPE=Release")
            }
        }
    }

    buildTypes {
        debug {
            externalNativeBuild {
                cmake {
                    // Reference generation for the step-loop equivalence test (#531 chunk 1): debug libraries only.
                    cppFlags += "-DEW_MNN_TEST_SWITCHES"
                }
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    kotlinOptions {
        jvmTarget = "21"
    }
}

dependencies {
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("junit:junit:4.13.2")
}
