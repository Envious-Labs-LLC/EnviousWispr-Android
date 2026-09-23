package com.envi.wispr.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Drift Guard (#220, audit REF-06), counted as such: the session owner holds a take-sized capture interface.
 * The new AIDL declares exactly the five operations a take uses, in order; the owner's proxy names only
 * that interface and binds it with the take action and a fresh identifier; the service hands the take
 * binder only to that action; and each take-binder method is one call of a service function the legacy
 * binder also calls, so the two interfaces cannot drift apart in behaviour.
 *
 * REVERTS: point `CaptureProxy` back at `IAudioCaptureService`; drop the action or the identifier from the
 * bind; return the legacy binder for every intent; give the take binder its own start body.
 */
class CaptureTakeInterfaceShapeTest {
    private val takeAidl = File("src/main/aidl/com/envi/wispr/audio/IAudioTakeService.aidl").readText()
    private val service = File("src/main/java/com/envi/wispr/audio/AudioCaptureService.kt").readText()
    private val bindings = File("src/main/java/com/envi/wispr/ui/PipelineBindings.kt").readText()
    private val picture = File("src/main/java/com/envi/wispr/audio/PicturePublisher.kt").readText()

    private fun block(text: String, start: String): String {
        val from = text.indexOf(start)
        assertTrue("$start is missing", from >= 0)
        return text.substring(from).substringBefore("\n    }\n")
    }

    @Test fun theTakeInterfaceDeclaresExactlyTheFiveOperationsInOrder() {
        val methods = Regex("""^\s*(?:boolean|void)\s+(\w+)\(""", RegexOption.MULTILINE).findAll(takeAidl).map { it.groupValues[1] }.toList()
        assertEquals(
            listOf("startCaptureForTake", "stopCapture", "finishTake", "registerSpectrumListener", "registerTakeListener"),
            methods,
        )
    }

    @Test fun theOwnerBindsOnlyTheTakeInterfaceWithAFreshIdentifier() {
        assertFalse("PipelineBindings names the legacy interface", bindings.contains("IAudioCaptureService"))
        assertTrue(bindings.contains("private class CaptureProxy(private val service: IAudioTakeService) : CaptureLink {"))
        assertTrue(bindings.contains("capture = CaptureProxy(IAudioTakeService.Stub.asInterface(binder))"))
        assertTrue("the owner binds with the one take intent", bindings.contains("val audioIntent = AudioCaptureService.takeBindIntent(appContext)"))
        val intent = service.substringAfter("fun takeBindIntent(context: Context): Intent =").substringBefore("\n\n")
        assertTrue("which carries the take action", intent.contains(".setAction(ACTION_BIND_TAKE)"))
        assertTrue("and a fresh identifier per call (CaptureBindingDeviceTest proves Android then gives a new binder)", intent.contains(".setIdentifier(UUID.randomUUID().toString())"))
    }

    @Test fun theServiceHandsTheTakeBinderOnlyToTheTakeAction() {
        assertTrue(
            service.contains("if (intent?.action == ACTION_BIND_TAKE) newTakeBinder(listenerSlots.openTakeEpoch(intent.identifier)) else binder"),
        )
    }

    @Test fun everyTakeBinderMethodIsOneCallOfASharedServiceFunction() {
        val take = block(service, "private fun newTakeBinder(epoch: Long): IBinder = object : IAudioTakeService.Stub() {")
        val legacy = block(service, "private val binder = object : IAudioCaptureService.Stub() {")
        val shared = listOf(
            "this@AudioCaptureService.startTake(autoStopOnSilence, pauseSeconds, inputDevicePick, keepEarbudsReady, takeId)",
            "this@AudioCaptureService.stopRecording()",
            "this@AudioCaptureService.finishTakeHold()",
        )
        shared.forEach { call ->
            assertTrue("the take binder calls $call", take.contains(call))
            assertTrue("and the legacy binder calls it too", legacy.contains(call))
        }
        assertTrue(take.contains("this@AudioCaptureService.spectrumListener.register(from, listener)"))
        assertTrue(take.contains("this@AudioCaptureService.takeListener.register(from, listener)"))
        assertFalse("the take binder starts nothing itself", take.contains("startRecording("))
        assertFalse("and unregisters nothing: the binding's end clears its slots", take.contains("unregister"))
    }

    @Test fun thePictureClearGoesThroughTheSlots() {
        val push = picture.substringAfter("private fun pushSpectrum(bands: FloatArray) {").substringBefore("\n    }\n")
        assertTrue("a failed push clears through the slots", push.contains("listener.clearIfCurrent(target)"))
        assertFalse("never with a direct compareAndSet", push.contains("compareAndSet"))
    }
}
