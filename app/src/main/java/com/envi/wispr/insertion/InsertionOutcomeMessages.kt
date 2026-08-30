package com.envi.wispr.insertion

import com.envi.wispr.history.TranscriptEntity
import com.envi.wispr.paste.AutoPasteAvailability
import com.envi.wispr.paste.InsertionHandoff

/**
 * The only value either user-facing surface of a missed dictation can be built from.
 *
 * **Its constructor is private and both factories live in the companion**, so nothing outside this
 * file can make one. That is the type-level half of the fix: `DictationNotificationController`
 * accepts an announcement and nothing else, so the durable notification cannot be posted from raw
 * strings a caller composed on its own. While that function took a `title` and a `detail`, a new
 * caller could post a notification saying the words were in History beside a toast saying they were
 * copied, which is exactly the pair of surfaces this type exists to keep together, and no drift
 * guard scanning today's callers could see the next one.
 *
 * Every field is derived inside one call from the same inputs: [toast] and [notificationBody] name
 * the same cause and the same destination because both come from the same classifier, and [haptic]
 * rides along because the decision to buzz is part of the same judgement. A buzz on a normal,
 * working clipboard dictation reports a failure that did not happen.
 */
class FallbackAnnouncement private constructor(
    val toast: String,
    val notificationTitle: String,
    val notificationBody: String,
    val haptic: Boolean,
) {
    companion object {

        /**
         * Decides whether a clipboard fallback is worth announcing, and with what words.
         *
         * `null` whenever auto-paste was not expected to work here and the words did reach the
         * place this dictation was aiming at. Words that missed it are announced whatever the
         * handoff and whatever the permission says, because that is the case the user has to act
         * on.
         */
        fun fallbackAnnouncement(
            autoPaste: AutoPasteAvailability,
            handoff: InsertionHandoff,
            clipboard: ClipboardOutcome,
            savedInHistory: Boolean,
        ): FallbackAnnouncement? {
            val cause = fallbackCause(autoPaste, handoff)
            if (cause == InsertionOutcomeMessages.FallbackCause.NONE &&
                !wordsMissedTheirDestination(clipboard, savedInHistory)
            ) {
                return null
            }
            val (title, body) = InsertionOutcomeMessages.fallbackNotification(
                clipboard,
                savedInHistory,
                cause,
            )
            return FallbackAnnouncement(
                toast = InsertionOutcomeMessages.fallbackToast(
                    autoPaste,
                    handoff,
                    clipboard,
                    savedInHistory,
                ),
                notificationTitle = title,
                notificationBody = body,
                haptic = true,
            )
        }

        /**
         * What the service says once it has ACCEPTED the text and then failed to place it.
         *
         * Never `null`: the service was alive, the words were handed to it, and every outcome here
         * leaves them somewhere other than the field the user was looking at. Both surfaces are
         * built from the same two facts, so the toast cannot claim a copy the notification denies.
         */
        fun serviceFallbackAnnouncement(
            reason: ServiceFallbackReason,
            clipboard: ClipboardOutcome,
            savedInHistory: Boolean,
        ): FallbackAnnouncement {
            val (title, body) = when (reason) {
                // The action ran, so the words may already be in the field. A flat "not inserted"
                // sends the user to paste text that is already there.
                ServiceFallbackReason.UNVERIFIED ->
                    InsertionOutcomeMessages.unverifiedNotification(clipboard, savedInHistory)
                ServiceFallbackReason.SENSITIVE_FIELD,
                ServiceFallbackReason.TARGET_NEVER_RETURNED,
                ServiceFallbackReason.NO_INSERTION_ACTION,
                ServiceFallbackReason.SERVICE_INTERRUPTED,
                ServiceFallbackReason.SERVICE_DESTROYED,
                // Always a fault here: the permission was granted, the service was alive, an
                // insertion was pending against a pinned field, and the field the user was looking
                // at did not receive the words. A field was in play on all five, so naming
                // auto-paste is the true sentence.
                -> InsertionOutcomeMessages.fallbackNotification(
                    clipboard,
                    savedInHistory,
                    InsertionOutcomeMessages.FallbackCause.AUTO_PASTE_MISSED_THE_FIELD,
                )
            }
            val toast = when (reason) {
                ServiceFallbackReason.UNVERIFIED ->
                    "Automatic insertion could not be confirmed. " +
                        hedgedDestinationSentence(clipboard, savedInHistory)
                ServiceFallbackReason.SENSITIVE_FIELD ->
                    "Protected field. " + destinationSentence(clipboard, savedInHistory)
                ServiceFallbackReason.TARGET_NEVER_RETURNED ->
                    "Your original text field did not come back. " +
                        destinationSentence(clipboard, savedInHistory)
                ServiceFallbackReason.NO_INSERTION_ACTION ->
                    "Automatic insertion was not available in that field. " +
                        destinationSentence(clipboard, savedInHistory)
                ServiceFallbackReason.SERVICE_INTERRUPTED ->
                    "Automatic insertion was interrupted. " +
                        destinationSentence(clipboard, savedInHistory)
                ServiceFallbackReason.SERVICE_DESTROYED ->
                    "Auto-paste stopped before your words arrived. " +
                        destinationSentence(clipboard, savedInHistory)
            }
            // Every reason here is a dictation the user watched fail to arrive in a field that was
            // there, with the permission granted and the service alive. All of them are worth a cue.
            return FallbackAnnouncement(
                toast = toast,
                notificationTitle = title,
                notificationBody = body,
                haptic = true,
            )
        }
    }
}

/**
 * Everything the app says about where a user's words ended up.
 *
 * One owner so the toast, the notification and the History row cannot drift apart, and no `Context`
 * so the copy is reachable from the fast gate. Each sentence answers the question the screen raises
 * and names the gesture that retrieves the words, per `content-brand.md`
 * RULE: brand-voice-relief-centered.
 *
 * Two rules hold everywhere below, and both exist because a surface that broke one shipped:
 *
 * 1. **Both halves of an announcement are computed once**, and the only type either surface can be
 *    posted from is [FallbackAnnouncement], whose constructor no caller can reach. A toast claiming
 *    a copy that failed while the notification beside it says the words are in History is
 *    unrepresentable, and neither surface can blame a mechanism the other does not.
 * 2. **A fallback is announced only when auto-paste was EXPECTED to work here**
 *    ([autoPasteWasExpectedToWork]), never merely because insertion did not happen. Both of that
 *    predicate's `when`s are exhaustive, so a new `AutoPasteAvailability` or `InsertionHandoff`
 *    member is a compile error rather than a silent new buzz.
 */
object InsertionOutcomeMessages {

    /**
     * Why the words are not in a text field, for the two surfaces that say it.
     *
     * One value rather than the Boolean it replaced, because that Boolean encoded two different
     * causes as one and then named the wrong one on the third. [TRANSCRIPT_NOT_SAVED] is decided in
     * the session owner BEFORE any field is consulted, so blaming auto-paste there told a user
     * dictating from the Quick Settings tile that a mechanism which was never in play had failed.
     * Both renderers below are exhaustive, so a new cause has to be given its sentence on each
     * surface rather than defaulting into somebody else's.
     */
    enum class FallbackCause {
        /** The words reached the place this dictation aimed at. No fault is named. */
        NONE,

        /** The permission is held and no service instance is bound. This is issue #16 itself. */
        AUTO_PASTE_NOT_CONNECTED,

        /** A field was in play, the service was alive, and the words did not arrive in it. */
        AUTO_PASTE_MISSED_THE_FIELD,

        /**
         * The transcript has no durable History row, so insertion was never requested and no field
         * was ever consulted. The clipboard is then the only copy, which is why it is announced.
         */
        TRANSCRIPT_NOT_SAVED,
    }

    /** The transient message shown the moment a dictation lands somewhere other than the field. */
    fun fallbackToast(
        autoPaste: AutoPasteAvailability,
        handoff: InsertionHandoff,
        clipboard: ClipboardOutcome,
        savedInHistory: Boolean,
    ): String {
        // Naming a fault is only true where there was one. Everywhere else the sentence states
        // where the words are and reports nothing.
        return toastCause(fallbackCause(autoPaste, handoff)) +
            destinationSentence(clipboard, savedInHistory)
    }

    /**
     * Title and body of the durable notification that outlives the toast.
     *
     * [cause] comes from the SAME classifier the toast uses, so the two surfaces cannot disagree
     * about what went wrong. Without it the notification named auto-paste as failed on a phone
     * where the user never granted the permission, which is the shipped defect one surface lower
     * down, and named it again where the transcript failed to save and no field was ever consulted.
     */
    fun fallbackNotification(
        clipboard: ClipboardOutcome,
        savedInHistory: Boolean,
        cause: FallbackCause,
    ): Pair<String, String> {
        val prefix = notificationCause(cause)
        return when {
            clipboard == ClipboardOutcome.COPIED -> "Your words are on the clipboard" to
                (prefix + "Press and hold the field, then tap Paste.")
            savedInHistory -> "Your words are saved in History" to
                (prefix + "Open EnviousWispr to copy them.")
            else -> "Your words could not be saved" to
                (prefix + "The transcript could not be stored. Please dictate again.")
        }
    }

    /**
     * The durable notification for the one outcome where the editor action RAN and could not be
     * read back. It hedges, because the words are very likely already in the field and telling the
     * user to paste would duplicate them.
     */
    fun unverifiedNotification(
        clipboard: ClipboardOutcome,
        savedInHistory: Boolean,
    ): Pair<String, String> = "Check your text field" to
        ("Automatic insertion could not be confirmed. " +
            hedgedDestinationSentence(clipboard, savedInHistory))

    /**
     * The line under "EnviousWispr is listening", written before the words exist.
     *
     * It may only state what is already decided at that instant, which rules out three sentences
     * this surface used to carry:
     *
     * 1. **No fault on PERMITTED_NOT_RUNNING.** The service is legitimately unbound during the
     *    normal connect window at every cold start, and this notification is built at the START of
     *    a session, before the handoff exists. Announcing a broken feature there is the mistake
     *    [autoPasteWasExpectedToWork] exists to stop, one surface earlier. The Home card
     *    downgrades on the same state for the same reason. A dictation that genuinely finds no
     *    service is told so afterwards, when it is a measured outcome rather than a guess.
     * 2. **No destination without the user's own clipboard setting.** `autoCopyToClipboard`, not
     *    the accessibility permission, decides whether the clipboard is where the words land. With
     *    it off the session writes nothing to the clipboard and History is the destination, so the
     *    old sentence sent the user to press and hold on somebody else's clip.
     * 3. **No destination at all before that setting has been READ.** [clipboard] is null until
     *    `AppPreferences` delivers the user's real values, which on a cold start is after this
     *    notification is built. The session owner used to pass its unloaded field, whose default
     *    auto-copy value is `true`, so a user with auto-copy off was promised the clipboard while
     *    the words went to History only. A default that looks exactly like a decided answer is the
     *    trap (`validation-discipline.md` FACT: silent-empty-traps, plausible-value traps), and the
     *    repair is a type that cannot carry one.
     */
    fun listeningDetail(
        autoPaste: AutoPasteAvailability,
        clipboard: ClipboardInsertionPolicy?,
    ): String {
        // Where the words will go is either not decided yet or not decided until the insertion is
        // attempted, so this says nothing about it.
        val destinationIsDecided =
            clipboard != null && autoPaste == AutoPasteAvailability.NOT_PERMITTED
        if (!destinationIsDecided) return "Speak naturally. Stop or cancel at any time."
        // Clipboard-only mode by the user's own choice, so the destination IS known in advance.
        // Not a fault for this user, so it is not reported as one.
        return if (clipboard.autoCopyToClipboard) {
            "Speak naturally. Your words will go to the clipboard."
        } else {
            "Speak naturally. Your words will be saved in History."
        }
    }

    /**
     * The durable record. Unmapped combinations fail open to the raw status rather than claiming a
     * destination the outcome does not support.
     */
    fun historyStatusLine(status: String, insertionResult: String): String = when {
        status != TranscriptEntity.STATUS_INSERTION_INTERRUPTED ->
            "Status: ${status.replace('_', ' ')}"
        insertionResult in CLIPBOARD_RESULTS -> "Not inserted. Copied to the clipboard."
        insertionResult in HISTORY_ONLY_RESULTS -> "Not inserted. Saved here only."
        // The action ran; only the read-back failed. A flat "Not inserted" sends the user to paste
        // text that is already in their field.
        insertionResult == InsertionResults.COPY_ONLY_UNVERIFIED ->
            "Insertion could not be confirmed. Also copied to the clipboard."
        insertionResult == InsertionResults.UNVERIFIED_NOT_COPIED ->
            "Insertion could not be confirmed. Also saved here."
        // A process died between the words being ready and any outcome being recorded, so where
        // they went is genuinely unknown and the sentence must not guess.
        insertionResult == InsertionResults.INSERTION_INTERRUPTED ->
            "Interrupted before insertion could be confirmed. Your words are saved here."
        else -> "Status: ${status.replace('_', ' ')}"
    }
}

/** Insertion outcomes whose words are on the system clipboard and are NOT in the field. */
private val CLIPBOARD_RESULTS = setOf(
    InsertionResults.CLIPBOARD,
    InsertionResults.COPY_ONLY,
    InsertionResults.COPY_ONLY_INTERRUPTED,
    InsertionResults.COPY_ONLY_SERVICE_DESTROYED,
    InsertionResults.COPY_ONLY_SENSITIVE,
)

/** Insertion outcomes whose words reached History and nowhere else. */
private val HISTORY_ONLY_RESULTS = setOf(
    InsertionResults.HISTORY_ONLY,
    InsertionResults.INSERTION_FAILED,
)

/**
 * Whether auto-paste was expected to place these words in a field for THIS dictation.
 *
 * The permission half first: with no auto-paste toggle of its own, declining the accessibility
 * permission IS clipboard-only mode, and it is a supported steady state because
 * `AppReadiness.coreReady` excludes accessibility. Nothing that happens afterwards is the failure of
 * a feature the user never connected.
 *
 * Then the handoff, which says whether there was ever a field in play. Four of the five entry points
 * cannot pin one: the tile, the Home button, onboarding practice and the side button pressed outside
 * an editor. For all four the clipboard is the DESIGNED destination and the dictation worked, so a
 * buzz, a long toast and a shade notification report a fault that did not happen, on the ordinary
 * use of the product.
 *
 * This is not the whole announcement decision. Words that missed the destination this dictation
 * aimed at are announced whatever this returns; [wordsMissedTheirDestination] owns that half.
 *
 * File-private, with every other classifier below it, because both the sentence library and
 * [FallbackAnnouncement]'s factories read them and nothing outside this file may.
 */
private fun autoPasteWasExpectedToWork(
    autoPaste: AutoPasteAvailability,
    handoff: InsertionHandoff,
): Boolean {
    val permitted = when (autoPaste) {
        AutoPasteAvailability.NOT_PERMITTED -> false
        AutoPasteAvailability.PERMITTED_NOT_RUNNING,
        AutoPasteAvailability.LIVE,
        -> true
    }
    if (!permitted) return false
    return when (handoff) {
        // The service took the text; this branch is not reached, and if it ever is, the
        // insertion is in flight and calling it a failure would be the same defect again.
        InsertionHandoff.SCHEDULED -> false
        // There was no field to insert into. Not a fault.
        InsertionHandoff.NO_PINNED_TARGET -> false
        // Nothing was dictated. Not a fault.
        InsertionHandoff.EMPTY_TEXT -> false
        // The permission is held and the service is gone. This is issue #16 itself.
        InsertionHandoff.SERVICE_NOT_RUNNING -> true
        // A previous insertion is still holding the path, so this one silently did not land.
        InsertionHandoff.INSERTION_ALREADY_PENDING -> true
        // The service was bound and did not answer within the handoff deadline.
        InsertionHandoff.SERVICE_DID_NOT_ANSWER -> true
        // Insertion was never attempted because the transcript has no durable row, so the
        // clipboard is the only copy and the user should know before overwriting it.
        InsertionHandoff.HISTORY_NOT_DURABLE -> true
    }
}

/**
 * Whether the words missed the place this dictation was aiming at.
 *
 * Exhaustive over [ClipboardOutcome] because its three values are the whole point: a copy that
 * failed is a fault even for a user with no auto-paste, and a copy that was never made is that
 * user's own setting and is not.
 */
private fun wordsMissedTheirDestination(
    clipboard: ClipboardOutcome,
    savedInHistory: Boolean,
): Boolean = when (clipboard) {
    // The words are where the user can paste them from.
    ClipboardOutcome.COPIED -> false
    // The destination the code aimed at is empty. The user will press and hold and find
    // somebody else's clip, so this is announced whatever else was true.
    ClipboardOutcome.WRITE_FAILED -> true
    // History was the destination by the user's own setting, so this is a success unless the
    // row is not there either.
    ClipboardOutcome.NOT_ATTEMPTED -> !savedInHistory
}

/**
 * The ONE sentence that says where the words are, for every surface that says it.
 *
 * Both arguments are outcomes the code measured: the clipboard write can genuinely fail, and the
 * History row can genuinely be absent. A caller cannot pass its own wording in, which is what let a
 * toast say "Transcript copied" beside a notification saying they were in History.
 */
private fun destinationSentence(
    clipboard: ClipboardOutcome,
    savedInHistory: Boolean,
): String = when {
    clipboard == ClipboardOutcome.COPIED -> "Your words are on the clipboard, ready to paste."
    savedInHistory -> "Your words are saved in History."
    else -> "Your words could not be saved. Please dictate again."
}

/** The same fact, for the one path where the words may ALSO already be in the field. */
private fun hedgedDestinationSentence(
    clipboard: ClipboardOutcome,
    savedInHistory: Boolean,
): String =
    when {
        clipboard == ClipboardOutcome.COPIED ->
            "Your words are also on the clipboard if they did not arrive."
        savedInHistory -> "Your words are also saved in History if they did not arrive."
        else -> "Check the field before dictating again."
    }

/**
 * What went wrong on this dictation, read once and rendered by both surfaces.
 *
 * The suppression comes first, so a handoff that is not a fault can never acquire a cause. The
 * `when` is exhaustive: a new [InsertionHandoff] member is a compile error here rather than a
 * sentence blaming whichever mechanism the last member happened to blame.
 */
private fun fallbackCause(
    autoPaste: AutoPasteAvailability,
    handoff: InsertionHandoff,
): InsertionOutcomeMessages.FallbackCause {
    if (!autoPasteWasExpectedToWork(autoPaste, handoff)) {
        return InsertionOutcomeMessages.FallbackCause.NONE
    }
    return when (handoff) {
        InsertionHandoff.SERVICE_NOT_RUNNING ->
            InsertionOutcomeMessages.FallbackCause.AUTO_PASTE_NOT_CONNECTED
        // Decided before any field was consulted: there may never have been one, and four of
        // the five entry points cannot have one.
        InsertionHandoff.HISTORY_NOT_DURABLE ->
            InsertionOutcomeMessages.FallbackCause.TRANSCRIPT_NOT_SAVED
        InsertionHandoff.INSERTION_ALREADY_PENDING,
        InsertionHandoff.SERVICE_DID_NOT_ANSWER,
        -> InsertionOutcomeMessages.FallbackCause.AUTO_PASTE_MISSED_THE_FIELD
        // Unreachable: the suppression above already returned NONE for these three.
        InsertionHandoff.SCHEDULED,
        InsertionHandoff.NO_PINNED_TARGET,
        InsertionHandoff.EMPTY_TEXT,
        -> InsertionOutcomeMessages.FallbackCause.NONE
    }
}

/** The cause as the toast leads with it, ending in the space before the destination. */
private fun toastCause(cause: InsertionOutcomeMessages.FallbackCause): String = when (cause) {
    InsertionOutcomeMessages.FallbackCause.NONE -> ""
    InsertionOutcomeMessages.FallbackCause.AUTO_PASTE_NOT_CONNECTED ->
        "Auto-paste is not connected. "
    InsertionOutcomeMessages.FallbackCause.AUTO_PASTE_MISSED_THE_FIELD ->
        "Automatic insertion did not reach your field. "
    InsertionOutcomeMessages.FallbackCause.TRANSCRIPT_NOT_SAVED ->
        "History could not store this dictation. "
}

/**
 * The same cause as the durable notification leads with. The two auto-paste causes read alike here
 * because the notification body has already lost the toast's context by the time it is read, and
 * "not connected" versus "did not reach" is a distinction the user cannot act on differently hours
 * later.
 */
private fun notificationCause(cause: InsertionOutcomeMessages.FallbackCause): String = when (cause) {
    InsertionOutcomeMessages.FallbackCause.NONE -> ""
    InsertionOutcomeMessages.FallbackCause.AUTO_PASTE_NOT_CONNECTED,
    InsertionOutcomeMessages.FallbackCause.AUTO_PASTE_MISSED_THE_FIELD,
    -> "Auto-paste did not reach your text field. "
    InsertionOutcomeMessages.FallbackCause.TRANSCRIPT_NOT_SAVED ->
        "History could not store this dictation. "
}
