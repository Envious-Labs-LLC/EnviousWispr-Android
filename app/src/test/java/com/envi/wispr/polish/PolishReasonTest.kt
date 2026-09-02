package com.envi.wispr.polish

import com.envi.wispr.cleanup.PipelineOutcome
import com.envi.wispr.providers.Provider
import com.envi.wispr.providers.ProviderErrorSignal
import com.envi.wispr.providers.ProviderFailureKind
import com.envi.wispr.providers.SelfHostedProtocol
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Drift Guard: every provider failure kind and every pipeline outcome maps to exactly one reason.
 * A new `ProviderFailureKind` member fails to compile in `PolishReason.from`; this pins the values.
 */
class PolishReasonTest {

    private val cloud = PolishPolicy.Cloud(Provider.OPENAI, "gpt-test", null, SelfHostedProtocol.OPENAI_COMPATIBLE)

    @Test fun everyFailureKindMapsExactly() {
        val expected = mapOf(
            ProviderFailureKind.NO_API_KEY to PolishReason.NO_API_KEY,
            ProviderFailureKind.INVALID_CONFIGURATION to PolishReason.INVALID_CONFIGURATION,
            ProviderFailureKind.NETWORK to PolishReason.NETWORK,
            ProviderFailureKind.TIMEOUT to PolishReason.TIMEOUT,
            ProviderFailureKind.CANCELLED to PolishReason.CANCELLED,
            ProviderFailureKind.HTTP_ERROR to PolishReason.HTTP_ERROR,
            ProviderFailureKind.MALFORMED_RESPONSE to PolishReason.MALFORMED_RESPONSE,
            ProviderFailureKind.RESPONSE_TOO_LARGE to PolishReason.RESPONSE_TOO_LARGE,
            ProviderFailureKind.REDIRECT_REJECTED to PolishReason.REDIRECT_REJECTED,
        )
        assertEquals("every kind is pinned", ProviderFailureKind.values().toSet(), expected.keys)
        expected.forEach { (kind, reason) -> assertEquals(kind.name, reason, PolishReason.from(kind)) }
    }

    @Test fun aBodySignalRefinesOnlyAnHttpError() {
        assertEquals(PolishReason.HTTP_KEY_REJECTED, PolishReason.from(ProviderFailureKind.HTTP_ERROR, ProviderErrorSignal.KEY_REJECTED))
        assertEquals(PolishReason.HTTP_OUT_OF_CREDITS, PolishReason.from(ProviderFailureKind.HTTP_ERROR, ProviderErrorSignal.OUT_OF_CREDITS))
        assertEquals(PolishReason.HTTP_INPUT_TOO_LONG, PolishReason.from(ProviderFailureKind.HTTP_ERROR, ProviderErrorSignal.INPUT_TOO_LONG))
        assertEquals(PolishReason.HTTP_CONTENT_BLOCKED, PolishReason.from(ProviderFailureKind.HTTP_ERROR, ProviderErrorSignal.CONTENT_BLOCKED))
        assertEquals(PolishReason.NETWORK, PolishReason.from(ProviderFailureKind.NETWORK, ProviderErrorSignal.KEY_REJECTED))
        assertEquals("the persisted names of the #77 and #2 members, appended in order", listOf("HTTP_KEY_REJECTED", "HTTP_OUT_OF_CREDITS", "HTTP_INPUT_TOO_LONG", "HTTP_CONTENT_BLOCKED", "INVALID_CONFIGURATION", "TOO_SHORT"), PolishReason.entries.takeLast(6).map { it.name })
    }

    @Test fun offAndUnconfiguredPoliciesNameThemselvesWhateverThePipelineDid() {
        PipelineOutcome.values().forEach { outcome ->
            assertEquals(PolishReason.OFF, PolishReason.resolve(PolishPolicy.Off, outcome, null))
            assertEquals(PolishReason.CLOUD_NOT_CONFIGURED, PolishReason.resolve(PolishPolicy.CloudUnconfigured, outcome, null))
        }
    }

    @Test fun theTimeoutReasonsAreRecordedReasonsAndSurviveResolution() {
        assertEquals(PolishReason.LOCAL_TIMEOUT, PolishReason.resolve(PolishPolicy.LocalS1, PipelineOutcome.MODEL_DECLINED, PolishReason.LOCAL_TIMEOUT))
        assertTrue(PolishReason.entries.containsAll(listOf(PolishReason.LOCAL_TIMEOUT, PolishReason.WATCHDOG_TIMEOUT)))
    }

    @Test fun aRecordedAdapterReasonWinsOverThePipelineShape() {
        assertEquals(PolishReason.NO_API_KEY, PolishReason.resolve(cloud, PipelineOutcome.MODEL_DECLINED, PolishReason.NO_API_KEY))
        assertEquals(PolishReason.LOCAL_NOT_READY, PolishReason.resolve(PolishPolicy.LocalS1, PipelineOutcome.MODEL_DECLINED, PolishReason.LOCAL_NOT_READY))
        assertEquals(PolishReason.LOCAL_FAILED, PolishReason.resolve(PolishPolicy.LocalS1, PipelineOutcome.MODEL_DECLINED, PolishReason.LOCAL_FAILED))
    }

    @Test fun aDeclinedOrRejectedModelWithNothingRecordedIsAnOutputProblemNeverAnInferredCloudKind() {
        assertEquals(PolishReason.OUTPUT_REJECTED, PolishReason.resolve(cloud, PipelineOutcome.MODEL_DECLINED, null))
        assertEquals(PolishReason.OUTPUT_REJECTED, PolishReason.resolve(cloud, PipelineOutcome.MODEL_REJECTED, null))
        assertEquals(PolishReason.OUTPUT_REJECTED, PolishReason.resolve(PolishPolicy.LocalS1, PipelineOutcome.MODEL_REJECTED, null))
    }

    @Test fun pipelineShapesBeforeTheModelKeepTheirOwnReasons() {
        assertEquals(PolishReason.POLISHED, PolishReason.resolve(cloud, PipelineOutcome.MODEL_ACCEPTED, null))
        assertEquals(PolishReason.CLEANUP_RECOVERED, PolishReason.resolve(PolishPolicy.LocalS1, PipelineOutcome.CLEANUP_RECOVERED, null))
        assertEquals(PolishReason.EMPTY_AFTER_CLEANUP, PolishReason.resolve(cloud, PipelineOutcome.EMPTY_AFTER_CLEANUP, null))
        assertEquals(PolishReason.UNEXPECTED, PolishReason.resolve(cloud, PipelineOutcome.NO_MODEL, null))
    }
}
