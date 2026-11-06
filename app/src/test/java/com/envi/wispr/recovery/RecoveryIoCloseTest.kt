package com.envi.wispr.recovery

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.Closeable
import java.io.IOException

/** Product Outcome: an unknown IO close must not be reported as safely closed malformed metadata. */
class RecoveryIoCloseTest {
    @Test fun originalUseHidesCloseFailureBehindTheRealMalformedLedgerError() {
        val resource = Closing(fail = true)
        val failure = assertThrows(RecoveryStoreException::class.java) {
            resource.use { RecoveryLedgerCodec.decode(byteArrayOf()) }
        }
        assertEquals(RecoveryStoreFailure.MALFORMED, failure.reason)
        assertEquals("synthetic-close-canary", failure.suppressed.single().message)
        assertEquals(1, resource.calls)
    }

    @Test fun malformedLedgerAndFailedCloseReturnFreshIoFailureWithoutPrivateSuppressedData() {
        val resource = Closing(fail = true)
        val failure = assertThrows(RecoveryStoreException::class.java) {
            RecoveryIoClose.using(resource) { RecoveryLedgerCodec.decode(byteArrayOf()) }
        }
        closedIo(failure)
        assertEquals(1, resource.calls)
    }

    @Test fun failedCloseOverridesSuccessfulBodyAsAnUnknownIoOutcome() {
        val resource = Closing(fail = true)
        val failure = assertThrows(RecoveryStoreException::class.java) { RecoveryIoClose.using(resource) { 7 } }
        closedIo(failure)
        assertEquals(1, resource.calls)
    }

    @Test fun confirmedClosePreservesOnlyTheClosedReasonAndStripsExistingSuppressedData() {
        val original = RecoveryStoreException(RecoveryStoreFailure.UNSUPPORTED_FORMAT)
        original.addSuppressed(IOException("synthetic-body-canary"))
        val resource = Closing(fail = false)
        val failure = assertThrows(RecoveryStoreException::class.java) { RecoveryIoClose.using(resource) { throw original } }
        assertNotSame(original, failure)
        assertEquals(RecoveryStoreFailure.UNSUPPORTED_FORMAT, failure.reason)
        assertEquals(0, failure.suppressed.size)
        assertEquals(null, failure.cause)
        assertEquals(1, resource.calls)
    }

    @Test fun successfulBodyAndConfirmedCloseReturnTheActualValueOnce() {
        val resource = Closing(fail = false)
        assertEquals(7, RecoveryIoClose.using(resource) { 7 })
        assertEquals(1, resource.calls)
    }

    @Test fun aCloserErrorAlsoOverridesMalformedDataWithoutLeakingItsDetails() {
        val resource = AutoCloseable { throw AssertionError("synthetic-close-error-canary") }
        val failure = assertThrows(RecoveryStoreException::class.java) {
            RecoveryIoClose.using(resource) { RecoveryLedgerCodec.decode(byteArrayOf()) }
        }
        closedIo(failure)
    }

    private fun closedIo(failure: RecoveryStoreException) {
        assertEquals(RecoveryStoreFailure.IO_UNAVAILABLE, failure.reason)
        assertEquals("Protected recording unavailable", failure.message)
        assertEquals(null, failure.cause)
        assertEquals(0, failure.suppressed.size)
    }

    private class Closing(private val fail: Boolean) : Closeable {
        var calls = 0
        override fun close() {
            calls++
            if (fail) throw IOException("synthetic-close-canary")
        }
    }
}
