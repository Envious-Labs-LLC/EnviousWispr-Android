package com.envi.wispr

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.envi.wispr.asr.AsrFailureReason
import com.envi.wispr.asr.AsrService
import com.envi.wispr.asr.IAsrService
import com.envi.wispr.ui.PipelineBindings
import com.envi.wispr.ui.SpeechListener
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * A speech callback through the REAL `:asr` binder reaches the owner's listener on main, after the binder call
 * returned, never on the binder thread that received it (#253). The production proxy (`PipelineBindings
 * .speechProxy`) is bound to the real `AsrService` and asked for a file that does not exist; its typed failure
 * (`AUDIO_MISSING`) is the callback. Main is held while the request is made, so a listener that ran on the binder
 * thread would be seen before main is released.
 * REVERT: call the listener directly in `PostingSpeechListener` (the failure then arrives while main is held,
 * on a binder thread).
 */
@RunWith(AndroidJUnit4::class)
class SpeechCallbackDeviceTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun aTypedSpeechFailureReachesTheListenerOnMainAfterTheBinderCallReturned() {
        val connected = CountDownLatch(1)
        var service: IAsrService? = null
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                service = IAsrService.Stub.asInterface(binder)
                connected.countDown()
            }

            override fun onServiceDisconnected(name: ComponentName?) = Unit
        }
        context.bindService(Intent(context, AsrService::class.java), connection, Context.BIND_AUTO_CREATE)
        try {
            assertTrue("AsrService did not connect", connected.await(15, TimeUnit.SECONDS))
            val main = Handler(Looper.getMainLooper())
            // The failure has crossed the binder once the proxy posts it (or, reverted, once the listener runs).
            val crossed = CountDownLatch(1)
            val speech = PipelineBindings.speechProxy(checkNotNull(service)) { task -> crossed.countDown(); main.post(task) }

            val heard = CountDownLatch(1)
            var reason = -1
            var onMain = false
            var heardWhileMainHeld = false
            val release = CountDownLatch(1)
            val mainHeld = CountDownLatch(1)
            main.post { mainHeld.countDown(); release.await(10, TimeUnit.SECONDS) }
            assertTrue("main never took the hold", mainHeld.await(10, TimeUnit.SECONDS))

            val listener = object : SpeechListener {
                override fun onResult(text: String?) = record(-2)
                override fun onError(message: String?) = record(-3)
                override fun onFailure(reason: Int, detail: String?) = record(reason)
                private fun record(code: Int) {
                    heardWhileMainHeld = release.count > 0
                    crossed.countDown()
                    reason = code
                    onMain = Looper.myLooper() == Looper.getMainLooper()
                    heard.countDown()
                }
            }
            speech.transcribeFileForTake(context.cacheDir.resolve("no-such-take.pcm").path, "take-253", listener)
            assertTrue("the failure never crossed the binder", crossed.await(15, TimeUnit.SECONDS))
            release.countDown()
            assertTrue("the failure never reached the listener", heard.await(15, TimeUnit.SECONDS))
            assertTrue("the listener ran while main was held, so not through main", !heardWhileMainHeld)
            assertTrue("the listener ran on the main looper", onMain)
            assertEquals("the typed failure", AsrFailureReason.AUDIO_MISSING.code, reason)
        } finally {
            context.unbindService(connection)
        }
    }
}
