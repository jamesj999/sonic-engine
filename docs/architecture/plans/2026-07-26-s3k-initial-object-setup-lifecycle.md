# S3K Initial Object Setup Lifecycle Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use `superpowers:subagent-driven-development` to implement this plan task-by-task, with a fresh implementation worker and two-stage review for every task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Model Sonic 3 & Knuckles' one post-title-card, pre-`LevelLoop` level-object setup pass as a production-owned lifecycle so standalone CNZ advances beyond frame 185 without trace identity, metadata selection, or replay-owned object scheduling.

**Architecture:** Correct the existing S3K title-card approximation first: locked title-card frames advance the native VBlank clock but do not age already-loaded level objects, scroll the camera, or advance oscillation, because the ROM is still dispatching title-card SSTs at that point. A typed request written into `LevelLoadContext` is published as a private `LevelManager` token only after a genuine fresh S3K post-load assembly succeeds; previews, warm/shared reuse, state restoration, and failed loads cannot publish it. Live title-card release, ordinary first-frame execution, and comparison bootstrap may consume that production token but cannot create it; consumption invokes one audited S3K load-then-execute object-dispatch envelope.

**Tech Stack:** Java 21, Maven, JUnit Jupiter, OpenGGF production level/title-card/object lifecycles, ROM-backed S3K trace replay, local `docs/skdisasm/sonic3k.asm`.

## Global Constraints

- The disassembly is the source of truth; cite `docs/skdisasm/sonic3k.asm:7737-7748`, `:7849-7855`, `:7889-7906`, and `:66747-66799` in production comments that encode ordering.
- Trace data remains comparison-only. Replay may consume a production token and apply its one-time frame-zero RNG/VInt bootstrap, but it must never arm the token from `TraceData`, `trace_profile`, filename, zone, route, frame, checkpoint, sidekick metadata, or an expected outcome.
- S1 and S2 retain their current title-card and startup behavior. The new `LevelInitProfile` capability defaults to `NONE`; only `Sonic3kLevelInitProfile` opts in.
- The S3K locked title-card correction must distinguish ROM title-card SST dispatch from the engine's already-loaded level `ObjectManager`.
- The setup pass is object-only because OpenGGF already models playable creation/reset separately. It must not tick Sonic/Tails physics, animation, CPU history, input, control, touch response, or ring collection.
- The setup pass must not advance `LevelManager.frameCounter`, `ObjectManager.vblaCounter`, lag, camera, level events, water, global oscillation, or animated tiles.
- The pending lifecycle is rewind/session state because it may span live title-card frames. Reset, teardown, failed load, restore, and fresh reload must leave a deterministic token.
- `LevelLoadContext` is the production arming authority. `FULL + includePostLoadAssembly + FRESH_LEVEL_ASSEMBLY` may request setup; `PREVIEW_CAPTURE`, decode-only loads, warm/shared-level reuse, and snapshot/complete-run restoration may not.
- Clear any old token on load entry. Publish the new token only after every profile step and level publication succeeds. Any checked or unchecked load failure leaves `NONE`; startup remains fatal through the existing `IOException`/`RuntimeException` propagation.
- Keep current comparison-only bootstrap ordering: initialize live counter phases, consume the production token, then call `applyInitialRngSeedForReplay`.
- Non-goal: this work does not fix CNZ, ICZ, LBZ, or MHZ complete-run frame-zero gaps.
- Non-goal: this work does not replace or broaden the existing complete-run setup/restoration path.
- Non-goal: no recorder schema, fixture metadata, trace profile, diagnostic checkpoint, or trace identity becomes a lifecycle selector.
- No uncompressed trace fixture may be added.
- Update `CHANGELOG.md` and `docs/status/trace-frontier-log.md` when implementation moves the frontier.
- Every implementation commit must use the repository's required trailers and must not bypass hooks.
- Execute Tasks 1-7 strictly in order. Each task starts from the reviewed commit produced by the preceding task; no task in this plan is eligible for parallel dispatch.

---

## Requirements

1. S3K's pre-level title-card wait must stop executing already-loaded level objects. The title provider continues to animate, and the production VBlank clock advances once per locked frame.
2. A genuine fresh S3K post-load assembly requests exactly one initial level-object setup pass after player, sidekick, camera, event, and zone-player initialization. `LevelManager.loadLevel` publishes that request only after the complete load succeeds.
3. Only production lifecycle consumers may consume the pass:
   - live title-card release, immediately before the first ordinary level frame;
   - the first ordinary level-frame path when no title card is presented;
   - trace bootstrap after counter-phase initialization and before frame-zero RNG installation.
4. Consumption is one-shot and idempotent. A second consumer sees `false` and executes no objects.
5. The setup primitive follows S3K's `Load_Sprites` then `Process_Sprites` order:
   - update the two-axis placement cursor;
   - materialize the active spawn window;
   - execute current level-object slots once;
   - flush native child/dynamic allocations and capture the collision-response list for the following frame.
6. The setup primitive runs no player slot, touch response, ring collection, oscillator, camera, event, water, animated tile, level-frame, VBlank, or lag work.
7. `GameRng` remains native before and during setup. Trace bootstrap installs the recorded post-setup frame-zero seed only after token consumption.
8. Rewind capture/restoration preserves whether the setup pass is pending. Teardown and new load clear stale pending state before arming the new lifecycle.
9. Standalone CNZ must advance beyond frame 185 through production state only. Metadata variants cannot alter token arming or consumption.
10. S1/S2 traces and title-card tests, S3K AIZ/MGZ standards, all S3K complete-run frontiers, and S3K special stages must not regress.
11. A setup exception is atomic for lifecycle ownership: the token is consumed before dispatch and remains consumed if dispatch throws; the solid-execution registry is still balanced by `finally`.

## Exploration Synthesis

### ROM order

The S3K `Level` routine first creates `Obj_TitleCard` in dynamic slot 5 and loops at `loc_62CC`, calling `Process_Sprites` and `Render_Sprites` while waiting for the title-card object and decompression queues (`sonic3k.asm:7737-7748`). Level setup then creates the playable slots and zone state. At `sonic3k.asm:7849-7855`, the ROM calls:

```text
SpawnLevelMainSprites
Load_Sprites
Load_Rings
Draw_LRZ_Special_Rock_Sprites
Process_Sprites
Render_Sprites
Animate_Tiles
```

The ordinary loop is distinct: `Level_frame_counter` increments before `Process_Sprites`, with rendering and animated tiles afterward (`sonic3k.asm:7889-7906`).

CNZ balloons prove the setup pass is observable. `Obj_CNZBalloon` calls `Random_Number`, stores the low byte in `angle`, then uses and increments that angle during its first routine (`sonic3k.asm:66747-66799`). Replaying frame zero with an uninitialized balloon consumes the recorded frame-zero RNG seed one epoch too early. Historical commit `0f9b2c281` demonstrated that one object pass before RNG installation advances standalone CNZ from frame 185 to frame 1558, but selected it with `usesSidekickTitleCardSeedFrame(trace)`. That selection is prohibited and is not reused.

### Current engine divergence

`Sonic3kLevelInitProfile.levelLoadSteps` builds level objects, players, camera, events, sidekicks, and zone-player state before `RequestTitleCard`. During `GameLoop.updateTitleCardMode`, `Sonic3kTitleCardManager` inherits `TitleCardProvider.shouldRunLevelObjectsDuringLockedPhase() == true`, so every locked card frame calls `LevelManager.updateObjectPositions()` on already-loaded level objects.

`TestTitleCardObjectExecution` documents that this is divergent: ROM `loc_62CC` is still processing title-card SSTs, while the engine ages level objects. Nevertheless, `titleCardLegacyPath_s3kAiz1` currently pins a five-frame `ObjectManager` advance. Task 1 deliberately changes that expectation.

The existing S1 architecture supplies the useful precedent:

```java
@Override
public boolean shouldRunLevelObjectsDuringLockedPhase() {
    return false;
}

@Override
public int levelObjectPreludePassesAtRelease() {
    return 1;
}
```

S3K cannot copy the S1 provider count directly because headless replay suppresses title cards and because lifecycle ownership belongs to level assembly, not presentation metadata. Instead, `GameLoop` consumes a `LevelManager` token at release.

### Counter and placement hazards

`ObjectManager.update` increments both `frameCounter` and `vblaCounter` before dispatch, and `LevelManager.updateObjectPositions*` advances global oscillation after dispatch. Neither is suitable for the setup pass. S3K's existing normal update path already states the correct placement order for two-axis cursor placement: `placement.update(cameraX)`, `syncActiveSpawnsLoad(false)`, then execution. The dedicated primitive extracts that order without normal-frame counters or touches.

Locked title-card frames must still advance the VBlank clock even when they stop executing level objects. This requires a VBlank-only operation, rather than using `ObjectManager.update` as an accidental clock.

## Architecture Decision

### REVISED exact title-card dependency

The initial setup lifecycle depends on correcting S3K's locked title-card object population. These changes land in Task 1 before token arming or setup execution:

```java
// Sonic3kTitleCardManager
@Override
public boolean shouldRunLevelObjectsDuringLockedPhase() {
    return false;
}

@Override
public boolean shouldAdvanceVblankClockDuringLockedPhase() {
    return true;
}
```

Add the following default to `TitleCardProvider`:

```java
default boolean shouldAdvanceVblankClockDuringLockedPhase() {
    return false;
}
```

`GameLoop.updateTitleCardMode` calls `levelManager.advanceTitleCardVblankOnly()` when that capability is true. The method advances only `ObjectManager.vblaCounter`; it does not execute level objects or advance level-frame state.

The production lifecycle and load authority are:

```java
public enum InitialObjectSetupLifecycle {
    NONE,
    S3K_LOAD_THEN_EXECUTE_ONCE
}
```

```java
public enum LevelAssemblyKind {
    DECODE_ONLY,
    FRESH_LEVEL_ASSEMBLY,
    STATE_RESTORATION
}
```

```java
// LevelLoadContext; default is DECODE_ONLY.
private LevelAssemblyKind assemblyKind = LevelAssemblyKind.DECODE_ONLY;
private InitialObjectSetupLifecycle requestedInitialObjectSetupLifecycle =
        InitialObjectSetupLifecycle.NONE;

public boolean permitsInitialObjectSetupRequest() {
    return loadMode == LevelLoadMode.FULL
            && includePostLoadAssembly
            && assemblyKind == LevelAssemblyKind.FRESH_LEVEL_ASSEMBLY;
}
```

`loadCurrentLevel(...)` sets `FRESH_LEVEL_ASSEMBLY` only when it is actually
building a playable runtime. Preview capture remains `PREVIEW_CAPTURE` and
`DECODE_ONLY`. A warm `SharedLevel` reuse performs no `loadLevel` call and
therefore cannot request a token. Snapshot and complete-run state restoration
set `STATE_RESTORATION` (or explicitly discard a token at the restoration
boundary) unless that path performed a genuine fresh level assembly whose
native setup has not already been represented.

```java
// LevelInitProfile
default InitialObjectSetupLifecycle initialObjectSetupLifecycle() {
    return InitialObjectSetupLifecycle.NONE;
}
```

```java
// Sonic3kLevelInitProfile
@Override
public InitialObjectSetupLifecycle initialObjectSetupLifecycle() {
    return InitialObjectSetupLifecycle.S3K_LOAD_THEN_EXECUTE_ONCE;
}
```

`LevelManager` owns:

```java
private InitialObjectSetupLifecycle pendingInitialObjectSetupLifecycle =
        InitialObjectSetupLifecycle.NONE;

public boolean consumePendingInitialObjectSetupPass() {
    InitialObjectSetupLifecycle pending = pendingInitialObjectSetupLifecycle;
    pendingInitialObjectSetupLifecycle = InitialObjectSetupLifecycle.NONE;
    if (pending != InitialObjectSetupLifecycle.S3K_LOAD_THEN_EXECUTE_ONCE) {
        return false;
    }
    objectManager.runInitialS3kLoadThenExecutePass(
            camera.getX(), mainPlayable(), spriteManager.getSidekicks());
    return true;
}
```

No value-taking `LevelManager` arming setter exists. The named S3K profile step
after `InitZonePlayerState` calls
`ctx.requestInitialObjectSetupFromProfile(initialObjectSetupLifecycle())`.
That context method accepts the typed profile value only when
`permitsInitialObjectSetupRequest()` is true. `LevelManager.loadLevel` clears
its old token before executing steps, then publishes the context request only
after all steps, `writeCurrentLevel`, and the level-boundary reset succeed.
The catch path clears the token again before preserving the existing fatal
load semantics. Consumption clears before dispatch, so an object exception
cannot replay a partially executed setup pass.

The token may survive from load completion through locked title-card frames, so it is captured in `LevelSnapshot` through `LevelRewindSnapshotAdapter`. Three consumers race safely:

1. `GameLoop.updateTitleCardMode` consumes at title-card release before `exitTitleCard()` and before the first level-frame fallthrough.
2. `LevelFrameStep.execute` consumes at its entry for ordinary no-title production.
3. `TraceReplaySessionBootstrap.applyBootstrap` consumes after VInt/ring-floor phase initialization and before `applyInitialRngSeedForReplay`.

Only the first consumer executes the pass. Replay can call the consumer but cannot create the production token.

## Feature Design

### File and responsibility map

- Create `src/main/java/com/openggf/game/InitialObjectSetupLifecycle.java`: closed typed lifecycle values.
- Create `src/main/java/com/openggf/game/LevelAssemblyKind.java`: production load intent, distinct from presentation mode.
- Modify `src/main/java/com/openggf/game/LevelLoadContext.java`: typed assembly kind and profile-request field.
- Modify `src/main/java/com/openggf/game/LevelInitProfile.java`: default typed capability.
- Modify `src/main/java/com/openggf/game/sonic3k/Sonic3kLevelInitProfile.java`: S3K capability and named arming step after `InitZonePlayerState`.
- Modify `src/main/java/com/openggf/game/TitleCardProvider.java`: locked-phase VBlank-only capability.
- Modify `src/main/java/com/openggf/game/sonic3k/titlecard/Sonic3kTitleCardManager.java`: no level objects during locked pre-level card; VBlank-only clock enabled.
- Modify `src/main/java/com/openggf/GameLoop.java`: VBlank-only locked-card call and release token consumption.
- Modify `src/main/java/com/openggf/PostTitleCardDestination.java`: own the semantic decision whether a releasing title-card destination may consume fresh-level setup authority.
- Modify `src/main/java/com/openggf/LevelFrameStep.java`: no-title first-frame token consumption.
- Modify `src/main/java/com/openggf/level/LevelManager.java`: private token, private arming step factory, public consume-only seam, VBlank-only operation, reset behavior.
- Create `src/main/java/com/openggf/level/InitialObjectSetupCoordinator.java`: package-private owner of pending lifecycle state and atomic publish/take/discard/reset transitions; `LevelManager` remains the load-authority boundary and public façade.
- Modify `src/main/java/com/openggf/level/objects/ObjectManager.java`: dedicated S3K load-then-execute setup primitive.
- Modify `src/main/java/com/openggf/game/rewind/snapshot/LevelSnapshot.java`: pending lifecycle snapshot field using the enum value, not an ordinal.
- Modify `src/main/java/com/openggf/level/rewind/LevelRewindSnapshotAdapter.java`: capture and restore the pending lifecycle through `LevelManager` package-owned snapshot accessors.
- Modify `src/main/java/com/openggf/trace/replay/TraceReplaySessionBootstrap.java`: consume production token after counter setup and before RNG seed; do not add standalone S3K metadata selection.
- Modify `src/main/java/com/openggf/trace/TraceReplayBootstrap.java`: remove any remaining standalone S3K replay-owned setup selector; leave S1/S2 and existing complete-run contracts unchanged.
- Modify `src/test/java/com/openggf/tests/TestTitleCardObjectExecution.java`: corrected locked S3K policy and clock assertions.
- Create `src/test/java/com/openggf/tests/TestS3kInitialObjectSetupLifecycle.java`: ROM-backed production lifecycle integration tests.
- Create `src/test/java/com/openggf/level/objects/TestObjectManagerInitialS3kSetupPass.java`: isolated placement/dispatch/counter/touch tests.
- Modify `src/test/java/com/openggf/game/TestPostLoadAssemblyBehavior.java`: profile order and S1/S2 default assertions.
- Modify `src/test/java/com/openggf/level/rewind/TestLevelRewindSnapshotAdapter.java`: pending-token round trip and consumed-token state.
- Modify `src/test/java/com/openggf/tests/trace/s3k/TestS3kCnzTraceReplay.java`: standalone CNZ object/RNG closure without replay identity.
- Modify `src/test/java/com/openggf/tests/trace/TestTraceReplayStartPositionPolicy.java`: metadata-variant non-selection tests.
- Modify `CHANGELOG.md` and `docs/status/trace-frontier-log.md`: implementation and measured frontier evidence.

### Setup primitive contract

`ObjectManager.runInitialS3kLoadThenExecutePass` has this exact signature:

```java
public void runInitialS3kLoadThenExecutePass(
        int cameraX,
        PlayableEntity player,
        List<? extends PlayableEntity> sidekicks)
```

Its implementation extracts a private helper from the existing two-axis branch:

```java
private void runS3kLoadThenExecute(
        int cameraX,
        PlayableEntity player,
        List<? extends PlayableEntity> sidekicks,
        boolean incrementDispatchCounter)
```

For the initial setup pass, `incrementDispatchCounter` is `true` because `ObjectManager.frameCounter` counts object-dispatch passes, including the native setup `Process_Sprites`. `vblaCounter` remains unchanged because no VBlank is synthesized. The helper performs the same audited dispatch envelope as `update`:

```java
frameCounter++;
updateCameraBounds();
solidContacts.captureExecStartPlayerCentreY(player, activeSidekicks);
SolidExecutionRegistry registry = objectServices.solidExecutionRegistry();
registry.beginFrame(frameCounter, collectActivePlayers(player, activeSidekicks));
try {
    placement.update(cameraX);
    syncActiveSpawnsLoad(false);
    cleanupDestroyedDynamicObjects();
    runExecLoop(cameraX, player, activeSidekicks, false, false);
    flushPostExecDynamicSpawns();
} finally {
    registry.finishFrame();
}
captureCollisionResponseListForNextFrame();
```

It does not call `touchResponses`, `solidContacts.beginInlineFrame`,
`advanceGlobalOscillation`, `RingManager`, `Camera.updatePosition`,
`LevelManager.update`, or playable sprite updates. If dispatch throws,
`finishFrame()` still runs and the lifecycle remains consumed.

## Implementation Plan

### Task 1: Correct S3K Locked Title-Card Object and VBlank Ownership

**Files:**
- Modify: `src/main/java/com/openggf/game/TitleCardProvider.java`
- Modify: `src/main/java/com/openggf/game/sonic3k/titlecard/Sonic3kTitleCardManager.java`
- Modify: `src/main/java/com/openggf/GameLoop.java`
- Modify: `src/main/java/com/openggf/level/LevelManager.java`
- Modify: `src/test/java/com/openggf/tests/TestTitleCardObjectExecution.java`

**Interfaces:**
- Produces: `TitleCardProvider.shouldAdvanceVblankClockDuringLockedPhase(): boolean`
- Produces: `LevelManager.advanceTitleCardVblankOnly(): void`
- Preserves: S1 `shouldRunLevelObjectsDuringLockedPhase() == false`
- Preserves: S2 full `LevelFrameStep` title-card path

- [ ] **Step 1: Write failing S3K locked-card tests**

Change `titleCardLegacyPath_s3kAiz1` to expect zero `ObjectManager.frameCounter` delta and a five-count `vblaCounter` delta. Snapshot the first active level object's routine/debug state, camera X/Y and bounds, oscillator rewind state, and `GameServices.rng().getSeed()` before stepping, then assert all except VBlank remain unchanged after five locked frames. Release the card for one ordinary level frame and assert the oscillator advances exactly once from the locked baseline; this catches suppression leaking into the first unlocked frame.

Add provider-policy assertions:

```java
assertFalse(provider.shouldRunLevelObjectsDuringLockedPhase());
assertTrue(provider.shouldAdvanceVblankClockDuringLockedPhase());
```

- [ ] **Step 2: Run the focused tests and confirm the old approximation fails**

Run:

```bash
mvn -Dmse=off \
  -Dtest=com.openggf.tests.TestTitleCardObjectExecution \
  -Ds3k.rom.path='Sonic and Knuckles & Sonic 3 (W) [!].gen' \
  -Ds1.rom.path='Sonic The Hedgehog (W) (REV01) [!].gen' \
  -Ds2.rom.path='Sonic The Hedgehog 2 (W) (REV01) [!].gen' test
```

Expected: S3K fails because object frame delta is 5 instead of 0 and no VBlank-only provider capability exists; S1/S2 assertions retain their prior results.

- [ ] **Step 3: Add the provider and VBlank-only production APIs**

Add the default method to `TitleCardProvider`, override both S3K methods, and add:

```java
public void advanceTitleCardVblankOnly() {
    if (objectManager != null) {
        objectManager.advanceVblaCounter();
    }
}
```

In the locked non-player branch of `GameLoop.updateTitleCardMode`, replace implicit clocking through object execution with:

```java
if (tcpCard.shouldRunLevelObjectsDuringLockedPhase()) {
    levelManager.suppressGlobalOscillationForTitleCardPass();
    levelManager.updateObjectPositions();
    camera.updatePosition(true); // preserve the existing S1/S2 object path
} else if (tcpCard.shouldAdvanceVblankClockDuringLockedPhase()) {
    levelManager.advanceTitleCardVblankOnly();
    // S3K: deliberately no camera.updatePosition(true)
} else {
    // Preserve S1's existing locked-card forced camera step.
    camera.updatePosition(true);
}
```

Move the existing unconditional
`suppressGlobalOscillationForTitleCardPass()` into the two actual object-update
branches only: immediately before the S2 `LevelFrameStep.execute` call and
inside the `shouldRunLevelObjectsDuringLockedPhase()` branch above. Do not set
suppression on the VBlank-only path. Preserve the early return while the card
is locked. Move the current unconditional `camera.updatePosition(true)` at the
end of the non-player branch into the explicit branches shown above: it must be
absent only for the S3K VBlank-only capability, while S1 retains its required
forced camera update. Add a test spy/guard that `camera.updatePosition(true)` and
`camera.updateBoundaryEasing()` are never called for locked S3K. The first
unlocked fallthrough must see no stale suppression and must perform its normal
camera and oscillator steps.

- [ ] **Step 4: Run title-card and cross-game guards**

Run the Step 2 command and:

```bash
mvn -Dmse=off \
  -Dtest='com.openggf.tests.TestS3kAiz1SkipHeadless,com.openggf.tests.TestSonic3kLevelLoading,com.openggf.tests.TestArchitecturalSourceGuard' \
  -Ds3k.rom.path='Sonic and Knuckles & Sonic 3 (W) [!].gen' test
```

Expected: all selected tests pass; locked S3K level objects, RNG, camera, and
oscillator remain unchanged while VBlank advances exactly five; the first
unlocked frame advances camera/oscillator normally.

- [ ] **Step 5: Commit Task 1**

```bash
git add src/main/java/com/openggf/game/TitleCardProvider.java \
  src/main/java/com/openggf/game/sonic3k/titlecard/Sonic3kTitleCardManager.java \
  src/main/java/com/openggf/GameLoop.java \
  src/main/java/com/openggf/level/LevelManager.java \
  src/test/java/com/openggf/tests/TestTitleCardObjectExecution.java
git commit -m "fix(s3k): separate title-card and level object dispatch"
```

### Task 2: Add the Dedicated S3K Initial Object Setup Primitive

**Depends on:** Task 1 commit. Do not dispatch in parallel.

**Files:**
- Modify: `src/main/java/com/openggf/level/objects/ObjectManager.java`
- Create: `src/test/java/com/openggf/level/objects/TestObjectManagerInitialS3kSetupPass.java`

**Interfaces:**
- Produces: `ObjectManager.runInitialS3kLoadThenExecutePass(int, PlayableEntity, List<? extends PlayableEntity>): void`
- Consumes: existing two-axis `ObjectPlacementController`, `syncActiveSpawnsLoad(false)`, `runExecLoop`, `flushPostExecDynamicSpawns`

- [ ] **Step 1: Write failing setup-pass isolation tests**

Build a two-axis S3K `ObjectManager` fixture with one visible probe spawn and one out-of-window spawn. Use factory counters keyed by the two `ObjectSpawn` identities so the hidden assertion does not require an instance that should never be created. The visible probe increments an update count, creates one dynamic child, and exposes whether touch response ran. Assert:

```java
assertEquals(1, visibleProbe.updateCount());
assertEquals(1, visibleFactoryCreations.get());
assertEquals(0, hiddenFactoryCreations.get());
assertEquals(objectFrameBefore + 1, manager.getFrameCounter());
assertEquals(vblankBefore, manager.getVblaCounter());
assertEquals(0, touchProbe.touchCount());
assertTrue(manager.getActiveObjects().contains(dynamicChild));
```

Capture player and sidekick position, velocity, status, animation, mapping, CPU routine, and history cursor before the pass and assert byte-for-byte equality afterward.

Use a spy `SolidExecutionRegistry` and `SolidContactManager` to assert one
`captureExecStartPlayerCentreY`, one `beginFrame`, and one `finishFrame`, in
this exact order:

```text
updateCameraBounds
captureExecStartPlayerCentreY
SolidExecutionRegistry.beginFrame
placement.update
syncActiveSpawnsLoad
runExecLoop
flushPostExecDynamicSpawns
SolidExecutionRegistry.finishFrame
captureCollisionResponseListForNextFrame
```

Use an ordered spy/event ledger, not independent call counts, so moving
placement before `beginFrame` fails the test. Add a throwing object variant and
assert `finishFrame` still runs
exactly once while the exception propagates, no touch response runs, and no
second setup dispatch is implied.

- [ ] **Step 2: Run the new class and confirm the API is absent**

Run:

```bash
mvn -Dmse=off \
  -Dtest=com.openggf.level.objects.TestObjectManagerInitialS3kSetupPass test
```

Expected: test compilation fails because `runInitialS3kLoadThenExecutePass` is undefined.

- [ ] **Step 3: Extract the minimal load-then-execute helper**

Implement the signatures in Feature Design. Reuse the current two-axis placement branch instead of duplicating slot logic. Extract the whole audited envelope—`captureExecStartPlayerCentreY`, balanced `SolidExecutionRegistry.beginFrame/finishFrame`, placement/load, exec, post-exec child flush, and collision-list capture—not merely `runExecLoop`. Pass `enableTouchResponses=false`, `inlineSolidResolution=false`, and `solidPostMovement=false`; do not call normal `update`.

- [ ] **Step 4: Verify setup isolation and ordinary object updates**

Run:

```bash
mvn -Dmse=off \
  -Dtest='com.openggf.level.objects.TestObjectManagerInitialS3kSetupPass,com.openggf.level.objects.TestObjectPlacementManager,com.openggf.level.objects.TestObjectManagerVerticalPlacement,com.openggf.tests.TestTraceReplayInvariantGuard' test
```

Expected: every selected test passes; existing ordinary S3K update ordering is unchanged.

- [ ] **Step 5: Commit Task 2**

```bash
git add src/main/java/com/openggf/level/objects/ObjectManager.java \
  src/test/java/com/openggf/level/objects/TestObjectManagerInitialS3kSetupPass.java
git commit -m "feat(s3k): add initial level-object setup pass"
```

### Task 3: Add Typed Load-Arming and Reset Semantics

**Depends on:** Task 2 commit. Do not dispatch in parallel.

**Files:**
- Create: `src/main/java/com/openggf/game/InitialObjectSetupLifecycle.java`
- Create: `src/main/java/com/openggf/game/LevelAssemblyKind.java`
- Modify: `src/main/java/com/openggf/game/LevelLoadContext.java`
- Modify: `src/main/java/com/openggf/game/LevelInitProfile.java`
- Modify: `src/main/java/com/openggf/game/sonic3k/Sonic3kLevelInitProfile.java`
- Modify: `src/main/java/com/openggf/level/LevelManager.java`
- Modify: `src/test/java/com/openggf/game/TestPostLoadAssemblyBehavior.java`
- Create: `src/test/java/com/openggf/tests/TestS3kInitialObjectSetupLifecycle.java`

**Interfaces:**
- Produces: `InitialObjectSetupLifecycle`, `LevelAssemblyKind`
- Produces: `LevelLoadContext.requestInitialObjectSetupFromProfile(InitialObjectSetupLifecycle): void`
- Produces: package-owned publication from successful `LevelLoadContext`
- Produces: `LevelManager.hasPendingInitialObjectSetupPass(): boolean` as a read-only diagnostic/test query; it cannot arm or consume
- Consumes: Task 2 setup primitive only in later Task 4

- [ ] **Step 1: Write the load-authority matrix tests**

Assert S1/S2 profile values are `NONE`, S3K is
`S3K_LOAD_THEN_EXECUTE_ONCE`, and S3K orders the context request between
`InitZonePlayerState` and `RequestTitleCard`. Parameterize:

```text
FULL + post-load + FRESH_LEVEL_ASSEMBLY       -> request/publish
PREVIEW_CAPTURE + post-load + FRESH           -> NONE
FULL + no post-load + FRESH                    -> NONE
FULL + post-load + DECODE_ONLY                 -> NONE
FULL + post-load + STATE_RESTORATION           -> NONE
warm SharedLevel reuse (no loadLevel call)      -> NONE
```

Add a seamless-transition/fresh-reload pair: the in-place seamless path does
not arm, while the next genuine `loadCurrentLevel` does. Add a synthetic
profile step that throws after the request step; assert the old token was
cleared on entry, no new token is published, and the existing fatal exception
contract is preserved. Add teardown and failed-load retry assertions.

- [ ] **Step 2: Run the failing authority tests**

```bash
mvn -Dmse=off \
  -Dtest='com.openggf.game.TestPostLoadAssemblyBehavior,com.openggf.tests.TestS3kInitialObjectSetupLifecycle' \
  -Ds3k.rom.path='Sonic and Knuckles & Sonic 3 (W) [!].gen' test
```

Expected: compilation fails on the new enums/context methods.

- [ ] **Step 3: Implement request-then-publish arming**

Add the exact types and predicates from Architecture Decision. The profile
step writes only to its captured `ctx`. At `loadLevel` entry set the live token
to `NONE`; after every step, current-level publication, and rewind-boundary
reset succeeds, copy `ctx.requestedInitialObjectSetupLifecycle()` to the live
field. In every catch path set `NONE` before rethrowing with the existing
checked/unchecked wrapping. Do not swallow startup errors.

- [ ] **Step 4: Run and commit Task 3**

Run Step 2 plus `TestS3kAiz1SkipHeadless` and
`TestArchitecturalSourceGuard`; expect all green.

```bash
git add src/main/java/com/openggf/game/InitialObjectSetupLifecycle.java \
  src/main/java/com/openggf/game/LevelAssemblyKind.java \
  src/main/java/com/openggf/game/LevelLoadContext.java \
  src/main/java/com/openggf/game/LevelInitProfile.java \
  src/main/java/com/openggf/game/sonic3k/Sonic3kLevelInitProfile.java \
  src/main/java/com/openggf/level/LevelManager.java \
  src/test/java/com/openggf/game/TestPostLoadAssemblyBehavior.java \
  src/test/java/com/openggf/tests/TestS3kInitialObjectSetupLifecycle.java
git commit -m "feat(s3k): arm initial setup from successful loads"
```

### Task 4: Add Consumers and Prove Live/Headless Convergence

**Depends on:** Task 3 commit. Do not dispatch in parallel.

**Files:**
- Create: `src/main/java/com/openggf/level/InitialObjectSetupCoordinator.java`
- Modify: `src/main/java/com/openggf/PostTitleCardDestination.java`
- Modify: `src/main/java/com/openggf/level/LevelManager.java`
- Modify: `src/main/java/com/openggf/LevelFrameStep.java`
- Modify: `src/main/java/com/openggf/GameLoop.java`
- Modify: `src/test/java/com/openggf/tests/TestS3kInitialObjectSetupLifecycle.java`

**Interfaces:**
- Produces: `LevelManager.consumePendingInitialObjectSetupPass(): boolean`
- Produces: `LevelManager.discardPendingInitialObjectSetupForStateRestoration(): void`
- Consumes: Task 2 `ObjectManager.runInitialS3kLoadThenExecutePass`
- Consumes: Task 3 private pending token

Review-driven architecture correction: the package-private coordinator owns
the token and its atomic state transitions so the release-critical
`LevelManager` façade does not grow a second lifecycle responsibility or rely
on compressed multi-statement lines to satisfy its unchanged source budget.
`LevelManager.loadLevel` remains the sole publication authority, delegates
reset/publish only at its existing success/failure boundaries, and preserves
the Task 3 query plus Task 4 consume/discard APIs. Task 5 may later capture and
restore the coordinator-owned value through package-owned `LevelManager`
accessors without changing rewind schema in this task.

`PostTitleCardDestination` owns release routing semantics: `LEVEL` delegates
the one-shot consume call and `BONUS_STAGE` leaves authority untouched.
`GameLoop` therefore invokes one destination operation before `exitTitleCard`
without adding a routing predicate to the release-critical façade. Genuine
special-stage return remains `LEVEL` and may consume a token armed by its
fresh reload.

- [ ] **Step 1: Write one-shot, pause, exception, and convergence tests**

Cover release consumption, no-title first-frame consumption, a pause before
the first gameplay frame (token remains pending and no setup runs), and a
second consume returning false without state change. A throwing setup object
must leave the token consumed while Task 2's registry `finishFrame` remains
balanced.

Add special-stage ownership cases: entering a special stage with no level
assembly cannot arm or consume setup; a paused special-stage tick cannot
consume; returning through a genuine fresh level reload may arm exactly one
new token, while restoration of an already represented return snapshot may
not.

Define concrete test-only records rather than nonexistent production snapshot
APIs:

```java
record ObjectRow(int slot, String className, int x, int y) {}
record PlayerState(int centreX, int centreY, int xSpeed, int ySpeed,
                   int groundSpeed, int status, int animation, int mapping) {}
record RuntimeState(List<ObjectRow> objects, int activeSlotCount,
                    int objectFrame, int vbla, int levelFrame,
                    int cameraX, int cameraY, long rngSeed,
                    PlayerState player, List<PlayerState> sidekicks) {}
```

Build `RuntimeState` with a test helper over
`ObjectManager.getActiveObjects()`, `AbstractObjectInstance.getSlotIndex()`,
`ObjectInstance.getX()/getY()`, the existing public counters, camera getters,
and explicit playable getters. Sort rows by slot then class name. Do not invent
generic routine/status getters that `ObjectInstance` does not expose.
For the hidden-spawn check retain Task 2's factory creation counters.

Create fresh visible-title-card and no-title CNZ runtimes from the same inputs.
Compare `RuntimeState` after setup, including one CNZ balloon initialization.
Also compare the oscillator state immediately before and after the first
unlocked ordinary frame.

- [ ] **Step 2: Implement consumers**

Clear the token before calling Task 2's primitive. Consume at title-card
release before `exitTitleCard()`, and at `LevelFrameStep.execute` only after
the pause/early-return gates but before ordinary object dispatch. A paused
frame must not consume. Do not consume in preview, special-stage, or results
modes.

The discard method is a semantic production seam for callers that are about
to restore an already represented runtime state. It accepts no trace,
metadata, route, or lifecycle value. It must not be called for a genuine fresh
load whose native setup is still pending.

- [ ] **Step 3: Verify and commit Task 4**

```bash
mvn -Dmse=off \
  -Dtest='com.openggf.tests.TestS3kInitialObjectSetupLifecycle,com.openggf.tests.TestTitleCardObjectExecution,com.openggf.tests.TestS3kAiz1SkipHeadless' \
  -Ds3k.rom.path='Sonic and Knuckles & Sonic 3 (W) [!].gen' test
```

```bash
git add src/main/java/com/openggf/level/LevelManager.java \
  src/main/java/com/openggf/LevelFrameStep.java \
  src/main/java/com/openggf/GameLoop.java \
  src/test/java/com/openggf/tests/TestS3kInitialObjectSetupLifecycle.java
git commit -m "feat(s3k): consume initial setup at gameplay seams"
```

### Task 5: Add Rewind Schema and Pending/Consumed Restoration

**Depends on:** Task 4 commit. Do not dispatch in parallel.

**Files:**
- Modify: `src/main/java/com/openggf/game/rewind/snapshot/LevelSnapshot.java`
- Modify: `src/main/java/com/openggf/level/InitialObjectSetupCoordinator.java`
- Modify: `src/main/java/com/openggf/level/rewind/LevelRewindSnapshotAdapter.java`
- Modify: `src/main/java/com/openggf/level/LevelManager.java`
- Modify: `src/test/java/com/openggf/level/TestLevelManagerRewindSnapshot.java`
- Modify: `src/test/java/com/openggf/level/rewind/TestLevelRewindSnapshotAdapter.java`
- Modify: `src/test/java/com/openggf/game/rewind/TestRewindBenchmarkSizeEstimator.java`

**Interfaces:**
- Produces package-owned snapshot accessors:

```java
InitialObjectSetupLifecycle capturePendingInitialObjectSetupLifecycleForRewind()
void restorePendingInitialObjectSetupLifecycleForRewind(InitialObjectSetupLifecycle lifecycle)
```

- [ ] **Step 1: Write pending and consumed round-trip tests**

Capture/restore once while pending and once after consumption. Assert a restored
pending token executes exactly once, while a restored consumed snapshot cannot
execute. Add pause-before-capture and exception-after-consume variants.

- [ ] **Step 2: Extend the record and every constructor call**

Append `InitialObjectSetupLifecycle pendingInitialObjectSetupLifecycle` to the
canonical `LevelSnapshot` record. Update both convenience constructors and all
current canonical call sites, not just the adapter:

```text
src/main/java/com/openggf/level/rewind/LevelRewindSnapshotAdapter.java
src/test/java/com/openggf/level/TestLevelManagerRewindSnapshot.java
src/test/java/com/openggf/level/rewind/TestLevelRewindSnapshotAdapter.java
src/test/java/com/openggf/game/rewind/TestRewindBenchmarkSizeEstimator.java
```

Use `NONE` for legacy/convenience construction. Capture and restore the enum,
never its ordinal. Run `rg -n 'new LevelSnapshot\\(' src/main src/test` and
account for every result.

- [ ] **Step 3: Verify and commit Task 5**

```bash
mvn -Dmse=off \
  -Dtest='com.openggf.level.TestLevelManagerRewindSnapshot,com.openggf.level.rewind.TestLevelRewindSnapshotAdapter,com.openggf.game.rewind.TestRewindBenchmarkSizeEstimator,com.openggf.game.rewind.coverage.TestRewindCoverageGuard' test
```

```bash
git add src/main/java/com/openggf/game/rewind/snapshot/LevelSnapshot.java \
  src/main/java/com/openggf/level/rewind/LevelRewindSnapshotAdapter.java \
  src/main/java/com/openggf/level/LevelManager.java \
  src/test/java/com/openggf/level/TestLevelManagerRewindSnapshot.java \
  src/test/java/com/openggf/level/rewind/TestLevelRewindSnapshotAdapter.java \
  src/test/java/com/openggf/game/rewind/TestRewindBenchmarkSizeEstimator.java
git commit -m "feat(rewind): preserve initial setup lifecycle"
```

### Task 6: Consume Production Setup in Replay and Prove Standalone CNZ

**Depends on:** Task 5 commit. Do not dispatch in parallel.

**Files:**
- Modify: `src/main/java/com/openggf/trace/replay/TraceReplaySessionBootstrap.java`
- Modify: `src/main/java/com/openggf/trace/TraceReplayBootstrap.java`
- Create: `src/main/java/com/openggf/level/LevelRewindBoundaryCoordinator.java`
- Modify: `src/main/java/com/openggf/level/LevelManager.java`
- Modify: `src/test/java/com/openggf/level/TestLevelManagerRewindBoundary.java`
- Modify: `src/test/java/com/openggf/trace/TestPreludeFramesKnobsZero.java`
- Modify: `src/test/java/com/openggf/tests/trace/TestTraceReplayStartPositionPolicy.java`
- Modify: `src/test/java/com/openggf/tests/trace/s3k/TestS3kCnzTraceReplay.java`
- Verify: `src/test/java/com/openggf/tests/trace/s3k/TestS3kHczCompleteRunTraceReplay.java`

**Interfaces:**
- Consumes: Task 4 `LevelManager.consumePendingInitialObjectSetupPass(): boolean`
- Preserves: `TraceReplaySessionBootstrap.applyInitialRngSeedForReplay(TraceMetadata): void`
- Removes: standalone S3K selection through `usesSidekickTitleCardSeedFrame`, trace profile, sidekick metadata, or `levelObjectTitleCardPreludeFramesForTraceReplay`
- Preserves: the complete-run represented-state reset/restore/dispatch envelope
  independently of the now-zero metadata prelude knob
- Produces: package-private `LevelRewindBoundaryCoordinator` owning the
  existing rewind reset/mark boundary while preserving the typed
  `LevelManager` rewind façade and its 2,500-line ratchet

- [ ] **Step 1: Write failing comparison-only and metadata-variance tests**

Split this into two independent assertions.

First, a pure metadata-token-invariance parameterized test loads the same
production S3K lifecycle and varies these metadata values independently:

```text
trace_profile
sidekick_characters
checkpoint names
recording filename
rng_seed
start position
```

Assert only token behavior here: the production token is pending before
bootstrap and consumed exactly once afterward for every variant. Changing
metadata without a production token never creates or executes a pass. Do not
assert whole object-state equality across variants such as RNG seed or start
position, because those inputs legitimately change later comparison state.

Second, a fixed-metadata state-equivalence test compares the concrete
`RuntimeState` helper from Task 4 between live/no-title/bootstrap consumers.
The metadata is byte-identical in this test, so object equality has one cause.

Add explicit state-restoration coverage: complete-run restoration calls the
semantic
`LevelManager.discardPendingInitialObjectSetupForStateRestoration()` boundary
that discards any pending setup token before
restoring the segment row; preview and warm/shared reuse likewise have none.
Conversely, a replay backed by a genuine fresh load seam may consume the token.

Keep complete-run represented-state restoration separate from the production
setup token and from `levelObjectTitleCardPreludeFramesForTraceReplay`. The
shared bootstrap path must discard fresh-load authority before restoration,
then preserve the established reset,
`restoreCompleteRunSegmentObjectsAfterPreludeReset`, and represented-state
object-dispatch envelope for direct and standard callers alike. The metadata
prelude knob remains zero. Add the full HCZ complete-run replay and its
Poindexter slot/bounce oracle to the mandatory gate.

Extract the pre-existing `resetRewindBufferAfterLevelBoundary` /
`markRewindLevelLoadBoundary` responsibility into a package-private
`LevelRewindBoundaryCoordinator`. Keep the typed rewind façade on
`LevelManager`, use normal formatting, do not raise the source budget, and
retain the existing boundary test at the collaborator owner.

Epoch correction from the Task 6 RED investigation: the recorder emits its
frame `-1` `object_state_snapshot` block immediately before the first recorded
row, while replay bootstrap stops one native `LevelLoop` dispatch earlier.
The setup pass and the recorder snapshot are therefore adjacent native epochs,
not the same epoch. Prove both explicitly rather than offsetting a fixture or
running another bootstrap dispatch:

```text
after zero-seed production setup:
  slots 4..7 balloon angles = 11,38,38,A8
  RNG seed = 14A7ABBB

after one ordinary LevelFrameStep:
  slots 4..7 balloon angles = 12,39,39,A9
  matching frame -1 object_state_snapshot
  level frame counter = 1
  VBlank counter = 1
  RNG seed remains 14A7ABBB
```

`Random_Number` resets a zero low word to `$2A6D365B` and advances the seed
(`docs/skdisasm/sonic3k.asm:2992-3011`). `Obj_CNZBalloon` consumes one random
value during initialization, then increments its angle in the routine tail
(`docs/skdisasm/sonic3k.asm:66750-66795`). Level setup runs
`Load_Sprites`/`Process_Sprites` once before `LevelLoop`, whose next ordinary
row runs them again after its VBlank and level-counter increment
(`docs/skdisasm/sonic3k.asm:7849-7855,7884-7906`). The recorder writes its
pre-trace object snapshots at the next-frame arm boundary before emitting the
first CSV row (`tools/bizhawk/s3k_trace_recorder.lua:1210-1222` and the native
contract in `tools/bizhawk-headless/docs/s3k-aux-events.md:64-88`).

Before that ordinary frame, still assert the production token is already
consumed and metadata RNG is exact. After it, prove the snapshot angles at the
matching epoch and that no extra RNG consumption occurred. Never subtract an
angle from fixture data, hydrate a snapshot, inject an RNG seed before setup,
or add an extra replay/bootstrap dispatch.

- [ ] **Step 2: Run focused tests and confirm CNZ remains at frame 185**

Run:

```bash
mvn -Dmse=off \
  -Dtest='com.openggf.tests.trace.TestTraceReplayStartPositionPolicy,com.openggf.tests.trace.s3k.TestS3kCnzTraceReplay#replayMatchesTrace' \
  -Dsurefire.argLine='-Xshare:off -Xmx6g' -Dsurefire.forkCount=1 \
  -Ds3k.rom.path='Sonic and Knuckles & Sonic 3 (W) [!].gen' test
```

Expected before implementation: metadata-token assertions fail because replay
does not consume a production token; CNZ remains expected-red at frame 185
`y_speed`.

- [ ] **Step 3: Consume the token at the correct bootstrap seam**

In `TraceReplaySessionBootstrap.applyBootstrap`, after ring-floor/VInt initialization and before `applyInitialRngSeedForReplay`, add:

```java
if (gameplayMode != null && gameplayMode.getLevelManager() != null) {
    gameplayMode.getLevelManager().consumePendingInitialObjectSetupPass();
}
applyInitialRngSeedForReplay(trace.metadata());
```

Delete standalone S3K replay-owned setup selection. Do not alter S1/S2 title-card preludes or the existing S3K complete-run restoration branch.
Centralize the complete-run represented-state envelope outside the
metadata-selected object-prelude count, discard the production token before
restoration, and keep that count at zero.

- [ ] **Step 4: Run focused CNZ and policy guards**

Run the Step 2 command plus:

```bash
mvn -Dmse=off \
  -Dtest='com.openggf.tests.TestArchitecturalSourceGuard,com.openggf.tests.TestTraceReplayInvariantGuard,com.openggf.tests.trace.TestTraceHydrateSwitchDefault,com.openggf.trace.TestPreludeFramesKnobsZero' test
```

Expected: policy/guard tests pass. Standalone CNZ's measured first error is
strictly later than frame 185. Record the actual next frame/field; do not encode
historical frame 1558 as an expected target because the current fixture and
surrounding runtime have changed.

Also run `TestLevelManagerRewindBoundary`, the full
`TestS3kHczCompleteRunTraceReplay` class including its Poindexter oracle, S1
GHZ1, S2 EHZ1, and the seven-zone S3K complete-run matrix. HCZ must retain its
established pre-change f1088 `tails_cpu_target_y` frontier and known-red
Poindexter oracle, and the CNZ frontier must remain at frame 291 or later.

- [ ] **Step 5: Commit Task 6**

```bash
git add src/main/java/com/openggf/trace/replay/TraceReplaySessionBootstrap.java \
  src/main/java/com/openggf/trace/TraceReplayBootstrap.java \
  src/test/java/com/openggf/tests/trace/TestTraceReplayStartPositionPolicy.java \
  src/test/java/com/openggf/tests/trace/s3k/TestS3kCnzTraceReplay.java
git commit -m "fix(trace): consume production S3K setup lifecycle"
```

### Task 7: Cross-Game, Complete-Run, Rewind, and Documentation Gate

**Depends on:** Task 6 commit. Do not dispatch in parallel.

**Files:**
- Modify: `CHANGELOG.md`
- Modify: `docs/status/trace-frontier-log.md`
- Modify only if a measured intentional discrepancy changes: `docs/status/known-discrepancies.md`

**Interfaces:**
- Consumes: Tasks 1-6 complete implementation, in commit order
- Produces: exact verification evidence and frontier documentation

- [ ] **Step 1: Run focused lifecycle and policy suite**

```bash
mvn -Dmse=off \
  -Dtest='com.openggf.tests.TestS3kInitialObjectSetupLifecycle,com.openggf.level.objects.TestObjectManagerInitialS3kSetupPass,com.openggf.tests.TestTitleCardObjectExecution,com.openggf.game.TestPostLoadAssemblyBehavior,com.openggf.level.rewind.TestLevelRewindSnapshotAdapter,com.openggf.tests.TestArchitecturalSourceGuard,com.openggf.tests.TestTraceReplayInvariantGuard,com.openggf.tests.trace.TestTraceHydrateSwitchDefault,com.openggf.trace.TestPreludeFramesKnobsZero' \
  -Ds3k.rom.path='Sonic and Knuckles & Sonic 3 (W) [!].gen' test
```

Expected: zero failures/errors.

- [ ] **Step 2: Run standalone and must-keep-green S3K routes**

```bash
mvn -Dmse=off -Dtrace.frontierOnly=true -Dtrace.context.radius=20 \
  -Dtest='com.openggf.tests.trace.s3k.TestS3kCnzTraceReplay,com.openggf.tests.trace.s3k.TestS3kAizTraceReplay,com.openggf.tests.trace.s3k.TestS3kMgzTraceReplay,com.openggf.tests.trace.s3k.TestS3kSpecialStageTraceReplay,com.openggf.tests.TestS3kAiz1SkipHeadless,com.openggf.tests.TestSonic3kLevelLoading,com.openggf.game.sonic3k.TestSonic3kBootstrapResolver,com.openggf.game.sonic3k.TestSonic3kDecodingUtils' \
  -Dsurefire.argLine='-Xshare:off -Xmx6g' -Dsurefire.forkCount=1 \
  -Ds3k.rom.path='Sonic and Knuckles & Sonic 3 (W) [!].gen' test
```

Expected: CNZ first error is later than frame 185; AIZ and MGZ retain their pre-change first frame/field; special-stage and must-keep-green classes pass.

Then run the production special-stage lifecycle seams:

```bash
mvn -Dmse=off \
  -Dtest='com.openggf.tests.TestS3kSpecialStageHeadlessBoot,com.openggf.tests.TestS3kAiz1SpecialStageReturn,com.openggf.tests.TestS3kSpecialStageReturnWaterRestore' \
  -Ds3k.rom.path='Sonic and Knuckles & Sonic 3 (W) [!].gen' test
```

Expected: no special-stage tick consumes setup; fresh return reload and restored
return state follow the Task 4 arming matrix.

- [ ] **Step 3: Prove complete-run non-goal did not regress**

Run each class in an isolated Maven invocation to avoid report basename and singleton contamination:

```bash
for test in \
  TestS3kAizCompleteRunTraceReplay \
  TestS3kCnzCompleteRunTraceReplay \
  TestS3kHczCompleteRunTraceReplay \
  TestS3kIczCompleteRunTraceReplay \
  TestS3kLbzCompleteRunTraceReplay \
  TestS3kMgzCompleteRunTraceReplay \
  TestS3kMhzCompleteRunTraceReplay
do
  mvn -Dmse=off -Dtrace.frontierOnly=true -Dtrace.context.radius=20 \
    -Dtest="com.openggf.tests.trace.s3k.${test}" \
    -Dsurefire.argLine='-Xshare:off -Xmx6g' -Dsurefire.forkCount=1 \
    -Ds3k.rom.path='Sonic and Knuckles & Sonic 3 (W) [!].gen' test
done
```

Expected: every class retains its established pre-change first frame/field. Record exact error count deltas; do not classify a downstream-count change as a frontier move.

- [ ] **Step 4: Run S1/S2 cross-game title-card and green traces**

```bash
mvn -Dmse=off \
  -Dtest='com.openggf.tests.TestTitleCardObjectExecution,com.openggf.tests.trace.s1.TestS1Ghz1TraceReplay,com.openggf.tests.trace.s2.TestS2Ehz1TraceReplay,com.openggf.tests.trace.s2.TestS2CnzLevelSelectTraceReplay' \
  -Ds1.rom.path='Sonic The Hedgehog (W) (REV01) [!].gen' \
  -Ds2.rom.path='Sonic The Hedgehog 2 (W) (REV01) [!].gen' test
```

Expected: S1/S2 selected traces and title-card policies pass unchanged.

- [ ] **Step 5: Update documentation with measured evidence**

Add a concise `CHANGELOG.md` entry describing the production title-card/object lifecycle correction and standalone CNZ frontier move. Add a `docs/status/trace-frontier-log.md` entry containing:

- worktree, branch, and verified commit;
- every exact command above;
- before/after CNZ error count, first frame, field, expected value, and actual value;
- AIZ/MGZ and complete-run held frontiers;
- S1/S2 and special-stage pass counts;
- ROM citations;
- explicit statement that trace data remained comparison-only and complete-run f0 was not addressed.

Update `docs/status/known-discrepancies.md` only if the implementation changes a documented intentional contract.

- [ ] **Step 6: Run documentation and repository guards**

```bash
mvn -Dmse=off \
  -Dtest='com.openggf.tests.TestArchitecturalSourceGuard,com.openggf.tests.TestBuildToolingGuard,com.openggf.trace.TestTraceFixtureCompressionGuard,com.openggf.game.rewind.coverage.TestRewindCoverageGuard,com.openggf.game.rewind.coverage.TestStaticStateRewindCoverageGuard' test
git diff --check
```

Expected: all guards pass and `git diff --check` prints no output.

- [ ] **Step 7: Commit Task 7**

```bash
git add CHANGELOG.md docs/status/trace-frontier-log.md
git commit -m "docs(trace): record S3K setup lifecycle frontier"
```

When Step 5 changes the intentional-discrepancy contract, stage it explicitly before
the commit:

```bash
git add docs/status/known-discrepancies.md
```

## Integration Report

Implementation workers must append one immutable evidence block beneath this heading
before integration. The block is an execution contract whose populated values satisfy
these field definitions:

| Evidence field | Required recorded value |
|---|---|
| Implementation branch | Exact branch name and worktree path |
| Task commits in execution order | Full commit hashes for Tasks 1-7 |
| Final verified commit | Full hash used for the final commands |
| Dirty-state audit | Exact `git status --short` output classification |
| CNZ standalone before | Error count, first frame, field, expected value, actual value |
| CNZ standalone after | Error count, first frame, field, expected value, actual value |
| Title-card locked-phase result | S3K object delta, VBlank delta, RNG delta |
| Setup isolation result | Object, VBlank, level-frame, player, sidekick, touch, and oscillator deltas |
| Rewind result | Pending-token and consumed-token round-trip outcomes |
| AIZ standalone canary | Error count and first frame/field |
| MGZ standalone canary | Error count and first frame/field |
| Complete-run canaries | One line per class with error count and first frame/field |
| S3K special-stage result | Tests run, failures, errors, warnings |
| S1 canaries | Tests run, failures, errors, first divergence when expected-red |
| S2 canaries | Tests run, failures, errors, first divergence when expected-red |
| Policy and architecture guards | Command, test count, failures, errors |
| Documentation files updated | Exact staged paths |
| Regressions introduced | Empty list when none; otherwise class, old frontier, new frontier |
| Reviewer decision | `approve`, `revise`, or `reject`, plus the evidence-based reason |

Every value must come from a fresh command in Task 7. Expected-red traces are reported by their own Surefire class line and `target/trace-reports` first divergence, not Maven's aggregate exit status. If a command cannot run, the report records the command, exit status, environmental cause, and the narrower command that supplied replacement evidence.

### Task 7 immutable evidence block

- **Implementation branch:** `bugfix/ai-trace-s3k-lifecycle`;
  `/home/farrell/code/projects/OpenGGF/.worktrees/trace-s3k-lifecycle`.
- **Task commits in execution order:**
  `058e1399ce7ec99a6432071b8dd4db8b48f172c6`,
  `7c7f7a5c72bd436f6a907fd020b18141c4e7d5e3`,
  `d1b85ad77c8e65689d36834a9932138828909a9f`,
  `c690349b392b4d5bcb2a38028129854415c96f5f`,
  `e473154bbc5d1aed9620c748447b2ff7fa5b6e77`,
  `4f57b63e75b546e0e4b254f84bb2956bb28c6c86`,
  `0851aa5cb3b13db126799642fd4995609246f42f`,
  `f426bd7f8317372ae39b55a27abdfe6d42356146`,
  `7cdb74fdcbad0a661d46a2342c89e065f91ae55b`,
  `4fa77c18ced92d11013344a907ef990f0876afb9`,
  `82c839667bea32afbdc7442d0321d9e1ad944406`,
  `5fcdf2b6f62f319668e807a7b6d316155e77e0c5`,
  `8a2ddb8dfe35c7d935184f47c899563822239250`,
  `eaa13384541cc978586193dc18da4e512e7c1261`,
  `61cd1a3bcdd116dfc807ef724f272dbb545d3fd9`,
  `cad908bc9b91eb16480131b1ab6c0b1aac158a39`,
  `ac704fa554c37e5df2ebfa16bf16794db7c71509`,
  `d5f0c3c01cfcb42957e417d316ae47a778aa516a`,
  `763ef493321c4412c1efecd71820c7bf462265b5`.
- **Final verified commit:** `d5f0c3c01cfcb42957e417d316ae47a778aa516a`.
- **Dirty-state audit:** tracked state was clean before Task 7; only five
  existing untracked disassembly links were present and none was staged.
- **CNZ standalone before:** 9,140 errors; f185 `y_speed`, `0x0370` /
  `-0x0700`.
- **CNZ standalone after:** full report 6,762 errors at f291 `y_speed`,
  `0x01F8` / `-0x0651`; fresh frontier-only report 22 groups at f291
  `x_speed`, `0x0600` / `-0x02FB`.
- **Title-card locked phase:** object `0`, VBlank `+5`, RNG `0`.
- **Setup isolation:** object dispatch `+1`; VBlank, level-frame, player,
  sidekick, touch, and oscillator deltas `0`.
- **Rewind:** pending authority round-trips and consumes once; consumed
  authority round-trips as consumed; pause and exception atomicity pass.
- **AIZ standalone:** 2 groups; f719 `x`, `0x0040` / `0x0050`.
- **MGZ standalone:** 18 groups; f5164 `air`, `0` / `1`.
- **Complete runs:** AIZ 2 groups at f9376 `rings`; CNZ 28 at f0 `y`; HCZ 1
  replay group at f1088 `tails_cpu_target_y` plus its known-red Poindexter
  oracle; ICZ 7 at f0 `x`; LBZ 1 at f0 `player_mapping_frame`; MGZ 17 at
  f5550 `air`; MHZ 28 at f0 `y`.
- **S3K special stage:** 8 tests, 0 failures/errors/warnings.
- **S1 canaries:** GHZ1 and title-card coverage pass, no divergence.
- **S2 canaries:** EHZ1, CNZ level-select, and title-card coverage pass, no
  divergence.
- **Policy and architecture guards:** focused command 134 tests, 0
  failures/errors; final guard command is recorded in the Task 7 report.
- **Documentation:** `CHANGELOG.md`, `docs/status/trace-frontier-log.md`, and
  this plan. The intentional-discrepancy contract did not change.
- **Regressions introduced:** none; every measured canary frontier held.
- **Reviewer decision:** `approve` — production-owned, comparison-only setup
  advances standalone CNZ without a measured frontier regression.

## End-to-End Review Checklist

- [ ] The implementation used `superpowers:subagent-driven-development`, one implementation worker per task, followed by spec-compliance and code-quality review.
- [ ] `InitialObjectSetupLifecycle` is typed, defaults to `NONE`, and only S3K opts in.
- [ ] No trace/profile/zone/route/frame/sidekick/checkpoint/filename/outcome predicate arms or selects setup.
- [ ] Locked pre-level S3K title cards do not execute already-loaded level objects.
- [ ] Locked S3K title cards advance VBlank only; level frame, oscillator, player, events, camera scroll/bounds, touches, and RNG remain unchanged; first-unlocked oscillator/camera advance is asserted.
- [ ] Oscillator suppression is set only in branches that actually dispatch title-card objects.
- [ ] Only `FULL + post-load + FRESH_LEVEL_ASSEMBLY` requests setup; preview, no-post-load, decode-only, warm/shared reuse, state restoration, seamless in-place transitions, and failed loads do not.
- [ ] Failed-load rollback leaves `NONE` while preserving fatal startup propagation; successful fresh reload publishes once.
- [ ] Setup consumption follows S3K load-then-execute placement and runs exactly once.
- [ ] Setup captures exec-start player Y and balances solid-registry begin/finish, including exception paths.
- [ ] Setup increments only the audited object-dispatch counter and does not synthesize VBlank.
- [ ] Player and sidekick state are unchanged by the object-only pass.
- [ ] Replay initializes counter phases, consumes the production token, then installs frame-zero RNG.
- [ ] A replay without a production token cannot execute setup.
- [ ] Metadata token invariance and fixed-metadata object-state equivalence are separate tests.
- [ ] Pending and consumed lifecycle rewind/reset/load-failure behavior is covered, including pause-before-first-frame and exception atomicity.
- [ ] Standalone CNZ advances beyond frame 185 through native object/RNG state.
- [ ] AIZ/MGZ standalone frontiers hold.
- [ ] Complete-run f0 gaps are unchanged and remain outside this feature's scope.
- [ ] S3K special stage remains green.
- [ ] Special-stage entry/ticks cannot consume; fresh-return reload versus restored-return state follows the arming matrix.
- [ ] S1/S2 title-card and green trace behavior remains unchanged.
- [ ] `CHANGELOG.md` and `docs/status/trace-frontier-log.md` contain exact measured evidence.
- [ ] Required source, rewind, trace, compression, and tooling guards pass.
- [ ] No ROM, trace payload, disassembly symlink, generated report, or unrelated dirty file is staged.
- [ ] All commit trailers satisfy `.githooks/run-policy`; no commit used `--no-verify`.
