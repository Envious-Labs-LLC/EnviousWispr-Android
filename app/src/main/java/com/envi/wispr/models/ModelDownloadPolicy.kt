package com.envi.wispr.models

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import androidx.work.Constraints
import androidx.work.NetworkType
import com.envi.wispr.settings.AppPreferences
import kotlinx.coroutines.flow.first

/**
 * Which network a model download may use (#108): ONE rule for every enqueue path, so no path can pick its own.
 *
 * The "Download models" setting is Wi-Fi only by default; "Wi-Fi or mobile data" lets every download use any
 * connection. A revision the user chose to "Download now" from the update prompt may use any connection too,
 * without changing the setting. Wi-Fi is a transport, not "unmetered": a metered Wi-Fi hotspot counts as Wi-Fi and
 * an unmetered mobile plan does not (plan §18.6, §19.3).
 */
internal object ModelDownloadPolicy {
    /** One revision of one model: the unit a prompt answer and a mobile-data approval belong to. */
    fun revisionKey(model: ModelDescriptor): String = "${model.id}@${model.pinnedRevision}"

    fun anyNetwork(settingAllowsMobileData: Boolean, approvedRevisions: Set<String>, model: ModelDescriptor): Boolean =
        settingAllowsMobileData || revisionKey(model) in approvedRevisions

    suspend fun anyNetwork(context: Context, model: ModelDescriptor): Boolean {
        val state = AppPreferences(context).authoritativeState.first()
        return anyNetwork(state.modelDownloadsOnMobileData, state.mobileDataApprovedRevisions, model)
    }

    /**
     * "Download now" (the prompt's, or the card's while waiting): this revision may use mobile data, the setting is
     * unchanged, and a download already waiting for Wi-Fi is queued again under the new rule.
     */
    suspend fun allowMobileData(context: Context, model: ModelDescriptor) {
        AppPreferences(context).answerUpdatePrompt(revisionKey(model), downloadNow = true)
        ModelDeliveryWorker.reapplyNetwork(context)
    }

    /**
     * The update prompt is owed once per revision: an update is pending, the phone is off Wi-Fi, this download may not
     * use mobile data, and the user has not answered for this revision.
     */
    fun promptOwed(updatePending: Boolean, onWifi: Boolean, anyNetwork: Boolean, answered: Set<String>, model: ModelDescriptor): Boolean =
        updatePending && !onWifi && !anyNetwork && revisionKey(model) !in answered

    /**
     * Wi-Fi only accepts the Wi-Fi transport, including Wi-Fi advertised by a VPN.
     * The worker opens the transfer on a validated Wi-Fi network.
     */
    fun constraints(anyNetwork: Boolean): Constraints {
        val builder = Constraints.Builder().setRequiresStorageNotLow(true)
        return if (anyNetwork) {
            builder.setRequiredNetworkType(NetworkType.CONNECTED).build()
        } else {
            builder.setRequiredNetworkRequest(wifiRequest(), NetworkType.UNMETERED).build()
        }
    }

    /** Whether WorkManager holds [constraints] to Wi-Fi: what a queued download is waiting for. */
    fun requiresWifi(constraints: Constraints): Boolean =
        constraints.requiredNetworkRequest?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true

    fun excludesVpn(constraints: Constraints): Boolean =
        requiresWifi(constraints) &&
            constraints.requiredNetworkRequest?.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN) == true

    /** Use the app's Wi-Fi VPN when available. Otherwise use the job's Wi-Fi network or find one. */
    @Suppress("DEPRECATION") // The synchronous fallback does not keep a callback after the worker ends.
    fun wifiNetwork(context: Context, scheduled: android.net.Network?): android.net.Network? = runCatching {
        val connectivity = context.getSystemService(ConnectivityManager::class.java) ?: return null
        fun usableWifi(network: android.net.Network): Boolean =
            connectivity.getNetworkCapabilities(network)?.let {
                it.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) &&
                    !it.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) &&
                    it.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                    it.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
            } == true
        val current = connectivity.activeNetwork
        if (current != null && connectivity.getNetworkCapabilities(current)
                ?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
        ) {
            return@runCatching current.takeIf(::usableWifi)
        }
        current?.takeIf(::usableWifi) ?: scheduled?.takeIf(::usableWifi)
            ?: connectivity.allNetworks.firstOrNull(::usableWifi)
    }.getOrNull()

    fun onWifi(context: Context): Boolean = runCatching {
        val connectivity = context.getSystemService(ConnectivityManager::class.java) ?: return false
        connectivity.getNetworkCapabilities(connectivity.activeNetwork)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
    }.getOrDefault(false)

    private fun wifiRequest(): NetworkRequest = NetworkRequest.Builder()
        .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
        .removeCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
        .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        .build()
}
