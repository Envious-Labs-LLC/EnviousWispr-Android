package com.envi.wispr.insertion

import com.envi.wispr.history.TranscriptEntity
import com.envi.wispr.paste.AutoPasteAvailability
import com.envi.wispr.paste.InsertionHandoff

/**
 * The only value a clipboard fallback can be spoken from, and it is ONE calm line.
 *
 * **Its constructor is private and both factories live in the companion**, so nothing outside this
 * file can make one, and there is now only one surface to keep honest rather than two to keep in
 * agreement.
 *
 * **WHY ONE LINE, AND WHY NO HAPTIC AND NO SHADE NOTIFICATION.** macOS is the reference product and
 * it shows a single delivery pill, `Copied. Press ⌘V to paste`, for every clipboard-only outcome,
 * with no buzz, nothing durable, and nothing about delivery in History. Its only louder case is a
 * first Accessibility denial carrying a Grant action, which on Android is the Permissions page's
 * setup card and its auto-paste row rather than an interruption. This branch shipped a buzz, a long toast, a shade
 * notification that outlived the dictation and a red History label, which is four announcements of
 * one ordinary event. Query the contract rather than re-deciding it:
 * `sqlite3 -line ~/.claude/knowledge/enviouswispr/catalog.db "SELECT surface, exact_text FROM
 * user_copy WHERE platform_key='macos' AND feature_slug='multi-route-paste';"`
 *
 * **THE LINE NAMES NO FAULT, AND THAT IS WHAT MAKES IT SAFE TO SHOW.** It states where the words
 * are and the gesture that retrieves them. A sentence blaming auto-paste has to be right about a
 * mechanism, and it was wrong twice: once where the user had never granted the permission, once
 * where the transcript failed to save and no field was ever consulted. A sentence that only reports
 * the destination is measured, not inferred, so the one case it cannot get wrong is the one where a
 * dead service makes the entry point unknowable, which is reasoned about on
 * `InsertionHandoff.SERVICE_NOT_RUNNING` in [autoPasteWasExpectedToWork].
 */
class FallbackAnnouncement private constructor(val line: String) {
    companion object {

        /**
         * Decides whether a clipboard fallback is worth a line, and what it says.
         *
         * `null` whenever auto-paste was not expected to work here and the words did reach the
         * place this dictation was aiming at. Words that missed it get a line whatever the handoff
         * and whatever the permission says, because that is the case the user has to act on.
         */
        fun fallbackAnnouncement(
            autoPaste: AutoPasteAvailability,
            handoff: InsertionHandoff,
            clipboard: ClipboardOutcome,
            savedInHistory: Boolean,
        ): FallbackAnnouncement? {
            if (!autoPasteWasExpectedToWork(autoPaste, handoff) &&
                !wordsMissedTheirDestination(clipboard, savedInHistory)
            ) {
                return null
            }
            return FallbackAnnouncement(destinationLine(clipboard, savedInHistory))
        }

        /**
         * What the service says once it has ACCEPTED the text and then failed to place it.
         *
         * Never `null`: the service was alive, the words were handed to it, and every outcome here
         * leaves them somewhere other than the field the user was looking at.
         *
         * The only distinction that survives into the copy is whether the editor action RAN. On
         * that one path the words are very likely already in the field, so a flat instruction to
         * paste would duplicate them; every other reason gets the same calm destination line,
         * because "interrupted" and "the field never came back" are not things a user acts on
         * differently.
         */
        fun serviceFallbackAnnouncement(
            reason: ServiceFallbackReason,
            clipboard: ClipboardOutcome,
            savedInHistory: Boolean,
        ): FallbackAnnouncement = FallbackAnnouncement(
            when (reason) {
                ServiceFallbackReason.UNVERIFIED ->
                    hedgedDestinationLine(clipboard, savedInHistory)
                ServiceFallbackReason.SENSITIVE_FIELD,
                ServiceFallbackReason.TARGET_NEVER_RETURNED,
                ServiceFallbackReason.NO_INSERTION_ACTION,
                ServiceFallbackReason.SERVICE_INTERRUPTED,
                ServiceFallbackReason.SERVICE_DESTROYED,
                -> destinationLine(clipboard, savedInHistory)
            },
        )
    }
}

/**
 * Everything the app says about where a user's words ended up.
 *
 * One owner so every surface says the same thing, and no `Context` so the copy is reachable from
 * the fast gate. Each sentence answers the question the screen raises and names the gesture that
 * retrieves the words, per `content-brand.md` RULE: brand-voice-relief-centered.
 *
 * Two rules hold everywhere below, and both exist because a surface that broke one shipped:
 *
 * 1. **A fallback is spoken only when auto-paste was EXPECTED to work here**
 *    ([autoPasteWasExpectedToWork]), never merely because insertion did not happen. Both of that
 *    predicate's `when`s are exhaustive, so a new `AutoPasteAvailability` or `InsertionHandoff`
 *    member is a compile error rather than a silent new interruption.
 * 2. **What is said is a measured destination, never an inferred cause.** See
 *    [FallbackAnnouncement].
 */
object InsertionOutcomeMessages {

    /**
     * The line under "EnviousWispr is listening", written before the words exist.
     *
     * It may only state what is already decided at that instant, which rules out three sentences
     * this surface used to carry:
     *
     * 1. **No fault on PERMITTED_NOT_RUNNING.** The service is legitimately unbound during the
     *    normal connect window at every cold start, and this notification is built at the START of
     *    a session, before the handoff exists. Announcing a broken feature there is the mistake
     *    [autoPasteWasExpectedToWork] exists to stop, one surface earlier. The Permissions page's
     *    setup card downgrades on the same state for the same reason. A dictation that genuinely finds no
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
     * The durable record, and it says NOTHING about delivery.
     *
     * macOS keeps delivery outcomes out of History entirely, and the reason holds here: where a
     * dictation's words went is a fact about one moment, while a History row is read for weeks. A
     * red "Not inserted" turned a clipboard fallback that the user resolved in two seconds into a
     * permanent mark against the transcript.
     *
     * The empty string is the whole of that decision, so a caller renders nothing rather than
     * rendering a blank label. What survives is the genuine transcript failure: no audio, no
     * transcription, a session that errored. Those are not deliveries.
     */
    fun historyStatusLine(status: String): String = when (status) {
        // Delivery outcome. The transcript itself is intact and is shown below this line.
        TranscriptEntity.STATUS_INSERTION_INTERRUPTED -> ""
        TranscriptEntity.STATUS_COMPLETED -> ""
        else -> "Status: ${status.replace('_', ' ')}"
    }
}

/**
 * Whether auto-paste was expected to place these words in a field for THIS dictation.
 *
 * The permission half first: with no auto-paste toggle of its own, declining the accessibility
 * permission IS clipboard-only mode, and it is a supported steady state because
 * `AppReadiness.coreReady` excludes accessibility. Nothing that happens afterwards is the failure of
 * a feature the user never connected.
 *
 * Then the handoff, which says whether there was ever a field in play. Four of the five entry points
 * cannot pin one: the tile, the app's microphone button, onboarding practice and the side button
 * pressed outside an editor. For all four the clipboard is the DESIGNED destination and the dictation worked, so
 * speaking there reports an event that is not news, on the ordinary use of the product.
 *
 * This is not the whole decision. Words that missed the destination this dictation aimed at are
 * announced whatever this returns; [wordsMissedTheirDestination] owns that half.
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
        // There was no field to insert into. Not news.
        InsertionHandoff.NO_PINNED_TARGET -> false
        // Nothing was dictated. Not news.
        InsertionHandoff.EMPTY_TEXT -> false
        // The permission is held and the service is gone. This is issue #16 itself, and it
        // speaks even from an entry point that never had a field, because when the service is
        // dead nothing on the phone can say whether the user was in an editor: only the
        // accessibility service reads another app's focus and it is the thing that is gone. The
        // two available answers are to assume a field was in play, which adds a line to a tile
        // dictation taken while auto-paste happens to be dead, or to assume none was, which is
        // the silence issue #16 reported. The calm line makes the first cheap: it names no
        // fault, so on a tile dictation it says only where the words are, which is true, and it
        // cannot buzz and does not outlive the moment. A known limit, because the window is not
        // closable from this side of the accessibility boundary (`code-design-rules.md`
        // RULE: close-the-window-never-handle-it).
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
 * History row can genuinely be absent. A caller cannot pass its own wording in.
 *
 * The first two lines are the calm case and read like macOS's delivery pill: the destination, then
 * the gesture. The third is not a delivery at all. The words exist nowhere the user can reach, so
 * it is the one line here that reports a failure, and it must keep doing so.
 */
private fun destinationLine(
    clipboard: ClipboardOutcome,
    savedInHistory: Boolean,
): String = when {
    clipboard == ClipboardOutcome.COPIED -> "Copied. Press and hold, then tap Paste."
    savedInHistory -> "Saved in History. Open EnviousWispr to copy."
    else -> "Your words could not be saved. Please dictate again."
}

/** The same fact, for the one path where the words may ALSO already be in the field. */
private fun hedgedDestinationLine(
    clipboard: ClipboardOutcome,
    savedInHistory: Boolean,
): String = when {
    clipboard == ClipboardOutcome.COPIED ->
        "Copied too, if it did not arrive. Press and hold, then tap Paste."
    savedInHistory -> "Saved in History too, if it did not arrive."
    else -> "Check your text field before dictating again."
}
