package com.envi.wispr.polish

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.envi.wispr.cleanup.CleanupOptions
import com.envi.wispr.models.FirebaseModelFixture
import com.envi.wispr.models.ModelDeliveryStore
import com.envi.wispr.models.ModelManifest
import com.envi.wispr.models.ModelStorage
import com.envi.wispr.processing.ProcessingPreference
import com.envi.wispr.processing.ProcessingQualification
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * EXPERIMENT harness (exp/eg1-micro-opencl-bench), Harness Contract class: it measures, it protects nothing.
 *
 * Drives the real `:polish` service over its binder with fixed EG-1 Micro text cases on one backend arm
 * (`eg1Backend` = cpu | opencl, written to the debug override the portable runtime reads), from a cold process the
 * host created with force-stop. Writes every outcome, the polish process memory and the thermal readings to
 * `files/bench/<runTag>.json` for the host to pull. Cases come from `/data/local/tmp/<eg1Cases>`.
 */
@RunWith(AndroidJUnit4::class)
class Eg1OpenClBenchDeviceTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun benchOneBackendArm() {
        val args = InstrumentationRegistry.getArguments()
        val backend = requireNotNull(args.getString("eg1Backend")) { "pass -e eg1Backend cpu|opencl" }
        require(backend == "cpu" || backend == "opencl") { "eg1Backend must be cpu or opencl" }
        val runTag = requireNotNull(args.getString("runTag")) { "pass -e runTag <name>" }
        val casesName = requireNotNull(args.getString("eg1Cases")) { "pass -e eg1Cases <file in /data/local/tmp>" }
        val firstDelayMs = args.getString("firstDelayMs")?.toLong() ?: 0L
        val rounds = args.getString("rounds")?.toInt() ?: 1

        FirebaseModelFixture.stageEg1IfRequested(context)
        assertTrue(
            "EG-1 Micro must be verified in this app; pass -e firebaseEg1Fixture true with shards in /data/local/tmp",
            ModelDeliveryStore(ModelStorage.root(context)).isVerified(ModelManifest.eg1Micro),
        )
        val debugDir = File(context.filesDir, "debug").apply { mkdirs() }
        File(debugDir, "eg1-backend").writeText(backend)
        assertEquals(backend, File(debugDir, "eg1-backend").readText())
        val flashAttention = args.getString("eg1Fa") ?: "auto"
        require(flashAttention in setOf("auto", "on", "off")) { "eg1Fa must be auto, on or off" }
        File(debugDir, "eg1-fa").let { if (flashAttention == "auto") it.delete() else it.writeText(flashAttention) }

        val cases = JSONArray(runShell("cat /data/local/tmp/$casesName"))
        assertTrue("No cases in $casesName", cases.length() > 0)
        val polishProcess = "${context.packageName}:polish"
        assertTrue("$polishProcess must start cold; force-stop the app first", runShell("pidof $polishProcess").isBlank())

        val result = JSONObject().put("backend", backend).put("runTag", runTag).put("firstDelayMs", firstDelayMs).put("flashAttention", flashAttention)
        result.put("thermalStart", thermal())

        var service: IPolishService? = null
        val connected = CountDownLatch(1)
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                service = IPolishService.Stub.asInterface(binder); connected.countDown()
            }
            override fun onServiceDisconnected(name: ComponentName?) { service = null }
        }
        assertTrue(context.bindService(Intent(context, PolishService::class.java), connection, Context.BIND_AUTO_CREATE))
        try {
            assertTrue("PolishService did not connect", connected.await(15, TimeUnit.SECONDS))
            val proxy = checkNotNull(service)
            val policy = PolishPolicy.LocalS1(
                S1ControlSettings.DEFAULT, ProcessingPreference.DEFAULT, ProcessingQualification.NONE, LocalPolishModel.EG1_MICRO,
            )
            val warmAt = SystemClock.elapsedRealtime()
            proxy.warmUpWithPolicy(policy)
            // The scenario under test: the user keeps speaking for firstDelayMs while the model loads.
            if (firstDelayMs > 0) Thread.sleep(firstDelayMs)
            val outcomes = JSONArray()
            var requestId = 1L
            for (round in 0 until rounds) {
                for (index in 0 until cases.length()) {
                    val case = cases.getJSONObject(index)
                    val sentAt = SystemClock.elapsedRealtime()
                    val outcome = request(proxy, requestId++, case.getString("raw"), policy, "bench-$runTag-$round-$index")
                    val doneAt = SystemClock.elapsedRealtime()
                    val diagnostics = outcome.diagnostics
                    outcomes.put(
                        JSONObject()
                            .put("round", round).put("id", case.getString("id")).put("bucket", case.optString("bucket"))
                            .put("reason", outcome.reason.name).put("engine", outcome.engine)
                            .put("latencyMs", outcome.latencyMs).put("wallMs", doneAt - sentAt)
                            .put("sinceWarmMs", doneAt - warmAt)
                            .put("queueMs", diagnostics?.queueMs).put("generationMs", diagnostics?.generationMs)
                            .put("lastLoadMs", diagnostics?.lastLoadMs).put("runtime", diagnostics?.runtime?.identity)
                            .put("rawChars", case.getString("raw").length).put("text", outcome.text),
                    )
                    if (index == 0 && round == 0) result.put("memoryAfterFirst", memory(polishProcess))
                }
            }
            result.put("outcomes", outcomes)
            result.put("memoryEnd", memory(polishProcess))
            result.put("thermalEnd", thermal())
        } finally {
            context.unbindService(connection)
        }
        val out = File(context.filesDir, "bench").apply { mkdirs() }
        File(out, "$runTag.json").writeText(result.toString(1))
    }

    private fun request(service: IPolishService, id: Long, raw: String, policy: PolishPolicy, takeId: String): PolishOutcome {
        val done = CountDownLatch(1)
        var delivered: PolishOutcome? = null
        service.polishRequestWithCleanupForTake(
            id, raw, CleanupRequestOptions(CleanupOptions()), policy, takeId,
            object : IPolishCallback.Stub() {
                override fun onOutcome(outcome: PolishOutcome?) { delivered = outcome; done.countDown() }
            },
        )
        assertTrue("Polish request $takeId did not answer within 90 s", done.await(90, TimeUnit.SECONDS))
        return checkNotNull(delivered).also { assertEquals(id, it.requestId) }
    }

    private fun memory(process: String): JSONObject {
        val pid = runShell("pidof $process").trim()
        val json = JSONObject().put("pid", pid)
        if (pid.isEmpty()) return json
        val status = runShell("cat /proc/$pid/status")
        Regex("(?m)^(VmRSS|VmHWM):\\s+(\\d+) kB$").findAll(status).forEach { json.put(it.groupValues[1] + "Kb", it.groupValues[2].toLong()) }
        val meminfo = runShell("dumpsys meminfo $pid")
        Regex("(?m)^\\s*(TOTAL PSS|TOTAL|GL mtrack|Graphics|EGL mtrack|Other mtrack|Native Heap):?\\s+(\\d+)").findAll(meminfo)
            .forEach { json.put(it.groupValues[1].replace(' ', '_') + "Kb", it.groupValues[2].toLong()) }
        return json
    }

    private fun thermal(): JSONObject {
        val thermal = runShell("dumpsys thermalservice")
        val battery = runShell("dumpsys battery")
        return JSONObject()
            .put("status", Regex("Thermal Status: (\\d+)").find(thermal)?.groupValues?.get(1))
            .put("skinC", Regex("mValue=([0-9.]+), mType=3, mName=SKIN").find(thermal)?.groupValues?.get(1))
            .put("apC", Regex("mValue=([0-9.]+), mType=0, mName=AP").find(thermal)?.groupValues?.get(1))
            .put("batteryTenthsC", Regex("temperature: (\\d+)").find(battery)?.groupValues?.get(1))
            .put("powered", Regex("(AC|USB) powered: true").containsMatchIn(battery))
    }

    private fun runShell(command: String): String {
        val pfd = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command)
        return ParcelFileDescriptor.AutoCloseInputStream(pfd).bufferedReader().use { it.readText() }
    }
}
