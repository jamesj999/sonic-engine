# S3K Structural Replay Phase Detection

## Goal

Remove the `pre_level_intro_prefix`, `sidekick_seed_frame_prelude`, and
`pre_trace_osc_frames` metadata dependencies from S3K trace replay. Replay must
derive the AIZ prefix boundary structurally and make fresh S3K level bootstrap
execute the native setup path without copying recorded outcome state into the
engine.

## Design

No new execution-provenance event or phase-control metadata is introduced.

For fresh S3K level starts, the headless fixture currently creates Sonic in the
post-initialisation routine before replay drives frame 0. The ROM instead enters
the first `LevelLoop` with the playable object still owning its routine-0
initialisation work. That difference is why a normal replay frame applies
gravity to Sonic too early and why the existing harness compensates with a
sidekick-only prelude plus oscillator pre-advance.

The fixture will instead expose a fresh-level bootstrap path that leaves
playable objects at the same native pre-loop lifecycle boundary as the engine's
ordinary S3K level startup. Replay then drives and compares frame 0 as a normal
full native frame. Sonic initialisation, Tails initialisation/gravity, object
processing, `OscillateNumDo`, animation, and controller history all advance
through their production owners. No trace row selects a subset of engine
systems, and no trace values initialise those systems.

This is a replay-fixture lifecycle correction, not a second S3K gameplay
implementation. It must call the same production startup APIs used by ordinary
S3K level loading. If the production API cannot expose the pre-loop boundary
without replay-only mutation, implementation stops and the design is revisited.

For an intro-prefix capture, the boundaries are distinct:

1. Intro begins when the first recorded `zone_act_state` is outside live LEVEL
   mode.
2. The first LEVEL-mode row is found from the existing `zone_act_state`
   transition and remains `VBLANK_ONLY`.
3. The row after the LEVEL setup boundary is the first driven gameplay
   iteration even when gameplay, VBlank, and lag counters remain pinned.
   Subsequent prefix rows default to full native frames. A row is suppressed
   only when the lag counter directly proves a lag/VBlank-only sample, or when
   it changes controller input while every recorded gameplay field and all
   counters remain identical to the preceding row; that latter case advances
   only the input latch.
4. Strict comparison begins at the existing `gameplay_start` checkpoint.
5. Prefix classification and previous-input driving remain active through the
   `gameplay_start` checkpoint.

## Compatibility

Legacy metadata fields remain parseable so old fixtures and tooling do not fail
to load, but S3K replay policy will ignore them. Recorder metadata generation
will stop emitting the phase-control extras. Existing source guards and
`KNOWN_DISCREPANCIES.md` will remove the compensating phase contracts while
retaining the comparison-only/no-hydration ratchets. Other games retain their
existing bootstrap rules.

## Testing

Focused policy tests will first prove that:

1. AIZ is classified as a pre-level-prefix replay from its recorded transition
   into live LEVEL mode after removing its metadata marker.
2. CNZ frame 0 is produced by one normal native frame from the corrected
   production-backed fresh-level lifecycle boundary, with no sidekick-only or
   oscillator prelude.
3. Conflicting legacy marker values cannot change those classifications.
4. AIZ complete-run, CNZ complete-run, MGZ counter-zero, bonus-stage,
   single-character, and visible-hold starts do not acquire either removed
   prelude.
5. Guards reject inference from frame-zero player/sidekick motion, animation,
   or oscillator outcome values.
6. Pinned counters after a transition into LEVEL mode do not suppress native
   prefix execution; explicit lag-counter evidence and unchanged-state
   input-latch rows remain the only prefix exceptions.

The AIZ and CNZ trace replay tests will verify input alignment and frontier
behavior without another fixture regeneration. Existing trace bootstrap
contract tests, build-tooling guards, negative start-shape fixtures, and the S3K
must-keep-green tests will guard unrelated replay and engine behavior.
