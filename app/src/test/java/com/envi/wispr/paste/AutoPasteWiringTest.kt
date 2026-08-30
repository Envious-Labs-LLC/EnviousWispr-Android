package com.envi.wispr.paste

import com.envi.wispr.insertion.FallbackAnnouncement
import com.envi.wispr.insertion.ServiceFallbackReason
import com.envi.wispr.shortcuts.DictationNotificationController
import java.io.File
import java.lang.reflect.Modifier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * DRIFT GUARD, explicitly NOT product coverage (`testing-philosophy.md`
 * RULE: every-test-declares-which-of-four-things-it-protects).
 *
 * It exists because of a hole the review named: `AutoPasteAvailabilityTest` proves the combinator
 * and `InsertionOutcomeMessagesTest` proves the sentences, and BOTH stay green while the wiring
 * that carries either to a user is deleted. Every row below fails against a NAMED revert of the
 * issue #16 fix, and each one says which.
 *
 * Source text, not behaviour, because the wiring is Android-only: a `StateFlow` published from an
 * `AccessibilityService` lifecycle callback and collected by a `ViewModel` is unreachable from
 * `app/src/test` (`android-testing-patterns.md`
 * FACT: the-two-source-sets-answer-different-questions). The behavioural half is the adb recipe in
 * `.claude/knowledge/device-testing.md`; this half is what runs on every commit.
 */
class AutoPasteWiringTest {

    /** REVERT: `AutoPasteReadiness.evaluate(currentReadiness.accessibilityPermitted, true)`. */
    @Test
    fun theViewModelDerivesAutoPasteFromLivenessAndNotFromAConstant() {
        val source = read("ui/AppViewModel.kt")
        assertTrue(
            "AppViewModel no longer reads PasteAccessibilityService.isBound, so every readiness " +
                "surface is back to reporting the Android setting alone, which still names a " +
                "crashed service. That is issue #16.",
            source.contains("PasteAccessibilityService.isBound"),
        )
        // The join itself lives in AutoPasteReadiness.observe and is proven by execution in
        // AutoPasteReadinessObserveTest. What this file still has to protect is that the view model
        // USES that answer and does not project it back down afterwards: a trailing operator that
        // recomputed the state from the permission would restore issue #16 with the join green.
        val arguments = callArguments(source, "AutoPasteReadiness.observe(")
        assertEquals(
            "AppViewModel does not derive auto-paste through AutoPasteReadiness.observe, so the " +
                "join is hand-written again and nothing executes it",
            1,
            arguments.size,
        )
        assertTrue(
            "AutoPasteReadiness.observe is not passed the pushed liveness " +
                "(${arguments.first()}), so the answer can be computed without it",
            arguments.first().contains("PasteAccessibilityService.isBound"),
        )
        assertFalse(
            "AutoPasteReadiness.observe is called with a hardcoded boolean " +
                "(${arguments.first()}), so liveness is asserted rather than observed",
            Regex("\\b(true|false)\\b").containsMatchIn(arguments.first()),
        )
        val callStart = source.indexOf("AutoPasteReadiness.observe(")
        var cursor = callStart + "AutoPasteReadiness.observe(".length
        var depth = 1
        while (depth > 0) {
            when (source[cursor]) {
                '(' -> depth++
                ')' -> depth--
            }
            cursor++
        }
        val afterCall = source.substring(cursor).trimStart()
        assertFalse(
            "The auto-paste answer is post-processed (${afterCall.take(40)}), so the view model " +
                "can recompute it from the permission after the join got it right. That is the " +
                "two-sources-of-truth defect that IS issue #16.",
            afterCall.startsWith("."),
        )
        // Guarding the call SHAPE only ever guards one arrangement of it: an intermediate property
        // (`val joined = observe(...)` then `val autoPaste = joined.map { ... }`) puts the
        // permission back in charge with no dot after the call. So the rule is about the view
        // model's JOB instead. It wires; it does not decide. Deciding here means naming an
        // availability or re-running the rule, and neither has any legitimate reason to appear.
        val decisions = Regex("AutoPasteAvailability\\.(NOT_PERMITTED|PERMITTED_NOT_RUNNING|LIVE)")
            .findAll(source).map { it.value }.toList()
        assertEquals(
            "AppViewModel names an auto-paste answer directly (${decisions.joinToString()}). It is " +
                "wiring, not the decision: the moment it can produce an availability of its own, " +
                "it can hand the UI one the permission chose. Only AutoPasteReadiness decides.",
            emptyList<String>(),
            decisions,
        )
        assertFalse(
            "AppViewModel calls AutoPasteReadiness.evaluate directly, so it can answer with the " +
                "permission fact in both arguments and never consult the pushed liveness at all",
            source.contains("AutoPasteReadiness.evaluate("),
        )
    }

    /** REVERT: deleting `onUnbind`, which is the only signal that fires before teardown blocks. */
    @Test
    fun theServiceRetractsItsPublicationOnUnbindAndOnDestroy() {
        val source = read("paste/PasteAccessibilityService.kt")
        assertTrue(
            "PasteAccessibilityService no longer overrides onUnbind, so a service the user turns " +
                "off keeps reporting LIVE until onDestroy finishes its blocking Room drain",
            source.contains("override fun onUnbind("),
        )
        assertEquals(
            "Both onUnbind and onDestroy must retract the publication, and only for their own " +
                "instance: nulling unconditionally lets an outgoing instance kill a replacement " +
                "Android has already connected",
            2,
            Regex("if \\(instance === this\\) publishBinding\\(null\\)").findAll(source).count(),
        )
    }

    /**
     * REVERT: deleting `boundState.value = service != null` from `publishBinding`, which leaves the
     * insertion path's `instance` correct and the whole UI reading a flow nobody updates.
     */
    @Test
    fun publishBindingIsTheOnlyWriterOfTheLivenessFacts() {
        val source = read("paste/PasteAccessibilityService.kt")
        val assignments = Regex("(?<![.\\w])instance = ").findAll(source).count()
        assertEquals(
            "`instance` is assigned outside publishBinding, so the insertion path and the UI can " +
                "report different health for the same service",
            1,
            assignments,
        )
        assertEquals(
            "`boundState` is assigned outside publishBinding, so the UI-facing fact has a second " +
                "writer and can disagree with the insertion path about the same service",
            1,
            Regex("boundState\\.value = ").findAll(source).count(),
        )
        val body = slice(
            source,
            "private fun publishBinding(service: PasteAccessibilityService?) {",
            "\n        }",
        )
        // BOTH facts, in ONE function. Either line alone is a service whose two readers report
        // different health: dropping the flow write is exactly issue #16 wearing a fixed insertion
        // path, and it leaves every assertion about `instance` untouched.
        assertTrue(
            "publishBinding no longer writes the insertion path's instance: $body",
            body.contains("instance = service"),
        )
        assertTrue(
            "publishBinding no longer writes the UI-facing liveness flow, so every readiness " +
                "surface is frozen at whatever the flow was initialised to: $body",
            body.contains("boundState.value = service != null"),
        )
        assertTrue(
            "onServiceConnected must publish through publishBinding",
            source.contains("publishBinding(this)"),
        )
    }

    /**
     * REVERT (session side): deleting the `announceInsertionFallback(...)` call, restoring the
     * shipped silence.
     *
     * REVERT (service side): putting `keepTranscriptOnClipboard(pending)` back inline in
     * `onInterrupt` or `onDestroy`. Those two copied the pending words to the clipboard and said
     * nothing at all, which is issue #16's silence reached from the one direction where the service
     * dies holding the text. The count below is the closure: the clipboard is reachable from
     * `recordAndAnnounce` and nowhere else, so a NEW teardown path cannot copy silently either.
     */
    @Test
    fun everyDictationThatMissesTheFieldReachesTheAnnouncement() {
        val service = read("paste/PasteAccessibilityService.kt")
        val keepCalls = Regex("keepTranscriptOnClipboard\\(").findAll(service).count()
        assertEquals(
            "The service keeps words on the clipboard from more than one place. Every such place " +
                "is a dictation that missed the field, so the copy and the sentence have to be " +
                "the same call: recordAndAnnounce. Sites found: $keepCalls (expected the " +
                "declaration plus its single call inside recordAndAnnounce)",
            2,
            keepCalls,
        )
        val announce = slice(
            service,
            "private fun recordAndAnnounce(reason: ServiceFallbackReason, pending: PendingInsertion) {",
            "\n    private fun ",
        )
        assertTrue(
            "recordAndAnnounce is no longer the site that writes the clipboard, so the copy and " +
                "the sentence can be separated again",
            announce.contains("keepTranscriptOnClipboard(pending)"),
        )
        listOf(
            "override fun onInterrupt() {" to ServiceFallbackReason.SERVICE_INTERRUPTED,
            "override fun onDestroy() {" to ServiceFallbackReason.SERVICE_DESTROYED,
        ).forEach { (anchor, reason) ->
            val teardown = slice(service, anchor, "\n    }")
            assertTrue(
                "$anchor disposes of a pending insertion without announcing it, so a user whose " +
                    "service died mid-insertion is told nothing at all: $teardown",
                teardown.contains("recordAndAnnounce(ServiceFallbackReason.$reason, pending)"),
            )
        }
        val source = read("ui/DictationSessionService.kt")
        // `substringAfter` and `substringBefore` return the WHOLE receiver when their delimiter is
        // absent, so a reformat of either line would silently widen this to the entire file and
        // the check below would then match the DECLARATION of announceInsertionFallback rather
        // than the call inside the branch (`validation-discipline.md`
        // RULE: a-partial-check-looks-exactly-like-a-complete-one).
        val branch = slice(
            source,
            "if (handoff != InsertionHandoff.SCHEDULED) {",
            "\n            DebugLogger.log(",
        )
        assertTrue(
            "A dictation that did not reach the field no longer announces where its words went, " +
                "which is the silence issue #16 reported",
            branch.contains("announceInsertionFallback("),
        )
        assertTrue(
            "announceInsertionFallback must ask InsertionOutcomeMessages what to say AND whether " +
                "to say anything, or a user who never granted the permission is buzzed and " +
                "notified after every successful clipboard dictation",
            source.contains("FallbackAnnouncement.fallbackAnnouncement("),
        )
    }

    /**
     * REVERT: any readiness surface reading the permission fact alone again, and, the revert the
     * old single `AutoPasteAvailability.LIVE` check could not see, DELETING any four of the five
     * surfaces. One survivor kept that check green while four screens went back to reporting the
     * Android setting, which is the state issue #16 is about.
     *
     * The five are enumerated because they are a closed population: every place in the app that
     * tells a user whether auto-paste will work. Each row names the surface and the exact text that
     * ties it to the COMBINED answer rather than to the permission.
     */
    @Test
    fun noReadinessSurfaceReportsThePermissionAsIfItWereLiveness() {
        val source = read("ui/AppShell.kt")
        assertFalse(
            "AppShell reads the permission fact directly, so a surface can report a crashed " +
                "service as Ready",
            source.contains("accessibilityPermitted"),
        )
        // The Insert chip is checked inside its OWN block. Its evidence line also appears on the
        // onboarding SetupActionCard, so scanning the whole file stays green after the chip stops
        // consuming the combined answer and goes back to coreReady, which excludes accessibility.
        val insertChip = slice(source, "label = \"Insert\",", ")")
        val surfaces = listOf(
            Triple(
                "the Settings auto-paste row",
                "AutoPasteAvailability.LIVE -> \"Ready for right-button dictation\"",
                source,
            ),
            Triple(
                "the Home 'not connected' card",
                "autoPaste == AutoPasteAvailability.PERMITTED_NOT_RUNNING",
                source,
            ),
            Triple(
                "the Home card's status dot and its screen-reader label",
                "StatusDot(ready = false, description = autoPaste.statusDescription())",
                source,
            ),
            Triple(
                "the Insert readiness chip",
                "ready = autoPaste == AutoPasteAvailability.LIVE,",
                insertChip,
            ),
            Triple(
                "onboarding step 4",
                "AutoPasteAvailability.LIVE -> \"Right-button auto-insert ready\"",
                source,
            ),
        )
        val missing = surfaces.filterNot { (_, evidence, haystack) -> haystack.contains(evidence) }
        assertEquals(
            "A readiness surface no longer consumes the combined auto-paste answer, so it is back " +
                "to reporting the Android setting, which still names a crashed service: " +
                missing.joinToString { it.first },
            emptyList<String>(),
            missing.map { it.first },
        )
        // The three-way answer is the point: a surface that only knows LIVE from not-LIVE sends a
        // user who already granted the permission to grant it again.
        AutoPasteAvailability.entries.forEach { availability ->
            assertTrue(
                "AppShell never mentions $availability, so a state the combinator can return has " +
                    "no sentence on any screen",
                source.contains("AutoPasteAvailability.$availability"),
            )
        }
    }

    /**
     * REVERT: `wordsNotInserted(context: Context, title: String, detail: String)`, and with it any
     * caller composing the durable notification's own sentences.
     *
     * The first two assertions are the ones that hold against a caller this file has never seen.
     * Scanning `PasteAccessibilityService` for a toast literal was the old shape and it could only
     * ever see today's callers: a NEW class posting a notification that contradicts its toast was
     * invisible to it. The property is now carried by the TYPE. `FallbackAnnouncement`'s
     * constructor is private to its own file, its two factories derive both surfaces in one call,
     * and the notification API accepts nothing else, so the contradiction is unrepresentable rather
     * than merely absent from the current source.
     */
    @Test
    fun theServiceCannotHandItsOwnSentenceToTheUser() {
        val constructors = FallbackAnnouncement::class.java.declaredConstructors
            .filterNot { it.isSynthetic }
        assertTrue("FallbackAnnouncement declares no constructor at all", constructors.isNotEmpty())
        val reachable = constructors.filterNot { Modifier.isPrivate(it.modifiers) }
        assertEquals(
            "FallbackAnnouncement can be constructed from outside its own file, so any caller can " +
                "hand the durable notification sentences that contradict the toast beside it: " +
                reachable.joinToString { it.toString() },
            emptyList<String>(),
            reachable.map { it.toString() },
        )
        val posted = DictationNotificationController::class.java.declaredMethods
            .filter { it.name == "wordsNotInserted" }
        assertEquals("wordsNotInserted is not declared once", 1, posted.size)
        assertEquals(
            "wordsNotInserted no longer takes the derived announcement, so a caller can compose " +
                "the durable notification's own sentences again: " +
                posted.first().parameterTypes.joinToString { it.simpleName },
            listOf("Context", "FallbackAnnouncement"),
            posted.first().parameterTypes.map { it.simpleName },
        )
        // The toast half stays a source check: which surface a service SHOWS is not visible to
        // reflection, and one call site is what keeps it reading from the same value.
        val source = read("paste/PasteAccessibilityService.kt")
        val toastCalls = Regex("Toast\\.makeText\\(").findAll(source).count()
        assertEquals("The service shows a toast from more than one place", 1, toastCalls)
        assertTrue(
            "The service composes a toast from something other than the announcement, so the " +
                "toast and the durable notification can state opposite facts about one event, " +
                "which is what 'Transcript copied' beside 'saved in History' was",
            source.contains("Toast.makeText(this, announcement.toast, Toast.LENGTH_LONG)"),
        )
        assertTrue(
            "The service no longer asks FallbackAnnouncement what to say",
            source.contains("FallbackAnnouncement.serviceFallbackAnnouncement("),
        )
        val reasons = ServiceFallbackReason.entries.filterNot {
            source.contains("ServiceFallbackReason.$it")
        }
        assertEquals(
            "A service fallback outcome announces nothing: $reasons",
            emptyList<ServiceFallbackReason>(),
            reasons,
        )
    }

    /**
     * REVERT: `if (Settings.System.getInt(...HAPTIC_FEEDBACK_ENABLED...) != 1) return` at the top
     * of `vibrate`, which silenced the recording-started and recording-stopped cues too.
     */
    @Test
    fun theRecordingCuesAreNotGatedOnTheTouchFeedbackSetting() {
        val source = read("ui/DictationSessionService.kt")
        assertEquals(
            "The session service reads the touch-feedback setting in more than one place, so " +
                "the cue is no longer the only thing that decides which buzzes it silences",
            1,
            Regex("Settings\\.System\\.getInt\\(").findAll(source).count(),
        )
        assertTrue(
            "The system touch-feedback switch now gates every cue, including the recording " +
                "started and stopped buzz. Those are the only signal on the side-button path, " +
                "where there is no window and the user is looking at another app's text field.",
            source.contains("if (cue.honoursSystemHapticSetting &&"),
        )
        assertTrue(
            "SESSION_TRANSITION must not answer to the touch-feedback switch",
            source.contains("SESSION_TRANSITION(28L, 120, honoursSystemHapticSetting = false)"),
        )
        assertTrue(
            "The failure cue must still answer to it, for parity with performResultHaptic",
            source.contains("FAILURE(45L, 180, honoursSystemHapticSetting = true)"),
        )
        assertEquals(
            "Recording started and recording stopped are the two cues that must always fire",
            2,
            Regex("vibrate\\(HapticCue\\.SESSION_TRANSITION\\)").findAll(source).count(),
        )
    }

    /**
     * REVERT: deleting the `dismissWordsNotInserted` call, which left notification 1002 standing
     * as a present-tense claim about a clipboard the next dictation is about to overwrite.
     */
    @Test
    fun eachDictationRetractsThePreviousOnesClipboardClaim() {
        val controller = read("shortcuts/DictationNotificationController.kt")
        assertTrue(
            "Nothing cancels RESULT_NOTIFICATION_ID, so the durable 'press and hold, then tap " +
                "Paste' notification outlives the clipboard it describes",
            controller.contains("cancel(RESULT_NOTIFICATION_ID)"),
        )
        val session = read("ui/DictationSessionService.kt")
        val beginSession = slice(session, "private fun beginSession() {", "\n    private fun ")
        assertTrue(
            "beginSession no longer retracts the previous dictation's result notification. It is " +
                "the one place every entry point passes through, so the stale claim survives and " +
                "sends the user to paste words the clipboard no longer holds.",
            beginSession.contains("DictationNotificationController.dismissWordsNotInserted("),
        )
        // A dictation is not the only thing that replaces the clipboard. These two are the app's
        // own successful clipboard writes, and each knows its write succeeded, which is why they
        // can retract a claim that a clipboard change by another app cannot.
        val shell = read("ui/AppShell.kt")
        assertEquals(
            "A clipboard replacement the app makes itself no longer retracts the standing 'press " +
                "and hold, then tap Paste' claim, so the History Copy button or vocabulary Export " +
                "leaves the user instructed to paste a transcript that is no longer there",
            2,
            Regex("DictationNotificationController\\.dismissWordsNotInserted\\(context\\)")
                .findAll(shell).count(),
        )
        listOf(
            "the History row's Copy button" to "ClipData.newPlainText(\"EnviousWispr\",",
            "vocabulary Export" to "ClipData.newPlainText(\"EnviousWispr vocabulary\",",
        ).forEach { (surface, write) ->
            // From the clipboard write to the moment the same handler tells the user it worked.
            // Both retractions have to happen inside that span, because after the toast the
            // handler is over and the false claim is already standing beside a fresh clipboard.
            val handler = slice(shell, write, "Toast.makeText(")
            assertTrue(
                "$surface replaces the clipboard without retracting the earlier dictation's " +
                    "claim on it: $handler",
                handler.contains("dismissWordsNotInserted(context)"),
            )
        }
    }

    /**
     * Slices [source] between two delimiters, failing when either is missing.
     *
     * `substringAfter` and `substringBefore` both return their receiver when the delimiter is
     * absent, which turns a moved line into a check that passes against anything.
     */
    private fun slice(source: String, after: String, before: String): String {
        assertTrue("The anchor '$after' was not found, so this check would scan the whole file", source.contains(after))
        val tail = source.substringAfter(after)
        assertTrue("The anchor '$before' was not found after '$after'", tail.contains(before))
        return tail.substringBefore(before)
    }

    /** Returns the argument text of each call to [prefix], parens balanced. */
    private fun callArguments(source: String, prefix: String): List<String> = buildList {
        var index = source.indexOf(prefix)
        while (index >= 0) {
            var cursor = index + prefix.length
            var depth = 1
            while (cursor < source.length && depth > 0) {
                when (source[cursor]) {
                    '(' -> depth += 1
                    ')' -> depth -= 1
                }
                cursor += 1
            }
            add(source.substring(index + prefix.length, cursor - 1))
            index = source.indexOf(prefix, cursor)
        }
    }

    private fun read(relativePath: String): String {
        // A wrong working directory must fail loudly rather than pass vacuously.
        val candidates = listOf(
            File("src/main/java/com/envi/wispr/$relativePath"),
            File("app/src/main/java/com/envi/wispr/$relativePath"),
        )
        return candidates.firstOrNull { it.isFile && it.length() > 0L }?.readText()
            ?: throw AssertionError(
                "$relativePath was not found from working directory ${File(".").absolutePath}",
            )
    }
}
