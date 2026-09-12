package com.cleanrecorder.app

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.util.Log
import android.view.Surface
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.roundToInt

/**
 * Wraps a hardware AVC encoder configured for COLOR_FormatSurface input. The encoder's
 * input Surface is bound directly to the VirtualDisplay produced by MediaProjection, so
 * frames never cross into app-process CPU memory (no ImageReader / no Bitmap copies).
 */
class VideoEncoder(
    config: AppliedVideoConfig,
    private val startTimeNanos: Long,
    private val onTrackReady: (MediaFormat) -> Int,
    private val onSampleReady: (trackIndex: Int, buffer: ByteBuffer, info: MediaCodec.BufferInfo) -> Unit
) {
    companion object {
        private const val TAG = "VideoEncoder"

        /**
         * Resolves a user-requested (width, height, fps, bitrate) against what the device's
         * actual hardware AVC encoder can do, per MediaCodecInfo.VideoCapabilities. This exists
         * because MediaCodec.configure() throws IllegalArgumentException outright on an
         * unsupported combination (e.g. many chipsets cap out well under 240fps at 4K) rather
         * than gracefully degrading, so we have to pre-flight it ourselves.
         *
         * Also folds in the device's current display refresh rate as an *advisory* note only
         * (not a hard clamp): a VirtualDisplay only receives new frames as fast as the source
         * compositor produces them, which is capped by the panel's refresh rate regardless of
         * what the encoder is configured for. Configuring the encoder above that rate isn't
         * wrong — MediaCodec handles the real, possibly-lower arrival cadence fine via genuine
         * presentation timestamps — it just means the *effective* captured fps will be lower
         * than requested, which callers should be told about rather than silently absorbing.
         */
        fun resolveSupportedConfig(
            requestedWidth: Int,
            requestedHeight: Int,
            requestedFps: Int,
            requestedBitrateBps: Int,
            displayRefreshRateHz: Float?
        ): AppliedVideoConfig {
            val mime = MediaFormat.MIMETYPE_VIDEO_AVC
            val videoCaps = findVideoCapabilities(mime)

            if (videoCaps == null) {
                Log.w(TAG, "No AVC encoder capabilities found; using requested values as-is")
                return AppliedVideoConfig(requestedWidth, requestedHeight, requestedFps, requestedBitrateBps, wasClamped = false)
            }

            var width = requestedWidth
            var height = requestedHeight
            var clamped = false
            val reasons = mutableListOf<String>()

            if (!videoCaps.isSizeSupported(width, height)) {
                var w = width
                var h = height
                var iterations = 0
                while (!videoCaps.isSizeSupported(w, h) && iterations < 25) {
                    w = (w * 0.92).roundToInt().let { it - (it % 2) }
                    h = (h * 0.92).roundToInt().let { it - (it % 2) }
                    iterations++
                }
                if (videoCaps.isSizeSupported(w, h)) {
                    width = w
                    height = h
                } else {
                    // Last-resort safe fallback that virtually every hardware AVC encoder supports.
                    width = 1280
                    height = 720
                }
                clamped = true
                reasons += "resolution reduced to ${width}x${height} (device encoder limit)"
            }

            var fps = requestedFps
            try {
                val fpsRange = videoCaps.getSupportedFrameRatesFor(width, height)
                when {
                    fps > fpsRange.upper -> {
                        fps = fpsRange.upper.toInt().coerceAtLeast(1)
                        clamped = true
                        reasons += "frame rate capped to ${fps}fps (encoder limit at ${width}x${height})"
                    }
                    fps < fpsRange.lower -> {
                        fps = fpsRange.lower.roundToInt().coerceAtLeast(1)
                        clamped = true
                        reasons += "frame rate raised to ${fps}fps (encoder minimum)"
                    }
                }
            } catch (e: IllegalArgumentException) {
                Log.w(TAG, "getSupportedFrameRatesFor rejected ${width}x${height}; leaving fps as requested", e)
            }

            var bitrate = requestedBitrateBps
            val bitrateRange = videoCaps.bitrateRange
            when {
                bitrate > bitrateRange.upper -> {
                    bitrate = bitrateRange.upper
                    clamped = true
                    reasons += "bitrate capped to ${bitrate / 1_000_000}Mbps (encoder limit)"
                }
                bitrate < bitrateRange.lower -> {
                    bitrate = bitrateRange.lower
                    clamped = true
                    reasons += "bitrate raised to ${bitrate / 1_000_000}Mbps (encoder minimum)"
                }
            }

            val advisory = displayRefreshRateHz?.let { hz ->
                if (fps > hz + 1f) {
                    "panel refresh rate is ~${hz.roundToInt()}Hz — actual captured frame cadence " +
                        "will follow that rate even though the encoder is configured for ${fps}fps"
                } else null
            }

            return AppliedVideoConfig(
                width = width,
                height = height,
                fps = fps,
                bitrateBps = bitrate,
                wasClamped = clamped,
                clampReason = reasons.joinToString("; ").ifEmpty { null },
                advisoryNote = advisory
            )
        }

        private fun findVideoCapabilities(mime: String): MediaCodecInfo.VideoCapabilities? {
            return try {
                val list = MediaCodecList(MediaCodecList.REGULAR_CODECS)
                val info = list.codecInfos.firstOrNull { codecInfo ->
                    codecInfo.isEncoder && codecInfo.supportedTypes.any { it.equals(mime, ignoreCase = true) }
                } ?: return null
                info.getCapabilitiesForType(mime).videoCapabilities
            } catch (e: Exception) {
                Log.e(TAG, "Failed to query encoder capabilities", e)
                null
            }
        }
    }

    private val codec: MediaCodec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
    val inputSurface: Surface

    @Volatile private var muxerTrackIndex = -1
    private val running = AtomicBoolean(false)
    private var drainThread: Thread? = null

    init {
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, config.width, config.height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, config.bitrateBps)
            setInteger(MediaFormat.KEY_FRAME_RATE, config.fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2)
            setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR)
            if (android.os.Build.VERSION.SDK_INT >= 31) {
                setInteger(MediaFormat.KEY_LATENCY, 0)
            }
        }
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        inputSurface = codec.createInputSurface()
    }

    fun start() {
        codec.start()
        running.set(true)
        drainThread = Thread({ drainLoop() }, "VideoEncoderDrain").apply {
            priority = Thread.MAX_PRIORITY
            start()
        }
    }

    /** Signals end-of-stream on the encoder and blocks until the drain thread has
     * flushed the final frames and fully stopped. Must be called off the drain thread. */
    fun stopAndRelease() {
        if (running.getAndSet(false)) {
            try {
                codec.signalEndOfInputStream()
            } catch (_: Exception) {
            }
        }
        drainThread?.join(2000)
        try {
            codec.stop()
        } catch (_: Exception) {
        }
        try {
            codec.release()
        } catch (_: Exception) {
        }
        try {
            inputSurface.release()
        } catch (_: Exception) {
        }
    }

    private fun drainLoop() {
        val bufferInfo = MediaCodec.BufferInfo()
        var sawEos = false
        while (!sawEos) {
            val outIndex = try {
                codec.dequeueOutputBuffer(bufferInfo, 10_000L)
            } catch (e: IllegalStateException) {
                break
            }
            when {
                outIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                    if (!running.get()) {
                        // We requested EOS already; keep polling briefly for the final buffer.
                    }
                }
                outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    muxerTrackIndex = onTrackReady(codec.outputFormat)
                }
                outIndex >= 0 -> {
                    val encodedData: ByteBuffer? = codec.getOutputBuffer(outIndex)
                    if (encodedData != null && bufferInfo.size > 0 && (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
                        encodedData.position(bufferInfo.offset)
                        encodedData.limit(bufferInfo.offset + bufferInfo.size)
                        // Deliberately NOT rebased: the input Surface's buffer-queue
                        // timestamps are already absolute System.nanoTime()-derived values
                        // set by the compositor. AudioEngine anchors its own PTS to the same
                        // absolute nanoTime clock, so both tracks share one monotonic origin
                        // without either side needing to know the other's start offset.
                        if (muxerTrackIndex >= 0) {
                            onSampleReady(muxerTrackIndex, encodedData, bufferInfo)
                        }
                    }
                    codec.releaseOutputBuffer(outIndex, false)
                    if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        sawEos = true
                    }
                }
            }
        }
    }
}
