# Audio Rewind Tier 2 Logical SMPS Snapshot Implementation Plan

> **For:** Codex / future agents
> **Goal:** Implement logical audio snapshots for SMPS sequencing without claiming chip or PCM parity.

## Scope

Tier 2 captures backend-agnostic logical audio state: AudioManager intent,
backend music/SFX descriptors, override stack descriptors, SmpsDriver state,
and SmpsSequencer per-track state. OpenAL remains presentation-only. YM2612,
PSG, DAC, and resampler phase snapshots are deferred to Tier 3.

## Current Status

Tier 2a is implemented and focused-verified:

- Added `AudioLogicalSnapshot`.
- Added `AudioSourceDescriptor`.
- Added `AudioManager.captureLogicalSnapshot()`.
- Added `TestAudioLogicalSnapshot`.

The implemented seam captures AudioManager intent descriptors only. Tier 2b,
2c, and 2d remain to be implemented.

## Design Decisions

1. Implement Tier 2 in subtiers:
   - 2a: snapshot contracts, descriptor records, and OpenAL exclusion tests
   - 2b: backend logical graph extracted from `LWJGLAudioBackend`
   - 2c: `SmpsDriver` and `SmpsSequencer` compact snapshots
   - 2d: sparse audio keyframe store and replay-to-target integration
2. Snapshot records are manual value records, not reflection captures.
3. Immutable ROM/SMPS/DAC/config data is referenced by stable descriptors.
4. Mutable arrays in sequencer tracks are deep-copied on capture and restore.
5. Tier 2 tests compare logical SMPS/debug state, not PCM samples.
6. Restoring logical audio state clears/rebuilds presentation queues instead of
   restoring OpenAL native objects.

## Files

Expected production files:

- `src/main/java/com/openggf/audio/rewind/AudioLogicalSnapshot.java`
- `src/main/java/com/openggf/audio/rewind/AudioSourceDescriptor.java`
- `src/main/java/com/openggf/audio/rewind/AudioKeyframeStore.java`
- `src/main/java/com/openggf/audio/rewind/SmpsDriverSnapshot.java`
- `src/main/java/com/openggf/audio/rewind/SmpsSequencerSnapshot.java`
- `src/main/java/com/openggf/audio/rewind/SmpsTrackSnapshot.java`
- `src/main/java/com/openggf/audio/LogicalAudioRuntime.java`
- `src/main/java/com/openggf/audio/SmpsPlaybackGraph.java`
- `src/main/java/com/openggf/audio/AudioManager.java`
- `src/main/java/com/openggf/audio/LWJGLAudioBackend.java`
- `src/main/java/com/openggf/audio/driver/SmpsDriver.java`
- `src/main/java/com/openggf/audio/smps/SmpsSequencer.java`

Expected tests:

- `src/test/java/com/openggf/audio/TestAudioLogicalSnapshot.java`
- `src/test/java/com/openggf/audio/TestSmpsSequencerSnapshot.java`
- `src/test/java/com/openggf/audio/TestSmpsDriverSnapshot.java`
- `src/test/java/com/openggf/audio/TestAudioKeyframeReplay.java`

## Steps

1. Add failing Tier 2a tests: **done**
   - snapshot records exclude OpenAL presentation identifiers
   - AudioManager intent snapshot captures ring alternation and timeline cursor
   - descriptor records identify base/donor music and SFX without object ids
2. Add `AudioSourceDescriptor`, `AudioLogicalSnapshot`, and minimal capture
   methods that satisfy Tier 2a without changing playback behavior. **done**
3. Self-review Tier 2a for accidental OpenAL/native buffer capture. **done**
4. Add failing Tier 2b tests around current music id, SFX blocked state,
   pending restore, speed state, and override stack descriptors.
5. Extract a backend-agnostic `SmpsPlaybackGraph` or `LogicalAudioRuntime` from
   `LWJGLAudioBackend` while leaving OpenAL as a drain/presentation adapter.
6. Add failing Tier 2c sequencer tests:
   - tempo/sample counters round-trip
   - fade state round-trips
   - track cursor, loops, return stack, modulation, envelopes, and DAC flags
     round-trip with deep array copies
7. Implement `SmpsSequencerSnapshot` and `SmpsTrackSnapshot`.
8. Add failing Tier 2c driver tests:
   - active sequencer set restores
   - FM/PSG locks and PSG latches restore by snapshot-local sequencer id
   - continuous SFX counters restore
9. Implement `SmpsDriverSnapshot`.
10. Add failing Tier 2d keyframe/replay tests using the Tier 1 command
    timeline.
11. Implement sparse `AudioKeyframeStore` and audio seek/replay plumbing.
12. Run focused Tier 2 suite, Tier 1 suite, and backend-bypass scan.
13. Run full `mvn -Dmse=off test`; merge to local `develop` only if the whole
    repository is green.

## Non-Goals

- No YM2612 operator/channel phase snapshots.
- No PSG noise/counter snapshots.
- No DAC playback phase snapshots.
- No resampler/high-pass history snapshots.
- No bit-exact PCM assertions.
- No OpenAL source/buffer/native object snapshots.

## Self-Review Checklist

- No OpenAL field is part of an authoritative snapshot record.
- Source descriptors can rebuild state without relying on Java object identity.
- All mutable sequencer arrays are copied on capture and restore.
- Tests compare logical state only and do not imply sample parity.
- Snapshot memory is bounded by descriptors and compact primitive records.
- Paused frame-step remains explicitly identified as a later deterministic
  runtime hook if not completed in the current subtier.
