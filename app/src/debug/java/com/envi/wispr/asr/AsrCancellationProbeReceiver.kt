package com.envi.wispr.asr

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Non-exported same-UID debug observer registration, running in :asr. No engine command. */
class AsrCancellationProbeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val run = intent.getStringExtra("run") ?: return
        val take = intent.getStringExtra("take").orEmpty()
        val listener = intent.extras?.getBinder("listener")?.let(IAsrProbeListener.Stub::asInterface)
        AsrDiagnostics.subscribe(run, take, listener)
        resultCode = Activity.RESULT_OK
    }
}
