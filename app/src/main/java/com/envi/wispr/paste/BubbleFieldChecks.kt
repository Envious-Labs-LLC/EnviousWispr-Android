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
 * - [finish], posted back through [main], applies the answer.
 *
 * One check runs at a time and at most one waits: a check submitted while one runs merges into the waiting
 * one ([BubbleFieldCheck.merge]), so a stream of window events cannot queue traversals behind an app that
 * is slow to answer. Each accessibility call is bounded by the framework's own interaction timeout, so a
 * running check always ends; nothing here waits for it. After [close] no answer is applied and nothing
 * further starts. Whether an answer is still about the CURRENT editor is the tracker's question, answered
 * when the answer is adopted ([EditorTargetTracker.fieldProbe]).
 *
 * Kept free of Android types so the ordering can be driven step by step, and on real threads, from the
 * JVM tests.
 */
internal class BubbleFieldChecks<A : Any>(
    private val background: (Runnable) -> Unit,
    private val main: (Runnable) -> Unit,
    private val begin: (BubbleFieldCheck) -> () -> A,
    private val finish: (A) -> Unit,
) {
    private var running = false
    private var waiting: BubbleFieldCheck? = null
    private var closed = false

    /** Main thread. Starts [check] now, or merges it into the one waiting behind the running check. */
    fun submit(check: BubbleFieldCheck) {
        if (closed || check.isEmpty) return
        if (running) {
            waiting = waiting?.merge(check) ?: check
            return
        }
        start(check)
    }

    /** Main thread. The retry sequence was cancelled: the waiting check keeps only its re-check. */
    fun forgetSequence() {
        waiting = waiting?.withoutSequence()?.takeUnless { it.isEmpty }
    }

    /** Main thread. No answer is applied after this, and nothing further starts. Safe to call twice. */
    fun close() {
        closed = true
        waiting = null
    }

    private fun start(check: BubbleFieldCheck) {
        // A throw in the snapshot must not take down the accessibility service, and auto-paste with it.
        val blocking = runCatching { begin(check) }.getOrNull() ?: return
        running = true
        val dispatched = runCatching {
            background(Runnable {
                // A throw off main is a check that answered nothing; the queue must not stay "running" forever.
                val answer = runCatching(blocking).getOrNull()
                main(Runnable { completed(answer) })
            })
        }
        if (dispatched.isFailure) running = false
    }

    private fun completed(answer: A?) {
        if (closed) {
            running = false
            return
        }
        // Still "running" while [finish] applies the answer, so a check it submits (a stale answer re-asks)
        // waits in the slot rather than starting a second one beside the next.
        try {
            answer?.let(finish)
        } finally {
            running = false
        }
        val next = waiting ?: return
        waiting = null
        start(next)
    }
}
