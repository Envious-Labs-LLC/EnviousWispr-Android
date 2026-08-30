package com.envi.wispr.paste

import android.content.ComponentName
import android.content.Context
import android.provider.Settings

/**
 * The one reader of the Android permission fact for auto-paste.
 *
 * Permission only. It cannot answer liveness: the setting string still names a service that has
 * crashed, which is issue #16. `PasteAccessibilityService.isBound` answers that half, and
 * `AutoPasteReadiness.evaluate` combines them.
 */
object AccessibilityPermission {

    fun isGranted(context: Context): Boolean {
        val component = ComponentName(context, PasteAccessibilityService::class.java)
        val flattened = component.flattenToString()
        val flattenedShort = component.flattenToShortString()
        return Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
        ).orEmpty()
            .split(':')
            .map(String::trim)
            .any { it.equals(flattened, ignoreCase = true) || it.equals(flattenedShort, ignoreCase = true) }
    }
}
