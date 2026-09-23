package com.envi.wispr

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import androidx.test.platform.app.InstrumentationRegistry
import com.envi.wispr.audio.AudioCaptureService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Which interface each bind receives, across the real process boundary (#220). No microphone, no audio.
 *
 * The owner binds with `AudioCaptureService.takeBindIntent`: the take action and a fresh identifier. Android
 * hands a bind with the same intent identity the cached binder while the service lives, so this test keeps
 * a legacy bind open (the service stays alive, as a warm hold would keep it) and proves that two take
 * binds in a row get two different binders, each the take interface, while the actionless bind gets the
 * legacy one. That a late registration from the first take binder is refused is `ListenerSlotsTest`'s.
 *
 * REVERT: drop `setIdentifier` from `takeBindIntent`; the second take bind then gets the first's binder.
 */
class CaptureBindingDeviceTest {
    private val context: Context get() = InstrumentationRegistry.getInstrumentation().targetContext

    private fun bind(intent: Intent): Pair<IBinder, ServiceConnection> {
        val latch = CountDownLatch(1)
        var bound: IBinder? = null
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                bound = binder
                latch.countDown()
            }
            override fun onServiceDisconnected(name: ComponentName?) = Unit
        }
        assertTrue("the capture service must bind", context.bindService(intent, connection, Context.BIND_AUTO_CREATE))
        assertTrue("it must connect within ten seconds", latch.await(10, TimeUnit.SECONDS))
        return bound!! to connection
    }

    @Test
    fun eachTakeBindGetsItsOwnTakeBinderAndTheActionlessBindTheLegacyOne() {
        val (legacy, legacyConnection) = bind(Intent(context, AudioCaptureService::class.java))
        try {
            assertEquals("com.envi.wispr.audio.IAudioCaptureService", legacy.interfaceDescriptor)
            val (first, firstConnection) = bind(AudioCaptureService.takeBindIntent(context))
            assertEquals("com.envi.wispr.audio.IAudioTakeService", first.interfaceDescriptor)
            context.unbindService(firstConnection)
            val (second, secondConnection) = bind(AudioCaptureService.takeBindIntent(context))
            try {
                assertEquals("com.envi.wispr.audio.IAudioTakeService", second.interfaceDescriptor)
                assertNotSame("the second take bind got the first take's binder, so its epoch would be the first's", first, second)
            } finally {
                context.unbindService(secondConnection)
            }
        } finally {
            context.unbindService(legacyConnection)
        }
    }
}
