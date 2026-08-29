package com.envi.wispr.ui

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.Gravity
import android.view.WindowManager
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.envi.wispr.paste.PasteAccessibilityService

/** Samsung side-button trampoline. It never owns a recording session or visible window. */
class VoiceInputActivity : Activity() {
    companion object {
        const val EXTRA_STOP = "stop"
        const val EXTRA_CANCEL = "cancel"
        const val EXTRA_TOGGLE = "toggle"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        configureLauncherWindow()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            Toast.makeText(this, "Allow microphone access in EnviousWispr first", Toast.LENGTH_LONG).show()
            startActivity(
                Intent(this, SettingsActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            )
            finishWithoutAnimation()
            return
        }

        val action = when {
            intent.getBooleanExtra(EXTRA_CANCEL, false) -> DictationSessionService.ACTION_CANCEL
            intent.getBooleanExtra(EXTRA_STOP, false) -> DictationSessionService.ACTION_STOP
            intent.getBooleanExtra(EXTRA_TOGGLE, false) -> DictationSessionService.ACTION_TOGGLE
            else -> DictationSessionService.ACTION_TOGGLE
        }
        if (action == DictationSessionService.ACTION_START ||
            action == DictationSessionService.ACTION_TOGGLE
        ) {
            PasteAccessibilityService.pinTargetForDictation()
        }
        runCatching { DictationSessionService.sendCommand(this, action) }
            .onFailure {
                Toast.makeText(this, "Dictation service could not start", Toast.LENGTH_LONG).show()
            }
        finishWithoutAnimation()
    }

    private fun configureLauncherWindow() {
        window.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        window.addFlags(
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
        )
        window.attributes = window.attributes.apply {
            width = 1
            height = 1
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = 0
        }
    }

    private fun finishWithoutAnimation() {
        finish()
        overridePendingTransition(0, 0)
    }
}
