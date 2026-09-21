package com.envi.wispr.audio

import android.os.RemoteException
import com.envi.wispr.debug.DebugLogger
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.LockSupport

/**
 * The recorder's live picture for ONE take: the ring the capture thread offers into, the analyser thread
 * that drains it, the published bands, and the push to the one registered listener (#151, #187).
 *
 * Owned by the take's `CaptureSession` and closed exactly once by the service (#188). The listener slot is
 * the binding's, not the take's, so it is handed in. All of it belongs to THIS take: a thread that
 * outlives its take writes into a dead take's array, and the getter reads the live one.
 *
 * [interrupt] is the one platform edge, injected so a JVM test can count that [close] interrupts once.
 */
internal class PicturePublisher(
    private val listener: AtomicReference<IAudioSpectrumListener?>,
    private val tag: String,
    private val interrupt: (Thread) -> Unit = { it.interrupt() },
) {
    companion object {
        /** Eight chunks of picture backlog, 256 ms: the analyser drains it every wake, so it never fills in practice. */
        const val SPECTRUM_RING_CHUNKS = 8

        /** The analyser's longest sleep: it re-checks whether its take is over at least this often, unpark or not. */
        const val ANALYSER_PARK_NS = 50_000_000L
    }

    /**
     * The picture path. The capture thread offers every read here with its position; the analyser
     * thread drains it and publishes into [publishedBands] under [bandsLock], which the binder getter
     * shares and the capture thread never touches.
     */
    private val spectrumRing = BlockRing(SPECTRUM_RING_CHUNKS, PcmAudio.READ_CHUNK_BYTES)
    private val publishedBands = FloatArray(SpectrumAnalyzer.BAND_COUNT)
    private val bandsLock = Any()
    @Volatile private var analyserThread: Thread? = null
    private val closed = AtomicBoolean(false)

    /**
     * How the picture left this take: pushes to the registered listener, and polls of the legacy
     * getter. Shape only, logged once at release; `polled` must read 0 in production since #187.
     */
    val pushes = AtomicInteger(0)
    val polls = AtomicInteger(0)

    /**
     * Capture thread only. The picture is a limb: a refused offer drops this chunk and nothing else. The
     * analyser sees the drop as a jump in position and starts its window afresh (SpectrumAnalyzer).
     * **Nothing here logs, allocates, locks or calls across a process.**
     */
    fun offer(buffer: ByteArray, bytesRead: Int, position: Long) {
        spectrumRing.offer(buffer, bytesRead, position)
        LockSupport.unpark(analyserThread)
    }

    /** LEGACY (#187): the getter's copy under the lock, counted as a poll. Always BAND_COUNT long. */
    fun snapshot(): FloatArray {
        polls.incrementAndGet()
        return synchronized(bandsLock) { publishedBands.copyOf() }
    }

    /**
     * Start the one thread that turns this take's audio into the recorder's picture.
     *
     * Runs AFTER the capture thread is up, and its own failure is its own: a thread that cannot be
     * constructed or started leaves the take with no picture (the published bands stay zero, the pill
     * shows its resting rail) and touches none of the capture resources. The picture is a limb.
     * [stillLive] is the service's identity check for this take, evaluated by the analyser on every pass.
     */
    fun start(stillLive: () -> Boolean) {
        runCatching {
            val thread = Thread({ analyserLoop(stillLive) }, "SpectrumAnalyserThread")
            analyserThread = thread
            thread.start()
        }.onFailure {
            analyserThread = null
            DebugLogger.warn(tag, "Live picture unavailable for this take: ${it.message}")
        }
    }

    /** Interrupts the analyser. Idempotent; never joined, because it holds nothing a stop waits for. */
    fun close() {
        if (!closed.compareAndSet(false, true)) return
        analyserThread?.let { runCatching { interrupt(it) } }
    }

    /**
     * Analyser thread only. Drains the picture ring, analyses every queued chunk in order and publishes
     * once per wake, so what the recorder reads is always the newest audio the ring held.
     *
     * Exits when its take is no longer the live one, when its ending has been claimed, or when it is
     * interrupted, and checks all three at least every [ANALYSER_PARK_NS] whether or not the capture
     * thread unparks it. Never joined: it holds nothing a stop waits for.
     *
     * A failure publishes the zero picture before leaving, so the rail rests rather than holding the
     * last shape it was given, and it costs the picture only: the take does not know this thread exists.
     */
    private fun analyserLoop(stillLive: () -> Boolean) {
        val chunk = ByteArray(PcmAudio.READ_CHUNK_BYTES)
        val bands = FloatArray(SpectrumAnalyzer.BAND_COUNT)
        try {
            val analyzer = SpectrumAnalyzer()
            while (stillLive() && !Thread.currentThread().isInterrupted) {
                var analysed = false
                while (true) {
                    val length = spectrumRing.poll(chunk)
                    if (length < 0) break
                    analyzer.analyze(chunk, length, spectrumRing.lastPolledTag, bands)
                    analysed = true
                }
                if (analysed) {
                    synchronized(bandsLock) {
                        System.arraycopy(bands, 0, publishedBands, 0, SpectrumAnalyzer.BAND_COUNT)
                    }
                    // Outside the lock: the push is a binder transaction and the lock is the getter's.
                    pushSpectrum(bands)
                }
                LockSupport.parkNanos(ANALYSER_PARK_NS)
            }
        } catch (e: Exception) {
            bands.fill(0f)
            synchronized(bandsLock) { publishedBands.fill(0f) }
            pushSpectrum(bands)
            DebugLogger.warn(tag, "Live picture stopped for this take: ${e.message}")
        } finally {
            if (analyserThread === Thread.currentThread()) analyserThread = null
        }
    }

    /**
     * Analyser thread only. Hand one picture to the registered listener, if any (#187).
     *
     * `oneway`, so this never waits on the app process; the parcel is written before the call returns,
     * so the analyser's own array is safe to pass. A dead client throws: the slot is cleared with
     * `compareAndSet` so a registration that replaced this one in the meantime is kept, and the loss is
     * logged once per take. The picture is a limb: nothing here can reach the capture thread or the take.
     */
    private fun pushSpectrum(bands: FloatArray) {
        val target = listener.get() ?: return
        try {
            target.onSpectrum(bands)
            pushes.incrementAndGet()
        } catch (e: RemoteException) {
            if (listener.compareAndSet(target, null)) {
                DebugLogger.warn(tag, "Live picture listener gone: ${e.javaClass.simpleName}")
            }
        }
    }
}
