package com.envi.wispr.models

import android.content.Context
import android.os.ParcelFileDescriptor
import androidx.test.platform.app.InstrumentationRegistry
import com.envi.wispr.debug.DebugLogger
import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue

/** Harness Contract: stage Test Lab's model file using production integrity/admission checks. */
internal object FirebaseModelFixture {
    fun stageS1IfRequested(context: Context) {
        val option = InstrumentationRegistry.getArguments().getString("firebaseS1Fixture") ?: return
        require(option == "true") { "firebaseS1Fixture must be true when supplied" }
        stageModel(context, ModelManifest.s1, "firebase-s1-")
    }

    fun stageEg1IfRequested(context: Context) {
        val option = InstrumentationRegistry.getArguments().getString("firebaseEg1Fixture") ?: return
        require(option == "true") { "firebaseEg1Fixture must be true when supplied" }
        stageModel(context, ModelManifest.eg1Micro, "firebase-eg1-")
    }

    /** Existing speech benchmark fixture, shared with recovery qualification. */
    fun stageParakeet(context: Context) {
        stageModel(context, ModelManifest.parakeet, "firebase-parakeet-")
    }

    private fun stageModel(context: Context, model: ModelDescriptor, tempPrefix: String) {
        val source = Files.createTempDirectory(context.cacheDir.toPath(), tempPrefix).toFile()
        val copied = mutableListOf<File>()
        try {
            // Test Lab --other-files uploads here before instrumentation starts. Shell can read
            // this approved path; the app needs no shared-storage permission. Never interpolate input.
            model.files.forEach { entry ->
                val local = copyFromTmp(entry.name, source)
                copied += local
                assertEquals("Test Lab fixture ${entry.name} is incomplete", entry.expectedBytes, local.length())
            }
            val store = ModelDeliveryStore(ModelStorage.root(context))
            assertEquals("Pinned ${model.displayName} admission failed", DownloadState.READY, store.adoptExisting(model, source).state)
            assertTrue("Admitted ${model.displayName} did not verify", store.isVerified(model))
            DebugLogger.log("FirebaseFixture", "${model.displayName} fixture verified bytes=${model.files.sumOf { it.expectedBytes }}")
        } finally {
            copied.forEach { file -> check(file.delete() || !file.exists()) { "Could not remove test fixture copy ${file.name}" } }
            source.listFiles()?.forEach { check(it.delete() || !it.exists()) { "Could not remove leftover fixture file ${it.name}" } }
            check(source.delete() || !source.exists()) { "Could not remove test fixture directory" }
        }
    }

    internal fun copyFromTmp(name: String, into: File): File {
        val target = File(into, name)
        val descriptor = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand("cat /data/local/tmp/$name")
        ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { input -> target.outputStream().use { input.copyTo(it) } }
        assertTrue("Test Lab did not upload $name", target.length() > 0)
        return target
    }
}
