package com.envi.wispr.asr

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.os.Process
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.json.JSONObject

/** Real service/client death qualification, distinct from direct-model native proof and recorder/editor UAT. */
internal class AsrQualificationIpc(
    private val context: Context,
    private val inputs: AsrQualificationInputs,
    private val worker: AsrQualificationWorker,
    private val report: AsrQualificationReport,
) {
    private class Binding(private val context: Context, service: Class<*>) : AutoCloseable {
        private enum class State { NEW, ATTEMPTED, CONFIRMED, CLOSED }
        private var state = State.NEW
        private val connected = CountDownLatch(1)
        val died = CountDownLatch(1)
        private val death = IBinder.DeathRecipient { died.countDown() }
        private var linked = false
        @Volatile var binder: IBinder? = null
        private val intent = Intent(context, service)
        private val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, service: IBinder?) { binder = service; connected.countDown() }
            override fun onServiceDisconnected(name: ComponentName?) { }
            override fun onBindingDied(name: ComponentName?) { connected.countDown() }
            override fun onNullBinding(name: ComponentName?) { connected.countDown() }
        }
        fun open(step: AsrQualificationBudget.Step): IBinder {
            step.left() // Admission check, before a bind can create a service/model owner.
            state = State.ATTEMPTED
            check(context.bindService(intent, connection, Context.BIND_AUTO_CREATE)) { "Qualification service bind refused" }
            state = State.CONFIRMED
            step.await(connected, "real service connected")
            val service = checkNotNull(binder) { "Qualification binding returned no Binder" }
            service.linkToDeath(death, 0)
            linked = true
            return service
        }
        fun unbindKeepingDeathObserver() {
            check(state == State.CONFIRMED)
            context.unbindService(connection)
            state = State.CLOSED
        }
        override fun close() {
            val previous = state
            try {
                if (linked) { binder?.unlinkToDeath(death, 0); linked = false }
            } finally {
                try { if (previous != State.CLOSED && previous != State.NEW) context.unbindService(connection) }
                catch (failure: IllegalArgumentException) { if (previous == State.CONFIRMED) throw failure }
                finally { state = State.CLOSED }
            }
        }
    }
    private sealed interface Reply {
        data class Words(val text: String?) : Reply
        data class Failure(val code: Int?) : Reply
    }
    private class Callback : IAsrCallback.Stub() {
        val done = CountDownLatch(1)
        val count = AtomicInteger()
        val reply = AtomicReference<Reply?>()
        private fun finish(value: Reply) { count.incrementAndGet(); if (reply.compareAndSet(null, value)) done.countDown() }
        override fun onResult(text: String?) = finish(Reply.Words(text))
        override fun onError(message: String?) = finish(Reply.Failure(null))
        override fun onFailure(reason: Int, detail: String?) = finish(Reply.Failure(reason))
        fun words(clip: AsrQualificationInputs.Clip, step: AsrQualificationBudget.Step): String {
            step.await(done, "complete speech callback")
            val text = (reply.get() as? Reply.Words)?.text
            check(!text.isNullOrBlank() && count.get() == 1) { "Speech did not return one nonblank successful callback" }
            clip.requireWords(text)
            return text
        }
    }
    private val events = AsrProbeEvents(context)
    private var held: Binding? = null
    private var helper: Binding? = null
    private var service: IAsrService? = null
    private var generation = 0L
    private var pid = 0L
    private enum class State { NEW, ACTIVE, IDLE, RETIRED, INCOMPLETE }
    private var state = State.NEW
    private sealed interface NativeOwner {
        data object NeverBound : NativeOwner
        data object Unconfirmed : NativeOwner
        data class Known(val generation: Long, val pid: Long) : NativeOwner
    }
    private var nativeOwner: NativeOwner = NativeOwner.NeverBound
    val retirementConfirmed: Boolean get() = state == State.RETIRED

    private fun number(data: JSONObject, name: String) = AsrProbeEvents.number(data, name)
    private fun registered(take: String, step: AsrQualificationBudget.Step, different: Long? = null): JSONObject =
        events.await("registered", step.left()) { it.getString("take") == take && (different == null || number(it, "request") != different) }
    private fun retired(request: Long, step: AsrQualificationBudget.Step): JSONObject =
        events.await("retired", step.left()) { number(it, "request") == request && number(it, "pid") == pid }
    private fun requireClean(data: JSONObject, cancelled: Boolean) {
        check(data.get("cancelled") == cancelled && data.getString("outcome") == AsrRequest.Retirement.BODY_COMPLETED.name) { "Server did not prove the expected whole-request retirement" }
        check(number(data, "generation") == generation && number(data, "pid") == pid)
    }

    private fun openAsr(step: AsrQualificationBudget.Step) {
        step.left()
        // Invalidate the previous owner's proof BEFORE a bind can create another owner. A failed bind
        // or first registration cannot borrow a historical retirement event from a prior generation.
        nativeOwner = NativeOwner.Unconfirmed
        service = null
        held = Binding(context, AsrService::class.java)
        service = IAsrService.Stub.asInterface(checkNotNull(held).open(step))
    }

    private fun identifyOwner(entry: JSONObject) {
        generation = number(entry, "generation")
        pid = number(entry, "pid")
        check(generation > 0 && pid > 0) { "ASR owner identity was not positive" }
        nativeOwner = NativeOwner.Known(generation, pid)
    }

    fun readyAndExactSiblingCancellation(step: AsrQualificationBudget.Step) {
        events.register(step.left())
        openAsr(step)
        val asr = checkNotNull(service)
        val take = UUID.randomUUID().toString()
        val callback = Callback()
        state = State.ACTIVE
        worker.call(step) { asr.transcribeFileForTake(inputs.short.file.path, take, callback) }
        val registration = registered(take, step)
        identifyOwner(registration)
        val words = callback.words(inputs.short, step)
        requireClean(retired(number(registration, "request"), step), cancelled = false)
        state = State.IDLE
        report.data.put("ipc_short", words).put("ipc_pid", pid).put("ipc_generation", generation)

        val sharedTake = UUID.randomUUID().toString()
        val old = Callback()
        val replacement = Callback()
        state = State.ACTIVE
        worker.call(step) { asr.transcribeFileForTake(inputs.long.file.path, sharedTake, old) }
        val oldId = number(registered(sharedTake, step), "request")
        events.await("encoder", step.left()) { number(it, "request") == oldId && number(it, "pid") == pid }
        worker.call(step) { asr.transcribeFileForTake(inputs.short.file.path, sharedTake, replacement) }
        val nextId = number(registered(sharedTake, step, oldId), "request")
        val cancelledAt = System.nanoTime()
        worker.call(step) { asr.cancelTranscription(sharedTake, old) }
        val oldRetired = retired(oldId, step)
        requireClean(oldRetired, cancelled = true)
        check(old.count.get() == 0) { "Cancelled old request delivered words/failure" }
        check(number(oldRetired, "nanos") - cancelledAt in 0..1_000_000_000L) { "Healthy IPC cancellation exceeded the one-second retirement policy" }
        val nextRetired = retired(nextId, step)
        requireClean(nextRetired, cancelled = false)
        report.data.put("ipc_replacement_short", replacement.words(inputs.short, step))
        check(checkNotNull(held?.binder).isBinderAlive) { "Healthy cancellation killed the retained service" }
        state = State.IDLE

        // The other identity axis: an existing client may reuse a callback across different take ids.
        val firstTake = UUID.randomUUID().toString()
        val secondTake = UUID.randomUUID().toString()
        val sharedCallback = Callback()
        state = State.ACTIVE
        worker.call(step) { asr.transcribeFileForTake(inputs.long.file.path, firstTake, sharedCallback) }
        val firstId = number(registered(firstTake, step), "request")
        events.await("encoder", step.left()) { number(it, "request") == firstId && number(it, "pid") == pid }
        worker.call(step) { asr.transcribeFileForTake(inputs.short.file.path, secondTake, sharedCallback) }
        val secondId = number(registered(secondTake, step), "request")
        worker.call(step) { asr.cancelTranscription(secondTake, null); asr.cancelTranscription(UUID.randomUUID().toString(), sharedCallback) }
        worker.call(step) { asr.cancelTranscription(firstTake, sharedCallback) }
        requireClean(retired(firstId, step), cancelled = true)
        worker.call(step) { asr.cancelTranscription(firstTake, sharedCallback) } // Stale retired key cannot touch the reused callback's new take.
        requireClean(retired(secondId, step), cancelled = false)
        report.data.put("different_take_same_callback_short", sharedCallback.words(inputs.short, step))
        state = State.IDLE
        report.save()
    }

    fun clientDeathAndRetainedServiceReuse(step: AsrQualificationBudget.Step) {
        val asr = checkNotNull(service)
        helper = Binding(context, AsrCancellationClientProbe::class.java)
        val probe = IAsrCancellationProbe.Stub.asInterface(checkNotNull(helper).open(step))
        val helperPid = probe.pid()
        val take = UUID.randomUUID().toString()
        state = State.ACTIVE
        worker.call(step) { probe.start(inputs.long.file.path, take) }
        val oldId = number(registered(take, step), "request")
        events.await("active", step.left()) { number(it, "request") == oldId && number(it, "pid") == pid }
        val helperDied = checkNotNull(helper).died
        checkNotNull(helper).unbindKeepingDeathObserver() // Remove AUTO_CREATE before kill without dropping its actual death signal.
        val cancellationStart = System.nanoTime()
        val command = File("/proc/$helperPid/cmdline").readBytes().toString(Charsets.UTF_8).trimEnd('\u0000')
        check(command == context.packageName + ":asr_client_probe") { "Refusing to kill an unidentified process" }
        Process.killProcess(helperPid) // Same call verified this exact same-UID helper PID/command.
        step.await(helperDied, "helper Binder death")
        events.await("callback_died", step.left()) { number(it, "request") == oldId && number(it, "pid") == pid }
        val oldRetired = retired(oldId, step)
        requireClean(oldRetired, cancelled = true)
        check(number(oldRetired, "nanos") - cancellationStart in 0..1_000_000_000L) { "Callback-death job did not retire within the declared healthy bound" }
        check(checkNotNull(held?.binder).isBinderAlive && !events.seen("owner_retired") { number(it, "generation") == generation })
        val nextTake = UUID.randomUUID().toString()
        val next = Callback()
        worker.call(step) { asr.transcribeFileForTake(inputs.short.file.path, nextTake, next) }
        val nextId = number(registered(nextTake, step), "request")
        requireClean(retired(nextId, step), cancelled = false)
        report.data.put("after_client_death_short", next.words(inputs.short, step)).put("helper_pid", helperPid)
        state = State.IDLE
        report.save()
    }

    fun orderlyServiceTurnover(step: AsrQualificationBudget.Step) {
        val oldGeneration = generation
        val oldPid = pid
        checkNotNull(held).close()
        val oldRetired = events.await("owner_retired", step.left()) { number(it, "generation") == oldGeneration && number(it, "pid") == pid }
        openAsr(step)
        val take = UUID.randomUUID().toString()
        val next = Callback()
        state = State.ACTIVE
        worker.call(step) { checkNotNull(service).transcribeFileForTake(inputs.short.file.path, take, next) }
        val entry = registered(take, step)
        val nextGeneration = number(entry, "generation")
        identifyOwner(entry) // Cleanup owns this new identity even if a later qualification assertion fails.
        check(nextGeneration != oldGeneration && pid == oldPid) { "Same-process generation turnover was not staged" }
        val opening = events.await("model_opening", step.left()) { number(it, "generation") == nextGeneration && number(it, "pid") == pid }
        check(number(opening, "sequence") > number(oldRetired, "sequence")) { "The new model opened before actual prior retirement" }
        requireClean(retired(number(entry, "request"), step), cancelled = false)
        report.data.put("after_turnover_short", next.words(inputs.short, step)).put("turnover_new_generation", generation)
        state = State.IDLE
        report.save()
    }

    fun close(step: AsrQualificationBudget.Step) {
        var failure: Throwable? = null
        var nativeRetired = nativeOwner == NativeOwner.NeverBound
        fun attempt(action: () -> Unit) { try { action() } catch (error: Throwable) { val first = failure; if (first == null) failure = error else first.addSuppressed(error) } }
        attempt { helper?.close() }
        attempt { held?.close() }
        when (val owner = nativeOwner) {
            NativeOwner.NeverBound -> Unit
            NativeOwner.Unconfirmed -> attempt { error("An attempted ASR binding has no confirmed owner retirement; inputs/permit must be retained") }
            is NativeOwner.Known -> attempt {
                events.await("owner_retired", step.left()) { number(it, "generation") == owner.generation && number(it, "pid") == owner.pid }
                nativeRetired = true
            }
        }
        attempt { report.data.put("ipc_events", events.snapshot()) }
        attempt { events.close(step.left()) }
        state = if (nativeRetired) State.RETIRED else State.INCOMPLETE
        failure?.let { throw it }
    }
}
