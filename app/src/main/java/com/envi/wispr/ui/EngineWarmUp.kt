package com.envi.wispr.ui

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import com.envi.wispr.asr.AsrService
import com.envi.wispr.debug.DebugLogger
import com.envi.wispr.polish.IPolishService
import com.envi.wispr.polish.PolishService
import com.envi.wispr.providers.ProviderConfigurationRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Holds the speech and polish engines bound so their models load BEFORE the first practice take.
 *
 * Measured on the founder's S26 (polish-engines.md FACT: residency-measured-2026-09-01): the speech
 * model takes about 3.4 s to initialise cold and the polish model's first-ever GPU load compiles its
 * kernels for about 10.6 s. A new user meets both on the first dictation of setup, reads it as a slow
 * app, and the founder's phone pass of build 121 named that as the thing that makes people uninstall.
 * Binding the two services makes each load in its own process (`AsrService.onCreate` initialises the
 * recogniser; `warmUpWithPolicy` loads the polish model the user's policy needs), the same warm-at-
 * connect the session owner does, only earlier: while the user reads the permissions and grants them.
 *
 * Held only while setup's permissions and practice screens are on screen, then released; setup is the
 * user acting, not idle (`architecture-rules.md` RULE: no-idle-cost), and the session owner keeps both
 * models resident after a take anyway (RULE: isolate-limbs, #72).
 */
internal class EngineWarmUp(private val context: Context, private val scope: CoroutineScope) {
    private var speechBound = false
    private var polishBound = false

    private val speech = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            DebugLogger.log(TAG, "Speech engine warming for setup")
        }

        override fun onServiceDisconnected(name: ComponentName?) = Unit
    }

    private val polish = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val service = IPolishService.Stub.asInterface(binder)
            scope.launch {
                val policy = withContext(Dispatchers.IO) { ProviderConfigurationRepository(context).loadPolicy() }
                runCatching { service.warmUpWithPolicy(policy) }
                    .onFailure { error -> DebugLogger.warn(TAG, "Polish warm-up refused: ${error.javaClass.simpleName}") }
                DebugLogger.log(TAG, "Polish engine warming for setup")
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) = Unit
    }

    /** Idempotent: a second call while bound does nothing. */
    fun start() {
        if (speechBound || polishBound) return
        speechBound = runCatching {
            context.bindService(Intent(context, AsrService::class.java), speech, Context.BIND_AUTO_CREATE)
        }.getOrDefault(false)
        polishBound = runCatching {
            context.bindService(Intent(context, PolishService::class.java), polish, Context.BIND_AUTO_CREATE)
        }.getOrDefault(false)
    }

    fun stop() {
        if (speechBound) runCatching { context.unbindService(speech) }
        if (polishBound) runCatching { context.unbindService(polish) }
        speechBound = false
        polishBound = false
    }

    private companion object {
        const val TAG = "EngineWarmUp"
    }
}
