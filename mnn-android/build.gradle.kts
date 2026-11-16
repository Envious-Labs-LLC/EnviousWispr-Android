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

// MNN fixes this app carries on top of the pinned submodule (#531): each patch is applied once, before CMake
// configures, and a patch that is already applied is recognised and left alone. A patch that neither applies nor is
// already applied fails the build rather than building an engine without the fix.
val mnnSource = rootProject.file("third_party/MNN")
// The patches this build must carry, by name: a missing or extra file fails the build instead of shrinking the list.
val requiredMnnPatches = listOf("0001-close-prefix-cache-file-handles.patch")
val applyMnnPatches by tasks.registering {
    val patches = requiredMnnPatches.map { file("patches/$it") }
    inputs.files(patches)
    doLast {
        val present = fileTree("patches") { include("*.patch") }.map { it.name }.sorted()
        if (present != requiredMnnPatches.sorted() || patches.any { !it.isFile }) {
            throw GradleException("mnn-android/patches must hold exactly $requiredMnnPatches, found $present")
        }
        patches.forEach { patch ->
            fun git(vararg args: String) = ProcessBuilder(listOf("git", "-C", mnnSource.path) + args).redirectErrorStream(true).start().waitFor()
            when {
                git("apply", "--reverse", "--check", patch.path) == 0 -> logger.info("MNN patch already applied: ${patch.name}")
                git("apply", patch.path) == 0 -> logger.lifecycle("Applied MNN patch ${patch.name}")
                else -> throw GradleException("MNN patch ${patch.name} neither applies nor is already applied to ${mnnSource.path}")
            }
        }
    }
}
tasks.configureEach {
    if (name.startsWith("configureCMake") || name.startsWith("buildCMake")) dependsOn(applyMnnPatches)
}
