# Live Viewport A/V Recording Design

## Summary

OpenGGF will expose its existing lossless FFmpeg capture stack during normal
windowed execution. A configurable chord, default `Shift+O`, starts and stops
recording. While active, the window shows a red dot and white `REC` label in
the top-right of the game viewport. The indicator is drawn after framebuffer
capture, so it never appears in the MKV or in F12 screenshots.

The recording contains only the game viewport. Window borders and
letterbox/pillarbox bars are excluded.

This is separate from the Shift+F9 BK2-style input/movie recording feature.

## Goals

- Toggle live A/V recording from any normally rendered engine mode after audio
  initialization.
- Export a playable, lossless MKV containing FFV1 video and FLAC stereo audio.
- Record the exact viewport presentation after the FINAL display-shader phase,
  including HUDs, debug overlays, pause, frame-step visuals, presentation
  shaders, and the VHS rewind effect.
- Record forward audio, explicit silence, and reverse-audio presentation with
  exactly one PCM packet for every submitted video frame.
- Keep speaker playback and the recorder independent: capture must never drain
  or replace the live speaker FIFO.
- Recover safely from missing FFmpeg, encoder failure, resize, shutdown, and
  repeated shortcut input.
- Correct the executable Git modes of `tools/bizhawk/record_trace.sh` and
  `tools/bizhawk/run_bizhawk_lua.sh` from `100644` to `100755`.

## Non-goals

- Replacing or changing input/movie recording and playback.
- Recording the `REC` indicator or window bars.
- Supporting a resolution change inside one MKV stream.
- Adding lossy codec selection, streaming, microphone input, or a recording
  browser.
- Changing trace-replay capture output or the headless `TraceCaptureTool`.

## User Experience

### Shortcut

`capture.toggleKey` defaults to `O`. Recording toggles on the rising edge of
the complete chord:

```text
configured key held AND Shift held AND Ctrl not held AND Alt not held
```

The order does not matter: Shift then O and O then Shift each toggle once when
the full chord becomes true. Holding the chord, GLFW repeat events, or adding
Ctrl/Alt while held do not retrigger it. The full chord must become false
before it can toggle again.

The shortcut remains distinct from `debug.recording.recordKey` (Shift+F9).

### Start and stop frames

- The rising edge is sampled before `GameLoop.step()` advances input history.
- Starting occurs before update/render; the same display frame is submitted
  and then receives the visible REC indicator.
- Stopping begins before update/render; the stop-edge frame is not submitted
  and has no indicator.
- A new start request is ignored while the previous file is finalizing.

### Indicator

- Projection-space placement: 8 px from the top and 8 px from the right edge
  of the current logical game viewport.
- A 10 px red filled dot precedes white `REC` text at 0.8 font scale.
- It is visible only in `ACTIVE`, not `STARTING`, `STOPPING`, or `FAILED`.
- It is drawn after viewport capture and after the existing F12 screenshot
  capture. Both MKV frames and screenshots remain indicator-free.

### Output

- Directory: existing `capture.outputDir`.
- Filename: `capture-live-<UTC yyyyMMdd-HHmmss-SSS>.mkv`.
- Video dimensions: the physical viewport width and height at start.
- Video rate: the engine's effective display rate from the shared
  `FrameRateResolver`: 50 Hz for PAL, otherwise configured
  `SonicConfiguration.FPS`.
- Video codec: FFV1; audio codec: FLAC stereo.
- Scaling: 1. The existing `capture.scale` remains trace-tool-only.

## Viewport Contract

The capture rectangle is fixed at start:

```text
(viewportX, viewportY, viewportWidth, viewportHeight)
```

`GlReadPixelsGrabber` reads that exact `GL_BACK` region. It validates positive
dimensions and overflow-safe `width * height * 4` allocation.

Before every submitted frame, Engine compares the current rectangle with the
start rectangle. Any origin or size change initiates a normal stop before
pixels are read. This covers framebuffer resize, integer-scale snapping,
projection/aspect changes, and viewport repositioning. A framebuffer callback
may request the stop early, but the per-frame comparison is authoritative.

## Presentation Audio Contract

### Why the offline path cannot be reused

`AudioManager.beginCaptureMode(...)` replaces the deterministic runtime and is
owned by offline trace capture. In a live engine, the recorder and speaker
backend would then compete for one FIFO. Live recording must attach a
non-consuming tap to the existing `StreamBackedDeterministicAudioRuntime`.

### Tap ownership

`AudioManager` owns at most one live capture handle. Starting capture:

1. Verifies that the active runtime provides deterministic presentation PCM.
2. Creates the tap using output sample rate and effective display rate.
3. Attaches it to the exact current runtime.

Stopping detaches from that same runtime instance. It never restores or
replaces an audio runtime. If the active runtime changes while recording,
drain reports a capture error and Engine stops the recording; it does not
silently attach to a new timeline.

### One packet per video frame

The tap owns an `AudioFrameClock(sampleRate, displayRate)`. Every capture call
advances this capture clock exactly once and returns exactly that many stereo
frames:

- **Fresh NORMAL presentation:** copy the latest final mixed PCM without
  consuming the speaker FIFO. Pad with silence or truncate by at most the
  clock-phase difference so the packet matches the capture clock.
- **Reverse presentation:** read the requested count through a fork of the
  active `PcmHistoryRing.ReverseCursor`. `ReverseCursor.fork()` copies the
  exact source position, rate, and oldest-history bound without advancing the
  speaker cursor. This also makes starting capture partway through an existing
  rewind begin at the sound currently heard, rather than newest history.
- **No fresh presentation PCM:** return an explicitly zero-filled packet.
  This covers user pause, frame-step `SILENT_STEP`, menus/modes that do not
  advance deterministic audio, and modal frames that skip `GameLoop.step()`.

The capture controller is the sole caller and drains exactly once immediately
before submitting each frame. Each drain is itself the presentation-frame
tick; there is no separate arming call that pause/modal modes could omit.
Draining consumes forward freshness. A second drain without a subsequent
NORMAL mix is silence. `SILENT_STEP` explicitly clears pending forward
freshness. If multiple NORMAL advances occur before one displayed frame, the
tap retains only the latest final mixed packet; one displayed frame still
produces one capture packet.

The tap's buffer is independent of `AudioOutputFifo`; tests must prove draining
capture leaves speaker PCM byte-identical.

### Rewind

On reverse-presentation start, the tap forks the live cursor.
`setReversePlaybackRate` updates both.
Each displayed rewind frame reads the capture clock's requested stereo-frame
count from the capture cursor. If history is exhausted, the cursor zero-fills
the tail and the tap still returns the full capture-clock count. On release,
the tap exits reverse mode without committing its cursor to live history; the
live cursor remains authoritative.

History clear increments the ring epoch, invalidates both cursors, clears tap
forward freshness, and makes the next capture packet silence until fresh
forward presentation exists. A stale cursor from an older epoch cannot read
new history.

### Shared effective frame rate

Add `com.openggf.configuration.FrameRateResolver.effective(SonicConfigurationService)`.
It returns 50 for PAL and `max(1, FPS)` otherwise. Engine scheduling, the live
deterministic runtime, the capture audio clock, and FFmpeg all use this one
value. This intentionally corrects the current PAL inconsistency where audio
uses 50 Hz but Engine schedules configured FPS.

## Components and Interfaces

### Runtime capture lease

The implementation tap remains package-private. A public runtime-owned lease
is the only cross-package type:

```java
public interface PresentationAudioCapture extends AutoCloseable {
    int sampleRate();
    int frameRate();
    int maxStereoFramesPerPacket();
    int drainPresentationFrame(short[] target);
    @Override void close();
}
```

`StreamBackedDeterministicAudioRuntime.openPresentationAudioCapture(...)`
returns the lease, mirrors final mixed NORMAL PCM into its private tap, and
forwards reverse lifecycle events. The lease validates
`target.length >= maxStereoFramesPerPacket() * 2`; it never silently truncates
to caller capacity.

### `AudioManager`

```java
public LiveCaptureAudioHandle beginLiveCaptureAudio(int frameRate);

public interface LiveCaptureAudioHandle extends AutoCloseable {
    int sampleRate();
    int frameRate();
    int maxStereoFramesPerPacket();
    int drainPresentationFrame(short[] target);
    @Override void close();
}
```

The handle captures the runtime lease and attached runtime identity. Calls
after close or a second simultaneous attach fail deterministically.
`AudioManager.applyDeterministicAudioRuntime(...)` synchronously invalidates
and closes an active handle before replacing/resetting the runtime. Closing an
already invalidated handle is idempotent and never detaches a newer runtime.

### `GlReadPixelsGrabber`

Preserve the two-argument constructor and add:

```java
public GlReadPixelsGrabber(int x, int y, int width, int height);
```

The four-argument constructor reads a fixed viewport region.

### `CaptureRecorder`

The existing recorder remains the encoder façade. Live capture uses
`BackpressurePolicy.BLOCK`, queue capacity 8, and `FfmpegEncoder(..., 1)`.
Existing worker failure and encoder abort behavior remain authoritative.

Add an explicit idempotent `abort()` through `CaptureRecorder` and
`EncoderSink`. `FfmpegEncoder` retains both its phase-1 video process and
phase-2 mux process as fields; `abort()` closes pipes, forcibly destroys
either live process, and removes temporary files. Graceful user/resize stop
uses `stop()`. Start/grab/drain/submit failure and finalizer timeout use
`abort()`. A failed partial output is deleted.

Stop/abort ownership is serialized by an internal recorder lifecycle lock.
Exactly one terminal operation owns cleanup. Concurrent abort marks the
terminal state, kills processes, and wakes a stop waiting on them; stop then
returns the abort failure. A worker calling abort never joins itself. All
process waits and worker joins are bounded, and both destroyed processes are
waited on before cleanup returns.

### `LiveCaptureRecorderFactory`

Production naming/path policy is isolated and clock-injected:

```java
public final class LiveCaptureRecorderFactory {
    public LiveCaptureRecorderFactory(
        SonicConfigurationService config, Clock clock, String ffmpeg);
    CaptureRecorder create(CaptureViewport viewport, int frameRate);
}
```

It resolves `capture.outputDir` and formats UTC
`capture-live-yyyyMMdd-HHmmss-SSS.mkv`, using BLOCK, capacity 8, and FFmpeg
scale 1. Tests inject a fixed UTC `Clock`.

### `LiveCaptureController`

```java
public final class LiveCaptureController implements AutoCloseable {
    public enum State { INACTIVE, STARTING, ACTIVE, STOPPING, FAILED }
    public enum StopReason { USER, VIEWPORT_CHANGED, CAPTURE_ERROR, SHUTDOWN }

    public void start(CaptureViewport viewport, int frameRate);
    public void capturePresentedFrame(CaptureViewport currentViewport);
    public void requestStop(StopReason reason);
    public State state();
    public Throwable lastFailure();
    public boolean indicatorVisible();
    @Override void close();
}
```

Dependencies are injected as narrow audio-handle, grabber, recorder, clock,
and finalizer-executor factories. Unit tests use fakes; Engine constructs the
production factories.

Engine owns `LiveCaptureChord`. When `LiveCaptureChord.update(...)` reports a
rising edge, Engine calls `controller.requestStop(USER)` if ACTIVE, calls
`controller.start(currentViewport, effectiveFrameRate)` if INACTIVE or FAILED,
and ignores the edge while STARTING or STOPPING. The controller therefore
never owns raw input state.

State transitions:

```text
INACTIVE -> STARTING -> ACTIVE -> STOPPING -> INACTIVE
                    \-> FAILED -> STARTING (retry after cleanup is terminal)
ACTIVE -> STOPPING -> FAILED (finalizer/worker/mux failure)
```

- Start failures abort/close any resource already acquired in reverse order.
- Frame grab, audio drain, or submit failure stops accepting frames, hides the
  indicator, detaches audio immediately, and aborts/finalizes the encoder.
- Normal stop detaches audio immediately and runs blocking recorder
  finalization on a single background finalizer.
- Shutdown waits at most 10 seconds for finalization and then calls the
  explicit abort path, including a live phase-2 mux process.
- All close/stop/error paths are idempotent.
- `STOPPING` owns one terminal cleanup future. No start can create a recorder
  until that future is terminal. A finalizer/worker/mux failure transitions
  STOPPING to FAILED only after cleanup completes.
- `FAILED` persists with `lastFailure()` and no indicator. A new chord/start
  request may clear the prior failure and retry through STARTING only when the
  cleanup future is terminal. `close()` clears FAILED to INACTIVE.
- If abort races graceful stop, the serialized recorder terminal operation
  makes abort authoritative; the controller observes one terminal failure and
  never launches overlapping cleanup.

### `Engine`

Engine remains the composition root:

1. Sample chord before `update()`.
2. Update and render normally.
3. Apply FINAL display shader.
4. Capture viewport pixels and presentation PCM; submit the atomic
   `CapturedFrame`.
5. Run existing F12 screenshot capture.
6. Draw window-only REC indicator.
7. Return to the outer loop, which immediately swaps buffers.

`LiveCapturePresentationCoordinator.present(viewport, screenshot, indicator)`
is an injected, package-testable seam that calls controller capture exactly
once, then screenshot, then indicator. Engine calls it unconditionally after
FINAL for every rendered mode. Executable tests pin the order and one-submit
semantics; a narrow source guard pins FINAL before the coordinator and
`display()` immediately before `glfwSwapBuffers`.

## Failure Handling

| Failure | User-visible behavior | Cleanup |
| --- | --- | --- |
| FFmpeg missing | Warning log; no indicator | Nothing attached |
| Tap unsupported/already active | Warning log; no indicator | Acquired resources closed |
| Encoder open fails | Warning log; no indicator | Encoder abort + tap detach |
| Viewport changes | Normal stop | Tap detach + async finalize |
| Grab/audio/submit fails | Warning log; indicator clears | Tap detach + abort/finalize |
| Encoder worker/mux fails | Warning log after finalizer completes | Temp files/process cleaned by encoder |
| Repeated stop/close | No-op | No double detach/finalize |
| Engine shutdown | Wait up to 10 seconds | Explicit abort kills video/mux processes |

## Configuration and Documentation

Add:

```yaml
capture:
  toggleKey: O
```

Update `ConfigCatalog`, `SonicConfiguration`,
`SonicConfigurationService`, generated/default YAML, `CONFIGURATION.md`,
`README.md`, and `CHANGELOG.md`. Clarify that `capture.scale`, `capture.fps`,
and `capture.codec` remain trace-tool settings; live capture uses shared effective
engine FPS, scale 1, FFV1, and FLAC.

## Verification

### Unit tests

- Chord: modifier-first, key-first, held/repeat, release/repress, Ctrl/Alt,
  unrelated key, and Shift+F9 independence.
- Audio tap: exact 48 kHz/60 Hz and 44.1 kHz/60 Hz packet totals; forward copy;
  silence; destination capacity; speaker FIFO non-consumption; reverse begin,
  attach-mid-rewind fork, rate, exhaustion padding, release, epoch invalidation,
  and history clear.
- Shared frame rate: PAL resolves to 50 for Engine, runtime, tap, and encoder;
  non-PAL uses configured FPS.
- AudioManager: unsupported runtime, double attach, close idempotence, and
  runtime replacement.
- Grabber: origin, dimensions, validation, and overflow.
- Controller: every state transition, stop/abort race, terminal-cleanup gate,
  and failure injection point.
- Recorder factory: fixed UTC name, configured directory, BLOCK/capacity 8,
  scale 1.
- Executable presentation-order test plus narrow FINAL/display/swap guard.
- Configuration default and YAML/catalog round-trip.

### Runtime-mode integration

Using the real presentation coordinator with fake controller dependencies,
prove one atomic frame per display in:

- Level play.
- User pause.
- Frame-step.
- Held and coast live rewind, then release.
- Special stage and bonus stage.
- Title/menu and editor.
- Modal display-shader picker frame.
- Viewport-change stop and engine shutdown.

### Media smoke test

When FFmpeg and ffprobe are on `PATH`, create a short viewport recording with a
known moving pixel marker and known stereo tone, including silence and reverse
segments. Assert:

- MKV exists and is non-empty.
- Video codec is FFV1; audio codec is FLAC stereo.
- Width/height equal the viewport.
- Frame count equals submitted count.
- Audio duration differs from `frameCount / frameRate` by no more than one
  audio sample.
- Decoded frame pixels match the submitted pre-indicator buffer. Indicator
  exclusion is proven separately through the executable Engine action-order
  seam, not inferred from direct encoder input.

An optional ROM-backed manual check records forward play, pause, frame-step,
held rewind, release, and resize, then plays the output.

## Acceptance Criteria

1. The complete configured chord toggles exactly once per rising edge.
2. Start and stop do not interfere with Shift+F9 input/movie recording.
3. Every submitted viewport frame has an exact-duration stereo PCM packet.
4. Speaker PCM is unchanged by capture drains.
5. Rewind video and independently drained reverse audio are captured.
6. Pause, frame-step, menus, editor, stages, and modal frames use fresh PCM or
   explicit silence—never stale PCM.
7. MKV and F12 screenshots exclude the REC indicator.
8. Any viewport change stops before a mismatched frame is submitted.
9. Failures and shutdown leave no active tap, encoder worker, FFmpeg video or
   mux process,
   or visible indicator.
10. FFmpeg/ffprobe verification proves lossless codecs, dimensions, frame
    count, duration sync, and indicator exclusion.
11. Both BizHawk shell launchers are committed with mode `100755`.

## Rollback

Removing the Engine/controller/config integration returns capture to
headless-only use. The audio tap is additive and does not alter speaker FIFO
ownership. The existing offline `beginCaptureMode` API remains unchanged.
