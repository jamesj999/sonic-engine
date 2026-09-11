# Reverse Audio Re-Synthesis Design

**Date:** 2026-05-26
**Status:** **Parked** — depends on prerequisite migration (see "Prerequisite" below). External review surfaced six findings (P1.1–P1.3, P2.1–P2.3); P1.2/P1.3/P2.1/P2.2/P2.3 are folded into this revised spec, P1.1 is the prerequisite.
**Scope:** Extend held-rewind audio past the current 10-second silence wall by re-synthesizing historical PCM from audio keyframes.

## Prerequisite

This design assumes `LWJGLAudioBackend` uses `StreamBackedDeterministicAudioRuntime` for presentation. **It does not, today.** `LWJGLAudioBackend.supportsDeterministicRuntimePresentation()` returns `false` at line 952, so production audio drains via `LWJGLAudioBackend.fillBuffer()` (line 694), which reads from its own private `pcmHistory`/`reverseCursor` — entirely outside the runtime path this spec wires into.

The migration is a separate sub-project. Until it lands, the design below cannot be implemented. See [`2026-05-26-lwjgl-runtime-presentation-migration-design.md`](2026-05-26-lwjgl-runtime-presentation-migration-design.md).

## Problem

When a player holds the live-rewind key (`LiveRewindManager`), audio plays in reverse by reading the `PcmHistoryRing` backwards via `ReverseCursor`. The ring is sized at exactly 10 seconds of stereo PCM (`AudioManager.PCM_HISTORY_SECONDS = 10`). Past 10 seconds, the cursor exhausts and `readPrevious` zeros the remainder of its output buffer, producing silence. Engine state continues to rewind correctly because keyframe replay handles it; only audio presentation dies.

## Decision Summary

| Decision | Choice | Rationale |
|----------|--------|-----------|
| Behavior mode | Extended tape-rewind (continue reverse playback past 10s) | Preserves the existing UX; only the time limit changes. |
| Scope | Music + SFX (faithful tape effect) | Snapshot already captures both; faithful is the more useful effect for the UX. |
| Chip emulator strategy | Mutate the live `SmpsDriver` during rewind, with bracket save/restore | Live driver is paused during reverse mode (`advanceFrame` short-circuits). At reverse-start, snapshot the full audio state (music + standalone SFX driver) via `AudioManager.captureLogicalSnapshot`. At reverse-end, restore it before the existing `afterRewindRestore` music resync runs. This eliminates the SFX-driver state bleed that finding P1.2 surfaced. |
| Thread model | Synchronous bursts on the audio drain path | YM2612+PSG emulate at many × real-time; a 1s burst is ~10ms on modern hardware. |

## Architecture

### Components

**Modified**

- `PcmHistoryRing` — add `prependBackward(long startAudioFrame, short[] source, int frames)` that writes at logical indices below the active cursor's `oldestReadableFrame` and extends the cursor's readable window backwards. Existing `floorMod` slot mapping overwrites newest (already-consumed) slots, which is exactly what we need.
- `PcmHistoryRing.ReverseCursor` — add package-private `extendOldestTo(long newOldest)` to lower `oldestReadableFrame`. Required because the cursor gates the early-exit at `readPrevious` line 78.
- `AudioBackendLogicalSnapshot` — add nullable `AudioFrameClock.Snapshot clockSnapshot` field. Records "this driver state corresponds to audio-frame index X." Required for audio-frame ↔ game-frame mapping at keyframes.
- `DeterministicAudioRuntime` — add `default AudioFrameClock.Snapshot captureClockSnapshot() { return null; }` plus `default void restoreClockSnapshot(AudioFrameClock.Snapshot s) {}`. `StreamBackedDeterministicAudioRuntime` implements both against its `frameClock`. `NoOpDeterministicAudioRuntime` keeps defaults.
- `AudioManager.captureLogicalSnapshot()` — reads `deterministicAudioRuntime.captureClockSnapshot()` into the new field. `restoreLogicalSnapshot()` deliberately does NOT restore the clock; the clock snapshot is read by `ReverseResynthesizer` for per-burst lookups only. (If `restoreLogicalSnapshot` restored the clock, then `afterRewindRestore` at rewind end would leave the clock pointing at an old keyframe's audio-frame count, breaking subsequent forward audio-frame indexing.)
- `AudioKeyframeStore` — add `keyframeAtOrBeforeAudioFrame(long audioFrame)` and `replayLiveTo(AudioManager, long targetAudioFrame)` (sibling of existing `replayTo` / `replayToLogicalState`). Internally enters `audioManager.beginRewindReplay(..., REVERSE_RESYNTH)` and iterates command timeline entries whose audio-frame position falls in `(keyframe.audioFrame .. targetAudioFrame]`, calling `audioManager.replayTimelineCommand(entry.command())` for each.
- `AudioReplayReason` — add enum value `REVERSE_RESYNTH`.
- `AudioManager.replayTimelineCommand` — extend so that under a `REVERSE_RESYNTH` scope, `PlaySfx` routes directly to the live `SmpsDriver` / standalone SFX driver chip-state mutation path rather than `AudioBackend.playSfx*` (which records new timeline entries and has unwanted side effects).
- `StreamBackedDeterministicAudioRuntime` — accept an optional `ReverseResynthesizer` in its constructor / setter. In `drainPcm()`, when `reverseCursor != null`, call `resynth.ensureHeadroom(reverseCursor)` before `readPrevious`.

**New**

- `com.openggf.audio.runtime.ReverseResynthesizer` — owns the burst loop. Holds references to `PcmHistoryRing`, `AudioKeyframeStore`, `AudioManager`, and the active `DeterministicAudioRuntime` (for clock restore). No state beyond these references; all per-burst state is local.

### Constants

- `BURST_AUDIO_FRAMES = sampleRate * 1` — synthesize one second per burst.
- `HEADROOM_THRESHOLD_FRAMES = sampleRate / 2` — trigger a burst when cursor headroom (`nextReadableFrame − oldestReadableFrame`) drops below 0.5 seconds.

### Control flow during reverse playback drain

```
StreamBackedDeterministicAudioRuntime.drainPcm(target, frames):
  if reverseCursor != null:
    resynth.ensureHeadroom(reverseCursor)        // new
    int read = reverseCursor.readPrevious(target, frames)
    rememberLastReverseFrame(target, read)
    return read
  // else: unchanged path (FIFO drain + release crossfade)
```

```
ReverseResynthesizer.ensureHeadroom(cursor):
  while cursor.headroom() < HEADROOM_THRESHOLD_FRAMES:
    long targetOldestAudioFrame = cursor.oldestReadableFrame - BURST_AUDIO_FRAMES
    if targetOldestAudioFrame < 0:
      return  // start-of-history floor; cursor exhausts naturally

    AudioLogicalSnapshot keyframe =
        audioKeyframes.keyframeAtOrBeforeAudioFrame(targetOldestAudioFrame)
    if keyframe == null || keyframe.backend().clockSnapshot() == null:
      return  // no usable keyframe (legacy/empty case)

    // 1. Restore live SmpsDriver + standalone SFX driver + clock
    audioManager.restoreLogicalSnapshot(keyframe)
    runtime.restoreClockSnapshot(keyframe.backend().clockSnapshot())

    // 2. Replay timeline commands at game frame keyframe.commandTimelineFrame
    //    (commands queued AT the keyframe's game frame, ordered after the
    //    keyframe's commandEntryCount) under REVERSE_RESYNTH scope.

    // 3. Step game-frame-by-game-frame from keyframe.commandTimelineFrame
    //    forward. The clock is the bridge between game-frame index and
    //    audio-frame index.
    long gameFrame  = keyframe.commandTimelineFrame()
    long audioFrame = keyframe.backend().clockSnapshot().totalSamplesProduced()
    long burstEnd   = cursor.oldestReadableFrame   // exclusive
    short[] musicScratch = ...
    short[] sfxScratch   = ...
    short[] mixedScratch = ...
    int mixedOffset = 0  // grows as we accumulate samples for the burst window

    while audioFrame < burstEnd:
      // (a) Apply any timeline entries whose frame == gameFrame, in order.
      //     replayTimelineCommand under REVERSE_RESYNTH scope routes SFX
      //     directly into the standalone SFX driver chip-state path.
      for entry in timelineEntriesAtFrame(gameFrame):
        audioManager.replayTimelineCommand(entry.command())

      // (b) Pull this game-frame's PCM from both drivers.
      int samplesThisFrame = clock.samplesForNextFrame()
      smpsDriver.read(musicScratch, samplesThisFrame)
      sfxDriver.read(sfxScratch, samplesThisFrame)
      mixSfxIntoMusic(musicScratch, sfxScratch, samplesThisFrame)

      // (c) If this game-frame's audio range overlaps [targetOldest, burstEnd),
      //     copy the overlapping portion into mixedScratch at mixedOffset.
      long thisFrameStart = audioFrame
      long thisFrameEnd   = audioFrame + samplesThisFrame
      long copyStart = max(thisFrameStart, targetOldestAudioFrame)
      long copyEnd   = min(thisFrameEnd,   burstEnd)
      if copyStart < copyEnd:
        int srcOff = (int)(copyStart - thisFrameStart)
        int len    = (int)(copyEnd - copyStart)
        System.arraycopy(musicScratch, srcOff * 2,
                         mixedScratch, mixedOffset * 2, len * 2)
        mixedOffset += len

      audioFrame = thisFrameEnd
      gameFrame++

    // (d) Single prepend at the burst's logical start.
    pcmHistory.prependBackward(targetOldestAudioFrame, mixedScratch, mixedOffset)

  // headroom restored
```

The game-frame walk replaces my earlier audio-frame stepping. Timeline commands have a single source of truth (their `frame()`), so they apply at game-frame boundaries; audio-frame indices are derived from the clock as we walk forward. This addresses finding P1.3.

### Data Flow Diagram

```
  Live forward play                Reverse playback (held rewind)
  ----------------                 ----------------
  SmpsDriver -> AudioStream        ReverseResynthesizer
         |                                |
         v                                v
  advanceFrame                     ensureHeadroom -> restore SmpsDriver from
         |                          keyframe -> replay timeline -> step chips
         v                                |
  PcmHistoryRing (forward writes)  PcmHistoryRing.prependBackward (older slots)
         |                                |
         +------------ ReverseCursor.readPrevious <-+
                              |
                              v
                         drainPcm -> AudioOutputFifo -> OpenAL
```

## Edge Cases

1. **Rewind crosses earliest available keyframe.** `keyframeAtOrBeforeAudioFrame` returns null → `ensureHeadroom` returns without prepending. Cursor exhausts naturally, silence after. Floor shifts from "10 seconds" to "earliest keyframe" (typically frame 0 for a fresh session).

2. **Cursor headroom drops to zero before burst completes.** Synchronous burst blocks the audio drain thread. At ~10ms per second of synth on modern HW, worst case is one audio frame of latency. Acceptable; revisit only if audible.

3. **Resume forward play, then rewind again.** `recordExternalFrame` keeps the audio command timeline + keyframe captures up to date. `advanceFrame` continues to write live PCM into the ring. No special handling.

4. **Seek (vs held-rewind).** `RewindController.seekTo` already calls `audioKeyframes.replayToLogicalState`. PCM ring is not used during seek — the user doesn't expect reverse playback for a single jump. Mode 1 affects only held-rewind drain.

5. **Multiple bursts back-to-back over a long held rewind.** `ensureHeadroom`'s `while` loop handles this — each iteration restores from a slightly earlier keyframe and synthesizes one more second.

6. **`PcmHistoryRing.clear()` during re-synth.** Only called from `AudioManager.afterRewindRestore` (rewind end). Never mid-burst. Safe.

7. **Audio frame-rate drift.** `AudioFrameClock` has `remainder` accumulator. Restoring from keyframe snapshot reproduces the original `samplesForNextFrame()` sequence deterministically.

8. **Physical-slot overlap in `prependBackward`.** When a burst is about to run, the cursor's unread interval `[oldestReadableFrame, nextReadableFrame]` occupies some physical slots in the ring after `floorMod`. Writing into slots that overlap the unread interval would corrupt samples the cursor hasn't yet read. The required invariant on every `prependBackward` call: `(cursor.nextReadableFrame − startAudioFrame + 1) ≤ capacityFrames`. The `HEADROOM_THRESHOLD_FRAMES` trigger guarantees this — at trigger time, unread ≤ 0.5s and the burst adds 1s, so total span ≤ 1.5s, well under the 10s capacity — but `prependBackward` must assert it defensively. Addresses finding P2.2.

9. **WAV-fallback SFX (JUMP/RING/SPINDASH/SKID) are out of scope for the faithful tape effect.** `LWJGLAudioBackend.playSfx(String, float)` (line 984) routes these through `sfxFallback` → `alGenSources` and pushes them into `sfxSources`. They are not in `AudioLogicalSnapshot` and have no chip-state representation. During a re-synth burst, the standalone SFX driver path reproduces SMPS-backed SFX faithfully; WAV-fallback SFX are silent. Test surface: assert WAV `playSfx` calls under `REVERSE_RESYNTH` scope are silent no-ops. Addresses finding P2.3.

## Testing

### Unit tests (no ROM, no OpenGL)

1. `TestPcmHistoryRing.prependBackward_extendsReadableWindow` — write 10 forward frames, prepend 5 older frames. Cursor reads all 15 in reverse order, then silence.
2. `TestPcmHistoryRing.prependBackward_overwritesConsumedSlots` — fill to capacity, advance cursor past N slots, prepend N older slots. Cursor reads the correct historical samples.
3. `TestPcmHistoryRing.prependBackward_invariants` — non-adjacent `startAudioFrame` throws; exceeding capacity throws.
4. `TestAudioFrameClockSnapshotRoundTrip` — capture, restore, verify `totalSamplesProduced` and `remainder` match; verify `samplesForNextFrame()` produces identical sequence post-restore.
5. `TestAudioBackendLogicalSnapshotClockField` — snapshot from configured runtime has non-null `clockSnapshot`; with NoOp runtime, field is null.

### Integration tests (stubbed drivers)

6. `TestReverseResynthesizer_burstFillsHeadroom` — fake `SmpsDriver` whose `read()` writes a deterministic ramp. Real `PcmHistoryRing`, fake `AudioKeyframeStore` with one keyframe at audio-frame 0. Drive cursor to low headroom, call `ensureHeadroom`. Assert window extended and samples match the deterministic pattern.
7. `TestReverseResynthesizer_noKeyframeReturnsCleanly` — empty keyframe store. `ensureHeadroom` returns without throwing, ring window unchanged.
8. `TestReverseResynthesizer_replaysTimelineSfx` — keyframe at frame 0 (no music), command timeline has one `PlaySfx` entry inside the burst window. Fake SFX driver records `playSfx` calls. Assert SFX triggered during burst at the right audio-frame.

### End-to-end with real chips (no ROM)

9. `TestStreamBackedDeterministicAudioRuntime_reverseBeyondTenSeconds` — wire runtime + small history ring (1 second), simulate 5 seconds of normal `advanceFrame` producing recognizable PCM, `beginReversePresentation`, drain past the 1s window. With re-synth installed, PCM keeps flowing; without (control), PCM goes silent.

### Manual smoke test

10. Boot engine, start a level, play ~30 seconds with music, hold rewind. Audio should keep playing in reverse for the full 30 seconds. Compare against `develop` (silence after 10s).

### Performance benchmark (GATING)

11. `BenchmarkReverseResynthesizer` — synth 1s, 5s, and 10s bursts from representative S1/S2/S3K SMPS snapshots with realistic SFX-laden command timelines. Report p50/p95/p99 burst duration. **Gating threshold:** p95 burst duration must be less than (OpenAL stream buffer slack) − safety margin. Concrete budget to be set after measurement; if p95 exceeds it, this feature does not ship synchronously and must move to a worker thread or smaller bursts. Addresses finding P2.1.

### Out of scope

- Sample-exact identity between re-synthesized PCM and originally-played PCM across the 10s boundary. Determinism is by design; verifying bit-exact identity adds complexity for marginal value.
- WAV-fallback SFX faithfulness (covered above under edge case 9).

## Known Open Points (deferred to post-migration review)

1. The `REVERSE_RESYNTH` scope's seam between `replayTimelineCommand` and the live backend's `playSfx*` path. Current design routes SFX directly to the chip-state path, but the precise call shape inside `AudioBackend` (`LWJGLAudioBackend`) needs to be verified to ensure no side effects (timeline re-recording, listener notifications) bleed through. Re-verify against the post-migration backend.
2. Synchronous burst on the audio drain thread vs offloading to a worker. The gating benchmark (test 11) decides this.
3. Whether the rewind-bracket save/restore (P1.2 fix) needs to also reset OpenAL one-shot sources (`sfxSources`) at rewind end to silence WAV SFX triggered mid-rewind by future code paths. Today no live code triggers WAV SFX under `REVERSE_RESYNTH` scope, but if that changes, this is the seam.

## Configuration

No new user-facing configuration in v1. Thresholds (`BURST_AUDIO_FRAMES`, `HEADROOM_THRESHOLD_FRAMES`) are private constants in `ReverseResynthesizer`.

## Files Touched

| File | Change |
|------|--------|
| `src/main/java/com/openggf/audio/runtime/PcmHistoryRing.java` | Add `prependBackward`; cursor `extendOldestTo`. |
| `src/main/java/com/openggf/audio/runtime/DeterministicAudioRuntime.java` | Add `captureClockSnapshot` / `restoreClockSnapshot` defaults. |
| `src/main/java/com/openggf/audio/runtime/StreamBackedDeterministicAudioRuntime.java` | Implement clock snapshot methods; accept `ReverseResynthesizer`; call `ensureHeadroom` in `drainPcm`. |
| `src/main/java/com/openggf/audio/runtime/ReverseResynthesizer.java` | New. |
| `src/main/java/com/openggf/audio/rewind/AudioBackendLogicalSnapshot.java` | Add `clockSnapshot` field. |
| `src/main/java/com/openggf/audio/rewind/AudioLogicalSnapshot.java` | Plumb clock through (no new field — already nested via `backend`). |
| `src/main/java/com/openggf/audio/rewind/AudioKeyframeStore.java` | Add `keyframeAtOrBeforeAudioFrame`, `replayLiveTo`. |
| `src/main/java/com/openggf/audio/rewind/AudioReplayReason.java` | Add `REVERSE_RESYNTH`. |
| `src/main/java/com/openggf/audio/AudioManager.java` | Capture clock snapshot; route `REVERSE_RESYNTH` SFX directly to chip path. |
| `src/main/java/com/openggf/audio/LWJGLAudioBackend.java` | Wire `ReverseResynthesizer` into the `StreamBackedDeterministicAudioRuntime`. |
| `src/test/java/com/openggf/audio/runtime/TestPcmHistoryRing.java` | Add prepend tests. |
| `src/test/java/com/openggf/audio/runtime/TestStreamBackedDeterministicAudioRuntime.java` | Add reverse-beyond-window test. |
| `src/test/java/com/openggf/audio/runtime/TestReverseResynthesizer.java` | New. |
| `src/test/java/com/openggf/audio/runtime/TestAudioFrameClockSnapshotRoundTrip.java` | New. |
| `src/test/java/com/openggf/audio/rewind/TestAudioBackendLogicalSnapshotClockField.java` | New. |
