package com.envi.wispr.about

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.envi.wispr.telemetry.InstallSource

/**
 * The What's New page's way to updates (#38): the app's Google Play listing, where Play itself shows Update when a
 * newer version exists. No Play SDK is involved, so the app reads no update state of its own; the listing is the
 * answer. The listing is the production package's, whatever suffix a preview build carries.
 */
internal object PlayStoreLink {
    const val PACKAGE = "com.envi.wispr"
    const val MARKET_URL = "market://details?id=$PACKAGE"
    const val WEB_URL = "https://play.google.com/store/apps/details?id=$PACKAGE"

    /** The Play Store app: `market://` alone can open another store or a chooser, and then the web fallback never runs. */
    const val PLAY_STORE_PACKAGE = "com.android.vending"

    /**
     * Opens the listing in the Play Store app, else in a browser. False when neither opened: no Play Store and no
     * browser, or both refused.
     */
    fun open(context: Context): Boolean = listOf(MARKET_URL to PLAY_STORE_PACKAGE, WEB_URL to null).any { (url, app) ->
        runCatching {
            val view = Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(if (app != null) view.setPackage(app) else view)
        }.isSuccess
    }

    /**
     * The line under the button for a copy Google Play may not update, or null. It says what the phone reports, never
     * where the copy came from: the installer record can be cleared or reassigned. An installer we could not read
     * ([InstallSource.UNKNOWN]) says nothing, since nothing is known.
     */
    fun installNote(source: InstallSource): String? = when (source) {
        InstallSource.PLAY, InstallSource.UNKNOWN -> null
        InstallSource.GALAXY_STORE, InstallSource.PACKAGE_INSTALLER, InstallSource.NONE, InstallSource.OTHER ->
            "Your phone does not list Google Play as where this copy came from, so Google Play may not update it."
    }
}
