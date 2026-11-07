package com.envi.wispr.telemetry

import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Observability contract: accurate once-per-install snapshots and bounded, closed Play connections. */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class InstallReferralReporterTest {
    private val received = InstallReferralRead.Received(InstallReferral.parse("utm_source=reddit"), 10L, 20L)

    @Test fun existingInstallBackfillsOnceAndAHandledInstallIsNotReadAgain() = runTest {
        var handled = false
        var reads = 0
        val calls = mutableListOf<String>()
        val reporter = InstallReferralReporter(
            { handled }, { calls += "handled"; handled = true }, { reads++; received },
            { calls += it.name + ":" + it.properties()["referral_source"] },
        )
        // No identity freshness flag: an installation from before this feature is equally eligible.
        reporter.report(InstallSource.PLAY)
        reporter.report(InstallSource.PLAY)
        assertEquals(1, reads)
        assertTrue(handled)
        assertEquals(listOf("install_referrer.received:reddit", "handled"), calls)
    }

    @Test fun transientFailureCanRetryNextStartButUnsupportedDoesNotRepeat() = runTest {
        var handled = false
        var reads = 0
        var captured = 0
        var outcome: InstallReferralRead = InstallReferralRead.RetryLater
        fun reporter() = InstallReferralReporter({ handled }, { handled = true }, { reads++; outcome }, { captured++ })
        reporter().report(InstallSource.PLAY)
        assertFalse(handled)
        outcome = InstallReferralRead.Unsupported
        reporter().report(InstallSource.PLAY)
        reporter().report(InstallSource.PLAY)
        assertTrue(handled)
        assertEquals(2, reads)
        assertEquals(0, captured)
    }

    @Test fun nonPlayCopiesNeverOpenAReferrerConnection() = runTest {
        val reporter = InstallReferralReporter({ error("not Play") }, { error("not Play") }, { error("not Play") }, { error("not Play") })
        for (source in InstallSource.entries.filter { it != InstallSource.PLAY }) reporter.report(source)
    }

    @Test fun successfulDuplicateSetupCallbacksReadOnceAndAlwaysClose() = runTest {
        val connection = Connection { setup, disconnected -> setup(0); setup(0); disconnected() }
        assertEquals(received, readInstallReferral(connection))
        assertEquals(1, connection.reads)
        assertEquals(1, connection.closed)
    }

    @Test fun unsupportedTransientAndDisconnectedConnectionsCloseWithoutReading() = runTest {
        for ((code, expected) in listOf(2 to InstallReferralRead.Unsupported, 1 to InstallReferralRead.RetryLater)) {
            val connection = Connection { setup, _ -> setup(code) }
            assertEquals(expected, readInstallReferral(connection))
            assertEquals(0, connection.reads)
            assertEquals(1, connection.closed)
        }
        val disconnected = Connection { _, lost -> lost() }
        assertEquals(InstallReferralRead.RetryLater, readInstallReferral(disconnected))
        assertEquals(1, disconnected.closed)
    }

    @Test fun readFailureLeavesTheLookupRetryableAndClosesTheClient() = runTest {
        val connection = Connection { setup, _ -> setup(0) }
        connection.failRead = true
        assertEquals(InstallReferralRead.RetryLater, readInstallReferral(connection))
        assertEquals(1, connection.closed)
    }

    @Test fun aSilentConnectionTimesOutAndCancellationClosesWithoutCapturingLateCallbacks() = runTest {
        val silent = Connection { _, _ -> }
        val lookup = async { readInstallReferral(silent) }
        runCurrent()
        advanceTimeBy(5_000)
        runCurrent()
        assertEquals(InstallReferralRead.RetryLater, lookup.await())
        assertEquals(1, silent.closed)
        var callback: ((Int) -> Unit)? = null
        val cancelled = Connection { setup, _ -> callback = setup }
        val job = launch { readInstallReferral(cancelled) }
        runCurrent()
        job.cancelAndJoin()
        assertEquals(1, cancelled.closed)
        callback!!(0)
        callback!!(0)
        assertEquals(0, cancelled.reads)
    }

    private inner class Connection(private val start: ((Int) -> Unit, () -> Unit) -> Unit) : InstallReferralConnection {
        var reads = 0
        var closed = 0
        var failRead = false
        override fun connect(setup: (Int) -> Unit, disconnected: () -> Unit) = start(setup, disconnected)
        override fun read(): InstallReferralRead.Received {
            reads++
            if (failRead) error("unavailable")
            return received
        }
        override fun close() { closed++ }
    }
}
