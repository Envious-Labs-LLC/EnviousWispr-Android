package com.envi.wispr.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.envi.wispr.telemetry.AppDefect
import com.envi.wispr.telemetry.Telemetry

/**
 * Debug-only probe of the telemetry pipe (issue #176, plan §11.1): raises the one defect that exists
 * for this purpose, `AppDefect.DebugProbe`, from the MAIN process, so a real Sentry event with this
 * install's tags can be looked up. It never captures an analytics row: PostHog rows come only from
 * the product's own emitters, so the row set on the server is never polluted by a rig.
 *
 * Absent from release builds: `src/debug/` source and the debug manifest only.
 *
 * Usage:
 *   adb shell am broadcast -a com.envi.wispr.debug.TELEMETRY_PROBE com.envi.wispr
 *   adb logcat -d | grep DebugTelemetry
 */
class DebugTelemetryReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val status = Telemetry.status()
        Telemetry.breadcrumb("debug", "probe", mapOf("source" to "broadcast"))
        Telemetry.defect(AppDefect.DebugProbe, mapOf("source" to "broadcast"))
        DebugLogger.log("DebugTelemetry", "probe raised: sentry=${status.sentry} posthog=${status.postHog} environment=${status.environment} identity=${status.installId != null}")
    }
}
