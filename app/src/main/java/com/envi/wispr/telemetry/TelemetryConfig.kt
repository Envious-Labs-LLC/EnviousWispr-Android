package com.envi.wispr.telemetry

import android.app.Application
import android.content.Context
import android.content.pm.ApplicationInfo
import com.envi.wispr.BuildConfig
import java.util.UUID

/**
 * The build-owned facts every row and every Sentry event carries (issue #176): read once per process
 * from the package and `BuildConfig`, never from a preference. `environment` is `development` on a
 * debuggable build and `production` otherwise, so the founder's Play builds are production dogfood, as on
 * the Mac. `processRunId` is one UUID per process start: the journal keys interruption on it.
 */
class TelemetryConfig(
    val versionName: String,
    val appBuild: Int,
    val environment: String,
    val processName: String,
    val isMainProcess: Boolean,
    val processRunId: String,
) {
    /** Sentry's release string, package-prefixed like the Mac's (`com.enviouswispr.app@2.4.3`). */
    val release: String get() = "${BuildConfig.APPLICATION_ID}@$versionName+$appBuild"

    /** `main`, `audio`, `asr`, `vad`, `polish`: the closed set the manifest declares, as a tag value. */
    val processTag: String get() = if (isMainProcess) "main" else processName.substringAfterLast(':')

    companion object {
        const val PRODUCTION = "production"
        const val DEVELOPMENT = "development"

        fun read(context: Context): TelemetryConfig {
            val debuggable = (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
            val processName = Application.getProcessName()
            return TelemetryConfig(
                versionName = BuildConfig.VERSION_NAME,
                appBuild = BuildConfig.VERSION_CODE,
                environment = if (debuggable) DEVELOPMENT else PRODUCTION,
                processName = processName,
                isMainProcess = processName == context.packageName,
                processRunId = UUID.randomUUID().toString().lowercase(),
            )
        }
    }
}
