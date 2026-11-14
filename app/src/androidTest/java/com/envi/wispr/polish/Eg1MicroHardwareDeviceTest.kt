package com.envi.wispr.polish

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.os.ParcelFileDescriptor
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.envi.wispr.cleanup.CleanupOptions
import com.envi.wispr.debug.DebugLogger
import com.envi.wispr.llama.S1Native
import com.envi.wispr.models.FirebaseModelFixture
import com.envi.wispr.models.ModelDeliveryControlStore
import com.envi.wispr.models.ModelDeliveryStore
import com.envi.wispr.models.ModelDeliveryWorker
import com.envi.wispr.models.ModelDescriptor
import com.envi.wispr.models.ModelDownloadPolicy
import com.envi.wispr.models.ModelManifest
import com.envi.wispr.models.ModelStorage
import com.envi.wispr.processing.ProcessingPreference
import com.envi.wispr.processing.ProcessingQualification
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Physical-device UAT gate for EG-1 Micro (#528) against Firebase Test Lab hardware (`SC-51E`, `shiba`):
 * exercises seq-0 prefix cache parity on both S1-mini and EG-1 Micro, real `:polish` binder swaps under
 * `< 1.5 GiB` RSS, 5+ dictations (email budget & non-English preservation), and live 2-shard R2 delivery.
 */
@RunWith(AndroidJUnit4::class)
class Eg1MicroHardwareDeviceTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun eg1AndS1HardwareParityCacheSwapAndDictations() {
        FirebaseModelFixture.stageS1IfRequested(context)
        FirebaseModelFixture.stageEg1IfRequested(context)
        val store = ModelDeliveryStore(ModelStorage.root(context))
        assertTrue("S1-mini fixture must be verified before hardware UAT", store.isVerified(ModelManifest.s1))
        assertTrue("EG-1 Micro fixture must be verified before hardware UAT", store.isVerified(ModelManifest.eg1Micro))

        verifyInProcessSeq0PrefixCacheParity(
            descriptor = ModelManifest.s1,
            systemPrompt = S1Config.SYSTEM_PROMPT,
            userPrompt = S1PromptBuilder.buildUserPrompt(
                "uh enviouswispr works with saurabh and it is really really useful",
                S1ControlSettings.DEFAULT,
            ),
            tag = "S1-mini",
        )
        verifyInProcessSeq0PrefixCacheParity(
            descriptor = ModelManifest.eg1Micro,
            systemPrompt = EGOneNamedLanguagePromptBuilder.SYSTEM_PROMPT,
            userPrompt = EGOneNamedLanguagePromptBuilder.buildUserPrompt(
                "uh enviouswispr works with saurabh and it is really really useful",
            ),
            tag = "EG-1 Micro",
        )

        var service: IPolishService? = null
        val connected = CountDownLatch(1)
        var bound = false
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                service = IPolishService.Stub.asInterface(binder)
                connected.countDown()
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                service = null
            }
        }

        bound = context.bindService(Intent(context, PolishService::class.java), connection, Context.BIND_AUTO_CREATE)
        assertTrue("PolishService bind returned false", bound)
        try {
            assertTrue("PolishService did not connect", connected.await(15, TimeUnit.SECONDS))
            val proxy = checkNotNull(service)
            val s1Policy = PolishPolicy.LocalS1(
                S1ControlSettings.DEFAULT,
                ProcessingPreference.DEFAULT,
                ProcessingQualification.NONE,
                LocalPolishModel.S1_MINI,
            )
            val eg1Policy = PolishPolicy.LocalS1(
                S1ControlSettings.DEFAULT,
                ProcessingPreference.DEFAULT,
                ProcessingQualification.NONE,
                LocalPolishModel.EG1_MICRO,
            )

            // Cold & warm S1-mini through real `:polish` binder.
            proxy.warmUpWithPolicy(s1Policy)
            val s1Cold = requestTake(
                proxy,
                1L,
                "uh enviouswispr works with saurabh and it is really really useful",
                s1Policy,
                "uat-s1-cold",
            )
            assertEquals(PolishReason.POLISHED, s1Cold.reason)
            assertTrue("Unexpected S1 engine: ${s1Cold.engine}", s1Cold.engine.startsWith("S1-mini by Superwhisper"))
            assertEquals(LocalPolishModel.S1_MINI.wire, s1Cold.processing?.modelId)
            val s1ColdLoadMs = checkNotNull(s1Cold.diagnostics?.lastLoadMs)
            assertTrue("Expected positive cold S1 load time, got $s1ColdLoadMs", s1ColdLoadMs > 0L)

            val s1Warm = requestTake(
                proxy,
                2L,
                "please send the report tomorrow morning",
                s1Policy,
                "uat-s1-warm",
            )
            assertEquals(PolishReason.POLISHED, s1Warm.reason)
            assertEquals(s1ColdLoadMs, s1Warm.diagnostics?.lastLoadMs)
            val polishPid = readPolishPid()

            // Live runtime swap: S1-mini -> EG-1 Micro.
            proxy.warmUpWithPolicy(eg1Policy)
            val eg1Cold = requestTake(
                proxy,
                3L,
                "uh enviouswispr works with saurabh and it is really really useful",
                eg1Policy,
                "uat-swap-to-eg1",
            )
            assertEquals(PolishReason.POLISHED, eg1Cold.reason)
            assertEquals("EG-1 Micro by Envious Labs (CPU)", eg1Cold.engine)
            assertEquals(S1RuntimeKind.PORTABLE_CPU, eg1Cold.diagnostics?.runtime)
            assertEquals(LocalPolishModel.EG1_MICRO.wire, eg1Cold.processing?.modelId)
            assertNoThinkTags(eg1Cold.text)
            assertEquals("Expected same :polish PID after swap to EG-1", polishPid, readPolishPid())
            val (eg1RssKb, eg1HwmKb) = readPolishMemoryKb(polishPid)
            assertTrue("Expected :polish VmRSS in 1..1_572_864 kB, got $eg1RssKb", eg1RssKb in 1L..RSS_LIMIT_KB)
            assertTrue("Expected :polish VmHWM in 1..1_572_864 kB, got $eg1HwmKb", eg1HwmKb in 1L..RSS_LIMIT_KB)

            // Live swap back: EG-1 Micro -> S1-mini.
            proxy.warmUpWithPolicy(s1Policy)
            val backToS1 = requestTake(
                proxy,
                4L,
                "please send the report tomorrow morning",
                s1Policy,
                "uat-swap-back-s1",
            )
            assertEquals(PolishReason.POLISHED, backToS1.reason)
            assertTrue("Unexpected S1 engine after swap back: ${backToS1.engine}", backToS1.engine.startsWith("S1-mini by Superwhisper"))
            assertEquals(LocalPolishModel.S1_MINI.wire, backToS1.processing?.modelId)
            assertEquals("Expected same :polish PID after swap back to S1", polishPid, readPolishPid())
            val (s1AfterSwapRssKb, _) = readPolishMemoryKb(polishPid)
            assertTrue("Expected S1 VmRSS < 1.5 GiB after swap back, got $s1AfterSwapRssKb", s1AfterSwapRssKb in 1L..RSS_LIMIT_KB)

            // Switch to EG-1 Micro and execute 5 real dictations (short, medium, email budget, Spanish).
            proxy.warmUpWithPolicy(eg1Policy)
            val d1 = requestTake(
                proxy,
                5L,
                "uh enviouswispr works with saurabh and it is really really useful",
                eg1Policy,
                "uat-eg1-1",
            )
            val d2 = requestTake(
                proxy,
                6L,
                "um can we move the standup to three thirty tomorrow afternoon",
                eg1Policy,
                "uat-eg1-2",
            )
            val d3 = requestTake(
                proxy,
                7L,
                "so basically the build passed all unit tests on the first try",
                eg1Policy,
                "uat-eg1-3",
            )
            val d4Email = requestTake(
                proxy,
                8L,
                "hey alex i wanted to follow up on the android release schedule um we finished validating the local polish model today and the latency looks great so let us aim to ship the internal testing build this afternoon thanks saurabh",
                eg1Policy,
                "uat-eg1-email",
            )
            val d5Spanish = requestTake(
                proxy,
                9L,
                "eh hola carlos por favor envíame el informe de ventas mañana por la mañana muchas gracias",
                eg1Policy,
                "uat-eg1-spanish",
            )

            val allEg1 = listOf(d1, d2, d3, d4Email, d5Spanish)
            allEg1.forEach { outcome ->
                assertEquals(PolishReason.POLISHED, outcome.reason)
                assertEquals("EG-1 Micro by Envious Labs (CPU)", outcome.engine)
                assertTrue("Blank polish output for ${outcome.requestId}", outcome.text.isNotBlank())
                assertNoThinkTags(outcome.text)
            }
            assertTrue(
                "EG-1 email latency ${d4Email.latencyMs}ms exceeded 20s local budget",
                d4Email.latencyMs < EG1_EMAIL_MAX_LATENCY_MS,
            )
            val spanishLower = d5Spanish.text.lowercase()
            assertTrue("Expected Spanish 'informe' in ${d5Spanish.text}", spanishLower.contains("informe"))
            assertTrue("Expected Spanish 'mañana' in ${d5Spanish.text}", spanishLower.contains("mañana"))

            val warmLatencies = listOf(d2, d3, d4Email, d5Spanish).map { it.latencyMs }.sorted()
            val warmP50Ms = warmLatencies[warmLatencies.size / 2]
            DebugLogger.log(
                "Eg1HardwareUat",
                "s1ColdLoadMs=$s1ColdLoadMs s1WarmLatencyMs=${s1Warm.latencyMs} " +
                    "eg1ColdLoadMs=${eg1Cold.diagnostics?.lastLoadMs} eg1WarmP50Ms=$warmP50Ms " +
                    "eg1EmailLatencyMs=${d4Email.latencyMs} polishPid=$polishPid " +
                    "rssKb=$eg1RssKb hwmKb=$eg1HwmKb s1CacheParity=true eg1CacheParity=true",
            )
        } finally {
            if (bound) context.unbindService(connection)
        }
    }

    @Test
    fun eg1LiveR2TwoShardWorkerDownloadVerifiesBothShards() {
        val model = ModelManifest.eg1Micro
        val root = ModelStorage.root(context)
        val store = ModelDeliveryStore(root)
        val controls = ModelDeliveryControlStore(root)
        val workManager = WorkManager.getInstance(context)
        val workName = ModelDeliveryWorker.downloadWorkName(model)

        workManager.cancelUniqueWork(workName).result.get(15, TimeUnit.SECONDS)
        val beforeIds = workManager.getWorkInfosForUniqueWork(workName).get(15, TimeUnit.SECONDS).map { it.id }.toSet()
        controls.clear(model)
        assertTrue("Could not remove staged EG-1 Micro before live R2 test", store.remove(model))
        assertFalse("EG-1 Micro remained verified after removal", store.isVerified(model))

        val startedAt = System.currentTimeMillis()
        var lastBytes = -1L
        var progressSamples = 0
        val expectedTotal = model.files.sumOf { it.expectedBytes }
        var targetId: UUID? = null
        val finished = runBlocking {
            ModelDownloadPolicy.allowMobileData(context, model)
            ModelDeliveryWorker.enqueue(context, model)
            withTimeout(R2_DOWNLOAD_TIMEOUT_MS) {
                workManager.getWorkInfosForUniqueWorkFlow(workName)
                    .map { infos ->
                        val match = if (targetId != null) {
                            infos.firstOrNull { it.id == targetId }
                        } else {
                            infos.firstOrNull { it.id !in beforeIds }?.also { targetId = it.id }
                        }
                        if (match != null) {
                            val reported = match.progress.getLong(ModelDeliveryWorker.KEY_BYTES, -1L)
                            if (reported >= 0L) {
                                progressSamples += 1
                                assertTrue(
                                    "Expected cumulative progress <= $expectedTotal, got $reported",
                                    reported <= expectedTotal,
                                )
                                assertTrue(
                                    "Progress decreased from $lastBytes to $reported",
                                    reported >= lastBytes,
                                )
                                lastBytes = reported
                            }
                        }
                        match
                    }
                    .filterNotNull()
                    .first { it.state.isFinished }
            }
        }
        assertEquals(
            "EG-1 Micro R2 worker ended in ${finished.state} (${finished.outputData.getString(ModelDeliveryWorker.KEY_REASON)})",
            WorkInfo.State.SUCCEEDED,
            finished.state,
        )
        assertTrue("Downloaded EG-1 Micro failed store verification", store.isVerified(model))
        val dir = File(root, model.id)
        model.files.forEach { fileSpec ->
            val shard = File(dir, fileSpec.name)
            assertTrue("Missing downloaded shard ${shard.name}", shard.isFile)
            assertEquals("Wrong size for shard ${shard.name}", fileSpec.expectedBytes, shard.length())
        }
        val elapsedMs = System.currentTimeMillis() - startedAt
        DebugLogger.log(
            "Eg1HardwareUat",
            "r2Download elapsedMs=$elapsedMs observedProgressSamples=$progressSamples maxBytes=$lastBytes expectedTotal=$expectedTotal",
        )
    }

    private fun verifyInProcessSeq0PrefixCacheParity(
        descriptor: ModelDescriptor,
        systemPrompt: String,
        userPrompt: String,
        tag: String,
    ) {
        val selection = checkNotNull(S1ModelSelector.resolve(context, descriptor)) {
            "Verified model not found for $tag"
        }
        val initStatus = S1Native.initialize(context.applicationInfo.nativeLibraryDir)
        assertFalse("S1Native initialize failed for $tag: $initStatus", initStatus.startsWith("ERROR:"))
        try {
            val loadStatus = S1Native.loadModel(selection.file.path, S1Config.CONTEXT_SIZE, S1Config.THREAD_COUNT)
            assertFalse("S1Native loadModel failed for $tag: $loadStatus", loadStatus.startsWith("ERROR:"))

            val uncached = S1Native.generate(systemPrompt, userPrompt, 128)
            assertFalse("Uncached $tag generation failed: $uncached", uncached.startsWith("ERROR:"))
            val uncachedEnd = S1Native.lastEnd()
            assertEquals("Expected 4-element S1Native.lastEnd for $tag", 4, uncachedEnd.size)
            assertEquals("Uncached $tag unexpectedly hit prefix cache", 0, uncachedEnd[3])

            assertTrue("$tag S1Native.warmPrefix returned false", S1Native.warmPrefix(systemPrompt))

            val cached = S1Native.generate(systemPrompt, userPrompt, 128)
            assertFalse("Cached $tag generation failed: $cached", cached.startsWith("ERROR:"))
            val cachedEnd = S1Native.lastEnd()
            assertEquals("Cached $tag did not use seq-0 prefix cache", 1, cachedEnd[3])
            assertEquals("$tag cached output diverged from uncached output", uncached, cached)
            DebugLogger.log("Eg1HardwareUat", "cacheParity=$tag uncachedCacheFlag=${uncachedEnd[3]} cachedFlag=${cachedEnd[3]} chars=${cached.length}")
        } finally {
            S1Native.unload()
        }
    }

    private fun requestTake(
        service: IPolishService,
        requestId: Long,
        rawText: String,
        policy: PolishPolicy,
        takeId: String,
    ): PolishOutcome {
        val done = CountDownLatch(1)
        var delivered: PolishOutcome? = null
        service.polishRequestWithCleanupForTake(
            requestId,
            rawText,
            CleanupRequestOptions(CleanupOptions()),
            policy,
            takeId,
            object : IPolishCallback.Stub() {
                override fun onOutcome(outcome: PolishOutcome?) {
                    delivered = outcome
                    done.countDown()
                }
            },
        )
        assertTrue("Polish request $takeId ($requestId) timed out", done.await(90, TimeUnit.SECONDS))
        return checkNotNull(delivered).also { assertEquals(requestId, it.requestId) }
    }

    private fun assertNoThinkTags(text: String) {
        assertFalse("Unexpected <think> leak in polish output: $text", text.contains("<think>"))
        assertFalse("Unexpected </think> leak in polish output: $text", text.contains("</think>"))
    }

    private fun readPolishPid(): Int {
        val raw = runShell("pidof com.envi.wispr:polish").trim()
        assertTrue("com.envi.wispr:polish is not running", raw.isNotEmpty())
        val pids = raw.split(Regex("\\s+"))
        assertEquals("Expected single :polish process, got $pids", 1, pids.size)
        return pids.single().toInt()
    }

    private fun readPolishMemoryKb(pid: Int): Pair<Long, Long> {
        val status = runShell("cat /proc/$pid/status")
        val rss = checkNotNull(Regex("(?m)^VmRSS:\\s+(\\d+) kB$").find(status)) {
            "VmRSS missing in /proc/$pid/status"
        }.groupValues[1].toLong()
        val hwm = checkNotNull(Regex("(?m)^VmHWM:\\s+(\\d+) kB$").find(status)) {
            "VmHWM missing in /proc/$pid/status"
        }.groupValues[1].toLong()
        return rss to hwm
    }

    private fun runShell(command: String): String {
        val pfd = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command)
        return ParcelFileDescriptor.AutoCloseInputStream(pfd).bufferedReader().use { it.readText() }
    }

    private companion object {
        const val RSS_LIMIT_KB = 1_572_864L // 1.5 GiB
        const val EG1_EMAIL_MAX_LATENCY_MS = 20_000L
        const val R2_DOWNLOAD_TIMEOUT_MS = 180_000L
    }
}
