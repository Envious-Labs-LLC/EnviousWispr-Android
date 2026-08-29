plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.envi.wispr.benchmark"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.envi.wispr.benchmark"
        minSdk = 30
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
        buildConfigField("int", "BENCHMARK_CONTEXT_SIZE", "2048")
        buildConfigField("int", "BENCHMARK_THREAD_COUNT", "4")
        buildConfigField("int", "BENCHMARK_BATCH_SIZE", "512")
        buildConfigField("int", "BENCHMARK_UBATCH_SIZE", "512")
        buildConfigField("String", "BENCHMARK_SPEC_TYPE", "\"\"")
        buildConfigField("int", "BENCHMARK_SPEC_MAX", "0")
    }

    flavorDimensions += "engine"
    productFlavors {
        create("cpu") {
            dimension = "engine"
            buildConfigField("String", "BENCHMARK_ENGINE", "\"cpu\"")
            buildConfigField("String", "BENCHMARK_COMPUTE_UNIT", "\"cpu\"")
        }
        create("geniexGpu") {
            dimension = "engine"
            buildConfigField("String", "BENCHMARK_ENGINE", "\"geniex_gpu\"")
            buildConfigField("String", "BENCHMARK_COMPUTE_UNIT", "\"gpu\"")
        }
        create("geniexNpu") {
            dimension = "engine"
            buildConfigField("String", "BENCHMARK_ENGINE", "\"geniex_npu\"")
            buildConfigField("String", "BENCHMARK_COMPUTE_UNIT", "\"npu\"")
        }
        create("geniexQairt") {
            dimension = "engine"
            buildConfigField("String", "BENCHMARK_ENGINE", "\"geniex_qairt\"")
            buildConfigField("String", "BENCHMARK_COMPUTE_UNIT", "\"npu\"")
        }
        create("geniexNpuT6") {
            dimension = "engine"
            buildConfigField("String", "BENCHMARK_ENGINE", "\"geniex_npu_t6\"")
            buildConfigField("String", "BENCHMARK_COMPUTE_UNIT", "\"npu\"")
            buildConfigField("int", "BENCHMARK_THREAD_COUNT", "6")
        }
        create("geniexNpuT2") {
            dimension = "engine"
            buildConfigField("String", "BENCHMARK_ENGINE", "\"geniex_npu_t2\"")
            buildConfigField("String", "BENCHMARK_COMPUTE_UNIT", "\"npu\"")
            buildConfigField("int", "BENCHMARK_THREAD_COUNT", "2")
        }
        create("geniexNpuT6B2048U1024") {
            dimension = "engine"
            buildConfigField("String", "BENCHMARK_ENGINE", "\"geniex_npu_t6_b2048_u1024\"")
            buildConfigField("String", "BENCHMARK_COMPUTE_UNIT", "\"npu\"")
            buildConfigField("int", "BENCHMARK_THREAD_COUNT", "6")
            buildConfigField("int", "BENCHMARK_BATCH_SIZE", "2048")
            buildConfigField("int", "BENCHMARK_UBATCH_SIZE", "1024")
        }
        create("geniexNpuT6B2048U1024C1024") {
            dimension = "engine"
            buildConfigField("String", "BENCHMARK_ENGINE", "\"geniex_npu_t6_b2048_u1024_c1024\"")
            buildConfigField("String", "BENCHMARK_COMPUTE_UNIT", "\"npu\"")
            buildConfigField("int", "BENCHMARK_CONTEXT_SIZE", "1024")
            buildConfigField("int", "BENCHMARK_THREAD_COUNT", "6")
            buildConfigField("int", "BENCHMARK_BATCH_SIZE", "2048")
            buildConfigField("int", "BENCHMARK_UBATCH_SIZE", "1024")
        }
        create("geniexNpuSpec") {
            dimension = "engine"
            buildConfigField("String", "BENCHMARK_ENGINE", "\"geniex_npu_spec\"")
            buildConfigField("String", "BENCHMARK_COMPUTE_UNIT", "\"npu\"")
            buildConfigField("int", "BENCHMARK_THREAD_COUNT", "6")
            buildConfigField("int", "BENCHMARK_BATCH_SIZE", "2048")
            buildConfigField("int", "BENCHMARK_UBATCH_SIZE", "1024")
            buildConfigField("String", "BENCHMARK_SPEC_TYPE", "\"ngram-simple\"")
            buildConfigField("int", "BENCHMARK_SPEC_MAX", "64")
        }
        create("geniexHybrid") {
            dimension = "engine"
            buildConfigField("String", "BENCHMARK_ENGINE", "\"geniex_hybrid\"")
            buildConfigField("String", "BENCHMARK_COMPUTE_UNIT", "\"hybrid\"")
            buildConfigField("int", "BENCHMARK_THREAD_COUNT", "6")
            buildConfigField("int", "BENCHMARK_BATCH_SIZE", "2048")
            buildConfigField("int", "BENCHMARK_UBATCH_SIZE", "1024")
        }
    }

    sourceSets {
        getByName("geniexGpu").java.srcDir("src/geniex/java")
        getByName("geniexNpu").java.srcDir("src/geniex/java")
        getByName("geniexQairt").java.srcDir("src/geniexQairt/java")
        getByName("geniexNpuT6").java.srcDir("src/geniex/java")
        getByName("geniexNpuT2").java.srcDir("src/geniex/java")
        getByName("geniexNpuT6B2048U1024").java.srcDir("src/geniex/java")
        getByName("geniexNpuT6B2048U1024C1024").java.srcDir("src/geniex/java")
        getByName("geniexNpuSpec").java.srcDir("src/geniex/java")
        getByName("geniexHybrid").java.srcDir("src/geniex/java")
    }

    buildFeatures {
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
}

dependencies {
    implementation("androidx.core:core-ktx:1.16.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    "cpuImplementation"(project(":llama-android"))
    "geniexGpuImplementation"("com.qualcomm.qti:geniex-android:0.4.0")
    "geniexNpuImplementation"("com.qualcomm.qti:geniex-android:0.4.0")
    "geniexQairtImplementation"("com.qualcomm.qti:geniex-android:0.4.0")
    "geniexNpuT6Implementation"("com.qualcomm.qti:geniex-android:0.4.0")
    "geniexNpuT2Implementation"("com.qualcomm.qti:geniex-android:0.4.0")
    "geniexNpuT6B2048U1024Implementation"("com.qualcomm.qti:geniex-android:0.4.0")
    "geniexNpuT6B2048U1024C1024Implementation"("com.qualcomm.qti:geniex-android:0.4.0")
    "geniexNpuSpecImplementation"("com.qualcomm.qti:geniex-android:0.4.0")
    "geniexHybridImplementation"("com.qualcomm.qti:geniex-android:0.4.0")
}
