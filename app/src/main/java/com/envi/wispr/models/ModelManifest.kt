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
    private const val parakeetRepo = "csukuangfj/sherpa-onnx-nemo-parakeet-tdt-0.6b-v2-int8"
    private const val parakeetRevision = "1ab9323565ddb038682214b292f588070a538ce2"
    private const val s1Repo = "superwhisper/s1-mini-GGUF"
    private const val s1Revision = "34add00a48a2e5d24e5a4ee5405a99620a3a240c"
    private fun resolve(repo: String, revision: String, file: String) = "https://huggingface.co/$repo/resolve/$revision/$file?download=true"

    val parakeet = ModelDescriptor("parakeet", "sherpa-onnx", "Parakeet", "NVIDIA", "CC-BY-4.0", "", parakeetRevision, listOf(
        ModelFile("encoder.int8.onnx", 652184296, "a32b12d17bbbc309d0686fbbcc2987b5e9b8333a7da83fa6b089f0a2acd651ab", resolve(parakeetRepo, parakeetRevision, "encoder.int8.onnx")),
        ModelFile("decoder.int8.onnx", 7257753, "b6bb64963457237b900e496ee9994b59294526439fbcc1fecf705b31a15c6b4e", resolve(parakeetRepo, parakeetRevision, "decoder.int8.onnx")),
        ModelFile("joiner.int8.onnx", 1739080, "7946164367946e7f9f29a122407c3252b680dbae9a51343eb2488d057c3c43d2", resolve(parakeetRepo, parakeetRevision, "joiner.int8.onnx")),
        ModelFile("tokens.txt", 9384, "ec182b70dd42113aff6c5372c75cac58c952443eb22322f57bbd7f53977d497d", resolve(parakeetRepo, parakeetRevision, "tokens.txt")),
    ))
    val s1 = ModelDescriptor("s1-mini", "llama.cpp", "S1-mini", "Superwhisper", "Apache License 2.0", "THIRD_PARTY_NOTICES.txt", s1Revision, listOf(
        ModelFile("s1-mini-q4_k_m.gguf", 484219808, "3b41ebe2502cbd03e811d5d16b022f5ab551eda58d62597d152f89535003c634", resolve(s1Repo, s1Revision, "s1-mini-q4_k_m.gguf")),
    ))
    val all = listOf(parakeet, s1)
}

private val SHA256 = Regex("[0-9a-fA-F]{64}")
private fun String.isSafeFileName() = isNotBlank() && this != "." && this != ".." && !contains('/') && !contains('\\') && !contains("..")
