package com.envi.wispr.ui

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build

/**
 * Whether the accessibility guide may float over Android Settings in picture-in-picture (#476).
 *
 * Android's own Allow dialog refuses a tap while another app's window overlaps it (AOSP
 * `AccessibilityServiceWarning.getTouchConsumingListener`), and an app cannot place its PiP window. So PiP is
 * offered only on Samsung software where a human tapped Allow with the guide floating and it worked. The key is
 * Samsung's raw One UI value (`Build.VERSION.SEM_PLATFORM_INT`, read by reflection), compared exactly: not the
 * Android version (several One UI releases share one), not the build fingerprint (it changes with every monthly
 * patch). Absent or unreadable means no PiP. The measurements behind each entry are in the #476 plan.
 */
internal object FloatingGuideEligibility {
    /** Raw `SEM_PLATFORM_INT` values measured with a human tap on Allow while the guide floated. */
    private val MEASURED_ONE_UI: Set<Int> = emptySet()

    fun allows(context: Context): Boolean = isSamsung() &&
        context.packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE) &&
        oneUiRaw()?.let { it in MEASURED_ONE_UI } == true

    fun isSamsung(): Boolean = Build.MANUFACTURER.equals("samsung", ignoreCase = true)

    /** Samsung's One UI value, or null on other makers and on any build where the field cannot be read. */
    fun oneUiRaw(): Int? = if (!isSamsung()) null else runCatching {
        Build.VERSION::class.java.getDeclaredField("SEM_PLATFORM_INT").getInt(null)
    }.getOrNull()?.takeIf { it > 0 }
}
