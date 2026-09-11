# Audio Rewind Tier 1 Command Timeline Implementation Plan

> **For:** Codex / future agents
> **Goal:** Implement Tier 1 command-level audio rewind support without claiming SMPS, chip, or sample parity.

## Scope

Tier 1 records resolved gameplay audio intent in a frame-ordered timeline. It does not replay SMPS state, snapshot chip state, or make OpenAL authoritative.

## Design Decisions

1. Recording happens after AudioManager resolves the semantic route:
   - `GameSound.RING` records resolved `RING_LEFT` / `RING_RIGHT`, not raw `RING`.
   - donor routes record donor game id and resolved id.
   - speed-shoes commands record semantic speed commands.
2. Frame comes from an explicit AudioManager frame cursor.
3. Order is assigned by `AudioCommandTimeline` as a per-frame monotonic counter.
4. `discardAfter(frame)` removes commands with `entry.frame() > frame`.
5. Suppressed rewind replay scopes still do not record durable future commands.
6. Tier 1 exposes timeline data for tests and later keyframe/replay work, but does not yet consume it to rebuild SMPS/chip state.

## Files

- `src/main/java/com/openggf/audio/rewind/AudioCommand.java`
- `src/main/java/com/openggf/audio/rewind/AudioTimelineEntry.java`
- `src/main/java/com/openggf/audio/rewind/AudioCommandTimeline.java`
- `src/main/java/com/openggf/audio/AudioManager.java`
- `src/main/java/com/openggf/game/rewind/RewindController.java`
- `src/test/java/com/openggf/audio/TestAudioCommandTimeline.java`

## Steps

1. Add failing tests:
   - frame/order assignment
   - donor command recording
   - ring alternation records resolved left/right commands
   - speed shoes records semantic commands
   - discardAfter drops future commands
   - RewindController seek/stepBackward discard future audio timeline entries
2. Add command value model and timeline container.
3. Wire AudioManager command entry points to record resolved commands.
4. Wire RewindController restore/branch paths to discard future audio commands.
5. Run focused Tier 1 tests.
6. Rerun Tier 0 focused suite.
7. Rerun scans:
   - `git diff --check`
   - backend bypass `rg` scanner

## Non-Goals

- No SMPS sequencer snapshot.
- No YM2612/PSG/DAC snapshot.
- No sample parity.
- No OpenAL buffer rewind.
- No replay engine that rebuilds sound from sparse audio keyframes yet.
