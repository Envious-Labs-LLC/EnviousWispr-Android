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
        assertTrue("release leaves the unbind to the feeder's exit, as before #188", release.contains("active.detector.close(unbindNow = false)"))
        assertTrue(release.contains("active.picture.close()"))
        val destroy = service.substringAfter("override fun onDestroy()")
        assertTrue("teardown unbinds now, as before #188", destroy.contains("active.detector.close(unbindNow = true)"))
        assertTrue(destroy.contains("active.picture.close()"))
        assertEquals("the hold is closed twice around the join, both through the owner", 2,
            Regex("warmHoldOwner\\.close\\(WarmHold\\.END_DESTROYED\\)").findAll(destroy).count())
        // The route is the capture thread's: teardown stops the take and joins that thread, whose
        // releaseSession closes the route with the recorder. A second close from onDestroy would race
        // the loop still reading the route, and today's teardown never released it from here either.
        assertFalse("onDestroy leaves the route to the capture thread", destroy.contains("route.close("))
        // #115 review round 1, F8: the listener slots are the SERVICE's to clear; a warm hold keeps it
        // alive past the owner's unbind, so both go on the last unbind and again on destroy.
        val unbind = member(service, "override fun onUnbind(intent: Intent?): Boolean")
        listOf("spectrumListener.set(null)", "takeListener.set(null)").forEach {
            assertTrue("onUnbind clears $it", unbind.contains(it))
            assertTrue("onDestroy clears $it", destroy.contains(it))
        }
        // #212 code review: the old form compared against a join #115 removed, so indexOf was -1 and it
        // could not fail. The order it meant: the publisher closes after route-thread shutdown begins.
        assertFalse("onDestroy never joins the capture thread", destroy.contains("thread.join("))
        assertTrue(
            "the event publisher closes after route-thread shutdown begins",
            destroy.indexOf("takeEvents.close()") > destroy.indexOf("routeThread.quitSafely()"),
        )
        // F6: a refused start carries nothing of the previous take.
        assertTrue(member(service, "private fun publishStartRefused(takeId: String, failure: Int)").contains("takeEvents.publishEnded(takeId, TERMINAL_REASON_NONE, failure, null, SILENCE_STATUS_DISABLED, 0f, null)"))
    }

    @Test
    fun theServiceKeepsOnlyTheSessionAndTheBinder() {
        // An ALLOWLIST of what the service may declare at class level, so a moved member coming back
        // under any name is red, not only under its old one (Codex code review 1). A legitimate new
        // member is added here on purpose, with the reason in the commit.
        val expectedFunctions = setOf(
            "nextCaptureToken", "startRecording", "captureLoop", "claimEnding", "stopRecording",
            "endTake", "endTakeLocked", "releaseSession", "closeResources", "waitForFileReady",
            // #115: the two publishers of a start refused before capture began.
            "publishStartRefused", "failSetup",
            // #212: a production take's start removes earlier production takes' capture files.
            "sweepEarlierTakeFiles",
        )
        val actualFunctions = Regex("^ {4}(?:(?:private|internal|public|protected|inline|suspend|operator|tailrec|infix)\\s+)*fun\\s+(\\w+)\\s*\\(", RegexOption.MULTILINE)
            .findAll(service).map { it.groupValues[1] }.toSet()
        assertEquals("the service declares only its own non-override functions", expectedFunctions, actualFunctions)
        val expectedFields = setOf(
            "sessionLock", "session", "lastEffective", "lastStartFailure", "warmHoldOwner", "destroyed",
            "routeThread", "routeHandler", "routeScheduler", "isRecording", "captureThread", "lastAudioFile",
            "currentAmplitude", "spectrumListener", "takePeakAmplitude", "lastSilenceStatus", "terminalReason",
            "tokens", "binder",
            // #115: the take-event listener slot and its publisher.
            "takeListener", "takeEvents",
        )
        val actualFields = Regex("^ {4}(?:@\\w+(?:\\([^)]*\\))?\\s+)*(?:(?:private|internal|public|protected|lateinit|const)\\s+)*(?:val|var)\\s+(\\w+)\\b", RegexOption.MULTILINE)
            .findAll(service).map { it.groupValues[1] }.toSet()
        assertEquals("the service holds only its own fields", expectedFields, actualFields)
        val expectedSessionFields = setOf("record", "file", "output", "readBuffer", "token", "detector", "picture", "route", "keepEarbudsReady", "takeId", "liveVisible", "bytesWritten", "endingClaim", "stopRequested")
        val actualSessionFields = Regex("^ {8}(?:@\\w+\\s+)*(?:(?:private|internal)\\s+)?(?:val|var)\\s+(\\w+)\\b", RegexOption.MULTILINE)
            .findAll(service.substringAfter("private class CaptureSession(").substringBefore("\n    }\n")).map { it.groupValues[1] }.toSet()
        assertEquals("the session carries the recorder, the file, the token and three owners, nothing of the owners' insides", expectedSessionFields, actualSessionFields)
        listOf("AudioTrackSilence", "HandedRoute", "ResolvedRoute").forEach { name ->
            assertFalse("the service must not declare class $name any more", Regex("class $name\\b").containsMatchIn(service))
        }
        owners.forEach { (name, text) ->
            assertEquals("$name declares exactly one close", 1, Regex("fun close\\(").findAll(text).count())
            assertFalse("$name carries no session identity check; those are the service's lambdas", text.contains("session ===") || text.contains("session !=="))
        }
        assertTrue("the identity checks are still in the service", service.contains("session === newSession") && service.contains("session !== active"))
    }

    // #212 row 17. REVERT R15 (a "recording.pcm" literal as the opened file) and R16 (the sweep without
    // its production guard) turn this red.
    @Test
    fun everyTakeOpensOnlyItsOwnFile() {
        val start = member(service, "private fun startRecording(")
        assertEquals("one token per capture", 1, Regex("nextCaptureToken\\(\\)").findAll(start).count())
        assertTrue(start.contains("val file = File(cacheDir, CaptureFiles.nameFor(takeId, token))"))
        assertTrue("the session carries the file's own token", start.contains("token = token,"))
        assertTrue("the exact-file stale check stays", start.contains("if (file.exists() && !file.delete())"))
        assertTrue(
            "only a production take sweeps",
            start.contains("if (CaptureFiles.isProductionTake(takeId)) sweepEarlierTakeFiles(keep = file.name)"),
        )
        assertFalse("no capture filename literal", Regex("\"[^\"\\n]*\\.pcm\"").containsMatchIn(service))
        val sweep = member(service, "private fun sweepEarlierTakeFiles(keep: String)")
        assertTrue(sweep.contains("!CaptureFiles.isSweptAtTakeStart(entry.name)"))
        assertEquals("the sweep is the only other delete of a capture file", 2, Regex("\\.delete\\(\\)").findAll(service).count())
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
            // #115 review (chunks B and C, F3): the process-scoped recorder lease refuses a second recorder.
            "error: A recorder is still held in this process; refusing to start",
            "error: RECORD_AUDIO permission not granted",
            "log: Buffer sizes: minimum=\${} coerced=\${} read=\${} block=\${}",
            "log: Byte ceiling reached (\${} bytes), auto-stopping",
            "log: Live picture: pushed=\${} polled=\${}",
            "log: Max duration reached (\${}ms), auto-stopping",
            "log: Recording started (PID: \${}, ",
            // #212: a production take's start removes earlier production takes' files, counts only.
            "log: Removed \${} earlier capture files; \${} could not be removed",
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
            // #115 review round 2: a failed recorder release keeps the process lease held.
            "warn: Recorder release failed; the process's recorder lease stays held",
            "warn: Auto-stop unavailable: the detector reported so",
            "warn: Auto-stop unavailable: the feeder failed, \${}",
            "warn: Bluetooth link refused for \${}; staying on the earbuds",
            // "warn: Capture thread did not finish during service teardown" left with the join it announced (#115 chunk C).
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
