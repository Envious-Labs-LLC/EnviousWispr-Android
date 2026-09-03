package com.envi.wispr.models

data class ModelFile(
    val name: String,
    val expectedBytes: Long,
    val sha256: String?,
    val sourceUrl: String,
)

data class ModelDescriptor(
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
            it.name.isSafeFileName() && it.expectedBytes > 0 && it.sha256?.matches(SHA256) == true && validateModelSource(it.sourceUrl) && it.sourceUrl.contains("/resolve/$pinnedRevision/")
        }
}

object ModelManifest {
    private const val parakeetRepo = "csukuangfj/sherpa-onnx-nemo-parakeet-tdt-0.6b-v3-int8"
    private const val parakeetRevision = "2bda32ec70b097a55adaa07d9a7173915b43cc78"
    private const val s1Repo = "superwhisper/s1-mini-GGUF"
    private const val s1Revision = "34add00a48a2e5d24e5a4ee5405a99620a3a240c"
    private fun resolve(repo: String, revision: String, file: String) = "https://huggingface.co/$repo/resolve/$revision/$file?download=true"

    val parakeet = ModelDescriptor("parakeet", "sherpa-onnx", "Parakeet", "NVIDIA", "CC-BY-4.0", "", parakeetRevision, listOf(
        ModelFile("encoder.int8.onnx", 652184281, "acfc2b4456377e15d04f0243af540b7fe7c992f8d898d751cf134c3a55fd2247", resolve(parakeetRepo, parakeetRevision, "encoder.int8.onnx")),
        ModelFile("decoder.int8.onnx", 11845275, "179e50c43d1a9de79c8a24149a2f9bac6eb5981823f2a2ed88d655b24248db4e", resolve(parakeetRepo, parakeetRevision, "decoder.int8.onnx")),
        ModelFile("joiner.int8.onnx", 6355277, "3164c13fc2821009440d20fcb5fdc78bff28b4db2f8d0f0b329101719c0948b3", resolve(parakeetRepo, parakeetRevision, "joiner.int8.onnx")),
        ModelFile("tokens.txt", 93939, "d58544679ea4bc6ac563d1f545eb7d474bd6cfa467f0a6e2c1dc1c7d37e3c35d", resolve(parakeetRepo, parakeetRevision, "tokens.txt")),
    ))
    val s1 = ModelDescriptor("s1-mini", "llama.cpp", "S1-mini", "Superwhisper", "Apache License 2.0", "THIRD_PARTY_NOTICES.txt", s1Revision, listOf(
        ModelFile("s1-mini-q4_k_m.gguf", 484219808, "3b41ebe2502cbd03e811d5d16b022f5ab551eda58d62597d152f89535003c634", resolve(s1Repo, s1Revision, "s1-mini-q4_k_m.gguf")),
    ))
    val all = listOf(parakeet, s1)
}

private val SHA256 = Regex("[0-9a-fA-F]{64}")
private fun String.isSafeFileName() = isNotBlank() && this != "." && this != ".." && !contains('/') && !contains('\\') && !contains("..")
