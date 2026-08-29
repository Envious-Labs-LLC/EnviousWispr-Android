package com.envi.wispr.debug

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.envi.wispr.paste.PasteAccessibilityService

/** Debug-only, silent UAT hook for verifying auto-paste against the previously focused field. */
class PasteProbeActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        intent.getStringExtra("expected_clipboard")?.let { expected ->
            Handler(Looper.getMainLooper()).postDelayed({
                val actual = clipboard.primaryClip
                    ?.getItemAt(0)
                    ?.coerceToText(this)
                    ?.toString()
                Log.i("PasteProbe", "clipboard_matches_expected=${actual == expected}")
                finish()
            }, 250L)
            return
        }

        intent.getStringExtra("previous_clipboard")?.let { previous ->
            clipboard.setPrimaryClip(ClipData.newPlainText("Previous test clipboard", previous))
        }
        val text = intent.getStringExtra("text") ?: "EnviousWispr auto-insert proof"
        val previousClipboard = clipboard.primaryClip
        finish()
        val scheduled = PasteAccessibilityService.pasteWhenTargetReturns(text, previousClipboard)
        if (!scheduled) {
            clipboard.setPrimaryClip(ClipData.newPlainText("EnviousWispr", text))
        }
        Log.i(
            "PasteProbe",
            "scheduled=$scheduled",
        )
    }
}
