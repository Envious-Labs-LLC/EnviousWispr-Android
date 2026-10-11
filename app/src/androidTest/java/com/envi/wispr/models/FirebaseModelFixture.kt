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
        val model = ModelManifest.s1
        val entry = model.files.single()
        val source = Files.createTempDirectory(context.cacheDir.toPath(), "firebase-s1-").toFile()
        val local = File(source, entry.name)
        try {
            // Test Lab --other-files uploads here before instrumentation starts. Shell can read
            // this approved path; the app needs no shared-storage permission. Never interpolate input.
            val descriptor = InstrumentationRegistry.getInstrumentation().uiAutomation
                .executeShellCommand("cat /data/local/tmp/s1-mini-q4_k_m.gguf")
            ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { input ->
                local.outputStream().use { output -> input.copyTo(output) }
            }
            assertEquals("Test Lab model fixture is incomplete", entry.expectedBytes, local.length())
            val store = ModelDeliveryStore(ModelStorage.root(context))
            assertEquals("Pinned model admission failed", DownloadState.READY, store.adoptExisting(model, source).state)
            assertTrue("Admitted model did not verify", store.isVerified(model))
            DebugLogger.log("FirebaseFixture", "S1 fixture verified bytes=${entry.expectedBytes}")
        } finally {
            check(local.delete() || !local.exists()) { "Could not remove test fixture copy" }
            check(source.delete() || !source.exists()) { "Could not remove test fixture directory" }
        }
    }
}
