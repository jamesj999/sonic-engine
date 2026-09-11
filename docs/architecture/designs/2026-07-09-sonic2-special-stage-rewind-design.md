# Sonic 2 Special Stage Rewind Design

## Goal

Add held-key live rewind inside Sonic 2 special stages while keeping the existing
mode-boundary rule: entering or leaving a special stage starts a fresh rewind
timeline. This spec covers Sonic 2 only. Sonic 1 support is provided by the
existing special-stage rewind branch, and Sonic 3 and Knuckles special stages
remain non-rewindable until their own design lands.

## Existing Foundation

The shared framework from the Sonic 1 special-stage rewind work is the base:

- `SpecialStageProvider.supportsRewind()` is the semantic capability gate.
- `SpecialStageProvider.rewindAdapter()` supplies one provider-owned adapter
  under `SpecialStageProvider.SPECIAL_STAGE_REWIND_KEY`.
- `SpecialStageStepper` replays recorded `Bk2FrameInput` rows by installing a
  logical input override, mapping it through `SpecialStageInputMapper`, calling
  `handleInput`, `handlePlayer2Input`, and `update` on the active provider.
- `GameLoop` registers the provider-owned adapter before a rewind-capable
  special-stage mode-entry boundary, records frames after live `update`, and
  clears the special-stage session on results or level return boundaries.
- Live-only debug and tuning shortcuts sever the current special-stage rewind
  session and suppress same-frame recording.

The Sonic 2 work should not add a new shared rewind controller or a new
GameLoop mode. It should plug into this provider/adapter path.

## Architecture

Sonic 2 becomes rewind-capable only after the entire
`Sonic2SpecialStageManager` runtime graph can capture and restore deterministic
simulation state. `Sonic2SpecialStageProvider` then returns `true` from
`supportsRewind()` and returns a `Sonic2SpecialStageRewindAdapter` from
`rewindAdapter()`.

The adapter is game-local and delegates to package-local manager methods:

```java
final class Sonic2SpecialStageRewindAdapter
        implements RewindSnapshottable<Sonic2SpecialStageSnapshot> {
    private final Sonic2SpecialStageManager manager;

    @Override
    public String key() {
        return SpecialStageProvider.SPECIAL_STAGE_REWIND_KEY;
    }

    @Override
    public Sonic2SpecialStageSnapshot capture() {
        return manager.captureRewindSnapshot();
    }

    @Override
    public void restore(Sonic2SpecialStageSnapshot snapshot) {
        manager.restoreRewindSnapshot(snapshot);
    }
}
```

`resetForMissingSnapshot()` should keep the default throwing behavior. The
adapter is registered only while an eligible Sonic 2 special stage is active, so
a missing snapshot means the registration lifecycle is wrong.

## Snapshot Surface

Use explicit snapshot records rather than generic reflection capture. The S2
runtime includes structural renderers, ROM data, callbacks, mutable arrays, and
polymorphic active objects. Explicit records make the capture contract reviewable
and avoid accidentally serializing GL or ROM cache state.

### Manager Snapshot

Create `Sonic2SpecialStageSnapshot` in
`com.openggf.game.sonic2.specialstage`. It should capture:

- `initialized`, `currentStage`, `resultState`, `emeraldCollected`.
- `frameCounter`, `heldButtons`, `pressedButtons`, `p2HeldButtons`,
  `p2LogicalButtons`.
- `tailsControlCounter` and a cloned `tailsCtrlRecordBuf`.
- `lastDrawingIndex`, `checkpointRainbowPaletteActive`,
  `rainbowPaletteCycleIndex`, `pendingCheckpoint`,
  `pendingCheckpointNumber`, `pendingRingRequirement`,
  `pendingRingsCollected`, `pendingFinalCheckpoint`,
  `currentRingRequirement`.
- Alignment/debug state: `spriteDebugMode`, `planeDebugMode`,
  `alignmentTestMode`, `alignmentTestSavedRainbowPalette`,
  `alignmentPendingCheckpoint`, `alignmentFrameIndex`,
  `alignmentFrameTimer`, `alignmentTrackFrameIndex`,
  `alignmentLastDecodedFrameIndex`, cloned `alignmentDecodedTrackFrame`,
  `alignmentDrawingIndex`, `alignmentTriggerOffsetFrames`,
  `alignmentRainbowSpeedScale`, `alignmentRainbowSpeedAccumulator`,
  `alignmentStepByTrackFrame`.
- Lag state: `lagCompensation`, `lagAccumulator`,
  `lagCompensationDisplayEnabled`, `diagnosticWallStartTime`,
  `diagnosticUpdateCount`, `diagnosticTrackAdvances`, `lastFrameTime`,
  `frameSampleCount`, `frameSampleSum`.
- Background/track derived state: `skydomeScrollX`, `alternateScrollBuffer`,
  `lastAlternateScrollBuffer`, `drawingIndex`, `lastAnimFrame`,
  `vScrollBG`, `hScrollDebugTotal`, `hScrollDebugFrames`,
  `lastDebugSegmentIndex`, cloned `decodedTrackFrame`,
  `lastDecodedFrameIndex`, `lastDecodedFlipped`.
- Mutable palette state: deep copies of all initialized `Palette` lines, or at
  minimum a value snapshot for palette line 3 colors 11-13 plus enough baseline
  state to distinguish emerald colors from checkpoint rainbow colors.
- Nested snapshots for the track animator, player topology and player state,
  intro, object manager, checkpoint, and any active alignment checkpoint.

Do not capture ROM/art/cache arrays such as `trackFrames`, `backgroundArt`,
mapping data, or renderer instances. ROM-loaded palette source data remains
structural, but the live `Palette[]` is mutable gameplay/render state and must
be captured. Restore should only re-prime render-facing decoded state and
palette texture caches that are pure products of captured fields.

### Track Animator Snapshot

Add package-local capture/restore on `Sonic2TrackAnimator` for:

- `stageLayout` clone and `layoutLength`.
- `currentSegmentIndex`, `currentFrameInSegment`, `frameDelayCounter`,
  `currentSegmentType`, `currentSegmentFlipped`.
- `speedFactor`, `stageComplete`, `orientationFlipped`,
  `lastOrientationFrame`.

Capturing the layout clone avoids needing to re-read ROM data during restore and
preserves mock-layout tests.

### Player Topology Snapshot

S2 player topology is initialized from the active team configuration and is
structural for the lifetime of one special-stage session, but it is still part
of the rewind contract because collision and render order iterate the manager's
`players` list.

Capture a compact player-topology value:

- Ordered active slots: `PlayerType.SONIC` and/or `PlayerType.TAILS`.
- The `isMainCharacter` role for each slot.
- Which slot is `sonicPlayer`, which slot is `tailsPlayer`, and whether the
  players are linked as each other's `otherPlayer`.

Restore should preserve the existing player objects created during
initialization when the captured topology matches the live topology, then
restore each player's nested state in list order and re-apply `otherPlayer`
links. If the topology does not match, restore should fail fast with a clear
exception instead of rebuilding players silently. A topology mismatch means a
configuration/team boundary happened without a fresh special-stage rewind
session.

After restore, the renderer must still reference the manager's ordered
`players` list. If implementation ever replaces the list instance, it must call
`renderer.setPlayers(players)` immediately after reconstruction.

### Player Snapshot

Add `Sonic2SpecialStagePlayerSnapshot` and package-local
capture/restore methods on `Sonic2SpecialStagePlayer`. Capture:

- Routine state: `routine`, `routineSecondary`.
- Position and subpixel state: `ssXPos`, `ssXSub`, `ssYPos`, `ssYSub`,
  `ssZPos`, render-facing `xPos`/`yPos`, `xVel`, `yVel`, `inertia`, and
  `angle`.
- Player timers and counters: `ssSlideTimer`, `ssHurtTimer`, `ssDplcTimer`,
  `ssInitFlipTimer`, `ssFlipTimer`, and `ssLastAngleIndex`.
- Animation state: `anim`, `prevAnim`, `animFrame`, `animFrameDuration`,
  `mappingFrame`, `globalAnimFrameTimer`.
- Radii, priority, status flags, render flags, and `collisionProperty`.
- Control-record buffer clone, `ctrlRecordIndex`, `swapPositionsFlag`.
- A player-owned invulnerability countdown for this player.

The current S2 player uses `TimerManager` and `SSInvulnerabilityTimer` for
post-bomb invulnerability. That is not safe for special-stage rewind: the
special-stage replay stepper does not tick shared timers, and
`TimerManager.restore()` recreates generic timers without the callback to the
player. The implementation must migrate S2 special-stage invulnerability to a
player-owned countdown that is decremented from the special-stage update path and
captured in the player snapshot. `SSInvulnerabilityTimer` must no longer own S2
special-stage invulnerability because the shared timer manager does not tick
during special-stage rewind replay. Keeping the countdown inside the player
runtime graph makes keyframe restore plus frame replay deterministic.

### Intro Snapshot

Add capture/restore to `Sonic2SpecialStageIntro` for:

- Current phase, phase/frame counters, banner/message positions and visibility.
- Ring requirement and letter flyout state.
- Message letters and banner letters as value snapshots, preserving list order.

Callbacks are not captured; they are structural and should remain installed by
the manager's setup path.

### Object Manager And Object Snapshots

Add `Sonic2SpecialStageObjectManagerSnapshot` and value snapshots for active
objects. Capture:

- `objectLocationData` clone, `stageOffsets` clone, `currentPosition`,
  `currentStage`, `lastProcessedSegment`.
- `ringsCollected`, `perfectRingsTotal`, `currentSpecialAct`,
  `noCheckpointFlag`, `noCheckpointMsgFlag`, `ringsToGoEnabled`,
  `emeraldSpawned`.
- Ordered active object snapshots.

Active objects are polymorphic. Each snapshot should include a type enum and the
base object state:

- Base object state: `state`, `angle`, `depthFixed`, `screenX`, `screenY`,
  `trackFloorY`, `animIndex`, `animFrame`, `animTimer`, `onScreen`,
  `highPriority`.
- Ring extra: `spinFrame`.
- Bomb extra: no extra fields beyond base unless future code adds one.
- Emerald extra: `phase`, `phaseTimer`, `bobbingOffset`, `bobbingCounter`,
  `ringRequirement`, `musicFaded`, `emeraldAwarded`.

Restore should recreate the correct concrete object type, restore base and extra
fields, and reconnect structural references such as the emerald's manager
reference. It must preserve active-object ordering because collision and render
order depend on list order.

### Checkpoint Snapshot

Add capture/restore to `Sonic2SpecialStageCheckpoint` for:

- Phase, phase timer, last result, current checkpoint, ring requirement, rings
  collected.
- Message letters, hand state, rainbow rings, pending checkpoint data, and
  `rainbowOnly`.

Callbacks are structural. Restore must not null or replace
`onCheckpointResolved` or `onMusicFadeRequested`.

### Palette And Renderer State

S2 palette state is gameplay-visible and mutable. Stage initialization creates
four palette lines from ROM, then `applyEmeraldPalette()` mutates palette line 3
colors 11-13. Checkpoint rainbow and rainbow cycling mutate the same three
colors later. A rewind that crosses either mutation must restore the exact live
colors, not recompute from only the current checkpoint flag.

The recommended implementation is to deep-copy all four initialized `Palette`
lines in the manager snapshot. If implementation narrows this to line 3 colors
11-13, it must also capture enough state to restore both the emerald baseline
and the current checkpoint rainbow color phase exactly.

Capture manager-level `checkpointRainbowPaletteActive` and
`rainbowPaletteCycleIndex` as control state, but do not rely on those two fields
as the only source of palette truth. On restore, write the captured palette
colors back to `palettes` and recache touched palette lines through graphics if
graphics services are available; null-guard graphics for headless tests.

Renderer, FBO, shader, and debug text renderer instances are structural and not
captured. Restore must not allocate GL resources in headless tests.

## Debug, Tuning, And Boundary Rules

The shared GameLoop live-only boundary logic remains authoritative. These inputs
are not represented in `Bk2FrameInput` and must sever the current special-stage
rewind session before mutating state:

- Global special-stage key.
- X/Z stage or layout debug.
- Complete/fail debug keys.
- Sprite-debug and plane-debug toggles.
- Sprite-debug navigation.
- Alignment-test toggle and adjustment controls.
- F1 lag-model diagnostics display.

S2 captures the internal lag-model enable state because trace-paced sessions
force the model off. Normal play has no live enable/disable control; F1 only
toggles the read-only diagnostics display and remains a boundary event.

## Audio Rules

The existing rewind controller owns audio replay suppression while held rewind
is active. S2 special-stage snapshot restore should not manually replay SFX or
music when restoring. It should restore flags that determine future live audio
behavior, for example emerald `musicFaded`, `emeraldAwarded`, manager
`resultState`, and lag/music state. If a restore re-enters a state that expects
music tempo to be active, the restore path should set the audio multiplier only
when the live manager state requires it and should be null-guarded for headless
tests.

## Testing Strategy

Headless tests should prove capture/restore correctness at component level.
End-to-end visual rewind remains a manual ROM + GL gate.

Required tests:

- Extend `TestSpecialStageRewindCapability`
  - Assert `Sonic2SpecialStageProvider.supportsRewind()` is true after the
    adapter exists.
  - Assert the provider returns a present adapter whose key is
    `SpecialStageProvider.SPECIAL_STAGE_REWIND_KEY`.
  - Assert Sonic 3 and Knuckles remains non-rewindable.
- `TestSonic2SpecialStagePlayerSnapshot`
  - Cover Sonic-alone, Tails-alone, and Sonic+Tails topology, including
    `isMainCharacter`, list order, `sonicPlayer`/`tailsPlayer` references,
    `otherPlayer` relinking, and renderer player-list binding.
  - Mutate Sonic and Tails player state including control-record buffers,
    routine states, animation fields, hurt state, and invulnerability countdown.
  - Capture, mutate, restore, assert exact value equality.
- `TestSonic2SpecialStageObjectSnapshot`
  - Cover active ring sparkle state, bomb explosion state, emerald phase/timers,
    active-object list ordering, and manager reference reattachment.
- `TestSonic2SpecialStageCheckpointSnapshot`
  - Cover rainbow/message phases, message letters, rainbow rings, hand state,
    pending result data, and callback preservation across restore.
- `TestSonic2SpecialStageRewindSnapshot`
  - Build a manager with initialized nested components or focused test hooks.
  - Mutate manager, track animator, players, intro, object manager, checkpoint,
    mutable palette colors, palette flags, lag state, and decoded track cache.
  - Capture, mutate further, restore, assert the captured state returns.
  - Assert structural fields such as renderer and callbacks remain usable and
    are not replaced with snapshot data.
  - Round-trip snapshots before and after emerald palette application, before
    checkpoint rainbow, during checkpoint rainbow, and after checkpoint rainbow
    clears.
- Extend `TestGameplayModeContextSpecialStageRewindAdapter`
  - Register a Sonic 2 provider and assert the generic special-stage key is
    captured.
  - Deregister and assert the key is removed.
- Keep the existing S1 tests green:
  - `TestSonic1SpecialStageRewindSnapshot`
  - `TestGameLoopSpecialStageRewindBoundary`
  - `TestGameLoopSpecialStageRewindDebugBoundary`
  - `TestLiveRewindManagerSpecialStageMode`

## Manual Acceptance Gate

With `s2.gen` available:

1. Enter a Sonic 2 special stage.
2. Hold live rewind during the intro, normal running, ring collection, bomb hit,
   post-hit invulnerability, checkpoint message, and emerald approach.
3. Verify player position, half-pipe track frame, rings, active objects,
   checkpoint UI, palette rainbow, and invulnerability flash restore cleanly.
4. Verify repeated short backward steps across a keyframe boundary are seamless.
5. Verify no double-triggered SFX or duplicated music fade/jingle occurs during
   replay or release.
6. Verify F1 or alignment/debug controls sever the current rewind session
   and a clean following frame starts a fresh session.
7. Verify the transition to results disables special-stage rewind.
8. Verify Sonic 1 special-stage rewind still works and Sonic 3 and Knuckles
   special stages remain inert.

## Risks

1. **Timer migration risk.** The required move of S2 special-stage
   invulnerability out of `TimerManager` must preserve live gameplay behavior.
   Pin it with focused player tests before enabling provider rewind.
2. **Object list reconstruction risk.** Active object ordering and concrete type
   reconstruction are load-bearing for collision/render parity. Snapshot tests
   must assert list order and type-specific fields.
3. **Callback preservation risk.** Checkpoint and object-manager callbacks are
   structural. Restore must not replace them with null or stale snapshot data.
4. **Palette restore risk.** Checkpoint rainbow palette writes are visible. The
   restore path needs a pure, null-guarded palette re-apply helper.
5. **Lag compensation determinism.** The numeric lag factor and accumulator must
   restore together; otherwise replay may skip a different frame than live play.

## File-Touch List

Create:

- `src/main/java/com/openggf/game/sonic2/specialstage/Sonic2SpecialStageSnapshot.java`
- `src/main/java/com/openggf/game/sonic2/specialstage/Sonic2SpecialStageRewindAdapter.java`
- Focused nested snapshot records as needed in
  `com.openggf.game.sonic2.specialstage`, or package-private nested records if
  they stay readable.
- `src/test/java/com/openggf/game/sonic2/specialstage/TestSonic2SpecialStagePlayerSnapshot.java`
- `src/test/java/com/openggf/game/sonic2/specialstage/TestSonic2SpecialStageObjectSnapshot.java`
- `src/test/java/com/openggf/game/sonic2/specialstage/TestSonic2SpecialStageCheckpointSnapshot.java`
- `src/test/java/com/openggf/game/sonic2/specialstage/TestSonic2SpecialStageRewindSnapshot.java`

Modify:

- `src/main/java/com/openggf/game/sonic2/Sonic2SpecialStageProvider.java`
- `src/main/java/com/openggf/game/sonic2/specialstage/Sonic2SpecialStageManager.java`
- `src/main/java/com/openggf/game/sonic2/specialstage/Sonic2TrackAnimator.java`
- `src/main/java/com/openggf/game/sonic2/specialstage/Sonic2SpecialStagePlayer.java`
- `src/main/java/com/openggf/game/sonic2/specialstage/Sonic2SpecialStageIntro.java`
- `src/main/java/com/openggf/game/sonic2/specialstage/Sonic2SpecialStageObject.java`
- `src/main/java/com/openggf/game/sonic2/specialstage/Sonic2SpecialStageRing.java`
- `src/main/java/com/openggf/game/sonic2/specialstage/Sonic2SpecialStageBomb.java`
- `src/main/java/com/openggf/game/sonic2/specialstage/Sonic2SpecialStageEmerald.java`
- `src/main/java/com/openggf/game/sonic2/specialstage/Sonic2SpecialStageCheckpoint.java`
- `src/test/java/com/openggf/game/TestSpecialStageRewindCapability.java`
- `src/test/java/com/openggf/game/session/TestGameplayModeContextSpecialStageRewindAdapter.java`

Explicitly untouched:

- Sonic 3 and Knuckles special-stage provider and manager, except tests that
  assert it remains non-rewindable.
- Shared GameLoop rewind boundary wiring, unless a test exposes an S2-specific
  bug in the existing provider-agnostic path.
- Level rewind, bonus-stage rewind, and trace replay code.
