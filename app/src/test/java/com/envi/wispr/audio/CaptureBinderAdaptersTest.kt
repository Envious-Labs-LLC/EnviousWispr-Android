package com.envi.wispr.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Harness Contract with a legacy-compatibility half (#361): every capture transaction reaches the one service
 * operation it always meant, with the same arguments, through the adapters and nothing else. When this fails, an
 * older client's start records with the wrong microphone or silence setting, a legacy start recovers an abandoned
 * recorder it has no proof for (#213), or a take binding that already ended fills the next take's listener slot
 * (#220). Built on the JVM: this module's unit tests stub `android.os.Binder`. Each row names its mutation.
 */
class CaptureBinderAdaptersTest {
    private val calls = mutableListOf<String>()

    private val ops = object : CaptureOperations {
        override fun startLegacy(autoStopOnSilence: Boolean, pauseSeconds: Float, pick: InputDevicePick, keepEarbudsReady: Boolean): Boolean {
            calls += "legacy $autoStopOnSilence $pauseSeconds $pick $keepEarbudsReady"
            return true
        }
        override fun startTake(autoStopOnSilence: Boolean, pauseSeconds: Float, inputDevicePick: String?, keepEarbudsReady: Boolean, takeId: String?): Boolean {
            calls += "take $autoStopOnSilence $pauseSeconds $inputDevicePick $keepEarbudsReady $takeId"
            return true
        }
        override fun finishTake(): Boolean { calls += "finish"; return true }
        override fun stopCapture() { calls += "stop" }
        override val takePeakAmplitude = 0.5f
        override val liveState = AudioCaptureService.LIVE_READY
        override val liveAfterMs = 120L
        override val effectiveInputDevice = "Phone microphone"
        override val inputRouteKind = 1
        override val inputRouteReason = 2
        override val lastStartFailure = 3
        override val silenceStopStatus = 4
        override val isCapturing = true
        override val terminalReason = 5
        override val currentAmplitude = 0.25f
        override fun spectrumBands() = FloatArray(SpectrumAnalyzer.BAND_COUNT) { 0.1f }
        override val audioFilePath = "/cache/take.pcm"
        override val elapsedMs = 900L
        override fun waitForFileReady(timeoutMs: Long): Boolean { calls += "wait $timeoutMs"; return true }
    }
    private val slots = ListenerSlots()
    private val spectrum = slots.slot<IAudioSpectrumListener> { it }
    private val takeEvents = slots.slot<ITakeListener> { it }
    private val adapters = CaptureBinderAdapters(ops, spectrum, takeEvents)
    private val legacy = adapters.legacy as IAudioCaptureService.Stub

    /**
     * Row 1: the four legacy starts are refusal-only starts with the arguments they always had; the take start is
     * the one that may recover. MUTATION m1: the held start drops keepEarbudsReady; m2: the legacy take start loses recovery.
     */
    @Test fun everyStartMeansWhatItAlwaysMeant() {
        // Each answer is the operation's own (review round 1): a binder that reached the right call but answered
        // differently would fail here.
        assertTrue(legacy.startCapture())
        assertTrue(legacy.startCaptureWithSilenceStop(true, 1.5f))
        assertTrue(legacy.startCaptureWithInputDevice(true, 1.5f, null))
        assertTrue(legacy.startCaptureWithInputDeviceHeld(false, 2f, null, true))
        assertTrue(legacy.startCaptureForTake(true, 1.5f, "auto", true, "take-1"))
        assertTrue((adapters.forTake(slots.openTakeEpoch("a")) as IAudioTakeService.Stub).startCaptureForTake(false, 0f, null, false, "take-2"))
        assertEquals(
            listOf(
                "legacy false 0.0 ${InputDevicePick.Auto} false",
                "legacy true 1.5 ${InputDevicePick.Auto} false",
                "legacy true 1.5 ${InputDevicePick.Auto} false",
                "legacy false 2.0 ${InputDevicePick.Auto} true",
                "take true 1.5 auto true take-1",
                "take false 0.0 null false take-2",
            ),
            calls,
        )
    }

    /** Row 2: every read and the remaining operations pass straight through. */
    @Test fun everyReadAndOperationPassesThrough() {
        assertEquals(0.5f, legacy.takePeakAmplitude)
        assertEquals(AudioCaptureService.LIVE_READY, legacy.liveState)
        assertEquals(120L, legacy.liveAfterMs)
        assertEquals("Phone microphone", legacy.effectiveInputDevice)
        assertEquals(listOf(1, 2, 3, 4, 5), listOf(legacy.inputRouteKind, legacy.inputRouteReason, legacy.lastStartFailure, legacy.silenceStopStatus, legacy.terminalReason))
        assertTrue(legacy.isCapturing)
        assertEquals(0.25f, legacy.currentAmplitude)
        assertEquals(SpectrumAnalyzer.BAND_COUNT, legacy.spectrumBands.size)
        assertEquals("/cache/take.pcm", legacy.audioFilePath)
        assertEquals(900L, legacy.elapsedMs)
        assertEquals(RecordingLimits.MAX_DURATION_MS, legacy.maxDurationMs)
        assertEquals(0, legacy.audioData.size)
        assertTrue(legacy.finishTake())
        legacy.stopCapture()
        assertTrue(legacy.waitForFileReady(250L))
        assertEquals(listOf("finish", "stop", "wait 250"), calls)
    }

    /**
     * Row 3: a take binder registers under its own epoch, so after its unbind a late registration is refused and the
     * next take's slot stays its own. MUTATION m3: the take binder registers as legacy.
     */
    @Test fun aTakeBindersRegistrationDiesWithItsBinding() {
        val first = adapters.forTake(slots.openTakeEpoch("a")) as IAudioTakeService.Stub
        slots.closeTakeEpoch("a")
        val late = object : ITakeListener.Stub() {
            override fun onLive(takeId: String?, forced: Boolean, routeKind: Int, routeReason: Int, liveAfterMs: Long) = Unit
            override fun onTick(takeId: String?, elapsedMs: Long) = Unit
            override fun onSilenceStatus(takeId: String?, status: Int) = Unit
            override fun onEnded(takeId: String?, terminalReason: Int, startFailure: Int, audioFilePath: String?, silenceStatus: Int, takePeakAmplitude: Float, effectiveInputDevice: String?) = Unit
        }
        first.registerTakeListener(late)
        assertNull("a binding that ended fills nothing", takeEvents.listener.get())
        val second = adapters.forTake(slots.openTakeEpoch("b")) as IAudioTakeService.Stub
        second.registerTakeListener(late)
        assertSame(late, takeEvents.listener.get())
    }

    /** Row 4: the legacy binder's registrations are legacy, and its unregister clears only its own. */
    @Test fun theLegacyBinderRegistersAsLegacy() {
        val listener = object : IAudioSpectrumListener.Stub() {
            override fun onSpectrum(bands: FloatArray?) = Unit
        }
        legacy.registerSpectrumListener(listener)
        assertSame(listener, spectrum.listener.get())
        legacy.unregisterSpectrumListener(listener)
        assertNull(spectrum.listener.get())
    }
}
