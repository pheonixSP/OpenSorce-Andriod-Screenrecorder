package com.cleanrecorder.app

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle

class MainActivity : ComponentActivity() {

    private lateinit var projectionManager: MediaProjectionManager
    private var pendingAudioModeForLaunch: AudioMode = AudioMode.SYSTEM_ONLY

    private val projectionLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            ProjectionRequestActivity.persistAudioMode(this, pendingAudioModeForLaunch)
            val serviceIntent = Intent(this, RecordService::class.java).apply {
                action = RecordService.ACTION_START
                putExtra(RecordService.EXTRA_RESULT_CODE, result.resultCode)
                putExtra(RecordService.EXTRA_RESULT_DATA, result.data)
                putExtra(RecordService.EXTRA_AUDIO_MODE, pendingAudioModeForLaunch.ordinal)
            }
            ContextCompat.startForegroundService(this, serviceIntent)
        }
    }

    private val micPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) launchProjectionRequest(pendingAudioModeForLaunch)
    }

    private val notifPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* no-op */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        projectionManager = getSystemService(MediaProjectionManager::class.java)

        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notifPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        setContent {
            MaterialTheme(colorScheme = darkColorScheme(primary = Color(0xFFFF3B30))) {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    RecorderScreen(
                        onStartRequested = ::onStartRequested,
                        onStopRequested = ::onStopRequested
                    )
                }
            }
        }
    }

    private fun onStartRequested(mode: AudioMode) {
        pendingAudioModeForLaunch = mode
        val needsMic = mode == AudioMode.MIC_ONLY || mode == AudioMode.SYSTEM_AND_MIC
        if (needsMic && ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        } else {
            launchProjectionRequest(mode)
        }
    }

    private fun launchProjectionRequest(mode: AudioMode) {
        pendingAudioModeForLaunch = mode
        projectionLauncher.launch(projectionManager.createScreenCaptureIntent())
    }

    private fun onStopRequested() {
        val stopIntent = Intent(this, RecordService::class.java).apply {
            action = RecordService.ACTION_STOP
        }
        startService(stopIntent)
    }
}

@Composable
fun RecorderScreen(
    onStartRequested: (AudioMode) -> Unit,
    onStopRequested: () -> Unit
) {
    val status by RecordingState.status.collectAsStateWithLifecycle()
    var selectedMode by remember { mutableStateOf(AudioMode.SYSTEM_ONLY) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        Spacer(Modifier.height(24.dp))

        Text(
            text = "CleanRecorder",
            fontSize = 28.sp,
            fontWeight = FontWeight.Bold
        )
        Text(
            text = "1080p60 • Hardware encoder • No overlays",
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f)
        )

        Spacer(Modifier.height(12.dp))

        StatusCard(status)

        Spacer(Modifier.height(8.dp))

        Text(
            text = "Audio source",
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.align(Alignment.Start)
        )

        AudioModeSelector(
            selected = selectedMode,
            enabled = !status.isRecording,
            onSelected = { selectedMode = it }
        )

        Spacer(Modifier.weight(1f))

        RecordButton(
            isRecording = status.isRecording,
            onClick = {
                if (status.isRecording) onStopRequested() else onStartRequested(selectedMode)
            }
        )

        Spacer(Modifier.height(12.dp))
    }
}

@Composable
private fun StatusCard(status: RecordingStatus) {
    val bg = if (status.isRecording) Color(0xFF3A1414) else MaterialTheme.colorScheme.surfaceVariant
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = bg),
        shape = RoundedCornerShape(20.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column {
                Text(
                    text = if (status.isRecording) "Recording" else "Idle",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (status.isRecording) Color(0xFFFF6B60) else MaterialTheme.colorScheme.onSurface
                )
                if (status.isRecording) {
                    val m = status.elapsedSeconds / 60
                    val s = status.elapsedSeconds % 60
                    Text(
                        text = "%02d:%02d".format(m, s),
                        fontSize = 30.sp,
                        fontWeight = FontWeight.Bold
                    )
                } else {
                    Text(
                        text = "Tap record to begin",
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                    )
                }
            }
            if (status.isRecording) {
                PulsingDot()
            }
        }
    }

    status.lastSavedUri?.let {
        Spacer(Modifier.height(8.dp))
        Text(
            text = "Saved to DCIM/CleanRecorder",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f)
        )
    }
}

@Composable
private fun PulsingDot() {
    val infinite = rememberInfiniteTransition(label = "pulse")
    val alpha by infinite.animateFloat(
        initialValue = 0.3f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(700),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulseAlpha"
    )
    Box(
        modifier = Modifier
            .size(16.dp)
            .clip(CircleShape)
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = Color(0xFFFF3B30).copy(alpha = alpha),
            shape = CircleShape
        ) {}
    }
}

@Composable
private fun AudioModeSelector(
    selected: AudioMode,
    enabled: Boolean,
    onSelected: (AudioMode) -> Unit
) {
    val options = listOf(
        AudioMode.NONE to "None",
        AudioMode.SYSTEM_ONLY to "System",
        AudioMode.MIC_ONLY to "Mic",
        AudioMode.SYSTEM_AND_MIC to "System + Mic"
    )
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        options.forEach { (mode, label) ->
            val isSelected = mode == selected
            FilterChip(
                selected = isSelected,
                enabled = enabled,
                onClick = { onSelected(mode) },
                label = { Text(label, fontSize = 12.sp) }
            )
        }
    }
}

@Composable
private fun RecordButton(isRecording: Boolean, onClick: () -> Unit) {
    val bg = if (isRecording) Color(0xFF2A2A2A) else Color(0xFFFF3B30)
    Surface(
        modifier = Modifier
            .size(84.dp)
            .clip(CircleShape),
        color = bg,
        onClick = onClick
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                imageVector = if (isRecording) Icons.Filled.Stop else Icons.Filled.FiberManualRecord,
                contentDescription = if (isRecording) "Stop recording" else "Start recording",
                tint = Color.White,
                modifier = Modifier.size(36.dp)
            )
        }
    }
}
