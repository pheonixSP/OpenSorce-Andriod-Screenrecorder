# CleanRecorder

A no-overlay Android screen recorder: hardware-encoded video up to 4K/240fps (device-capability
permitting), configurable bitrate, system-audio + mic capture/mixing, a live status-bar
notification timer, and a Quick Settings tile toggle.

## Build & run

This is a normal Android Studio project — there's no separate "compile" step you need to
run yourself:

1. Open the `CleanRecorder/` folder in Android Studio (Koala+). It'll detect the Gradle
   project automatically and start syncing (downloading AGP 8.5.2 / Kotlin 1.9.24 and the
   dependencies in `app/build.gradle.kts`). Let that finish — first sync can take a few minutes.
2. On your phone: **Settings → About phone → tap "Build number" 7 times** to unlock Developer
   Options, then **Settings → Developer options → USB debugging → on**.
3. Plug the phone into your computer with a USB cable. Choose "Allow" / "Always allow from
   this computer" on the RSA fingerprint prompt that pops up on the phone screen.
4. In Android Studio, pick your device from the device dropdown in the toolbar (next to the
   Run button), then click **Run ▶** (or `Shift+F10`).

That last step is the part worth calling out: clicking Run does the whole pipeline for you —
Gradle compiles the Kotlin sources, packages the APK, signs it with a debug key, pushes it to
the phone over the USB (ADB) connection, and launches it, all automatically in the background.
You never touch a raw `.apk` file or run a manual install command; Android Studio's build
output panel just shows progress and then the app opens on your phone by itself. Any time you
change code and hit Run again, it rebuilds and reinstalls the same way (usually much faster on
incremental builds).

No `gradlew` wrapper is checked into the repo — Android Studio will offer to generate one on
first sync, or run `gradle wrapper` yourself if you have Gradle installed locally and prefer
building from a terminal.

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
- **`MainActivity`** — Compose UI: audio-source picker, resolution/frame-rate/bitrate preset
  selectors, live elapsed-time card, single record button, runtime permission handling
  (`RECORD_AUDIO`, `POST_NOTIFICATIONS`).
- **`ResolutionPreset` / `FrameRatePreset` / `BitratePreset`** (`RecordingConfig.kt`) — the
  selectable presets: 360p→4K, 30→240fps, 6→60 Mbps. Resolution presets are a target "long
  edge"; the actual capture width/height is derived by matching the device's real aspect
  ratio (see `RecordService.computeCaptureGeometry`), and upscaling is allowed on purpose
  (e.g. a 1080p-native phone requesting 4K) — same mechanism system display-mirroring/casting
  uses to render into a larger virtual surface.
- **`VideoEncoder.resolveSupportedConfig`** — before configuring the encoder, queries the
  device's actual `MediaCodecInfo.VideoCapabilities` and clamps the requested resolution/fps/
  bitrate down to whatever the hardware really supports, since `MediaCodec.configure()` throws
  outright on an unsupported combination instead of degrading gracefully. The clamped values
  (plus *why* they were clamped) flow back into `RecordingState` so the UI can show what's
  actually recording, not just what was requested.
- **`Prefs`** — persists the last-chosen audio mode + resolution + fps + bitrate so a
  Quick Settings tile-triggered recording uses the same settings you last picked in-app.

## Output

Files are written via `MediaStore` (scoped storage) to `DCIM/CleanRecorder/`, using
`IS_PENDING` to guard against a half-written file appearing in galleries mid-recording.

## Known trade-offs worth knowing about

- Resolution presets (360p–4K) and fps presets (30–240) are requests, not guarantees — see
  `VideoEncoder.resolveSupportedConfig` above. A phone's hardware AVC encoder frequently can't
  actually do 4K@240 (few chipsets can), so it gets clamped to the nearest supported combo and
  `RecordingState.appliedVideoConfig` reflects the real values in use.
- Separately, a `VirtualDisplay` only receives new frames as fast as the source compositor
  produces them, which is capped by the phone's actual panel refresh rate (commonly 60/90/120Hz)
  regardless of what the encoder is configured for. Selecting 240fps on a 60Hz phone won't
  crash anything or corrupt the file — MediaCodec handles the real, lower arrival cadence fine
  via genuine presentation timestamps — it just means the effective captured fps will quietly
  be lower than what was selected. `RecordService` surfaces this as an advisory log line
  rather than silently pretending the number is real.
- If system-audio capture fails to initialize (some OEM skins restrict it further than AOSP),
  the service automatically falls back to video-only rather than failing the whole recording —
  `RecordingState.audioMode` reflects the *effective* mode, not just the requested one.
- `AudioPlaybackCaptureConfiguration` only captures audio from apps that don't opt out via
  `setAllowedCapturePolicy`/`AudioAttributes` — a small number of apps (DRM'd media, some game
  engines) block it by design, and no public API can override that.
