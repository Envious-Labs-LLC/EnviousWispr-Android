package com.envi.wispr

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import com.envi.wispr.debug.DebugLogger
import org.junit.Assume.assumeTrue

/**
 * The one skip the silence device rows may take (#305): a harness restriction observed separately, never a product
 * failure. It logs `NOT RUN: <row>: <reason>` under the tag `DeviceNotRun` BEFORE it skips, because a bare
 * instrumentation report shows a skipped row as a pass; a direct run reads status `-3/-4` as NOT RUN and this line
 * for the reason.
 */
internal object DeviceNotRun {
    fun skipUnless(condition: Boolean, row: String, reason: String) {
        if (!condition) DebugLogger.warn("DeviceNotRun", "NOT RUN: $row: $reason")
        assumeTrue("NOT RUN: $row: $reason", condition)
    }

    /** The one harness restriction observed separately: the app does not hold the microphone permission. */
    fun requireMicrophone(context: Context, row: String) = skipUnless(
        context.packageManager.checkPermission(Manifest.permission.RECORD_AUDIO, context.packageName) == PackageManager.PERMISSION_GRANTED,
        row,
        "the app does not hold the microphone permission",
    )
}
