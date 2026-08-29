package com.envi.wispr.shortcuts

import android.content.ComponentName
import android.content.Context
import android.service.quicksettings.TileService

object DictationSurfaceState {
    enum class Phase {
        IDLE,
        LISTENING,
        PROCESSING,
    }

    private const val PREFERENCES = "dictation_surface_state"
    private const val KEY_PHASE = "phase"

    fun read(context: Context): Phase {
        val value = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            .getString(KEY_PHASE, Phase.IDLE.name)
        return runCatching { Phase.valueOf(value.orEmpty()) }.getOrDefault(Phase.IDLE)
    }

    fun update(context: Context, phase: Phase) {
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_PHASE, phase.name)
            .apply()
        TileService.requestListeningState(
            context,
            ComponentName(context, DictationTileService::class.java),
        )
    }
}
