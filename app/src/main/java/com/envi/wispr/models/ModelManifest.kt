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
    private const val parakeetRepo = "Olicorne/parakeet-tdt-0.6b-v3-smoothquant-onnx"
    private const val parakeetRevision = "9d104194420cfe48c3374385bb42b42a788b9225"
    private const val s1Repo = "superwhisper/s1-mini-GGUF"
    private const val s1Revision = "34add00a48a2e5d24e5a4ee5405a99620a3a240c"
    private fun resolve(repo: String, revision: String, file: String) = "https://huggingface.co/$repo/resolve/$revision/$file?download=true"

    /**
     * Our own host: the `enviouslabs-models` R2 bucket behind `models.enviouslabs.co`, shared with the
     * macOS and Windows apps, edge-cached per prefix. Object keys put the pinned revision under a
     * prefix that never changes, so a version bump moves a segment BELOW the cache rule (#168).
     */
    private fun hosted(prefix: String, revision: String, file: String) = "https://models.enviouslabs.co/$prefix/$revision/$file"

    /**
     * The speech model (#374): Parakeet TDT 0.6b v3 with a SmoothQuant int8 encoder (Olicorne, from NVIDIA's weights,
     * CC BY 4.0), onnx-asr layout, decoded by `ParakeetEngine`. Its storage id is `parakeet-sq`, so it never shares a
     * folder with the sherpa-onnx model it replaced (`LegacyModelSweep` removes that folder). Hugging Face serves the
     * two model files from an `int8/` folder, a shape [validateModelSource] does not admit, so only `vocab.txt` has a
     * fallback there. The audio front end (`nemo128.onnx`) ships in the APK.
     */
    val parakeet = ModelDescriptor("parakeet-sq", "onnxruntime", "Parakeet", "NVIDIA", "CC-BY-4.0", "THIRD_PARTY_NOTICES.txt", parakeetRevision, listOf(
        ModelFile("encoder-model.int8.onnx", 649524002, "019f798a42be5eee029d8591116308df8e8adf1f55a6292c15f1bd5583f04af4", hosted("parakeet-sq", parakeetRevision, "encoder-model.int8.onnx")),
        ModelFile("decoder_joint-model.int8.onnx", 18203490, "63a6cd892244e5dbdd8b41541514f2643c7d3c7c454f9adcdf99ca31acb802d0", hosted("parakeet-sq", parakeetRevision, "decoder_joint-model.int8.onnx")),
        ModelFile("vocab.txt", 93939, "d58544679ea4bc6ac563d1f545eb7d474bd6cfa467f0a6e2c1dc1c7d37e3c35d", hosted("parakeet-sq", parakeetRevision, "vocab.txt"), resolve(parakeetRepo, parakeetRevision, "vocab.txt")),
    ))
    val s1 = ModelDescriptor("s1-mini", "llama.cpp", "S1-mini", "Superwhisper", "Apache License 2.0", "THIRD_PARTY_NOTICES.txt", s1Revision, listOf(
        ModelFile("s1-mini-q4_k_m.gguf", 484219808, "3b41ebe2502cbd03e811d5d16b022f5ab551eda58d62597d152f89535003c634", hosted("s1", s1Revision, "s1-mini-q4_k_m.gguf"), resolve(s1Repo, s1Revision, "s1-mini-q4_k_m.gguf")),
    ))
    val all = listOf(parakeet, s1)
}

private val SHA256 = Regex("[0-9a-fA-F]{64}")
private fun String.isSafeFileName() = isNotBlank() && this != "." && this != ".." && !contains('/') && !contains('\\') && !contains("..")
