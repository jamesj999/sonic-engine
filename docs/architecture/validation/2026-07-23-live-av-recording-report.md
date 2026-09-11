# Live Viewport A/V Recording Validation

## Requirements Traceability

| Requirement | Implementation and evidence | Result |
| --- | --- | --- |
| Configurable start/stop shortcut | `capture.toggleKey` defaults to `O`; `LiveCaptureChord` recognizes a complete Shift+key rising edge with Ctrl/Alt released. Engine toggles one controller without affecting Shift+F9 input/movie recording. Chord, configuration, and Engine tests cover modifier-first/key-first order, holds, release/repress, unrelated keys, and Shift+F9 independence. | PASS |
| Window-only red-dot/white-`REC` indicator | Engine captures after the FINAL display pass, performs F12 capture, then draws the indicator before buffer swap. The executable presentation-order seam and exhaustive game-mode/presentation-state matrix prove `capture, screenshot, indicator`; renderer tests pin the 10 px dot and top-right placement. Thus neither MKV nor F12 receives indicator pixels. | PASS |
| Viewport-only video | `CaptureViewport` and the four-argument `GlReadPixelsGrabber` read the fixed physical game viewport, excluding borders and letterbox/pillarbox areas. A viewport move or size change requests a normal stop before a mismatched frame can be submitted. | PASS |
| Synchronized audio/video including rewind | A non-consuming runtime presentation-audio lease owns a separate frame clock and cursor. It emits fresh forward PCM or explicit silence, forks history for independently drained reverse audio, pads exhausted history with silence, and does not consume speaker FIFO data. PAL uses 50 Hz; other modes use `max(1, configured FPS)` through the shared `FrameRateResolver`. | PASS |
| Pause, frame-step, menus, editor, stages, modal display, rewind | The Engine presentation boundary is unconditional after FINAL. The executable matrix covers every `GameMode` and normal, modal shader-picker, paused, frame-step, and rewind states, with exactly one capture call per displayed frame. | PASS |
| Lossless output and bounded cleanup | Live recording uses scale 1, FFV1 video, stereo FLAC audio, BLOCK backpressure, and queue capacity 8. Serialized stop/abort ownership, retained video/mux processes, one shared shutdown deadline, idempotent cleanup, and partial-file deletion are covered by failure and concurrency tests. | PASS |
| BizHawk launchers directly executable | `tools/bizhawk/record_trace.sh` and `tools/bizhawk/run_bizhawk_lua.sh` are committed as mode `100755`. | PASS |

## Changed Files and Commits

The feature range is `61190cb2d..b8d5b421f` (50 files, 4,437 insertions,
63 deletions), plus the initial launcher-mode commit itself:

- `61190cb2d` — make the two BizHawk launchers executable.
- `affb830b3`, `28b28aae7` — authoritative design/plan and resolver-package
  alignment.
- `6278dda74`, `5e49d63f8` — presentation audio tap, rewind cursor/clock, and
  identity-safe `AudioManager` capture handle.
- `405802f42` — fixed physical viewport model and GL readback.
- `ddd8fbcfa`, `a5434e9a0`, `0180fa1d6` — live controller/factory/chord and
  serialized, deadline-bounded FFmpeg stop/abort.
- `77fde7c72`, `be09a1176`, `4c250461b` — configuration, Engine wiring,
  post-FINAL presentation boundary, indicator, and exhaustive integration
  coverage.
- `7aee1bb1d`, `4d570ada3` — operation documentation and production-pipeline
  FFmpeg/ffprobe media proof.
- `d60e742ef`, `fc195c540` — observable failure transitions and correct
  reporting generations for synchronous retries.
- `b8d5b421f` — byte-exact comparison of every decoded video frame.

Production changes are concentrated in `Engine`, `audio`/`audio.runtime`,
`capture`, `configuration`, and `config.yaml`. Tests mirror those packages.
User documentation was updated in `README.md`, `CONFIGURATION.md`, and
`CHANGELOG.md`; the existing offline trace-capture API remains unchanged.

## Automated Test Evidence

- A manual production run exposed that globally enabling deterministic
  presentation on LWJGL silenced gameplay audio. The corrective regression
  keeps ordinary LWJGL sessions on the legacy/NoOp path and hot-attaches the
  stream-backed runtime only while recording. `TestAudioManagerRuntimeInstallation`
  exercises that production path without an OpenAL device: ongoing music and
  SFX survive capture start, speaker and capture drains are independent,
  synthesized-but-not-yet-uploaded PCM bridges capture stop, and stopping
  during rewind defers legacy restoration until reverse release. Focused audio
  verification passed 36 tests; the corrective full `mvn test -Dmse=off` run
  passed 12,569 tests with 0 failures/errors and 11 skipped.
- Task-focused audio, viewport, controller, Engine-boundary, configuration,
  and media tests passed throughout Tasks 2–7. The final media command
  `mvn -Dtest=LiveCaptureMediaSmokeTest,FfmpegEncoderSmokeTest test` executed
  rather than skipping.
- Task 7 full verification recorded 12,548 passed, 0 failed/errors, 11
  skipped before review. A later order-sensitive CNZ registry failure was
  reproducible in two broad runs but passed in isolation; no capture test
  failed.
- Final `mvn test`, attempt 1: exit 1; 12,550 passed, 1 failed, 0 errors, 11
  skipped. The unrelated
  `TestPlayableSpriteRollSpeed.s3kTailsStopsRollingBelowMinimumRollSpeedThreshold`
  expected `65409` but observed `0`.
- Isolation rerun `mvn -Dtest=TestPlayableSpriteRollSpeed test`: exit 0;
  repository Maven reporting recorded 12,551 passed, 0 failed/errors, 11
  skipped.
- Fresh final `mvn test`, attempt 2: exit 0; 12,551 passed, 0 failed/errors,
  11 skipped.
- Final `mvn package`: exit 0; 12,551 passed, 0 failed/errors, 11 skipped.
- Final `git diff --check`: exit 0.

The generated `docs/status/rewind-round-trip-gaps.md` change produced by test execution was
restored after verification and is not part of this change.

## FFmpeg/ffprobe Media Evidence

`LiveCaptureMediaSmokeTest` ran through production `CaptureRecorder` and
`FfmpegEncoder`, with `/usr/bin/ffmpeg` and `/usr/bin/ffprobe` available:

- Matroska viewport: 18 x 10 physical pixels at 30 fps.
- Video: FFV1, six submitted frames and six decoded frames.
- Every decoded RGBA frame is byte-for-byte identical to its submitted
  pre-indicator viewport frame (decoded with `vflip` to undo GL-origin
  normalization).
- Audio: FLAC, stereo, 48,000 Hz, 9,600 stereo sample frames.
- Expected and ffprobe-observed duration: 0.2 seconds; error 0 samples
  (allowance at most one sample).
- Decoded PCM is byte-exact across the known tone region, two explicit-silence
  packets, and reversed-sample region.
- The output exists and is non-empty. Indicator exclusion is additionally
  established at the actual Engine presentation boundary, where capture and
  F12 screenshot precede indicator drawing.

## Manual Runtime Check

**SKIP — missing environmental prerequisite: native-window input automation
and visual/audio playback inspection.**

The discovered root ROM `s2.gen` booted the packaged runtime successfully:
OpenGL and 48 kHz OpenAL initialized, Sonic 2 was detected, and the engine
reached the title-screen runtime. However, this harness has neither `xdotool`
nor `wmctrl`, nor a facility to interact with and inspect the native window.
It therefore could not honestly verify the human sequence of forward play,
pause, frame-step, held rewind/release, Shift+O start/stop, resize stop, and
visual/audio MKV playback. Automated boundary and media tests cover those
contracts; no manual PASS is claimed.

## Integration Report

Engine owns the shortcut and one controller. The complete chord is sampled
before input-history update. After each rendered mode's FINAL shader pass,
Engine submits exactly the fixed viewport pixels plus that presentation
frame's PCM, takes any F12 screenshot, draws the window-only indicator, then
swaps. Resize requests a graceful asynchronous stop; start is gated until
terminal cleanup finishes. The audio lease is independent of speaker
consumption and runtime replacement invalidates it safely.

Ordinary Java exceptions escaping `init()` or `loop()` do flush as expected:
`Engine.run()` invokes `cleanup()` from `finally`, and live capture is the
first cleanup step, before audio and graphics teardown. Controller close waits
up to ten seconds for graceful finalization, then uses the explicit bounded
abort path to kill retained video/mux processes and remove partial output.
Each cleanup step catches its own failure so later teardown still runs. This
cannot guarantee a valid file after process termination that bypasses Java
cleanup—such as `System.exit`, JVM/native crash, SIGKILL, or power loss.

Failures opening FFmpeg/audio, grabbing/draining/submitting a frame, or in the
worker/mux are logged once per attempt, clear the indicator, detach the audio
lease, and clean retained processes/temp files. A synchronous retry using the
same exception object still starts a new reporting generation and emits one
new warning without repeated-frame spam.

## End-to-End Review

The independent complete-range review initially found one Important issue:
live-capture failures (missing FFmpeg, unsupported tap, encoder open, frame,
worker, or mux failures) could be silent. `d60e742ef` added Engine-owned
once-per-transition warning reporting and tests.

Re-review found one Important identity edge: a synchronous
`FAILED → STARTING → FAILED` retry reusing the same exception could be
suppressed. `fc195c540` resets the reporting generation immediately before
every permitted start attempt and proves one warning for the new attempt,
while repeated observation remains non-spamming.

The remaining optional Minor noted that media smoke sampled only first/last
video frames. `b8d5b421f` now compares all six decoded RGBA frames byte for
byte while retaining codec, audio, frame-count, and duration assertions.

Final reviewer verdict: **no blockers**. No Critical or Important findings
remain; after the all-frame improvement there were no new findings and the
no-blocker verdict stood.

## Residual Risks and Deferrals

- Native interactive operation and subjective playback quality remain for a
  human because this environment cannot automate or inspect the GLFW window.
- A hard process/native failure that bypasses `Engine.run()` cleanup may leave
  an unfinalized Matroska file; ordinary engine exceptions are covered by the
  `finally` cleanup path.
- The repository has unrelated order-sensitive tests: the final first attempt
  hit the roll-speed test and earlier Task 7 review hit a CNZ registry test.
  Both passed outside their failing order, and the fresh final full suite and
  package were green.
- The prior Minor all-frame media-evidence gap is fixed; there are no deferred
  Critical/Important review findings.

## Human Review Checklist

- [ ] Ensure `ffmpeg`/`ffprobe` are on `PATH` and select a writable
  `capture.outputDir`.
- [ ] Press Shift+O during play; confirm the red dot and white `REC` appear in
  the top-right of the viewport.
- [ ] Record forward play, pause, one or more frame-steps, held rewind, rewind
  release, and resumed play; press Shift+O again and wait for finalization.
- [ ] Play the MKV and confirm synchronized forward/reverse sound and explicit
  silence during non-audio presentation frames.
- [ ] Confirm the MKV contains only the physical game viewport and neither it
  nor an F12 screenshot contains the `REC` indicator.
- [ ] Start another recording, resize/move the viewport, and confirm recording
  stops cleanly with a playable file.
- [ ] Trigger an ordinary engine exception during recording and confirm the
  file is finalized by shutdown cleanup; separately verify a warning appears
  for a deliberate FFmpeg/open/frame failure.
- [ ] Confirm Shift+F9 input/movie recording remains independent.
