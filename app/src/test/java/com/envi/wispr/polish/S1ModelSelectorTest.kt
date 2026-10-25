package com.envi.wispr.polish

import com.envi.wispr.models.DownloadState
import com.envi.wispr.models.ModelDeliveryStore
import com.envi.wispr.models.ModelDescriptor
import com.envi.wispr.models.ModelFile
import com.envi.wispr.models.ModelTransport
import com.envi.wispr.models.TransportResponse
import com.envi.wispr.processing.ProcessingEnvironment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.nio.file.Files
import java.security.MessageDigest

/**
 * Product Outcome (#108). When this fails, local polish stops while its model updates, or evidence gathered on the
 * previous model is filed under the new one: the selector must load the revision actually installed, name it, and
 * give it its own identity.
 */
class S1ModelSelectorTest {
    private val oldBytes = "previous s1".toByteArray()
    private val newBytes = "current s1 model".toByteArray()

    private fun descriptor(bytes: ByteArray, revision: String) = ModelDescriptor(
        "s1-mini", "llama.cpp", "S1-mini", "Test", "Test", "", revision,
        listOf(ModelFile(S1Config.MODEL_FILENAME, bytes.size.toLong(), hash(bytes), "https://huggingface.co/test/s1/resolve/$revision/${S1Config.MODEL_FILENAME}")),
    )

    private val previous = descriptor(oldBytes, R1)
    private val current = descriptor(newBytes, R2).copy(previous = listOf(previous))

    private fun install(root: java.io.File, model: ModelDescriptor, bytes: ByteArray) =
        assertEquals(DownloadState.READY, ModelDeliveryStore(root).download(model, ModelTransport { _, _ -> TransportResponse(ByteArrayInputStream(bytes), false) }).state)

    /** The previous revision is selected, loaded and named while the current one is not installed. REVERT: current only. */
    @Test fun thePreviousRevisionIsSelectedAndNamed() {
        val root = Files.createTempDirectory("s1-previous").toFile()
        try {
            install(root, previous, oldBytes)
            assertEquals(previous, S1ModelSelector.installedDescriptor(root, current))
            val selection = checkNotNull(S1ModelSelector.resolve(root, current)) { "the previous revision did not resolve" }
            assertEquals(previous, selection.descriptor)
            assertEquals("previous s1", selection.file.readText())
        } finally { root.deleteRecursively() }
    }

    /**
     * The identity a qualification is filed under follows the revision: evidence on the previous model cannot match the
     * current identity the main process accepts. REVERT: build `s1ContextId` from the manifest only.
     */
    @Test fun previousEvidenceCarriesItsOwnIdentity() {
        assertFalse(ProcessingEnvironment.s1ContextId(previous) == ProcessingEnvironment.s1ContextId(current))
        assertEquals(ProcessingEnvironment.s1ContextId(current), ProcessingEnvironment.s1ContextId(current.copy(previous = emptyList())))
    }

    /**
     * Resident reuse: the cheap stamp is stable while the previous revision stays installed, and changes once the
     * current one replaces it, so a resident engine reloads exactly once at the swap.
     */
    @Test fun theStampIsStableUntilTheSwapThenChangesOnce() {
        val root = Files.createTempDirectory("s1-stamp").toFile()
        try {
            install(root, previous, oldBytes)
            val onPrevious = S1ModelSelector.installationStamp(root, current)
            assertNotNull(onPrevious)
            assertEquals("stable while nothing changes", onPrevious, S1ModelSelector.installationStamp(root, current))
            install(root, current, newBytes)
            val onCurrent = S1ModelSelector.installationStamp(root, current)
            assertTrue("changes at the swap", onCurrent != null && onCurrent != onPrevious)
            assertEquals(current, S1ModelSelector.installedDescriptor(root, current))
            assertEquals("stable again after", onCurrent, S1ModelSelector.installationStamp(root, current))
        } finally { root.deleteRecursively() }
    }

    /**
     * The service's own configuration helpers: a resident engine on the previous revision is reused while it stays
     * installed, reloads exactly once when the current revision replaces it, and is reused again after; a qualification
     * on the previous revision is filed under its identity.
     * REVERT: build `loadConfiguration`'s identity from the manifest only (previous-revision requests then never match).
     */
    @Test fun aResidentEngineIsReusedOnPreviousReloadsOnceAtTheSwapAndIsReusedAfter() {
        val root = Files.createTempDirectory("s1-resident").toFile()
        try {
            val policy = PolishPolicy.LocalS1(S1ControlSettings.DEFAULT)
            val resident = S1LoadedConfiguration()
            var loads = 0
            fun load() {
                val requested = S1ModelSelector.loadConfiguration(root, policy, current)
                if (resident.matches(requested)) return
                val selection = checkNotNull(S1ModelSelector.resolve(root, current))
                resident.ensure(S1ModelSelector.resolvedConfiguration(requested, selection)) { candidates -> loads++; candidates.first() }
            }
            install(root, previous, oldBytes)
            load(); load()
            assertEquals("one load, then reuse, on the previous revision", 1, loads)
            assertEquals(ProcessingEnvironment.s1ContextId(previous), S1ModelSelector.qualificationContextId(S1ModelSelector.resolve(root, current)))
            install(root, current, newBytes)
            load(); load()
            assertEquals("exactly one more load at the swap, then reuse", 2, loads)
            assertEquals(ProcessingEnvironment.s1ContextId(current), S1ModelSelector.qualificationContextId(S1ModelSelector.resolve(root, current)))
        } finally { root.deleteRecursively() }
    }

    private fun hash(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}

private const val R1 = "1111111111111111111111111111111111111111"
private const val R2 = "2222222222222222222222222222222222222222"
