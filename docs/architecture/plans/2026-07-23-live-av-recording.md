# Live Viewport A/V Recording Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a configurable live viewport A/V recorder with synchronized forward/reverse audio and a window-only REC indicator.

**Architecture:** Engine samples a complete-chord edge and owns the render seam, while a testable `LiveCaptureController` owns lifecycle and atomic frame submission. A non-consuming tap attached to the existing deterministic audio runtime supplies one exact-duration PCM packet per video frame, including independent reverse-history reads and explicit silence. The existing FFmpeg recorder encodes the fixed viewport before Engine draws the REC indicator.

**Tech Stack:** Java 17, LWJGL/OpenGL, JUnit 5, Maven, FFmpeg/ffprobe, existing `com.openggf.capture` and deterministic rewind-audio runtime.

## Global Constraints

- Authoritative design: `docs/architecture/designs/2026-07-23-live-av-recording-design.md`.
- Capture only `(viewportX, viewportY, viewportWidth, viewportHeight)`; exclude window bars.
- Never call or modify the semantics of offline `AudioManager.beginCaptureMode(...)` for live capture.
- Never consume or replace the speaker presentation FIFO/runtime.
- Submit exactly one stereo PCM packet, sized by a capture-owned `AudioFrameClock`, with every video frame.
- Capture after FINAL display shader and before screenshots/REC indicator; screenshots remain indicator-free.
- Use JUnit 5 only.
- Preserve unrelated worktree changes and linked disassembly resources.
- Each implementation task receives an independent plan-compliance review and code-quality review before the next dependent task.
- Execution starts from the committed spec/plan baseline with no tracked
  prototype changes. Before each task, `git status --short --untracked-files=no`
  must be empty; linked untracked disassembly paths are ignored.

---

## File and ownership map

| File | Responsibility |
| --- | --- |
| `audio/runtime/LiveAudioCaptureTap.java` | Capture-owned clock, forward PCM mirror, silence, independent reverse cursor |
| `audio/runtime/PresentationAudioCapture.java` | Public runtime-owned lease; keeps tap implementation private |
| `audio/runtime/StreamBackedDeterministicAudioRuntime.java` | Attach/detach tap and forward presentation/reverse lifecycle events |
| `audio/AudioManager.java` | Public runtime-identity-safe live audio handle |
| `configuration/FrameRateResolver.java` | One PAL/non-PAL cadence used by Engine, audio, and capture |
| `capture/CaptureViewport.java` | Immutable fixed viewport rectangle |
| `capture/GlReadPixelsGrabber.java` | Read exact fixed GL_BACK region with validated sizing |
| `capture/LiveCaptureChord.java` | Complete-chord rising-edge state machine |
| `capture/LiveCaptureController.java` | Capture state, resources, submission, async finalization |
| `capture/LiveCaptureIndicatorRenderer.java` | Final window-only red-dot/white-REC pass |
| `Engine.java` | Composition, input seam, capture ordering, viewport/shutdown hooks |
| Configuration/YAML/docs | Shortcut discovery and live-vs-trace capture distinction |

---

### Task 1: Preserve executable launcher modes

**Status:** Completed in baseline commit `61190cb2d`; execution subagents start
at Task 2.

**Files:**
- Mode change: `tools/bizhawk/record_trace.sh`
- Mode change: `tools/bizhawk/run_bizhawk_lua.sh`

**Interfaces:**
- Consumes: Existing shell launchers.
- Produces: Git index mode `100755` for direct invocation.

- [x] **Step 1: Verify the pre-change mode**

Run:

```bash
git ls-files -s tools/bizhawk/record_trace.sh tools/bizhawk/run_bizhawk_lua.sh
```

Expected before the staged change: both entries begin with `100644`.

- [x] **Step 2: Record executable modes**

Run:

```bash
chmod +x tools/bizhawk/record_trace.sh tools/bizhawk/run_bizhawk_lua.sh
git update-index --chmod=+x tools/bizhawk/record_trace.sh tools/bizhawk/run_bizhawk_lua.sh
```

- [x] **Step 3: Verify direct execution reaches argument validation**

Run:

```bash
tools/bizhawk/record_trace.sh --help
tools/bizhawk/run_bizhawk_lua.sh --help
```

Expected: neither command fails with `Permission denied`. Their existing help/argument exit behavior is otherwise unchanged.

- [x] **Step 4: Verify the staged mode**

Run:

```bash
git ls-files -s tools/bizhawk/record_trace.sh tools/bizhawk/run_bizhawk_lua.sh
git diff --cached --summary -- tools/bizhawk/record_trace.sh tools/bizhawk/run_bizhawk_lua.sh
```

Expected: `100755` and two `mode change 100644 => 100755` lines.

- [x] **Step 5: Commit**

```bash
git commit -m "fix(tooling): make BizHawk shell launchers executable

Changelog: n/a: file-mode correction only
Guide: n/a
Known-Discrepancies: n/a
S3K-Known-Discrepancies: n/a
Agent-Docs: n/a
Configuration-Docs: n/a
Skills: n/a"
```

---

### Task 2: Build the presentation-frame audio tap

**Files:**
- Create: `src/main/java/com/openggf/configuration/FrameRateResolver.java`
- Create: `src/test/java/com/openggf/configuration/FrameRateResolverTest.java`
- Create: `src/main/java/com/openggf/audio/runtime/PresentationAudioCapture.java`
- Create: `src/main/java/com/openggf/audio/runtime/LiveAudioCaptureTap.java`
- Modify: `src/main/java/com/openggf/audio/runtime/StreamBackedDeterministicAudioRuntime.java`
- Modify: `src/main/java/com/openggf/audio/runtime/PcmHistoryRing.java`
- Create: `src/test/java/com/openggf/audio/runtime/TestLiveAudioCaptureTap.java`
- Modify: `src/test/java/com/openggf/audio/runtime/TestStreamBackedDeterministicAudioRuntime.java`
- Modify: `src/test/java/com/openggf/audio/runtime/TestPcmHistoryRing.java`

**Interfaces:**
- Consumes: `AudioFrameClock`, `PcmHistoryRing`, final mixed `musicScratch`, reverse lifecycle/rate.
- Produces:

```java
public final class FrameRateResolver {
    public static int effective(SonicConfigurationService config);
}
public interface PresentationAudioCapture extends AutoCloseable {
    int sampleRate();
    int frameRate();
    int maxStereoFramesPerPacket();
    int drainPresentationFrame(short[] target);
    @Override void close();
}
PresentationAudioCapture openPresentationAudioCapture(int sampleRate, int frameRate);
PcmHistoryRing.ReverseCursor PcmHistoryRing.ReverseCursor.fork();
```

- [ ] **Step 1: Write failing shared frame-rate tests**

Create `FrameRateResolverTest` asserting PAL resolves to 50 even when FPS is
60, NTSC resolves configured FPS, and zero/negative loaded FPS is sanitized by
the configuration service before resolution.

- [ ] **Step 2: Run the resolver test and observe failure**

```bash
mvn -Dtest=FrameRateResolverTest test
```

Expected: FAIL because `FrameRateResolver` does not exist.

- [ ] **Step 3: Implement the resolver**

```java
public static int effective(SonicConfigurationService config) {
    return "PAL".equalsIgnoreCase(config.getString(SonicConfiguration.REGION))
            ? 50
            : Math.max(1, config.getInt(SonicConfiguration.FPS));
}
```

- [ ] **Step 4: Write failing clock, forward-copy, and silence tests**

Create `TestLiveAudioCaptureTap` tests that:

```java
@Test void sixtyVideoFramesAt48000HzProduceExactly48000StereoFrames()
@Test void sixtyVideoFramesAt44100HzProduceExactly44100StereoFrames()
@Test void freshForwardPcmIsCopiedAndPaddedToTheCaptureClockCount()
@Test void noFreshForwardPcmProducesZeroFilledPacket()
@Test void drainConsumesFreshnessAndSecondDrainIsSilence()
@Test void normalThenSilentStepThenDrainIsSilence()
@Test void multipleNormalAdvancesRetainOnlyLatestPacket()
@Test void undersizedDestinationIsRejectedBeforeClockAdvances()
```

Drive exactly one `drainPresentationFrame` per video frame. Assert 60 drains
total the exact sample rate and every unwritten sample is zero.

- [ ] **Step 5: Run the new tests and observe failure**

Run:

```bash
mvn -Dtest=TestLiveAudioCaptureTap test
```

Expected: FAIL because `LiveAudioCaptureTap` does not exist.

- [ ] **Step 6: Implement the private tap and public lease**

Implement:

```java
final class LiveAudioCaptureTap {
    LiveAudioCaptureTap(int sampleRate, int frameRate) { ... }
    void acceptForwardPcm(short[] pcm, int stereoFrames) { ... }
    int drainPresentationFrame(short[] target) { ... }
}
```

The public `PresentationAudioCapture` lease delegates to this private tap.
`drainPresentationFrame` calls its own
`AudioFrameClock.samplesForNextFrame()`, validates against
`maxStereoFramesPerPacket() * 2`, zero-fills the requested window, then copies
`min(requested, available)` stereo frames.

- [ ] **Step 7: Write failing non-consumption, cursor-fork, and reverse tests**

Add runtime tests:

```java
@Test void liveTapDrainLeavesSpeakerFifoByteIdentical()
@Test void liveTapUsesIndependentReverseCursor()
@Test void attachingAfterSpeakerAdvancedReverseForksExactCursorPosition()
@Test void reverseRateIsMirroredToCaptureCursor()
@Test void captureCursorDoesNotCommitLiveHistoryOnRelease()
@Test void exhaustedReverseHistoryReturnsFullZeroPaddedClockCount()
@Test void historyClearInvalidatesCaptureCursorEpochAndReturnsSilence()
@Test void secondLeaseIsRejectedAndCloseIsIdempotent()
```

Use small distinct stereo sequences so forward/reverse ordering is exact.

- [ ] **Step 8: Run and observe the reverse tests fail**

Run:

```bash
mvn -Dtest=TestStreamBackedDeterministicAudioRuntime,TestLiveAudioCaptureTap test
```

Expected: new reverse/attach tests FAIL.

- [ ] **Step 9: Implement fork/epoch and integrate the tap**

Add one attached-tap field. On `FrameAudioMode.NORMAL`, call
`acceptForwardPcm` only after SFX mixing and before/after FIFO write without
draining the FIFO. Forward `beginReversePresentation`,
`setReversePlaybackRate`, `endReversePresentation`, and `clearPcmHistory` to
the tap. `SILENT_STEP` clears forward freshness; every drain consumes it, and
a later NORMAL replaces rather than queues an undisplayed packet. Add a ring
epoch captured by every cursor. `fork()` copies the live
cursor's source position/rate/bounds/epoch. Reads through a stale epoch produce
zero, and the tap always reports its clock-requested count. The capture cursor
never calls `commitReverseCursor`.

- [ ] **Step 10: Run the focused audio suite**

Run:

```bash
mvn -Dtest=FrameRateResolverTest,TestLiveAudioCaptureTap,TestStreamBackedDeterministicAudioRuntime,StreamBackedRuntimeProducedCountTest,TestPcmHistoryRing test
```

Expected: PASS, zero failures/errors.

- [ ] **Step 11: Stage exact scope and commit**

```bash
git add -- src/main/java/com/openggf/configuration/FrameRateResolver.java \
  src/test/java/com/openggf/configuration/FrameRateResolverTest.java \
  src/main/java/com/openggf/audio/runtime/PresentationAudioCapture.java \
  src/main/java/com/openggf/audio/runtime/LiveAudioCaptureTap.java \
  src/main/java/com/openggf/audio/runtime/StreamBackedDeterministicAudioRuntime.java \
  src/main/java/com/openggf/audio/runtime/PcmHistoryRing.java \
  src/test/java/com/openggf/audio/runtime/TestLiveAudioCaptureTap.java \
  src/test/java/com/openggf/audio/runtime/TestStreamBackedDeterministicAudioRuntime.java \
  src/test/java/com/openggf/audio/runtime/TestPcmHistoryRing.java
git diff --cached --name-only
```

Expected: exactly the nine files above.

```bash
git commit -m "feat(audio): add non-consuming presentation capture tap

Changelog: n/a: documented with the final integrated live-capture commit
Guide: n/a
Known-Discrepancies: n/a
S3K-Known-Discrepancies: n/a
Agent-Docs: n/a
Configuration-Docs: n/a
Skills: n/a"
```

---

### Task 3: Expose a runtime-safe AudioManager capture handle

**Files:**
- Create: `src/main/java/com/openggf/audio/LiveCaptureAudioHandle.java`
- Modify: `src/main/java/com/openggf/audio/AudioManager.java`
- Create: `src/test/java/com/openggf/audio/AudioManagerLiveCaptureTest.java`

**Interfaces:**
- Consumes:

```java
PresentationAudioCapture
StreamBackedDeterministicAudioRuntime.openPresentationAudioCapture(int, int);
FrameRateResolver.effective(SonicConfigurationService);
```

- Produces:

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

- [ ] **Step 1: Write failing AudioManager handle tests**

Cover:

```java
@Test void beginsDrainsAndIdempotentlyClosesLiveHandle()
@Test void rejectsNoOpOrUnsupportedRuntime()
@Test void rejectsSecondSimultaneousHandle()
@Test void handleRejectsDrainAfterClose()
@Test void handleRejectsDrainAfterRuntimeReplacement()
```

Use package-visible runtime injection already used by audio tests; never boot OpenGL.

- [ ] **Step 2: Run and observe failure**

```bash
mvn -Dtest=AudioManagerLiveCaptureTest test
```

Expected: FAIL because the handle/API does not exist.

- [ ] **Step 3: Implement identity-safe attach/drain/close**

The handle stores the exact `StreamBackedDeterministicAudioRuntime` and public
runtime lease. Every drain checks
`deterministicAudioRuntime == attachedRuntime`. `close()` idempotently closes
the lease. `applyDeterministicAudioRuntime` synchronously invalidates/closes
the active handle before replacement and clears the reference only for that
handle. Replace `configuredFrameRate()`'s duplicated PAL logic with
`FrameRateResolver.effective(config)`.

- [ ] **Step 4: Run focused AudioManager tests**

```bash
mvn -Dtest=AudioManagerLiveCaptureTest,AudioManagerCaptureModeTest,TestAudioManagerRewindSuppression test
```

Expected: PASS and existing offline capture behavior unchanged.

- [ ] **Step 5: Stage exact scope and commit**

```bash
git add -- src/main/java/com/openggf/audio/LiveCaptureAudioHandle.java \
  src/main/java/com/openggf/audio/AudioManager.java \
  src/test/java/com/openggf/audio/AudioManagerLiveCaptureTest.java
git diff --cached --name-only
```

Expected: exactly those three files.

```bash
git commit -m "feat(audio): expose live capture audio handle

Changelog: n/a: documented with the final integrated live-capture commit
Guide: n/a
Known-Discrepancies: n/a
S3K-Known-Discrepancies: n/a
Agent-Docs: n/a
Configuration-Docs: n/a
Skills: n/a"
```

---

### Task 4: Validate and capture a fixed viewport region

**Files:**
- Create: `src/main/java/com/openggf/capture/CaptureViewport.java`
- Modify: `src/main/java/com/openggf/capture/GlReadPixelsGrabber.java`
- Modify: `src/test/java/com/openggf/capture/GlReadPixelsGrabberTest.java`
- Create: `src/test/java/com/openggf/capture/CaptureViewportTest.java`

**Interfaces:**
- Produces:

```java
public record CaptureViewport(int x, int y, int width, int height) {
    public int rgbaByteSize();
}
public GlReadPixelsGrabber(int x, int y, int width, int height);
```

- [ ] **Step 1: Write failing validation/overflow tests**

Assert zero/negative width or height throws `IllegalArgumentException`.
Assert `new CaptureViewport(0, 0, Integer.MAX_VALUE, 2).rgbaByteSize()`
throws with a clear overflow message. Assert ordinary `320x224` returns
`286720`. In `GlReadPixelsGrabberTest`, add a package-visible GL-read callback
fake and assert `(13,17,320,224)` is forwarded unchanged. Add an
assumption-gated offscreen GL test that clears colored bars around a
non-zero-origin viewport and expects the grabbed corners to contain viewport
colors rather than bar colors.

- [ ] **Step 2: Run and observe failure**

```bash
mvn -Dtest=CaptureViewportTest,GlReadPixelsGrabberTest test
```

Expected: FAIL because `CaptureViewport` and origin constructor are absent.

- [ ] **Step 3: Implement the record and region constructor**

Use `Math.multiplyExact(Math.multiplyExact(width, height), 4)` for byte size.
Keep `GlReadPixelsGrabber(width,height)` delegating to `(0,0,width,height)`.
Call `glReadPixels(x, y, width, height, ...)`.

- [ ] **Step 4: Add the minimal GL-call seam and non-zero-origin integration**

Extract only the minimal package-visible read-region invocation seam needed to
assert `(x,y,width,height)` is passed unchanged; do not introduce a general GL
abstraction. Make the previously written offscreen test pass.

- [ ] **Step 5: Run capture primitive tests**

```bash
mvn -Dtest=CaptureViewportTest,GlReadPixelsGrabberTest,CapturedFrameTest test
```

Expected: PASS.

- [ ] **Step 6: Stage exact scope and commit**

```bash
git add -- src/main/java/com/openggf/capture/CaptureViewport.java \
  src/main/java/com/openggf/capture/GlReadPixelsGrabber.java \
  src/test/java/com/openggf/capture/CaptureViewportTest.java \
  src/test/java/com/openggf/capture/GlReadPixelsGrabberTest.java
git diff --cached --name-only
```

Expected: exactly those four files.

```bash
git commit -m "feat(capture): support fixed viewport frame grabs

Changelog: n/a: documented with the final integrated live-capture commit
Guide: n/a
Known-Discrepancies: n/a
S3K-Known-Discrepancies: n/a
Agent-Docs: n/a
Configuration-Docs: n/a
Skills: n/a"
```

---

### Task 5: Implement chord and capture lifecycle controllers

**Files:**
- Create: `src/main/java/com/openggf/capture/LiveCaptureChord.java`
- Create: `src/main/java/com/openggf/capture/LiveCaptureController.java`
- Create: `src/main/java/com/openggf/capture/LiveCaptureRecorderFactory.java`
- Modify: `src/main/java/com/openggf/capture/CaptureRecorder.java`
- Modify: `src/main/java/com/openggf/capture/EncoderSink.java`
- Modify: `src/main/java/com/openggf/capture/FfmpegEncoder.java`
- Create: `src/test/java/com/openggf/capture/LiveCaptureChordTest.java`
- Create: `src/test/java/com/openggf/capture/LiveCaptureControllerTest.java`
- Create: `src/test/java/com/openggf/capture/LiveCaptureRecorderFactoryTest.java`
- Modify: `src/test/java/com/openggf/capture/CaptureRecorderTest.java`
- Modify: `src/test/java/com/openggf/capture/EncoderSinkTest.java`
- Modify: `src/test/java/com/openggf/capture/FfmpegEncoderCommandTest.java`

**Interfaces:**
- Consumes: `CaptureViewport`, `LiveCaptureAudioHandle`, `VideoFrameGrabber`,
  `CaptureRecorder`, a single-thread finalizer `Executor`.
- Produces:

```java
boolean LiveCaptureChord.update(boolean keyDown, boolean shiftDown,
                                boolean controlDown, boolean altDown);

public enum LiveCaptureController.State { INACTIVE, STARTING, ACTIVE, STOPPING, FAILED }
public enum LiveCaptureController.StopReason {
    USER, VIEWPORT_CHANGED, CAPTURE_ERROR, SHUTDOWN
}
public record LiveCaptureController.Dependencies(
    AudioHandleFactory audio,
    FrameGrabberFactory grabber,
    RecorderFactory recorder,
    ExecutorService finalizer,
    Duration shutdownTimeout) {}
public interface AudioHandleFactory {
    LiveCaptureAudioHandle open(int frameRate);
}
public interface FrameGrabberFactory {
    VideoFrameGrabber create(CaptureViewport viewport);
}
public interface RecorderFactory {
    CaptureRecorder create(CaptureViewport viewport, int frameRate);
}
public void start(CaptureViewport viewport, int frameRate);
public void capturePresentedFrame(CaptureViewport currentViewport);
public void requestStop(StopReason reason);
public State state();
public Throwable lastFailure();
public boolean indicatorVisible();
public void close();
void CaptureRecorder.abort();
```

- [ ] **Step 1: Write the full chord matrix**

Tests must exercise Shift-first, key-first, held/repeat, Ctrl/Alt suppression,
release/repress, modifier removal while key remains held, and the default O
versus separate F9 input-recording key.

- [ ] **Step 2: Run chord tests and observe failure**

```bash
mvn -Dtest=LiveCaptureChordTest test
```

Expected: FAIL because `LiveCaptureChord` does not exist.

- [ ] **Step 3: Implement complete-chord rising edge**

Store `previousComplete`. Return `complete && !previousComplete`, then assign
`previousComplete = complete`. Do not depend on GLFW repeat edges.

- [ ] **Step 4: Write lifecycle and failure-injection tests**

Cover successful start/frame/normal async stop plus failure at:

- audio-handle acquisition;
- recorder factory/open;
- framebuffer grab;
- audio drain;
- submit/encoder worker observation;
- final mux;
- audio close;
- finalizer timeout killing both FFmpeg processes;
- repeated stop/close;
- start while STOPPING;
- retry from FAILED;
- mismatched viewport before grab.
- fixed UTC recorder naming and configured output directory.

Assert resource acquisition is reversed, indicator is visible only in ACTIVE,
frame indexes are monotonic, and viewport mismatch submits nothing.

- [ ] **Step 5: Run controller tests and observe failure**

```bash
mvn -Dtest=LiveCaptureControllerTest test
```

Expected: new lifecycle tests FAIL against the prototype.

- [ ] **Step 6: Add explicit recorder abort and bounded process ownership**

Expose idempotent `abort()` through `CaptureRecorder` and `EncoderSink`.
Retain both `videoProc` and `muxProc` as `FfmpegEncoder` fields. Guard
open/finish/abort with an internal terminal-state lock. Abort closes
stdin/audio output, destroys either live process, interrupts/joins the worker,
deletes temp files, and deletes a partial final output. Add a package-visible
process-launch seam so tests use fake never-exiting processes rather than real
OS leaks. Tests cover concurrent stop+abort, worker-self-abort without
self-join, video hang, mux hang, bounded destroy/wait, and partial/temp cleanup.

- [ ] **Step 7: Implement the explicit controller state machine**

Use the exact `Dependencies` record above. Allocate PCM as
`new short[audioHandle.maxStereoFramesPerPacket() * 2]`. Detach audio on the
caller thread as soon as ACTIVE ends. Run `CaptureRecorder.stop()` on the
finalizer. A finalization failure transitions to FAILED and stores
`lastFailure` only after terminal cleanup. FAILED persists without an
indicator. `start()` from FAILED clears the failure and retries through
STARTING only after cleanup is terminal. Start while STOPPING is ignored. If
abort races stop, abort is authoritative and only one terminal result is
published. `close()` waits `shutdownTimeout` (production: 10 seconds), then
calls abort and shuts down the finalizer; it leaves state INACTIVE.

Implement `LiveCaptureRecorderFactory(config, clock, ffmpeg)` with a fixed UTC
clock seam. `create(viewport, frameRate)` resolves `capture.outputDir`, uses
label `live`, timestamp `yyyyMMdd-HHmmss-SSS`, BLOCK, capacity 8, and
`FfmpegEncoder(ffmpeg, 1)`.

- [ ] **Step 8: Run all capture controller tests**

```bash
mvn -Dtest=LiveCaptureChordTest,LiveCaptureControllerTest,LiveCaptureRecorderFactoryTest,CaptureRecorderTest,EncoderSinkTest,FfmpegEncoderCommandTest test
```

Expected: PASS.

- [ ] **Step 9: Stage exact scope and commit**

```bash
git add -- src/main/java/com/openggf/capture/LiveCaptureChord.java \
  src/main/java/com/openggf/capture/LiveCaptureController.java \
  src/main/java/com/openggf/capture/LiveCaptureRecorderFactory.java \
  src/main/java/com/openggf/capture/CaptureRecorder.java \
  src/main/java/com/openggf/capture/EncoderSink.java \
  src/main/java/com/openggf/capture/FfmpegEncoder.java \
  src/test/java/com/openggf/capture/LiveCaptureChordTest.java \
  src/test/java/com/openggf/capture/LiveCaptureControllerTest.java \
  src/test/java/com/openggf/capture/LiveCaptureRecorderFactoryTest.java \
  src/test/java/com/openggf/capture/CaptureRecorderTest.java \
  src/test/java/com/openggf/capture/EncoderSinkTest.java \
  src/test/java/com/openggf/capture/FfmpegEncoderCommandTest.java
git diff --cached --name-only
```

Expected: exactly those twelve files.

```bash
git commit -m "feat(capture): coordinate live recording lifecycle

Changelog: n/a: documented with the final integrated live-capture commit
Guide: n/a
Known-Discrepancies: n/a
S3K-Known-Discrepancies: n/a
Agent-Docs: n/a
Configuration-Docs: n/a
Skills: n/a"
```

---

### Task 6: Integrate configuration, Engine ordering, and REC rendering

**Files:**
- Modify: `src/main/java/com/openggf/configuration/SonicConfiguration.java`
- Modify: `src/main/java/com/openggf/configuration/ConfigCatalog.java`
- Modify: `src/main/java/com/openggf/configuration/SonicConfigurationService.java`
- Modify: `src/main/resources/config.yaml`
- Modify: `src/test/java/com/openggf/configuration/CaptureConfigDefaultsTest.java`
- Create: `src/main/java/com/openggf/capture/LiveCaptureIndicatorRenderer.java`
- Create: `src/main/java/com/openggf/capture/LiveCapturePresentationCoordinator.java`
- Modify: `src/main/java/com/openggf/Engine.java`
- Modify: `src/test/java/com/openggf/TestGameLoop.java`
- Create: `src/test/java/com/openggf/TestEngineLiveCapturePresentation.java`
- Modify: `src/test/java/com/openggf/tests/TestArchitecturalSourceGuard.java`
- Create: `src/test/java/com/openggf/capture/LiveCaptureIndicatorRendererTest.java`
- Create: `src/test/java/com/openggf/capture/LiveCapturePresentationCoordinatorTest.java`

**Interfaces:**
- Consumes all Tasks 2–5 interfaces.
- Produces `capture.toggleKey` default `GLFW_KEY_O` and Engine order:

```text
FINAL -> live viewport capture -> F12 screenshot -> REC indicator -> swap
```

- [ ] **Step 1: Add failing configuration assertions**

Assert standalone defaults resolve `CAPTURE_TOGGLE_KEY` to `GLFW_KEY_O`.
Parse the resource YAML/catalog through the existing configuration loader and
assert the flattened `capture.toggleKey` resolves to O; do not search for a
nonexistent literal dotted YAML line.

- [ ] **Step 2: Add failing Engine order/source assertions**

Add an executable test for:

```java
new LiveCapturePresentationCoordinator(controller)
    .present(viewport, screenshot, indicator)
```

and assert callback order `capture, screenshot, indicator`, including when
capture is inactive/no-op. Extend the narrow source guard to assert FINAL
precedes the coordinator call, `handleLiveCaptureShortcut` precedes
`update()`, and `loop()` invokes `display()` immediately before
`glfwSwapBuffers`.

- [ ] **Step 3: Run and observe failures**

```bash
mvn -Dtest=CaptureConfigDefaultsTest,TestArchitecturalSourceGuard,LiveCaptureIndicatorRendererTest test
```

Expected: FAIL for missing configuration/renderer/order.

- [ ] **Step 4: Add configuration entries**

Add `CAPTURE_TOGGLE_KEY`, catalog metadata at `capture.toggleKey`, default
`GLFW_KEY_O`, and resource YAML. Do not repurpose `RECORDING_RECORD_KEY`.

- [ ] **Step 5: Implement the focused indicator renderer**

Render a 10 px red filled dot and white `REC` at 0.8 scale, with an 8 px
top/right projection-space margin. Inject primitive/text sinks so unit tests
assert geometry and colors without OpenGL.

- [ ] **Step 6: Write failing Engine integration/mode tests**

In `LiveCapturePresentationCoordinatorTest` and
`TestEngineLiveCapturePresentation`, test the injected seam and production
dependency constants:

```java
@Test void presentOrdersCaptureThenScreenshotThenIndicator()
@Test void allRenderedGameModesUseTheSamePostPresentationSeam()
@Test void activePresentationDrainsAndSubmitsExactlyOnce()
@Test void stopEdgePresentationSubmitsZeroFrames()
@Test void viewportOriginOnlyChangeStopsBeforeGrab()
@Test void cleanupClosesCaptureBeforeAudioAndGraphics()
@Test void productionRecorderUsesBlockCapacityEightAndScaleOne()
@Test void palEngineTargetAndCaptureRateBothResolveToFifty()
```

Iterate the rendered `GameMode` values, including level, special/bonus stages,
title/menu, editor, and modal shader-picker simulation. Pause, frame-step, and
rewind PCM semantics remain owned by Tasks 2–3; here assert the post-render seam
still runs once. No real FFmpeg or GL is used.

- [ ] **Step 7: Run and observe Engine integration failures**

```bash
mvn -Dtest=LiveCapturePresentationCoordinatorTest,TestEngineLiveCapturePresentation,TestArchitecturalSourceGuard test
```

Expected: FAIL because the action seam and production wiring do not exist.

- [ ] **Step 8: Integrate Engine**

Add:

```java
public final class LiveCapturePresentationCoordinator {
  public void present(CaptureViewport viewport, Runnable screenshot, Runnable indicator) {
    controller.capturePresentedFrame(viewport);
    screenshot.run();
    indicator.run();
  }
}
```

Construct `LiveCaptureController.Dependencies` with
`AudioManager.beginLiveCaptureAudio(effectiveRate)`,
`GlReadPixelsGrabber(viewport...)`,
`LiveCaptureRecorderFactory(configService, Clock.systemUTC(), ffmpeg)`, a
named single-thread finalizer, and
`Duration.ofSeconds(10)`. Set `targetFps =
FrameRateResolver.effective(configService)`. Sample raw chord state before
`update()` through Engine-owned `LiveCaptureChord`. On its rising edge, call
`requestStop(USER)` when ACTIVE, call `start(currentViewport, effectiveRate)`
when INACTIVE or FAILED, and ignore STARTING/STOPPING. After FINAL invoke the
action seam with capture, existing F12
screenshot, and indicator callbacks. The framebuffer callback requests stop;
per-frame viewport equality remains authoritative. `cleanup()` closes the
controller before audio/graphics destruction.

- [ ] **Step 9: Run focused integration tests**

```bash
mvn -Dtest=CaptureConfigDefaultsTest,LiveCaptureIndicatorRendererTest,LiveCapturePresentationCoordinatorTest,LiveCaptureControllerTest,TestEngineLiveCapturePresentation,TestGameLoop,TestArchitecturalSourceGuard,FrameRateResolverTest test
```

Expected: PASS.

- [ ] **Step 10: Stage exact scope and commit**

```bash
git add -- src/main/java/com/openggf/configuration/SonicConfiguration.java \
  src/main/java/com/openggf/configuration/ConfigCatalog.java \
  src/main/java/com/openggf/configuration/SonicConfigurationService.java \
  src/main/resources/config.yaml \
  src/test/java/com/openggf/configuration/CaptureConfigDefaultsTest.java \
  src/main/java/com/openggf/capture/LiveCaptureIndicatorRenderer.java \
  src/main/java/com/openggf/capture/LiveCapturePresentationCoordinator.java \
  src/main/java/com/openggf/Engine.java \
  src/test/java/com/openggf/TestGameLoop.java \
  src/test/java/com/openggf/TestEngineLiveCapturePresentation.java \
  src/test/java/com/openggf/tests/TestArchitecturalSourceGuard.java \
  src/test/java/com/openggf/capture/LiveCaptureIndicatorRendererTest.java \
  src/test/java/com/openggf/capture/LiveCapturePresentationCoordinatorTest.java
git diff --cached --name-only
```

Expected: exactly those thirteen files.

```bash
git commit -m "feat(engine): add live viewport recording shortcut

Changelog: n/a: documented with the final integrated live-capture commit
Guide: n/a
Known-Discrepancies: n/a
S3K-Known-Discrepancies: n/a
Agent-Docs: n/a
Configuration-Docs: n/a: documented with the final integrated live-capture commit
Skills: n/a"
```

---

### Task 7: Prove the media contract and document operation

**Files:**
- Create: `src/test/java/com/openggf/capture/LiveCaptureMediaSmokeTest.java`
- Modify: `src/test/java/com/openggf/capture/FfmpegEncoderSmokeTest.java`
- Modify: `CONFIGURATION.md`
- Modify: `README.md`
- Modify: `CHANGELOG.md`

**Interfaces:**
- Consumes the completed live capture pipeline.
- Produces ffprobe-backed evidence for codec, dimensions, frame count, duration,
  and encoder pixel fidelity. Engine action-order tests prove indicator
  exclusion.

- [ ] **Step 1: Write the FFmpeg/ffprobe-gated smoke test**

Skip with a clear assumption unless both binaries are on `PATH`. Generate a
short fixed-size frame sequence containing a known moving marker and PCM
sequence containing tone, silence, and reversed samples. Encode through the
production recorder.

- [ ] **Step 2: Assert media metadata**

Invoke ffprobe JSON output and assert:

```text
video codec_name == ffv1
audio codec_name == flac
audio channels == 2
width/height == submitted viewport
nb_read_frames == submitted frame count
abs(audioDuration - frameCount/frameRate) <= 1/sampleRate
```

- [ ] **Step 3: Assert encoder pixel fidelity**

Decode the first/last test frames to RGBA and compare them byte-for-byte with
the submitted buffers. Do not claim this direct-encoder test proves Engine
indicator ordering; `TestEngineLiveCapturePresentation` is that proof.

- [ ] **Step 4: Run smoke and focused regression suites**

```bash
mvn -Dtest=LiveCaptureMediaSmokeTest,FfmpegEncoderSmokeTest test
mvn -Dtest='com.openggf.capture.*,com.openggf.audio.*' test
```

Expected: PASS; smoke tests may SKIP only when FFmpeg/ffprobe are unavailable.

- [ ] **Step 5: Update documentation**

Document Shift+O toggle, output directory/format, FFmpeg dependency,
viewport-only behavior, automatic stop on viewport change, indicator and
screenshot exclusion, rewind support, and the distinction from Shift+F9.
Add an Unreleased changelog entry.

- [ ] **Step 6: Run full verification**

```bash
mvn test
mvn package
git diff --check
git status --short
```

Expected: all tests/build pass; only intentional branch files remain changed.

- [ ] **Step 7: Perform manual ROM-backed check when environment supports it**

Use the discovered root-level ROM. Record forward play, pause, one frame-step,
held rewind, release, and a resize-triggered stop. Play the MKV and confirm
audio continuity/reverse audio, viewport-only pixels, and absent REC overlay.
Record PASS or environment-specific SKIP in the Integration Report.

- [ ] **Step 8: Stage exact scope and commit**

```bash
git add -- src/test/java/com/openggf/capture/LiveCaptureMediaSmokeTest.java \
  src/test/java/com/openggf/capture/FfmpegEncoderSmokeTest.java \
  CONFIGURATION.md README.md CHANGELOG.md
git diff --cached --name-only
```

Expected: exactly those five files.

```bash
git commit -m "test(capture): verify live recording media output

Changelog: updated
Guide: n/a
Known-Discrepancies: n/a
S3K-Known-Discrepancies: n/a
Agent-Docs: n/a
Configuration-Docs: updated
Skills: n/a"
```

---

### Task 8: Record integration and review evidence

**Files:**
- Create: `docs/architecture/validation/2026-07-23-live-av-recording-report.md`

**Interfaces:**
- Consumes: Task 1–7 commits, test reports, media metadata, and reviewer findings.
- Produces: the required Integration Report and End-to-End Review artifact.

- [ ] **Step 1: Run the final end-to-end review**

Dispatch an independent reviewer against the complete Task 1–7 commit range,
the authoritative spec, all test evidence, configuration/docs, lifecycle,
performance, and media contract. Fix every Critical/Important finding, rerun
affected verification, and obtain a no-blocker verdict before writing the
report.

- [ ] **Step 2: Write the validation report**

Include these exact headings:

```markdown
# Live Viewport A/V Recording Validation
## Requirements Traceability
## Changed Files and Commits
## Automated Test Evidence
## FFmpeg/ffprobe Media Evidence
## Manual Runtime Check
## Integration Report
## End-to-End Review
## Residual Risks and Deferrals
## Human Review Checklist
```

Record commands, exit status, pass/fail/skip counts, codecs, dimensions,
frame/audio duration, reviewer findings and their resolutions. A manual SKIP
must name the missing environmental prerequisite.

- [ ] **Step 3: Run final clean verification**

```bash
mvn test
mvn package
git diff --check
git status --short --untracked-files=no
git diff --name-only HEAD --
```

Expected: tests and package pass; tracked status/diff contains only the new
validation report. Linked untracked disassembly resources are outside this
assertion and remain untouched.

- [ ] **Step 4: Stage exact scope and commit**

```bash
git add -- docs/architecture/validation/2026-07-23-live-av-recording-report.md
git diff --cached --name-only
```

Expected: exactly the validation report.

```bash
git commit -m "docs: record live capture validation

Changelog: n/a
Guide: n/a
Known-Discrepancies: n/a
S3K-Known-Discrepancies: n/a
Agent-Docs: n/a
Configuration-Docs: n/a
Skills: n/a"
```

---

## Review and handoff gates

After every task:

1. Dispatch a plan-compliance reviewer with only that task, the spec, and the
   task commit range.
2. Fix all Critical and Important findings.
3. Dispatch a code-quality reviewer against the corrected range.
4. Re-run the task verification command.
5. Continue only when both reviewers report no blockers.

After Task 8:

1. Confirm the committed validation report contains the final Integration
   Report and End-to-End Review evidence.
2. Stop for human review. Do not merge to `develop` without explicit approval.
