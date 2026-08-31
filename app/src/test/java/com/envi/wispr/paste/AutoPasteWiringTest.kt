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
     * REVERT: dropping the pin result on the floor in `beginSession`, or handing insertion's own
     * answer straight to the branch.
     *
     * Either one restores a silence that no announcement test can see, because both surfaces are
     * then told the truthful thing about the wrong moment. The service can die before the pin and
     * be rebound before insertion, or be pinned and then torn down mid-dictation; both arrive at
     * insertion as a live service with nothing pinned, which is the destination four of the five
     * entry points are DESIGNED to reach. The correction has to happen where the handoff is
     * produced, once, or the toast, the notification, the History row and the log stop agreeing.
     */
    @Test
    fun theHandoffIsJudgedByWhatTheStartSawNotOnlyByWhatInsertionFound() {
        val source = read("ui/DictationSessionService.kt")
        val begin = slice(source, "private fun beginSession() {", "\n    private fun ")
        assertTrue(
            "beginSession discards the pin result again, so nothing can tell a dead service at " +
                "the start from the four entry points that never had a target: $begin",
            begin.contains("targetPinAtStart = PasteAccessibilityService.pinTargetForDictation()"),
        )
        assertEquals(
            "The handoff must pass through InsertionJudgement.handoffToJudge exactly once, at the " +
                "point it is produced. Zero sites is the shipped silence; two is two owners of one " +
                "decision, which is the defect issue #16 itself was",
            1,
            Regex("InsertionJudgement\\.handoffToJudge\\(").findAll(source).count(),
        )
        assertTrue(
            "handoffToJudge is no longer reading the value the START recorded, so it can only " +
                "repeat what insertion already said",
            source.contains("startPin = targetPinAtStart"),
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
     *
     * Three of them moved from the deleted Home screen onto the Permissions page (#47). The names
     * below changed; not one evidence literal did, which is what makes the move checkable.
     */
    @Test
    fun noReadinessSurfaceReportsThePermissionAsIfItWereLiveness() {
        // Both files are read because the surfaces live in two of them since #47: the Permissions
        // page in `ui/SettingsPages.kt`, onboarding step 4 in `ui/AppShell.kt`. `read` throws on a
        // missing or empty file, so renaming one fails loudly rather than shrinking the haystack.
        val shell = read("ui/AppShell.kt")
        val pages = read("ui/SettingsPages.kt")
        val source = shell + "\n" + pages
        assertFalse(
            "A readiness screen reads the permission fact directly, so a surface can report a " +
                "crashed service as Ready",
            source.contains("accessibilityPermitted"),
        )
        // The Insert chip is checked inside its OWN block. Its evidence line also appears on the
        // onboarding SetupActionCard, so scanning the whole file stays green after the chip stops
        // consuming the combined answer and goes back to coreReady, which excludes accessibility.
        val insertChip = slice(source, "label = \"Insert\",", ")")
        val surfaces = listOf(
            Triple(
                "the Permissions page auto-paste row",
                "AutoPasteAvailability.LIVE -> \"Ready for right-button dictation\"",
                source,
            ),
            Triple(
                "the Permissions page 'not connected' card",
                "autoPaste == AutoPasteAvailability.PERMITTED_NOT_RUNNING",
                source,
            ),
            Triple(
                "the Permissions card's status dot and its screen-reader label",
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
     * REVERT: making `FallbackAnnouncement`'s constructor reachable, or the service composing its
     * own sentence instead of asking for one.
     *
     * These hold against a caller this file has never seen. Scanning `PasteAccessibilityService`
     * for a toast literal was the old shape and could only ever see today's callers.
     *
     * DELETED WITH THE SECOND SURFACE, stated rather than dropped in silence: this used to pin
     * `wordsNotInserted(Context, FallbackAnnouncement)`, so no caller could compose a durable
     * notification contradicting its own toast. There is no durable notification, so that
     * requirement has no subject. Two stronger properties replace it, both in
     * `InsertionOutcomeMessagesTest`: `nothingPostsADurableNotificationForAClipboardFallback`
     * fails if one comes back at all, and `theAnnouncementCarriesOneUserFacingStringAndNothingElse`
     * fails if the type grows a second surface to disagree with the first.
     */
    @Test
    fun theServiceCannotHandItsOwnSentenceToTheUser() {
        val constructors = FallbackAnnouncement::class.java.declaredConstructors
            .filterNot { it.isSynthetic }
        assertTrue("FallbackAnnouncement declares no constructor at all", constructors.isNotEmpty())
        val reachable = constructors.filterNot { Modifier.isPrivate(it.modifiers) }
        assertEquals(
            "FallbackAnnouncement can be constructed from outside its own file, so any caller can " +
                "hand the user a sentence nothing derived: " +
                reachable.joinToString { it.toString() },
            emptyList<String>(),
            reachable.map { it.toString() },
        )
        // Which surface a service SHOWS is not visible to reflection, and one call site is what
        // keeps it reading from the same value.
        val source = read("paste/PasteAccessibilityService.kt")
        val toastCalls = Regex("Toast\\.makeText\\(").findAll(source).count()
        assertEquals("The service shows a toast from more than one place", 1, toastCalls)
        assertTrue(
            "The service composes a toast from something other than the announcement, so it can " +
                "state a destination nothing measured",
            source.contains("Toast.makeText(this, announcement.line, Toast.LENGTH_LONG)"),
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

    /*
     * DELETED, and recorded rather than dropped in silence:
     * `eachDictationRetractsThePreviousOnesClipboardClaim`.
     *
     * It protected the retraction of notification 1002, a present-tense claim about a clipboard the
     * next dictation was about to overwrite, from its three callers: `beginSession`, the History
     * row's Copy button and vocabulary Export. There is no durable claim to retract, so the
     * requirement has no subject rather than having lapsed.
     *
     * What replaces it is strictly stronger, because a claim that cannot be posted cannot go stale:
     * `InsertionOutcomeMessagesTest.nothingPostsADurableNotificationForAClipboardFallback` sweeps
     * every main source and fails if `wordsNotInserted` or `RESULT_NOTIFICATION_ID` returns.
     */

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
