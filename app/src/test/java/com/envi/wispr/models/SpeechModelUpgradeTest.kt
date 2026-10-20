package com.envi.wispr.models

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Product outcome (#458): when this fails, an upgraded phone never gets Parakeet Ultra (and keeps the model that drops
 * the ends of takes), or it downloads Ultra after the user paused or cancelled speech delivery, or a Remove leaves the
 * retired 670 MB model behind.
 */
class SpeechModelUpgradeTest {
    @get:Rule val folder = TemporaryFolder()

    private val active = ModelDeliveryControlState.ACTIVE
    private val paused = ModelDeliveryControlState.PAUSED
    private val cancelled = ModelDeliveryControlState.CANCELLED

    /** MUTATION: drop any clause; each row below is the one that turns red. */
    @Test fun theUpgradeIsOwedOnlyForAnOldModelWithoutTheCurrentOneAndWithoutAStop() {
        assertTrue(SpeechModelUpgrade.owed(retiredFolderPresent = true, currentInstalled = false, currentControl = active))
        assertFalse("no old model: a new install goes through setup", SpeechModelUpgrade.owed(false, false, active))
        assertFalse("Ultra already installed", SpeechModelUpgrade.owed(true, true, active))
        assertFalse("the user paused speech delivery", SpeechModelUpgrade.owed(true, false, paused))
        assertFalse("the user cancelled speech delivery", SpeechModelUpgrade.owed(true, false, cancelled))
    }

    private fun controls() = ModelDeliveryControlStore(folder.root)
    private fun retiredRecord() = File(folder.root, ".model-controls/${SpeechModelUpgrade.RETIRED_ID}.state")
    private fun writeRetired(state: String) = retiredRecord().apply { parentFile!!.mkdirs(); writeText(state) }

    /** MUTATION: skip the inheritance (an earlier Pause is forgotten and Ultra downloads anyway). */
    @Test fun anEarlierPauseOrCancelCarriesOverAndTheOldRecordGoes() {
        for (state in listOf("PAUSED", "CANCELLED")) {
            controls().clear(ModelManifest.parakeet)
            writeRetired(state)
            controls().inheritRetired(SpeechModelUpgrade.RETIRED_ID, ModelManifest.parakeet)
            assertEquals(ModelDeliveryControlState.valueOf(state), controls().read(ModelManifest.parakeet))
            assertFalse(retiredRecord().exists())
        }
    }

    /** MUTATION: let the old record overwrite Ultra's (an explicit Resume on the Ultra card is undone). */
    @Test fun theCurrentRecordWinsAndAnInterruptedMigrationFinishes() {
        controls().write(ModelManifest.parakeet, active)
        writeRetired("PAUSED")
        controls().inheritRetired(SpeechModelUpgrade.RETIRED_ID, ModelManifest.parakeet)
        assertEquals(active, controls().read(ModelManifest.parakeet))
        assertFalse("the obsolete stop is consumed", retiredRecord().exists())
    }

    /** MUTATION: keep the old record after inheriting (a later Resume clears Ultra's, and the old Pause comes back). */
    @Test fun aLaterResumeStaysEffective() {
        writeRetired("CANCELLED")
        controls().inheritRetired(SpeechModelUpgrade.RETIRED_ID, ModelManifest.parakeet)
        controls().clear(ModelManifest.parakeet) // what Download, Resume and Update do
        controls().inheritRetired(SpeechModelUpgrade.RETIRED_ID, ModelManifest.parakeet)
        assertEquals(active, controls().read(ModelManifest.parakeet))
    }

    @Test fun anActiveOrUnreadableOldRecordIsDroppedWithoutEffect() {
        for (state in listOf("ACTIVE", "garbage")) {
            controls().clear(ModelManifest.parakeet)
            writeRetired(state)
            controls().inheritRetired(SpeechModelUpgrade.RETIRED_ID, ModelManifest.parakeet)
            assertFalse(File(folder.root, ".model-controls/${ModelManifest.parakeet.id}.state").exists())
            assertFalse(retiredRecord().exists())
        }
    }

    /** MUTATION: drop the sweep from the speech Remove, or run it for the polish model. */
    @Test fun removingTheSpeechModelAlsoFreesTheRetiredOne() {
        File(folder.root, "parakeet-sq").mkdirs()
        File(folder.root, "parakeet-sq/encoder-model.int8.onnx").writeBytes(ByteArray(700))
        assertEquals(0L, SpeechModelUpgrade.afterRemove(folder.root, ModelManifest.s1))
        assertTrue(File(folder.root, "parakeet-sq").exists())
        assertEquals(700L, SpeechModelUpgrade.afterRemove(folder.root, ModelManifest.parakeet))
        assertFalse(File(folder.root, "parakeet-sq").exists())
    }

    /** Harness contract on the source: the worker's Remove calls the rule, and the app start runs the check off main. */
    @Test fun theWorkerAndTheAppStartCallTheRule() {
        val worker = File("src/main/java/com/envi/wispr/models/ModelDeliveryWorker.kt").readText()
        assertTrue(worker.contains("SpeechModelUpgrade.afterRemove(root, model)"))
        val app = File("src/main/java/com/envi/wispr/models/ModelBootstrapApplication.kt").readText()
        assertTrue(app.contains("speechUpgradeScope.launch { SpeechModelUpgrade.run(app) }"))
    }
}
