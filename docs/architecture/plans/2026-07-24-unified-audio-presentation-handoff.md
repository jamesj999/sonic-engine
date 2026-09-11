# Unified Audio Presentation Tasks 12–16 Handoff

Date: 2026-07-24

## Repository state

- Worktree: `/home/farrell/code/projects/OpenGGF-live-av-recording`
- Branch: `feature/ai-live-av-recording`
- Task 11 final reviewed HEAD: `9b214a390`
- Start the next session by confirming `git status --short`; the only expected
  untracked entries are the pre-existing disassembly links under `docs/`.
- Do not work in the primary `/home/farrell/code/projects/OpenGGF` checkout.

Authoritative documents:

- Design:
  `docs/architecture/designs/2026-07-23-unified-audio-presentation-design.md`
- Approved execution plan:
  `docs/architecture/plans/2026-07-23-unified-audio-presentation.md`
- Per-task briefs/reports and progress ledger:
  `.superpowers/sdd/task-<n>-brief.md`,
  `.superpowers/sdd/task-<n>-report.md`, and
  `.superpowers/sdd/progress.md` (ignored working records)

## Completed work and evidence

Tasks 1–7 established the allocation-free mixer, deterministic sample voices,
composite SMPS voice, bounded structural command queue, final-PCM producer,
all-source factories, and exactly-one outer presentation tick. Their commits
run from `fb3d48d24` through `0cfa15b01`; see the ledger/reports for each
reviewed range.

- Task 8, transactional snapshot/rewind authority:
  `446548033..b5dadf743`. Seven review/fix passes proved dual legacy/producer
  prepare/commit/rollback, exact fractional cadence/history, masks, restore
  identity, and held-rewind behavior. Final independent review was green and
  the fresh full suite passed 12,749 with 11 skipped.
- Task 9, final-PCM speaker cutover:
  `b661884be`, `0aa79a8b9`. OpenAL became a bounded single final-PCM sink;
  speaker/capture packet and queue continuity were proved. Final independent
  review was green; the pre-review full suite passed 12,769 with 11 skipped,
  followed by green post-review architecture gates.
- Task 10, sole presentation entrypoint:
  `ff2e91743..851325f1d`. `AudioManager.presentFrame(PresentationMode)` is the
  one production boundary, including boundary/retry transaction fixes.
  Independent re-review was green; the latest Task 10 full suite passed
  12,834 with 11 skipped.
- Task 11, graceful live-capture audio degradation:
  `2b81a1194`, review fix `9b214a390`. Live attachment now uses only the
  authoritative producer.
  Attach/metadata/drain/close failures continue video with phase-correct
  stereo silence; video/encoder/file/mux failures remain fatal. The developer
  hook is
  `-Dopenggf.debug.liveCaptureAudioFailAfterFrames=N`.
  The review found that `2b81a1194` still queried tap sample-rate metadata
  outside the degradation boundary. `9b214a390` validates/caches all metadata
  inside that boundary and adds the RED/GREEN regression; do not treat the
  first commit alone as green. Independent re-review found no Critical,
  Important, or Minor findings. The final clean prescribed gate passed 51/51,
  including two FFmpeg/ffprobe media tests and the forced attach-failure MKV.
  The first clean full suite passed 12,846 and had two unrelated order/timing
  flakes; both failing tests passed in a clean isolated 2/2 rerun. Consult
  `.superpowers/sdd/task-11-report.md` for exact commands.

## Remaining approved taskset

Do not combine these tasks. Use a fresh implementer and independent reviewer
for each, fixing every Critical/Important finding before advancing.

### Task 12 — unified offline trace capture

Dependency: the Task 11 producer-only non-consuming capture handle.

Replace `preCaptureRuntime`/`captureRuntime` with one
`offlineCaptureHandle`. `beginCaptureMode`, `drainCaptureFrame`, and
`endCaptureMode` become compatibility operations over the existing producer;
they must never install a runtime or open an audio device. Migrate
`TraceCaptureTool`, `TraceCaptureSession`, `HeadlessGameBoot`, and
`DrainPcmAudioTap` so each headless outer framebuffer boundary explicitly
presents once and drains/discards once. Simulation-only fast-forward steps may
enqueue commands but cannot multiply audio cadence. Prove combined SMPS, WAV,
and raw PCM, fresh silence rather than stale second drains, non-destructive
live/offline taps, matching-rate enforcement after source admission, and
failure cleanup.

Required gates:

```bash
mvn -Dtest=AudioManagerCaptureModeTest,DrainPcmAudioTapTest test
mvn -Dtest=TestTraceCaptureUnifiedAudio,TraceCaptureSessionTest test
mvn -Dtest=AudioManagerCaptureModeTest,DrainPcmAudioTapTest,TestTraceCaptureUnifiedAudio,TraceCaptureSessionTest,LiveCaptureControllerTest,AudioManagerLiveCaptureTest test
```

### Task 13 — remove split runtime and backend handoff

Dependency: Task 12 must own all remaining offline compatibility.

Delete the obsolete deterministic runtime, no-op/stream runtime,
`FrameAudioMode`, runtime live tap/capture, `AudioOutputFifo`, backend logical
snapshot, and `PcmSampleStream`. Keep `AudioFrameClock` and
`PcmHistoryRing` as producer primitives. Remove runtime installation,
backend-owned history/reverse cursor, presentation handoff arrays, live
capability flags, and the backend field from `AudioLogicalSnapshot`. Replace
obsolete runtime tests with producer boundary/rewind/queue/capture coverage
and strengthen the architecture guard.

Required searches/gates:

```bash
rg -n "DeterministicAudioRuntime|FrameAudioMode|PresentationAudioCapture|presentationHandoff|runtimeProvidesPresentationPcm|supportsLiveCapturePresentation" src/main src/test
mvn -Dtest=TestAudioPresentationArchitectureGuard test
mvn -Dtest='com.openggf.audio.**,com.openggf.capture.**,TestGameLoopAudioPresentationModes,TestEngineLiveCapturePresentation,TestLiveRewindManagerAudioCleanup,TestHeldRewindAudioStepCost,TestTraceCaptureUnifiedAudio' test
```

The first search must have no matches after cleanup. Stage only the exact Task
13 files (plus explicitly reported moved tests).

### Task 14 — automated source/mode/failure and ROM parity

Dependency: completed one-owner architecture from Task 13.

Add real final-PCM integration tests for title/gameplay continuity, special
stage rings/SFX, combined SMPS/WAV/pitched/raw PCM, toggle identity/cursor/FIFO
continuity, immediate held-rewind attachment, modal/pause/frame-step silence,
release/crossfade policy, repeated taps, no-device fallback, and exception
cleanup. Add separate top-level `@RequiresRom` S1, S2, and S3K tests that boot
the real module, exercise title/level/special-stage/ring routes, and compare
non-zero speaker/capture PCM—not merely queued commands.

Discover only root ROMs and use only verified matching properties:

```bash
find . -maxdepth 1 -type f -name '*.gen' -print
mvn -Dtest=TestUnifiedAudioPresentationIntegration,TestSonic1UnifiedAudioPresentationRomIntegration,TestSonic2UnifiedAudioPresentationRomIntegration,TestSonic3kUnifiedAudioPresentationRomIntegration,TestRomAudioIntegration,TestEngineLiveCapturePresentation,TestAudioPresentationArchitectureGuard test
```

All three verified ROMs are present at the worktree root as **symlinks**
(`s1.gen`, `s2.gen`, `s3k.gen`). An earlier revision of this handoff claimed
there were no root-level `.gen` files; that was wrong, and came from the
discovery command above using `-type f`, which does not follow symlinks. Use
`find -L . -maxdepth 1 -type f -name '*.gen' -print` and verify with
`sha1sum`. Missing ROM tests must be assumptions/`NOT RUN`, never reported as
passes — but with these ROMs present the `@RequiresRom` tests must actually run
and pass.

Expected hashes:

- S1 World REV01 SHA-1
  `69E102855D4389C3FD1A8F3DC7D193F8EEE5FE5B`
- S2 World REV01 SHA-1
  `8BCA5DCEF1AF3E00098666FD892DC1C2A76333F9`
- S3K locked-on SHA-1
  `CFBF98C36C776677290A872547AC47C53D2761D6`

### Task 15 — allocation and playable-media continuity

Dependency: Task 14 source fixtures.

Warm 10,000 frames, measure another 10,000 with
`AudioBenchmarkMemoryProbe`, and assert zero producer-loop bytes when the JVM
counter is supported. Identity/bound checks always run. Prove one-hour
no-device bounds, stalled sink/tap non-blocking behavior, and structural
command preservation. Extend media smoke coverage to mixed final audio and a
tap that fails after frame 17; FFmpeg decode must show non-zero PCM before the
failure, exact silence after it, monotonic timestamps, all video frames, stereo
FLAC, and A/V duration within one sample.

Required gate:

```bash
mvn -Dtest=TestAudioPresentationAllocationBudget,LiveCaptureMediaSmokeTest test
```

The existing warmed producer byte-counter test has occasionally reported an
80-byte full-suite-only allocation and then passed clean in isolation. Task 15
must replace flake tolerance with the approved warmed, identity, and bounded
evidence.

### Task 16 — final suite, architecture, manual listening, docs, review

Dependency: reviewed Tasks 1–15.

Run:

```bash
mvn test
mvn package
rg -n "alGenSources|alSourcePlay|AL_LOOPING|AL_PITCH" src/main/java/com/openggf/audio
rg -n "DeterministicAudioRuntime|runtimeProvidesPresentationPcm|deferredLiveCaptureRuntime" src/main src/test
git diff --check
```

OpenAL source tokens may exist only in `OpenAlPcmSink` for the one final-PCM
source. Superseded runtime tokens must have no matches. Restore
`docs/status/rewind-round-trip-gaps.md` after tests unless an intentional rewind-coverage
change is demonstrated.

Then obtain user-confirmed listening for all three verified ROMs—not merely
available games. For each game, use `./dev.sh` to verify title, gameplay,
special stage where available, rings/SFX before/during/after Shift+O, MKV
playback, held-rewind start, release, pause, and frame-step. Repeat with:

```bash
JAVA_TOOL_OPTIONS='-Dopenggf.debug.liveCaptureAudioFailAfterFrames=120' ./dev.sh
```

The user must confirm gameplay/video/speaker continuity and the MKV transition
to timed silence. Record ROM hash, ROM basename, ordinary/injected MKV
basenames, ffprobe durations, and the user's exact observations. Missing ROM
or listening rows are `NOT RUN`; audible claims cannot be inferred from PCM.

Align the earlier live-recording design/report, create
`docs/architecture/validation/2026-07-23-unified-audio-presentation-report.md`,
map every acceptance criterion to exact evidence, and run independent review
loops until no Critical/Important findings remain. Finish with a clean
committed `mvn test` and `mvn package`.

## Do-not-regress invariants

- Exactly one `AudioManager.presentFrame(mode)` per presented outer frame;
  fast-forward simulation steps do not multiply it.
- FORWARD advances voices/history; SILENT yields fresh zeros without cursor or
  history movement; REVERSE reads history and does not synthesize/append.
- OpenAL consumes only final PCM; recording and speaker receive independent
  views of the same producer-selected packet.
- Starting/stopping live capture never replaces a runtime, flushes the sink,
  rebinds voices, resets cursors, changes rewind state, or consumes speaker
  PCM.
- Capture starts immediately during held rewind and receives the next audible
  reverse packet.
- Live audio-only failures preserve capture clock phase, stereo cadence,
  frame index, video activity, and retryability. Encoder/file/video/mux
  failures remain whole-recorder fatal.
- S3K music and SFX share the correct session coordination owner; donor audio
  works in S1/S2 while legacy and producer state remain isolated until legacy
  deletion.
- Structural commands are never dropped; bounded queue/deferred ordering and
  owner-thread rules remain intact.
- Rewind publication is dual-path transactional until Task 13 removes the
  legacy half; a failed release cannot partially clean or publish state.
- No trace test may hydrate engine state from trace data or introduce
  zone/route/frame carve-outs.

## Known unrelated state

- Ignore, do not stage, and do not remove:
  `docs/kis2disasm`, `docs/s1disasm`, `docs/s2disasm`,
  `docs/scddisasm`, and `docs/skdisasm`.
- The no-ROM fire-curtain diagnostic and the warmed byte-counter test have
  shown order/timing-sensitive full-suite failures and then passed together in
  a clean isolated rerun. Record recurrence honestly; do not mask a genuine
  regression.
- Maven tests regenerate `docs/status/rewind-round-trip-gaps.md`; restore it if the task did
  not intentionally change rewind coverage.

## Next first action

Read `AGENTS.md`, the authoritative spec, and the full Task 12 plan section.
Generate `.superpowers/sdd/task-12-brief.md`, update the ignored ledger to mark
Task 11 complete at its final reviewed SHA, and delegate a fresh Task 12
implementer from that SHA. Begin with the two capture-mode/PCM-tap RED tests;
do not delete runtime classes until Task 12 is independently green.
