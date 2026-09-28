plugins { kotlin("jvm") version "2.0.21"; application }
repositories { mavenCentral() }
dependencies { implementation("com.microsoft.onnxruntime:onnxruntime:1.30.0") }
kotlin { jvmToolchain(21) }
sourceSets { main { kotlin.srcDirs("src") } }
application { mainClass.set("com.envi.wispr.asr.GoldenKt") }
