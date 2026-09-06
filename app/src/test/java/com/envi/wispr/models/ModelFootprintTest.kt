package com.envi.wispr.models

import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Product Outcome. When this fails a user deciding whether to keep EnviousWispr, or which model to
 * remove on a full phone, is shown a number that is not what the model is costing them.
 */
class ModelFootprintTest {

    @get:Rule
    val folder = TemporaryFolder()

    @Test
    fun aModelThatWasNeverDownloadedCostsNothing() {
        val missing = folder.newFolder().resolve("never-downloaded")
        assertEquals(0L, ModelFootprint.bytesUnder(missing))
    }

    @Test
    fun anEmptyDirectoryCostsNothing() {
        assertEquals(0L, ModelFootprint.bytesUnder(folder.newFolder()))
    }

    @Test
    fun theSizeIsEveryFileUnderneathAndNotJustTheTopLevel() {
        // The real layout nests: a model directory holds the encoder, the decoder and the tokens, and a
        // measurement that stopped at the top level would understate a model by most of its weight.
        val root = folder.newFolder()
        root.resolve("encoder.onnx").writeBytes(ByteArray(1_000))
        root.resolve("nested").mkdirs()
        root.resolve("nested/decoder.onnx").writeBytes(ByteArray(2_500))
        root.resolve("nested/deeper").mkdirs()
        root.resolve("nested/deeper/tokens.txt").writeBytes(ByteArray(500))
        assertEquals(4_000L, ModelFootprint.bytesUnder(root))
    }

    @Test
    fun aHalfFinishedDownloadIsCountedAtWhatItActuallyTakes() {
        // The manifest would say the full size. The user is paying for the part that landed, and this is
        // the case where the two numbers differ most and the disk one is the useful one.
        val root = folder.newFolder()
        root.resolve("encoder.onnx.part").writeBytes(ByteArray(1_234))
        assertEquals(1_234L, ModelFootprint.bytesUnder(root))
    }

    @Test
    fun aFilePassedDirectlyIsItsOwnSize() {
        val file = folder.newFile()
        file.writeBytes(ByteArray(777))
        assertEquals(777L, ModelFootprint.bytesUnder(file))
    }

    @Test
    fun aSymbolicLinkToAFileDoesNotCountItsBytesTwice() {
        // The first version used Kotlin's walkTopDown and a comment claiming it did not follow links.
        // It does. A link to a file would have counted the same bytes again, and the user would have
        // been told they could free more space than exists.
        val root = folder.newFolder()
        root.resolve("encoder.onnx").writeBytes(ByteArray(3_000))
        java.nio.file.Files.createSymbolicLink(
            root.resolve("alias.onnx").toPath(),
            root.resolve("encoder.onnx").toPath(),
        )
        assertEquals(3_000L, ModelFootprint.bytesUnder(root))
    }

    @Test
    fun aSymbolicLinkToADirectoryIsNotWalkedIntoAtAll() {
        // The worse shape: a link pointing at a parent turns a walk into a loop, and every pass adds
        // the whole tree again.
        val root = folder.newFolder()
        root.resolve("real").mkdirs()
        root.resolve("real/weights.bin").writeBytes(ByteArray(2_000))
        java.nio.file.Files.createSymbolicLink(
            root.resolve("loop").toPath(),
            root.toPath(),
        )
        assertEquals(2_000L, ModelFootprint.bytesUnder(root))
    }

    @Test
    fun theExpectedSizeIsTheSumOfEveryFileTheManifestNames() {
        listOf(ModelManifest.parakeet, ModelManifest.s1).forEach { model ->
            assertEquals(
                "the expected size must be the manifest's own sum for ${model.id}",
                model.files.sumOf { it.expectedBytes },
                ModelFootprint.expectedBytes(model),
            )
        }
    }

    @Test
    fun theShippedModelsHaveASizeWorthShowing() {
        // The whole reason this exists: EnviousWispr holds well over a gigabyte and no screen said so.
        // If a manifest ever reports a model as weightless, the card would silently stop saying anything.
        listOf(ModelManifest.parakeet, ModelManifest.s1).forEach { model ->
            val bytes = ModelFootprint.expectedBytes(model)
            org.junit.Assert.assertTrue(
                "${model.id} reports $bytes bytes, which no card can usefully show",
                bytes > 1_000_000L,
            )
        }
    }
}
