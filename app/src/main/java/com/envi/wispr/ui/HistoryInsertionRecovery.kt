package com.envi.wispr.ui

import android.content.Context
import com.envi.wispr.history.TranscriptEntity
import com.envi.wispr.insertion.InsertionResults
import com.envi.wispr.paste.InsertionHandoff
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Pure policy and lightweight persistence for re-inserting a History dictation whose insertion
 * did not complete (#17).
 *
 * Persists in-flight recovery transcript ids so even a process kill between scheduling a retry
 * and saving its outcome still prompts the user to check the editor before pasting twice.
 */
internal object HistoryInsertionRecovery {
    private const val PREFS_NAME = "history_recovery_state"
    private const val KEY_UNCERTAIN_IDS = "uncertain_ids"

    internal data class RecoveryStartMark(
        val persisted: Boolean,
        val hadPriorUncertain: Boolean,
    )

    private val persistenceLock = Any()
    private val recoveredIdsState = MutableStateFlow<Set<Long>>(emptySet())
    private val unverifiedIdsState = MutableStateFlow<Set<Long>>(emptySet())
    @Volatile
    private var hydratedFromPrefs = false

    /**
     * Transcript ids whose recovery insertion has already been verified in this process.
     * Blocks an immediate second tap during the window before Room's Flow emits the updated row,
     * or if the History write queue refused the update.
     */
    val sessionRecoveredIds: StateFlow<Set<Long>> = recoveredIdsState.asStateFlow()

    /**
     * Transcript ids whose recovery insertion was started and either ended unverified or was
     * interrupted by process death before a definitive non-written outcome was recorded.
     */
    val sessionUnverifiedIds: StateFlow<Set<Long>> = unverifiedIdsState.asStateFlow()

    fun hydratePersistedUncertain(context: Context) {
        if (hydratedFromPrefs) return
        synchronized(persistenceLock) {
            if (hydratedFromPrefs) return
            val stored = readPersistedIdsLocked(context)
            if (stored.isNotEmpty()) {
                unverifiedIdsState.update { it + stored }
            }
            hydratedFromPrefs = true
        }
    }

    /**
     * Synchronously persists [id] via `commit()` under [persistenceLock] before the editor can
     * receive text so a process kill cannot erase the duplicate warning and concurrent cleanup
     * cannot clobber the set. Returns whether persistence succeeded and whether [id] already
     * carried an uncertain warning prior to this attempt.
     */
    fun recordRecoveryStarted(context: Context, id: Long): RecoveryStartMark {
        if (id <= 0L) return RecoveryStartMark(persisted = false, hadPriorUncertain = false)
        return synchronized(persistenceLock) {
            val existing = readPersistedIdsLocked(context)
            val alreadyUncertain = id in existing || id in unverifiedIdsState.value
            // Always commit to disk even if Android's in-memory SharedPreferences cache already holds
            // the id from a prior failed commit(), so a failed disk save can never be bypassed.
            val committed = writePersistedIdsLocked(context, existing + id)
            if (committed) {
                unverifiedIdsState.update { it + id }
            }
            RecoveryStartMark(persisted = committed, hadPriorUncertain = alreadyUncertain)
        }
    }

    /** Clears the persisted in-flight marker under [persistenceLock] once Room confirms the row update or a fresh non-written attempt ends. */
    fun clearPersistedUncertain(context: Context, id: Long) {
        if (id <= 0L) return
        synchronized(persistenceLock) {
            unverifiedIdsState.update { it - id }
            val updated = readPersistedIdsLocked(context) - id
            writePersistedIdsLocked(context, updated)
        }
    }

    fun markSessionRecovered(id: Long) {
        if (id > 0L) {
            recoveredIdsState.update { it + id }
            unverifiedIdsState.update { it - id }
        }
    }

    fun markSessionUnverified(id: Long) {
        if (id > 0L) unverifiedIdsState.update { it + id }
    }

    fun isSessionRecovered(id: Long): Boolean = id in recoveredIdsState.value

    internal fun resetSessionRecoveredForTest() {
        recoveredIdsState.value = emptySet()
        unverifiedIdsState.value = emptySet()
        hydratedFromPrefs = false
    }

    fun canRetry(
        transcript: TranscriptEntity,
        recoveredIds: Set<Long> = recoveredIdsState.value,
    ): Boolean {
        if (transcript.finalText.isBlank()) return false
        if (transcript.id in recoveredIds) return false
        if (transcript.status == TranscriptEntity.STATUS_DRAFT ||
            transcript.status == TranscriptEntity.STATUS_PROCESSING ||
            transcript.status == TranscriptEntity.STATUS_SAVED_UNROUTED ||
            transcript.status == TranscriptEntity.STATUS_READY_FOR_INSERTION
        ) {
            return false
        }
        if (transcript.insertionResult == "pending") return false
        return transcript.insertionResult != InsertionResults.COMMITTED &&
            transcript.insertionResult != InsertionResults.PASTED
    }

    /**
     * True whenever the words may already have reached the editor (an unverified write, unknown
     * delivery, teardown interruption, or an in-flight recovery surviving process death), so
     * History asks the user to check the editor before inserting again.
     */
    fun needsDuplicateCheck(
        transcript: TranscriptEntity,
        unverifiedIds: Set<Long> = unverifiedIdsState.value,
    ): Boolean = transcript.id in unverifiedIds ||
        transcript.insertionResult == InsertionResults.COPY_ONLY_UNVERIFIED ||
        transcript.insertionResult == InsertionResults.UNVERIFIED_NOT_COPIED ||
        transcript.insertionResult == InsertionResults.DELIVERY_UNKNOWN ||
        transcript.insertionResult == InsertionResults.INSERTION_INTERRUPTED ||
        transcript.insertionResult == InsertionResults.COPY_ONLY_INTERRUPTED ||
        transcript.insertionResult == InsertionResults.COPY_ONLY_SERVICE_DESTROYED ||
        transcript.insertionResult == InsertionResults.INSERTION_FAILED

    /**
     * Plain-English feedback for a recovery insertion attempt. Returns null when scheduled and
     * EnviousWispr is handing control straight back to the editor.
     */
    fun feedbackMessage(handoff: InsertionHandoff): String? = when (handoff) {
        InsertionHandoff.SCHEDULED -> null
        InsertionHandoff.NO_PINNED_TARGET ->
            "No editable field is focused. Open a text field in another app, then try again."
        InsertionHandoff.SERVICE_NOT_RUNNING ->
            "Auto-paste is not running. Turn it on in Permissions, or tap Copy."
        InsertionHandoff.SERVICE_DID_NOT_ANSWER ->
            "Auto-paste did not respond. Try again, or tap Copy."
        InsertionHandoff.INSERTION_ALREADY_PENDING ->
            "Another dictation or insertion is already in progress. Try again in a moment."
        InsertionHandoff.EMPTY_TEXT ->
            "Nothing to insert."
    }

    private fun readPersistedIdsLocked(context: Context): Set<Long> = runCatching {
        context.applicationContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getStringSet(KEY_UNCERTAIN_IDS, emptySet())
            .orEmpty()
            .mapNotNull { it.toLongOrNull() }
            .toSet()
    }.getOrDefault(emptySet())

    private fun writePersistedIdsLocked(context: Context, ids: Set<Long>): Boolean = runCatching {
        context.applicationContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putStringSet(KEY_UNCERTAIN_IDS, ids.map { it.toString() }.toSet())
            .commit()
    }.getOrDefault(false)
}
