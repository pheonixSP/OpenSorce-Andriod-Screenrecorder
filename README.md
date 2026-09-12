# CleanRecorder

A no-overlay Android screen recorder: hardware-encoded 1080p60 video, system-audio + mic
capture/mixing, a live status-bar notification timer, and a Quick Settings tile toggle.

## Build

Open the `CleanRecorder/` folder in Android Studio (Koala+) and let it sync — it's a
standard Gradle project (AGP 8.5.2 / Kotlin 1.9.24 / compileSdk & targetSdk 35, minSdk 29).
No `gradlew` wrapper is included; run **File → Sync Project with Gradle Files** and Android
Studio will offer to generate the wrapper, or run `gradle wrapper` yourself if you have
Gradle installed locally.

minSdk is 29 (Android 10) because `AudioPlaybackCaptureConfiguration` — the API that makes
system-audio capture possible at all — doesn't exist before it.

## What's actually in here vs. what was asked for

Two items in the original spec describe things that aren't real public APIs, so I implemented
the closest real equivalent instead of inventing calls that won't compile:

- **"One UI 7 Dynamic Island / Live Activity / Ongoing Activity" APIs** — there's no
  third-party-accessible Samsung or AOSP API by these names as of this writing.
  `Notification.DecoratedCustomViewStyle` exists but isn't a "pill" API, and there's no
  `Category.STOPWATCH` (there is `CATEGORY_STOPWATCH`, which I do use). What I built instead:
  a foreground-service notification with `setOngoing(true)` + `setUsesChronometer(true)` +
  `CATEGORY_STOPWATCH`, which is the real mechanism that produces a persistent status-bar icon
  and a live, system-driven MM:SS counter in the shade on both stock Android and One UI,
  with tap-to-stop wired to a `PendingIntent`. A manual 1 Hz icon swap layers a pulsing red
  dot on top, since the chronometer text itself needs no polling.
- Everything else (MediaProjection → MediaCodec Surface input → VirtualDisplay,
  AudioPlaybackCaptureConfiguration, TileService, Compose UI) is implemented against real,
  current public APIs.

## Architecture

- **`RecordService`** — foreground service (`foregroundServiceType="mediaProjection"`).
  Owns the `MediaProjection`, `VirtualDisplay`, `VideoEncoder`, `AudioEngine`, and
  `MuxerController`. Registers a `MediaProjection.Callback` (required on Android 14+ before
  `createVirtualDisplay`) so system-initiated capture revocation tears down cleanly.
- **`VideoEncoder`** — `MediaCodec` AVC encoder configured with `COLOR_FormatSurface`; its
  `createInputSurface()` is handed straight to `VirtualDisplay`, so frames never cross into
  app-process CPU memory. Runs its own drain thread.
- **`AudioEngine`** — sets up an internal-playback `AudioRecord` (via
  `AudioPlaybackCaptureConfiguration`) and/or a mic `AudioRecord`, mixes them sample-by-sample
  with preallocated `ByteArray`/`ShortArray` buffers (zero per-chunk allocation), and feeds an
  AAC `MediaCodec` encoder. PTS is derived from cumulative sample count anchored to
  `System.nanoTime()`, matching the video encoder's own nanoTime-based surface timestamps so
  both tracks share one monotonic clock without cross-thread coordination.
- **`MuxerController`** — `MediaMuxer` requires every track added before `start()`, but video
  and audio run on independent threads; this wraps that start-up handshake with a
  `CountDownLatch` gated on the actual (not assumed) track count, resolved *before* the muxer
  is constructed so a failed mic/system-audio init can't leave the video track waiting forever.
- **`NotificationHelper`** — builds/updates the live notification described above.
- **`QuickTileService`** — reflects `RecordingState` (a process-wide `StateFlow`), stops a
  running recording directly, and routes starts through `ProjectionRequestActivity`.
- **`ProjectionRequestActivity`** — an invisible trampoline: a `TileService` can't itself
  register for an `ActivityResult`, so this activity requests MediaProjection consent on the
  tile's behalf and starts the service.
- **`MainActivity`** — Compose UI: audio-source picker, live elapsed-time card, single record
  button, runtime permission handling (`RECORD_AUDIO`, `POST_NOTIFICATIONS`).

## Output

Files are written via `MediaStore` (scoped storage) to `DCIM/CleanRecorder/`, using
`IS_PENDING` to guard against a half-written file appearing in galleries mid-recording.

## Known trade-offs worth knowing about

- Capture resolution is computed from the device's real display metrics, capped at 1920px on
  the long edge (not upscaled if the panel is smaller) — this is what "1080x1920 or scaled
  match to aspect ratio" meant in practice for non-1080p-native devices (foldables, tablets).
- If system-audio capture fails to initialize (some OEM skins restrict it further than AOSP),
  the service automatically falls back to video-only rather than failing the whole recording —
  `RecordingState.audioMode` reflects the *effective* mode, not just the requested one.
- `AudioPlaybackCaptureConfiguration` only captures audio from apps that don't opt out via
  `setAllowedCapturePolicy`/`AudioAttributes` — a small number of apps (DRM'd media, some game
  engines) block it by design, and no public API can override that.
