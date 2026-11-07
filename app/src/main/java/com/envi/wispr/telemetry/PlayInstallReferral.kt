package com.envi.wispr.telemetry

import android.content.Context
import com.android.installreferrer.api.InstallReferrerClient
import com.android.installreferrer.api.InstallReferrerStateListener
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Main-process analytics calls this once per process, in its own optional coroutine. No raw query is saved. */
internal suspend fun reportPlayInstallReferral(context: Context, capture: (AnalyticsEvent) -> Unit) = withContext(Dispatchers.IO) {
    val preferences = context.getSharedPreferences("install_referral", Context.MODE_PRIVATE)
    InstallReferralReporter(
        handled = { preferences.getBoolean("handled", false) },
        markHandled = { check(preferences.edit().putBoolean("handled", true).commit()) { "Referral marker write failed" } },
        read = {
            val client = InstallReferrerClient.newBuilder(context).build()
            readInstallReferral(object : InstallReferralConnection {
                override fun connect(setup: (Int) -> Unit, disconnected: () -> Unit) {
                    client.startConnection(object : InstallReferrerStateListener {
                        override fun onInstallReferrerSetupFinished(responseCode: Int) = setup(responseCode)
                        override fun onInstallReferrerServiceDisconnected() = disconnected()
                    })
                }

                override fun read(): InstallReferralRead.Received {
                    val details = client.installReferrer
                    return InstallReferralRead.Received(
                        InstallReferral.parse(details.installReferrer.orEmpty()),
                        details.referrerClickTimestampServerSeconds.takeIf { it > 0 },
                        details.installBeginTimestampServerSeconds.takeIf { it > 0 },
                    )
                }

                override fun close() = client.endConnection()
            })
        },
        capture = capture,
    ).report(InstallSource.of(context))
}
