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
    /**
     * Older pinned revisions of this same model that this build's engine still loads unchanged (#108): while a new
     * revision downloads, the one already on the phone keeps dictating, verified exactly as strictly against its own
     * descriptor. Same id, engine and file names, a different revision, no nesting (`ModelManifestTest`).
     */
    val previous: List<ModelDescriptor> = emptyList(),
    /**
     * Bytes the engine derives from this model and keeps outside the model folder (`EngineCache`), on top of
     * [files] (#531): EG-1 Micro's memory-mapped weight cache. Counted in storage checks and in what the app says the
     * model costs on the phone; 0 for engines that read the model files directly.
     */
    val engineCacheBytes: Long = 0L,
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
    private const val eg1Revision = "eg1-micro-1.0-g3d-mnn-b32"
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
    /**
     * EG-1 Micro (#528), Envious Labs' Qwen 3.5 0.8B polish fine-tune, run by MNN (#531): 4-bit in blocks of 32 with an
     * 8-bit output layer (`llmexport.py --quant_bit 4 --quant_block 32 --lm_quant_bit 8`), `llm_config.json` with
     * `is_visual` false. On the full 4,352-case exam (2026-11-15) it passed 76.3% against the Q4_K_M file's 75.5% with no
     * language significantly worse; the HQQ block-64 export (74.0%) failed that bar. `config.json` comes first because
     * the runtime loads the folder through it. No `previous`: the GGUF revision ran on another engine (`previous` means
     * "this engine still loads it", #108), and neither retired revision reached production; `Eg1ModelUpgrade` moves a
     * phone holding either one to this revision.
     */
    val eg1Micro = ModelDescriptor(
        id = "eg1-micro",
        engineId = "mnn",
        displayName = "EG-1 Micro",
        creator = "Envious Labs",
        license = "Envious Labs proprietary model weights; Apache-2.0 / MIT runtime dependencies",
        notice = "THIRD_PARTY_NOTICES.txt",
        pinnedRevision = eg1Revision,
        files = listOf(
            ModelFile("config.json", 254L, "062163f9685988f05c5c56c7a5e5883b15027fff74cd3020614d06addca54e74", hosted("eg1", eg1Revision, "config.json")),
            ModelFile("llm_config.json", 4_138L, "849428542b980b4b031fd8861ecdb1c5af749e6fc74173a3bdc19f584a700340", hosted("eg1", eg1Revision, "llm_config.json")),
            ModelFile("llm.mnn", 2_603_920L, "1f11f3f9e8ff0cfd6f70b01a4eaa26cff3d243a24937a26652834a8fa5e588ab", hosted("eg1", eg1Revision, "llm.mnn")),
            ModelFile("llm.mnn.weight", 597_498_250L, "ac5614feae191a289706930c62775087954dff30f91b0e87b6f812b74251e6af", hosted("eg1", eg1Revision, "llm.mnn.weight")),
            ModelFile("tokenizer.mtok", 7_337_120L, "bdf181ec543983404765394d682a25ecb95360172fa7e91b1a2573e0b632c34b", hosted("eg1", eg1Revision, "tokenizer.mtok")),
        ),
        previous = emptyList(),
        // Measured on the S26 with one engine thread, 2026-11-15 (#531): the built weight cache allocates 699,969,536 bytes;
        // each saved system-prompt entry takes 24,750,854 (files plus sidecar); at most four are kept, plus one being
        // rebuilt and 16 MiB of KV scratch (peak seen 15,138,816).
        engineCacheBytes = 699_969_536L + 5L * 24_750_854L + 16_777_216L,
    )
    val ONBOARDING = listOf(parakeet, s1)
    val LOCAL_POLISH = listOf(s1, eg1Micro)
    val all = listOf(parakeet, s1, eg1Micro)

    fun localPolish(id: String?): ModelDescriptor = LOCAL_POLISH.firstOrNull { it.id == id } ?: s1
}

private val SHA256 = Regex("[0-9a-fA-F]{64}")
private fun String.isSafeFileName() = isNotBlank() && this != "." && this != ".." && !contains('/') && !contains('\\') && !contains("..")
