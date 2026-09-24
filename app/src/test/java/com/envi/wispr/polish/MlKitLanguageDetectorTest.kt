package com.envi.wispr.polish

import com.envi.wispr.cleanup.DetectedLanguage
import com.google.android.gms.common.Feature
import com.google.android.gms.tasks.Task
import com.google.mlkit.nl.languageid.IdentifiedLanguage
import com.google.mlkit.nl.languageid.LanguageIdentifier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

/**
 * `MlKitLanguageDetector` (#279): the published client is released only by `close` reading an active count of zero
 * or by the last detection out after `close`, so it is never closed while a counted-in detection holds it. Driven
 * with a fake client; every wait is a bounded latch, never a clock. The rows assert ownership and close counts,
 * never a language result: the fake throws a controlled exception, which the detector turns into null.
 */
class MlKitLanguageDetectorTest {

    /** A client that counts its closes and the callers inside it, and records whether it was closed while held. */
    private class FakeClient(private val gate: CountDownLatch? = null) : LanguageIdentifier {
        val closes = AtomicInteger()
        val calls = AtomicInteger()
        private val inside = AtomicInteger()
        val entered = CountDownLatch(1)
        @Volatile var closedWhileHeld = false

        override fun identifyPossibleLanguages(text: String): Task<List<IdentifiedLanguage>> {
            calls.incrementAndGet()
            inside.incrementAndGet()
            try {
                entered.countDown()
                gate?.let { check(it.await(10, TimeUnit.SECONDS)) }
                throw IllegalStateException("controlled")
            } finally {
                inside.decrementAndGet()
            }
        }

        override fun close() {
            if (inside.get() > 0) closedWhileHeld = true
            closes.incrementAndGet()
        }

        override fun identifyLanguage(text: String): Task<String> = throw UnsupportedOperationException()
        override fun getOptionalFeatures(): Array<Feature> = emptyArray()
    }

    /** A detection on its own thread whose answer, or failure, is asserted on the test thread. */
    private class Worker(detector: MlKitLanguageDetector, text: String) {
        private val result = AtomicReference<Result<DetectedLanguage?>>()
        private val thread = thread { result.set(runCatching { detector.detect(text) }) }

        /** Joins, then asserts the detection finished without throwing and answered null (the fake always throws). */
        fun answeredNull() {
            thread.join(10_000)
            assertFalse("the detection finished", thread.isAlive)
            assertNull(result.get().getOrThrow())
        }
    }

    /**
     * Row 1, the audit's interleaving: A publishes X and pauses; B counts in, reads X and is inside it; `close` defers;
     * A resumes and sees `closed`. X must not be closed while B holds it, and is closed once after B leaves.
     * MUTATION m1: the post-publication `closed` branch releases the client again.
     */
    @Test fun aPublisherThatSeesCloseNeverReleasesAClientAnotherDetectionHolds() {
        val holdB = CountDownLatch(1)
        val x = FakeClient(gate = holdB)
        val published = CountDownLatch(1)
        val resumeA = CountDownLatch(1)
        val detector = MlKitLanguageDetector(newClient = { x }, afterPublish = {
            published.countDown()
            check(resumeA.await(10, TimeUnit.SECONDS))
        })
        val a = Worker(detector, "first words")
        check(published.await(10, TimeUnit.SECONDS))
        val b = Worker(detector, "second words")
        check(x.entered.await(10, TimeUnit.SECONDS))

        detector.close()
        assertEquals("close deferred: two detections are counted in", 0, x.closes.get())
        resumeA.countDown()
        a.answeredNull()
        assertEquals("A saw close and released nothing", 0, x.closes.get())
        assertFalse("never closed while B was inside it", x.closedWhileHeld)

        holdB.countDown()
        b.answeredNull()
        assertEquals("the last detection out released it once", 1, x.closes.get())
        assertFalse(x.closedWhileHeld)
    }

    /** Row 2: `close` with nothing active releases the published client once; afterwards nothing is built. MUTATION m2. */
    @Test fun closeWithNothingActiveReleasesOnceAndLaterDetectionsBuildNothing() {
        val built = CopyOnWriteArrayList<FakeClient>()
        val detector = MlKitLanguageDetector(newClient = { FakeClient().also { built += it } })
        assertNull(detector.detect("some words"))
        assertEquals(1, built.size)
        detector.close()
        assertEquals(1, built.single().closes.get())
        detector.close()
        assertNull(detector.detect("more words"))
        assertEquals("nothing built after close", 1, built.size)
        assertEquals("released once", 1, built.single().closes.get())
    }

    /**
     * Row 3: two detections build at once; the CAS loser releases only its own client, which nobody used, and the
     * winner is released only by the detector's `close`. MUTATION m3: the loser releases the published client.
     */
    @Test fun theLoserReleasesOnlyItsOwnClient() {
        val barrier = CyclicBarrier(2)
        val built = CopyOnWriteArrayList<FakeClient>()
        val detector = MlKitLanguageDetector(newClient = {
            FakeClient().also { built += it; barrier.await(10, TimeUnit.SECONDS) }
        })
        listOf(Worker(detector, "one"), Worker(detector, "two")).forEach { it.answeredNull() }

        assertEquals(2, built.size)
        val winner = built.single { it.calls.get() > 0 }
        val loser = built.single { it.calls.get() == 0 }
        assertEquals("both detections used the winner", 2, winner.calls.get())
        assertEquals("the loser released its own", 1, loser.closes.get())
        assertEquals("the winner is still open", 0, winner.closes.get())
        detector.close()
        assertEquals(1, winner.closes.get())
        assertTrue(listOf(winner, loser).none { it.closedWhileHeld })
    }

    /**
     * Row 4 (#279 review): a builder that hands both racers the SAME instance; the loser must not close the client it
     * shares with the winner. MUTATION m4: the loser releases what it built unconditionally.
     */
    @Test fun aLoserHoldingThePublishedInstanceClosesNothing() {
        val barrier = CyclicBarrier(2)
        val shared = FakeClient()
        val detector = MlKitLanguageDetector(newClient = { shared.also { barrier.await(10, TimeUnit.SECONDS) } })
        listOf(Worker(detector, "one"), Worker(detector, "two")).forEach { it.answeredNull() }

        assertEquals("both detections used it", 2, shared.calls.get())
        assertEquals("nothing closed it before close", 0, shared.closes.get())
        detector.close()
        assertEquals(1, shared.closes.get())
        assertFalse(shared.closedWhileHeld)
    }
}
