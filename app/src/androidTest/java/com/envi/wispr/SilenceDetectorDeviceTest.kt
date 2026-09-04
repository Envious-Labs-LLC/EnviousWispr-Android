package com.envi.wispr

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import androidx.test.platform.app.InstrumentationRegistry
import com.envi.wispr.vad.ISilenceVadService
import com.envi.wispr.vad.SilenceVadService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * The detector, on the real phone, in its real process, over its real binder interface.
 *
 * A test that called `SileroVadSession` directly would prove the mechanism and say nothing about the
 * wiring, and it would run in the default process where a different set of things happen to be
 * initialised. This binds the service, so the model loads where it actually ships.
 *
 * It needs no screen, which is why it can run while the phone is locked.
 */
class SilenceDetectorDeviceTest {

    private val context: Context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val blockBytes = 8_192

    private fun bind(): Pair<ISilenceVadService, ServiceConnection> {
        val latch = CountDownLatch(1)
        var service: ISilenceVadService? = null
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                service = ISilenceVadService.Stub.asInterface(binder)
                latch.countDown()
            }
            override fun onServiceDisconnected(name: ComponentName?) { service = null }
        }
        assertTrue(
            "the detector service must bind",
            context.bindService(
                Intent(context, SilenceVadService::class.java),
                connection,
                Context.BIND_AUTO_CREATE,
            ),
        )
        assertTrue("it must connect within ten seconds", latch.await(10, TimeUnit.SECONDS))
        return requireNonNull(service) to connection
    }

    private fun requireNonNull(s: ISilenceVadService?): ISilenceVadService {
        assertTrue("connected", s != null)
        return s!!
    }

    private fun silentBlock() = ByteArray(blockBytes)

    /** The recorded speech fixture, pushed to this package's cache by the caller. */
    private fun speechBlocks(): List<ByteArray> {
        val fixture = File(context.cacheDir, "enviouswispr-uat.pcm")
        assumeTrue("the speech fixture must be present", fixture.isFile && fixture.length() > blockBytes)
        val bytes = fixture.readBytes()
        return (0 until bytes.size / blockBytes).map {
            bytes.copyOfRange(it * blockBytes, (it + 1) * blockBytes)
        }
    }

    @Test
    fun theRealModelLoadsInItsOwnProcessAndScoresRealSpeech() {
        val (service, connection) = bind()
        try {
            val token = System.nanoTime()
            assertEquals(
                "the bundled model must load on this phone",
                SilenceVadService.STATUS_READY,
                service.start(token, 1.5f),
            )

            // Real recorded speech must not read as the end of a take.
            val speech = speechBlocks()
            speech.forEach { block ->
                assertEquals(
                    "speech must never end the take",
                    SilenceVadService.RESULT_CONTINUE,
                    service.processBlock(token, block),
                )
            }

            // Then silence. Seven below-threshold blocks is the nominal wait at 1.5 seconds, so a
            // generous margin here still proves it stops rather than that it stops on time.
            var stoppedAt = -1
            for (i in 0 until 40) {
                if (service.processBlock(token, silentBlock()) == SilenceVadService.RESULT_SILENCE) {
                    stoppedAt = i + 1
                    break
                }
            }
            assertTrue("silence after speech must end the take, it did not in 40 blocks", stoppedAt > 0)
            assertTrue("and it must not end on the very first silent block", stoppedAt > 1)
            service.finish(token)
        } finally {
            context.unbindService(connection)
        }
    }

    @Test
    fun aSilentRoomNeverEndsATakeThatNeverStarted() {
        // The two-way control. Without it, a fixture that fails to play looks exactly like the feature
        // working: the phone hears silence, and a terminal-reason check alone would pass having tested
        // nothing at all.
        val (service, connection) = bind()
        try {
            val token = System.nanoTime()
            assertEquals(SilenceVadService.STATUS_READY, service.start(token, 0.5f))
            repeat(80) {
                assertEquals(
                    "silence with no speech before it must never end a take",
                    SilenceVadService.RESULT_CONTINUE,
                    service.processBlock(token, silentBlock()),
                )
            }
            service.finish(token)
        } finally {
            context.unbindService(connection)
        }
    }

    @Test
    fun aBlockFromAnOlderTakeIsRefused() {
        val (service, connection) = bind()
        try {
            val older = System.nanoTime()
            val newer = older + 1_000_000
            assertEquals(SilenceVadService.STATUS_READY, service.start(newer, 1.5f))
            assertEquals(
                "a start from an older take must be refused outright",
                SilenceVadService.STATUS_UNAVAILABLE,
                service.start(older, 1.5f),
            )
            assertEquals(
                "and so must its blocks",
                SilenceVadService.RESULT_UNAVAILABLE,
                service.processBlock(older, silentBlock()),
            )
            assertEquals(
                "while the newer take carries on working",
                SilenceVadService.RESULT_CONTINUE,
                service.processBlock(newer, silentBlock()),
            )
            service.finish(newer)
        } finally {
            context.unbindService(connection)
        }
    }

    @Test
    fun aWronglySizedBlockIsRefusedRatherThanScored() {
        val (service, connection) = bind()
        try {
            val token = System.nanoTime()
            assertEquals(SilenceVadService.STATUS_READY, service.start(token, 1.5f))
            assertEquals(
                "a short block is a broken contract, not quiet audio",
                SilenceVadService.RESULT_UNAVAILABLE,
                service.processBlock(token, ByteArray(1_024)),
            )
            service.finish(token)
        } finally {
            context.unbindService(connection)
        }
    }
}
