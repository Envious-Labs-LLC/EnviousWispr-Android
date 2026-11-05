package com.envi.wispr.polish

import android.os.Parcel
import android.os.Parcelable
import java.util.concurrent.atomic.AtomicReference

/** The last successful load of one resident native instance, never an additive request stage. */
internal data class S1LoadTiming(val durationMs: Long, val completedAtMs: Long, val majorFaults: Long?)

/** Only measurements and finite identities cross the process boundary. No text or model paths. */
internal data class LocalPolishDiagnostics(
    val queueMs: Long,
    val generationMs: Long?,
    val lastLoadMs: Long?,
    val lastLoadAgeMs: Long?,
    val lastLoadMajorFaults: Long?,
    val runtime: S1RuntimeKind?,
) : Parcelable {
    fun properties(): Map<String, Any?> = mapOf(
        "polish_queue_ms" to queueMs,
        "polish_generation_ms" to generationMs,
        "polish_last_load_ms" to lastLoadMs,
        "polish_last_load_age_ms" to lastLoadAgeMs,
        "polish_last_load_major_faults" to lastLoadMajorFaults,
        "polish_runtime" to runtime?.identity,
    )

    override fun describeContents(): Int = 0
    override fun writeToParcel(dest: Parcel, flags: Int) {
        dest.writeLong(queueMs)
        dest.writeOptionalLong(generationMs)
        dest.writeOptionalLong(lastLoadMs)
        dest.writeOptionalLong(lastLoadAgeMs)
        dest.writeOptionalLong(lastLoadMajorFaults)
        dest.writeString(runtime?.name)
    }

    companion object {
        @JvmField
        val CREATOR: Parcelable.Creator<LocalPolishDiagnostics> = object : Parcelable.Creator<LocalPolishDiagnostics> {
            override fun createFromParcel(source: Parcel): LocalPolishDiagnostics = LocalPolishDiagnostics(
                source.readLong(), source.readOptionalLong(), source.readOptionalLong(),
                source.readOptionalLong(), source.readOptionalLong(), source.readString()?.let(S1RuntimeKind::valueOf),
            )
            override fun newArray(size: Int): Array<LocalPolishDiagnostics?> = arrayOfNulls(size)
        }
    }
}

/** Request-local observation only. Expiry snapshots never wait for the native worker. */
internal class LocalPolishTiming(
    private val queueMs: Long,
    private val runtime: S1RuntimeKind?,
    private val load: S1LoadTiming?,
    private val nowMs: () -> Long,
) {
    private sealed interface Generation {
        data object NotStarted : Generation
        data class Running(val startedAtMs: Long) : Generation
        data class Finished(val durationMs: Long) : Generation
    }

    private val generation = AtomicReference<Generation>(Generation.NotStarted)

    fun <T> measureGeneration(block: () -> T): T {
        generation.set(Generation.Running(nowMs()))
        try {
            return block()
        } finally {
            val seen = generation.get()
            if (seen is Generation.Running) {
                generation.compareAndSet(seen, Generation.Finished((nowMs() - seen.startedAtMs).coerceAtLeast(0L)))
            }
        }
    }

    fun snapshot(): LocalPolishDiagnostics {
        val seen = generation.get()
        val sampledAt = nowMs()
        val duration = when (seen) {
            Generation.NotStarted -> null
            is Generation.Running -> (sampledAt - seen.startedAtMs).coerceAtLeast(0L)
            is Generation.Finished -> seen.durationMs
        }
        return LocalPolishDiagnostics(
            queueMs, duration, load?.durationMs,
            load?.let { (sampledAt - it.completedAtMs).coerceAtLeast(0L) },
            load?.majorFaults, runtime,
        )
    }
}

private fun Parcel.writeOptionalLong(value: Long?) {
    writeInt(if (value == null) 0 else 1)
    if (value != null) writeLong(value)
}

private fun Parcel.readOptionalLong(): Long? = when (readInt()) {
    0 -> null
    1 -> readLong()
    else -> throw IllegalArgumentException("Unknown optional duration tag")
}

/** Optional observation must never replace a text outcome. */
internal fun LocalPolishTiming?.snapshotOrNull(): LocalPolishDiagnostics? =
    this?.let { runCatching(it::snapshot).getOrNull() }
