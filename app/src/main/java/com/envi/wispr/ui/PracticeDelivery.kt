package com.envi.wispr.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.ResultReceiver
import androidx.core.content.ContextCompat
import java.util.concurrent.atomic.AtomicBoolean

/** An explicit result destination for one accepted practice take. Never targets another app. */
internal class PracticeDelivery(val token: String, private val send: (Int, String) -> Unit) {
    private val terminal = AtomicBoolean(false)

    fun acceptsCommand(candidate: String): Boolean = candidate == token && !terminal.get()

    fun update(code: Int, text: String = "", terminalEvent: Boolean = false) {
        if (terminalEvent) {
            if (!terminal.compareAndSet(false, true)) return
        } else if (terminal.get()) return
        runCatching {
            send(code, text)
        }
    }

    companion object {
        const val TOKEN = "practice_token"
        const val RECEIVER = "practice_receiver"
        const val TEXT = "practice_text"
        const val STARTING = 1
        const val RECORDING = 2
        const val PROCESSING = 3
        const val FINISHED = 4
        const val ENDED = 5
        const val ERROR = 6

        fun start(context: Context, token: String, receiver: ResultReceiver) {
            ContextCompat.startForegroundService(context, Intent(context, DictationSessionService::class.java)
                .setAction(DictationSessionService.ACTION_START)
                .putExtra(TOKEN, token).putExtra(RECEIVER, receiver))
        }

        fun command(context: Context, token: String, action: String) {
            context.startService(Intent(context, DictationSessionService::class.java).setAction(action).putExtra(TOKEN, token))
        }

        @Suppress("DEPRECATION")
        fun from(intent: Intent?): PracticeDelivery? {
            val token = intent?.getStringExtra(TOKEN)?.takeIf { it.length in 1..100 } ?: return null
            val receiver = intent.getParcelableExtra<ResultReceiver>(RECEIVER) ?: return null
            return PracticeDelivery(token) { code, text ->
                receiver.send(code, Bundle().apply { putString(TOKEN, token); putString(TEXT, text) })
            }
        }
    }
}
