package com.envi.wispr.models

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #374 chunk 1: the staged speech model is queued only on a phone that already dictates, only until it verifies,
 * and never against the user's pause or cancel. Product outcome: when this fails, a phone either never gets the new
 * model before the engine swap, or a download the user stopped starts again by itself.
 */
class StagedModelTriggerTest {
    private val active = ModelDeliveryControlState.ACTIVE

    @Test fun queuesOnlyWhenTheModelInUseIsReadyAndTheStagedOneIsNot() {
        assertTrue(StagedModelTrigger.shouldQueue(inUseReady = true, stagedReady = false, control = active))
        assertFalse("a fresh install keeps today's setup flow", StagedModelTrigger.shouldQueue(false, false, active))
        assertFalse("already verified: nothing to do", StagedModelTrigger.shouldQueue(true, true, active))
    }

    /** MUTATION: drop the control check (a paused or cancelled download is then resumed on every app start). */
    @Test fun neverResumesADownloadTheUserPausedOrCancelled() {
        assertFalse(StagedModelTrigger.shouldQueue(true, false, ModelDeliveryControlState.PAUSED))
        assertFalse(StagedModelTrigger.shouldQueue(true, false, ModelDeliveryControlState.CANCELLED))
    }

    /**
     * The whole pass queues exactly the staged models that are due, and nothing else. MUTATIONS: drop the enqueue call;
     * enqueue unconditionally; ignore the control state.
     */
    @Test fun thePassQueuesExactlyTheDueModels() {
        fun pass(inUseReady: Boolean, ready: Boolean, control: ModelDeliveryControlState): List<String> {
            val queued = mutableListOf<String>()
            StagedModelTrigger.queuePending(inUseReady, ModelManifest.staged, { ready }, { control }) { queued += it.id }
            return queued
        }
        assertEquals(listOf("parakeet-sq"), pass(true, false, active))
        assertEquals(emptyList<String>(), pass(true, false, ModelDeliveryControlState.PAUSED))
        assertEquals(emptyList<String>(), pass(true, false, ModelDeliveryControlState.CANCELLED))
        assertEquals(emptyList<String>(), pass(true, true, active))
        assertEquals(emptyList<String>(), pass(false, false, active))
    }

    /** The decision reads the store the Storage page writes: a stored cancel is what the trigger sees. */
    @Test fun readsTheUsersStoredChoice() {
        val root = createTempDir()
        try {
            val store = ModelDeliveryControlStore(root)
            assertEquals(active, store.read(ModelManifest.parakeetSq))
            store.write(ModelManifest.parakeetSq, ModelDeliveryControlState.CANCELLED)
            assertFalse(StagedModelTrigger.shouldQueue(true, false, store.read(ModelManifest.parakeetSq)))
        } finally {
            root.deleteRecursively()
        }
    }

    /**
     * Harness contract on the source: the trigger reuses the existing KEEP enqueue and never the REPLACE path that
     * clears the control store, and start-up runs it off the main thread. MUTATIONS: call `enqueue`; call it on main.
     */
    @Test fun usesTheKeepEnqueueOffTheMainThread() {
        val trigger = File("src/main/java/com/envi/wispr/models/StagedModelTrigger.kt").readText()
        assertTrue(Regex("""ModelDeliveryWorker\.enqueueSetup\(context, \w+, mobileData = false\)""").containsMatchIn(trigger))
        assertFalse(Regex("""ModelDeliveryWorker\.enqueue\(""").containsMatchIn(trigger))
        val app = File("src/main/java/com/envi/wispr/models/ModelBootstrapApplication.kt").readText()
        val call = app.indexOf("StagedModelTrigger.run(")
        assertTrue(call > 0)
        assertTrue("run on the IO dispatcher", app.lastIndexOf("Dispatchers.IO).launch", call) in (call - 200)..call)
    }

    private fun createTempDir(): File = kotlin.io.path.createTempDirectory("staged-trigger").toFile()
}
