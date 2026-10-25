package com.envi.wispr.models

import java.io.ByteArrayInputStream
import java.io.File
import java.security.MessageDigest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Product outcome (#108 plan §17.4, §18.1 to §18.3, §19). When this fails, a take starts while an update renames the
 * model folders, an engine binds during the swap, start-up lets an engine read a folder recovery is still moving, or
 * an update replaces the model still dictating outside the gate (or is lost, or downloaded twice).
 */
class ModelSwapGateTest {
    @get:Rule val folder = TemporaryFolder()

    /** MUTATION: start open; or let a swap close the gate under a take; or run the owner's step while refusing. */
    @Test fun takesAndSwapsExcludeEachOtherAndStartUpWaitsForRecovery() {
        val gate = ModelSwapGate()
        var transitions = 0
        val step = { transitions++; true }
        assertEquals("closed until start-up recovery", ModelSwapGate.Admission.UPDATING, gate.admitTake(step))
        assertFalse(gate.open)
        assertFalse(gate.beginSwap())
        assertEquals(0, transitions)
        gate.recovered()
        assertEquals(ModelSwapGate.Admission.ADMITTED, gate.admitTake(step))
        assertFalse("a swap never closes the gate under a take", gate.beginSwap())
        assertFalse(gate.idle)
        gate.releaseTake()
        assertTrue(gate.idle)
        assertTrue(gate.beginSwap())
        assertEquals("a start during the swap is refused", ModelSwapGate.Admission.UPDATING, gate.admitTake(step))
        assertEquals("and the owner's state is never touched", 1, transitions)
        assertNull("no engine binds during the swap", gate.ifOpen { "bound" })
        assertFalse("one swap at a time", gate.beginSwap())
        gate.endSwap()
        assertEquals("bound", gate.ifOpen { "bound" })
        assertEquals("a busy owner is not admitted", ModelSwapGate.Admission.NOT_IDLE, gate.admitTake { false })
        assertTrue("and holds no permit", gate.idle)
    }

    private fun descriptor(bytes: ByteArray, revision: String) = ModelDescriptor(
        "demo", "test", "Demo", "Test", "Test", "", revision,
        listOf(ModelFile("model.bin", bytes.size.toLong(), hash(bytes), "https://huggingface.co/test/demo/resolve/$revision/model.bin")),
    )
    private val oldBytes = "previous model".toByteArray()
    private val newBytes = "current model!".toByteArray()
    private val previous = descriptor(oldBytes, "1".repeat(40))
    private val current = descriptor(newBytes, "2".repeat(40)).copy(previous = listOf(previous))
    private fun transport(bytes: ByteArray, opened: MutableList<String> = mutableListOf()) =
        ModelTransport { url, _ -> opened += url; TransportResponse(ByteArrayInputStream(bytes), false) }
    private fun installed(root: File) = File(root, "demo/model.bin").readText()

    /**
     * An update that cannot be applied now ends STAGED: the previous model stays in place, and the next attempt applies
     * the staged revision without fetching it again. REVERT: admit outside [admission], or refetch a complete staging.
     */
    @Test fun anUpdateWaitsInStagingUntilTheGatedAdmissionRuns() {
        val root = folder.root
        val store = ModelDeliveryStore(root)
        assertEquals(DownloadState.READY, store.download(previous, transport(oldBytes)).state)
        val deferred = store.download(current, transport(newBytes), admission = { null })
        assertEquals(DownloadState.STAGED, deferred.state)
        assertEquals("the previous model keeps dictating", "previous model", installed(root))
        assertEquals(previous, store.usableDescriptor(current))
        val opened = mutableListOf<String>()
        var admissions = 0
        val applied = store.download(current, transport(newBytes, opened), admission = { admissions++; it() })
        assertEquals(DownloadState.READY, applied.state)
        assertEquals("nothing fetched twice", emptyList<String>(), opened)
        assertEquals(1, admissions)
        assertEquals("current model!", installed(root))
        assertFalse(File(root, ".demo.old").exists())
    }

    /** Recovery never replaces a final that still holds a previous revision; that is the gated admission's job. */
    @Test fun recoveryLeavesThePreviousModelForTheGatedAdmission() {
        val root = folder.root
        val store = ModelDeliveryStore(root)
        store.download(previous, transport(oldBytes))
        store.download(current, transport(newBytes), admission = { null })
        assertFalse("start-up recovery leaves it", store.reconcile(current))
        assertEquals("previous model", installed(root))
        assertTrue("a waiting update keeps the gate shut at start until recovery has looked", store.interruptedAdmission(current))
    }

    /**
     * Drift guard: every engine bind in the main process goes through `EngineBindings`, and every final-folder write in
     * the worker through `ModelSwap.exclusive`. A bind elsewhere survives a swap's revocation; a write elsewhere
     * renames folders under a running engine.
     */
    @Test fun everyEngineBindAndFolderWriteGoesThroughTheSwap() {
        val sources = File("src/main/java").walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
        val binders = sources.filter { Regex("""bindService\(Intent\([^)]*(AsrService|PolishService)::class""").containsMatchIn(it.readText()) }.map { it.name }
        assertEquals("no engine bind outside the registry", emptyList<String>(), binders)
        val worker = File("src/main/java/com/envi/wispr/models/ModelDeliveryWorker.kt").readText()
        assertTrue(worker.contains("ModelSwap.exclusive(applicationContext) { store.remove(model) }"))
        assertTrue(worker.contains("ModelSwap.exclusive(applicationContext) { store.repair(model) }"))
        assertEquals("download and adoption admit through the swap", 2, Regex("""admission = \{ mutation -> gatedAdmission\(model, controls, mutation\) \}""").findAll(worker).count())
        assertTrue(worker.contains("ModelSwap.exclusive(applicationContext) {\n            if (isStopped || controls.read(model) != ModelDeliveryControlState.ACTIVE) false else mutation()"))
        val registry = File("src/main/java/com/envi/wispr/models/EngineBindings.kt").readText()
        assertTrue(registry.contains("ModelSwapGate.main.ifOpen {"))
        val start = File("src/main/java/com/envi/wispr/models/ModelBootstrapApplication.kt").readText()
        assertTrue("the gate opens after start-up recovery", start.substringAfter("speechUpgradeScope.launch {").substringBefore("SpeechModelUpgrade.run(app)").contains("ModelSwapGate.main.recovered()"))
    }

    private fun hash(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
