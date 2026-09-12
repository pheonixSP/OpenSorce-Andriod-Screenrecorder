package com.cleanrecorder.app

import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

class QuickTileService : TileService() {

    private var scope: CoroutineScope? = null
    private var collectJob: Job? = null

    override fun onStartListening() {
        super.onStartListening()
        val newScope = CoroutineScope(Dispatchers.Main.immediate)
        scope = newScope
        collectJob = newScope.launch {
            RecordingState.status.collect { status ->
                renderTile(status)
            }
        }
    }

    override fun onStopListening() {
        collectJob?.cancel()
        collectJob = null
        scope = null
        super.onStopListening()
    }

    override fun onClick() {
        super.onClick()
        val isRecording = RecordingState.status.value.isRecording
        if (isRecording) {
            val stopIntent = Intent(this, RecordService::class.java).apply {
                action = RecordService.ACTION_STOP
            }
            startService(stopIntent)
        } else {
            // Starting requires the MediaProjection consent dialog, which only an Activity
            // can present. Route through the transparent trampoline and collapse the shade.
            val trampoline = Intent(this, ProjectionRequestActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            if (Build.VERSION.SDK_INT >= 34) {
                val pendingIntent = android.app.PendingIntent.getActivity(
                    this,
                    0,
                    trampoline,
                    android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
                )
                startActivityAndCollapse(pendingIntent)
            } else {
                @Suppress("DEPRECATION")
                startActivityAndCollapse(trampoline)
            }
        }
    }

    private fun renderTile(status: RecordingStatus) {
        val tile = qsTile ?: return
        tile.state = if (status.isRecording) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.label = getString(R.string.tile_label)
        if (Build.VERSION.SDK_INT >= 30) {
            tile.subtitle = if (status.isRecording) {
                val m = status.elapsedSeconds / 60
                val s = status.elapsedSeconds % 60
                "%02d:%02d".format(m, s)
            } else {
                "Tap to start"
            }
        }
        tile.updateTile()
    }
}
