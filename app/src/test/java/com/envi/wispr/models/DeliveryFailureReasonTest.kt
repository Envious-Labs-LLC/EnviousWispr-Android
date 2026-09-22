package com.envi.wispr.models

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import java.io.IOException

/**
 * Drift guard and observability contract (issue #176): the closed delivery reasons and their wire
 * names are pinned; a typed exception keeps its reason at the catch; only the no-space errno reads as
 * the disk; the two manifest hosts map to their tokens and anything else is `unknown`.
 */
class DeliveryFailureReasonTest {

    @Test
    fun theWireVocabularyIsPinned() {
        assertEquals(
            listOf(
                "manifest_unavailable", "transport", "http_status", "redirect_refused", "partial_response",
                "integrity_mismatch", "disk_full", "staging_failed", "admission_failed", "cancelled", "paused",
            ),
            DeliveryFailureReason.entries.map { it.wire },
        )
    }

    @Test
    fun aTypedExceptionKeepsItsReasonAndABareOneIsTheNetworkUnlessItNamesTheDisk() {
        assertEquals(DeliveryFailureReason.HTTP_STATUS, DeliveryFailureReason.of(ModelDeliveryException(DeliveryFailureReason.HTTP_STATUS, "model source returned HTTP 503")))
        assertEquals(DeliveryFailureReason.ADMISSION_FAILED, DeliveryFailureReason.of(ModelDeliveryException(DeliveryFailureReason.ADMISSION_FAILED, "could not admit model atomically")))
        assertEquals(DeliveryFailureReason.TRANSPORT, DeliveryFailureReason.of(IOException("Connection reset")))
        assertEquals(DeliveryFailureReason.TRANSPORT, DeliveryFailureReason.of(IOException()))
        assertEquals(DeliveryFailureReason.DISK_FULL, DeliveryFailureReason.of(IOException("write failed: ENOSPC (No space left on device)")))
    }

    @Test
    fun theTwoManifestHostsMapToTheirTokensAndAnythingElseIsUnknown() {
        assertEquals(ModelSourceHost.MIRROR, ModelSourceHost.of("models.enviouslabs.co"))
        assertEquals(ModelSourceHost.HUGGING_FACE, ModelSourceHost.of("huggingface.co"))
        assertEquals(ModelSourceHost.HUGGING_FACE, ModelSourceHost.of("cdn-lfs.huggingface.co"))
        assertEquals(ModelSourceHost.UNKNOWN, ModelSourceHost.of("evil-huggingface.co"))
        assertEquals(ModelSourceHost.UNKNOWN, ModelSourceHost.of(null))
        assertEquals(listOf("mirror", "huggingface", "unknown"), ModelSourceHost.entries.map { it.wire })
    }

    /**
     * Drift Guard (#194): the delivery log names the host by this token, so an unrecognised host must
     * render as the literal `unknown`, never as itself. REVERT: change an unknown host's classification
     * or the `unknown` wire token; logging the raw host is `DiagnosticsShapeTest`'s row.
     */
    @Test
    fun anUnknownHostRendersAsTheUnknownTokenNeverItself() {
        val host = "cdn.example-with-a-user-token.invalid"
        assertEquals("unknown", ModelSourceHost.of(host).wire)
        assertFalse(ModelSourceHost.of(host).wire.contains("example"))
    }

    @Test
    fun byteBucketsArePinned() {
        assertEquals("0", ModelDeliveryWorker.bytesBucket(0L))
        assertEquals("lt_10mb", ModelDeliveryWorker.bytesBucket(1L))
        assertEquals("lt_100mb", ModelDeliveryWorker.bytesBucket(10L * 1024 * 1024))
        assertEquals("lt_500mb", ModelDeliveryWorker.bytesBucket(100L * 1024 * 1024))
        assertEquals("lt_1gb", ModelDeliveryWorker.bytesBucket(500L * 1024 * 1024))
        assertEquals("ge_1gb", ModelDeliveryWorker.bytesBucket(1024L * 1024 * 1024))
    }
}
