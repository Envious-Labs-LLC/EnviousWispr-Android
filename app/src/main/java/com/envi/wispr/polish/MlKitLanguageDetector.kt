package com.envi.wispr.polish

import android.content.Context
import android.os.SystemClock
import com.envi.wispr.cleanup.DetectedLanguage
import com.envi.wispr.cleanup.LanguageDetector
import com.envi.wispr.debug.DebugLogger
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.common.MlKit
import com.google.mlkit.nl.languageid.LanguageIdentification
import com.google.mlkit.nl.languageid.LanguageIdentifier
import java.io.Closeable
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * The shipped [LanguageDetector]: ML Kit language identification, model bundled in the APK, no network
 * and no Play Services.
 *
 * A LIMB in the sense of `architecture-rules.md` RULE: isolate-limbs. It converts a model that will not
 * load, a wait that expires, the `und` sentinel and an empty candidate list into null, which the policy
 * reads as "nothing was established" and cleanup reads as "behave exactly as before #107".
 *
 * **It catches `Exception`, not `Throwable`, so a VM error still propagates.** An `OutOfMemoryError`
 * hidden behind an un-language-aware transcript is worse than a crash, so this is not a "never throws"
 * guarantee and describing it as one would retire a check a reader should still make.
 *
 * ## It runs INLINE, and that is a decision with a named cost
 *
 * Review rounds 5 to 8 produced FOUR generations of one defect here: **slow vendor work done while
 * holding shared state.** A lock held across initialization hung teardown; a cached thread pool put every
 * later take on the same lock; two-phase publication then closed a duplicate client while holding the
 * lock. Each fix was smaller than the last and each one recreated the class.
 *
 * So the machinery is gone rather than fixed a fifth time — no executor, no `Future`, no cancellation, no
 * lock. Identification runs on the caller's thread, and the only shared state is THREE atomics: the
 * closed flag, the client pointer, and the active-detection count. `close` never waits on another
 * application thread or on a shared-state lock; the final vendor `close()` is still a synchronous call
 * and is not bounded by anything here.
 *
 * **The accepted limit, stated rather than hidden, and MEASURED rather than assumed.** [DEADLINE_MS]
 * bounds the identification but NOT the one-time client acquisition, which is therefore unbounded in
 * principle. Measured on the S26 (SM-S948U1) 2026-09-03 across three cold `:polish` starts: **17 ms,
 * 19 ms and 22 ms**, from before `MlKit.initialize` to a usable client. A later client in an
 * already-initialised process took 0 ms. **That number is the whole justification for deleting the
 * machinery** — four generations of concurrency defect had been spent bounding a twenty-millisecond call.
 *
 * An earlier reading of 14 to 19 ms is superseded and must not be quoted: its timer started AFTER
 * `MlKit.initialize`, so it measured only `getClient()` while being cited as covering initialization
 * (review round 9). The conclusion was unchanged, which is luck rather than diligence — the number
 * justifying a design decision has to measure the thing the decision is about.
 *
 * Every cold acquisition logs `client ready in Nms`, so the assumption is checkable in the field rather
 * than trusted; if that line grows large, the limit stopped being acceptable. Every caller reaches this
 * off the main thread — the engine's worker and executor, `Dispatchers.IO`, and binder threads on two
 * early exits and the polish callbacks — so a slow acquisition would cost a pooled thread, never a frame.
 * **A caller on the main thread would not be acceptable and must not reach this**
 * (`kotlin-patterns.md` RULE: never-block-a-binder-or-ui-thread).
 */
class MlKitLanguageDetector(private val context: Context) : LanguageDetector, Closeable {

    private companion object {
        const val TAG = "LanguageDetector"

        /**
         * Cap on the identification itself. It does NOT cover the one-time client acquisition, which is
         * the limit named in the class doc rather than a bound this constant pretends to give.
         */
        const val DEADLINE_MS = 400L
    }

    /** Set once by [close]. Read without a lock; it only ever goes false to true. */
    private val closed = AtomicBoolean(false)

    /** The live client, or null before the first acquisition and after [close]. Pointer only. */
    private val client = AtomicReference<LanguageIdentifier?>(null)

    /**
     * Detections currently inside [detect]. Without it, [close] could release the client between a
     * detection reading the pointer and calling through it — a genuine use-after-close, and ML Kit does
     * not document what an in-flight call on a released client does (review round 9).
     *
     * Pointer-level state only, so it does not reintroduce the class that killed four earlier designs:
     * nothing slow is ever done while it is held, because it is a counter rather than a lock.
     */
    private val activeDetections = AtomicInteger(0)

    override fun detect(text: String): DetectedLanguage? {
        if (text.isBlank() || closed.get()) return null
        activeDetections.incrementAndGet()
        try {
            // Re-checked AFTER counting in. A close that began before the increment has already decided
            // whether to release, so this is the read that makes the decision consistent.
            if (closed.get()) return null
            return identify(text)
        } finally {
            // The last detection out of a closed detector performs the release the closer deferred.
            if (activeDetections.decrementAndGet() == 0 && closed.get()) releaseClient()
        }
    }

    private fun identify(text: String): DetectedLanguage? {
        val ready = client.get() ?: acquire() ?: return null
        val identified = try {
            // The default options return every candidate at or above 0.01 confidence, so the FLOOR is
            // applied by `CleanupLanguagePolicy` and not here. Setting a threshold on the client too
            // would be two answers to one question, and the policy's is the one under test.
            Tasks.await(ready.identifyPossibleLanguages(text), DEADLINE_MS, TimeUnit.MILLISECONDS)
        } catch (error: Exception) {
            // Shape only, never the text (`kotlin-patterns.md` RULE: no-content-in-diagnostics).
            DebugLogger.warn(TAG, "Language identification unavailable: ${error.javaClass.simpleName}")
            return null
        }
        val top = identified?.maxByOrNull { it.confidence } ?: return null
        // A language TAG and a score, never a sample of the text. This is the line the hardware run reads
        // to set `CleanupLanguagePolicy.MIN_CONFIDENCE` from measurement rather than from macOS's number.
        DebugLogger.log(TAG, "language=${top.languageTag} confidence=${top.confidence}")
        return DetectedLanguage(top.languageTag, top.confidence)
    }

    /**
     * Builds the client and publishes it, holding NO lock at any point.
     *
     * A caller that loses the publication, or that finishes after [close], releases the client it built
     * instead of leaking it — and releases it outside any shared state, which is the defect that killed
     * the previous three designs. Normal app takes are serialised, but concurrent first callers remain
     * representable: a duplicate request reaching a binder early exit while another thread is acquiring
     * produces two. The CAS publishes one and the loser releases its own, so the cost is a wasted
     * allocation rather than a leak.
     */
    private fun acquire(): LanguageIdentifier? {
        // BEFORE `MlKit.initialize`, which is the unbounded part. Round 9 caught the timer starting
        // after it, so the number being used to justify the design measured only `getClient()`.
        val started = SystemClock.elapsedRealtime()
        val created = try {
            // `MlKit.initialize` is NOT idempotent: verified 2026-09-03 by disassembling the pinned
            // `common-18.11.0.aar`, it reaches
            // `Preconditions.checkState(instance == null, "MlKitContext is already initialized")`. ML Kit
            // bootstraps from `MlKitInitProvider`, a ContentProvider with no `android:process`, so it runs
            // in the DEFAULT process only: `:polish` needs this call, and the main process needs the throw
            // ignored. Both broken states were observed on the phone before this shape.
            try {
                MlKit.initialize(context.applicationContext)
            } catch (_: IllegalStateException) {
                // Already initialized in this process. Normal wherever ML Kit's own provider ran.
            }
            LanguageIdentification.getClient().also {
                // Content-free, and the receipt for the unbounded-acquisition limit named in the class
                // doc. Covers initialization AND client construction. If this line grows large in the
                // field, the limit stopped being acceptable.
                DebugLogger.log(TAG, "client ready in ${SystemClock.elapsedRealtime() - started}ms")
            }
        } catch (error: Exception) {
            DebugLogger.warn(TAG, "Language client unavailable: ${error.javaClass.simpleName}")
            return null
        }

        if (!client.compareAndSet(null, created)) {
            // Another caller published first. Use theirs and release ours.
            release(created)
            return client.get()
        }
        if (closed.get()) {
            // `close` ran while this was building. Whoever observes it cleans up; `getAndSet` makes that
            // exactly one of us.
            releaseClient()
            return null
        }
        return created
    }

    /**
     * Releases the bundled model. Called from both services' `onDestroy`, which is what keeps
     * `architecture-rules.md` RULE: no-idle-cost true in the APP process: the engine process ends after a
     * dictation and takes the model with it, but the session owner lives in the long-running app process
     * and would otherwise hold a resident model between dictations.
     *
     * It cannot wait for anything: a flag and one atomic swap, then a vendor call that no other thread is
     * blocked behind.
     */
    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        // Only when nothing is using it. A detection in flight releases it on its way out instead.
        if (activeDetections.get() == 0) releaseClient()
    }

    /** Takes the published client, if any, and releases it. `getAndSet` makes that exactly one caller. */
    private fun releaseClient() = release(client.getAndSet(null))

    private fun release(identifier: LanguageIdentifier?) {
        if (identifier == null) return
        try {
            identifier.close()
        } catch (error: Exception) {
            DebugLogger.warn(TAG, "Detector close failed: ${error.javaClass.simpleName}")
        }
    }
}
