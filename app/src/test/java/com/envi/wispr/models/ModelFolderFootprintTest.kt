package com.envi.wispr.models

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Product Outcome. When this fails the Storage page tells the user a wrong number about the space
 * EnviousWispr is taking, which is the one figure a user trying to free space decides with.
 *
 * These walk a REAL directory tree rather than asserting arithmetic on a data class. The defect review
 * found in the first version of that page was not arithmetic: it was measuring each model and then the
 * folder in separate passes, so the parts and the whole came from different instants and could disagree.
 * Only a test that walks a real tree can say the buckets and the total came from one visit per file.
 */
class ModelFolderFootprintTest {

    @get:Rule val temporaryFolder = TemporaryFolder()

    private val parakeet = ModelManifest.parakeet
    private val s1 = ModelManifest.s1
    private val models = listOf(parakeet, s1)

    private fun write(relativePath: String, bytes: Int) {
        val file = File(temporaryFolder.root, relativePath)
        file.parentFile?.mkdirs()
        file.writeBytes(ByteArray(bytes))
    }

    @Test
    fun everyFileIsCountedOnceAndThePartsAddUpToTheWhole() {
        write("${parakeet.id}/encoder.onnx", 600)
        write("${parakeet.id}/tokens.txt", 40)
        write("${s1.id}/model.gguf", 250)
        write("a-file-a-version-bump-left/old.onnx", 110)

        val footprint = ModelFootprint.measureFolder(temporaryFolder.root, models)

        assertEquals("both of this model's files", 640L, footprint.perModel[parakeet])
        assertEquals(250L, footprint.perModel[s1])
        assertEquals("what no model's own directory holds", 110L, footprint.unclaimed)
        assertEquals(1_000L, footprint.total)
        // The property that makes the page's rows trustworthy, stated once rather than implied by the
        // four numbers above: nothing is counted twice and nothing is missed.
        assertEquals(footprint.total, footprint.perModel.values.sum() + footprint.unclaimed)
    }

    @Test
    fun aNestedFileStillBelongsToItsModel() {
        // A model directory is not flat forever. A file one level down must not fall out of its model
        // and reappear as space "no model claims", which is the wrong story to tell a user.
        write("${parakeet.id}/nested/deeper/weights.bin", 500)
        val footprint = ModelFootprint.measureFolder(temporaryFolder.root, models)
        assertEquals(500L, footprint.perModel[parakeet])
        assertEquals(0L, footprint.unclaimed)
    }

    @Test
    fun aFolderThatWasNeverCreatedIsZeroForEveryModel() {
        // The ordinary state on a phone where nothing has downloaded yet. It must not throw, and it must
        // not report a model as absent from the map, because the page reads every model out of it.
        val missing = File(temporaryFolder.root, "not-created-yet")
        val footprint = ModelFootprint.measureFolder(missing, models)
        assertEquals(0L, footprint.total)
        assertEquals(0L, footprint.unclaimed)
        models.forEach { model ->
            assertEquals("${model.id} must be present and zero", 0L, footprint.perModel[model])
        }
    }

    @Test
    fun anEmptyFolderIsZeroRatherThanAnError() {
        val footprint = ModelFootprint.measureFolder(temporaryFolder.root, models)
        assertEquals(0L, footprint.total)
        assertEquals(0L, footprint.unclaimed)
    }

    @Test
    fun unclaimedIsNeverNegativeBecauseBothNumbersCameFromOneWalk() {
        // The clamp the first version needed is GONE, and this is why it is safe to have removed it:
        // every byte in `total` was added in the same visit that added it to a bucket, so the sum of the
        // buckets can never exceed the total. There is no interleaving left to defend against.
        write("${parakeet.id}/a.bin", 300)
        write("${s1.id}/b.bin", 300)
        write("stray/c.bin", 300)
        val footprint = ModelFootprint.measureFolder(temporaryFolder.root, models)
        assertTrue("unclaimed must not be negative: ${footprint.unclaimed}", footprint.unclaimed >= 0L)
        assertEquals(300L, footprint.unclaimed)
    }

    @Test
    fun aDirectoryNamedLikeAModelButOutsideTheRootIsNotCounted() {
        // Attribution is by PATH under the root, not by name, so a sibling directory cannot be credited
        // to a model and cannot inflate the total.
        val sibling = File(temporaryFolder.root.parentFile, "elsewhere-${parakeet.id}")
        sibling.mkdirs()
        File(sibling, "decoy.bin").writeBytes(ByteArray(900))
        try {
            val footprint = ModelFootprint.measureFolder(temporaryFolder.root, models)
            assertEquals(0L, footprint.total)
        } finally {
            sibling.deleteRecursively()
        }
    }
}
