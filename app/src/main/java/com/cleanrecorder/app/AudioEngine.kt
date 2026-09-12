package com.cleanrecorder.app

import android.annotation.SuppressLint
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.util.Log
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Captures internal ("what's playing") audio and/or the microphone, mixes them sample-by-sample
 * when both are active, and feeds the mixed PCM into a hardware AAC encoder. All working buffers
 * are allocated once up front (see [internalBuf], [micBuf], [mixedShorts]) and reused for the
 * lifetime of the recording so the hot capture loop never triggers GC.
 */
class AudioEngine(
    private val mediaProjection: MediaProjection?,
    private val audioMode: AudioMode,
    private val onTrackReady: (MediaFormat) -> Int,
    private val onSampleReady: (trackIndex: Int, buffer: ByteBuffer, info: MediaCodec.BufferInfo) -> Unit
) {
    companion object {
        private const val TAG = "AudioEngine"
        const val SAMPLE_RATE = 48_000
        private const val CHANNEL_COUNT = 2
        private const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_STEREO
        private const val ENCODING = AudioFormat.ENCODING_PCM_16BIT
        private const val AAC_BITRATE = 192_000
        // Number of PCM frames (samples per channel) processed per loop iteration.
        private const val FRAMES_PER_CHUNK = 1024
    }

    private var internalRecord: AudioRecord? = null
    private var micRecord: AudioRecord? = null
    private lateinit var aacCodec: MediaCodec

    @Volatile private var muxerTrackIndex = -1
    private val running = AtomicBoolean(false)
    private var captureThread: Thread? = null

    // Preallocated, reused every loop iteration - no per-chunk allocation.
    private val bytesPerChunk = FRAMES_PER_CHUNK * CHANNEL_COUNT * 2 // 16-bit samples
    private val internalBuf = ByteArray(bytesPerChunk)
    private val micBuf = ByteArray(bytesPerChunk)
    private val mixedShorts = ShortArray(FRAMES_PER_CHUNK * CHANNEL_COUNT)
    private val mixedBytes = ByteArray(bytesPerChunk)

    private var totalFramesWritten: Long = 0L
    private var ptsAnchorUs: Long = -1L

    val isActive: Boolean get() = audioMode != AudioMode.NONE

    @SuppressLint("MissingPermission") // RECORD_AUDIO is verified by the caller before start()
    fun setup(): Boolean {
        if (audioMode == AudioMode.NONE) return false

        val format = AudioFormat.Builder()
            .setEncoding(ENCODING)
            .setSampleRate(SAMPLE_RATE)
            .setChannelMask(CHANNEL_CONFIG)
            .build()

        val minBufBytes = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, ENCODING)
        val recordBufBytes = (minBufBytes * 4).coerceAtLeast(bytesPerChunk * 4)

        try {
            if ((audioMode == AudioMode.SYSTEM_ONLY || audioMode == AudioMode.SYSTEM_AND_MIC) && mediaProjection != null) {
                val captureConfig = AudioPlaybackCaptureConfiguration.Builder(mediaProjection)
                    .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                    .addMatchingUsage(AudioAttributes.USAGE_GAME)
                    .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                    .addMatchingUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
                    .build()

                internalRecord = AudioRecord.Builder()
                    .setAudioFormat(format)
                    .setAudioPlaybackCaptureConfig(captureConfig)
                    .setBufferSizeInBytes(recordBufBytes)
                    .build()

                if (internalRecord?.state != AudioRecord.STATE_INITIALIZED) {
                    Log.e(TAG, "Internal AudioRecord failed to initialize")
                    internalRecord?.release()
                    internalRecord = null
                }
            }

            if (audioMode == AudioMode.MIC_ONLY || audioMode == AudioMode.SYSTEM_AND_MIC) {
                micRecord = AudioRecord(
                    MediaRecorder.AudioSource.MIC,
                    SAMPLE_RATE,
                    CHANNEL_CONFIG,
                    ENCODING,
                    recordBufBytes
                )
                if (micRecord?.state != AudioRecord.STATE_INITIALIZED) {
                    Log.e(TAG, "Mic AudioRecord failed to initialize")
                    micRecord?.release()
                    micRecord = null
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to set up audio capture", e)
            return false
        }

        if (internalRecord == null && micRecord == null) return false

        val aacFormat = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, SAMPLE_RATE, CHANNEL_COUNT).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, AAC_BITRATE)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, bytesPerChunk)
        }

        aacCodec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        aacCodec.configure(aacFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        return true
    }

    fun start() {
        aacCodec.start()
        internalRecord?.startRecording()
        micRecord?.startRecording()
        running.set(true)
        captureThread = Thread({ captureLoop() }, "AudioCaptureMix").apply {
            priority = Thread.MAX_PRIORITY
            start()
        }
    }

    fun stopAndRelease() {
        running.set(false)
        captureThread?.join(2000)
        try {
            internalRecord?.stop()
        } catch (_: Exception) {
        }
        internalRecord?.release()
        try {
            micRecord?.stop()
        } catch (_: Exception) {
        }
        micRecord?.release()
        if (::aacCodec.isInitialized) {
            try {
                aacCodec.stop()
            } catch (_: Exception) {
            }
            try {
                aacCodec.release()
            } catch (_: Exception) {
            }
        }
    }

    private fun captureLoop() {
        val bufferInfo = MediaCodec.BufferInfo()
        var endOfStreamQueued = false

        while (running.get() || !endOfStreamQueued) {
            val framesRead = readAndMix()
            val flags = if (!running.get()) {
                endOfStreamQueued = true
                MediaCodec.BUFFER_FLAG_END_OF_STREAM
            } else 0

            if (framesRead > 0 || flags != 0) {
                queueToEncoder(framesRead, flags)
            }
            drainEncoder(bufferInfo)

            if (endOfStreamQueued) break
        }
        // Final drain to flush any buffered encoder output after EOS.
        drainEncoder(bufferInfo, drainUntilEos = true)
    }

    /** Reads from whichever sources are active into their preallocated buffers, mixes them
     * sample-by-sample into [mixedBytes] when both are present, and returns frame count read. */
    private fun readAndMix(): Int {
        val hasInternal = internalRecord != null
        val hasMic = micRecord != null

        val internalBytesRead = if (hasInternal) {
            internalRecord!!.read(internalBuf, 0, bytesPerChunk, AudioRecord.READ_BLOCKING)
        } else -1
        val micBytesRead = if (hasMic) {
            micRecord!!.read(micBuf, 0, bytesPerChunk, AudioRecord.READ_BLOCKING)
        } else -1

        val validInternal = internalBytesRead > 0
        val validMic = micBytesRead > 0
        if (!validInternal && !validMic) return 0

        val byteCount = when {
            validInternal && validMic -> minOf(internalBytesRead, micBytesRead)
            validInternal -> internalBytesRead
            else -> micBytesRead
        }
        val sampleCount = byteCount / 2

        when {
            validInternal && validMic -> {
                // Sample-accurate additive mix with clipping to the 16-bit range.
                var bi = 0
                for (i in 0 until sampleCount) {
                    val a = ((internalBuf[bi + 1].toInt() shl 8) or (internalBuf[bi].toInt() and 0xFF)).toShort()
                    val b = ((micBuf[bi + 1].toInt() shl 8) or (micBuf[bi].toInt() and 0xFF)).toShort()
                    val sum = a + b
                    val clamped = when {
                        sum > Short.MAX_VALUE -> Short.MAX_VALUE
                        sum < Short.MIN_VALUE -> Short.MIN_VALUE
                        else -> sum.toShort()
                    }
                    mixedShorts[i] = clamped
                    bi += 2
                }
                shortsToBytes(mixedShorts, sampleCount, mixedBytes)
            }
            validInternal -> System.arraycopy(internalBuf, 0, mixedBytes, 0, byteCount)
            else -> System.arraycopy(micBuf, 0, mixedBytes, 0, byteCount)
        }

        return sampleCount / CHANNEL_COUNT // frames = samples / channels
    }

    private fun shortsToBytes(shorts: ShortArray, sampleCount: Int, out: ByteArray) {
        var bi = 0
        for (i in 0 until sampleCount) {
            val v = shorts[i].toInt()
            out[bi] = (v and 0xFF).toByte()
            out[bi + 1] = ((v shr 8) and 0xFF).toByte()
            bi += 2
        }
    }

    private fun queueToEncoder(framesRead: Int, flags: Int) {
        val inputIndex = try {
            aacCodec.dequeueInputBuffer(10_000L)
        } catch (e: IllegalStateException) {
            return
        }
        if (inputIndex < 0) return

        val inputBuffer = aacCodec.getInputBuffer(inputIndex) ?: return
        inputBuffer.clear()
        val byteCount = framesRead * CHANNEL_COUNT * 2
        if (byteCount > 0) {
            inputBuffer.put(mixedBytes, 0, byteCount)
        }

        val nowUs = System.nanoTime() / 1000
        if (ptsAnchorUs < 0) ptsAnchorUs = nowUs
        // Sample-accurate PTS derived from cumulative frame count avoids jitter from
        // per-chunk system-clock reads while still anchoring to the shared absolute clock.
        val ptsUs = ptsAnchorUs + (totalFramesWritten * 1_000_000L / SAMPLE_RATE)
        totalFramesWritten += framesRead

        aacCodec.queueInputBuffer(inputIndex, 0, byteCount, ptsUs, flags)
    }

    private fun drainEncoder(bufferInfo: MediaCodec.BufferInfo, drainUntilEos: Boolean = false) {
        while (true) {
            val outIndex = try {
                aacCodec.dequeueOutputBuffer(bufferInfo, 10_000L)
            } catch (e: IllegalStateException) {
                return
            }
            when {
                outIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                    if (!drainUntilEos) return
                }
                outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    muxerTrackIndex = onTrackReady(aacCodec.outputFormat)
                }
                outIndex >= 0 -> {
                    val data = aacCodec.getOutputBuffer(outIndex)
                    if (data != null && bufferInfo.size > 0 && (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
                        data.position(bufferInfo.offset)
                        data.limit(bufferInfo.offset + bufferInfo.size)
                        if (muxerTrackIndex >= 0) {
                            onSampleReady(muxerTrackIndex, data, bufferInfo)
                        }
                    }
                    val eos = (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0
                    aacCodec.releaseOutputBuffer(outIndex, false)
                    if (eos) return
                }
            }
        }
    }
}
