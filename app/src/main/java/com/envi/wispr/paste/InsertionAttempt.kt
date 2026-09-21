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

    /**
     * Whether the input session captured for the commit is STILL the pipe's current live one. A commit
     * accepts (`SENT`) even when the connection is discarded server-side just after the local liveness
     * check, so a `SENT` return is not proof the words landed. Trusting an unconfirmed commit on expiry
     * requires the session to still be live here: a dead session means focus left the field around the
     * write and nothing can vouch for the delivery, so the clipboard fallback must stand.
     */
    fun commitSessionLive(): Boolean

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

    enum class Evidence { NONE, SURROUNDING, NODE, UNREADABLE, COMMIT_TRUSTED }

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

    // A read at any point returned a complete but wrong field (the editor changed or dropped the
    // payload). It latches, because a single MISS is real evidence of failure that a later unreadable
    // tick must not erase: a commit is trusted on expiry ONLY when every judgement was UNREADABLE.
    private var sawMiss: Boolean = false

    // The clock at the FIRST UNREADABLE judgement, or -1 before one. A structurally unreadable field
    // (Chrome's web inputs) never becomes readable, so waiting the full deadline to trust a landed
    // commit is a 2.5 s stall the user feels. Trust it after UNREADABLE_COMMIT_TRUST_MS of unbroken
    // unreadability instead: long enough to let a merely-slow editor reveal a readable text or a MISS,
    // short enough to feel instant.
    private var unreadableSinceMs: Long = -1L

    /** Why the last judgement said no, in SHAPES only (`kotlin-patterns.md` RULE: no-content-in-diagnostics). */
    var lastMissShape: String? = null
        private set

    /** A write was sent and not explicitly rejected, so the words may be in the field. */
    val written: Boolean
        get() = verification != null && returned != Returned.FALSE

    fun tick(): Tick {
        attempts += 1
        if (expired()) return expiredTick()
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
            if (byWindow == Judgement.MISS) {
                val rescued = rescueCommitMissWithNode(record)
                if (rescued != null) return rescued
            }
            lastJudgement = byWindow
            if (byWindow == Judgement.MISS) sawMiss = true
            evidence = Evidence.SURROUNDING
            lastMissShape = when (byWindow) {
                Judgement.VERIFIED -> null
                else -> "judgement=${byWindow.name} evidence=SURROUNDING " +
                    "beforeLen=${window.before.length} afterLen=${window.after.length} " +
                    "insertedLen=${record.insertedText.length} offset=${window.offset}"
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
        if (judgement == Judgement.MISS) sawMiss = true
        if (judgement == Judgement.UNREADABLE && unreadableSinceMs < 0) unreadableSinceMs = editor.now()
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

    /**
     * A COMMIT-route window MISS means the pipe's re-read did not show the payload. On some editors
     * (Chrome web inputs) that re-read is STALE: it answers with the pre-write context and offset=0
     * even after the write landed, so [AccessibilityInsertionRules.judgeWindow] sees a document-start
     * read too short to hold the payload and returns MISS. Before accepting that and dropping to the
     * clipboard, read the pinned node once: the node holds the field's own text and is what verifies
     * the PASTE route reliably on these same editors. ONLY a node judge of VERIFIED overrides the
     * window MISS; a missing, unreadable, partial, or genuinely empty field keeps the MISS and the
     * clipboard fallback, so a real dropped write is never trusted. The write is not re-issued: this
     * only re-reads. Returns a Verified tick on rescue, or null to keep the window verdict.
     */
    private fun rescueCommitMissWithNode(record: Verification): Tick? {
        val read = try {
            editor.readTarget()
        } catch (error: Exception) {
            null
        } ?: return null
        if (AccessibilityInsertionRules.judge(record, read) != Judgement.VERIFIED) return null
        lastJudgement = Judgement.VERIFIED
        evidence = Evidence.NODE
        lastMissShape = null
        return verdict(Judgement.VERIFIED)
    }

    private fun verdict(judgement: Judgement): Tick = when {
        judgement == Judgement.VERIFIED -> Tick.Verified(checkNotNull(route))
        // Trust a landed-but-unreadable commit EARLY, once the field has been unreadable long enough
        // that it is structural rather than slow: this is what removes the 2.5 s stall in Chrome, not
        // the expiry path below.
        commitLandedButUnreadable() && unreadableGraceElapsed() -> trustedCommit()
        expired() -> expiredTick()
        else -> Tick.Waiting
    }

    /** A landed-but-unreadable commit reported as delivered, tagged so the outcome line stays honest. */
    private fun trustedCommit(): Tick {
        evidence = Evidence.COMMIT_TRUSTED
        return Tick.Verified(InsertionRoute.COMMIT)
    }

    private fun unreadableGraceElapsed(): Boolean =
        unreadableSinceMs >= 0 && editor.now() - unreadableSinceMs >= UNREADABLE_COMMIT_TRUST_MS

    /**
     * The outcome when the deadline passes with no VERIFIED judgement. A commit sent cleanly through
     * the input connection into a field that stayed UNREADABLE the whole time is trusted as delivered
     * rather than dropped to the clipboard: `commitText` is how a keyboard types, the field took it,
     * and the write happened exactly once (`tick` re-judges without re-writing), so there is no
     * double-insert risk. Chrome's web inputs answer every read with null, and clipboard-with-a-warning
     * on every such take is worse than trusting a write that landed. The trust is narrow on purpose,
     * see [commitLandedButUnreadable]; every other expiry keeps the clipboard fallback. The early
     * path in [verdict] normally trusts first; this covers a deadline reached before the grace.
     */
    private fun expiredTick(): Tick =
        if (commitLandedButUnreadable()) trustedCommit() else Tick.Expired(written)

    /**
     * Trust a commit on expiry ONLY when it was the COMMIT route, the input connection accepted it
     * cleanly (`VOID`, never `THREW` or a refusal), a write was sent, and every judgement across the
     * attempt was UNREADABLE. A single MISS ([sawMiss]) is real evidence the editor changed or dropped
     * the payload and always keeps the clipboard fallback; the PASTE route and a throw are never
     * trusted.
     */
    private fun commitLandedButUnreadable(): Boolean =
        route == InsertionRoute.COMMIT &&
            returned == Returned.VOID &&
            written &&
            !sawMiss &&
            lastJudgement == Judgement.UNREADABLE &&
            editor.commitSessionLive()

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

    companion object {
        // How long a committed field must stay UNREADABLE before the commit is trusted as delivered.
        // Well under the insertion deadline: a slow-but-readable editor answers a real read inside this
        // window and verifies normally; a structurally unreadable one (Chrome) is trusted here instead
        // of stalling to the deadline. Two to three 125 ms retries.
        private const val UNREADABLE_COMMIT_TRUST_MS = 250L
    }
}
