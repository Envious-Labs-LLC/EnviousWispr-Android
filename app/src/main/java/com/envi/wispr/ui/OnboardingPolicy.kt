package com.envi.wispr.ui

import com.envi.wispr.paste.AutoPasteAvailability

internal enum class OnboardingStage { WELCOME, DOWNLOADS, PERMISSIONS, PRACTICE }

/** Restored navigation never bypasses the facts required by the next screen. */
internal fun onboardingStage(step: Int, readiness: AppReadiness, autoPaste: AutoPasteAvailability): OnboardingStage {
    val requested = OnboardingStage.entries.getOrElse(step) { OnboardingStage.WELCOME }
    if (requested == OnboardingStage.WELCOME) return requested
    if (!readiness.requiredModelsReady) return OnboardingStage.DOWNLOADS
    if (requested == OnboardingStage.DOWNLOADS) return OnboardingStage.PERMISSIONS
    if (requested == OnboardingStage.PRACTICE &&
        (!readiness.microphoneGranted || autoPaste != AutoPasteAvailability.LIVE)
    ) return OnboardingStage.PERMISSIONS
    return requested
}

internal fun mergePracticeText(text: String, start: Int, end: Int, result: String): Pair<String, Int> {
    val left = minOf(start, end).coerceIn(0, text.length)
    val right = maxOf(start, end).coerceIn(left, text.length)
    val prefix = if (left > 0 && !text[left - 1].isWhitespace() && result.firstOrNull()?.isWhitespace() == false) " " else ""
    val suffix = if (right < text.length && !text[right].isWhitespace() && result.lastOrNull()?.isWhitespace() == false) " " else ""
    val inserted = prefix + result + suffix
    return text.replaceRange(left, right, inserted) to (left + inserted.length)
}
