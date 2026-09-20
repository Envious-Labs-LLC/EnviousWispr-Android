package com.envi.wispr.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Base64
import android.util.Log
import com.envi.wispr.paste.PasteAccessibilityService

/**
 * Debug-only fast eye for the test harness (#181). Hands back the accessibility tree the bound
 * `PasteAccessibilityService` holds, as the XML `uiautomator dump` writes, in the broadcast's result
 * data, so one `am broadcast` replaces a 1.9 s `uiautomator` process start.
 *
 * Result codes are the contract the harness reads:
 *   1  data holds the tree, base64 (base64 keeps the XML clear of `am`'s own quoting)
 *   2  the encoded tree is over [MAX_ENCODED_BYTES]; the harness falls back to `uiautomator`
 *   3  the service is not bound, or reading it threw; the harness falls back and retries later
 *   0  is what `am` reports when NO receiver answered, i.e. a release build; never set here
 *
 * Absent from release builds: this file lives in `src/debug/` and its `<receiver>` is declared only
 * in the debug manifest, gated by `android.permission.DUMP`, which the shell holds and no ordinary
 * app can be granted.
 *
 *   adb shell am broadcast -a com.envi.wispr.debug.DUMP com.envi.wispr
 */
class DebugDumpReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val xml = try {
            PasteAccessibilityService.windowTreeXml()
        } catch (failure: RuntimeException) {
            Log.w(TAG, "window tree read failed", failure)
            resultCode = RESULT_UNBOUND
            return
        }
        if (xml == null) {
            resultCode = RESULT_UNBOUND
            return
        }
        val encoded = Base64.encodeToString(xml.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
        if (encoded.length > MAX_ENCODED_BYTES) {
            Log.w(TAG, "window tree is ${encoded.length} bytes encoded; over the ${MAX_ENCODED_BYTES} cap")
            resultCode = RESULT_TOO_BIG
            return
        }
        resultData = encoded
        resultCode = RESULT_OK
    }

    companion object {
        private const val TAG = "DebugDump"
        const val RESULT_OK = 1
        const val RESULT_TOO_BIG = 2
        const val RESULT_UNBOUND = 3
        // The binder transaction buffer is 1 MB and SHARED across in-flight transactions; a quarter of
        // it is the conservative cap (review round 1). A normal screen is ~40 KB encoded.
        const val MAX_ENCODED_BYTES = 256 * 1024
    }
}
