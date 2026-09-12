package com.cleanrecorder.app

import android.net.Uri
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class AudioMode {
    NONE,
    SYSTEM_ONLY,
    MIC_ONLY,
    SYSTEM_AND_MIC
}

data class RecordingStatus(
    val isRecording: Boolean = false,
    val elapsedSeconds: Int = 0,
    val audioMode: AudioMode = AudioMode.SYSTEM_ONLY,
    val appliedVideoConfig: AppliedVideoConfig? = null,
    val lastError: String? = null,
    val lastSavedUri: Uri? = null
)

/**
 * Process-wide source of truth for recording state. RecordService is the sole writer;
 * MainActivity (Compose UI), NotificationHelper (live chronometer pill), and
 * QuickTileService (QS tile subtitle/state) are readers.
 */
object RecordingState {
    private val _status = MutableStateFlow(RecordingStatus())
    val status: StateFlow<RecordingStatus> = _status.asStateFlow()

    fun update(transform: (RecordingStatus) -> RecordingStatus) {
        _status.value = transform(_status.value)
    }

    fun reset() {
        _status.value = RecordingStatus()
    }
}
