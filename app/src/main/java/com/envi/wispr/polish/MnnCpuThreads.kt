package com.envi.wispr.polish

import java.io.File

/**
 * How many CPU threads EG-1 Micro's MNN engine gets (#531), decided at each load from the cores this process may use.
 *
 * Measured 2026-11-15, MNN b6a3216, in the real app:
 * - Galaxy S26: while the user types in another app, `:polish` sits in Android's foreground cpuset (cores 0-5), which
 *   leaves out the two fastest cores (6-7). There MNN's thread pool collapses as threads are added: a 10-token take took
 *   1.87-1.94 s on 4 threads and 0.59-0.62 s on 1, identical text.
 * - Galaxy A15, Galaxy A26, Pixel 7 (Test Lab, `Eg1MicroHardwareDeviceTest`): the process can use every core and
 *   4 threads ran 25-40% faster than 1 (A15 email take 5.9 s against a 10 s timeout on 1 thread).
 *
 * So: [FEW] when the phone's fastest cores are outside this process's allowed set, otherwise [MANY]. When either fact
 * cannot be read the answer is [MANY], the setting every phone ran before this rule. Saved prompt entries depend on the
 * thread count, so `mnn_jni.cpp` keys them by it.
 */
internal object MnnCpuThreads {
    const val MANY = 4
    const val FEW = 1

    /** The decision, free of Android: [allowed] cores this process may run on, [maxKhz] each core's top frequency. */
    fun choose(allowed: Set<Int>?, maxKhz: Map<Int, Long>): Int {
        if (allowed.isNullOrEmpty() || maxKhz.isEmpty()) return MANY
        val top = maxKhz.values.max()
        val fastest = maxKhz.filterValues { it == top }.keys
        return if (allowed.containsAll(fastest)) MANY else FEW
    }

    /** The thread count plus what it was read from, for the load log line (core numbers and speeds only). */
    data class Decision(val threads: Int, val allowed: Set<Int>?, val maxKhz: Map<Int, Long>) {
        fun logLine(): String = "MNN threads=$threads allowed=${allowed?.sorted() ?: "unread"} " +
            "fastest=${maxKhz.takeIf { it.isNotEmpty() }?.let { m -> m.filterValues { it == m.values.max() }.keys.sorted() } ?: "unread"}"
    }

    /** Reads `Cpus_allowed_list` from `/proc/self/status` and each core's `cpuinfo_max_freq`; never throws. */
    fun forThisProcess(): Decision {
        val allowed = runCatching {
            File("/proc/self/status").readLines().firstOrNull { it.startsWith("Cpus_allowed_list:") }
                ?.substringAfter(':')?.trim()?.let(::parseCpuList)
        }.getOrNull()
        val maxKhz = runCatching {
            File("/sys/devices/system/cpu").listFiles().orEmpty()
                .mapNotNull { dir -> Regex("cpu([0-9]+)").matchEntire(dir.name)?.groupValues?.get(1)?.toInt()?.let { it to dir } }
                .mapNotNull { (cpu, dir) -> File(dir, "cpufreq/cpuinfo_max_freq").takeIf { it.canRead() }?.readText()?.trim()?.toLongOrNull()?.let { cpu to it } }
                .toMap()
        }.getOrDefault(emptyMap())
        return Decision(choose(allowed, maxKhz), allowed, maxKhz)
    }

    /** Parses a kernel cpu list such as `0-1,4-5`; null for anything malformed, so a bad read never narrows the set. */
    fun parseCpuList(text: String): Set<Int>? {
        if (text.isBlank()) return null
        val out = mutableSetOf<Int>()
        for (part in text.split(',')) {
            val bounds = part.trim().split('-')
            val low = bounds[0].toIntOrNull() ?: return null
            val high = (if (bounds.size == 2) bounds[1].toIntOrNull() else if (bounds.size == 1) low else null) ?: return null
            if (high < low) return null
            for (cpu in low..high) out += cpu
        }
        return out
    }
}
