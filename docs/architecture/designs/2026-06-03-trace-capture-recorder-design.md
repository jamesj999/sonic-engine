# Trace Capture Recorder — Design

**Date:** 2026-06-03
**Status:** Approved (brainstorm) — ready for implementation planning
**Branch base:** `develop`

## 1. Purpose & Scope

Provide a performant, lossless, reasonably compact way to record **synced audio +
video** of gameplay. The MVP target is a **demo reel of a trace replay** — replay
one of the Trace Test Mode traces (optionally including the desync ghosts) and
write it to a discrete, timestamped file.

### MVP scope
- **Headless / offscreen**: no visible window required. Reuses
  `VisualReferenceGenerator`'s offscreen **GL-context setup** (hidden GLFW
  window, `glReadPixels`) but boots a **real `GameLoop` + gameplay session**
  rather than hand-driving rendering — see §2.3.
- **Deterministic, offline**: capture is paced by the encoder, not by a wall
  clock. Because the engine is a deterministic fixed-timestep replayer, "1:1, no
  dropped frames, perfect A/V sync" is a *structural guarantee*, not a
  performance gamble.
- **Synced audio + video**: each game frame contributes exactly one video frame
  and exactly `AudioFrameClock.samplesForNextFrame()` stereo PCM samples.
- **Threaded compression**: the frame-producing thread captures uncompressed
  pixels/PCM and hands them to a background encoder thread (required for MVP).
- **Cross-platform via ffmpeg** (external tool on `PATH`).
- **Trace-framework visibility flags** (ghosts / game HUD / debug HUD) honored
  identically by live Trace Test Mode and the recorder.

### Explicitly out of MVP scope (but the architecture must not preclude them)
- Non-headless / on-screen capture while watching in a visible window.
- Realtime capture of live interactive gameplay (start/stop hotkey).
- Backpressure policies other than "block" (drop-oldest / fail for realtime).
- Alternate codecs/containers beyond the default.

The design defines clean seams for all of the above but ships **one
implementation per seam** (YAGNI on implementations, forward-thinking on
boundaries).

## 2. Architecture

Two packages with a single driver-agnostic seam between them.

### 2.1 `com.openggf.capture` — scope-agnostic recording subsystem

Knows nothing about traces, ghosts, or headlessness. Driven entirely through
`CaptureRecorder`'s lifecycle API.

| Unit | Responsibility | MVP impl | Extension point (not built) |
|------|----------------|----------|-----------------------------|
| `CapturedFrame` (record) | One frame: RGBA bytes + that frame's stereo PCM shorts + `sampleCount` + `frameIndex` + `width`/`height` | — | — |
| `VideoFrameGrabber` (iface) | Return RGBA of the current framebuffer | `GlReadPixelsGrabber` | FBO/blit grabber; multi-viewport |
| `AudioFrameTap` (iface) | Return this frame's stereo PCM | `DrainPcmAudioTap` (`drainPcm`) | live OpenAL mix tee (realtime) |
| `FrameSink` (iface) | Consume a `CapturedFrame` | `EncoderSink` (bounded blocking queue → encoder thread) | preview/network sinks |
| `CaptureEncoder` (iface) | Encode + mux to a file | `FfmpegEncoder` | other codecs/containers |
| `BackpressurePolicy` (enum) | Action when the sink queue is full | `BLOCK` | `DROP_OLDEST`, `FAIL` (realtime) |
| `CaptureRecorder` | Lifecycle: `start()` → `submit(frame)` per frame → `stop()`. Owns the sink + encoder; resolves the timestamped output path | the single MVP recorder | reused unchanged by a hotkey/Engine hook |

`CaptureRecorder` API (sketch):

```java
recorder.start();                 // opens encoder/ffmpeg, temp files
for each frame:
    recorder.submit(captured);    // enqueue; BLOCK policy paces producer
recorder.stop();                  // flush queue, close pipe, run mux, finalize file
```

The recorder does **not** know who produces frames. The headless loop *pulls*
(step → grab → submit). A future realtime path would *push* from
`Engine.display()` with `BackpressurePolicy.DROP_OLDEST` — **without touching the
capture core**.

### 2.2 Trace driver (scope-specific)

Lives with the trace framework / `tools`. This is the MVP's only `CaptureRecorder`
caller.

| Unit | Responsibility |
|------|----------------|
| `TraceCaptureTool` (CLI entry, `tools`) | Parse args; perform the **headless engine boot** of §2.3 (offscreen GL + real `GameLoop` + gameplay session, no master-title/fade UI); construct a `CaptureRecorder`; run the deterministic step → render → grab → submit loop until the trace completes; tear down |
| `TraceCaptureSession` | Deterministic trace drive reusing the **live** step + render path: `TraceReplaySessionBootstrap` + BK2 playback (`PlaybackDebugManager`) + `LiveTraceComparator` + `GhostTraceRenderer`, driven by `GameLoop.step()` and the live LEVEL-mode render. No master-title/fade UI. Exposes `stepAndRender()` and `isComplete()` |
| `TraceRenderVisibility` | Config-backed value object: three independent **master gates** (ghosts / game HUD / debug HUD). Does **not** mutate `DebugOverlayManager` — `showDebugHud()` gates whether the debug HUD renders; per-element `DebugOverlayToggle` state is honored as-is at the render site (see §6.1). Honored by live Trace Test Mode **and** the recorder |

### 2.3 Headless boot & drive (non-UI) — what `TraceCaptureTool` must construct

Capture needs **both** the live `GameLoop.step()` (for audio, §3/§4) **and** the
live LEVEL-mode render (for ghosts + HUD). That rules out the
`VisualReferenceGenerator` model — it only creates a hidden GL context and
hand-drives `levelManager.drawWithSpritePriority(...)`; it builds **no** `Engine`,
`GameLoop`, or `PlaybackDebugManager`. The capture driver therefore boots a
**real `GameLoop` + gameplay session offscreen**, reusing the GL-context setup
from `VisualReferenceGenerator` but nothing of its render-driving.

Boot responsibilities (mirroring `Engine`/`TraceSessionLauncher` minus the
master-title/fade UI):

1. **Offscreen GL context** — hidden GLFW window + `GraphicsManager.init(...)`
   at the capture resolution (the reusable part of `VisualReferenceGenerator`).
2. **ROM + module** — load ROM, `GameModuleRegistry.detectAndSetModule(rom)`.
3. **Engine context + `GameLoop`** — construct an `EngineContext` and
   `new GameLoop(engineContext)` (cf. `Engine.java:204`); set it current so
   `GameServices`/`Engine.currentGameLoop()` resolve.
4. **Gameplay session** — `SessionManager.openGameplaySession(module)`
   (cf. `Engine.java:458`) to build the `GameplayModeContext`.
5. **Audio capture mode** — `AudioManager.beginCaptureMode(sampleRate, fps)`
   (§5) so `GameLoop.step()` produces presentation PCM headlessly.
6. **Trace launch without UI** — run the post-bootstrap sequence of
   `TraceSessionLauncher.finishLaunchAfterGameBootstrap()` (reset level
   subsystems, register team, `loadZoneAndAct`, `setGameMode(LEVEL)`, consume
   title-card requests, `PlaybackDebugManager.startSession(...)`, build
   `LiveTraceComparator` + `GhostTraceRenderer` + visibility), but invoked
   **directly** rather than via `GameLoop.launchGameByEntry(...)` (which runs the
   master-title exit/fade path, `GameLoop.java:2414`).

**Recommended refactor (so we don't fork a fragile sequence):** extract the
UI-agnostic body of `TraceSessionLauncher.finishLaunchAfterGameBootstrap()` into
a reusable `TraceReplayDriver` consumed by **both** the live launcher (which
keeps master-title + fade around it) and `TraceCaptureSession` (which calls it
directly after the headless boot above). The launcher's many ordering
invariants (team-register before `loadZoneAndAct`, title-card consumption, frame
-counter alignment) then live in one place. The per-frame render reuses the same
LEVEL-mode render + ghost-layering the live loop emits, so the reel matches
shipped presentation.

**Open item for the implementation plan:** finalize the exact engine-context
wiring (a minimal headless `Engine.initHeadless()`-style entry vs. assembling
`EngineContext` + `GameLoop` + `SessionManager` directly). Both are viable; the
plan picks one and the `TraceReplayDriver` refactor is the same either way.

## 3. Data Flow (headless, per frame)

1. `TraceCaptureSession.stepAndRender()`:
   - advance one deterministic trace frame via the **live `GameLoop.step()`** —
     the path **live Trace Test Mode** uses (`TraceSessionLauncher.LiveFixture`
     → `gameLoop.step()`). Capture deliberately uses this path **because it
     advances audio**: audio-frame advancement is owned by the step
     (`beginGameplayAudioFrameForTick` → `advanceGameplayAudioFrameForTick`,
     `GameLoop.java:871,894`). The capture session does **not** call
     `AudioManager.advanceGameplayFrameAudio()` itself — that would
     double-advance the audio clock per captured frame (see §4).
   - **Not** the headless trace-*test* path: `HeadlessTestFixture` /
     `HeadlessTestRunner.stepFrameFromRecording()` calls `LevelFrameStep.execute(...)`
     directly (`HeadlessTestRunner.java:122`) and **does not drive audio at all**.
     That path exists for silent logic-parity assertions; reusing it for capture
     would yield no PCM. The two paths are kept distinct on purpose.
   - render scene + ghosts + HUD to the offscreen framebuffer, each layer gated
     by `TraceRenderVisibility`.
2. `recorder.submit(new CapturedFrame(grabber.grab(), audioTap.drain(), ...))`:
   - `GlReadPixelsGrabber` reads `width*height*4` RGBA bytes from `GL_BACK`;
   - `DrainPcmAudioTap` drains the PCM **already produced by this frame's step**,
     sizing the drain from the **non-mutating** produced-sample count (§4) — never
     by calling the mutating `AudioFrameClock.samplesForNextFrame()` again.
3. `EncoderSink` enqueues the frame on a bounded blocking queue. With
   `BackpressurePolicy.BLOCK`, a full queue blocks the producer — which merely
   slows the deterministic loop and **cannot drop or desync** anything.
4. Encoder thread:
   - writes raw RGBA to the ffmpeg **video** process stdin;
   - appends the frame's PCM to a temp `s16le` audio file.
5. `recorder.stop()`:
   - close the video stdin (ffmpeg finalizes the video-only FFV1/MKV);
   - run a **mux pass** combining FFV1 video + lossless audio into the final
     timestamped file with `-c:v copy` (no video re-encode).

### Why two-phase (video pipe + temp audio file + mux) for MVP
ffmpeg reads a single stdin; muxing two live raw streams cross-platform needs
named pipes/extra FDs (awkward on Windows). Audio is tiny (48 kHz × 2ch × 2B ≈
192 KB/s), so buffering it to a temp file and running a fast `-c:v copy` mux at
the end is simpler and fully robust. A single-process streaming mux remains a
later optimization behind the same `CaptureEncoder` seam.

## 4. Synchronization Model

- **Video**: exactly one captured frame per game step.
- **Audio**: each game step produces exactly the samples
  `AudioFrameClock.samplesForNextFrame()` returns for that frame (48000/fps with
  remainder accumulation — e.g. 800/801 samples/frame at 60 fps). That clock is
  advanced **once**, inside the game-loop step
  (`StreamBackedDeterministicAudioRuntime.advanceFrame`,
  `StreamBackedDeterministicAudioRuntime.java:99`), which writes exactly that
  count into the output FIFO + history.
- Sync needs **no timestamps**: equal frame/sample cadence by construction.
  ffmpeg is told `-r <fps>` for video and `-ar 48000 -ac 2` for audio; their
  durations match because the per-frame sample budget integrates to exactly
  `sampleRate` per second.

### 4.1 Sample-count contract (must not re-advance the clock)

The producer must **not** call `samplesForNextFrame()` to size the drain — it
mutates `remainder` / `totalSamplesProduced` and would desync the audio clock by
one frame's budget on every captured frame. Instead:

- the capture API reports the sample count **produced during the step's
  `advanceFrame`** (captured at production time, non-mutating to read), and
- the tap drains exactly that many stereo frames.

Tests assert `drainPcm(...)` returns exactly the produced count — in offline
lockstep (one NORMAL `advanceFrame` then an immediate drain) the FIFO holds
precisely one frame's samples.

## 5. Audio Capture-Mode API (the one genuinely new integration)

`DeterministicAudioRuntime.drainPcm(...)` only yields PCM when a
`StreamBackedDeterministicAudioRuntime` is active (`providesPresentationPcm() ==
true`) and the gameplay audio frame is driven each step (the canonical
`GameLoop.step()` already does the latter — see §3). Two real obstacles block a
`com.openggf.tools` driver from simply turning this on:

- the runtime setter `setDeterministicAudioRuntime(...)` is **package-private**
  (`AudioManager.java:84`); and
- automatic installation only happens when the active backend reports
  `supportsDeterministicRuntimePresentation() == true` (`AudioManager.java:104`)
  — a headless/offscreen audio backend may not.

**Plan: add a concrete, public capture-mode API to `AudioManager`** (the one new
production surface this feature requires):

- `beginCaptureMode(int sampleRate, int frameRate)` — force-installs a
  `StreamBackedDeterministicAudioRuntime` (sized as in
  `configureDeterministicRuntimeForBackend`) **independent of the backend's
  `supportsDeterministicRuntimePresentation()`**, so headless capture works even
  with a null/offscreen backend. Snapshots the prior runtime for restore.
- `int drainCaptureFrame(short[] target)` — drains the current frame's
  presentation PCM and returns the stereo-frame count produced by the most recent
  NORMAL `advanceFrame` (the non-mutating produced count from §4.1).
- `endCaptureMode()` — reinstalls the previously-configured runtime and clears
  capture state.

`DrainPcmAudioTap` is a thin adapter over `drainCaptureFrame`, keeping the
capture subsystem decoupled from audio internals while giving the tool a public,
testable entry point. A dedicated capture/offscreen audio backend remains a
possible later refinement behind this same API. This is the main implementation
risk and should be validated first (a focused test: `beginCaptureMode` → drive N
canonical steps → assert each `drainCaptureFrame` returns the expected per-frame
sample count and total integrates to `sampleRate × seconds`).

## 6. Configuration

### 6.1 Trace-framework visibility flags (new `SonicConfiguration` keys)

Honored by **both** live Trace Test Mode and the recorder via
`TraceRenderVisibility`:

| Key | Default | Effect |
|-----|---------|--------|
| `TRACE_SHOW_DESYNC_GHOSTS` | `true` | Gates the single `GhostTraceRenderer` call site in the trace render path |
| `TRACE_SHOW_GAME_HUD` | `true` | Gates the game HUD (rings / score / time) render |
| `TRACE_SHOW_DEBUG_HUD` | `false` | Master gate for the debug overlay; when on, the existing per-element `DebugOverlayToggle` states (reused as-is) decide which panels render |

`TraceRenderVisibility` exposes these as three independent **master gates** and
does **not** mutate `DebugOverlayManager` (its constructor is private/singleton,
and overwriting toggle state would clobber the user's per-panel selection).
`showDebugHud()` gates whether the debug HUD renders at all; when true, the
existing per-element `DebugOverlayToggle` states are honored as-is at the render
site, so each panel stays independently on/off exactly as in interactive debug
mode. Driving per-panel selection from capture config in headless mode (where
there is no F-key input) is a follow-up; no new per-panel flag scheme is
introduced here.

### 6.2 Capture output options (CLI args, with config defaults)

| Arg | Default | Meaning |
|-----|---------|---------|
| `--trace <id\|dir>` | (required) | Which trace to record (resolved via `TraceCatalog`) |
| `--out-dir <dir>` | config `CAPTURE_OUTPUT_DIR` | Output directory |
| `--scale <n>` | `4` | Integer nearest-neighbor upscale factor |
| `--fps <n>` | engine `FPS` (60) | Output frame rate |
| `--codec <ffv1\|...>` | `ffv1` | Video codec (seam for future codecs) |

## 7. Encoder / ffmpeg Strategy

- **Default container/codec**: FFV1 video + lossless audio (FLAC or
  `pcm_s16le`) in **MKV** — fully bit-exact lossless.
- **Scaling + Y-flip** happen in the video encode pass (glReadPixels is
  bottom-up; keep the hot path a raw memcpy and let ffmpeg flip/scale):
  `-vf "vflip,scale=iw*N:ih*N:flags=neighbor"`.
- **Video encode (phase 1)**:
  `ffmpeg -f rawvideo -pix_fmt rgba -s {W}x{H} -r {fps} -i pipe:0
   -vf "vflip,scale=iw*N:ih*N:flags=neighbor" -c:v ffv1 video.mkv`
- **Mux (phase 2)**:
  `ffmpeg -i video.mkv -f s16le -ar 48000 -ac 2 -i audio.raw
   -c:v copy -c:a flac {final}.mkv`
- **Discovery**: locate `ffmpeg` on `PATH`; fail fast with a clear, actionable
  message if absent (capture is the only feature that needs it).

## 8. Output & Naming

- One file per CLI run (the headless analogue of the original "key to
  start/stop discrete files").
- Filename: `capture-<traceId>-<UTC:yyyyMMdd-HHmmss>.mkv` in the resolved output
  directory. Temp `video.mkv` / `audio.raw` live in a per-run temp dir and are
  deleted after a successful mux (kept on failure for diagnosis).

## 9. Error Handling

- **ffmpeg missing / non-zero exit**: surface stderr tail; do not leave a
  half-written final file (write to a temp name, atomically rename on success).
- **Encoder thread failure**: the producer's next `submit()`/`stop()` observes
  the failure and aborts the run with the captured cause (no silent partial
  files).
- **Queue bound**: a small fixed capacity (e.g. 8–16 frames). With `BLOCK`,
  this caps memory; the deterministic loop simply waits.
- **Trace bootstrap failure**: mirror `TraceSessionLauncher`'s teardown
  (restore config, release GL/ROM) so a failed run leaves no orphaned state.

## 10. Testing Strategy

- **Pure-unit, no GL/ffmpeg**:
  - Audio capture-mode cadence: `beginCaptureMode` → drive N canonical steps →
    each `drainCaptureFrame` returns that frame's produced count, the total
    integrates to `sampleRate × seconds`, the clock advances **once per frame**
    (no double-advance), and the tap never mutates the clock to re-count.
  - `EncoderSink` + `BackpressurePolicy.BLOCK`: producer blocks when full,
    resumes on drain, zero frames dropped; frame order preserved.
  - `CaptureRecorder` lifecycle against a fake `CaptureEncoder` (records calls):
    start → N submits → stop emits N video frames + N×sampleCount audio samples.
  - `TraceRenderVisibility`: each flag maps to the right master gate
    (`showGhosts`/`showGameHud`/`showDebugHud`); no `DebugOverlayManager`
    mutation.
- **Integration (guarded, opt-in like other ROM/visual tests)**:
  - Short headless trace capture (a few hundred frames) with a fake encoder that
    asserts exact frame/sample counts and monotonic frame indices — verifies the
    full step→render→grab→submit loop without invoking ffmpeg.
  - Optional ffmpeg smoke test (skipped when ffmpeg/ROM absent): produce a tiny
    real MKV and assert it is a valid, non-empty file.
- **Determinism**: capturing the same trace twice yields byte-identical
  intermediate frame/sample streams (hash the pre-encode `CapturedFrame` stream).

## 11. Build / Run

- Add a Maven exec / documented invocation for `TraceCaptureTool`
  (mirrors `VisualReferenceGenerator`'s CLI ergonomics).
- Requires the relevant ROM (per existing ROM rules) and `ffmpeg` on `PATH`.

## 12. Forward-Compatibility Notes

- **Realtime / non-headless** later: add a push-style driver that taps
  `Engine.display()`'s presented framebuffer and a live audio mix, choosing
  `BackpressurePolicy.DROP_OLDEST` (or `BLOCK` to throttle). The `capture`
  package, `CapturedFrame`, `FrameSink`, `CaptureEncoder`, and `CaptureRecorder`
  are reused unchanged; only new `VideoFrameGrabber` / `AudioFrameTap`
  implementations and a driver are added.
- **Hotkey start/stop** later: a key handler calls the same
  `start()`/`submit()`/`stop()` lifecycle to produce discrete timestamped files
  during interactive play.
- **Other codecs/containers** later: additional `CaptureEncoder` implementations
  selected by the existing `--codec` arg.
