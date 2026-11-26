package com.envi.wispr.paste

/**
 * Runs the bubble's field checks off the main thread, one at a time (#561). A check made on main froze
 * the phone: `findFocus` waits up to the framework's interaction timeout for a busy app to answer, and
 * Sentry ENVIOUSWISPR-ANDROID-V caught that wait as a fatal ANR from `discoverOnConnect`.
 *
 * Every public call and both main-thread steps run on the main thread; only the blocking half runs on
 * [background]:
 * - [begin], on main, snapshots what the check needs (a copy of the remembered editor, the retry sequence's
 *   state) and returns the blocking half;
 * - that half runs on [background] and makes the accessibility calls;
 * - [finish], posted back through [main], applies the answer; [failed] is told instead when the blocking
 *   half threw or could not be dispatched, so the caller's retry sequence never stalls on a lost answer.
 *
 * One check runs at a time and at most one waits: a check submitted while one runs merges into the waiting
 * one ([BubbleFieldCheck.merge]), so a stream of window events cannot queue traversals behind an app that
 * is slow to answer. Each accessibility call is bounded by the framework's own interaction timeout, so a
 * running check always ends; nothing here waits for it. [cancel] abandons the running check's answer and
 * the waiting check; after [close] nothing is applied and nothing further starts. Whether an answer is still
 * about the CURRENT editor is the tracker's question, answered when the answer is adopted
 * ([EditorTargetTracker.fieldProbe]).
 *
 * Kept free of Android types so the ordering can be driven step by step, and on real threads, from the
 * JVM tests.
 */
internal class BubbleFieldChecks<A : Any>(
    private val background: (Runnable) -> Unit,
    private val main: (Runnable) -> Unit,
    private val begin: (BubbleFieldCheck) -> () -> A,
    private val finish: (A) -> Unit,
    private val failed: (BubbleFieldCheck) -> Unit,
) {
    private var running = false
    private var waiting: BubbleFieldCheck? = null
    private var closed = false

    /** Which start the running check is; [cancel] moves it on, so an abandoned answer is recognised. */
    private var startToken = 0

    /** Main thread. Starts [check] now, or merges it into the one waiting behind the running check. */
    fun submit(check: BubbleFieldCheck) {
        if (closed || check.isEmpty) return
        if (running) {
            waiting = waiting?.merge(check) ?: check
            return
        }
        start(check)
    }

    /**
     * Main thread. Asks [check] again, as OLDER than whatever already waits: an answer that could not be used
     * is re-asked without its retry attempt displacing a newer one in the merge.
     */
    fun submitOlder(check: BubbleFieldCheck) {
        if (closed || check.isEmpty) return
        if (running) {
            waiting = waiting?.let { check.merge(it) } ?: check
            return
        }
        start(check)
    }

    /** Main thread. The retry sequence was cancelled: the waiting check keeps only its re-check. */
    fun forgetSequence() {
        waiting = waiting?.withoutSequence()?.takeUnless { it.isEmpty }
    }

    /**
     * Main thread. The service was interrupted, unbound, or reconnected: the running check's answer is
     * abandoned (neither [finish] nor [failed] hears of it) and the waiting check is dropped. [running] stays
     * true while a blocked probe still occupies the one worker, so repeated cancels and submits merge into
     * [waiting] instead of piling obsolete probes onto the executor; [completed] frees the slot and drains
     * [waiting] once the worker finishes.
     */
    fun cancel() {
        startToken++
        waiting = null
    }

    /** Main thread. No answer is applied after this, and nothing further starts. Safe to call twice. */
    fun close() {
        cancel()
        closed = true
    }

    private fun start(check: BubbleFieldCheck) {
        running = true
        val token = startToken
        // A throw in the snapshot must not take down the accessibility service, and auto-paste with it.
        // Report it through [completed] so an active retry sequence advances and any waiting check drains.
        val blocking = runCatching { begin(check) }.getOrNull()
        if (blocking == null) {
            completed(token, check, null)
            return
        }
        val dispatched = runCatching {
            background(Runnable {
                // A throw off main is a check that answered nothing; the queue must not stay "running" forever.
                val answer = runCatching(blocking).getOrNull()
                main(Runnable { completed(token, check, answer) })
            })
        }
        if (dispatched.isFailure) completed(token, check, null)
    }

    private fun completed(token: Int, check: BubbleFieldCheck, answer: A?) {
        if (closed) {
            running = false
            return
        }
        // Still "running" while the answer is applied, so a check submitted from [finish] or [failed]
        // waits in the slot rather than starting a second one beside the next.
        try {
            // Abandoned by [cancel]: neither [finish] nor [failed] hears of it, while `running` still
            // clears below so the latest waiting check starts once the worker is free.
            if (token == startToken) {
                if (answer != null) finish(answer) else failed(check)
            }
        } finally {
            running = false
        }
        if (closed) return
        val next = waiting ?: return
        waiting = null
        start(next)
    }
}
