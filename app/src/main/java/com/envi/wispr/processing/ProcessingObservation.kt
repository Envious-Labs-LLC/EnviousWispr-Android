package com.envi.wispr.processing

import com.envi.wispr.polish.PolishContext
import com.envi.wispr.polish.PolishReason
import java.util.Base64
import java.text.DateFormat
import java.util.Date

/** Latest committed user take. Canaries have a different result type and cannot construct this route. */
internal data class ProcessingObservation(
    val takeId: String,
    val requestId: Long,
    val completedAtMs: Long,
    val contextToken: String,
    val reason: PolishReason,
    val speechPreference: ProcessingPreference,
    val local: ProcessingUsage?,
    val takeAcceptedAtMs: Long,
    val processEpoch: String = PROCESS_EPOCH,
    val localPreference: ProcessingPreference? = local?.preference,
) {
    val operationKind: String get() = "user-dictation"
    val speechBackend: ProcessingBackend get() = ProcessingBackend.CPU
    fun speechDisplayLine(): String = "${DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(completedAtMs))}: Parakeet used CPU"
    fun displayLine(): String {
        val route = when (val context = PolishContext.decode(contextToken)) {
            PolishContext.Off -> "AI polish off"
            PolishContext.Local -> if (reason == PolishReason.POLISHED && local?.acceptedLocalText == true && local.backend != null) {
                "S1-mini used ${local.backend.shortLabel}" + if (local.failedBackends.isNotEmpty()) " after the preferred option failed" else ""
            } else "Text kept on this phone. No local model result used."
            is PolishContext.Cloud -> if (reason == PolishReason.POLISHED) "${context.providerName} used" else "Text kept on this phone. Cloud polish was not used."
            PolishContext.CloudUnconfigured, null -> "Text kept on this phone"
        }
        return "${DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(completedAtMs))}: $route"
    }
    fun encode(): String = listOf(
        "1", takeId, requestId.toString(), completedAtMs.toString(), contextToken, reason.name,
        speechPreference.encode(), local?.backend?.wire.orEmpty(), local?.contextId.orEmpty(),
        local?.preference?.encode().orEmpty(), (local?.acceptedLocalText == true).toString(),
        local?.failedBackendCodes.orEmpty(), (local?.runtimeFailed == true).toString(),
        local?.modelId.orEmpty(), local?.artifactStamp.orEmpty(), local?.artifactSha256.orEmpty(), takeAcceptedAtMs.toString(), processEpoch, localPreference?.encode().orEmpty(), local?.loadStamp?.encode().orEmpty(), local?.generationStamp?.encode().orEmpty(), (local?.generationFailed == true).toString(),
    ).joinToString("|") { Base64.getUrlEncoder().withoutPadding().encodeToString(it.toByteArray(Charsets.UTF_8)) }
    companion object {
        private val PROCESS_EPOCH = java.util.UUID.randomUUID().toString()
        fun decode(value: String?): ProcessingObservation? {
            if (value == null) return null
            val p = value.split('|').map { String(Base64.getUrlDecoder().decode(it), Charsets.UTF_8) }
            require(p.size == 22 && p[0] == "1" && p[2].toLong() >= 0 && p[3].toLong() >= 0 && p[16].toLong() >= 0 && p[4] in PolishContext.TOKENS)
            require(java.util.UUID.fromString(p[17]).toString() == p[17])
            return ProcessingObservation(p[1], p[2].toLong(), p[3].toLong(), p[4], PolishReason.valueOf(p[5]), ProcessingPreference.decode(p[6]),
                if (p[8].isEmpty()) null else ProcessingUsage(p[7].takeIf(String::isNotEmpty)?.let(ProcessingBackend::fromWire), p[8], ProcessingPreference.decode(p[9]), p[10].toBooleanStrict(), p[11], p[12].toBooleanStrict(), p[13], p[14], p[15], ProcessingEvidenceStamp.decode(p[19].takeIf(String::isNotEmpty)), ProcessingEvidenceStamp.decode(p[20].takeIf(String::isNotEmpty)), p[21].toBooleanStrict()), p[16].toLong(), p[17], p[18].takeIf(String::isNotEmpty)?.let(ProcessingPreference::decode))
        }
    }
}
