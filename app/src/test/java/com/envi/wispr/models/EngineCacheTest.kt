package com.envi.wispr.models

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files

/**
 * Drift Guard for #531's engine cache lifecycle. When it fails, a phone keeps a stale 600 MB cache from another
 * revision (wasted storage, or worse, MNN reads weights that belong to other model files) or removing EG-1 Micro
 * leaves the cache behind.
 */
class EngineCacheTest {
    @get:Rule val temp = TemporaryFolder()

    private val model = ModelManifest.eg1Micro

    private fun cacheWith(root: File, build: String, revision: String = model.pinnedRevision): File =
        File(EngineCache.modelFolder(root, model), "$revision-$build").apply {
            mkdirs()
            File(this, "0_0_0_0_0.static").writeBytes(ByteArray(1_000))
            File(this, "0_0_0_0_sync.static").writeBytes(ByteArray(0))
        }

    @Test fun sweepingKeepsTheCurrentCacheAndRemovesOtherRevisionsAndBuilds() {
        val root = temp.newFolder("model-cache")
        val current = cacheWith(root, "b6a3216")
        val otherBuild = cacheWith(root, "0000000")
        val otherRevision = cacheWith(root, "b6a3216", revision = "eg1-micro-0.9")
        assertTrue(EngineCache.sweepExcept(root, model, keep = current))
        assertTrue("the current cache stays", File(current, "0_0_0_0_0.static").isFile)
        assertFalse("another engine build's cache is removed", otherBuild.exists())
        assertFalse("another revision's cache is removed", otherRevision.exists())
        assertEquals(1_000L, EngineCache.bytes(root, model))
    }

    @Test fun removingTheModelRemovesEveryCacheFolder() {
        val root = temp.newFolder("model-cache")
        cacheWith(root, "b6a3216")
        cacheWith(root, "0000000")
        assertTrue(EngineCache.removeAll(root, model))
        assertFalse(EngineCache.modelFolder(root, model).exists())
        assertEquals(0L, EngineCache.bytes(root, model))
        assertTrue("removing an absent cache is fine", EngineCache.removeAll(root, model))
    }

    @Test fun thePrefixSubfolderIsCountedAndRemovedButNothingDeeper() {
        val root = temp.newFolder("model-cache")
        val stale = cacheWith(root, "0000000")
        val prefix = File(stale, EngineCache.PREFIX_FOLDER).apply { mkdirs() }
        File(prefix, "p0123456789abcdef_0.k").writeBytes(ByteArray(500))
        assertEquals(1_500L, EngineCache.bytes(root, model))
        assertTrue(EngineCache.removeAll(root, model))
        assertFalse(stale.exists())

        val again = cacheWith(root, "0000000")
        File(File(again, EngineCache.PREFIX_FOLDER), "deeper").mkdirs()
        assertFalse("a folder inside the prefix folder is reported", EngineCache.removeAll(root, model))
        assertTrue(again.isDirectory)
    }

    @Test fun leftoverScratchFilesAreSweptAndNothingElse() {
        val root = temp.newFolder("model-cache")
        val cache = cacheWith(root, "b6a3216")
        File(cache, "0000000102EB50C0.k").writeBytes(ByteArray(10))
        File(cache, "0000000102EB50C0.v").writeBytes(ByteArray(10))
        assertEquals(2, EngineCache.sweepScratch(cache))
        assertTrue("the weight cache stays", File(cache, "0_0_0_0_0.static").isFile)
        assertTrue("its completion marker stays", File(cache, "0_0_0_0_sync.static").isFile)
    }

    @Test fun aLinkOrSubfolderInsideACacheIsNeverFollowedAndIsReported() {
        val root = temp.newFolder("model-cache")
        val outside = temp.newFolder("user-data").apply { File(this, "keep.txt").writeText("mine") }
        val stale = cacheWith(root, "0000000")
        Files.createSymbolicLink(File(stale, "link").toPath(), outside.toPath())
        File(stale, "nested").mkdirs()
        assertFalse("an unexpected entry is reported, not silently skipped", EngineCache.sweepExcept(root, model, keep = null))
        assertTrue("a linked folder's contents survive", File(outside, "keep.txt").isFile)
        assertTrue("the folder holding the unexpected entries stays", stale.isDirectory)
    }
}
