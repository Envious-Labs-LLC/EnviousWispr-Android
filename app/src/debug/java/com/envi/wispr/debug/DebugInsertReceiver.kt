package com.envi.wispr.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.envi.wispr.paste.PasteAccessibilityService

/**
 * Debug-only, no-audio insertion rig for synthetic UAT (#141). A broadcast does NOT steal input focus,
 * so it can pin the editor that is focused right now and then insert into it, reproducing the real
 * two-step dictation handshake (pin at record start, insert when text is ready) with a fixed sentence
 * and no microphone or speech recognition.
 *
 * Absent from release builds: this file lives in `src/debug/` and its `<receiver>` is declared only in
 * the debug manifest, so it cannot be compiled into or triggered on a Play build.
 *
 * Usage: focus a text field, then
 *   adb shell am broadcast -a com.envi.wispr.debug.INSERT --es text "your sentence" com.envi.wispr
 * Read the outcome with the tag below:
 *   adb logcat -d | grep DebugInsert
 */
class DebugInsertReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val text = intent.getStringExtra("text") ?: "EnviousWispr debug insert"
        val pin = PasteAccessibilityService.pinTargetForDictation()
        val handoff = PasteAccessibilityService.pasteWhenTargetReturns(0L, text)
        Log.i("DebugInsert", "pin=$pin handoff=$handoff text=\"$text\"")
    }
}
