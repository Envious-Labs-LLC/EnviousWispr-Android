package com.envi.wispr.ui

import android.app.AppOpsManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Process

/**
 * Whether the accessibility guide floats over Android Settings in picture-in-picture (#477).
 *
 * Offered on every phone whose software supports picture-in-picture and where the user has not switched it off for
 * this app. Measured 2026-11-02 on eight Firebase Test Lab phones (Pixel 10, Pixel 8a, Galaxy S24, A35, A16, OnePlus
 * Nord CE 3 Lite, OPPO A79, Motorola edge 50 fusion): Android's Allow dialog accepted a test-injected tap with the box
 * on screen, after dragging the box away where it covered Allow. A human-finger check is the founder's S26 pass. Plan: docs/feature-requests/issue-477-2026-11-02-floating-settings-guide.md.
 * A phone that refuses anyway keeps a way through: the box tells the user to close it, then tap Allow.
 */
internal object FloatingGuideEligibility {
    fun allows(context: Context): Boolean = decide(
        hasPictureInPicture = context.packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE),
        appOpMode = runCatching {
            context.getSystemService(AppOpsManager::class.java)
                .unsafeCheckOpNoThrow(AppOpsManager.OPSTR_PICTURE_IN_PICTURE, Process.myUid(), context.packageName)
        }.getOrDefault(AppOpsManager.MODE_ERRORED),
    )

    /** Floats only when the phone supports picture-in-picture and the app's permission reads exactly allowed. */
    fun decide(hasPictureInPicture: Boolean, appOpMode: Int): Boolean =
        hasPictureInPicture && appOpMode == AppOpsManager.MODE_ALLOWED
}
