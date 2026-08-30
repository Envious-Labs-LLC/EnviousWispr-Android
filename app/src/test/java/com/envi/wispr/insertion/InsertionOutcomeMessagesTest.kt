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
 * PRODUCT OUTCOME for every row except the two declared DRIFT GUARDs at the end, which are not
 * counted as product coverage. When a product row fails, a user is told their words are somewhere
 * they are not, on the screen they open when the product looks broken, or is told a working
 * dictation failed.
 *
 * The clipboard write genuinely can fail: `keepTranscriptOnClipboard` refuses when newer clipboard
 * content is detected, and shipped copy claimed "Transcript copied" on that path regardless.
 */
class InsertionOutcomeMessagesTest {

    @Test
    fun aCrashedServiceIsNamedRatherThanBlamingTheField() {
        assertEquals(
            "Auto-paste is not connected. Your words are on the clipboard, ready to paste.",
            InsertionOutcomeMessages.fallbackToast(
                autoPaste = AutoPasteAvailability.PERMITTED_NOT_RUNNING,
                handoff = InsertionHandoff.SERVICE_NOT_RUNNING,
                clipboard = ClipboardOutcome.COPIED,
                savedInHistory = true,
            ),
        )
    }

    /**
     * Issue #16 review, CLASS A. The four handoffs where auto-paste WAS expected to work name the
     * failure; the three where there was never a field to insert into state where the words are
     * and report no fault. Every member of the enum appears in one list or the other, so a new one
     * cannot be added without deciding which it is.
     */
    @Test
    fun onlyTheHandoffsWhereAutoPasteWasExpectedToWorkNameAFailure() {
        val faults = listOf(
            InsertionHandoff.SERVICE_NOT_RUNNING to
                "Auto-paste is not connected. Your words are on the clipboard, ready to paste.",
            InsertionHandoff.INSERTION_ALREADY_PENDING to
                "Automatic insertion did not reach your field. Your words are on the clipboard, " +
                    "ready to paste.",
            InsertionHandoff.SERVICE_DID_NOT_ANSWER to
                "Automatic insertion did not reach your field. Your words are on the clipboard, " +
                    "ready to paste.",
            // Not an auto-paste sentence: this handoff is chosen in the session owner before any
            // field is consulted, and four of the five entry points never have one.
            InsertionHandoff.HISTORY_NOT_DURABLE to
                "History could not store this dictation. Your words are on the clipboard, " +
                    "ready to paste.",
        )
        val notFaults = listOf(
            InsertionHandoff.NO_PINNED_TARGET,
            InsertionHandoff.EMPTY_TEXT,
            InsertionHandoff.SCHEDULED,
        )
        assertEquals(
            "A handoff member is in neither list, so the enum grew and nobody decided whether it " +
                "is a fault the user should be told about",
            InsertionHandoff.entries.toSet(),
            (faults.map { it.first } + notFaults).toSet(),
        )
        faults.forEach { (handoff, expected) ->
            assertEquals(
                "Handoff $handoff produced the wrong sentence",
                expected,
                InsertionOutcomeMessages.fallbackToast(
                    autoPaste = AutoPasteAvailability.LIVE,
                    handoff = handoff,
                    clipboard = ClipboardOutcome.COPIED,
                    savedInHistory = true,
                ),
            )
        }
        notFaults.forEach { handoff ->
            assertEquals(
                "Handoff $handoff told the user automatic insertion failed, and it did not: there " +
                    "was no field to insert into and the clipboard is the designed destination",
                "Your words are on the clipboard, ready to paste.",
                InsertionOutcomeMessages.fallbackToast(
                    autoPaste = AutoPasteAvailability.LIVE,
                    handoff = handoff,
                    clipboard = ClipboardOutcome.COPIED,
                    savedInHistory = true,
                ),
            )
        }
    }

    @Test
    fun aFailedClipboardWriteIsNeverDescribedAsCopied() {
        val message = InsertionOutcomeMessages.fallbackToast(
            autoPaste = AutoPasteAvailability.PERMITTED_NOT_RUNNING,
            handoff = InsertionHandoff.SERVICE_NOT_RUNNING,
            clipboard = ClipboardOutcome.WRITE_FAILED,
            savedInHistory = true,
        )
        assertEquals(
            "Auto-paste is not connected. Your words are saved in History.",
            message,
        )
        assertFalse("A failed clipboard write must not mention the clipboard", message.contains("clipboard"))
    }

    @Test
    fun wordsThatReachedNothingAskTheUserToDictateAgain() {
        // No field was in play, so the sentence reports no fault. The words are still gone, which
        // is the half the user has to act on.
        assertEquals(
            "Your words could not be saved. Please dictate again.",
            InsertionOutcomeMessages.fallbackToast(
                autoPaste = AutoPasteAvailability.LIVE,
                handoff = InsertionHandoff.NO_PINNED_TARGET,
                clipboard = ClipboardOutcome.WRITE_FAILED,
                savedInHistory = false,
            ),
        )
        assertEquals(
            "Automatic insertion did not reach your field. Your words could not be saved. " +
                "Please dictate again.",
            InsertionOutcomeMessages.fallbackToast(
                autoPaste = AutoPasteAvailability.LIVE,
                handoff = InsertionHandoff.SERVICE_DID_NOT_ANSWER,
                clipboard = ClipboardOutcome.WRITE_FAILED,
                savedInHistory = false,
            ),
        )
    }

    /**
     * Issue #16 review, MAJ-1 and MAJ-6. Declining the accessibility permission IS clipboard-only
     * mode, because `AppReadiness.coreReady` excludes accessibility and there is no other toggle.
     * Announcing a fallback there buzzes the failure pattern and posts a shade notification after
     * every successful dictation, and reports the disconnection of something never connected.
     */
    @Test
    fun aUserWhoNeverEnabledAutoPasteIsNotToldTheirWorkingDictationFailed() {
        InsertionHandoff.entries.forEach { handoff ->
            assertNull(
                "Handoff $handoff announced a fallback to a user who never granted the permission",
                FallbackAnnouncement.fallbackAnnouncement(
                    autoPaste = AutoPasteAvailability.NOT_PERMITTED,
                    handoff = handoff,
                    clipboard = ClipboardOutcome.COPIED,
                    savedInHistory = true,
                ),
            )
        }
    }

    /** The same user must still hear about words the clipboard did NOT take. */
    @Test
    fun silenceForAnUnpermittedUserStopsAtWordsThatCouldBeLost() {
        val announcement = FallbackAnnouncement.fallbackAnnouncement(
            autoPaste = AutoPasteAvailability.NOT_PERMITTED,
            handoff = InsertionHandoff.SERVICE_NOT_RUNNING,
            clipboard = ClipboardOutcome.WRITE_FAILED,
            savedInHistory = true,
        )
        assertNotNull("Words the clipboard did not take must always be announced", announcement)
        assertEquals("Your words are saved in History", announcement!!.notificationTitle)
    }

    /** A user who DID connect auto-paste and lost it is told, because for them it is a fault. */
    @Test
    fun aUserWhoConnectedAutoPasteIsToldWhenItStopsReachingTheField() {
        val announcement = FallbackAnnouncement.fallbackAnnouncement(
            autoPaste = AutoPasteAvailability.PERMITTED_NOT_RUNNING,
            handoff = InsertionHandoff.SERVICE_NOT_RUNNING,
            clipboard = ClipboardOutcome.COPIED,
            savedInHistory = true,
        )
        assertNotNull(announcement)
        assertEquals(
            "Auto-paste is not connected. Your words are on the clipboard, ready to paste.",
            announcement!!.toast,
        )
        assertEquals("Your words are on the clipboard", announcement.notificationTitle)
        assertTrue("A fault the user can act on still earns its haptic", announcement.haptic)
    }

    /**
     * Issue #16 review, CLASS A, the whole table. One row per `InsertionHandoff` member at each
     * `AutoPasteAvailability`, because the defect was a suppression that named ONE state and let
     * every other combination through to a failure buzz, a long toast and a shade notification.
     *
     * Four of the five entry points cannot pin a target: the Quick Settings tile, the Home button,
     * onboarding practice, and the side button pressed outside a text field. All four produce
     * NO_PINNED_TARGET with a perfectly live service, so the wrong answer here buzzes a failure on
     * ordinary, working use of the product.
     */
    @Test
    fun onlyAFaultTheUserCanActOnIsAnnounced() {
        val announcedWhenPermitted = setOf(
            InsertionHandoff.SERVICE_NOT_RUNNING,
            InsertionHandoff.INSERTION_ALREADY_PENDING,
            InsertionHandoff.SERVICE_DID_NOT_ANSWER,
            InsertionHandoff.HISTORY_NOT_DURABLE,
        )
        AutoPasteAvailability.entries.forEach { autoPaste ->
            InsertionHandoff.entries.forEach { handoff ->
                val announcement = FallbackAnnouncement.fallbackAnnouncement(
                    autoPaste = autoPaste,
                    handoff = handoff,
                    clipboard = ClipboardOutcome.COPIED,
                    savedInHistory = true,
                )
                val expected = autoPaste != AutoPasteAvailability.NOT_PERMITTED &&
                    handoff in announcedWhenPermitted
                assertEquals(
                    "autoPaste=$autoPaste handoff=$handoff announced=${announcement != null}, " +
                        "expected=$expected. A dictation that reached the clipboard as designed " +
                        "must not buzz and notify; a dictation that lost the field must.",
                    expected,
                    announcement != null,
                )
            }
        }
    }

    /**
     * The floor under the suppression above. Whatever the handoff and whatever the permission, if
     * the words are in neither place the user can retrieve them from, they are told, because that
     * is the case where saying nothing loses them.
     */
    @Test
    fun wordsThatReachedNeitherTheClipboardNorHistoryAreAlwaysAnnounced() {
        AutoPasteAvailability.entries.forEach { autoPaste ->
            InsertionHandoff.entries.forEach { handoff ->
                listOf(ClipboardOutcome.WRITE_FAILED, ClipboardOutcome.NOT_ATTEMPTED)
                    .forEach { clipboard ->
                        val announcement = FallbackAnnouncement.fallbackAnnouncement(
                            autoPaste = autoPaste,
                            handoff = handoff,
                            clipboard = clipboard,
                            savedInHistory = false,
                        )
                        assertNotNull(
                            "autoPaste=$autoPaste handoff=$handoff clipboard=$clipboard lost the " +
                                "words silently",
                            announcement,
                        )
                        assertEquals(
                            "Your words could not be saved",
                            announcement!!.notificationTitle,
                        )
                    }
            }
        }
    }

    /**
     * The two ways of not being on the clipboard, which a single Boolean could not tell apart.
     *
     * A copy that was ATTEMPTED and failed is a fault for every user: the place they will press
     * and hold is empty. A copy that was never attempted is `autoCopyToClipboard` turned off, so
     * History is the destination and the dictation worked. Announcing that one buzzed and posted a
     * shade notification after every successful dictation for a user who chose History.
     */
    @Test
    fun aClipboardCopyTheUserTurnedOffIsNotReportedAsAFailedOne() {
        val turnedOff = FallbackAnnouncement.fallbackAnnouncement(
            autoPaste = AutoPasteAvailability.NOT_PERMITTED,
            handoff = InsertionHandoff.SERVICE_NOT_RUNNING,
            clipboard = ClipboardOutcome.NOT_ATTEMPTED,
            savedInHistory = true,
        )
        assertNull(
            "A dictation that went to History because the user turned auto-copy off is a " +
                "success, and announcing it reports a fault that did not happen",
            turnedOff,
        )
        val failed = FallbackAnnouncement.fallbackAnnouncement(
            autoPaste = AutoPasteAvailability.NOT_PERMITTED,
            handoff = InsertionHandoff.NO_PINNED_TARGET,
            clipboard = ClipboardOutcome.WRITE_FAILED,
            savedInHistory = true,
        )
        assertNotNull(
            "The clipboard was the destination for this dictation and it is empty, so the user " +
                "will press and hold and find somebody else's clip",
            failed,
        )
        assertEquals("Your words are saved in History", failed!!.notificationTitle)
    }

    /**
     * Issue #16 review, CLASS B. The service side used to pass a toast LITERAL saying "Transcript
     * copied" while the notification beside it was computed from the clipboard write's real
     * result, so on a failed copy the two surfaces stated opposite facts about one event in the
     * same second. Both are now composed from the same two measured facts.
     *
     * The property, not a sample of sentences: each surface names the clipboard exactly when the
     * clipboard took the words, and History exactly when it did not and the row survived.
     */
    @Test
    fun theToastAndTheDurableNotificationNeverNameDifferentDestinations() {
        ServiceFallbackReason.entries.forEach { reason ->
            ClipboardOutcome.entries.forEach { clipboard ->
                listOf(true, false).forEach { savedInHistory ->
                    val announcement = FallbackAnnouncement.serviceFallbackAnnouncement(
                        reason = reason,
                        clipboard = clipboard,
                        savedInHistory = savedInHistory,
                    )
                    val notification =
                        announcement.notificationTitle + " " + announcement.notificationBody
                    val where = "reason=$reason clipboard=$clipboard " +
                        "savedInHistory=$savedInHistory"
                    val onTheClipboard = clipboard == ClipboardOutcome.COPIED
                    listOf("toast" to announcement.toast, "notification" to notification)
                        .forEach { (surface, text) ->
                            assertEquals(
                                "$where: the $surface names the clipboard and the words are " +
                                    "${if (onTheClipboard) "" else "not "}on it",
                                onTheClipboard,
                                text.contains("clipboard"),
                            )
                            assertEquals(
                                "$where: the $surface names History and the words are " +
                                    "${if (savedInHistory) "" else "not "}there",
                                !onTheClipboard && savedInHistory,
                                text.contains("History"),
                            )
                        }
                }
            }
        }
    }

    /**
     * Issue #16 review, CLASS B on the third surface. The toast and the notification hedge on the
     * unverified path, where the editor action RAN and only the read-back failed. The History row
     * used to keep that hedge only when the clipboard write ALSO succeeded, so a failed copy
     * downgraded the same event to a flat "Not inserted" in the record the user reads later.
     *
     * `NOT_ATTEMPTED` is absent because the service always attempts the copy: the transcript goes
     * on the clipboard as part of the insertion itself.
     */
    @Test
    fun theHistoryRowKeepsTheHedgeTheOtherSurfacesGiveTheSameEvent() {
        ServiceFallbackReason.entries.forEach { reason ->
            listOf(ClipboardOutcome.COPIED, ClipboardOutcome.WRITE_FAILED).forEach { clipboard ->
                val row = historyLine(InsertionResults.forServiceFallback(reason, clipboard))
                val announcement = FallbackAnnouncement.serviceFallbackAnnouncement(
                    reason = reason,
                    clipboard = clipboard,
                    savedInHistory = true,
                )
                val where = "reason=$reason clipboard=$clipboard"
                val hedges = reason == ServiceFallbackReason.UNVERIFIED
                assertEquals(
                    "$where: the notification says '${announcement.notificationBody}'",
                    hedges,
                    announcement.notificationBody.contains("could not be confirmed"),
                )
                assertEquals(
                    "$where: the notification hedges and the History row says '$row', so the " +
                        "user reads a flat claim the code cannot make and pastes text that is " +
                        "already in their field",
                    hedges,
                    row.contains("could not be confirmed"),
                )
                assertEquals(
                    "$where: the History row says '$row' about a clipboard that is $clipboard",
                    clipboard == ClipboardOutcome.COPIED,
                    row.contains("clipboard"),
                )
            }
        }
    }

    /**
     * The one service-side outcome where the editor action RAN. Telling this user to press and
     * hold and tap Paste duplicates the words already in their field.
     */
    @Test
    fun anInsertionThatRanAndCouldNotBeReadBackNeverInstructsAPaste() {
        val announcement = FallbackAnnouncement.serviceFallbackAnnouncement(
            reason = ServiceFallbackReason.UNVERIFIED,
            clipboard = ClipboardOutcome.COPIED,
            savedInHistory = true,
        )
        assertEquals("Check your text field", announcement.notificationTitle)
        assertFalse(
            "Unverified copy must not instruct a paste: ${announcement.notificationBody}",
            announcement.notificationBody.contains("tap Paste"),
        )
        assertFalse(
            "Unverified copy must not instruct a paste: ${announcement.toast}",
            announcement.toast.contains("ready to paste"),
        )
        // The other three reasons DID fail to place the words, so they say so plainly.
        assertEquals(
            "Your original text field did not come back. Your words are on the clipboard, " +
                "ready to paste.",
            FallbackAnnouncement.serviceFallbackAnnouncement(
                reason = ServiceFallbackReason.TARGET_NEVER_RETURNED,
                clipboard = ClipboardOutcome.COPIED,
                savedInHistory = true,
            ).toast,
        )
    }

    @Test
    fun theDurableNotificationNamesTheGestureThatRetrievesTheWords() {
        assertEquals(
            "Your words are on the clipboard" to
                "Auto-paste did not reach your text field. Press and hold the field, then tap Paste.",
            InsertionOutcomeMessages.fallbackNotification(
                clipboard = ClipboardOutcome.COPIED,
                savedInHistory = true,
                cause = InsertionOutcomeMessages.FallbackCause.AUTO_PASTE_MISSED_THE_FIELD,
            ),
        )
        assertEquals(
            "Your words are saved in History" to
                "Auto-paste did not reach your text field. Open EnviousWispr to copy them.",
            InsertionOutcomeMessages.fallbackNotification(
                clipboard = ClipboardOutcome.WRITE_FAILED,
                savedInHistory = true,
                cause = InsertionOutcomeMessages.FallbackCause.AUTO_PASTE_MISSED_THE_FIELD,
            ),
        )
        assertEquals(
            "Your words could not be saved" to
                "Auto-paste did not reach your text field. The transcript could not be stored. " +
                    "Please dictate again.",
            InsertionOutcomeMessages.fallbackNotification(
                clipboard = ClipboardOutcome.WRITE_FAILED,
                savedInHistory = false,
                cause = InsertionOutcomeMessages.FallbackCause.AUTO_PASTE_MISSED_THE_FIELD,
            ),
        )
    }

    /**
     * The notification is the surface that outlives the toast, so it is the one the user reads
     * later. It must not name auto-paste as failed on a phone where it was never connected: that
     * is the same wrong sentence the toast used to carry, one surface down.
     */
    @Test
    fun theDurableNotificationBlamesAutoPasteOnlyWhereItWasExpectedToWork() {
        val forAUserWithoutAutoPaste = FallbackAnnouncement.fallbackAnnouncement(
            autoPaste = AutoPasteAvailability.NOT_PERMITTED,
            handoff = InsertionHandoff.SERVICE_NOT_RUNNING,
            clipboard = ClipboardOutcome.WRITE_FAILED,
            savedInHistory = true,
        )
        assertNotNull(
            "A clipboard copy that was attempted and failed is a fault for every user",
            forAUserWithoutAutoPaste,
        )
        assertEquals(
            "The notification told a user who never granted the permission that auto-paste failed",
            "Open EnviousWispr to copy them.",
            forAUserWithoutAutoPaste!!.notificationBody,
        )
        assertEquals("Your words are saved in History.", forAUserWithoutAutoPaste.toast)
        val forAUserWhoLostIt = FallbackAnnouncement.fallbackAnnouncement(
            autoPaste = AutoPasteAvailability.PERMITTED_NOT_RUNNING,
            handoff = InsertionHandoff.SERVICE_NOT_RUNNING,
            clipboard = ClipboardOutcome.WRITE_FAILED,
            savedInHistory = true,
        )
        assertEquals(
            "Auto-paste did not reach your text field. Open EnviousWispr to copy them.",
            forAUserWhoLostIt!!.notificationBody,
        )
    }

    /**
     * The unverified route performed the editor action and could not read it back, so the words are
     * probably already in the field. Telling this user to paste duplicates their dictation.
     */
    @Test
    fun anUnconfirmedInsertionIsNeverAnnouncedAsAFailureToPasteFrom() {
        val (title, body) = InsertionOutcomeMessages.unverifiedNotification(
            clipboard = ClipboardOutcome.COPIED,
            savedInHistory = true,
        )
        assertEquals("Check your text field", title)
        assertFalse("Unverified copy must not instruct a paste: $body", body.contains("tap Paste"))
        assertTrue(body.contains("could not be confirmed"))
    }

    /**
     * Written at the START of a session, so it may only state what is already decided. Three
     * sentences it used to carry were not: a fault on the ordinary cold-start connect window, a
     * destination read off the accessibility permission rather than off the setting that picks the
     * destination, and a destination read off that setting BEFORE it had been loaded.
     *
     * The last of those is a property of the CALLER, not of this function, so the final block reads
     * the session owner. A pure test of correct inputs stays green while `promoteToForeground`
     * hands over a default whose auto-copy value is `true`, which is the shipped defect: on a cold
     * start with auto-copy off and accessibility not permitted, the notification promised the
     * clipboard while the words went to History only. That block is a DRIFT GUARD and is not
     * counted as product coverage; every row above it is Product Outcome.
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

    @Test
    fun theHistoryRowSaysWhereTheWordsWentInsteadOfNamingAStatusConstant() {
        assertEquals(
            "Not inserted. Copied to the clipboard.",
            historyLine(InsertionResults.CLIPBOARD),
        )
        assertEquals(
            "Not inserted. Copied to the clipboard.",
            historyLine(InsertionResults.COPY_ONLY_INTERRUPTED),
        )
        assertEquals(
            "Not inserted. Saved here only.",
            historyLine(InsertionResults.INSERTION_FAILED),
        )
        assertEquals(
            "Not inserted. Saved here only.",
            historyLine(InsertionResults.HISTORY_ONLY),
        )
    }

    /**
     * The action RAN on this path and only the read-back failed, so a flat "Not inserted" sends the
     * user to paste text already in their field.
     */
    @Test
    fun anUnconfirmedInsertionIsNotRecordedAsAFlatFailure() {
        assertEquals(
            "Insertion could not be confirmed. Also copied to the clipboard.",
            historyLine(InsertionResults.COPY_ONLY_UNVERIFIED),
        )
    }

    /** Written by the DAO's stale-row recovery, which fires exactly when a process was killed. */
    @Test
    fun aRowRecoveredAfterAProcessDeathClaimsNoDestinationItCannotKnow() {
        val line = historyLine(InsertionResults.INSERTION_INTERRUPTED)
        assertEquals("Interrupted before insertion could be confirmed. Your words are saved here.", line)
        assertFalse("Where the words went is unknown on this path", line.contains("clipboard"))
    }

    /**
     * The population, not a sample. This is what makes the two rows above impossible to add without
     * also mapping them: `insertion_interrupted` was a live producer with no sentence, written by a
     * third writer (the DAO) that a hand-listed sweep missed.
     */
    @Test
    fun everyProducedInsertionResultHasASentence() {
        val values = producedInsertionResults()
        assertTrue("Reflection found no constants, so this test proves nothing", values.size >= 9)
        values.forEach { value ->
            val line = historyLine(value)
            assertFalse(
                "insertionResult '$value' has no sentence and falls through to machine-speak: $line",
                line.startsWith("Status: "),
            )
        }
    }

    /** Two-way control: without it the row above could pass by mapping everything to one line. */
    @Test
    fun anUnmappedOutcomeClaimsNoDestination() {
        assertEquals("Status: insertion interrupted", historyLine("a_result_nobody_mapped"))
        assertEquals(
            "Status: asr error",
            InsertionOutcomeMessages.historyStatusLine(TranscriptEntity.STATUS_ASR_ERROR, "asr_error"),
        )
    }

    /**
     * DRIFT GUARD, not product coverage. The enumeration above is only as complete as the object,
     * so a producer that writes a raw literal instead of a constant would reopen exactly the hole
     * this closed.
     */
    @Test
    fun noProducerWritesAnInsertionResultAsARawLiteral() {
        val values = producedInsertionResults()
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
                            add(
                                InsertionOutcomeMessages.fallbackToast(
                                    autoPaste,
                                    handoff,
                                    clipboard,
                                    savedInHistory,
                                ),
                            )
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
            for (clipboard in ClipboardOutcome.entries) {
                for (savedInHistory in listOf(true, false)) {
                    for (cause in InsertionOutcomeMessages.FallbackCause.entries) {
                        val (title, body) = InsertionOutcomeMessages.fallbackNotification(
                            clipboard,
                            savedInHistory,
                            cause,
                        )
                        add(title)
                        add(body)
                    }
                }
            }
            for (reason in ServiceFallbackReason.entries) {
                for (clipboard in ClipboardOutcome.entries) {
                    for (savedInHistory in listOf(true, false)) {
                        val announcement = FallbackAnnouncement.serviceFallbackAnnouncement(
                            reason,
                            clipboard,
                            savedInHistory,
                        )
                        add(announcement.toast)
                        add(announcement.notificationTitle)
                        add(announcement.notificationBody)
                    }
                }
            }
            producedInsertionResults().forEach { add(historyLine(it)) }
        }
        sentences.forEach { sentence ->
            assertFalse("Em-dash in user-facing copy: $sentence", sentence.contains('—'))
            assertFalse("En-dash in user-facing copy: $sentence", sentence.contains('–'))
        }
        assertTrue("The sweep found no sentences to check", sentences.isNotEmpty())
    }

    private fun historyLine(insertionResult: String): String =
        InsertionOutcomeMessages.historyStatusLine(
            TranscriptEntity.STATUS_INSERTION_INTERRUPTED,
            insertionResult,
        )

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
