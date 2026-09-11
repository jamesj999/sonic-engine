# Sonic 3&K Special Stage Rewind Design

## Goal

Add held-key live rewind support to the Sonic 3&K Blue Spheres special stage by plugging the existing special-stage rewind framework into the S3K special-stage runtime.

This design covers the in-stage Blue Spheres play mode only. The S3K results screen remains a transition/result mode, not a rewindable special-stage manager. Rewind must stop cleanly at the special-stage lifecycle boundary and must not carry a stage snapshot into the results screen.

## Existing Foundation

The shared special-stage rewind path already exists:

- `SpecialStageProvider` exposes `supportsRewind()` and `rewindAdapter()`.
- Provider adapters implement `RewindSnapshottable<Snapshot>` and provide provider-owned capture and restore.
- `GameplayModeContext.registerSpecialStageAdapter(...)` registers the active provider adapter with `RewindRegistry`.
- `SpecialStageStepper` only replays mapped input and calls provider update during rewind seek/replay.
- `GameLoop` treats unsupported providers as a disabled rewind surface.
- Sonic 2 already has a provider-local adapter and value snapshot design.

S3K should reuse this path directly. Do not add a second special-stage rewind controller, do not special-case S3K in `GameLoop`, and do not make `SpecialStageStepper` understand S3K internals or snapshot state.

## Recommended Architecture

Implement a provider-local snapshot and adapter:

- Add `Sonic3kSpecialStageSnapshot` as an immutable package-local value object under `com.openggf.game.sonic3k.specialstage`.
- Add `Sonic3kSpecialStageRewindAdapter implements RewindSnapshottable<Sonic3kSpecialStageSnapshot>`.
- Its `key()` must return `SpecialStageProvider.SPECIAL_STAGE_REWIND_KEY`, matching the Sonic 2 adapter and the shared special-stage registry contract.
- Let `Sonic3kSpecialStageProvider.supportsRewind()` return `true` only when the adapter is wired and all S3K mutable runtime state is captured.
- Let `Sonic3kSpecialStageProvider.rewindAdapter()` return the S3K adapter.
- Add package-private `captureRewindSnapshot()` and `restoreRewindSnapshot(Sonic3kSpecialStageSnapshot snapshot)` methods on `Sonic3kSpecialStageManager`, mirroring the Sonic 2 manager naming.
- Add package-private capture/restore helpers on mutable subsystem classes where direct manager access would otherwise require broad setters.

The adapter should fail fast if capture or restore is requested before the manager is initialized. The shared rewind framework should never receive a partially initialized S3K stage snapshot.

## Design Alternatives

Use explicit value snapshots rather than reflective field capture. The S3K stage has final subsystem objects, structural ROM data, callbacks, renderer-owned caches, and audio side effects; reflection would make it too easy to capture structural fields that should not be restored or miss semantic side-effect rules.

Do not rebuild state by reloading the layout and replaying only high-level counters. Grid mutations, collision response queue entries, palette fades, background scroll deltas, Tails AI delay buffers, banner state, and clear-sequence state are all mid-frame runtime facts that must round-trip exactly.

Do not replace final subsystem objects during restore. The renderer, collision path, and manager accessor surface assume stable subsystem identities. Restore should mutate existing `grid`, `player`, `collisionQueue`, `perspective`, `background`, `hud`, `banner`, `palette`, `ringConverter`, and `tailsAI` instances in place.

## Snapshot Surface

### Manager State

Capture all manager-owned mutable scalars that can affect future update, render, debug, or transition behavior:

- lifecycle and counters: `currentStage`, `initialized`, `finished`, `emeraldCollected`, `superEmeraldMode`, `ringsCollected`, `spheresLeft`, `ringsLeft`, `frameCounter`;
- input latches: `heldButtons`, `pressedButtons`, `p2HeldButtons`;
- clear and exit flow: `clearRoutine`, `clearTimer`, `emeraldTimer`, `emeraldInteractIndex`, `exitSpinStarted`, `palFadeDelay`, `musicSpedUp`;
- ring animation: `ringAnimTimer`, `ringAnimFrame`;
- legacy manager banner fields: `bannerPhase`, `bannerTimer`, `bannerOffset`;
- Tails/P2 visual and jump state: `tailsAnimTimer`, `tailsMappingFrame`, `tailsTailsAnimTimer`, `tailsTailsMappingFrame`, `tailsJumping`, `tailsJumpHeight`, `tailsJumpVelocity`, `tailsEnabled`, `playerCharacter`;
- debug state: `spriteDebugMode`, `useSkLayouts`.

`renderer`, `dataLoader`, `collision`, and loaded ROM data are structural and must not be included in the snapshot. They are created or loaded by initialization and reused by restore.

### Grid

`Sonic3kSpecialStageGrid` owns the live 32x32 cell buffer. Capture a defensive clone of the full `buffer` array and restore by copying into the existing array.

Layout trailer/start parameters and static layout bytes are structural after initialization. They do not need a separate snapshot if the live buffer and manager/player state are restored.

### Player

`Sonic3kSpecialStagePlayer` must round-trip:

- position and motion: `xPos`, `yPos`, `angle`, `velocity`, `rate`, `rateTimer`;
- movement flags: `turning`, `turnLock`, `advancing`, `started`, `bumperLock`, `bumperInteractIndex`;
- jump state: `jumping`, `jumpHeight`, `jumpVelocity`;
- animation state: `animFrameTimer`, `mappingFrame`, `prevMappingFrame`;
- completion and mode state: `failed`, `clearRoutineActive`, `fadeTimer`, `blueSphereMode`;
- one-frame latch: `rateJustIncreased`.

`rateJustIncreased` is a side-effect latch consumed by the manager in the same update that sets audio tempo. Snapshot capture should represent the post-update state without replaying the tempo side effect on restore. The safest implementation is to capture the field for deterministic state inspection but ensure restore does not trigger an audio tempo change until `player.update(...)` computes a fresh rate increase on a later frame.

### Tails AI and P2 State

`Sonic3kSpecialStageTailsAI` owns the delayed P1 input/jump buffers used by Tails when Player 2 is idle. Capture:

- `posTableInput` clone;
- `posTableJump` clone;
- `posTableIndex`;
- `cpuIdleTimer`;
- `lastP2Input`.

Manager-owned Tails animation and jump fields are captured with manager state. Restore must preserve the relationship between the AI delay buffers and those manager fields so rewinding across a Sonic jump, spring jump, or P2 takeover does not desync Tails.

### Collision Response Queue

`Sonic3kSpecialStageCollisionQueue` owns in-flight ring and blue-sphere animations. Capture defensive clones of:

- `types`;
- `timers`;
- `frames`;
- `gridIndices`.

The blue-sphere callback is structural and is provided each frame by `Sonic3kSpecialStageManager.update(...)`; do not capture callbacks.

### Ring Converter

`Sonic3kSpecialStageRingConverter.seedBlueConverted` is mutable but only meaningful during a single synchronous `convert(...)` call. Prefer converting it to a local variable if practical. If it remains a field, include it in the snapshot helper so a restore cannot inherit stale converter state from a previously interrupted conversion path.

### Perspective

`Sonic3kSpecialStagePerspective` has structural loaded data and live frame selection:

- capture `animFrame` and `paletteFrame`;
- do not capture `perspectiveMaps` or `framePointers`.

Loaded perspective data is initialized from ROM and should remain stable for the active stage.

### Background

`Sonic3kSpecialStageBackground` must capture:

- `vScroll`;
- `hScroll`;
- `prevXPos`;
- `prevYPos`.

The previous position fields are required because the next update computes scroll from deltas, not from absolute player position alone.

### Palette

`Sonic3kSpecialStagePalette` must capture:

- deep copies of all four active `Palette` lines;
- `stagePaletteData` clone or a stable structural reference with restore-time validation;
- `fadeActive`.

After restore, upload the restored palettes to `GraphicsManager` through `Sonic3kSpecialStagePaletteUploader.cacheAll(...)`. Otherwise the model state can rewind while the rendered palette remains at the later frame.

### HUD

`Sonic3kSpecialStageHud` must capture:

- `sphereHudDirty`;
- `ringHudDirty`;
- `displayedSphereCount`;
- `displayedRingCount`.

This prevents a rewind to an earlier count from leaving the cached HUD digits marked clean with later displayed values.

### Banner

`Sonic3kSpecialStageBanner` must capture:

- `phase`;
- `slideOffset`;
- `displayTimer`;
- `triggeredAdvance`;
- `showPerfect`.

Manager-owned legacy banner fields are captured separately even if current rendering uses the `Sonic3kSpecialStageBanner` object. This avoids hidden regressions if older render/debug paths still query manager fields.

### Game State and Emerald Flags

The S3K special stage currently writes emerald collection into `GameStateManager` during `collectEmerald()`. Rewinding across emerald collection requires restoring both the manager-local `emeraldCollected` flag and the durable game-state emerald flag.

Implementation should prefer the existing `GameStateManager.capture()` / `restore(GameStateSnapshot)` API inside the S3K special-stage snapshot. That API already covers `emeraldCount`, `gotEmeralds`, and `gotSuperEmeralds`, and avoids adding a second emerald restore surface. Capture the game-state snapshot alongside manager state and restore it after manager-local emerald fields so the durable state and current stage agree.

If implementation chooses a narrower emerald-only snapshot for performance or isolation, it must still cover:

- whether the current normal emerald was collected before capture;
- whether the current super emerald was collected before capture;
- whether `superEmeraldMode` was active;
- `currentStage`.

Restore must reconcile the game-state emerald flag to the captured value. Do not clear unrelated emeralds unless using the full existing `GameStateSnapshot`, whose purpose is to restore the entire gameplay state.

### Audio Side Effects

Snapshot restore must not replay SFX. Specifically, restore should not call collision, collection, jump, spring, perfect, goal, all-spheres, or ring SFX paths.

Music speed is persistent external state and must match the restored manager state:

- if `musicSpedUp` is false, restore speed multiplier to `1`;
- if `musicSpedUp` is true, restore speed multiplier from `player.calculateMusicTempo()`;
- on stage exit/reset, continue to reset speed multiplier to `1`.

If the restored frame is inside the emerald clear sequence after `updateClearEmeraldLoad()` has switched to emerald music, restore should not restart music unless the existing audio manager has an idempotent "ensure current music" API. Treat music track rewind as a known follow-up unless tests show a concrete mismatch.

## Boundary Rules

Entering S3K special stage initializes the manager and creates a fresh rewindable timeline. Leaving the stage, entering the results screen, resetting the special-stage manager, or switching debug layout sets must invalidate the current special-stage rewind history.

Provider debug methods that reinitialize the stage are boundaries:

- `debugNextStage()`;
- `debugToggleLayoutSet()`.

Special-stage debug shortcut keypresses are live-only boundaries in `GameLoop.detectSpecialStageLiveOnlyShortcutBoundary()`. Pressing sprite-debug, plane-debug, alignment, lag, next-stage, layout-set, complete, fail, or special-stage toggle shortcuts must sever the current rewind session before applying the shortcut.

After that boundary, persistent debug state can still be part of the new timeline:

- `toggleSpriteDebugMode()` should round-trip through `spriteDebugMode`;
- plane debug and alignment methods are currently no-ops and do not require snapshot state;
- lag compensation methods are currently no-ops and do not require snapshot state.

## Testing Strategy

Add focused unit tests before enabling provider support:

- provider capability: S3K reports `supportsRewind() == true` and returns an adapter whose `key()` is `SpecialStageProvider.SPECIAL_STAGE_REWIND_KEY` once implementation is complete;
- adapter lifecycle: capture before initialization fails clearly; capture/restore after initialization delegates to the manager;
- manager round-trip: initialize a stage, mutate representative manager fields through gameplay or package-private test hooks, capture, mutate again, restore, and assert state equality;
- grid round-trip: live buffer mutations restore exactly;
- player round-trip: position, angle, rate timer, movement flags, jump state, animation state, clear/fade state, and `rateJustIncreased` restore;
- Tails AI round-trip: delayed input/jump buffers and P2 idle takeover state restore;
- collision queue round-trip: in-flight ring and blue-sphere animations continue from the restored timers and frames;
- background/perspective/HUD/banner/palette round-trip: each subsystem restores the fields listed above;
- emerald state round-trip: rewinding across `collectEmerald()` restores only the affected current-stage emerald flag;
- audio tempo restore: rewinding from sped-up state and non-sped-up state sets the speed multiplier appropriately without replaying SFX.

Then add integration coverage:

- update `TestSpecialStageRewindCapability` so S1, S2, and S3K are all supported and a dummy unsupported provider still disables rewind;
- extend shared special-stage rewind tests to exercise S3K adapter registration through `GameplayModeContext`; if the test captures the registry, initialize the S3K manager first with a headless/ROM-backed fixture, otherwise limit the registration assertion to capability and adapter key;
- add an S3K special-stage rewind smoke test that captures after collecting at least one sphere/ring, advances several frames, restores, and verifies grid/counter/player/palette-visible state.

Keep the tests headless. Do not add ROM-asset requirements beyond the existing S3K special-stage tests unless the test already opts into `s3k.gen`.

## Manual Acceptance

With a valid `s3k.gen`, manually verify held rewind in S3K Blue Spheres across:

- initial banner slide-out and auto-advance;
- blue-sphere collection and delayed red-sphere conversion;
- ring collection and ring animation queue;
- sphere-to-ring conversion and PERFECT banner re-entry;
- bumper and spring interactions;
- red-sphere failure exit spin and white fade;
- all-spheres clear sequence, emerald placement, emerald collection, and transition to results;
- Tails AI jump following and Player 2 takeover;
- debug next-stage/layout-set boundaries.

## Risks

The largest correctness risks are external or side-effect state:

- emerald collection writes durable game state outside the manager;
- audio speed lives outside the special-stage manager;
- palette data has both Java model state and graphics-manager cache state;
- one-frame latches such as `rateJustIncreased` can duplicate side effects if restored naively.

The implementation should prefer explicit restore hooks with narrow tests for these cases over broad "all fields" copying.

## File-Touch List

Expected production files:

- `src/main/java/com/openggf/game/sonic3k/specialstage/Sonic3kSpecialStageSnapshot.java`
- `src/main/java/com/openggf/game/sonic3k/specialstage/Sonic3kSpecialStageRewindAdapter.java`
- `src/main/java/com/openggf/game/sonic3k/specialstage/Sonic3kSpecialStageProvider.java`
- `src/main/java/com/openggf/game/sonic3k/specialstage/Sonic3kSpecialStageManager.java`
- `src/main/java/com/openggf/game/sonic3k/specialstage/Sonic3kSpecialStageGrid.java`
- `src/main/java/com/openggf/game/sonic3k/specialstage/Sonic3kSpecialStagePlayer.java`
- `src/main/java/com/openggf/game/sonic3k/specialstage/Sonic3kSpecialStageTailsAI.java`
- `src/main/java/com/openggf/game/sonic3k/specialstage/Sonic3kSpecialStageCollisionQueue.java`
- `src/main/java/com/openggf/game/sonic3k/specialstage/Sonic3kSpecialStageRingConverter.java`
- `src/main/java/com/openggf/game/sonic3k/specialstage/Sonic3kSpecialStagePerspective.java`
- `src/main/java/com/openggf/game/sonic3k/specialstage/Sonic3kSpecialStageBackground.java`
- `src/main/java/com/openggf/game/sonic3k/specialstage/Sonic3kSpecialStagePalette.java`
- `src/main/java/com/openggf/game/sonic3k/specialstage/Sonic3kSpecialStageHud.java`
- `src/main/java/com/openggf/game/sonic3k/specialstage/Sonic3kSpecialStageBanner.java`
- focused `GameStateManager` emerald-slot restore support if needed

Expected test files:

- S3K special-stage snapshot/adapter tests under `src/test/java/com/openggf/game/sonic3k/specialstage`
- updates to existing shared special-stage rewind capability and registration tests
