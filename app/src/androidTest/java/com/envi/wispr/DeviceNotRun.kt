package com.envi.wispr

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import com.envi.wispr.debug.DebugLogger
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue

/**
 * The one skip the silence device rows may take (#305): a harness restriction observed separately (a missing grant of a
 * permission the app requests). It logs `NOT RUN: <row>: <reason>` under the tag `DeviceNotRun` BEFORE it skips, because a bare
 * instrumentation report shows a skipped row as a pass; a direct run reads status `-3/-4` as NOT RUN and this line
 * for the reason.
 */
internal object DeviceNotRun {
    fun skipUnless(condition: Boolean, row: String, reason: String) {
        if (!condition) DebugLogger.warn("DeviceNotRun", "NOT RUN: $row: $reason")
        assumeTrue("NOT RUN: $row: $reason", condition)
    }

    /**
     * The one harness restriction observed separately: the app does not HOLD the microphone permission. That the
     * app REQUESTS it is the product's, so a manifest without it fails the row instead of skipping it.
     */
    fun requireMicrophone(context: Context, row: String) {
        val requested = context.packageManager
            .getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS)
            .requestedPermissions
        assertTrue("the app must declare RECORD_AUDIO", requested?.contains(Manifest.permission.RECORD_AUDIO) == true)
        skipUnless(
            context.packageManager.checkPermission(Manifest.permission.RECORD_AUDIO, context.packageName) == PackageManager.PERMISSION_GRANTED,
            row,
            "the app does not hold the microphone permission",
        )
    }
}
