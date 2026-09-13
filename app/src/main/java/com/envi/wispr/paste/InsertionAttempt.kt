package com.envi.wispr.paste

import com.envi.wispr.insertion.InsertionText
import com.envi.wispr.paste.AccessibilityInsertionRules.EditorRead
import com.envi.wispr.paste.AccessibilityInsertionRules.EditorSelection
import com.envi.wispr.paste.AccessibilityInsertionRules.Judgement
import com.envi.wispr.paste.AccessibilityInsertionRules.SurroundingWindow
import com.envi.wispr.paste.AccessibilityInsertionRules.Verification
import com.envi.wispr.paste.AccessibilityInsertionRules.WINDOW_CHARS

/**
 * The pinned editor as the service can see it on one tick. `null` from [EditorWrites.locateTarget]
 * means it is not present right now; every field here is a read the service made for this tick.
 */
internal data class TargetState(
    val read: EditorRead,
    val selection: EditorSelection?,
    val sensitive: Boolean,
)

/** What the editor's paste call reported, classified by the port that made it. */
internal enum class PasteOutcome {
    /** `performAction` returned true. */
    ACCEPTED,

    /** `performAction` returned false: the framework's explicit rejection, nothing mutated. */
    REFUSED,

    /** The pinned node was not there to be asked; `performAction` was never called. */
    TARGET_GONE,

    /**
     * The editor's text or selection no longer matches the snapshot the payload was composed against;
     * `performAction` was never called. The attempt prepares again on its next tick.
     */
    CONTEXT_CHANGED,
}

/** What the input connection's commit call reported, classified by the port that made it. */
internal enum class CommitOutcome {
    /** `commitText` was called on the captured connection. Void by contract. */
    SENT,

    /**
     * The input session captured at the eligibility check is no longer live (input finished or
     * restarted since); `commitText` was never called. The attempt prepares again on its next tick.
     */
    SESSION_CHANGED,
}

/**
 * Everything [InsertionAttempt] may ask the accessibility service to do. The service implements it
 * with its `AccessibilityNodeInfo`, input connection and clipboard calls; a test implements it with a
 * fake. Any of these may throw; the attempt catches and classifies by WHEN the throw happened, never
 * by what it was.
 */
internal interface EditorWrites {
    fun locateTarget(): TargetState?

    /**
     * Whether the input session provably belongs to the pinned node right now. `true` also captures
     * that session inside the port, so [readSurrounding] and [commit] use the connection that was
     * checked, never whichever one is current later.
     */
    fun commitEligible(): Boolean

    /**
     * A read off the captured input connection: up to [beforeChars] characters before the caret and
     * [afterChars] after it. Null when no session was captured, the captured one is no longer live,
     * or the editor answered nothing.
     */
    fun readSurrounding(beforeChars: Int, afterChars: Int): SurroundingWindow?

    /**
     * Puts [payload] on the clipboard for the paste route, taking the restore snapshot immediately
     * before the first write. The FIRST staging of an attempt needs no read. Any later staging needs
     * CONFIRMED ownership of the clipboard: a read Android refuses is not a licence to write over a
     * clip the user may have copied since, and it is not a licence to paste an unknown clip either,
     * so `false` there ends the attempt with the words kept wherever they are.
     */
    fun stageClipboard(payload: String): Boolean

    /**
     * The editor's own paste on the pinned node, at the write boundary and in ONE call: refresh the
     * node, compare its text and selection with the snapshot the payload was composed against
     * ([expectedBaseline], [expectedSelection]), confirm `ACTION_PASTE` is advertised, then paste.
     * [PasteOutcome.TARGET_GONE] and [PasteOutcome.CONTEXT_CHANGED] mean `performAction` was never
     * called and nothing was mutated. A throw from `performAction` itself escapes, because that call
     * may have mutated the editor.
     */
    fun paste(expectedBaseline: String?, expectedSelection: EditorSelection?): PasteOutcome

    /**
     * `commitText` on the captured input connection, guarded by its liveness in the same call.
     * [CommitOutcome.SESSION_CHANGED] means `commitText` was never called and nothing was mutated.
     * A throw from `commitText` itself escapes, because that call may have reached the editor.
     */
    fun commit(payload: String): CommitOutcome

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

    enum class Evidence { NONE, SURROUNDING, NODE, UNREADABLE }

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
        val chosen = InsertionRoutePolicy.select(commitEligible())
        route = chosen
        return when (chosen) {
            InsertionRoute.COMMIT -> {
                // The pipe's own read composes the payload, so the seam is repaired against what
                // the editor really holds around the caret even when the node exposes no text. A
                // window the composer cannot use falls back to the node read, as the paste route.
                val window = readSurrounding()?.takeIf { it.composable }
                if (expired()) return Tick.Expired(false)
                val plan = if (window != null) {
                    payloadFor(
                        window.before + window.selected + window.after,
                        EditorSelection(window.before.length, window.before.length + window.selected.length),
                    )
                } else {
                    payloadFor(baseline, target.selection)
                }
                writeCommit(target, baseline, plan.text, window)
            }
            InsertionRoute.PASTE -> writePaste(target, baseline, payloadFor(baseline, target.selection))
        }
    }

    private fun writeCommit(
        located: TargetState,
        baseline: String?,
        payload: String,
        window: SurroundingWindow?,
    ): Tick {
        val record = Verification(
            action = AccessibilityInsertionRules.Action.COMMIT,
            beforeText = baseline,
            beforeWasHint = located.read.isShowingHintText,
            selection = located.selection,
            insertedText = payload,
            beforeWindow = window,
        )
        if (expired()) return Tick.Expired(false)
        verification = record
        val outcome = try {
            editor.commit(payload)
        } catch (error: Exception) {
            writeCount += 1
            returned = Returned.THREW
            noteOverrun()
            return judge(record)
        }
        noteOverrun()
        return when (outcome) {
            CommitOutcome.SESSION_CHANGED -> {
                // commitText was never called: the session moved between the check and the write,
                // so the next tick prepares again and will most likely take the paste route.
                verification = null
                Tick.Waiting
            }
            CommitOutcome.SENT -> {
                writeCount += 1
                returned = Returned.VOID
                judge(record)
            }
        }
    }

    private fun writePaste(
        located: TargetState,
        baseline: String?,
        plan: InsertionText.SmartPayloadPlan,
    ): Tick {
        // The first staging of the attempt writes without a read; a staging on a later tick (after
        // TARGET_GONE or CONTEXT_CHANGED) needs confirmed ownership inside the port, and refuses
        // otherwise, so ACTION_PASTE can never deliver a clip that is not ours.
        if (!stage(plan.text)) return Tick.StagingFailed
        if (expired()) return Tick.Expired(false)
        val record = Verification(
            action = AccessibilityInsertionRules.Action.PASTE,
            beforeText = baseline,
            beforeWasHint = located.read.isShowingHintText,
            selection = located.selection,
            insertedText = plan.text,
        )
        verification = record
        val outcome = try {
            // One call at the write boundary: refresh, compare with the snapshot the payload was
            // composed against, check the action is advertised, paste. A stale smart payload can
            // never be pasted at a caret it was not composed for.
            editor.paste(baseline, located.selection)
        } catch (error: Exception) {
            writeCount += 1
            returned = Returned.THREW
            noteOverrun()
            return judge(record)
        }
        noteOverrun()
        when (outcome) {
            PasteOutcome.TARGET_GONE, PasteOutcome.CONTEXT_CHANGED -> {
                // ACTION_PASTE was never invoked: nothing was mutated, so the next tick prepares again
                // from a fresh read. Its staging then needs confirmed clipboard ownership.
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
        // The commit route's first evidence is a second read off the same connection; the node judge
        // decides only when the windows cannot (`AccessibilityInsertionRules.judgeWindow`).
        val window = if (record.action == AccessibilityInsertionRules.Action.COMMIT) readSurrounding() else null
        val byWindow = window?.let { AccessibilityInsertionRules.judgeWindow(record, it) }
        if (byWindow != null) {
            lastJudgement = byWindow
            evidence = Evidence.SURROUNDING
            lastMissShape = when (byWindow) {
                Judgement.VERIFIED -> null
                else -> "judgement=${byWindow.name} evidence=SURROUNDING " +
                    "beforeLen=${window.before.length} afterLen=${window.after.length} " +
                    "insertedLen=${record.insertedText.length} documentStart=${window.atDocumentStart}"
            }
            return verdict(byWindow)
        }
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
        return verdict(judgement)
    }

    private fun verdict(judgement: Judgement): Tick = when {
        judgement == Judgement.VERIFIED -> Tick.Verified(checkNotNull(route))
        expired() -> Tick.Expired(written)
        else -> Tick.Waiting
    }

    /**
     * The same window on both sides of the write: enough before the caret to hold the payload plus
     * the [WINDOW_CHARS] the judge compares AND to show whether the draft already ended that way, and
     * [WINDOW_CHARS] after it.
     */
    private fun readSurrounding(): SurroundingWindow? = try {
        editor.readSurrounding(text.length + 2 * WINDOW_CHARS, WINDOW_CHARS)
    } catch (error: Exception) {
        null
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
