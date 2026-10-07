package com.envi.wispr.polish

import com.envi.wispr.processing.ProcessingEvidenceStamp
import com.envi.wispr.processing.ProcessingEnvironment
import com.envi.wispr.processing.ProcessingBackend
import com.envi.wispr.processing.ProcessingPreference
import com.envi.wispr.processing.ProcessingQualification

/** Prompt controls are deliberately absent: they do not change the created native runtime. */
internal data class S1LoadConfiguration(
    val contextId: String,
    val preference: ProcessingPreference,
    val qualification: ProcessingQualification,
    val artifactStamp: String = "",
) {
    val candidates: List<ProcessingBackend> get() = preference.candidates(
        ProcessingEnvironment.standardPolishBackends,
        if (qualification.contextId == contextId) qualification.eligible(preference.retryRevision) else emptySet(),
    )
}

/** One native instance on PolishService's worker. A failed replacement never leaves the old key ready. */
internal class S1LoadedConfiguration {
    data class Loaded(val requested: S1LoadConfiguration, val backend: ProcessingBackend, val failedBackendCodes: String, val failureStamp: ProcessingEvidenceStamp?) {
        val failedBackends get() = if (failedBackendCodes.isEmpty()) emptySet() else failedBackendCodes.split(',').map(ProcessingBackend::fromWire).toSet()
    }
    @Volatile private var current: Loaded? = null
    val loaded: Loaded? get() = current
    fun matches(configuration: S1LoadConfiguration): Boolean {
        val loaded = current ?: return false
        val old = loaded.requested
        if (old.contextId != configuration.contextId || old.artifactStamp != configuration.artifactStamp || old.preference != configuration.preference) return false
        val candidates = configuration.candidates
        if (loaded.backend !in candidates) return false
        return candidates.takeWhile { it != loaded.backend }.all { backend ->
            backend in loaded.failedBackends && !(configuration.qualification.contextId == configuration.contextId && backend in configuration.qualification.backends &&
                configuration.qualification.evidenceStamps[backend]?.newerThan(loaded.failureStamp) == true)
        }
    }
    fun clear() { current = null }
    fun ensure(configuration: S1LoadConfiguration, failures: () -> Set<ProcessingBackend> = { emptySet() }, failureStamp: () -> ProcessingEvidenceStamp? = { null }, release: () -> Unit = {}, load: (List<ProcessingBackend>) -> ProcessingBackend): Loaded {
        current?.takeIf { matches(configuration) }?.let { return it.copy(requested = configuration).also { current = it } }
        current = null
        val candidates = configuration.candidates
        if (candidates.isEmpty()) release()
        require(candidates.isNotEmpty()) { "No qualified processing path" }
        val backend = load(candidates)
        check(backend in candidates) { "Unexpected processing path" }
        return Loaded(configuration, backend, failures().sortedBy { it.ordinal }.joinToString(",") { it.wire }, failureStamp()).also { current = it }
    }
}
