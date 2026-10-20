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
    private const val parakeetRepo = "Olicorne/parakeet-tdt-0.6b-v3-ultra-onnx"
    private const val parakeetRevision = "3b80a92332b5d34c364a35a880ef049c7fede9f2"
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
     * The speech model (#458): Parakeet Ultra, moondream's post-train of NVIDIA's Parakeet TDT 0.6b v3 (CC BY 4.0), as
     * Olicorne's int8 ONNX export in the onnx-asr layout, decoded by `ParakeetEngine`. Same file names, tokenizer and
     * graph interface as the SmoothQuant set it replaced, which lost the ends of takes (#458); its storage id is
     * `parakeet-ultra`, so it never shares a folder with that set (`LegacyModelSweep` removes it). Hugging Face serves
     * the two model files from an `int8/` folder, a shape [validateModelSource] does not admit, so only `vocab.txt`
     * has a fallback there. The audio front end (`nemo128.onnx`) ships in the APK.
     */
    val parakeet = ModelDescriptor("parakeet-ultra", "onnxruntime", "Parakeet Ultra", "NVIDIA, moondream", "CC-BY-4.0", "THIRD_PARTY_NOTICES.txt", parakeetRevision, listOf(
        ModelFile("encoder-model.int8.onnx", 649537325, "8a2b47169cf3f1b114010e12c0221bc6f07203289477a65a44847b9e3f1e01ed", hosted("parakeet-ultra", parakeetRevision, "encoder-model.int8.onnx")),
        ModelFile("decoder_joint-model.int8.onnx", 18203490, "667ffde2242a560923cba6bc7f69ef03d736f8a36ca9588f2e6f9fa818cf2fc1", hosted("parakeet-ultra", parakeetRevision, "decoder_joint-model.int8.onnx")),
        ModelFile("vocab.txt", 93939, "d58544679ea4bc6ac563d1f545eb7d474bd6cfa467f0a6e2c1dc1c7d37e3c35d", hosted("parakeet-ultra", parakeetRevision, "vocab.txt"), resolve(parakeetRepo, parakeetRevision, "vocab.txt")),
    ))
    val s1 = ModelDescriptor("s1-mini", "llama.cpp", "S1-mini", "Superwhisper", "Apache License 2.0", "THIRD_PARTY_NOTICES.txt", s1Revision, listOf(
        ModelFile("s1-mini-q4_k_m.gguf", 484219808, "3b41ebe2502cbd03e811d5d16b022f5ab551eda58d62597d152f89535003c634", hosted("s1", s1Revision, "s1-mini-q4_k_m.gguf"), resolve(s1Repo, s1Revision, "s1-mini-q4_k_m.gguf")),
    ))
    val all = listOf(parakeet, s1)
}

private val SHA256 = Regex("[0-9a-fA-F]{64}")
private fun String.isSafeFileName() = isNotBlank() && this != "." && this != ".." && !contains('/') && !contains('\\') && !contains("..")
