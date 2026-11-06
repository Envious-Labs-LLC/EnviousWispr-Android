package com.envi.wispr.asr

import android.content.Context
import android.os.Build
import android.os.Process
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.envi.wispr.BuildConfig
import com.envi.wispr.models.ModelManifest
import com.envi.wispr.models.ModelStorage
import com.envi.wispr.models.ModelSwapGate
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Component qualification: active ORT invocation cancellation, exact complete words and retained-model reuse.
 * Real IPC: exact callback identity, callback-process death and orderly service generation turnover.
 * This bypasses recording/polish/insertion. Recorder/editor UAT and native-site mutation controls are separate.
 * JNI residency is NOT graph/kernel progress. No universal speed/cancellation or physical-power-loss claim.
 */
@RunWith(AndroidJUnit4::class)
class ParakeetCancellationDeviceTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    private open class Trace(private val stage: AsrObserver.Stage = AsrObserver.Stage.ENCODER) : AsrObserver {
        val target = AtomicReference<AsrObserver.Call?>()
        val current = AtomicReference<AsrObserver.Call?>()
        val raw = AtomicReference<NativeTerminationProof.Raw?>()
        val sessions = mutableMapOf<AsrObserver.Stage, Int>()
        private var model: Long? = null
        override fun entered(call: AsrObserver.Call) {
            check(model == null || model == call.model) { "A replaced model cannot count as retained-model reuse" }
            model = call.model
            val previous = sessions.putIfAbsent(call.stage, call.session)
            check(previous == null || previous == call.session) { "A replaced native session cannot count as reuse" }
            current.set(call)
            if (call.stage == stage) target.compareAndSet(null, call)
        }
        override fun returned(call: AsrObserver.Call) {
            if (target.get() === call) raw.set(NativeTerminationProof.Raw(call, true, null, null, System.nanoTime()))
            current.compareAndSet(call, null)
        }
        override fun failed(call: AsrObserver.Call, failure: ai.onnxruntime.OrtException) {
            if (target.get() === call) raw.set(NativeTerminationProof.Raw(call, false, failure.code.name, failure.message, System.nanoTime()))
            current.compareAndSet(call, null)
        }
    }

    private class SamplingTrace(private val cancellation: AsrCancellation, private val worker: AsrQualificationWorker) : Trace() {
        private val scheduler = ScheduledThreadPoolExecutor(1) { Thread(it, "AsrQualificationSample").apply { isDaemon = true } }
            .apply { removeOnCancelPolicy = true }
        private val scheduled = AtomicBoolean(false)
        val sampled = CountDownLatch(1)
        val sample = AtomicReference<NativeTerminationProof.Sample?>()
        val stack = AtomicReference<List<String>>(emptyList())
        val controlFailure = AtomicReference<String?>()
        override fun entered(call: AsrObserver.Call) {
            super.entered(call)
            if (call.stage != AsrObserver.Stage.ENCODER || !scheduled.compareAndSet(false, true)) return
            // test-fixture-timer: ONE preregistered sample after real encoder entry, never a completion oracle/retry.
            scheduler.schedule({
                try {
                    val before = current.get()
                    val workerThread: Thread = checkNotNull(worker.thread.get())
                    val frames = workerThread.stackTrace
                    val after = current.get()
                    stack.set(frames.map { it.toString() })
                    val native = before === call && after === call && frames.any {
                        it.isNativeMethod && it.className == "ai.onnxruntime.OrtSession" && it.methodName == "run"
                    }
                    val uncancelled = !cancellation.isCancelled
                    val at = System.nanoTime()
                    if (!native || !uncancelled) {
                        sample.set(NativeTerminationProof.Sample(call, native, uncancelled, at, at, false))
                    } else {
                        val cancelAt = System.nanoTime()
                        var returned = false
                        try { cancellation.cancel(); returned = true }
                        catch (failure: Throwable) { controlFailure.set(failure.javaClass.simpleName) }
                        sample.set(NativeTerminationProof.Sample(call, true, true, at, cancelAt, returned))
                    }
                } catch (failure: Throwable) { controlFailure.set(failure.javaClass.simpleName) }
                finally { sampled.countDown() }
            }, 20, TimeUnit.MILLISECONDS)
        }
        fun close() { scheduler.shutdown() }
        fun nativeExited() { if (!scheduled.get()) sampled.countDown() }
    }

    private class Run(val context: Context, target: String) {
        val budget = AsrQualificationBudget()
        val report = AsrQualificationReport(context, target)
        val worker = AsrQualificationWorker()
        var inputDirectory: File? = null
        var inputs: AsrQualificationInputs? = null
        var model: TdtModel? = null
        var omitted: NoNativeTerminationControl? = null
        var ipc: AsrQualificationIpc? = null
        var permit = false
        fun setup(step: AsrQualificationBudget.Step) {
            step.left()
            check(BuildConfig.TELEMETRY_POSTHOG_KEY.isEmpty() && BuildConfig.TELEMETRY_SENTRY_DSN.isEmpty()) { "Qualification builds must omit telemetry" }
            inputDirectory = Files.createTempDirectory(context.cacheDir.toPath(), "asr-qualification-").toFile()
            report.data.put("input_directory", checkNotNull(inputDirectory).path).put("device", "${Build.MANUFACTURER}/${Build.MODEL}")
                .put("api", Build.VERSION.SDK_INT).put("process", Process.myPid()).put("app_type", "debug")
                .put("app_sha256", AsrQualificationInputs.hash(File(context.applicationInfo.sourceDir)))
                .put("test_sha256", AsrQualificationInputs.hash(File(InstrumentationRegistry.getInstrumentation().context.applicationInfo.sourceDir)))
            report.save()
            inputs = worker.call(step) { AsrQualificationInputs(context, checkNotNull(inputDirectory)) }
            step.left()
            check(ModelSwapGate.main.admitTake { true } == ModelSwapGate.Admission.ADMITTED) { "Model-folder qualification permit was not admitted" }
            permit = true // Folder lifetime only; no successful speech-use/residency notification is manufactured.
        }
        fun finish(failure: Throwable?): Throwable? {
            var problem = failure
            fun attempt(action: () -> Unit) {
                try { action() } catch (error: Throwable) { val previous = problem; if (previous == null) problem = error else previous.addSuppressed(error) }
            }
            if (!worker.unconfirmed) {
                attempt { model?.let { owned -> worker.call(budget.cleanup()) { owned.close() }; model = null } }
                attempt { omitted?.let { owned -> worker.call(budget.cleanup()) { owned.close() }; omitted = null } }
            }
            attempt { ipc?.close(budget.cleanup()) }
            val safe = !worker.unconfirmed && model == null && omitted == null && ipc?.retirementConfirmed != false
            if (safe) {
                attempt { inputDirectory?.let(AsrQualificationInputs::removeOwnedInputs) }
                if (permit) { ModelSwapGate.main.releaseTake(); permit = false }
            }
            report.data.put("input_cleanup", when {
                !safe -> "RETAINED_UNCONFIRMED"
                inputDirectory == null -> "NOT_CREATED"
                inputDirectory?.exists() != true -> "REMOVED"
                else -> "CLEANUP_FAILED"
            })
            report.data.put("complete", problem == null && safe).put("status", if (problem == null && safe) "QUALIFIED" else "FAILED")
            problem?.let { report.data.put("failure_class", it.javaClass.simpleName) }
            attempt { report.save() }
            if (problem != null) {
                report.data.put("complete", false).put("status", "FAILED").put("failure_class", checkNotNull(problem).javaClass.simpleName)
                attempt { report.save() }
            }
            worker.shutdown()
            if (!safe) {
                // Unknown native work/cleanup is never disposed or passed. Retain partial evidence, then contain the fixture process.
                report.data.put("complete", false).put("status", "INCOMPLETE_PROCESS_END_REQUIRED")
                attempt { report.save() }
                Process.killProcess(Process.myPid())
            }
            return problem
        }
    }

    private fun callJson(call: AsrObserver.Call): JSONObject = JSONObject().put("model", call.model).put("invocation", call.invocation)
        .put("serial", call.serial).put("stage", call.stage.name).put("session", call.session).put("options_serial", call.options).put("thread", call.thread)
    private fun rawJson(raw: NativeTerminationProof.Raw?): Any = raw?.let {
        JSONObject().put("call", callJson(it.call)).put("returned", it.returned).put("code", it.code).put("message", it.message).put("nanos", it.atNanos)
    } ?: JSONObject.NULL

    @Test fun cancelledInvocationRetiresAndReusesTheModel() {
        val run = Run(context, "active-invocation-and-ipc")
        var failure: Throwable? = null
        try {
            val setup = run.budget.step(60)
            run.setup(setup)
            val input = checkNotNull(run.inputs)
            val directory = ModelStorage.directory(context, ModelManifest.parakeet)
            run.model = run.worker.call(setup) { TdtModel.open(directory, input.preprocessor, ParakeetEngine.THREADS) }
            val model = checkNotNull(run.model)
            val recipe = run.worker.call(setup) { TdtRecipe(model) }
            val shortSamples = run.worker.call(setup) { input.short.samples() }
            val longSamples = run.worker.call(setup) { input.long.samples() }
            val sessions = Trace()
            fun words(clip: AsrQualificationInputs.Clip, samples: FloatArray, seconds: Long, key: String) {
                val text = run.worker.call(run.budget.step(seconds)) {
                    model.withInvocation(AsrCancellation(), sessions) { recipe.transcribe(samples).text.trim() }
                }
                clip.requireWords(text)
                run.report.data.put(key, text)
                run.report.save()
            }
            words(input.short, shortSamples, 10, "baseline_short")
            words(input.long, longSamples, 45, "baseline_long")
            check(sessions.sessions.keys == AsrObserver.Stage.entries.toSet()) { "All three actual native session stages must be observed" }
            val originalSessions = sessions.sessions.toMap()
            val cancellation = AsrCancellation()
            val trace = SamplingTrace(cancellation, run.worker)
            try {
                val step = run.budget.step(8)
                val operation = run.worker.start(step, onExit = trace::nativeExited) {
                    try { model.withInvocation(cancellation, trace) { recipe.transcribe(longSamples) }; false }
                    catch (_: AsrCancelled) { true }
                }
                step.await(trace.sampled, "the one encoder JNI sample/control")
                val cancelled = run.worker.await(operation, step)
                val sample = trace.sample.get()
                val raw = trace.raw.get()
                val proof = NativeTerminationProof.activeInvocation(sample, raw)
                run.report.data.put("native_raw", rawJson(raw)).put("native_stack", JSONArray(trace.stack.get()))
                    .put("native_sample_call", sample?.call?.let(::callJson) ?: JSONObject.NULL)
                    .put("jni_residency_observed", sample?.nativeFrame == true).put("graph_progress_claim", "NOT_OBSERVED")
                    .put("native_control_returned", sample?.controlReturned == true).put("native_control_failure", trace.controlFailure.get())
                val retiredMs = sample?.let { (operation.retiredNanos.get() - it.cancellationNanos) / 1_000_000.0 }
                run.report.data.put("invocation_retirement_ms", retiredMs ?: JSONObject.NULL).put("native_proof", proof)
                run.report.save()
                check(proof) { "NOT STAGED: no same-call active ORT termination proof; no retry or preemption claim" }
                check(cancelled && retiredMs != null && retiredMs in 0.0..1_000.0) { "Healthy native invocation did not cleanly retire within the declared policy" }
                check(sample?.call?.session == originalSessions[AsrObserver.Stage.ENCODER])
            } finally { trace.close() }
            words(input.short, shortSamples, 10, "same_model_reuse_short")
            words(input.long, longSamples, 45, "same_model_reuse_long")
            check(sessions.sessions == originalSessions) { "A reload/replaced session cannot count as reuse" }
            run.report.data.put("retained_model_identity", model.modelIdentity)
            run.worker.call(run.budget.step(12)) { model.close() }
            run.model = null // Actual successful close before any ASR service bind; never two heavy models.
            run.ipc = AsrQualificationIpc(context, input, run.worker, run.report)
            checkNotNull(run.ipc).readyAndExactSiblingCancellation(run.budget.step(25))
            checkNotNull(run.ipc).clientDeathAndRetainedServiceReuse(run.budget.step(20))
            checkNotNull(run.ipc).orderlyServiceTurnover(run.budget.step(25))
        } catch (error: Throwable) { failure = error }
        run.finish(failure)?.let { throw it }
    }

    @Test fun everyNativeSiteRejectsTheControlWithSetTerminateRemoved() {
        val run = Run(context, "native-site-and-omission-controls-local")
        var failure: Throwable? = null
        try {
            val setup = run.budget.step(60)
            run.setup(setup)
            val input = checkNotNull(run.inputs)
            val directory = ModelStorage.directory(context, ModelManifest.parakeet)
            val samples = run.worker.call(setup) { input.short.samples() }
            val results = JSONArray()
            run.model = run.worker.call(setup) { TdtModel.open(directory, input.preprocessor, ParakeetEngine.THREADS) }
            val candidate = checkNotNull(run.model)
            val recipe = run.worker.call(setup) { TdtRecipe(candidate) }
            fun site(stage: AsrObserver.Stage, omitted: Boolean, work: (AsrCancellation, AsrObserver) -> Unit) {
                val token = AsrCancellation()
                val controlFailure = AtomicReference<Throwable?>()
                val trace = object : Trace(stage) {
                    private val first = AtomicBoolean(true)
                    override fun entered(call: AsrObserver.Call) {
                        super.entered(call)
                        if (call.stage == stage && first.compareAndSet(true, false)) {
                            try { token.cancel() } catch (error: Throwable) { controlFailure.set(error) }
                        }
                    }
                }
                val cancelled = run.worker.call(run.budget.step(45)) {
                    try { work(token, trace); false } catch (_: AsrCancelled) { true }
                }
                val target = checkNotNull(trace.target.get()) { "Native site was not reached" }
                val raw = trace.raw.get()
                val accepted = NativeTerminationProof.terminated(target, raw)
                results.put(JSONObject().put("stage", stage.name).put("omitted_native_termination", omitted)
                    .put("raw", rawJson(raw)).put("native_oracle_accepted", accepted).put("typed_cancelled", cancelled))
                run.report.data.put("site_controls", results)
                run.report.save()
                check(controlFailure.get() == null && cancelled)
                check(if (omitted) !accepted && raw?.returned == true else accepted) { "Native oracle did not distinguish actual termination from Kotlin-only cancellation" }
                check(!NativeTerminationProof.activeInvocation(null, raw)) { "Pre-entry native refusal cannot count as active-invocation proof" }
            }
            AsrObserver.Stage.entries.forEach { stage ->
                site(stage, false) { token, observer -> candidate.withInvocation(token, observer) { recipe.transcribe(samples) } }
            }
            run.worker.call(run.budget.step(12)) { candidate.close() }
            run.model = null
            run.omitted = run.worker.call(run.budget.step(60)) { NoNativeTerminationControl.open(directory, input.preprocessor, ParakeetEngine.THREADS) }
            val omitted = checkNotNull(run.omitted)
            val controlRecipe = run.worker.call(run.budget.step(10)) { TdtRecipe(omitted) }
            AsrObserver.Stage.entries.forEach { stage ->
                site(stage, true) { token, observer -> omitted.withInvocation(token, observer) { controlRecipe.transcribe(samples) } }
            }
        } catch (error: Throwable) { failure = error }
        run.finish(failure)?.let { throw it }
    }
}
