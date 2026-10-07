package com.envi.wispr.processing

/** The available hardware categories, not a promise that any particular model can use them. */
internal enum class ProcessingBackend(val wire: String, val label: String, val shortLabel: String) {
    CPU("cpu", "Processor", "CPU"), GPU("gpu", "Graphics", "GPU"), NPU("npu", "AI chip", "NPU");
    companion object {
        fun fromWire(value: String): ProcessingBackend = entries.firstOrNull { it.wire == value }
            ?: throw IllegalArgumentException("Unknown processing backend")
    }
}

/** Owns an encoded order so a caller's mutable list can never change an admitted take. */
internal data class BackendOrder private constructor(private val encoded: String) {
    val backends: List<ProcessingBackend> get() = encoded.split(',').map(ProcessingBackend::fromWire)
    fun encode(): String = encoded
    companion object {
        val DEFAULT = of(listOf(ProcessingBackend.GPU, ProcessingBackend.CPU, ProcessingBackend.NPU))
        fun of(backends: List<ProcessingBackend>): BackendOrder {
            require(backends.size == ProcessingBackend.entries.size && backends.toSet() == ProcessingBackend.entries.toSet())
            return BackendOrder(backends.joinToString(",") { it.wire })
        }
        fun decode(value: String): BackendOrder = of(value.split(',').map(ProcessingBackend::fromWire))
    }
}

/** A saved custom order survives Automatic and temporary loss of a provider. */
internal data class ProcessingPreference(
    val automatic: Boolean,
    val customOrder: BackendOrder?,
    val retryRevision: Long,
) {
    init { require(retryRevision >= 0 && (automatic || customOrder != null)) }
    fun candidates(implemented: Set<ProcessingBackend>, qualified: Set<ProcessingBackend>): List<ProcessingBackend> =
        (if (automatic) BackendOrder.DEFAULT else checkNotNull(customOrder)).backends.filter {
            it in implemented && (automatic || it in qualified)
        }
    fun custom(available: List<ProcessingBackend>): ProcessingPreference = copy(
        automatic = false,
        customOrder = customOrder ?: BackendOrder.of((available + BackendOrder.DEFAULT.backends).distinct()),
    )
    fun move(backend: ProcessingBackend, delta: Int, available: Set<ProcessingBackend>): ProcessingPreference {
        val order = checkNotNull(customOrder).backends.toMutableList()
        val eligible = order.filter { it in available }
        val from = eligible.indexOf(backend)
        val to = from + delta
        if (from < 0 || to !in eligible.indices) return this
        val a = order.indexOf(backend); val b = order.indexOf(eligible[to])
        order[a] = eligible[to]; order[b] = backend
        return copy(customOrder = BackendOrder.of(order))
    }
    fun retry(): ProcessingPreference = copy(retryRevision = Math.addExact(retryRevision, 1))
    fun encode(): String = "1;${if (automatic) "auto" else "custom"};${customOrder?.encode() ?: "unset"};$retryRevision"
    companion object {
        val DEFAULT = ProcessingPreference(true, null, 0)
        fun decode(value: String?): ProcessingPreference {
            if (value == null) return DEFAULT
            val parts = value.split(';')
            require(parts.size == 4 && parts[0] == "1")
            val automatic = when (parts[1]) { "auto" -> true; "custom" -> false; else -> error("Invalid processing mode") }
            val order = if (parts[2] == "unset") null else BackendOrder.decode(parts[2])
            return ProcessingPreference(automatic, order, parts[3].toLong())
        }
    }
}

/** Successful native canaries for one model/runtime/platform identity, copied into a request. */
internal data class ProcessingQualification private constructor(
    val contextId: String,
    private val encodedBackends: String,
    private val encodedFailures: String,
    private val encodedStamps: String,
) {
    val backends: Set<ProcessingBackend> get() = if (encodedBackends.isEmpty()) emptySet() else encodedBackends.split(',').map(ProcessingBackend::fromWire).toSet()
    private val failureHighWater: Map<ProcessingBackend, Long> get() = if (encodedFailures.isEmpty()) emptyMap() else encodedFailures.split(',').associate {
        val pair = it.split(':'); ProcessingBackend.fromWire(pair[0]) to pair[1].toLong()
    }
    val failedAtRevision: Map<ProcessingBackend, Long> get() = failureHighWater.filterKeys { it !in backends }
    val evidenceStamps: Map<ProcessingBackend, ProcessingEvidenceStamp> get() = if (encodedStamps.isEmpty()) emptyMap() else encodedStamps.split(',').associate {
        val p = it.split('='); ProcessingBackend.fromWire(p[0]) to checkNotNull(ProcessingEvidenceStamp.decode(p[1]))
    }
    /** Retry can revisit a previously proven route, never an unseen accelerator. */
    fun eligible(revision: Long): Set<ProcessingBackend> = backends + failedAtRevision.filterValues { revision > it }.keys
    fun observed(success: Set<ProcessingBackend>, failures: Set<ProcessingBackend>, runtimeFailed: Boolean, revision: Long, stamp: ProcessingEvidenceStamp): ProcessingQualification {
        val available = backends.toMutableSet()
        val history = failureHighWater.toMutableMap()
        val stamps = evidenceStamps.toMutableMap()
        val failing = if (runtimeFailed) ProcessingEnvironment.standardPolishBackends else failures
        (success + failing).forEach { backend ->
            if (!stamp.newerThan(stamps[backend])) return@forEach
            stamps[backend] = stamp
            if (backend in success) available += backend else {
                if (backend in available || backend in history) history[backend] = maxOf(history[backend] ?: 0, revision)
                available -= backend
            }
        }
        return create(contextId, available, history, stamps)
    }
    fun encode(): String = "$contextId;$encodedBackends;$encodedFailures;$encodedStamps"
    companion object {
        val NONE = ProcessingQualification("", "", "", "")
        fun of(contextId: String, backends: Set<ProcessingBackend>): ProcessingQualification = create(contextId, backends, emptyMap(), emptyMap())
        private fun create(contextId: String, backends: Set<ProcessingBackend>, failures: Map<ProcessingBackend, Long>, stamps: Map<ProcessingBackend, ProcessingEvidenceStamp>): ProcessingQualification {
            require(contextId.length == 64 && contextId.all { it in '0'..'9' || it in 'a'..'f' })
            require(failures.values.all { it >= 0 })
            return ProcessingQualification(contextId, backends.sortedBy { it.ordinal }.joinToString(",") { it.wire },
                failures.entries.sortedBy { it.key.ordinal }.joinToString(",") { "${it.key.wire}:${it.value}" },
                stamps.entries.sortedBy { it.key.ordinal }.joinToString(",") { "${it.key.wire}=${it.value.encode()}" })
        }
        fun decode(value: String?): ProcessingQualification {
            if (value == null) return NONE
            val parts = value.split(';'); require(parts.size in 2..4)
            if (parts.all(String::isEmpty)) return NONE
            val list = if (parts[1].isEmpty()) emptyList() else parts[1].split(',').map(ProcessingBackend::fromWire)
            require(list.distinct().size == list.size)
            val failures = if (parts.size < 3 || parts[2].isEmpty()) emptyList() else parts[2].split(',').map {
                val pair = it.split(':'); require(pair.size == 2)
                ProcessingBackend.fromWire(pair[0]) to pair[1].toLong()
            }
            require(failures.map { it.first }.distinct().size == failures.size)
            val stamps = if (parts.size < 4 || parts[3].isEmpty()) emptyList() else parts[3].split(',').map {
                val p = it.split('='); require(p.size == 2)
                ProcessingBackend.fromWire(p[0]) to checkNotNull(ProcessingEvidenceStamp.decode(p[1]))
            }
            require(stamps.map { it.first }.distinct().size == stamps.size)
            return create(parts[0], list.toSet(), failures.toMap(), stamps.toMap())
        }
    }
}
