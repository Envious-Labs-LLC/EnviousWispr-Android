package com.envi.wispr.models

internal data class ModelFile(
    val name: String,
    val expectedBytes: Long,
    val sha256: String?,
    /** Our own host first. */
    val sourceUrl: String,
    /**
     * Tried only when the primary cannot be OPENED (unreachable, or a non-2xx before any byte lands).
     * The same bytes under a different roof: the byte count and SHA-256 check stay the one admission
     * gate whichever host served (#168).
     */
    val fallbackUrl: String? = null,
) {
    val sources: List<String> get() = listOfNotNull(sourceUrl, fallbackUrl)
}

internal data class ModelDescriptor(
    val id: String,
    val engineId: String,
    val displayName: String,
    val creator: String,
    val license: String,
    val notice: String,
    val pinnedRevision: String,
    val files: List<ModelFile>,
) {
    val isAvailable: Boolean
        get() = id.isSafeFileName() && files.isNotEmpty() && files.all {
            it.name.isSafeFileName() && it.expectedBytes > 0 && it.sha256?.matches(SHA256) == true &&
                it.sources.all { url -> validateModelSource(url, pinnedRevision, it.name) }
        }
}

internal object ModelManifest {
    private const val parakeetRepo = "csukuangfj/sherpa-onnx-nemo-parakeet-tdt-0.6b-v3-int8"
    private const val parakeetRevision = "2bda32ec70b097a55adaa07d9a7173915b43cc78"
    private const val parakeetSqRepo = "Olicorne/parakeet-tdt-0.6b-v3-smoothquant-onnx"
    private const val parakeetSqRevision = "9d104194420cfe48c3374385bb42b42a788b9225"
    private const val s1Repo = "superwhisper/s1-mini-GGUF"
    private const val s1Revision = "34add00a48a2e5d24e5a4ee5405a99620a3a240c"
    private fun resolve(repo: String, revision: String, file: String) = "https://huggingface.co/$repo/resolve/$revision/$file?download=true"

    /**
     * Our own host: the `enviouslabs-models` R2 bucket behind `models.enviouslabs.co`, shared with the
     * macOS and Windows apps, edge-cached per prefix. Object keys put the pinned revision under a
     * prefix that never changes, so a version bump moves a segment BELOW the cache rule (#168).
     */
    private fun hosted(prefix: String, revision: String, file: String) = "https://models.enviouslabs.co/$prefix/$revision/$file"

    val parakeet = ModelDescriptor("parakeet", "sherpa-onnx", "Parakeet", "NVIDIA", "CC-BY-4.0", "", parakeetRevision, listOf(
        ModelFile("encoder.int8.onnx", 652184281, "acfc2b4456377e15d04f0243af540b7fe7c992f8d898d751cf134c3a55fd2247", hosted("parakeet-onnx", parakeetRevision, "encoder.int8.onnx"), resolve(parakeetRepo, parakeetRevision, "encoder.int8.onnx")),
        ModelFile("decoder.int8.onnx", 11845275, "179e50c43d1a9de79c8a24149a2f9bac6eb5981823f2a2ed88d655b24248db4e", hosted("parakeet-onnx", parakeetRevision, "decoder.int8.onnx"), resolve(parakeetRepo, parakeetRevision, "decoder.int8.onnx")),
        ModelFile("joiner.int8.onnx", 6355277, "3164c13fc2821009440d20fcb5fdc78bff28b4db2f8d0f0b329101719c0948b3", hosted("parakeet-onnx", parakeetRevision, "joiner.int8.onnx"), resolve(parakeetRepo, parakeetRevision, "joiner.int8.onnx")),
        ModelFile("tokens.txt", 93939, "d58544679ea4bc6ac563d1f545eb7d474bd6cfa467f0a6e2c1dc1c7d37e3c35d", hosted("parakeet-onnx", parakeetRevision, "tokens.txt"), resolve(parakeetRepo, parakeetRevision, "tokens.txt")),
    ))
    val s1 = ModelDescriptor("s1-mini", "llama.cpp", "S1-mini", "Superwhisper", "Apache License 2.0", "THIRD_PARTY_NOTICES.txt", s1Revision, listOf(
        ModelFile("s1-mini-q4_k_m.gguf", 484219808, "3b41ebe2502cbd03e811d5d16b022f5ab551eda58d62597d152f89535003c634", hosted("s1", s1Revision, "s1-mini-q4_k_m.gguf"), resolve(s1Repo, s1Revision, "s1-mini-q4_k_m.gguf")),
    ))
    /**
     * The speech model the #374 engine swap will decode with: Parakeet TDT 0.6b v3 with a SmoothQuant int8 encoder
     * (Olicorne, from NVIDIA's weights, CC BY 4.0), onnx-asr layout. Staged in chunk 1: downloaded and verified on a
     * phone that already dictates, while [parakeet] and its engine keep working. Hugging Face serves the two model
     * files from an `int8/` folder, which is not a shape [validateModelSource] admits, so only `vocab.txt` has a
     * fallback there; our own host is the source for the rest. The audio front end (`nemo128.onnx`) ships in the APK.
     */
    val parakeetSq = ModelDescriptor("parakeet-sq", "onnxruntime", "Parakeet", "NVIDIA", "CC-BY-4.0", "", parakeetSqRevision, listOf(
        ModelFile("encoder-model.int8.onnx", 649524002, "019f798a42be5eee029d8591116308df8e8adf1f55a6292c15f1bd5583f04af4", hosted("parakeet-sq", parakeetSqRevision, "encoder-model.int8.onnx")),
        ModelFile("decoder_joint-model.int8.onnx", 18203490, "63a6cd892244e5dbdd8b41541514f2643c7d3c7c454f9adcdf99ca31acb802d0", hosted("parakeet-sq", parakeetSqRevision, "decoder_joint-model.int8.onnx")),
        ModelFile("vocab.txt", 93939, "d58544679ea4bc6ac563d1f545eb7d474bd6cfa467f0a6e2c1dc1c7d37e3c35d", hosted("parakeet-sq", parakeetSqRevision, "vocab.txt"), resolve(parakeetSqRepo, parakeetSqRevision, "vocab.txt")),
    ))
    /** What the Storage page, onboarding and readiness show: the models the running engine uses. */
    val all = listOf(parakeet, s1)
    /** Downloaded in the background ahead of a planned engine swap, never shown as a model the app uses (#374). */
    val staged = listOf(parakeetSq)
    /** Every model the delivery worker and telemetry may name: [all] plus [staged]. */
    val deliverable = all + staged
}

private val SHA256 = Regex("[0-9a-fA-F]{64}")
private fun String.isSafeFileName() = isNotBlank() && this != "." && this != ".." && !contains('/') && !contains('\\') && !contains("..")
