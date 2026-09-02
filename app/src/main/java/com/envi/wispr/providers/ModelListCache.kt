package com.envi.wispr.providers

import android.content.Context
import android.content.SharedPreferences
import com.envi.wispr.debug.DebugLogger
import org.json.JSONArray
import org.json.JSONObject

/**
 * The last discovered list per provider (#84), a UI convenience with no policy meaning: it is written only
 * for the SAVED credential (the view model decides when), holds model rows and a time, never a key or any
 * derivative of one, and is never consulted at dictation time.
 */
class ModelListCache internal constructor(private val preferences: SharedPreferences) {
    constructor(context: Context) : this(
        context.applicationContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE),
    )

    data class Entry(val fetchedAt: Long, val models: List<DiscoveredModel>)

    fun read(provider: Provider): Entry? {
        val raw = preferences.getString(provider.name, null) ?: return null
        return runCatching {
            val root = JSONObject(raw)
            val rows = root.getJSONArray("models")
            Entry(
                fetchedAt = root.getLong("fetchedAt"),
                models = (0 until rows.length()).map { index ->
                    val row = rows.getJSONObject(index)
                    DiscoveredModel(
                        id = row.getString("id"),
                        displayName = row.getString("displayName"),
                        access = ModelAccess.valueOf(row.getString("access")),
                        recommended = row.getBoolean("recommended"),
                    )
                },
            )
        }.getOrNull()
    }

    /** @return false when the commit failed; the caller still shows the fresh list. */
    fun write(provider: Provider, entry: Entry): Boolean {
        val rows = JSONArray()
        entry.models.forEach { model ->
            rows.put(
                JSONObject()
                    .put("id", model.id)
                    .put("displayName", model.displayName)
                    .put("access", model.access.name)
                    .put("recommended", model.recommended),
            )
        }
        val root = JSONObject().put("fetchedAt", entry.fetchedAt).put("models", rows)
        val ok = preferences.edit().putString(provider.name, root.toString()).commit()
        if (!ok) DebugLogger.warn(TAG, "Model list cache commit failed for $provider")
        return ok
    }

    fun clear(provider: Provider) {
        preferences.edit().remove(provider.name).commit()
    }

    companion object {
        const val PREFERENCES = "envious_wispr_model_cache"
        private const val TAG = "ModelListCache"
    }
}
