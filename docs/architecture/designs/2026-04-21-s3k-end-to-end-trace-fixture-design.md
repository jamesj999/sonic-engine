# S3K End-to-End Trace Fixture Design

## Goal

Add a real Sonic 3&K trace fixture and replay test built from the BK2 movie
`s3k-aiz1-aiz2-sonictails.bk2`, covering the continuous path from the Angel Island
intro cutscene through AIZ1 gameplay, the seamless fire-curtain transition into
AIZ2, the rest of Angel Island, and the outro handoff into Hydrocity.

The fixture is explicitly end-to-end. It is not split into independent segment
tests, because the main value is proving that the seamless transition path works
correctly under one continuous BK2 and one continuous ROM trace.

## Why This Needs More Than A Normal Trace

S3K AIZ is not a simple zone/act replay:

- The run begins in intro/cutscene state before normal gameplay.
- The AIZ1 fire sequence reloads AIZ2 art and progression state mid-run.
- During that transition, the engine and ROM intentionally distinguish current
  gameplay progression state from the apparent act presentation state.
- The fixture should continue past the player-controlled section and through
  the HCZ handoff/cutscene frames, because the zone transition logic is part of
  what we are validating.

The trace therefore needs explicit phase/checkpoint annotations so failures can
be understood in terms of the last clean phase boundary reached, even though any
divergence still fails the test.

## Recommended Approach

Use one authoritative fixture directory containing:

- `s3k-aiz1-aiz2-sonictails.bk2`
- `metadata.json`
- `physics.csv`
- `aux_state.jsonl`

The recorder remains responsible for producing the authoritative trace files.
The replay harness remains strict: any divergence is a test failure. The only
new diagnostic layer is recorder-emitted checkpoint metadata inside
`aux_state.jsonl` plus replay-report plumbing that exposes the latest
checkpoint reached before divergence.

This keeps the continuous replay contract intact while making long-run failures
diagnosable.

## Checkpoint Model

Glossary for this fixture:

- `aiz1` means the pre-reload Angel Island gameplay phase (`actual_act == 0`)
- `aiz2` means the post-reload continuation phase (`actual_act == 1`)
- checkpoint names use those familiar labels for readability, but payload
  correctness is always judged from `actual_zone_id`, `actual_act`, and
  `apparent_act`

### Event Type

Add a new aux event type for S3K:

```json
{
  "frame": 1234,
  "event": "checkpoint",
  "name": "aiz1_fire_transition_begin",
  "actual_zone_id": 0,
  "actual_act": 0,
  "apparent_act": 0,
  "game_mode": 12,
  "notes": "optional short recorder note"
}
```

The important rule is that checkpoint payloads must carry:

- `actual_*`: current gameplay zone/act state
- `apparent_act`: title-card / presentation-side act state

In S3K this is ROM-backed, not engine-invented: the recorder must read the ROM
RAM label `Apparent_act` directly. The engine's `LevelManager.getApparentAct()`
is the Java-side mirror of that ROM state. There is no separate apparent-zone
RAM surface for this fixture, so checkpoint and audit events track:

- `actual_zone_id`
- `actual_act`
- `apparent_act`

`apparent_zone` is intentionally omitted. There is no ROM RAM surface for it in
this fixture, and the trace must not invent one.

For this fixture, `actual_zone_id` is authoritative for zone progression. The
important divergence window is AIZ's fire transition, where `currentAct` has
advanced to AIZ2 continuation state while `apparentAct` intentionally remains
at the prior act presentation value. Outside transition/signpost/results
windows, `actual_act` and `apparent_act` are expected to be equal.

For intro frames that occur before the level state is observable, these fields
must be emitted as `null`, not guessed defaults.

### Why A New Event Type

`routine_change` and `mode_change` already exist, but they are too low-level to
act as stable replay milestones:

- they do not carry zone/act progression state
- they are tied to object/control behavior, not test-phase meaning
- they are not named in terms a replay report can surface directly

`checkpoint` is therefore a separate semantic event type whose job is to mark
human-meaningful milestones in the end-to-end run.

### Required Checkpoints

The initial implementation should emit these named checkpoints:

- `intro_begin`
- `gameplay_start`
- `aiz1_fire_transition_begin`
- `aiz2_reload_resume`
- `aiz2_main_gameplay`
- `hcz_handoff_begin`
- `hcz_handoff_complete`

Optional checkpoints may be added only when they can be detected from explicit
ROM/runtime state that would be present in future runs too:

- `aiz2_signpost_begin`
- `aiz2_results_begin`

Optional checkpoints are report-only. They may appear in recorder output and in
divergence context, but they are never used as elastic-window anchors or as
required pass/fail milestones.

### Emission Rules

Checkpoint emission must be sparse and deterministic:

- Emit once when a phase boundary is first crossed.
- Do not emit every frame while a phase remains active.
- Detection should be based on ROM/runtime state already used by the engine or
  recorder, not on brittle frame constants.
- Prefer explicit state transitions already represented by S3K event logic over
  camera-position guesses when both are available.
- Checkpoints are sticky for the duration of the run. Once a named checkpoint
  has been emitted, it must not emit again even if the underlying source signal
  falls and rises later.
- `aux_state.jsonl` must be written in non-decreasing frame order. Same-frame
  event ordering must also be deterministic so backward scans are stable across
  re-records. Within a frame, emit `zone_act_state` first, then any checkpoint
  event, then remaining event types in a fixed detector execution order.

### Transition Timing Principle

The replay fixture must distinguish between:

- semantic phase correctness
- literal frame-count equality inside load-bound cutscene windows

For AIZ specifically, some intro and fire-transition timings in the original
game are partly a consequence of how long Nemesis/Kosinski work takes on the
real machine. The engine does not need to artificially reproduce that loading
pressure just to consume the same number of frames.

The fixture therefore treats these windows as **elastic, checkpoint-bounded
phases** rather than mandatory frame-count matches:

- `intro_begin` -> `gameplay_start`
- `aiz1_fire_transition_begin` -> `aiz2_main_gameplay`

Inside those windows, the replay contract is:

- the engine must reach the same named checkpoints in the same order
- the same gameplay/presentation state transitions must occur
- checkpoint payload state (`actual_zone_id`, `actual_act`, `apparent_act`,
  `game_mode`) must remain correct
- frame-count drift inside the elastic window is diagnostic, not by itself a
  test failure

Outside those elastic windows, replay remains strict and frame-by-frame.

This keeps the fixture honest about game mechanics while avoiding fake waits or
ROM-specific decompression stalls whose only purpose would be to pad time.

### Required Checkpoint Signals

Each required checkpoint must be keyed from a concrete source signal:

| Checkpoint | Source signal |
| --- | --- |
| `intro_begin` | Frame 0 of the BK2 recording. This is an unconditional anchor, not a detected state transition. |
| `gameplay_start` | First frame where S3K is in level gameplay and the player control-lock timer reaches zero. |
| `aiz1_fire_transition_begin` | Rising edge of the dedicated AIZ fire-transition state becoming active. This must be keyed from the same ROM-visible/event-driven transition state the engine models, not from camera position. |
| `aiz2_reload_resume` | First frame after the seamless reload where `actual_act == 1` while ROM `Apparent_act == 0`. This is the main act-divergence checkpoint. |
| `aiz2_main_gameplay` | First post-reload frame where `actual_act == 1` and normal gameplay control is restored (`move_lock == 0`). |
| `hcz_handoff_begin` | First frame where the AIZ-to-HCZ transition path becomes active, keyed from actual zone progression / transition state rather than cutscene timing constants. |
| `hcz_handoff_complete` | First frame where HCZ is the actual zone, `actual_act == 0`, and normal gameplay control is restored (`move_lock == 0`). |

The exact RAM labels/addresses behind these signals are a required research
deliverable before implementation. The implementation plan must freeze them in
`docs/architecture/research/2026-04-21-s3k-trace-addresses.md` before recorder
code is finalized.

"Concrete source signal" does not require a single RAM byte. A checkpoint may
be derived from a deterministic combination of ROM-observable RAM fields so
long as:

- every input to the detector is readable from BizHawk RAM during recording
- the rule is deterministic and re-recordable
- the same semantic rule can be evaluated against engine runtime state during
  replay

Recorder logic must never depend on engine-side enums, mutation keys, or other
Java-only state.

## Recorder Design

### Fixture Scope

Create an S3K recorder flow parallel to the existing S1/S2 workflow, but the
fixture format stays the same v3 contract:

- `game = "s3k"`
- `trace_schema = 3`
- `csv_version = 4`

No physics.csv schema expansion is allowed for this work. All S3K-specific
phase/checkpoint data lives in `aux_state.jsonl`.

For a fixture that starts before gameplay, the recorder must support recording
from BK2 frame 0 instead of waiting for the gameplay-unlock trigger used by the
normal act-level recorders. `gameplay_start` becomes a checkpoint inside the
trace, not the recording start condition.

### State Tracking

The S3K recorder must track enough state to emit meaningful checkpoints:

- gameplay-frame counter
- vblank counter
- lag counter
- actual zone/act
- apparent act
- game mode / cutscene state if available

In concrete engine terms, this means:

- `actual_zone_id` / `actual_act`: the current zone/act progression state
- `apparent_act`: ROM `Apparent_act` as recorded from emulator RAM

There is no distinct apparent zone surface in the engine today, so the trace
does not invent one.

The recorder should also emit a `zone_act_state` diagnostic event whenever any
of these values changes, even if that change does not map to a named
checkpoint. This gives a low-level audit trail beneath the semantic checkpoint
events without spamming every frame.

Suggested payload:

```json
{
  "frame": 1200,
  "event": "zone_act_state",
  "actual_zone_id": 0,
  "actual_act": 1,
  "apparent_act": 0
}
```

`game_mode` must be emitted as the raw integer game-mode byte read from ROM RAM
(`Game_mode`), not as a hex string. If a field is not yet observable at the
start of the intro recording, emit `null`.

This integer encoding is deliberate for the new S3K semantic events. Existing
hex-string fields in older event types (`routine_change`, object diagnostics)
remain unchanged.

`zone_act_state` is an edge-triggered audit event, not a debounced semantic
checkpoint. Emit it on every observed change of `actual_zone_id`,
`actual_act`, or `apparent_act`, including transient but real ROM state
changes. Checkpoints remain the stable, human-meaningful layer.

### Determinism Requirement

The recorder path must be deterministic on repeated runs of the same BK2:

- re-recording the same BK2 twice with the same BizHawk/core version must
  produce byte-identical `physics.csv`
- re-recording the same BK2 twice with the same BizHawk/core version must
  produce byte-identical `aux_state.jsonl`
- `metadata.json` must record:
  `bizhawk_version`, `genesis_core`, and a non-empty `rom_checksum`

This is required because the fixture spans seamless reload behavior and needs a
replayable emulator baseline.

## Replay Test Design

### Test Shape

Add one real replay test for the full fixture:

- one BK2
- one trace directory
- one replay pass
- any divergence causes test failure

The test should not stop at the AIZ1/AIZ2 boundary or at loss of control. It
must continue through the entire recorded HCZ handoff.

The replay harness should remain a **single end-to-end test** even though the
comparison model is phase-aware. Elastic windows are not separate tests and do
not weaken the contract into "best effort" replay. They only change how the
intro and fire-transition spans are judged:

- in elastic windows, success means the engine reaches the required checkpoints
  and state transitions cleanly
- at the exit checkpoint of an elastic window, comparison re-synchronizes to
  the checkpoint's trace frame and resumes strict frame-by-frame replay

If the engine never reaches the expected exit checkpoint, reaches checkpoints
out of order, or reaches the exit checkpoint with the wrong state, the replay
fails immediately.

### Elastic-Window Comparison Algorithm

Elastic windows are defined entirely from the recorded trace's required
checkpoint stream:

- window 1: `intro_begin` -> `gameplay_start`
- window 2: `aiz1_fire_transition_begin` -> `aiz2_main_gameplay`

Replay uses two checkpoint sources:

- **trace checkpoints** from recorded `aux_state.jsonl`
- **engine checkpoints** emitted live during replay by a dedicated
  replay-side detector

The replay-side detector is responsible for observing engine runtime state each
frame and emitting the same required checkpoint names. It does not read the
trace's aux file and it does not persist a second aux stream to disk. Its job
is only to answer:

- which required checkpoint, if any, did the engine just reach?
- what were the engine-side payload values for that checkpoint?

The detector may also emit optional checkpoints when their conditions are met.
Those contribute to replay diagnostics only; they do not affect pass/fail or
elastic-window control flow.

The detector must be driven from engine mirrors of the same semantics used by
the recorder: current zone/act, apparent act, control-lock state, and
event/transition state derived from ROM-backed behavior. It must not depend on
test-only frame constants.

Frame pairing rule:

- strict replay runs normally until an elastic-window entry checkpoint is
  reached
- the entry checkpoint frame itself is still validated at the strict boundary
  that led into the window
- for `intro_begin`, which is anchored at BK2 frame 0, that entry-frame
  validation is the normal initial frame comparison performed before the window
  opens
- while the window is open, BK2 input continues to advance one frame at a time,
  but `TraceBinder.compareFrame(...)` is suspended
- when the engine emits the expected exit checkpoint with matching payload
  state, the window closes
- the engine frame that emitted the exit checkpoint is validated semantically by
  checkpoint payload only; it is not field-compared against a `physics.csv` row
- strict replay resumes on the **next** replay tick against the trace frame
  immediately after the recorded exit checkpoint frame

Concretely: if the trace recorded `aiz2_main_gameplay` at trace frame `M` and
the engine reaches it after replay has advanced `N` engine ticks since the
window opened, the harness resumes strict comparison at trace frame `M + 1`
using the next executed engine frame.

For this fixture, player inputs during the intro and fire-transition windows
are effectively engine-scripted, so any input-stream discontinuity introduced
when `N != trace_span` is absorbed by the same diagnostic-drift rule; the
harness does not attempt to re-align skipped or over-consumed inputs inside the
window.

Failure conditions for an elastic window:

- the engine emits a required checkpoint out of order
- the engine emits the expected exit checkpoint with mismatched payload state
- the engine never emits the expected exit checkpoint before the drift budget is
  exhausted
- the trace runs out of frames before the window closes

Drift budget:

- let `trace_span = exit_checkpoint_frame - entry_checkpoint_frame`
- let `max_engine_span = trace_span + max(180, trace_span)`

Frame-count drift inside the window is diagnostic only up to
`max_engine_span`. If the engine has not reached the exit checkpoint by then,
the replay fails as structurally divergent rather than hanging indefinitely.

This budget is intentionally generous enough to absorb decompression-related
timing differences while still bounding pathological stalls.

### CI Stance

This fixture is intended to land as a normal default-suite test. The target
state is:

- the real replay test is discovered by normal Surefire test selection
- `mvn test` stays green with the S3K replay enabled
- no `@Disabled`, no manual-only naming trick, and no Surefire exclusion is
  used to defer parity work

That requirement is compatible with the elastic-window model above. The replay
must become green by judging decompression-bound windows semantically instead of
forcing the engine to recreate ROM-length loading stalls.

### Reporting Behavior

When a replay fails, the report should include:

- the first failing frame as it already does
- the latest checkpoint reached at or before that frame
- the most recent `zone_act_state` event at or before that frame

This should be implemented by scanning aux events backward from the first error
frame at report-render time. It does not require changing the core
`TraceBinder` comparison contract or stuffing checkpoint state into every
`FrameComparison`.

Visible contract:

- `DivergenceReport.toSummary()` appends the latest checkpoint before the first
  error when one exists
- `DivergenceReport.toJson()` adds top-level fields for
  `latest_checkpoint_before_first_error` and
  `latest_zone_act_state_before_first_error`
- `getContextWindow(centreFrame, radius)` includes the latest checkpoint and
  latest `zone_act_state` event at or before `centreFrame` whenever available

This is enough to answer:

- did we diverge before or after the fire transition started?
- did we survive the seamless reload into AIZ2?
- did we reach the HCZ handoff at all?

### Success Criteria

The end-to-end fixture is considered structurally valid when:

- the recorder can capture the full BK2 without manual intervention
- `TraceData.load(...)` accepts the generated files as schema v3 S3K data
- the replay harness can consume the full fixture directory and produce
  checkpoint-aware diagnostics
- the fixture can be re-recorded deterministically under the pinned BizHawk/core
  environment

Merge acceptance is stricter than structural validity:

- the checked-in `TestS3kAizTraceReplay` must pass in the default suite
- any decompression-bound timing drift must be handled by the elastic-window
  model, not by disabling the test or excluding it from Surefire
- remaining gameplay divergences must be fixed in engine code before the work is
  considered complete

## Files In Scope

Expected implementation files:

- `tools/bizhawk/s3k_trace_recorder.lua`
- `tools/bizhawk/record_s3k_trace.bat`
- `src/test/resources/traces/s3k/aiz1_to_hcz_fullrun/`
- `src/test/java/com/openggf/tests/trace/s3k/TestS3kAizTraceReplay.java`
- `src/test/java/com/openggf/tests/trace/TraceEvent.java`
- `src/test/java/com/openggf/tests/trace/TraceEventFormatter.java`
- `src/test/java/com/openggf/tests/trace/TraceData.java`
- `src/test/java/com/openggf/tests/trace/DivergenceReport.java`
- `src/test/java/com/openggf/tests/trace/AbstractTraceReplayTest.java`
- optional replay-side helper(s) under `src/test/java/com/openggf/tests/trace/s3k/`
  for checkpoint detection / elastic-window coordination

Single-zone fixtures keep the existing `<zone><act>_fullrun/` naming.
Multi-zone end-to-end fixture directories use:

- `<start-zone><start-act>_to_<end-zone>_fullrun/`

For this fixture: `aiz1_to_hcz_fullrun/`

`metadata.json` for the fixture remains start-zone oriented (`zone`, `zone_id`,
`act` describe the recording start). End-zone verification is owned by the
checkpoint stream and final `zone_act_state`, not by new metadata fields.

The implementation should reuse the current trace parser and replay structure
where possible. This design does not require a new file format or a new replay
engine.

## Non-Goals

- Do not split the authoritative fixture into multiple segment tests.
- Do not add S3K-only columns to `physics.csv`.
- Do not weaken replay strictness by allowing mismatches to continue as test
  success.
- Do not encode phase boundaries as hardcoded frame numbers in committed test
  code.
- Do not add artificial delays whose only purpose is to mimic ROM decompression
  duration rather than gameplay behavior.

## Risks And Mitigations

### Risk: transition-state ambiguity

The AIZ fire sequence can make current and apparent act disagree.

Mitigation:
- always capture both actual and apparent act in checkpoint and zone-act events

### Risk: long-run failures remain opaque

A full-run test can fail early and hide later sections.

Mitigation:
- recorder-emitted checkpoints identify the last successfully reached phase

### Risk: overfitting checkpoint detection to one movie

If checkpoint detection depends on this exact run's camera timing, it will be
fragile.

Mitigation:
- key checkpoints off explicit game/event state when available

### Risk: replay fails only because load-bound phases are shorter or longer

The AIZ intro and fire curtain can consume different numbers of frames between
the ROM and the engine for reasons unrelated to gameplay correctness.

Mitigation:
- model those spans as elastic checkpoint-bounded phases
- require correct checkpoint order and state at phase exit
- resume strict frame replay immediately after the elastic window closes

### Risk: recorder and replay detector drift apart over time

If checkpoint detection rules change after a fixture is recorded, saved
checkpoint events can become stale relative to live replay detection.

Mitigation:
- keep recorder-side and replay-side checkpoint rules aligned to the same
  documented semantic contract
- treat signal-rule changes as a fixture-regeneration event
- re-record the authoritative BK2 whenever checkpoint detection semantics
  materially change

## Implementation Follow-On

The next step after this spec is an implementation plan that breaks the work
into:

1. freeze checkpoint RAM signals in `docs/architecture/research/2026-04-21-s3k-trace-addresses.md`
2. S3K recorder completion
3. replay-side checkpoint detector and elastic-window harness
4. checkpoint and zone/act aux-event parsing/reporting
5. fixture recording and import
6. engine parity fixes until the default-suite replay test is green
7. end-to-end verification on the real BK2
