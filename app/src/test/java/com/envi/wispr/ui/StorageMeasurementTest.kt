package com.envi.wispr.ui

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Product Outcome. When this fails the Storage page tells the user a wrong number about the space
 * EnviousWispr is taking, which is the one figure a user on a full phone decides with.
 *
 * The arithmetic is small and it is the only part of that page a JVM test can reach; the page itself was
 * verified on a running Android build (#20), where 6.1 MB, 2.6 MB and a 1.2 MB leftover summed to the
 * 9.9 MB the page displayed.
 */
class StorageMeasurementTest {

    @Test
    fun whatNoModelClaimsIsTheDifferenceBetweenTheFolderAndTheModels() {
        // The case this row exists for: a version bump leaves a file behind, or a download half
        // finishes, so the folder holds more than the models account for. Summing the cards would report
        // the tidy number and hide what the user is paying.
        val reading = StorageMeasurement(
            perModel = listOf("Parakeet" to 6_144_000L, "S1-mini" to 2_560_000L),
            folderTotal = 9_932_800L,
        )
        assertEquals(1_228_800L, reading.unaccounted)
    }

    @Test
    fun aTidyFolderClaimsNothingExtra() {
        val reading = StorageMeasurement(
            perModel = listOf("Parakeet" to 600L, "S1-mini" to 400L),
            folderTotal = 1_000L,
        )
        assertEquals("with nothing left behind there is no extra row to show", 0L, reading.unaccounted)
    }

    @Test
    fun aFolderMeasuredSmallerThanItsModelsNeverReportsNegativeSpace() {
        // The per-model walks and the folder walk are one measurement, but they are not one instant: a
        // remove landing between them can leave the folder smaller than the models were. A negative
        // number would be printed as "-1.2 MB no model claims", which is nonsense on a settings screen.
        val reading = StorageMeasurement(
            perModel = listOf("Parakeet" to 6_144_000L, "S1-mini" to 2_560_000L),
            folderTotal = 1_000L,
        )
        assertEquals(0L, reading.unaccounted)
    }

    @Test
    fun anEmptyPhoneAccountsForNothing() {
        assertEquals(0L, StorageMeasurement(perModel = emptyList(), folderTotal = 0L).unaccounted)
    }
}
