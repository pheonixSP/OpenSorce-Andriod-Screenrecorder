package com.cleanrecorder.app

import android.content.Context
import android.content.SharedPreferences

/** Centralized persistence for the last-chosen recording configuration, read by both the
 * in-app UI and the Quick Settings tile trampoline so a tile-triggered recording uses
 * whatever the user last configured in MainActivity. */
object Prefs {
    private const val FILE = "clean_recorder_prefs"
    private const val KEY_AUDIO_MODE = "audio_mode"
    private const val KEY_LONG_EDGE = "long_edge"
    private const val KEY_FPS = "fps"
    private const val KEY_BITRATE_BPS = "bitrate_bps"

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun persist(context: Context, config: RecordingConfig) {
        prefs(context).edit()
            .putInt(KEY_AUDIO_MODE, config.audioMode.ordinal)
            .putInt(KEY_LONG_EDGE, config.longEdge)
            .putInt(KEY_FPS, config.fps)
            .putInt(KEY_BITRATE_BPS, config.bitrateBps)
            .apply()
    }

    fun read(context: Context): RecordingConfig {
        val p = prefs(context)
        val audioOrdinal = p.getInt(KEY_AUDIO_MODE, AudioMode.SYSTEM_ONLY.ordinal)
        val audioMode = AudioMode.entries.getOrElse(audioOrdinal) { AudioMode.SYSTEM_ONLY }
        val longEdge = p.getInt(KEY_LONG_EDGE, ResolutionPreset.P1080.longEdge)
        val fps = p.getInt(KEY_FPS, 60)
        val bitrate = p.getInt(KEY_BITRATE_BPS, 14_000_000)
        return RecordingConfig(audioMode, longEdge, fps, bitrate)
    }
}
