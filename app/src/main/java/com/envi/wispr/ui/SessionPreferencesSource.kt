package com.envi.wispr.ui

import com.envi.wispr.audio.InputDevicePick
import com.envi.wispr.cleanup.CleanupOptions
import com.envi.wispr.insertion.ClipboardInsertionPolicy
import com.envi.wispr.polish.PolishPolicy
import com.envi.wispr.settings.AppPreferencesState
import com.envi.wispr.settings.cleanupOptions
import com.envi.wispr.settings.clipboardInsertionPolicy
import com.envi.wispr.vad.SilenceStopDetector
import com.envi.wispr.vocabulary.BuiltinVocabulary
import com.envi.wispr.vocabulary.CustomTerm
import com.envi.wispr.vocabulary.StructuredTermRestorer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicReference

/**
 * What one take runs with, frozen at its start (#69): a settings change applies from the next session.
 * Since #193 the capture and notice fields are frozen here too, so a settings emission landing after the
 * take's answer (a late first read, a mid-take change) can never move a take already decided.
 */
internal data class SessionPreferences(
    val cleanup: CleanupOptions = CleanupOptions(),
    val matcher: StructuredTermRestorer.Matcher = StructuredTermRestorer.compile(emptyList()),
    val clipboard: ClipboardInsertionPolicy = ClipboardInsertionPolicy(),
    /** Latched once per session; a settings change applies from the next session (issue #69). */
    val policy: PolishPolicy = PolishPolicy.Off,
    val autoStopOnSilence: Boolean = false,
    val silencePauseSeconds: Float = SilenceStopDetector.DEFAULT_PAUSE_SECONDS,
    val inputDevicePick: String = InputDevicePick.AUTO,
    val keepEarbudsReady: Boolean = true,
    val showBluetoothTips: Boolean = true,
)

/**
 * One reader's answer (#193). A reader is a limb: it may not have answered yet, it may have answered with
 * the user's values, or its read may have failed, in which case the take runs on the last successful
 * values (the defaults on a first run) and the failure is a typed fact of the take, never its ending.
 */
internal sealed interface PreferenceRead {
    /** Nothing has landed yet. */
    data object Pending : PreferenceRead

    /** The first emission landed; the values are the user's. */
    data object Fresh : PreferenceRead

    /**
     * The read failed. [reason] is a content-free token: `exception:<SimpleName>`,
     * `completed_without_value`, or `timed_out` (only ever set by the caller for its own take).
     */
    data class Failed(val reason: String) : PreferenceRead {
        companion object {
            const val COMPLETED_WITHOUT_VALUE = "completed_without_value"
            const val TIMED_OUT = "timed_out"
            /**
             * The class's simple name when it has the Throwable-name shape both vendors admit, else `Exception`, so a
             * `StoreFailure` or an anonymous class still reports a fallback instead of losing the field (#307).
             */
            fun exception(error: Throwable): Failed {
                val name = error.javaClass.simpleName
                return Failed("exception:" + if (THROWABLE_SHAPE.matches(name)) name else "Exception")
            }

            private val THROWABLE_SHAPE = Regex("\\A${com.envi.wispr.telemetry.SentrySchema.THROWABLE_NAME}\\z")
        }
    }
}

/**
 * The settings reader's snapshot: its outcome and its values in ONE immutable object, replaced whole, so
 * a take reading it sees an outcome and the values that came with it, never a torn pair.
 */
internal data class SettingsSnapshot(
    val read: PreferenceRead = PreferenceRead.Pending,
    val cleanupOptions: CleanupOptions = CleanupOptions(),
    /**
     * Null until `AppPreferences` delivers the user's real values, which on a cold start is AFTER the
     * listening notification is built, and null for good when the read failed before ever answering. A
     * `ClipboardInsertionPolicy()` stand-in here reads as a decided answer and its auto-copy default is
     * `true`, so the notification promised the clipboard to a user who had turned auto-copy off and whose
     * words went to History only (`validation-discipline.md` FACT: silent-empty-traps, plausible-value traps).
     */
    val clipboardPolicy: ClipboardInsertionPolicy? = null,
    val autoStopOnSilence: Boolean = false,
    val silencePauseSeconds: Float = SilenceStopDetector.DEFAULT_PAUSE_SECONDS,
    val inputDevicePick: String = InputDevicePick.AUTO,
    val showBluetoothTips: Boolean = true,
    val keepEarbudsReady: Boolean = true,
)

/** The vocabulary reader's snapshot, the same shape: outcome and values together. */
internal data class TermsSnapshot(
    val read: PreferenceRead = PreferenceRead.Pending,
    val structuredTerms: List<CustomTerm> = emptyList(),
)

/**
 * What a take starts with (#193): both readers' outcomes and the values that came with them, taken by ONE
 * atomic read of each reader's snapshot after the wait. The take is built from this alone; nothing rereads
 * the live source after suspending.
 */
internal data class PreferenceStart(
    val settings: SettingsSnapshot,
    val terms: TermsSnapshot,
) {
    /** Null on an ordinary take; otherwise which readers fell back and why, one token for the take's facts. */
    fun fallbackToken(): String? {
        val settingsFailed = settings.read as? PreferenceRead.Failed
        val termsFailed = terms.read as? PreferenceRead.Failed
        return when {
            settingsFailed != null && termsFailed != null -> "both:${settingsFailed.reason}:${termsFailed.reason}"
            settingsFailed != null -> "settings:${settingsFailed.reason}"
            termsFailed != null -> "terms:${termsFailed.reason}"
            else -> null
        }
    }
}

/**
 * The session owner's live view of the user's settings and custom words (#186): the two collectors that
 * ran in the Service's `onCreate`, each writing ONE atomic snapshot, and the two first-answer signals
 * `beginSession` awaits. The collectors are launched on the INJECTED scope and nothing else, so the
 * owner's `destroy()` cancels them exactly as cancelling the Service's scope did.
 *
 * Since #193 a reader that fails answers `Failed` and the take starts on the last successful values; the
 * first-answer signals exist only so an ordinary cold start still waits the milliseconds for the user's
 * REAL values (a take started on defaults before the first emission gave a user with auto-stop a manual
 * take after every cold start).
 */
internal class SessionPreferencesSource(
    /** `AppPreferences.authoritativeState` in production; a test feeds a flow of its own. */
    private val preferenceStates: Flow<AppPreferencesState>,
    /** `CustomTermRepository.observeTerms()` in production. */
    private val terms: Flow<List<CustomTerm>>,
    /** The legacy custom-term migration, run once before the terms are observed. */
    private val migrateLegacyTerms: suspend () -> Unit,
    private val log: SessionLog,
) {
    private val settingsSnapshot = AtomicReference(SettingsSnapshot())
    private val termsSnapshot = AtomicReference(TermsSnapshot())

    /** Completed once, by the collector, AFTER its snapshot holds a first answer (`Fresh` or `Failed`). */
    private val settingsAnswered = CompletableDeferred<Unit>()
    private val termsAnswered = CompletableDeferred<Unit>()

    /**
     * The LIVE clipboard policy for the listening notification, which is built before `beginSession`
     * freezes a snapshot; null means "not decided", which the notification must be able to say nothing
     * about rather than guess at (see [SettingsSnapshot.clipboardPolicy]).
     */
    val clipboardPolicy: ClipboardInsertionPolicy?
        get() = settingsSnapshot.get().clipboardPolicy

    /** Launches the two collectors as direct children of [scope]; creates no scope or job of its own. */
    fun start(scope: CoroutineScope) {
        scope.launch {
            try {
                try {
                    migrateLegacyTerms()
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    log.warn("Unable to migrate custom terms: ${error.javaClass.simpleName}")
                }
                terms.collect { userTerms ->
                    termsSnapshot.set(TermsSnapshot(PreferenceRead.Fresh, BuiltinVocabulary.withUserTerms(userTerms)))
                    termsAnswered.complete(Unit)
                }
                // A flow that completes before its first emission is a failed read, not a pending one; one
                // that completes after a value has answered and its values stand.
                if (termsSnapshot.get().read == PreferenceRead.Pending) {
                    termsFailed(PreferenceRead.Failed(PreferenceRead.Failed.COMPLETED_WITHOUT_VALUE))
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                // The snapshot first, then the line: a reader waiting on the line sees the failed answer.
                termsFailed(PreferenceRead.Failed.exception(error))
                log.warn("Unable to load custom terms: ${error.javaClass.simpleName}")
            }
        }
        scope.launch {
            try {
                preferenceStates.collect { preferences ->
                    settingsSnapshot.set(
                        SettingsSnapshot(
                            read = PreferenceRead.Fresh,
                            cleanupOptions = preferences.cleanupOptions(),
                            clipboardPolicy = preferences.clipboardInsertionPolicy(),
                            autoStopOnSilence = preferences.autoStopOnSilenceEnabled,
                            silencePauseSeconds = preferences.silencePauseSeconds,
                            inputDevicePick = preferences.inputDevicePick,
                            showBluetoothTips = preferences.showBluetoothTips,
                            keepEarbudsReady = preferences.keepEarbudsReady,
                        ),
                    )
                    settingsAnswered.complete(Unit)
                }
                if (settingsSnapshot.get().read == PreferenceRead.Pending) {
                    settingsFailed(PreferenceRead.Failed(PreferenceRead.Failed.COMPLETED_WITHOUT_VALUE))
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                settingsFailed(PreferenceRead.Failed.exception(error))
                log.warn("Unable to load cleanup preferences: ${error.javaClass.simpleName}")
            }
        }
    }

    /** The outcome changes; the values are the LAST successful ones (the defaults if none ever landed). */
    private fun settingsFailed(failed: PreferenceRead.Failed) {
        settingsSnapshot.updateAndGet { it.copy(read = failed) }
        settingsAnswered.complete(Unit)
    }

    private fun termsFailed(failed: PreferenceRead.Failed) {
        termsSnapshot.updateAndGet { it.copy(read = failed) }
        termsAnswered.complete(Unit)
    }

    /**
     * Waits under ONE deadline for both readers to answer, then takes ONE atomic read of each snapshot.
     * Never "not ready": a reader still [PreferenceRead.Pending] at that read is reported to THIS take as
     * `Failed(timed_out)` with the snapshot's values; the reader itself is not written, so a late first
     * emission still answers `Fresh` for a later take. Outcome and values always come from the same read.
     */
    suspend fun awaitAnswers(boundMs: Long): PreferenceStart {
        withTimeoutOrNull(boundMs) {
            settingsAnswered.await()
            termsAnswered.await()
        }
        val settings = settingsSnapshot.get()
        val terms = termsSnapshot.get()
        return PreferenceStart(
            settings = if (settings.read == PreferenceRead.Pending) settings.copy(read = PreferenceRead.Failed(PreferenceRead.Failed.TIMED_OUT)) else settings,
            terms = if (terms.read == PreferenceRead.Pending) terms.copy(read = PreferenceRead.Failed(PreferenceRead.Failed.TIMED_OUT)) else terms,
        )
    }

    /**
     * The take's snapshot, built from [start] alone. The matcher is an argument because `beginSession`
     * compiles it off the main thread from `start.terms`; the policy is the latched polish policy.
     */
    fun freeze(start: PreferenceStart, matcher: StructuredTermRestorer.Matcher, policy: PolishPolicy): SessionPreferences =
        SessionPreferences(
            cleanup = start.settings.cleanupOptions,
            matcher = matcher,
            // The stand-in for a read that never answered: today's null branch, kept on purpose (#193 plan
            // §14 weighs auto-copy on against off for this one case).
            clipboard = start.settings.clipboardPolicy ?: ClipboardInsertionPolicy(),
            policy = policy,
            autoStopOnSilence = start.settings.autoStopOnSilence,
            silencePauseSeconds = start.settings.silencePauseSeconds,
            inputDevicePick = start.settings.inputDevicePick,
            keepEarbudsReady = start.settings.keepEarbudsReady,
            showBluetoothTips = start.settings.showBluetoothTips,
        )
}
