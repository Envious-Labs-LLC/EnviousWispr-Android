package com.envi.wispr.feedback

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Response
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.URI
import java.time.Instant
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

internal data class FeedbackDestination(val dsn: String, val endpoint: String, val key: String) {
    companion object {
        const val PROJECT = "4512117176795136"
        const val HOST = "o4511097055477760.ingest.us.sentry.io"
        fun parse(value: String): FeedbackDestination? = runCatching {
            val uri = URI(value)
            val key = uri.rawUserInfo ?: return null
            if (uri.scheme != "https" || uri.host != HOST || uri.path != "/$PROJECT" ||
                uri.port != -1 || uri.query != null || uri.fragment != null || !key.matches(Regex("[0-9a-f]+"))) return null
            FeedbackDestination(value, "https://$HOST/api/$PROJECT/envelope/", key)
        }.getOrNull()
    }
}

internal data class FeedbackReply(val kind: Kind, val holdUntilMs: Long = 0) {
    enum class Kind { ACCEPTED, RETRY, REJECTED, CONFIGURATION }
    companion object {
        fun classify(status: Int, headers: Map<String, String>, now: Long): FeedbackReply {
            val h = headers.mapKeys { it.key.lowercase() }
            val hold = h["x-sentry-rate-limits"].orEmpty().split(',').mapNotNull { quota ->
                val parts = quota.trim().split(':')
                if (parts.size < 2 || parts[1].split(';').none { it in setOf("", "feedback", "user_report_v2", "attachment") }) null
                else deadline(parts[0], now)
            }.maxOrNull() ?: 0
            return when (status) {
                in 200..299 -> FeedbackReply(Kind.ACCEPTED, hold)
                400, 413 -> FeedbackReply(Kind.REJECTED, hold)
                401, 403, 404 -> FeedbackReply(Kind.CONFIGURATION, hold)
                429 -> {
                    val retry = h["retry-after"]?.let { deadline(it, now) ?: runCatching {
                        ZonedDateTime.parse(it, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli()
                    }.getOrNull() } ?: (now + 60_000)
                    FeedbackReply(Kind.RETRY, maxOf(hold, retry, now + 1000))
                }
                else -> FeedbackReply(Kind.RETRY, hold)
            }
        }
        private fun deadline(seconds: String, now: Long): Long? {
            val value = seconds.toDoubleOrNull()?.takeIf { it.isFinite() && it > 0 } ?: return null
            val millis = (value * 1000).coerceAtMost((Long.MAX_VALUE - now).toDouble()).toLong()
            return now + millis
        }
    }
}

/** Standalone envelope, never Sentry.captureFeedback: no SDK scope or automatic attachments. */
internal class FeedbackSender(private val destination: FeedbackDestination, private val client: OkHttpClient = CLIENT) {
    companion object {
        private val CLIENT = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false)
            .retryOnConnectionFailure(false).connectTimeout(15, TimeUnit.SECONDS).readTimeout(15, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS).callTimeout(60, TimeUnit.SECONDS).build()
        const val FILENAME = "enviouswispr-android-diagnostics.json"
        fun envelope(record: FeedbackRecord, destination: FeedbackDestination, sentAtMs: Long): ByteArray {
            val feedback = JSONObject().put("message", record.message).put("source", "custom")
            record.email?.let { feedback.put("contact_email", it) }
            val tags = JSONObject().put("app", "enviouswispr-android").put("product.platform", "android")
            record.usageId?.let { tags.put("analytics.distinct_id", it) }
            val payload = JSONObject().put("event_id", record.id).put("type", "feedback")
                .put("timestamp", record.submittedAtMs / 1000.0).put("platform", "java").put("level", "error")
                .put("release", "com.envi.wispr@${record.context.version}+${record.context.build}")
                .put("environment", record.context.environment).put("tags", tags)
                .put("sdk", JSONObject().put("name", "enviouswispr-android-feedback").put("version", record.context.version)
                    .put("settings", JSONObject().put("infer_ip", "never")))
                .put("contexts", JSONObject().put("feedback", feedback)
                    .put("app", JSONObject().put("app_version", record.context.version).put("app_build", record.context.build))
                    .put("os", JSONObject().put("name", "Android").put("version", record.context.osVersion).put("build", record.context.osBuild)))
            return ByteArrayOutputStream().apply {
                fun line(value: JSONObject) { write(value.toString().toByteArray(Charsets.UTF_8)); write(10) }
                line(JSONObject().put("event_id", record.id).put("sent_at", Instant.ofEpochMilli(sentAtMs).toString()).put("dsn", destination.dsn))
                val bytes = payload.toString().toByteArray(Charsets.UTF_8)
                line(JSONObject().put("type", "feedback").put("length", bytes.size)); write(bytes); write(10)
                record.diagnostics?.let {
                    val attachment = it.toByteArray(Charsets.UTF_8)
                    line(JSONObject().put("type", "attachment").put("length", attachment.size).put("filename", FILENAME).put("content_type", "application/json"))
                    write(attachment); write(10)
                }
            }.toByteArray()
        }
    }
    /** The caller's send mutex stays held through terminal callback/settlement, even on cancellation. */
    suspend fun send(record: FeedbackRecord, settle: (FeedbackReply) -> Unit): Boolean {
        val request = Request.Builder().url(destination.endpoint)
            .header("X-Sentry-Auth", "Sentry sentry_version=7, sentry_key=${destination.key}, sentry_client=enviouswispr-android-feedback/${record.context.version}")
            .post(envelope(record, destination, System.currentTimeMillis()).toRequestBody("application/x-sentry-envelope".toMediaType())).build()
        val call = client.newCall(request)
        val terminal = CompletableDeferred<Unit>()
        try {
            return suspendCancellableCoroutine { continuation ->
                continuation.invokeOnCancellation { call.cancel() }
                val callback = object : Callback {
                    override fun onFailure(call: Call, e: IOException) {
                        terminal.complete(Unit)
                        if (continuation.isActive) continuation.resume(false)
                    }
                    override fun onResponse(call: Call, response: Response) {
                        val settled = runCatching {
                            response.use { settle(FeedbackReply.classify(it.code, it.headers.toMultimap().mapValues { entry -> entry.value.joinToString(",") }, System.currentTimeMillis())) }
                        }.isSuccess
                        terminal.complete(Unit)
                        if (continuation.isActive) continuation.resume(settled)
                    }
                }
                try { call.enqueue(callback) } catch (_: Exception) {
                    terminal.complete(Unit)
                    if (continuation.isActive) continuation.resume(false)
                }
            }
        } finally {
            if (!terminal.isCompleted) call.cancel()
            withContext(NonCancellable) { terminal.await() }
        }
    }
}
