package com.envi.wispr.feedback

import android.content.Context
import android.icu.text.BreakIterator
import android.os.Build
import com.envi.wispr.BuildConfig
import com.envi.wispr.history.EnviousWisprDatabase
import com.envi.wispr.telemetry.Telemetry
import com.envi.wispr.telemetry.TakeStage
import com.envi.wispr.ui.TerminalResult
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

internal fun feedbackGraphemes(text: String): Int {
    val iterator = BreakIterator.getCharacterInstance(Locale.ROOT)
    iterator.setText(text)
    var count = 0
    iterator.first()
    while (iterator.next() != BreakIterator.DONE) {
        count++
        if (count > 4000) break
    }
    return count
}

/** Closed projection of metadata only. Never reads History text, clipboard, provider keys or local logs. IO caller. */
internal object FeedbackDiagnostics {
    fun context(): FeedbackContext = FeedbackContext(BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE.toString(),
        if (BuildConfig.DEBUG) "development" else "production", Build.VERSION.RELEASE, Build.ID, Build.MODEL)
    fun snapshot(context: Context, metadata: FeedbackContext): String {
        val json = JSONObject().put("schema", 1).put("app", "enviouswispr-android").put("platform", "android")
            .put("context", metadata.json()).put("api_level", Build.VERSION.SDK_INT)
        FeedbackValidation.canonicalId(Telemetry.status().installId)?.let { json.put("analytics.distinct_id", it) }
        val takes = JSONArray()
        runCatching {
            EnviousWisprDatabase.get(context).query(
                "SELECT take_id, stage, terminal_result, admitted_at_ms, terminal_at_ms FROM take_journal ORDER BY admitted_at_ms DESC LIMIT 10", null,
            ).use { cursor ->
                while (cursor.moveToNext()) {
                    val id = FeedbackValidation.canonicalId(cursor.getString(0)) ?: continue
                    val stage = TakeStage.entries.firstOrNull { it.name == cursor.getString(1) } ?: continue
                    val row = JSONObject().put("take_id", id).put("stage", stage.name).put("admitted_at_ms", cursor.getLong(3))
                    val result = TerminalResult.entries.firstOrNull { it.wire == cursor.getString(2) }
                    result?.let { row.put("result", it.wire) }
                    if (!cursor.isNull(4)) row.put("terminal_at_ms", cursor.getLong(4))
                    takes.put(row)
                }
            }
        }
        json.put("recent_dictations", takes)
        return json.toString(2)
    }
}
