package com.envi.wispr.paste

import android.content.ComponentName
import android.content.Context
import android.provider.Settings

/**
 * Whether one of Android's accessibility shortcuts is assigned to our service (#475): the floating accessibility
 * button, the volume-key shortcut, or the gesture. Using any of them toggles the service, so auto-paste goes off on
 * every other use; a customer S22+ turned one on during setup by mistake.
 *
 * The three keys are hidden settings, but an app can read them: measured from the app's own process on Android 16,
 * targetSdk 36, 2026-10-24 (a hidden key that is not readable throws instead, as `accessibility_qs_targets` did).
 * A read that throws counts as not assigned, so a surface falls back to its general wording.
 */
internal object AccessibilityShortcut {
    /** Software (floating button), hardware (volume keys) and gesture shortcut targets, each a `:`-separated list. */
    val KEYS = listOf("accessibility_button_targets", "accessibility_shortcut_target_service", "accessibility_gesture_targets")

    fun assignedToUs(context: Context): Boolean {
        val component = ComponentName(context, PasteAccessibilityService::class.java)
        val ours = setOf(component.flattenToString(), component.flattenToShortString())
        return KEYS.any { key -> names(runCatching { Settings.Secure.getString(context.contentResolver, key) }.getOrNull(), ours) }
    }

    /** Whether [value], a `:`-separated component list, names one of [ours]; parsed as `AccessibilityPermission` does. */
    fun names(value: String?, ours: Set<String>): Boolean =
        value.orEmpty().split(':').map(String::trim).any { entry -> ours.any { it.equals(entry, ignoreCase = true) } }
}
