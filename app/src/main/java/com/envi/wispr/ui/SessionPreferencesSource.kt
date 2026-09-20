package com.envi.wispr.ui

import com.envi.wispr.audio.InputDevicePick
import com.envi.wispr.cleanup.CleanupOptions
import com.envi.wispr.insertion.ClipboardInsertionPolicy
import com.envi.wispr.polish.PolishPolicy
import com.envi.wispr.settings.AppPreferences
import com.envi.wispr.settings.cleanupOptions
import com.envi.wispr.settings.clipboardInsertionPolicy
import com.envi.wispr.vad.SilenceStopDetector
import com.envi.wispr.vocabulary.BuiltinVocabulary
import com.envi.wispr.vocabulary.CustomTerm
import com.envi.wispr.vocabulary.CustomTermRepository
import com.envi.wispr.vocabulary.StructuredTermRestorer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * What one take runs with, frozen at its start (#69): a settings change applies from the next session.
 */
internal data class SessionPreferences(
    val cleanup: CleanupOptions = CleanupOptions(),
    val terms: List<CustomTerm> = emptyList(),
    val matcher: StructuredTermRestorer.Matcher = StructuredTermRestorer.compile(emptyList()),
    val clipboard: ClipboardInsertionPolicy = ClipboardInsertionPolicy(),
    /** Latched once per session; a settings change applies from the next session (issue #69). */
    val policy: PolishPolicy = PolishPolicy.Off,
)

/**
 * The session owner's live view of the user's settings and custom words (#186): the two collectors that
 * ran in the Service's `onCreate`, the fields they write, and the two readiness signals `beginSession`
 * awaits. The collectors are launched on the INJECTED scope and nothing else, so the owner's `destroy()`
 * cancels them exactly as cancelling the Service's scope did.
 *
 * The fields are `@Volatile` because IO collectors write them and main or a worker reads them later; the
 * readiness deferreds publish the first snapshot, but later emissions still need visibility.
 */
internal class SessionPreferencesSource(
    private val appPreferences: AppPreferences,
    private val customTerms: CustomTermRepository,
    /** The legacy custom-term migration, run once before the terms are observed. */
    private val migrateLegacyTerms: suspend () -> Unit,
    private val log: SessionLog,
) {
    @Volatile var structuredTerms: List<CustomTerm> = emptyList()
        private set

    @Volatile var cleanupOptions = CleanupOptions()
        private set

    /**
     * Null until `AppPreferences` delivers the user's real values, which on a cold start is AFTER
     * the listening notification is built. A `ClipboardInsertionPolicy()` stand-in here reads as a
     * decided answer and its auto-copy default is `true`, so the notification promised the
     * clipboard to a user who had turned auto-copy off and whose words went to History only
     * (`validation-discipline.md` FACT: silent-empty-traps, plausible-value traps).
     */
    @Volatile var clipboardPolicy: ClipboardInsertionPolicy? = null
        private set

    /**
     * Frozen at the moment a take starts, never read again during it. That is the same contract macOS
     * uses, and it is why changing the slider mid-dictation does not move the goalposts under you.
     *
     * Written in the same collector block as the cleanup options, BEFORE its readiness signal completes,
     * because `beginSession` awaits that signal before binding anything. Written anywhere else and a
     * user who had enabled auto-stop would silently get a manual take after every cold start.
     */
    @Volatile var autoStopOnSilence = false
        private set

    @Volatile var silencePauseSeconds = SilenceStopDetector.DEFAULT_PAUSE_SECONDS
        private set

    /** The stored pick, frozen per take like the silence setting; crosses the binder as a string. */
    @Volatile var inputDevicePick = InputDevicePick.AUTO
        private set

    @Volatile var showBluetoothTips = true
        private set

    /** The 30 s earbud hold, frozen per take and carried on the start call. */
    @Volatile var keepEarbudsReady = true
        private set

    private val cleanupPreferencesReady = CompletableDeferred<Unit>()
    private val structuredTermsReady = CompletableDeferred<Unit>()

    /** Launches the two collectors as direct children of [scope]; creates no scope or job of its own. */
    fun start(scope: CoroutineScope) {
        scope.launch {
            try {
                try {
                    migrateLegacyTerms()
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    log.warn("Unable to migrate custom terms: ${error.message}")
                }
                customTerms.observeTerms().collect { terms ->
                    structuredTerms = BuiltinVocabulary.withUserTerms(terms)
                    structuredTermsReady.complete(Unit)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                log.warn("Unable to load custom terms: ${error.message}")
            }
        }
        scope.launch {
            try {
                appPreferences.authoritativeState.collect { preferences ->
                    cleanupOptions = preferences.cleanupOptions()
                    clipboardPolicy = preferences.clipboardInsertionPolicy()
                    autoStopOnSilence = preferences.autoStopOnSilenceEnabled
                    silencePauseSeconds = preferences.silencePauseSeconds
                    inputDevicePick = preferences.inputDevicePick
                    showBluetoothTips = preferences.showBluetoothTips
                    keepEarbudsReady = preferences.keepEarbudsReady
                    cleanupPreferencesReady.complete(Unit)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                log.warn("Unable to load cleanup preferences: ${error.message}")
            }
        }
    }

    /** True when both readiness signals completed within [timeoutMs]; false when either never did. */
    suspend fun awaitReady(timeoutMs: Long): Boolean =
        withTimeoutOrNull(timeoutMs) {
            cleanupPreferencesReady.await()
            structuredTermsReady.await()
            true
        } == true

    /**
     * The take's snapshot. The terms and the matcher are arguments because `beginSession` snapshots the
     * terms BEFORE compiling the matcher off the main thread; re-reading them here would change which
     * terms a take runs with if the collector emitted in between.
     */
    fun freeze(termsSnapshot: List<CustomTerm>, matcher: StructuredTermRestorer.Matcher, policy: PolishPolicy): SessionPreferences =
        SessionPreferences(
            cleanup = cleanupOptions,
            terms = termsSnapshot,
            matcher = matcher,
            // Non-null by construction: cleanupPreferencesReady, awaited by the caller, is
            // completed only after the line that writes this field.
            clipboard = clipboardPolicy ?: ClipboardInsertionPolicy(),
            policy = policy,
        )
}
