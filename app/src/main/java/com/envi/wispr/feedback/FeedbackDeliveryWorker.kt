package com.envi.wispr.feedback

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.envi.wispr.BuildConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit

/** Only WorkManager constructs this by class name; correspondence runs in the default process. */
class FeedbackDeliveryWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val id = inputData.getString("feedback_id")?.takeIf { it.matches(Regex("[0-9a-f]{32}")) }
            ?: return@withContext Result.failure()
        FeedbackDelivery.mutex.withLock {
            val store = FeedbackDelivery.store(applicationContext)
            // A prior response whose small disk transaction failed restricts every later send first.
            if (!FeedbackDelivery.retrySettlement(store)) return@withLock Result.retry()
            val doc = runCatching { store.read() }.getOrNull() ?: return@withLock Result.retry()
            val record = doc.records.firstOrNull { it.id == id } ?: return@withLock Result.success()
            if (record.state == FeedbackRecordState.REJECTED || doc.pausedLaunch == FeedbackDelivery.launch) return@withLock Result.success()
            if (doc.holdUntilMs > System.currentTimeMillis()) return@withLock Result.retry()
            val destination = FeedbackDestination.parse(BuildConfig.TELEMETRY_SENTRY_DSN) ?: return@withLock Result.success()
            val settled = FeedbackSender(destination).send(record) { reply ->
                FeedbackDelivery.responseReceived(store, id, reply)
            }
            if (!settled) return@withLock Result.retry()
            val remaining = runCatching { store.read().records.firstOrNull { it.id == id } }.getOrNull()
            if (remaining?.state == FeedbackRecordState.PENDING) Result.retry() else Result.success()
        }
    }
}

/** WorkManager owns constraints/backoff; this gate only serializes our global server restrictions. */
internal object FeedbackDelivery {
    val mutex = Mutex()
    val launch: String = UUID.randomUUID().toString()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private data class Settlement(val id: String, val reply: FeedbackReply)
    // Only touched while mutex is held, including the terminal network callback it awaits.
    private var unsettled: Settlement? = null
    fun store(context: Context): FeedbackStore = FeedbackStore(File(context.noBackupFilesDir, "feedback/state.json"))
    fun responseReceived(store: FeedbackStore, id: String, reply: FeedbackReply) {
        unsettled = Settlement(id, reply) // Install restriction BEFORE potentially failing/cancelled IO.
        store.settle(id, reply, launch)
        unsettled = null
    }
    fun retrySettlement(store: FeedbackStore): Boolean {
        val pending = unsettled ?: return true
        return runCatching { store.settle(pending.id, pending.reply, launch); unsettled = null }.isSuccess
    }
    fun schedule(context: Context, id: String) {
        val request = OneTimeWorkRequestBuilder<FeedbackDeliveryWorker>()
            .setInputData(workDataOf("feedback_id" to id))
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS).build()
        WorkManager.getInstance(context).enqueueUniqueWork("feedback-$id", ExistingWorkPolicy.KEEP, request)
    }
    fun recover(context: Context) {
        val app = context.applicationContext
        scope.launch {
            runCatching {
                mutex.withLock {
                    val doc = store(app).recover(launch)
                    doc.records.filter { it.state != FeedbackRecordState.REJECTED }.forEach { schedule(app, it.id) }
                }
            }
            // Storage/configuration problems are shown by the feedback form, never logged with correspondence.
        }
    }
}
