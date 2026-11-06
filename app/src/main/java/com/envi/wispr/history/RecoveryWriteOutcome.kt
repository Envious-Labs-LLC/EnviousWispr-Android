package com.envi.wispr.history

internal enum class RecoveryWriteFailure { QUEUE_REJECTED, WRITE_FAILED }

/** Actual database answers; pending work is represented by its unanswered receipt. */
internal sealed interface RecoverySaveOutcome {
    data class Saved(val id: Long) : RecoverySaveOutcome { init { require(id > 0L) } }
    data class PreservedExisting(val id: Long) : RecoverySaveOutcome { init { require(id > 0L) } }
    data object Deleted : RecoverySaveOutcome
    data class Failed(val reason: RecoveryWriteFailure) : RecoverySaveOutcome
}

internal sealed interface RecoveryDiscardOutcome {
    data object Committed : RecoveryDiscardOutcome
    data class Unconfirmed(val reason: RecoveryWriteFailure) : RecoveryDiscardOutcome
}
