package com.envi.wispr.telemetry

import com.android.installreferrer.api.InstallReferrerClient.InstallReferrerResponse
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull

internal sealed class InstallReferralRead {
    data class Received(val referral: InstallReferral, val clickSeconds: Long?, val installSeconds: Long?) : InstallReferralRead()
    data object Unsupported : InstallReferralRead()
    data object RetryLater : InstallReferralRead()
}

/** One official SDK connection; production reads it on IO, with no dependency on an Activity or dictation. */
internal interface InstallReferralConnection {
    fun connect(setup: (Int) -> Unit, disconnected: () -> Unit)
    fun read(): InstallReferralRead.Received
    fun close()
}

internal suspend fun readInstallReferral(connection: InstallReferralConnection): InstallReferralRead {
    return try {
        val response = withTimeoutOrNull(5_000) {
            suspendCancellableCoroutine { continuation ->
                val answered = AtomicBoolean()
                val answer: (Int) -> Unit = { code ->
                    if (answered.compareAndSet(false, true)) continuation.resume(code)
                }
                connection.connect(answer) { answer(InstallReferrerResponse.SERVICE_DISCONNECTED) }
            }
        }
        when (response) {
            InstallReferrerResponse.OK -> connection.read()
            InstallReferrerResponse.FEATURE_NOT_SUPPORTED -> InstallReferralRead.Unsupported
            else -> InstallReferralRead.RetryLater
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        InstallReferralRead.RetryLater
    } finally {
        runCatching { connection.close() }
    }
}

/** A best-effort snapshot, including older installs. The flag means handled, never acknowledged by PostHog. */
internal class InstallReferralReporter(
    private val handled: () -> Boolean,
    private val markHandled: () -> Unit,
    private val read: suspend () -> InstallReferralRead,
    private val capture: (AnalyticsEvent.InstallReferrerReceived) -> Unit,
) {
    suspend fun report(source: InstallSource) {
        if (source != InstallSource.PLAY || handled()) return
        when (val result = read()) {
            is InstallReferralRead.Received -> {
                capture(AnalyticsEvent.InstallReferrerReceived(result.referral, result.clickSeconds, result.installSeconds))
                markHandled()
            }
            InstallReferralRead.Unsupported -> markHandled()
            InstallReferralRead.RetryLater -> Unit // No timer/reconnect: a later main-process start can try again.
        }
    }
}
