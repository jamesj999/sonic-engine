# LWJGL Runtime-Presentation Migration Design

**Date:** 2026-05-26
**Status:** Draft for review
**Scope:** Migrate `LWJGLAudioBackend` to use `StreamBackedDeterministicAudioRuntime` as the sole source of presentation PCM. Retire the backend-private `pcmHistory`/`reverseCursor` path. Prerequisite for [reverse-resynth-design](2026-05-26-reverse-resynth-design.md).

## Problem

`LWJGLAudioBackend.supportsDeterministicRuntimePresentation()` returns `false` at line 952. `AudioManager.configureDeterministicRuntimeForBackend()` therefore installs `NoOpDeterministicAudioRuntime` for the production backend. Production audio drains via `LWJGLAudioBackend.fillBuffer()` at line 694, which reads from a backend-private `PcmHistoryRing` + `ReverseCursor` allocated at line 194. The runtime path (`StreamBackedDeterministicAudioRuntime`) is dead code in production.

Two paths coexist for the same job. Resynth (and any future runtime-side audio work) cannot land cleanly until one of them is retired.

## Decision

Migrate production to the runtime path. Delete the backend-private duplicate.

## Architecture

### Code-level changes

1. `LWJGLAudioBackend.supportsDeterministicRuntimePresentation()` returns `true`.
2. `LWJGLAudioBackend.init()` no longer allocates `pcmHistory`. Field deleted.
3. `LWJGLAudioBackend.reverseCursor` field deleted.
4. `LWJGLAudioBackend.beginReversePresentation()` and `endReversePresentation()` become **true no-ops**. `AudioManager.beginReverseAudioPresentation` / `endReverseAudioPresentation` already call the runtime side at lines 583/591, so the backend has nothing left to do for reverse mode.
5. `LWJGLAudioBackend.attachDeterministicAudioRuntime` (line 944) asserts that the attached runtime returns `providesPresentationPcm() == true`. Throws `IllegalStateException` with a clear message if not. Fails loudly at wire-up time, not mid-stream.
6. `LWJGLAudioBackend.fillBuffer()` simplified. Extract a package-private seam for testing:

```java
private void fillBuffer(int bufferId) {
    synchronized (streamLock) {
        fillPresentationBuffer(streamData, STREAM_BUFFER_SIZE);
    }
    // ... DirectBuffer fill and OpenAL upload, unchanged
}

// Package-private for test access via TestLwjglRuntimePresentationRoundTrip
void fillPresentationBuffer(short[] target, int frames) {
    Arrays.fill(target, (short) 0);
    deterministicAudioRuntime.drainPcm(target, frames);
    clearCompletedRuntimeSfxIfNeeded();
}
```

7. `LWJGLAudioBackend.playSfxSmps` (line 473-522) — reshape the conditional explicitly around music presence:

```java
if (smpsDriver != null) {
    // Music is active: add SFX sequencer into the music driver
} else {
    // No music: create or reuse standalone SFX driver, bind to runtime.setSfxStream
}
```

Drops the `currentStream == smpsDriver || runtimeProvidesPresentationPcm()` form. Same behavior, intent expressed clearly.

8. Collapse all other `runtimeProvidesPresentationPcm()` branches across the backend: `playMusicSmps` (line 326), `playSmps` (lines 355, 380, 417), `stopStream` (line 568), `playSfxSmps` (line 518). The runtime branch always executes; the legacy branch is dead.
9. Introduce a private helper `requirePresentationRuntime()` returning the runtime. Centralizes the post-migration assumption in one place. Called by post-migration code paths that need the runtime, throws if the runtime doesn't provide presentation PCM (defense in depth — the attach-time assertion should already have caught this).
10. **Remove embedded `startStream()` calls from `playMusicSmps` (line 331), `playSmps` (line 422), and `playSfxSmps` (line 529).** Rationale: post-migration, the consuming runtime calls `consumeCommands()` inside `advanceFrame()`, which invokes `AudioManager.replayTimelineCommand()`, which can call backend `playSmps()`. If `playSmps` calls `startStream()` synchronously, `startStream` prefills via three `fillBuffer()` calls, each of which `drainPcm()`s the runtime FIFO **before the current frame's PCM has been produced**. That drain consumes stale FIFO content and creates startup latency. `updateStream()` already calls `startStream()` when `streamBuffers == null` (line 592-594), so deferring startup to the next `updateStream` tick is mechanically sufficient and avoids the reentrancy.

11. **Tighten `updateStream`'s `hasStream` predicate.** Today: `hasStream = currentStream != null || sfxStream != null || runtimeProvidesPresentationPcm()`. Post-migration, the third term is always true, so `updateStream()` would refill OpenAL buffers with silence forever even when no music/SFX is active. That is **not** intended. Add `DeterministicAudioRuntime.hasActivePresentation()` (default `false`) and override in `StreamBackedDeterministicAudioRuntime` to return true when any of:
    - bound music or SFX stream is non-null, OR
    - `outputFifo` has unread frames, OR
    - reverse cursor is active, OR
    - release crossfade is in progress.

    Then `hasStream = currentStream != null || sfxStream != null || deterministicAudioRuntime.hasActivePresentation()`. This keeps behavior intentional: drain when there's actually content, idle when there isn't. The reverse-cursor and crossfade branches are required so those transient presentation states aren't truncated by the predicate going false.

### Behaviors preserved

- `currentStream`, `sfxStream`, `currentSmps`, `smpsDriver` — still tracked as logical/save-state references. `doRestoreMusic` (line 628) and `MusicState` still need them.
- `musicStack` and the music override save/restore flow — unchanged.
- `playSmps` clears `standaloneSFX` deliberately when starting non-override music — preserved.
- `doRestoreMusic` calls `bindRuntimePresentationStreams()` at line 656 to rebind both music and SFX after restore — preserved. Test (#6) covers this explicitly.
- Speed shoes / speed multiplier propagation — `setSpeedShoes` / `setSpeedMultiplier` mutate backend fields and `currentSmps` as today. The runtime has no speed API; it reads from the bound driver after the mutation, so no runtime hooks are needed. No new methods on `DeterministicAudioRuntime` for speed.
- WAV-fallback SFX (`sfxFallback`, `sfxSources`, `playSfx(String, float)`) — orthogonal to SMPS, unaffected.
- `NullAudioBackend` — inherits the default `false` for `supportsDeterministicRuntimePresentation`, continues to use `NoOpDeterministicAudioRuntime`. Untouched.

### Profile sections

- `audio.music_stream` — now wraps the `drainPcm` call inside `fillPresentationBuffer`. Comment updated to reflect that the section measures runtime drain time, not direct stream read.
- `audio.sfx_stream` — **deleted** from `fillBuffer`. WAV one-shot cleanup runs in `update()` after `updateStream()`, not in `fillBuffer`; DirectBuffer prep is already covered by `audio.upload`. Keeping the section under a changed meaning would silently rewrite profiler-timeline history. Removing it is the honest choice.
- `audio.upload` — unchanged.

## Architectural note

`LWJGLAudioBackend` and `StreamBackedDeterministicAudioRuntime` share mutable `SmpsDriver` instances after migration. The reverse-resynth design's "mutate live driver" strategy still requires the rewind-bracket snapshot/restore documented in the parked resynth spec (P1.2 fix). This migration does not by itself make live-driver mutation safe during reverse playback; it only makes the pipeline coherent.

## Edge cases

1. **Test fixtures that pin `LWJGLAudioBackend` with a `NoOpDeterministicAudioRuntime`.** Fail at `attachDeterministicAudioRuntime` with a clear message naming what to use instead. Tests that wanted the no-op behavior should use `NullAudioBackend` or a test-only backend that advertises `supportsDeterministicRuntimePresentation() == false`.

2. **Backend init failure path.** `AudioManager.setBackend` calls `backend.init()` first, then `configureDeterministicRuntimeForBackend()` which calls `attachDeterministicAudioRuntime()`. The new attach-time assertion fires during attach, **after** init completes. `setBackend` catches `Exception` and falls back to `NullAudioBackend` at line 153, so a failing assertion at attach time degrades production audio to null rather than crashing the engine — same as today's init-failure handling.

3. **`stopStream` clearing.** Today it stops `smpsDriver`, clears runtime music, optionally clears runtime SFX based on call site. Post-migration: it clears runtime music unconditionally (was already gated only by `runtimeProvidesPresentationPcm()`), preserves the existing `playSmps`-side standalone-SFX clearing on non-override music start.

4. **Continuous SFX detection** (line 457-471). Reads `smpsDriver` and `sfxStream` identifiers — independent of which path produces PCM. Unchanged.

5. **Reverse playback** — drains through `StreamBackedDeterministicAudioRuntime.drainPcm` which already routes to `ReverseCursor.readPrevious` over its own ring (line 134-138 of the runtime). Backend's `beginReversePresentation`/`endReversePresentation` are no-ops. `AudioManager.beginReverseAudioPresentation` flow is unchanged externally.

6. **`startStream()` reentrancy during command replay.** Addressed by item 10 in the architecture section: post-migration backend command handlers (`playMusicSmps`, `playSmps`, `playSfxSmps`) do NOT call `startStream()` directly. Stream startup is driven exclusively from `updateStream()` at the regular backend tick cadence, which already has the "if not started, start" branch. Test #1 below locks this by asserting that after a `playMusicSmps` issued through the runtime command path, the OpenAL stream is NOT prefilled until the next `updateStream()` runs.

## Testing

### Acceptance gates (user-confirmed)

1. All existing audio unit tests pass after migration.
2. New integration test exercising the runtime-presentation round trip (below).

### New tests in `TestLwjglRuntimePresentationRoundTrip`

Headless (no OpenAL). Backend stand-in or `LWJGLAudioBackend` test fixture; test target is the package-private `fillPresentationBuffer` seam.

1. **Forward music produces PCM through runtime, with stub driver, no startup reentrancy.** `ScriptedSmpsDriver` (test double implementing `AudioStream`, emits a known ramp). Wire as music stream via runtime command path; assert `isStreamStarted() == false` immediately after the command (no embedded `startStream()` from `playMusicSmps`). Then invoke `updateStream()`; assert `isStreamStarted() == true` and that draining `fillPresentationBuffer` produces PCM matching the ramp. Locks both the PCM correctness AND the startup-deferral invariant from finding P1.

   Test seam: add a package-private `boolean isStreamStarted()` accessor on `LWJGLAudioBackend` returning `streamBuffers != null`. Co-located with `fillPresentationBuffer` (already package-private for test access). Avoids reflection on private state.
2. **SFX routes into `smpsDriver` when music exists.** `playSfxSmps` with `smpsDriver != null`. Assertion strategy depends on what `SmpsDriver` actually exposes:
    - **Preferred (if practical):** inject a test-only `SmpsDriver` subclass that records `addSequencer(seq, true)` calls. Assert one such call after `playSfxSmps`. Stable and behavior-precise.
    - **Fallback (if subclassing the driver is awkward):** assert `sfxStream` stays null AND the runtime SFX binding stays null/unchanged, plus a PCM smoke (drain N samples, assert any non-silence). Looser but resilient to internal driver changes.

   The plan picks one of these at implementation time based on what `SmpsDriver` exposes today; the implementation plan should call out the choice.
3. **Standalone SFX activates when `smpsDriver == null`.** Stop music. `playSfxSmps`. Assert `sfxStream` is non-null and `runtime.musicStream`/`sfxStream` bindings are correct.
4. **Reverse playback drains from the runtime ring.** Forward-step N frames of scripted PCM. `AudioManager.beginReverseAudioPresentation()`. Drain. Assert PCM matches forward output in reverse order (sample-exact — no resynth involved yet).
5. **`restoreMusic` rebinds the restored driver into the runtime.** Play music A, override with B, `restoreMusic`. Assert `currentStream` references A's driver AND the runtime's music stream binding equals A's driver. Identity check, not PCM.
6. **`stopStream` clearing semantics, split by caller path:**
    - `stopStream()` direct → runtime music stream cleared.
    - `playSmps` non-override path → standalone SFX cleared deliberately if it was present.
    - `restoreMusic` path → standalone SFX preserved and rebound through `bindRuntimePresentationStreams`.

### New test in `TestAudioManagerRuntimeInstallation` (extension)

7. When `setBackend` installs a backend whose `supportsDeterministicRuntimePresentation() == true`, the installed runtime has `providesPresentationPcm() == true`. Locks the invariant at the AudioManager level rather than only on LWJGLAudioBackend.

### Real-SMPS smoke test

8. **One smoke test** plays real S2 test SMPS data through the full path (`AudioManager` → `LWJGLAudioBackend` → runtime → `fillPresentationBuffer`). Asserts no exceptions and that *some* non-silent sample is produced after N steps (where N is chosen large enough to outlast SMPS startup delay/rest behavior). Provides confidence that real synthesis still routes; does not assert specific PCM shape.

### Manual smoke (user-side, not codified)

9. Boot each game (S1, S2, S3K) on the migration branch, play through 30 seconds of music + SFX, hold rewind, restore. Confirm subjective parity with `develop`.

## Files Touched

| File | Change |
|------|--------|
| `src/main/java/com/openggf/audio/LWJGLAudioBackend.java` | Flip `supportsDeterministicRuntimePresentation`; delete `pcmHistory`, `reverseCursor`; collapse runtime conditionals; reshape `playSfxSmps` around `smpsDriver != null`; remove embedded `startStream()` from play* handlers; extract `fillPresentationBuffer` + `isStreamStarted` test seams; add attach-time assertion; add `requirePresentationRuntime`; update profile-section comments; switch `updateStream` to use `hasActivePresentation` predicate. |
| `src/main/java/com/openggf/audio/runtime/DeterministicAudioRuntime.java` | Add `default boolean hasActivePresentation() { return false; }`. |
| `src/main/java/com/openggf/audio/runtime/StreamBackedDeterministicAudioRuntime.java` | Override `hasActivePresentation` per the predicate defined in architecture item 11. |
| `src/test/java/com/openggf/audio/TestLwjglRuntimePresentationRoundTrip.java` | New. Six routing/ownership tests + reverse-playback test + real-SMPS smoke test. |
| `src/test/java/com/openggf/audio/TestAudioManagerRuntimeInstallation.java` | Extend with `providesPresentationPcm` invariant test. |

## Out of Scope

- Reverse audio re-synthesis. Lives in [reverse-resynth-design](2026-05-26-reverse-resynth-design.md), depends on this migration.
- Any change to `PcmHistoryRing`, `AudioFrameClock`, or snapshot records. These are exercised through this migration but not modified.
- Any behavioral change to `StreamBackedDeterministicAudioRuntime` beyond adding the `hasActivePresentation()` predicate.
- Any change to `NullAudioBackend` or its presentation semantics.
- Any change to WAV-fallback SFX path (`sfxFallback`, `sfxSources`).

## Risk

The change deletes a production code path. If `StreamBackedDeterministicAudioRuntime` has a latent bug not caught by existing unit tests, the entire production audio path degrades on the migration commit. Mitigations:
- Test surface above covers routing, ownership, reverse playback, restore, and a real-SMPS smoke.
- Single-PR delivery makes revert trivial.
- The user-side manual smoke test is the final gate before merge.
