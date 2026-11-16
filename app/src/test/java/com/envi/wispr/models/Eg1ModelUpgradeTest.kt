package com.envi.wispr.models

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Product Outcome (decision level) for #531's automatic EG-1 upgrade: when it fails, a tester holding the old EG-1
 * files is left with EG-1 "not downloaded" and has to download it by hand, or a user who paused or cancelled gets a
 * download they did not want.
 */
class Eg1ModelUpgradeTest {
    @get:Rule val temp = TemporaryFolder()

    @Test fun theUpgradeIsOwedOnlyForTheOldFilesWithDeliveryActive() {
        val active = ModelDeliveryControlState.ACTIVE
        assertTrue(Eg1ModelUpgrade.owed(retiredInstalled = true, currentInstalled = false, control = active))
        assertFalse("no old files: nothing to upgrade", Eg1ModelUpgrade.owed(false, false, active))
        assertFalse("the new files are already installed", Eg1ModelUpgrade.owed(true, true, active))
        assertFalse("the user paused EG-1's delivery", Eg1ModelUpgrade.owed(true, false, ModelDeliveryControlState.PAUSED))
        assertFalse("the user cancelled EG-1's delivery", Eg1ModelUpgrade.owed(true, false, ModelDeliveryControlState.CANCELLED))
    }

    @Test fun theOldRevisionIsRecognisedByItsReceiptAndNothingElse() {
        val root = temp.newFolder("models")
        assertFalse("no folder", Eg1ModelUpgrade.retiredInstalled(root))
        val folder = File(root, "eg1-micro").apply { mkdirs() }
        File(folder, ".verified-receipt").writeText("eg1-micro-1.0-g3d\neg1-micro-1.0-g3d-Q4_K_M-00001-of-00002.gguf=287622528:e53c")
        assertTrue("the old revision's receipt", Eg1ModelUpgrade.retiredInstalled(root))
        File(folder, ".verified-receipt").writeText("eg1-micro-1.0-g3d-mnn-hqq64\nconfig.json=254:0621")
        assertTrue("the first MNN export's receipt (internal testers, build 322)", Eg1ModelUpgrade.retiredInstalled(root))
        File(folder, ".verified-receipt").writeText("eg1-micro-1.0-g3d-mnn-b32\nconfig.json=254:0621")
        assertFalse("the current revision's receipt is not a retired one", Eg1ModelUpgrade.retiredInstalled(root))
        File(folder, ".verified-receipt").writeText("eg1-micro-1.0-g3d-mnn-hqq64-other\nconfig.json=254:0621")
        assertFalse("a revision that only starts like a retired one", Eg1ModelUpgrade.retiredInstalled(root))
        assertTrue("no retired revision is the shipped one", ModelManifest.eg1Micro.pinnedRevision !in Eg1ModelUpgrade.RETIRED_REVISIONS)
    }
}
