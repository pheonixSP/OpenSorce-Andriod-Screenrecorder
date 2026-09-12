package com.cleanrecorder.app

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat

/**
 * Invisible single-purpose activity that requests MediaProjection consent on behalf of the
 * Quick Settings tile (a TileService cannot register for an ActivityResult itself) and then
 * immediately starts RecordService with the resulting consent token. Finishes itself either way.
 */
class ProjectionRequestActivity : ComponentActivity() {

    private lateinit var projectionManager: MediaProjectionManager

    private val launcher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            val audioMode = readPersistedAudioMode(this)
            val serviceIntent = Intent(this, RecordService::class.java).apply {
                action = RecordService.ACTION_START
                putExtra(RecordService.EXTRA_RESULT_CODE, result.resultCode)
                putExtra(RecordService.EXTRA_RESULT_DATA, result.data)
                putExtra(RecordService.EXTRA_AUDIO_MODE, audioMode.ordinal)
            }
            ContextCompat.startForegroundService(this, serviceIntent)
        }
        finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        projectionManager = getSystemService(MediaProjectionManager::class.java)
        launcher.launch(projectionManager.createScreenCaptureIntent())
    }

    companion object {
        private const val PREFS = "clean_recorder_prefs"
        private const val KEY_AUDIO_MODE = "audio_mode"

        fun persistAudioMode(context: Context, mode: AudioMode) {
            prefs(context).edit().putInt(KEY_AUDIO_MODE, mode.ordinal).apply()
        }

        fun readPersistedAudioMode(context: Context): AudioMode {
            val ordinal = prefs(context).getInt(KEY_AUDIO_MODE, AudioMode.SYSTEM_ONLY.ordinal)
            return AudioMode.entries.getOrElse(ordinal) { AudioMode.SYSTEM_ONLY }
        }

        private fun prefs(context: Context): SharedPreferences =
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    }
}
