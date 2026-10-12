package com.envi.wispr.processing

import android.content.Context
import android.os.SystemClock
import android.provider.Settings

/** Shared native execution order across processes; a reboot starts a different order. Stays on-device. */
internal data class ProcessingEvidenceStamp(val boot: Int, val nanos: Long) {
    init { require(boot >= 0 && nanos >= 0) }
    fun newerThan(other: ProcessingEvidenceStamp?): Boolean = other == null || boot > other.boot || boot == other.boot && nanos > other.nanos
    fun encode() = "$boot:$nanos"
    companion object {
        @Volatile private var cachedBoot: Int? = null
        fun now(context: Context): ProcessingEvidenceStamp {
            val nanos = SystemClock.elapsedRealtimeNanos()
            val boot = cachedBoot ?: synchronized(this) {
                cachedBoot ?: Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT).also { cachedBoot = it }
            }
            return ProcessingEvidenceStamp(boot, nanos)
        }
        fun decode(value: String?): ProcessingEvidenceStamp? {
            if (value == null) return null
            val p = value.split(':'); require(p.size == 2)
            return ProcessingEvidenceStamp(p[0].toInt(), p[1].toLong())
        }
    }
}
