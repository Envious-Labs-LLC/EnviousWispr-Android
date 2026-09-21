package com.envi.wispr.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Drift Guard on the shape #188 left: the capture service keeps the session, the binder and the lock,
 * and each audio limb has one owner with one `close`. The last row is an Observability Contract: every
 * log template and thread name the hardware pass reads survived the move unchanged.
 *
 * Source-level because the service is an Android `Service`, and because what is guarded is where text
 * IS and is NOT.
 */
class AudioServiceShapeTest {

    private val audio = "src/main/java/com/envi/wispr/audio"
    private val service = File("$audio/AudioCaptureService.kt").readText()
    private val owners = mapOf(
        "PicturePublisher" to File("$audio/PicturePublisher.kt").readText(),
        "DetectorFeed" to File("$audio/DetectorFeed.kt").readText(),
        "TakeRoute" to File("$audio/TakeRoute.kt").readText(),
        "WarmHoldOwner" to File("$audio/WarmHoldOwner.kt").readText(),
    )

    private fun member(source: String, signature: String): String {
        val start = source.indexOf(signature)
        assertTrue("$signature must exist", start >= 0)
        val end = listOf("\n    /**", "\n    private fun ", "\n    fun ", "\n    override fun ")
            .map { source.indexOf(it, start + 1) }.filter { it > start }.minOrNull() ?: source.length
        return source.substring(start, end)
    }

    @Test
    fun everyLimbIsClosedWhereEveryEndingReaches() {
        val release = member(service, "private fun releaseSession(active: CaptureSession)")
        assertTrue("release closes the route through closeResources", release.contains("closeResources(active, keepRoute = holding)"))
        assertTrue(member(service, "private fun closeResources(active: CaptureSession, keepRoute: Boolean)").contains("active.route.close(keepRoute)"))
        assertTrue(release.contains("active.detector.close()"))
        assertTrue(release.contains("active.picture.close()"))
        val destroy = service.substringAfter("override fun onDestroy()")
        assertTrue(destroy.contains("active.detector.close()"))
        assertTrue(destroy.contains("active.picture.close()"))
        assertEquals("the hold is closed twice around the join, both through the owner", 2,
            Regex("warmHoldOwner\\.close\\(WarmHold\\.END_DESTROYED\\)").findAll(destroy).count())
        // The route is the capture thread's: teardown stops the take and joins that thread, whose
        // releaseSession closes the route with the recorder. A second close from onDestroy would race
        // the loop still reading the route, and today's teardown never released it from here either.
        assertFalse("onDestroy leaves the route to the capture thread", destroy.contains("route.close("))
    }

    @Test
    fun theServiceKeepsOnlyTheSessionAndTheBinder() {
        // The four lifecycles' members, enumerated from the pre-split service at 2ed2cdf (plan §2.5.1).
        val movedFunctions = listOf(
            "analyserLoop", "pushSpectrum", "startSpectrumAnalysis",
            "offerToDetector", "feederLoop", "abandonDetector", "unbindVad", "vadConnectionFor", "startSilenceDetection",
            "resolveRoute", "applyPreferredDevice", "registerRoutingListener", "observeFinalRoute", "routeAdmissible",
            "watchSink", "armDeadline", "markLive", "resetCommunicationDevice",
            "holdEligible", "startWarmHold", "onHoldEnded", "clearHoldBookkeeping", "finishTake",
        )
        movedFunctions.forEach { name ->
            assertFalse("the service must not declare $name any more", service.contains("private fun $name("))
        }
        listOf("AudioTrackSilence", "HandedRoute", "ResolvedRoute").forEach { name ->
            assertFalse("the service must not declare class $name any more", Regex("class $name\\b").containsMatchIn(service))
        }
        val movedFields = listOf(
            "spectrumRing", "publishedBands", "bandsLock", "analyserThread", "spectrumPushes", "spectrumPolls",
            "ring", "pendingBlock", "pendingBytes", "pendingPosition", "detectorAbandoned", "silenceStatus",
            "vadService", "vadBound", "feederThread", "vadConnection",
            "routeHold", "gate", "targetBluetooth", "phonePicked", "sink", "liveAtMs", "deadline", "sinkGone", "sinkWatch", "effective",
            "warmHold", "heldSinkType", "heldSinkName", "holdExpiry", "holdCommListener", "holdDeviceCallback",
        )
        movedFields.forEach { name ->
            val declared = Regex("^ {4}(@Volatile )?(private )?(lateinit )?(val|var) $name\\b|^ {8}(@Volatile )?(private )?(val|var) $name\\b", RegexOption.MULTILINE)
            assertFalse("the service must not hold the field $name any more", declared.containsMatchIn(service))
        }
        owners.forEach { (name, text) ->
            assertEquals("$name declares exactly one close", 1, Regex("fun close\\(").findAll(text).count())
            assertFalse("$name carries no session identity check; those are the service's lambdas", text.contains("session ===") || text.contains("session !=="))
        }
        assertTrue("the identity checks are still in the service", service.contains("session === newSession") && service.contains("session !== active"))
    }

    @Test
    fun everyLogTemplateAndThreadNameSurvivesTheMove() {
        // Captured from audio/AudioCaptureService.kt at 2ed2cdf, the commit before the split, with the
        // same extraction as below (`scratchpad` regenerates nothing: the list IS the baseline). Templates
        // are normalised so a `$name` and a `${owner.name}` interpolation read the same; the first
        // literal segment of a concatenated line is what is compared.
        val baseline = listOf(
            "error: Capture thread error",
            "error: Failed to determine audio buffer size",
            "error: Failed to start capture thread",
            "error: Failed to start recording",
            "error: No input device at all; refusing to start",
            "error: RECORD_AUDIO permission not granted",
            "log: Buffer sizes: minimum=\${} coerced=\${} read=\${} block=\${}",
            "log: Byte ceiling reached (\${} bytes), auto-stopping",
            "log: Live picture: pushed=\${} polled=\${}",
            "log: Max duration reached (\${}ms), auto-stopping",
            "log: Recording started (PID: \${}, ",
            "log: Stopped by \${}. \${} bytes ",
            "log: route adopt=\${} from the warm hold",
            "log: route change=\${} at \${} bytes",
            "log: route earbuds already gone at start; the phone may record",
            "log: route earbuds removed while \${}; the phone may record",
            "log: route hold end=\${} device=\${}",
            "log: route hold start=\${} ms=\${}",
            "log: route live=\${} after \${} ms ",
            "log: route start=\${} kind=\${} reason=\${} ",
            "mark: recording_start",
            "mark: recording_stop",
            "thread: AudioCaptureThread",
            "thread: AudioRouteThread",
            "thread: SilenceFeederThread",
            "thread: SpectrumAnalyserThread",
            "warn: AudioRecord stop failed: \${}",
            "warn: Auto-stop abandoned for this take",
            "warn: Auto-stop refused: pause \${} is out of range",
            "warn: Auto-stop unavailable: detector feeder could not start",
            "warn: Auto-stop unavailable: detector service could not be bound",
            "warn: Auto-stop unavailable: start failed, \${}",
            "warn: Auto-stop unavailable: the detector call failed",
            "warn: Auto-stop unavailable: the detector gave up mid-take",
            "warn: Auto-stop unavailable: the detector reported so",
            "warn: Auto-stop unavailable: the feeder failed, \${}",
            "warn: Bluetooth link refused for \${}; staying on the earbuds",
            "warn: Capture thread did not finish during service teardown",
            "warn: Detector unbind failed: \${}",
            "warn: Failed to close audio file: \${}",
            "warn: Failed to flush audio file: \${}",
            "warn: Failed to release AudioRecord: \${}",
            "warn: Failed to stop AudioRecord: \${}",
            "warn: Live picture listener gone: \${}",
            "warn: Live picture stopped for this take: \${}",
            "warn: Live picture unavailable for this take: \${}",
            "warn: Routing listener not registered: \${}",
            "warn: communication device reset threw: \${}",
            "warn: getAudioData() called, use getAudioFilePath() instead",
            "warn: hold could not keep the service: \${}",
            "warn: hold device callback not registered: \${}",
            "warn: hold listener not registered: \${}",
            "warn: route forced=\${} after \${} ms",
            "warn: route refused=\${}: earbuds connected, phone would record; failing the take",
            "warn: route reset=\${} performed=\${} after \${} ms",
            "warn: setCommunicationDevice threw: \${}",
            "warn: setPreferredDevice refused for \${}",
            "warn: sink watch not registered: \${}",
        )
        val log = Regex("DebugLogger\\.(\\w+)\\(\\s*(?:TAG|tag),\\s*\"((?:[^\"\\\\]|\\\\.)*)\"")
        val thread = Regex("(?:Thread\\([^\"\\n]*?|HandlerThread\\()\"([^\"]+)\"")
        val interpolation = Regex("\\$\\{[^}]*\\}|\\$\\w+")
        val placeholder = Regex.escapeReplacement("\${}")
        val now = (listOf(service) + owners.values).flatMap { text ->
            log.findAll(text).map { "${it.groupValues[1]}: ${interpolation.replace(it.groupValues[2], placeholder)}" }.toList() +
                thread.findAll(text).map { "thread: ${it.groupValues[1]}" }.toList()
        }.sorted()
        assertEquals("every template and thread name must survive the move, unchanged and exactly as often", baseline.sorted(), now)
    }
}
