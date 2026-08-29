package com.envi.wispr.shortcuts

import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.envi.wispr.ui.DictationSessionService
import com.envi.wispr.ui.VoiceInputActivity

class DictationTileService : TileService() {
    override fun onStartListening() {
        super.onStartListening()
        val phase = DictationSurfaceState.read(this)
        qsTile?.apply {
            state = if (phase == DictationSurfaceState.Phase.IDLE) {
                Tile.STATE_INACTIVE
            } else {
                Tile.STATE_ACTIVE
            }
            label = when (phase) {
                DictationSurfaceState.Phase.IDLE -> "EnviousWispr"
                DictationSurfaceState.Phase.LISTENING -> "Listening"
                DictationSurfaceState.Phase.PROCESSING -> "Processing"
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                subtitle = when (phase) {
                    DictationSurfaceState.Phase.IDLE -> "Tap to dictate"
                    DictationSurfaceState.Phase.LISTENING -> "Tap to stop"
                    DictationSurfaceState.Phase.PROCESSING -> "Working locally"
                }
            }
            updateTile()
        }
    }

    override fun onClick() {
        super.onClick()
        val phase = DictationSurfaceState.read(this)
        if (phase == DictationSurfaceState.Phase.PROCESSING) return

        if (phase != DictationSurfaceState.Phase.IDLE) {
            DictationSessionService.sendCommand(this, DictationSessionService.ACTION_TOGGLE)
            return
        }

        val launcher = Intent(this, VoiceInputActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(VoiceInputActivity.EXTRA_TOGGLE, true)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startActivityAndCollapse(
                PendingIntent.getActivity(
                    this,
                    20,
                    launcher,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                ),
            )
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(launcher)
        }
    }
}
