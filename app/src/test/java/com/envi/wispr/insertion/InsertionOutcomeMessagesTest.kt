package com.envi.wispr.insertion

import com.envi.wispr.history.TranscriptEntity
import com.envi.wispr.paste.AutoPasteAvailability
import com.envi.wispr.paste.InsertionHandoff
import java.io.File
import java.lang.reflect.Modifier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PRODUCT OUTCOME for every row except the DRIFT GUARDs declared as such, which are not counted as
 * product coverage. When a product row fails, a user is told their words are somewhere they are
 * not, or a working dictation is reported to them as a failure.
 *
 * **This suite now enforces a CALM contract, and that is a change of subject rather than a change
 * of wording.** The surfaces it used to hold together, a toast beside a durable notification, are
 * one line; the failure haptic is gone; and History says nothing about delivery. macOS is the
 * reference and shows a single pill, `Copied. Press ⌘V to paste`, for every clipboard-only outcome.
 * The rows that protected agreement BETWEEN two surfaces are gone with the second surface, and
 * three rows below replace them by asserting that neither surface can come back.
 *
 * The clipboard write genuinely can fail: `keepTranscriptOnClipboard` refuses when newer clipboard
 * content is detected, and shipped copy claimed "Transcript copied" on that path regardless.
 */
class InsertionOutcomeMessagesTest {

    private val copiedLine = "Copied. Press and hold, then tap Paste."
    private val historyLine = "Saved in History. Open EnviousWispr to copy."
    private val lostLine = "Your words could not be saved. Please dictate again."

    // ---------------------------------------------------------------- whether it speaks at all

    /**
     * Issue #16 review, CLASS A. The four handoffs where auto-paste WAS expected to work speak; the
     * three where there was never a field to insert into stay silent, because for them the
     * clipboard is the designed destination and the dictation worked. Every member of the enum is
     * in one list or the other, so a new one cannot be added without deciding which it is.
     */
    @Test
    fun onlyTheHandoffsWhereAutoPasteWasExpectedToWorkSpeakAtAll() {
        val speaks = listOf(
            InsertionHandoff.SERVICE_NOT_RUNNING,
            InsertionHandoff.INSERTION_ALREADY_PENDING,
            InsertionHandoff.SERVICE_DID_NOT_ANSWER,
            InsertionHandoff.HISTORY_NOT_DURABLE,
        )
        val silent = listOf(
            InsertionHandoff.NO_PINNED_TARGET,
            InsertionHandoff.EMPTY_TEXT,
            InsertionHandoff.SCHEDULED,
        )
        assertEquals(
            "A handoff member is in neither list, so the enum grew and nobody decided whether the " +
                "user hears about it",
            InsertionHandoff.entries.toSet(),
            (speaks + silent).toSet(),
        )
        speaks.forEach { handoff ->
            assertEquals(
                "Handoff $handoff said the wrong thing",
                copiedLine,
                announce(AutoPasteAvailability.LIVE, handoff)?.line,
            )
        }
        silent.forEach { handoff ->
            assertNull(
                "Handoff $handoff interrupted a dictation that worked: there was no field to " +
                    "insert into and the clipboard is the designed destination",
                announce(AutoPasteAvailability.LIVE, handoff),
            )
        }
    }

    @Test
    fun aUserWhoNeverEnabledAutoPasteIsNotToldTheirWorkingDictationFailed() {
        InsertionHandoff.entries.forEach { handoff ->
            assertNull(
                "handoff=$handoff spoke to a user who never granted the permission. Declining it " +
                    "IS clipboard-only mode and is a supported steady state",
                announce(AutoPasteAvailability.NOT_PERMITTED, handoff),
            )
        }
    }

    /** The silence has a floor: words the user cannot reach are announced whatever else was true. */
    @Test
    fun silenceForAnUnpermittedUserStopsAtWordsThatCouldBeLost() {
        assertEquals(
            lostLine,
            announce(
                AutoPasteAvailability.NOT_PERMITTED,
                InsertionHandoff.NO_PINNED_TARGET,
                clipboard = ClipboardOutcome.WRITE_FAILED,
                savedInHistory = false,
            )?.line,
        )
    }

    @Test
    fun wordsThatReachedNeitherTheClipboardNorHistoryAreAlwaysAnnounced() {
        AutoPasteAvailability.entries.forEach { autoPaste ->
            InsertionHandoff.entries.forEach { handoff ->
                assertNotNull(
                    "autoPaste=$autoPaste handoff=$handoff said nothing about words that reached " +
                        "nowhere the user can get them back from",
                    announce(
                        autoPaste,
                        handoff,
                        clipboard = ClipboardOutcome.WRITE_FAILED,
                        savedInHistory = false,
                    ),
                )
            }
        }
    }

    /**
     * A copy that was never ATTEMPTED is the user's own auto-copy setting and History is then the
     * destination, so it is a success. A copy that was attempted and failed is a fault. The
     * three-valued [ClipboardOutcome] is what keeps those apart.
     */
    @Test
    fun aClipboardCopyTheUserTurnedOffIsNotReportedAsAFailedOne() {
        assertNull(
            "A dictation that went to History by the user's own setting was reported as a fault",
            announce(
                AutoPasteAvailability.LIVE,
                InsertionHandoff.NO_PINNED_TARGET,
                clipboard = ClipboardOutcome.NOT_ATTEMPTED,
                savedInHistory = true,
            ),
        )
        assertEquals(
            "The same setting with no History row left the words nowhere, and that is announced",
            lostLine,
            announce(
                AutoPasteAvailability.LIVE,
                InsertionHandoff.NO_PINNED_TARGET,
                clipboard = ClipboardOutcome.NOT_ATTEMPTED,
                savedInHistory = false,
            )?.line,
        )
    }

    // ------------------------------------------------------------------------- what it says

    /**
     * The whole of the calm contract in one row: the line states a measured destination and the
     * gesture that retrieves the words, and names no mechanism. A sentence blaming auto-paste has
     * to be right about a mechanism, and the one it was wrong about is the case where a dead
     * service makes the entry point unknowable.
     */
    @Test
    fun theLineNamesTheDestinationAndTheGestureAndNeverAFault() {
        val line = announce(
            AutoPasteAvailability.PERMITTED_NOT_RUNNING,
            InsertionHandoff.SERVICE_NOT_RUNNING,
        )!!.line
        assertEquals(copiedLine, line)
        listOf("Auto-paste", "auto-paste", "insertion", "failed", "could not reach").forEach { word ->
            assertFalse("The calm line blamed a mechanism with '$word': $line", line.contains(word))
        }
    }

    @Test
    fun aFailedClipboardWriteIsNeverDescribedAsCopied() {
        val line = announce(
            AutoPasteAvailability.PERMITTED_NOT_RUNNING,
            InsertionHandoff.SERVICE_NOT_RUNNING,
            clipboard = ClipboardOutcome.WRITE_FAILED,
            savedInHistory = true,
        )!!.line
        assertEquals(historyLine, line)
        assertFalse("A failed clipboard write claimed a copy", line.contains("Copied"))
    }

    @Test
    fun wordsThatReachedNothingAskTheUserToDictateAgain() {
        assertEquals(
            lostLine,
            announce(
                AutoPasteAvailability.LIVE,
                InsertionHandoff.SERVICE_NOT_RUNNING,
                clipboard = ClipboardOutcome.WRITE_FAILED,
                savedInHistory = false,
            )?.line,
        )
    }

    /**
     * The editor action RAN on this one path and only the read-back failed, so the words are very
     * likely already in the field. A flat instruction to paste would duplicate them.
     */
    @Test
    fun anInsertionThatRanAndCouldNotBeReadBackNeverInstructsAPaste() {
        val line = FallbackAnnouncement.serviceFallbackAnnouncement(
            ServiceFallbackReason.UNVERIFIED,
            ClipboardOutcome.COPIED,
            savedInHistory = true,
        ).line
        assertEquals("Copied too, if it did not arrive. Press and hold, then tap Paste.", line)
        assertFalse("An unconfirmed insertion was reported as a flat failure", line == copiedLine)
    }

    /**
     * Every other service-side reason gets the same calm destination line, because "interrupted"
     * and "the field never came back" are not things a user acts on differently. Enumerated from
     * the enum so a new reason cannot quietly inherit somebody else's sentence.
     */
    @Test
    fun everyServiceFallbackReasonExceptTheUnverifiedOneSaysTheSameCalmLine() {
        ServiceFallbackReason.entries
            .filter { it != ServiceFallbackReason.UNVERIFIED }
            .forEach { reason ->
                assertEquals(
                    "Reason $reason said something other than where the words are",
                    copiedLine,
                    FallbackAnnouncement.serviceFallbackAnnouncement(
                        reason,
                        ClipboardOutcome.COPIED,
                        savedInHistory = true,
                    ).line,
                )
            }
    }

    // ------------------------------------------------------------------------ how loud it is

    /**
     * The type carries ONE user-facing string. While it carried four fields, a caller could post a
     * durable notification and buzz the phone from the same value, and every row above would stay
     * green. Read by reflection so adding a field back is red here rather than at a call site
     * nobody is looking at.
     */
    @Test
    fun theAnnouncementCarriesOneUserFacingStringAndNothingElse() {
        // Instance fields only. `Companion` is a static field on this class and is where the
        // factories live, so counting it would make the guard fail against correct code.
        val fields = FallbackAnnouncement::class.java.declaredFields
            .filterNot { it.isSynthetic || Modifier.isStatic(it.modifiers) }
            .map { it.name }
        assertEquals(
            "FallbackAnnouncement grew a field. A clipboard fallback is one calm line: a second " +
                "field is a second surface, which is what made this event four announcements",
            listOf("line"),
            fields,
        )
    }

    /**
     * DRIFT GUARD, not product coverage. REVERT: `DictationNotificationController.wordsNotInserted`
     * and the 1002 result notification coming back, from any caller.
     *
     * macOS posts nothing durable for a clipboard fallback. Where the words went is a fact about
     * one moment, and a shade entry outlives the clipboard it describes: the next dictation
     * overwrites that clipboard and the standing instruction then sends the user to paste
     * somebody else's text.
     */
    @Test
    fun nothingPostsADurableNotificationForAClipboardFallback() {
        val offences = mainSources().filter { file ->
            val text = file.readText()
            text.contains("wordsNotInserted") || text.contains("RESULT_NOTIFICATION_ID")
        }
        assertEquals(
            "A durable fallback notification is back: ${offences.map { it.name }}",
            emptyList<File>(),
            offences,
        )
    }

    /**
     * DRIFT GUARD, not product coverage. REVERT: a failure haptic on either announcement path.
     *
     * Both sites are checked because they are the two places a missed dictation is spoken from, and
     * a guard on one of them would have said nothing about the other.
     */
    @Test
    fun noFallbackPathBuzzesThePhone() {
        val session = slice(
            read("ui/DictationSessionService.kt"),
            "private fun announceInsertionFallback(",
            "\n    /**",
        )
        assertFalse(
            "The session owner buzzes on a clipboard fallback, which reports a fault on an " +
                "ordinary outcome of a working product: $session",
            session.contains("vibrate("),
        )
        val service = slice(
            read("paste/PasteAccessibilityService.kt"),
            "private fun recordAndAnnounce(reason: ServiceFallbackReason, pending: PendingInsertion) {",
            "\n    private fun ",
        )
        assertFalse(
            "The accessibility service buzzes on a clipboard fallback: $service",
            service.contains("performResultHaptic("),
        )
    }

    // ----------------------------------------------------------------------------- History

    /**
     * History is read for weeks; where one dictation's words went is a fact about one moment. A red
     * "Not inserted" turned a clipboard fallback the user resolved in two seconds into a permanent
     * mark against a transcript that is intact and shown directly below it. macOS keeps delivery
     * outcomes out of History entirely.
     */
    @Test
    fun historySaysNothingAboutWhereOneDictationsWordsWent() {
        assertEquals("", InsertionOutcomeMessages.historyStatusLine(TranscriptEntity.STATUS_INSERTION_INTERRUPTED))
        assertEquals("", InsertionOutcomeMessages.historyStatusLine(TranscriptEntity.STATUS_COMPLETED))
    }

    /**
     * Two-way control: without it the row above passes against a function that returns "" for
     * everything, and a user whose dictation genuinely failed would see no reason at all.
     */
    @Test
    fun historyStillNamesAGenuineTranscriptFailure() {
        assertEquals(
            "Status: asr error",
            InsertionOutcomeMessages.historyStatusLine(TranscriptEntity.STATUS_ASR_ERROR),
        )
    }

    // ------------------------------------------------------------- unchanged by the quieting

    /**
     * The line under "EnviousWispr is listening" is written BEFORE the words exist, so it may only
     * state what is already decided. The last block is a DRIFT GUARD and is not counted as product
     * coverage; every row above it is Product Outcome.
     */
    @Test
    fun theListeningNotificationStatesOnlyWhatIsAlreadyDecided() {
        val copyOn = ClipboardInsertionPolicy(autoCopyToClipboard = true)
        val copyOff = ClipboardInsertionPolicy(autoCopyToClipboard = false)
        assertEquals(
            "Speak naturally. Stop or cancel at any time.",
            InsertionOutcomeMessages.listeningDetail(AutoPasteAvailability.LIVE, copyOn),
        )
        assertEquals(
            "A dictation started while the service is still binding, which happens at every cold " +
                "start, was told a working feature was broken",
            "Speak naturally. Stop or cancel at any time.",
            InsertionOutcomeMessages.listeningDetail(
                AutoPasteAvailability.PERMITTED_NOT_RUNNING,
                copyOn,
            ),
        )
        assertEquals(
            "Speak naturally. Your words will go to the clipboard.",
            InsertionOutcomeMessages.listeningDetail(AutoPasteAvailability.NOT_PERMITTED, copyOn),
        )
        // Auto-copy off means the session writes nothing to the clipboard, so sending the user
        // there points them at somebody else's clip.
        AutoPasteAvailability.entries.forEach { autoPaste ->
            val detail = InsertionOutcomeMessages.listeningDetail(autoPaste, copyOff)
            assertFalse(
                "autoPaste=$autoPaste promised the clipboard with auto-copy off: $detail",
                detail.contains("clipboard"),
            )
        }
        assertEquals(
            "Speak naturally. Your words will be saved in History.",
            InsertionOutcomeMessages.listeningDetail(AutoPasteAvailability.NOT_PERMITTED, copyOff),
        )
        // Settings not loaded yet. The destination is not decided at all, so no sentence may name
        // one, whatever the permission says.
        AutoPasteAvailability.entries.forEach { autoPaste ->
            val detail = InsertionOutcomeMessages.listeningDetail(autoPaste, clipboard = null)
            assertEquals(
                "autoPaste=$autoPaste named a destination before the user's settings were read",
                "Speak naturally. Stop or cancel at any time.",
                detail,
            )
        }
        // DRIFT GUARD. REVERT: `clipboard = ClipboardInsertionPolicy()` in promoteToForeground,
        // or dropping the `?` from the field it reads. Either restores a stand-in that reads as a
        // decided answer, and every row above stays green through both.
        val session = read("ui/DictationSessionService.kt")
        assertTrue(
            "clipboardPolicy is no longer nullable, so the session owner cannot tell an unloaded " +
                "setting from a decided one and the listening notification is built from a " +
                "default whose auto-copy value is true",
            session.contains("private var clipboardPolicy: ClipboardInsertionPolicy? = null"),
        )
        val promote = slice(session, "private fun promoteToForeground(", "\n    private fun ")
        assertTrue(
            "The listening notification is built from something other than the live, possibly " +
                "unloaded clipboard field, so it can state a destination nobody has decided",
            promote.contains("clipboard = clipboardPolicy,"),
        )
        assertFalse(
            "promoteToForeground constructs a ClipboardInsertionPolicy of its own. That default " +
                "carries autoCopyToClipboard = true and is indistinguishable from the user's " +
                "real setting: $promote",
            promote.contains("ClipboardInsertionPolicy("),
        )
    }

    /**
     * DRIFT GUARD, not product coverage. The insertion result still reaches the database and the
     * DAO's stale-row recovery still writes one, so a producer inventing a raw literal would still
     * put a value nothing else knows about into a user's history.
     *
     * What this row no longer protects, stated rather than dropped in silence: every produced
     * result used to need a History SENTENCE. There is no such sentence any more, because History
     * says nothing about delivery, so that requirement went with the surface rather than lapsing.
     */
    @Test
    fun noProducerWritesAnInsertionResultAsARawLiteral() {
        val values = producedInsertionResults()
        assertTrue("Reflection found no constants, so this test proves nothing", values.size >= 9)
        // The two constant HOMES are not producers. Every other file that names one of these
        // strings is writing it to the database.
        val constantHomes = setOf("InsertionResults.kt", "TranscriptEntity.kt")
        val sources = mainSources().filterNot { it.name in constantHomes }
        assertEquals(
            "A named constant home no longer exists, so this guard is scanning the wrong set",
            constantHomes,
            mainSources().map { it.name }.filter { it in constantHomes }.toSet(),
        )
        assertTrue("No main sources were scanned", sources.size > 20)
        val offences = buildList {
            sources.forEach { file ->
                val text = file.readText()
                values.forEach { value ->
                    if (text.contains("\"$value\"") || text.contains("'$value'")) {
                        add("${file.name} writes '$value' as a literal instead of InsertionResults")
                    }
                }
            }
        }
        assertEquals(offences.joinToString("\n"), emptyList<String>(), offences)
    }

    /**
     * DRIFT GUARD, not product coverage. `content-brand.md` RULE: no-dashes-in-user-facing-text is
     * otherwise unenforced on this file: that rule's path trigger names `strings.xml`, and every
     * user-facing string in this app is a hardcoded Kotlin literal.
     */
    @Test
    fun noUserFacingSentenceCarriesAnEmDashOrEnDash() {
        val sentences = buildList {
            for (autoPaste in AutoPasteAvailability.entries) {
                for (handoff in InsertionHandoff.entries) {
                    for (clipboard in ClipboardOutcome.entries) {
                        for (savedInHistory in listOf(true, false)) {
                            announce(autoPaste, handoff, clipboard, savedInHistory)
                                ?.let { add(it.line) }
                        }
                    }
                }
                add(InsertionOutcomeMessages.listeningDetail(autoPaste, null))
                add(
                    InsertionOutcomeMessages.listeningDetail(
                        autoPaste,
                        ClipboardInsertionPolicy(autoCopyToClipboard = true),
                    ),
                )
                add(
                    InsertionOutcomeMessages.listeningDetail(
                        autoPaste,
                        ClipboardInsertionPolicy(autoCopyToClipboard = false),
                    ),
                )
            }
            for (reason in ServiceFallbackReason.entries) {
                for (clipboard in ClipboardOutcome.entries) {
                    for (savedInHistory in listOf(true, false)) {
                        add(
                            FallbackAnnouncement.serviceFallbackAnnouncement(
                                reason,
                                clipboard,
                                savedInHistory,
                            ).line,
                        )
                    }
                }
            }
            add(InsertionOutcomeMessages.historyStatusLine(TranscriptEntity.STATUS_ASR_ERROR))
        }
        sentences.forEach { sentence ->
            assertFalse("Em-dash in user-facing copy: $sentence", sentence.contains('—'))
            assertFalse("En-dash in user-facing copy: $sentence", sentence.contains('–'))
        }
        assertTrue("The sweep found no sentences to check", sentences.isNotEmpty())
    }

    // ----------------------------------------------------------------------------- helpers

    private fun announce(
        autoPaste: AutoPasteAvailability,
        handoff: InsertionHandoff,
        clipboard: ClipboardOutcome = ClipboardOutcome.COPIED,
        savedInHistory: Boolean = true,
    ): FallbackAnnouncement? = FallbackAnnouncement.fallbackAnnouncement(
        autoPaste = autoPaste,
        handoff = handoff,
        clipboard = clipboard,
        savedInHistory = savedInHistory,
    )

    /**
     * Slices [source] between two delimiters, failing when either is missing.
     *
     * `substringAfter` and `substringBefore` both return their receiver when the delimiter is
     * absent, which turns a moved line into a check that passes against anything.
     */
    private fun slice(source: String, after: String, before: String): String {
        assertTrue(
            "The anchor '$after' was not found, so this check would scan the whole file",
            source.contains(after),
        )
        val tail = source.substringAfter(after)
        assertTrue("The anchor '$before' was not found after '$after'", tail.contains(before))
        return tail.substringBefore(before)
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

    /** Every constant the object declares, read back rather than hand-listed. */
    private fun producedInsertionResults(): List<String> =
        InsertionResults::class.java.declaredFields
            .filter { Modifier.isStatic(it.modifiers) && it.type == String::class.java }
            .map { it.isAccessible = true; it.get(null) as String }

    private fun mainSources(): List<File> {
        // A wrong working directory must fail loudly rather than pass vacuously.
        val root = listOf(File("src/main/java"), File("app/src/main/java"))
            .firstOrNull { it.isDirectory }
            ?: throw AssertionError(
                "app/src/main/java was not found from working directory ${File(".").absolutePath}",
            )
        return root.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
    }
}
