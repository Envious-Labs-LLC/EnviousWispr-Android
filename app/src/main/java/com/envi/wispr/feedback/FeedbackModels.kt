package com.envi.wispr.feedback

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

internal data class FeedbackDraft(val revision: Long = 0, val message: String = "", val email: String = "")

/** The Mac's two independent message limits; the production caller supplies Android ICU boundaries. */
internal object FeedbackValidation {
    enum class Issue { EMPTY, TOO_LONG, EMAIL }
    private val emailPattern = Regex("""^[A-Za-z0-9!#$%&'*+/=?^_`{|}~\p{L}\p{M}\p{Nd}-]+(\.[A-Za-z0-9!#$%&'*+/=?^_`{|}~\p{L}\p{M}\p{Nd}-]+)*@([A-Za-z0-9\p{L}\p{Nd}]([A-Za-z0-9\p{L}\p{M}\p{Nd}-]*[A-Za-z0-9\p{L}\p{M}\p{Nd}])?\.)+([A-Za-z\p{L}][A-Za-z\p{L}\p{M}]+|[Xx][Nn]--[A-Za-z0-9-]+[A-Za-z0-9])$""")
    fun issue(draft: FeedbackDraft, graphemes: (String) -> Int): Issue? {
        val message = draft.message.trim()
        if (message.isEmpty()) return Issue.EMPTY
        if (message.codePointCount(0, message.length) > 4096 || graphemes(message) > 4000) return Issue.TOO_LONG
        val email = draft.email.trim()
        if (email.isNotEmpty() && !emailPattern.matches(email)) return Issue.EMAIL
        return null
    }
    fun canonicalId(value: String?): String? = value?.let {
        runCatching { UUID.fromString(it).toString().takeIf { canonical -> canonical == it } }.getOrNull()
    }
}

internal data class FeedbackContext(
    val version: String, val build: String, val environment: String,
    val osVersion: String, val osBuild: String, val deviceModel: String,
) {
    fun json(): JSONObject = JSONObject().put("version", version).put("build", build)
        .put("environment", environment).put("os_version", osVersion).put("os_build", osBuild).put("device_model", deviceModel)
    companion object {
        fun read(j: JSONObject) = FeedbackContext(j.strictString("version"), j.strictString("build"),
            j.strictString("environment"), j.strictString("os_version"), j.strictString("os_build"), j.strictString("device_model"))
    }
}

internal enum class FeedbackRecordState { PENDING, REJECTED, CONFIGURATION }

/** Frozen correspondence. Only explicit Send constructs this, never the telemetry SDK scope. */
internal data class FeedbackRecord(
    val id: String, val submittedAtMs: Long, val message: String, val email: String?,
    val diagnostics: String?, val usageId: String?, val context: FeedbackContext,
    val state: FeedbackRecordState = FeedbackRecordState.PENDING,
) {
    init {
        require(id.matches(Regex("[0-9a-f]{32}")))
        require(message.isNotBlank() && message.codePointCount(0, message.length) <= 4096)
        require(diagnostics == null || diagnostics.toByteArray(Charsets.UTF_8).size <= 64 * 1024)
        require(usageId == null || FeedbackValidation.canonicalId(usageId) != null)
    }
    fun json(): JSONObject = JSONObject().put("id", id).put("submitted_at_ms", submittedAtMs)
        .put("message", message).put("email", email ?: JSONObject.NULL)
        .put("diagnostics", diagnostics ?: JSONObject.NULL).put("usage_id", usageId ?: JSONObject.NULL)
        .put("context", context.json()).put("state", state.name)
    companion object {
        fun read(j: JSONObject) = FeedbackRecord(j.strictString("id"), j.strictLong("submitted_at_ms"),
            j.strictString("message"), j.nullableString("email"), j.nullableString("diagnostics"),
            j.nullableString("usage_id"), FeedbackContext.read(j.getJSONObject("context")),
            FeedbackRecordState.valueOf(j.strictString("state")))
    }
}

internal data class FeedbackDocument(
    val draft: FeedbackDraft = FeedbackDraft(), val admittedRevision: Long = -1, val admittedId: String? = null,
    val records: List<FeedbackRecord> = emptyList(), val holdUntilMs: Long = 0,
    val pausedLaunch: String? = null,
) {
    fun json(): JSONObject = JSONObject().put("schema", 1)
        .put("draft", JSONObject().put("revision", draft.revision).put("message", draft.message).put("email", draft.email))
        .put("admitted_revision", admittedRevision).put("admitted_id", admittedId ?: JSONObject.NULL)
        .put("records", JSONArray().also { list -> records.forEach { list.put(it.json()) } })
        .put("hold_until_ms", holdUntilMs).put("paused_launch", pausedLaunch ?: JSONObject.NULL)
    companion object {
        fun read(j: JSONObject): FeedbackDocument {
            require(j.strictLong("schema") == 1L)
            val draft = j.getJSONObject("draft")
            val list = j.getJSONArray("records")
            require(list.length() <= FeedbackStore.MAX_REPORTS)
            val records = (0 until list.length()).map { FeedbackRecord.read(list.getJSONObject(it)) }
            require(records.map { it.id }.distinct().size == records.size)
            return FeedbackDocument(FeedbackDraft(draft.strictLong("revision"), draft.strictString("message"), draft.strictString("email")),
                j.strictLong("admitted_revision"), j.nullableString("admitted_id"), records,
                j.strictLong("hold_until_ms"), j.nullableString("paused_launch"))
        }
    }
}

internal fun JSONObject.strictString(key: String): String = get(key) as? String ?: error("Invalid feedback field type")
internal fun JSONObject.nullableString(key: String): String? = if (isNull(key)) null else strictString(key)
internal fun JSONObject.strictLong(key: String): Long = when (val value = get(key)) {
    is Int -> value.toLong()
    is Long -> value
    else -> error("Invalid feedback number type")
}
