package com.envi.wispr.polish

import java.io.File

/**
 * This process's major page faults so far: page reads the kernel had to fetch from storage (#465). Logged around the
 * S1-mini load, so a slow reload can be told apart: tens of thousands of major faults mean the model's pages were
 * evicted from the page cache and read back from flash; a handful mean the time went elsewhere.
 *
 * A count, never content. Null when `/proc/self/stat` cannot be read or parsed (a release build is not dumpable, and
 * some `/proc/self` files then belong to root, as `S1RuntimeKind` measured for `auxv`), which the log reports as unknown.
 */
internal object MajorFaults {
    fun read(): Long? = try { parse(File("/proc/self/stat").readText()) } catch (error: Exception) { null }

    /**
     * `majflt` is field 12 of `proc_pid_stat(5)`. Field 2, the command name in parentheses, may itself hold spaces or
     * parentheses, so the fields are counted from after the LAST `)`, where field 3 (the state) begins.
     */
    internal fun parse(stat: String): Long? {
        val close = stat.lastIndexOf(')')
        if (close < 0) return null
        val fields = stat.substring(close + 1).trim().split(' ')
        return fields.getOrNull(MAJFLT_FIELD - FIRST_FIELD_AFTER_NAME)?.toLongOrNull()?.takeIf { it >= 0 }
    }

    private const val MAJFLT_FIELD = 12
    private const val FIRST_FIELD_AFTER_NAME = 3
}
