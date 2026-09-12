package com.cleanrecorder.app

import android.app.Service
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.PowerManager
import android.provider.MediaStore
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
import kotlin.math.roundToInt

class RecordService : Service() {

    companion object {
        private const val TAG = "RecordService"
        const val ACTION_START = "com.cleanrecorder.app.action.START"
        const val ACTION_STOP = "com.cleanrecorder.app.action.STOP"

        const val EXTRA_RESULT_CODE = "extra_result_code"
        const val EXTRA_RESULT_DATA = "extra_result_data"
        const val EXTRA_AUDIO_MODE = "extra_audio_mode"
        const val EXTRA_LONG_EDGE = "extra_long_edge"
        const val EXTRA_FPS = "extra_fps"
        const val EXTRA_BITRATE_BPS = "extra_bitrate_bps"

        const val NOTIF_ID = 4201
    }

    private lateinit var mediaProjectionManager: MediaProjectionManager
    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var videoEncoder: VideoEncoder? = null
    private var audioEngine: AudioEngine? = null
    private var muxerController: MuxerController? = null
    private var outputUri: Uri? = null
    private var outputPfd: ParcelFileDescriptor? = null
    private var wakeLock: PowerManager.WakeLock? = null

    private val mainHandler = Handler(Looper.getMainLooper())
    private var timerRunnable: Runnable? = null
    private var startElapsedMillis: Long = 0L

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            // The system revoked capture (e.g. user stopped it from the system share/cast
            // indicator). Tear down exactly as if ACTION_STOP had been sent.
            Log.i(TAG, "MediaProjection.Callback.onStop – finalizing recording")
            performStop()
        }
    }

    override fun onCreate() {
        super.onCreate()
        mediaProjectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> handleStart(intent)
            ACTION_STOP -> performStop()
            else -> Log.w(TAG, "Unknown or missing action, stopping service")
        }
        return START_NOT_STICKY
    }

    private fun handleStart(intent: Intent) {
        if (mediaProjection != null) {
            Log.w(TAG, "Start requested while already recording; ignoring")
            return
        }

        val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, -1)
        val resultData: Intent? = if (Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(EXTRA_RESULT_DATA)
        }
        val audioModeOrdinal = intent.getIntExtra(EXTRA_AUDIO_MODE, AudioMode.SYSTEM_ONLY.ordinal)
        val audioMode = AudioMode.entries.getOrElse(audioModeOrdinal) { AudioMode.SYSTEM_ONLY }
        val requestedLongEdge = intent.getIntExtra(EXTRA_LONG_EDGE, ResolutionPreset.P1080.longEdge)
        val requestedFps = intent.getIntExtra(EXTRA_FPS, 60)
        val requestedBitrateBps = intent.getIntExtra(EXTRA_BITRATE_BPS, 14_000_000)

        if (resultData == null || resultCode != android.app.Activity.RESULT_OK) {
            Log.e(TAG, "Missing/invalid projection consent result; aborting start")
            stopSelf()
            return
        }

        // Must call startForeground with FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION before
        // touching MediaProjection APIs, or the system throws on API 29+/34+.
        val initialNotification = NotificationHelper.buildLiveNotification(this, elapsedSeconds = 0, pulseOn = true)
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(
                NOTIF_ID,
                initialNotification,
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            )
        } else {
            startForeground(NOTIF_ID, initialNotification)
        }

        acquireWakeLock()

        val projection = mediaProjectionManager.getMediaProjection(resultCode, resultData)
        if (projection == null) {
            Log.e(TAG, "getMediaProjection returned null")
            stopSelfCleanly()
            return
        }
        mediaProjection = projection
        // Required since Android 14: a callback must be registered before createVirtualDisplay.
        projection.registerCallback(projectionCallback, mainHandler)

        try {
            startPipelines(projection, audioMode, requestedLongEdge, requestedFps, requestedBitrateBps)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start recording pipelines", e)
            performStop()
        }
    }

    private fun startPipelines(
        projection: MediaProjection,
        requestedAudioMode: AudioMode,
        requestedLongEdge: Int,
        requestedFps: Int,
        requestedBitrateBps: Int
    ) {
        val (rawWidth, rawHeight, densityDpi, refreshRateHz) = computeCaptureGeometry(requestedLongEdge)

        val appliedConfig = VideoEncoder.resolveSupportedConfig(
            requestedWidth = rawWidth,
            requestedHeight = rawHeight,
            requestedFps = requestedFps,
            requestedBitrateBps = requestedBitrateBps,
            displayRefreshRateHz = refreshRateHz
        )
        if (appliedConfig.wasClamped) {
            Log.w(TAG, "Requested config adjusted: ${appliedConfig.clampReason}")
        }
        appliedConfig.advisoryNote?.let { Log.i(TAG, it) }
        appliedConfigSummary = "${appliedConfig.width}x${appliedConfig.height} @ ${appliedConfig.fps}fps"

        val (uri, pfd) = createOutputTarget()
        outputUri = uri
        outputPfd = pfd

        // Resolve whether audio capture will actually work BEFORE the muxer is created:
        // the muxer's expected track count must be fixed at construction time and never
        // change afterward, so any audio setup failure has to be known up front rather
        // than discovered after video has already started expecting 2 tracks.
        var engine: AudioEngine? = null
        var effectiveAudioMode = requestedAudioMode
        if (requestedAudioMode != AudioMode.NONE) {
            val candidate = AudioEngine(
                mediaProjection = projection,
                audioMode = requestedAudioMode,
                onTrackReady = { format -> muxerController!!.addTrack(format) },
                onSampleReady = { idx, buf, info -> muxerController!!.writeSampleData(idx, buf, info) }
            )
            if (candidate.setup()) {
                engine = candidate
            } else {
                Log.e(TAG, "Audio engine setup failed; falling back to video-only recording")
                effectiveAudioMode = AudioMode.NONE
            }
        }

        val expectedTracks = if (engine != null) 2 else 1
        val muxer = MuxerController(pfd.fileDescriptor, expectedTracks)
        muxerController = muxer

        val startTimeNanos = System.nanoTime()

        val encoder = VideoEncoder(
            config = appliedConfig,
            startTimeNanos = startTimeNanos,
            onTrackReady = { format -> muxer.addTrack(format) },
            onSampleReady = { idx, buf, info -> muxer.writeSampleData(idx, buf, info) }
        )
        videoEncoder = encoder

        virtualDisplay = projection.createVirtualDisplay(
            "CleanRecorderVirtualDisplay",
            appliedConfig.width,
            appliedConfig.height,
            densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            encoder.inputSurface,
            null,
            mainHandler
        )
        encoder.start()

        engine?.start()
        audioEngine = engine

        startElapsedMillis = android.os.SystemClock.elapsedRealtime()
        RecordingState.update {
            it.copy(
                isRecording = true,
                elapsedSeconds = 0,
                audioMode = effectiveAudioMode,
                appliedVideoConfig = appliedConfig,
                lastError = null,
                lastSavedUri = null
            )
        }
        NotificationHelper.updateLiveNotification(this, elapsedSeconds = 0, pulseOn = true, configSummary = appliedConfigSummary)
        startTimer()
    }

    /** Returns (width, height, densityDpi, displayRefreshRateHz). Width/height match the
     * device's real aspect ratio scaled so the long edge equals [requestedLongEdge] —
     * upscaling is allowed on purpose (e.g. a 1080p-native phone requesting 4K), since the
     * compositor legitimately renders into a larger VirtualDisplay surface; this is the same
     * mechanism system "cast to a 4K TV" scaling uses. */
    private fun computeCaptureGeometry(requestedLongEdge: Int): CaptureGeometry {
        val windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        windowManager.defaultDisplay.getRealMetrics(metrics)

        val screenW = metrics.widthPixels
        val screenH = metrics.heightPixels
        val longSide = maxOf(screenW, screenH)
        val shortSide = minOf(screenW, screenH)

        val scale = requestedLongEdge.toDouble() / longSide
        val targetLong = requestedLongEdge - (requestedLongEdge % 2)
        val targetShort = (shortSide * scale).roundToInt().let { it - (it % 2) }.coerceAtLeast(2)

        val (width, height) = if (screenH >= screenW) targetShort to targetLong else targetLong to targetShort

        val refreshRate = try {
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay.refreshRate
        } catch (e: Exception) {
            null
        }

        return CaptureGeometry(width, height, metrics.densityDpi, refreshRate)
    }

    private data class CaptureGeometry(val width: Int, val height: Int, val densityDpi: Int, val refreshRateHz: Float?)

    private fun createOutputTarget(): Pair<Uri, ParcelFileDescriptor> {
        val fileName = "CleanRecorder_${System.currentTimeMillis()}.mp4"
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, fileName)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, "DCIM/CleanRecorder")
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }
        val uri = contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
            ?: throw IllegalStateException("MediaStore insert failed")
        val pfd = contentResolver.openFileDescriptor(uri, "rw")
            ?: throw IllegalStateException("Could not open output file descriptor")
        return uri to pfd
    }

    private fun finalizeOutputTarget() {
        val uri = outputUri ?: return
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.IS_PENDING, 0)
        }
        try {
            contentResolver.update(uri, values, null, null)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to finalize MediaStore entry", e)
        }
    }

    private var appliedConfigSummary: String? = null

    private fun startTimer() {
        val runnable = object : Runnable {
            override fun run() {
                val elapsedMs = android.os.SystemClock.elapsedRealtime() - startElapsedMillis
                val elapsedSeconds = (elapsedMs / 1000).toInt()
                val pulseOn = elapsedSeconds % 2 == 0
                RecordingState.update { it.copy(elapsedSeconds = elapsedSeconds) }
                NotificationHelper.updateLiveNotification(this@RecordService, elapsedSeconds, pulseOn, appliedConfigSummary)
                mainHandler.postDelayed(this, 1000L)
            }
        }
        timerRunnable = runnable
        mainHandler.post(runnable)
    }

    private fun stopTimer() {
        timerRunnable?.let { mainHandler.removeCallbacks(it) }
        timerRunnable = null
    }

    /** Public entry point for both ACTION_STOP and the projection-revoked callback. Safe to
     * call multiple times; only the first call does real work. */
    private fun performStop() {
        if (mediaProjection == null && videoEncoder == null) {
            // Already torn down.
            stopSelfCleanly()
            return
        }
        stopTimer()

        try {
            videoEncoder?.stopAndRelease()
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping video encoder", e)
        }
        try {
            audioEngine?.stopAndRelease()
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping audio engine", e)
        }
        try {
            virtualDisplay?.release()
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing virtual display", e)
        }
        try {
            mediaProjection?.unregisterCallback(projectionCallback)
            mediaProjection?.stop()
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping media projection", e)
        }
        try {
            muxerController?.stopAndRelease()
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping muxer", e)
        }
        try {
            outputPfd?.close()
        } catch (e: Exception) {
            Log.e(TAG, "Error closing output fd", e)
        }
        finalizeOutputTarget()

        val savedUri = outputUri
        RecordingState.update {
            it.copy(isRecording = false, lastSavedUri = savedUri)
        }

        videoEncoder = null
        audioEngine = null
        virtualDisplay = null
        mediaProjection = null
        muxerController = null
        outputUri = null
        outputPfd = null
        appliedConfigSummary = null

        releaseWakeLock()
        stopSelfCleanly()
    }

    private fun stopSelfCleanly() {
        NotificationHelper.cancel(this)
        if (Build.VERSION.SDK_INT >= 24) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
        stopSelf()
    }

    private fun acquireWakeLock() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "CleanRecorder::CaptureWakeLock").apply {
            setReferenceCounted(false)
            acquire(6 * 60 * 60 * 1000L) // 6h safety cap
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
    }

    override fun onDestroy() {
        stopTimer()
        releaseWakeLock()
        super.onDestroy()
    }
}
