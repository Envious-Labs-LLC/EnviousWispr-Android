package com.envi.wispr.models

import java.io.ByteArrayInputStream
import java.io.File
import java.security.MessageDigest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Product outcome (#108): when this fails, a model downloads over mobile data the user never allowed, a "Download now"
 * leaks onto a later revision, the update prompt nags after an answer or shows on Wi-Fi, or a version bump never
 * updates the model (or restarts after the user paused it).
 */
class ModelDownloadPolicyTest {
    @get:Rule val folder = TemporaryFolder()

    private fun descriptor(bytes: ByteArray, revision: String) = ModelDescriptor(
        "demo", "test", "Demo", "Test", "Test", "", revision,
        listOf(ModelFile("model.bin", bytes.size.toLong(), hash(bytes), "https://huggingface.co/test/demo/resolve/$revision/model.bin")),
    )
    private val oldBytes = "previous model".toByteArray()
    private val previous = descriptor(oldBytes, "1".repeat(40))
    private val current = descriptor("current model!".toByteArray(), "2".repeat(40)).copy(previous = listOf(previous))

    /** MUTATION: ignore the setting, or the approval; or key approvals by model only (a later revision inherits it). */
    @Test fun mobileDataOnlyWithTheSettingOrThisRevisionsApproval() {
        assertFalse("Wi-Fi only by default", ModelDownloadPolicy.anyNetwork(false, emptySet(), current))
        assertTrue("the setting allows it", ModelDownloadPolicy.anyNetwork(true, emptySet(), current))
        assertTrue("this revision was approved", ModelDownloadPolicy.anyNetwork(false, setOf(ModelDownloadPolicy.revisionKey(current)), current))
        assertFalse("an earlier revision's approval does not carry", ModelDownloadPolicy.anyNetwork(false, setOf(ModelDownloadPolicy.revisionKey(previous)), current))
    }

    /** MUTATION: drop any clause; each row is the one that turns red. */
    @Test fun thePromptIsOwedOncePerRevisionOffWifiForAWifiOnlyUpdate() {
        val key = ModelDownloadPolicy.revisionKey(current)
        assertTrue(ModelDownloadPolicy.promptOwed(updatePending = true, onWifi = false, anyNetwork = false, answered = emptySet(), model = current))
        assertFalse("no update pending", ModelDownloadPolicy.promptOwed(false, false, false, emptySet(), current))
        assertFalse("on Wi-Fi it just downloads", ModelDownloadPolicy.promptOwed(true, true, false, emptySet(), current))
        assertFalse("mobile data already allowed", ModelDownloadPolicy.promptOwed(true, false, true, emptySet(), current))
        assertFalse("already answered for this revision", ModelDownloadPolicy.promptOwed(true, false, false, setOf(key), current))
        assertTrue("an earlier revision's answer does not silence this one", ModelDownloadPolicy.promptOwed(true, false, false, setOf(ModelDownloadPolicy.revisionKey(previous)), current))
    }

    /** MUTATION: drop any clause of `ModelUpdates.owed`. */
    @Test fun anUpdateIsOwedOnlyOverAnInstalledPreviousRevisionWithoutAStop() {
        assertTrue(ModelUpdates.owed(currentInstalled = false, previousInstalled = true, control = ModelDeliveryControlState.ACTIVE))
        assertFalse("already current", ModelUpdates.owed(true, true, ModelDeliveryControlState.ACTIVE))
        assertFalse("nothing installed: setup owns a first download", ModelUpdates.owed(false, false, ModelDeliveryControlState.ACTIVE))
        assertFalse("the user paused it", ModelUpdates.owed(false, true, ModelDeliveryControlState.PAUSED))
        assertFalse("the user cancelled it", ModelUpdates.owed(false, true, ModelDeliveryControlState.CANCELLED))
    }

    /** The on-disk reading: a previous revision installed by its receipt, the current one not. REVERT: read current only. */
    @Test fun theStoreReadingFindsAnInstalledPreviousRevision() {
        val root = folder.root
        assertFalse("an empty phone owes no update", ModelUpdates.owed(root, current))
        val installed = ModelDeliveryStore(root).download(previous, ModelTransport { _, _ -> TransportResponse(ByteArrayInputStream(oldBytes), false) })
        assertEquals(DownloadState.READY, installed.state)
        assertTrue(ModelUpdates.owed(root, current))
        ModelDeliveryControlStore(root).write(current, ModelDeliveryControlState.PAUSED)
        assertFalse("a paused model is left alone", ModelUpdates.owed(root, current))
    }

    /**
     * Drift guard: every request the worker builds takes its constraints from the policy, and the transfer opens through
     * the network WorkManager assigned. A path that picks its own network type, or a transfer on the default network,
     * reopens the mobile-data leak.
     */
    @Test fun everyDownloadRequestTakesItsNetworkFromThePolicy() {
        val worker = File("src/main/java/com/envi/wispr/models/ModelDeliveryWorker.kt").readText()
        assertFalse("no request picks its own network type", worker.contains("NetworkType"))
        assertEquals("one request builder, from the policy", 1, Regex("""ModelDownloadPolicy\.constraints\(""").findAll(worker).count())
        assertTrue("the transfer uses the worker's network", worker.contains("HttpsRangeTransport(transferNetwork)") && worker.contains("network?.openConnection(url)"))
        assertTrue("a Wi-Fi-only transfer without a network finds Wi-Fi or waits", worker.contains("ModelDownloadPolicy.wifiNetwork(applicationContext) ?: run {"))
        assertTrue("a re-queue updates the captured request by id", worker.contains("manager.updateWork(request(model, kind, ModelDownloadPolicy.anyNetwork(context, model), waiting.id))"))
        val upgrade = File("src/main/java/com/envi/wispr/models/SpeechModelUpgrade.kt").readText()
        assertFalse(upgrade.contains("mobileData"))
        val preferences = File("src/main/java/com/envi/wispr/settings/AppPreferences.kt").readText()
        assertTrue("setup's stored choice carries over under its key", preferences.contains("""MODEL_DOWNLOADS_ON_MOBILE_DATA = booleanPreferencesKey("onboarding_mobile_data")"""))
    }

    private fun hash(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
