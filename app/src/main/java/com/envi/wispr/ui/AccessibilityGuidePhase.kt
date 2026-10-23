package com.envi.wispr.ui

/** Whether this guide session has handed the user to Android Settings yet; selects the button copy only. */
internal sealed interface GuideHistory {
    data object NeverOpened : GuideHistory
    data object Opened : GuideHistory
}

/**
 * Where the accessibility guide is (#476). One value instead of `agreed`/`launchFailed` flags, so a state such
 * as "Settings opened before consent" cannot be represented. Permission and binding are observed separately
 * through `AutoPasteReadiness`; they are not phase fields.
 */
internal sealed interface GuidePhase {
    /** The prominent disclosure, before consent. */
    data object Disclosure : GuidePhase

    /** Consent given; the guide is on screen with Open Settings as its primary action. */
    data class Ready(val history: GuideHistory) : GuidePhase

    /** Open Settings was tapped and the hand-off is in progress. Transient: never restored as is. */
    data class Launching(val history: GuideHistory) : GuidePhase

    /** Android Settings is in front of the guide. */
    data object AwaitingSettings : GuidePhase

    /** Android Settings could not be opened. */
    data class LaunchFailed(val history: GuideHistory) : GuidePhase

    /** Auto-paste is live; the guide closes. */
    data object Complete : GuidePhase

    companion object {
        /** The phase a recreated guide resumes in. A hand-off in flight is not repeated by itself. */
        fun restored(saved: GuidePhase): GuidePhase = when (saved) {
            is Launching -> Ready(saved.history)
            Disclosure, is Ready, AwaitingSettings, is LaunchFailed, Complete -> saved
        }

        /** The phase after the user comes back to the guide while auto-paste is still not live. */
        fun returned(current: GuidePhase): GuidePhase = when (current) {
            AwaitingSettings -> Ready(GuideHistory.Opened)
            Disclosure, is Ready, is Launching, is LaunchFailed, Complete -> current
        }

        fun encode(phase: GuidePhase): String = when (phase) {
            Disclosure -> "disclosure"
            is Ready -> "ready:" + encode(phase.history)
            is Launching -> "launching:" + encode(phase.history)
            AwaitingSettings -> "awaiting"
            is LaunchFailed -> "failed:" + encode(phase.history)
            Complete -> "complete"
        }

        /** Null for anything this build did not write, which the caller treats as a fresh start. */
        fun decode(value: String?): GuidePhase? {
            val parts = value?.split(':') ?: return null
            val history = parts.getOrNull(1)?.let(::decodeHistory)
            return when (parts[0]) {
                "disclosure" -> Disclosure
                "ready" -> history?.let(::Ready)
                "launching" -> history?.let(::Launching)
                "awaiting" -> AwaitingSettings
                "failed" -> history?.let(::LaunchFailed)
                "complete" -> Complete
                else -> null
            }
        }

        private fun encode(history: GuideHistory): String = when (history) {
            GuideHistory.NeverOpened -> "never"
            GuideHistory.Opened -> "opened"
        }

        private fun decodeHistory(value: String): GuideHistory? = when (value) {
            "never" -> GuideHistory.NeverOpened
            "opened" -> GuideHistory.Opened
            else -> null
        }
    }
}
