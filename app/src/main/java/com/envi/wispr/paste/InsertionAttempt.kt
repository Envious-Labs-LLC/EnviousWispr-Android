package com.envi.wispr.paste

import com.envi.wispr.insertion.InsertionText
import com.envi.wispr.paste.AccessibilityInsertionRules.EditorRead
import com.envi.wispr.paste.AccessibilityInsertionRules.EditorSelection
import com.envi.wispr.paste.AccessibilityInsertionRules.Judgement
import com.envi.wispr.paste.AccessibilityInsertionRules.Verification

/**
 * The pinned editor as the service can see it on one tick. `null` from [EditorWrites.locateTarget]
 * means it is not present right now; every field here is a read the service made for this tick.
 */
internal data class TargetState(
    val read: EditorRead,
    val selection: EditorSelection?,
    val sensitive: Boolean,
    val canPaste: Boolean,
)

/** What the editor's paste call reported, classified by the port that made it. */
internal enum class PasteOutcome {
    /** `performAction` returned true. */
    ACCEPTED,

    /** `performAction` returned false: the framework's explicit rejection, nothing mutated. */
    REFUSED,

    /** The pinned node was not there to be asked; `performAction` was never called. */
    TARGET_GONE,
}

/**
 * Everything [InsertionAttempt] may ask the accessibility service to do. The service implements it
 * with its `AccessibilityNodeInfo` and clipboard calls; a test implements it with a fake. Any of these
 * may throw; the attempt catches and classifies by WHEN the throw happened, never by what it was.
 */
internal interface EditorWrites {
    fun locateTarget(): TargetState?

    /** Whether the input session provably belongs to the pinned node right now. */
    fun commitEligible(): Boolean

    /**
     * Puts [payload] on the clipboard for the paste route, taking the restore snapshot immediately
     * before the first write. `false` means nothing is staged: the clipboard is no longer ours, or
     * the write failed.
     */
    fun stageClipboard(payload: String): Boolean

    /**
     * The editor's own paste on the pinned node. [PasteOutcome.TARGET_GONE] means the node could not be
     * found or refreshed and `ACTION_PASTE` was never called: nothing was mutated, so the attempt may
     * retry preparation. A throw from `performAction` itself escapes, because that call may have
     * mutated the editor.
     */
    fun paste(): PasteOutcome

    /** `commitText` on the captured input connection. Void by contract. */
    fun commit(payload: String)

    /** A fresh raw read of the pinned node; `null` when it cannot be refreshed. */
    fun readTarget(): EditorRead?

    fun now(): Long
}

/**
 * One dictation's insertion: one route, one content write, then judging until the deadline (#141).
 *
 * Ticked by the service every retry interval. The attempted-write record ([verification]) is installed
 * BEFORE the write call, so a throw from the write can never leave this looking as if nothing was sent,
 * and once it exists no tick will write again whatever the call returned. A plain `false` from the
 * editor's paste is the framework's explicit rejection: it is recorded, the attempt ends in
 * [Tick.Rejected], and the words are already on the clipboard.
 *
 * The deadline is checked at tick entry, after every blocking read, and immediately before the write;
 * a write that was already in flight when the deadline passed is judged normally and reported with
 * [overrun].
 */
internal class InsertionAttempt(
    private val editor: EditorWrites,
    private val text: String,
    private val smartInsertion: Boolean,
    private val deadlineMs: Long,
) {
    enum class Returned { NONE, TRUE, FALSE, VOID, THREW }

    enum class Evidence { NONE, NODE, UNREADABLE }

    sealed interface Tick {
        /** Nothing terminal happened; tick again after the retry interval. */
        data object Waiting : Tick

        data class Verified(val route: InsertionRoute) : Tick

        data object Sensitive : Tick

        /** The editor refused the paste (`false`). Nothing was mutated; the clipboard holds the words. */
        data object Rejected : Tick

        /** The clipboard could not be staged; nothing was written. */
        data object StagingFailed : Tick

        /** The deadline passed. [written] says whether a write was sent and is still unconfirmed. */
        data class Expired(val written: Boolean) : Tick
    }

    var attempts: Int = 0
        private set
    var route: InsertionRoute? = null
        private set
    var verification: Verification? = null
        private set
    var returned: Returned = Returned.NONE
        private set
    var evidence: Evidence = Evidence.NONE
        private set
    var lastJudgement: Judgement? = null
        private set
    var overrun: Boolean = false
        private set
    var writeCount: Int = 0
        private set

    /** Why the last judgement said no, in SHAPES only (`kotlin-patterns.md` RULE: no-content-in-diagnostics). */
    var lastMissShape: String? = null
        private set

    /** A write was sent and not explicitly rejected, so the words may be in the field. */
    val written: Boolean
        get() = verification != null && returned != Returned.FALSE

    fun tick(): Tick {
        attempts += 1
        if (expired()) return Tick.Expired(written)
        verification?.let { return judge(it) }
        return prepareAndWrite()
    }

    private fun prepareAndWrite(): Tick {
        val target = locate() ?: return Tick.Waiting
        if (target.sensitive) return Tick.Sensitive
        if (expired()) return Tick.Expired(false)

        val baseline = AccessibilityInsertionRules.baseline(target.read)
        val plan = payloadFor(baseline, target.selection)
        val chosen = InsertionRoutePolicy.select(commitEligible())
        route = chosen
        return when (chosen) {
            InsertionRoute.COMMIT -> writeCommit(target, plan.text)
            InsertionRoute.PASTE -> writePaste(target, baseline, plan)
        }
    }

    private fun writeCommit(target: TargetState, payload: String): Tick {
        val record = Verification(
            action = AccessibilityInsertionRules.Action.COMMIT,
            beforeText = AccessibilityInsertionRules.baseline(target.read),
            beforeWasHint = target.read.isShowingHintText,
            selection = target.selection,
            insertedText = payload,
        )
        if (expired()) return Tick.Expired(false)
        verification = record
        writeCount += 1
        returned = try {
            editor.commit(payload)
            Returned.VOID
        } catch (error: Exception) {
            Returned.THREW
        }
        noteOverrun()
        return judge(record)
    }

    private fun writePaste(
        located: TargetState,
        composedAgainst: String?,
        plan: InsertionText.SmartPayloadPlan,
    ): Tick {
        if (!stage(plan.text)) return Tick.StagingFailed
        // Re-read immediately before the paste. Two reasons. A standard EditText advertises
        // ACTION_PASTE only while the clipboard holds something, so a clipboard that was empty until
        // the staging a moment ago reads as "cannot paste" on the FIRST read and "can paste" now. And
        // if the user moved the caret while the smart payload was being prepared, fall back to their
        // literal words, staged again, and snapshot once more so the record describes the field the
        // paste actually lands in.
        var before = locate() ?: return Tick.Waiting
        if (!before.canPaste) return Tick.Rejected
        var payload = plan.text
        if (plan.changesDictatedText &&
            (AccessibilityInsertionRules.baseline(before.read) != composedAgainst ||
                before.selection != located.selection)
        ) {
            payload = text
            if (!stage(payload)) return Tick.StagingFailed
            before = locate() ?: return Tick.Waiting
        }
        if (expired()) return Tick.Expired(false)
        val record = Verification(
            action = AccessibilityInsertionRules.Action.PASTE,
            beforeText = AccessibilityInsertionRules.baseline(before.read),
            beforeWasHint = before.read.isShowingHintText,
            selection = before.selection,
            insertedText = payload,
        )
        verification = record
        val outcome = try {
            editor.paste()
        } catch (error: Exception) {
            writeCount += 1
            returned = Returned.THREW
            noteOverrun()
            return judge(record)
        }
        noteOverrun()
        when (outcome) {
            PasteOutcome.TARGET_GONE -> {
                // The node vanished between the read a moment ago and the call; ACTION_PASTE was never
                // invoked, so nothing was mutated and preparation may run again next tick.
                verification = null
                return Tick.Waiting
            }
            PasteOutcome.REFUSED -> {
                writeCount += 1
                returned = Returned.FALSE
                return Tick.Rejected
            }
            PasteOutcome.ACCEPTED -> {
                writeCount += 1
                returned = Returned.TRUE
                return judge(record)
            }
        }
    }

    private fun judge(record: Verification): Tick {
        val read = try {
            editor.readTarget()
        } catch (error: Exception) {
            null
        }
        val judgement = if (read == null) Judgement.UNREADABLE else AccessibilityInsertionRules.judge(record, read)
        lastJudgement = judgement
        evidence = if (judgement == Judgement.UNREADABLE) Evidence.UNREADABLE else Evidence.NODE
        lastMissShape = when (judgement) {
            Judgement.VERIFIED -> null
            Judgement.MISS -> "judgement=MISS actualLen=${read?.text?.length ?: -1} " +
                "beforeLen=${record.beforeText?.length ?: -1} insertedLen=${record.insertedText.length} " +
                "selection=${record.selection?.let { "${it.start}/${it.end}" } ?: "none"}"
            Judgement.UNREADABLE -> "judgement=UNREADABLE readNull=${read == null} " +
                "hint=${read?.isShowingHintText ?: false} baselineKnown=${record.beforeText != null}"
        }
        return when {
            judgement == Judgement.VERIFIED -> Tick.Verified(checkNotNull(route))
            expired() -> Tick.Expired(written)
            else -> Tick.Waiting
        }
    }

    private fun payloadFor(baseline: String?, selection: EditorSelection?): InsertionText.SmartPayloadPlan {
        if (!smartInsertion || baseline == null || selection == null) {
            return InsertionText.SmartPayloadPlan(text, false)
        }
        return InsertionText.smartPayloadPlan(baseline, text, selection.start, selection.end)
    }

    private fun locate(): TargetState? = try {
        editor.locateTarget()
    } catch (error: Exception) {
        null
    }

    private fun commitEligible(): Boolean = try {
        editor.commitEligible()
    } catch (error: Exception) {
        false
    }

    private fun stage(payload: String): Boolean = try {
        editor.stageClipboard(payload)
    } catch (error: Exception) {
        false
    }

    private fun expired(): Boolean = editor.now() >= deadlineMs

    private fun noteOverrun() {
        if (editor.now() > deadlineMs) overrun = true
    }
}
