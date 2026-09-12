package com.cleanrecorder.app

/** Preset "long edge" targets. Actual width/height are derived at capture time by matching
 * the device's real aspect ratio (see RecordService.computeCaptureGeometry), so a 16:9 phone
 * requesting P1080 gets 1080x1920, an ultra-tall phone gets e.g. 1080x2400, etc. */
enum class ResolutionPreset(val longEdge: Int, val label: String) {
    P360(640, "360p"),
    P480(854, "480p"),
    P720(1280, "720p"),
    P1080(1920, "1080p"),
    P1440(2560, "1440p"),
    P2160(3840, "4K")
}

/** Frame-rate presets. Two real-world constraints apply beyond just "does the encoder
 * support it": (1) the VirtualDisplay's actual frame production rate is driven by the
 * source display compositor, so nothing above the device's real panel refresh rate is ever
 * achievable no matter what the encoder is configured for, and (2) many hardware AVC
 * encoders cap out well below 240fps at high resolutions. Both are enforced at capture
 * start via MediaCodec capability queries — see VideoEncoder.resolveSupportedConfig. */
enum class FrameRatePreset(val fps: Int, val label: String) {
    FPS30(30, "30"),
    FPS60(60, "60"),
    FPS90(90, "90"),
    FPS120(120, "120"),
    FPS144(144, "144"),
    FPS165(165, "165"),
    FPS240(240, "240")
}

enum class BitratePreset(val bps: Int, val label: String) {
    MBPS6(6_000_000, "6 Mbps"),
    MBPS10(10_000_000, "10 Mbps"),
    MBPS14(14_000_000, "14 Mbps"),
    MBPS20(20_000_000, "20 Mbps"),
    MBPS30(30_000_000, "30 Mbps"),
    MBPS45(45_000_000, "45 Mbps"),
    MBPS60(60_000_000, "60 Mbps")
}

/** The user's requested configuration, sent to RecordService and persisted via Prefs. */
data class RecordingConfig(
    val audioMode: AudioMode,
    val longEdge: Int,
    val fps: Int,
    val bitrateBps: Int
)

/** What RecordService actually configured the encoder with, after capability resolution.
 * May differ from the request if the device's hardware encoder couldn't support it.
 * [advisoryNote] is informational only (e.g. "panel refresh rate is 90Hz") and does NOT
 * imply the numeric fields were altered because of it — see VideoEncoder.resolveSupportedConfig
 * for why capturing above the display's real refresh rate isn't clamped outright. */
data class AppliedVideoConfig(
    val width: Int,
    val height: Int,
    val fps: Int,
    val bitrateBps: Int,
    val wasClamped: Boolean,
    val clampReason: String? = null,
    val advisoryNote: String? = null
)
