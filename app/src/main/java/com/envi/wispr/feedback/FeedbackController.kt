package com.envi.wispr.feedback

import android.content.Context
import com.envi.wispr.BuildConfig
import com.envi.wispr.telemetry.Telemetry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.UUID

internal enum class FeedbackPhase { EDITING, SAVING, SAVED }
internal data class FeedbackFormState(
    val draft: FeedbackDraft = FeedbackDraft(), val loaded: Boolean = false,
    val phase: FeedbackPhase = FeedbackPhase.EDITING, val includeDiagnostics: Boolean = false,
    val diagnostics: String? = null, val snapshotLoading: Boolean = false,
    val problem: String? = null, val presentation: Long = 0, val offline: Boolean = false,
)

/** One process-owned feedback draft/submission, matching Mac FeedbackSubmission.shared. Contains no window references. */
internal class FeedbackController private constructor(context: Context) {
    companion object {
        @Volatile private var instance: FeedbackController? = null
        fun of(context: Context): FeedbackController = instance ?: synchronized(this) {
            instance ?: FeedbackController(context.applicationContext).also { instance = it }
        }
    }
    private val app = context.applicationContext
    private val store = FeedbackDelivery.store(app)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val writes = Mutex()
    private val mutable = MutableStateFlow(FeedbackFormState())
    val state: StateFlow<FeedbackFormState> = mutable
    private var generation = 0L
    init {
        scope.launch {
            val doc = withContext(Dispatchers.IO) { runCatching { store.read() }.getOrNull() }
            mutable.value = if (doc == null) mutable.value.copy(problem = "Feedback isn't available right now. Your message will stay here.")
            else mutable.value.copy(draft = doc.draft.copy(revision = maxOf(doc.draft.revision, doc.admittedRevision) + 1), loaded = true)
        }
    }
    fun open(restoredConsent: Boolean? = null): Long {
        generation++
        val token = generation
        mutable.value = mutable.value.copy(presentation = token,
            phase = if (mutable.value.phase == FeedbackPhase.SAVING) FeedbackPhase.SAVING else FeedbackPhase.EDITING,
            problem = if (mutable.value.loaded) null else mutable.value.problem,
            includeDiagnostics = restoredConsent ?: Telemetry.status().postHog, diagnostics = null, snapshotLoading = true)
        scope.launch {
            val snapshot = withContext(Dispatchers.IO) { runCatching { FeedbackDiagnostics.snapshot(app, FeedbackDiagnostics.context()) }.getOrNull() }
            val warning = withContext(Dispatchers.IO) {
                runCatching { val records = store.read().records; records.any { it.state != FeedbackRecordState.PENDING } ||
                    (records.isNotEmpty() && FeedbackDestination.parse(BuildConfig.TELEMETRY_SENTRY_DSN) == null) }.getOrDefault(true)
            }
            if (generation == token) mutable.value = mutable.value.copy(diagnostics = snapshot, snapshotLoading = false,
                includeDiagnostics = mutable.value.includeDiagnostics && snapshot != null,
                problem = if (warning) "A saved report couldn't be delivered. It is still on this phone." else mutable.value.problem)
        }
        return token
    }
    fun close(token: Long) { if (generation == token) generation++ }
    fun includeDiagnostics(token: Long, include: Boolean) { if (generation == token) mutable.value = mutable.value.copy(includeDiagnostics = include) }
    fun edit(token: Long, message: String, email: String) {
        if (generation != token || !mutable.value.loaded) return
        val draft = FeedbackDraft(mutable.value.draft.revision + 1, message, email)
        mutable.value = mutable.value.copy(draft = draft, problem = null)
        scope.launch {
            writes.withLock {
                val saved = withContext(Dispatchers.IO) { runCatching { store.saveDraft(draft) }.isSuccess }
                if (!saved) mutable.value = mutable.value.copy(problem = "Your draft couldn't be saved. Your message is still here.")
            }
        }
    }
    fun send(token: Long) {
        if (generation != token) return
        val form = mutable.value
        if (!form.loaded || form.phase == FeedbackPhase.SAVING || FeedbackValidation.issue(form.draft, ::feedbackGraphemes) != null ||
            (form.includeDiagnostics && form.snapshotLoading)) return
        if (FeedbackDestination.parse(BuildConfig.TELEMETRY_SENTRY_DSN) == null) {
            mutable.value = form.copy(problem = "Feedback isn't available right now. Your message will stay here.")
            return
        }
        val telemetry = Telemetry.status()
        val id = if (telemetry.postHog || form.includeDiagnostics) FeedbackValidation.canonicalId(telemetry.installId) else null
        val report = FeedbackRecord(UUID.randomUUID().toString().replace("-", ""), System.currentTimeMillis(),
            form.draft.message.trim(), form.draft.email.trim().ifEmpty { null },
            if (form.includeDiagnostics) form.diagnostics else null, id, FeedbackDiagnostics.context())
        mutable.value = form.copy(phase = FeedbackPhase.SAVING, problem = null)
        scope.launch {
            val result = writes.withLock { withContext(Dispatchers.IO) { runCatching { store.admit(form.draft, report) }.getOrNull() } }
            if (result == FeedbackStore.Admission.SAVED || result == FeedbackStore.Admission.ALREADY_SAVED) {
                // Queueing is a limb of a DURABLY saved report. Startup recovery repairs a scheduling failure.
                withContext(Dispatchers.IO) { runCatching { FeedbackDelivery.schedule(app, if (result == FeedbackStore.Admission.ALREADY_SAVED) store.read().admittedId!! else report.id) } }
                val latest = mutable.value
                val draft = if (latest.draft.revision == form.draft.revision) FeedbackDraft(form.draft.revision + 1) else latest.draft
                val online = runCatching { app.getSystemService(android.net.ConnectivityManager::class.java).activeNetwork != null }.getOrDefault(true)
                mutable.value = latest.copy(draft = draft, phase = if (generation == form.presentation) FeedbackPhase.SAVED else FeedbackPhase.EDITING,
                    offline = !online)
            } else {
                val problem = when (result) {
                    FeedbackStore.Admission.FULL -> "Too many reports are waiting to send. Your message will stay here."
                    FeedbackStore.Admission.STALE -> "Your message changed. Please review it before sending."
                    null -> "Your report couldn't be saved. Your message will stay here."
                    FeedbackStore.Admission.SAVED, FeedbackStore.Admission.ALREADY_SAVED -> error("Handled admission")
                }
                mutable.value = mutable.value.copy(phase = FeedbackPhase.EDITING, problem = problem)
            }
        }
    }
}
