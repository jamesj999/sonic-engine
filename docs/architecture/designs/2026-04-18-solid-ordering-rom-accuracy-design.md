# Solid-Object Ordering ROM Accuracy Design

Date: 2026-04-18

## Goal

Design an engine-level fix for solid-object ordering inaccuracies shared across Sonic 1, Sonic 2, and Sonic 3K, without adding more object-specific hacks.

This document is design-only. It does not implement code.

## Scope

In scope:

- Shared frame/update ordering around player movement, object execution, and solid-contact resolution
- Shared solid-contact state flow in `ObjectManager`, `LevelFrameStep`, `LevelManager`, `SpriteManager`, `CollisionSystem`, and `GameRuntime`
- Object-local compensations that exist because the shared architecture does not yet match ROM ordering

Out of scope:

- One-off cylinder fixes
- Object-by-object implementation work
- Changing plane-switcher scheduling or claiming new plane-switcher ROM parity in this revision
- Non-solid ordering topics unless they directly affect solid timing or same-frame contact semantics

## Terminology And ROM References

"ExecuteObjects-style scheduler" in this document means the ROM model where object slots execute in slot order and individual object routines invoke solid helpers inline from inside their own routine bodies.

Key disassembly anchors:

- S1: `ExecuteObjects:` at `docs/s1disasm/sonic.asm:5495`
- S1: `PlatformObject:` at `docs/s1disasm/sonic.asm:4567`
- S1: `SolidObject:` at `docs/s1disasm/_incObj/sub SolidObject.asm:14`
- S2: `SolidObject:` at `docs/s2disasm/s2.asm:34813`
- S2: `SlopedSolid_SingleCharacter:` at `docs/s2disasm/s2.asm:34923`
- S2: `PlatformObject:` at `docs/s2disasm/s2.asm:35485`
- S3K: `SolidObjectFull:` at `docs/skdisasm/sonic3k.asm:41000`
- S3K: `SolidObjectTop:` at `docs/skdisasm/sonic3k.asm:41779`
- S3K: `SolidObjectTopSloped2:` at `docs/skdisasm/sonic3k.asm:41826`

What is proven from those references:

- ROM objects call solid helpers inline during object execution.
- The scheduler order matters: earlier slots can mutate player state before later slots run.
- S1 uses a unified collision model, while S2/S3K use dual-path collision with plane-switcher-driven path changes.

What is inferred from those references plus local engine comments:

- Objects that read standing bits, pushing bits, or pre-contact velocities after their solid helper expect that data to be immediately available in the same routine.

## Proven Current Engine Behavior

### Shared frame flow

`LevelFrameStep.execute(...)` currently splits player physics and object execution into separate top-level steps.

- S2/S3K path: player physics first, then object execution via `LevelManager.updateObjectPositionsPostPhysicsWithoutTouches()` and `ObjectManager.update(..., inlineSolidResolution=true, solidPostMovement=true)`.
- S1 path: object execution first via `LevelManager.updateObjectPositionsWithoutTouches()`, then player physics, then a post-movement batched solid pass from `SpriteManager.tickPlayablePhysics(...)`.

This is proven by:

- `src/main/java/com/openggf/LevelFrameStep.java`
- `src/main/java/com/openggf/level/LevelManager.java`
- `src/main/java/com/openggf/sprites/managers/SpriteManager.java`

### Collision-model split

The engine intentionally distinguishes:

- `CollisionModel.UNIFIED` for Sonic 1: one collision index, no dynamic path switching
- `CollisionModel.DUAL_PATH` for Sonic 2 / Sonic 3K: dual collision indices plus runtime path/priority switching

This is proven by:

- `src/main/java/com/openggf/game/CollisionModel.java`
- `src/main/java/com/openggf/game/CrossGameFeatureProvider.java`

This matters because S1 is not just "a different frame order." It also has different collision-path semantics. Any shared fix has to preserve that distinction.

### Shared solid-contact architecture

`ObjectManager` currently contains two materially different solid paths:

1. A partial inline path:
   - `beginInlineFrame(...)`
   - `runExecLoop(...)`
   - `processInlineObject(...)`
   - `finishInlineFrame(...)`
2. A legacy batched path:
   - `updateSolidContacts(...)`
   - `SolidContacts.update(player, postMovement)`

The partial inline path still resolves solids after the object's full `update(...)` returns. It does not resolve at the true ROM call site inside the routine.

The legacy batched path still remains authoritative for:

- S1 post-movement solid resolution
- `CollisionSystem.resolveSolidContacts(...)`
- helper queries such as `hasStandingContact(...)` and `getHeadroomDistance(...)`

This is proven by:

- `src/main/java/com/openggf/level/objects/ObjectManager.java`
- `src/main/java/com/openggf/physics/CollisionSystem.java`

### Callback timing

Objects currently receive positive solid results through `SolidObjectListener.onSolidContact(...)`, but that callback fires after the solver has already mutated player state.

This is proven by:

- `ObjectManager.SolidContacts` comments around pre-contact snapshots
- objects reading `getPreContactXSpeed()`, `getPreContactYSpeed()`, and `getPreContactRolling()`

### Runtime ownership today

`GameRuntime` already owns mutable runtime-wide registries and clears transient frame state on reset/teardown. It does not yet own any object-execution or solid-checkpoint registry.

This is proven by:

- `src/main/java/com/openggf/game/GameRuntime.java`

That means the design choice here is real: either add new transient execution state to `GameRuntime`, or create a separate ownership model. This document recommends the former.

## Proven ROM Behavior

From the disassembly references above and from engine comments that explicitly compare current behavior to the ROM:

- Solid checks are performed inline during object execution.
- Later objects see player state already modified by earlier solid contacts in the same frame.
- Many objects read standing, pushing, or contact-derived state immediately after their solid helper returns.
- Some objects call a solid helper before later movement in the same routine.

This document does not claim proof for plane-switcher ordering. The current implementation plan keeps existing plane-switch timing unchanged and treats each player's already-established path/layer state as an input to solid checkpoints, not something this project reorders.

## Concrete Architectural Mismatches

### 1. S1 still depends on a pre-apply plus post-batched bridge

Current engine behavior:

- `LevelManager.updateObjectPositionsWithoutTouches()` pre-applies player velocity before object execution.
- `SpriteManager.tickPlayablePhysics(...)` later runs a post-movement batched solid pass.

ROM behavior:

- Solid helpers are called inline from object routines, not through a separate shared pass.

Why this is a true shared architecture issue:

- The pre-apply plus post-pass bridge exists to mimic inline timing without actually giving the object routine ownership of when solid resolution happens.

### 2. S2/S3K "inline" resolution still happens too late

Current engine behavior:

- `ObjectManager.runExecLoop()` runs `instance.update(...)`, then `processInlineObject(...)`.

ROM behavior:

- The object routine itself decides when the solid helper runs.

Why this is a true shared architecture issue:

- Any object that calls `SolidObject*` before later motion or later state changes still diverges even under the current "inline" mode.

### 3. Objects cannot observe no-contact as a routine result

Current engine behavior:

- `onSolidContact(...)` only fires on positive contact.

ROM behavior:

- The object routine can observe that standing/pushing bits are now clear after the helper returns.

Why this is a true shared architecture issue:

- Objects that depend on same-frame clear semantics must add manual validation or latches because "no contact" is not a first-class engine result.

### 4. Previous-frame standing/contact state is manager-owned, not routine-owned

Current engine behavior:

- Riding/support state lives in `ObjectManager.SolidContacts`.
- Objects poll manager state or preserve their own booleans.

ROM behavior:

- Standing/pushing bits are read as part of the object's own routine flow.

Why this is a true shared architecture issue:

- The shared manager owns the data shape, so objects reconstruct ROM semantics indirectly instead of receiving them directly.

### 5. Helper APIs still encode the old shared-pass model

Current engine behavior:

- `CollisionSystem.resolveSolidContacts(...)`, `hasStandingContact(...)`, and `getHeadroomDistance(...)` still assume a broad shared-pass abstraction.

ROM behavior:

- Solid resolution state is a side effect of object routines running in order.

Why this is a true shared architecture issue:

- Even after some objects migrate, those helpers will keep reintroducing the old mental model unless they are rewritten around checkpoint snapshots.

### 6. Object execution is fragmented across multiple update paths

Current engine behavior:

- `ObjectManager` does not have one single "run an object with solid context" helper today.
- `instance.update(...)` is called from the normal slot loop, counter-based S1 execution, slotless fallback loops, and `runNewlyLoadedObjects(...)`.

- ROM behavior:

- The scheduler contract applies to every object routine that runs that frame, regardless of which engine code path reached it.

Why this is a true shared architecture issue:

- If the runtime-owned execution context only wraps `runExecLoop(...)`, S1 counter-based objects and newly-loaded objects will still bypass the new ordering model.

## Proven Engine-Level Compensations

These are already shared workarounds. They are not object-specific ROM logic.

- `LevelManager.updateObjectPositionsWithoutTouches()` pre-applies velocity before object updates.
- `ObjectManager.SolidContacts` keeps a generic sticky contact buffer to reduce dropped riders.
- `ObjectManager.SolidContacts.resolveContactInternal(...)` applies velocity-based side/top classification adjustment to compensate for non-ROM timing.
- `deferSideToPostMovement` exists to suppress side effects in a pre/post split model.
- `refreshRidingTrackingPosition(...)` exists to prevent double-applying late object motion.
- `preContactXSpeed`, `preContactYSpeed`, and `preContactRolling` exist because callbacks arrive after contact mutation.

These are all proven by explicit comments in `ObjectManager.java` and `LevelManager.java`.

## Object-Local Adaptations

This section separates confirmed workarounds from likely ones. "Confirmed" means the file itself explicitly ties the behavior to solid-ordering mismatch or delayed contact semantics. "Likely" means the pattern strongly suggests that role, but the file is less explicit.

### Confirmed cross-game adaptation

- `Sonic1MonitorObjectInstance`
- `MonitorObjectInstance`
- `Sonic3kMonitorObjectInstance`

Shared pattern:

- Override `usesStickyContactBuffer()` to opt out of a generic engine workaround that exists only to mask moving-platform jitter from the shared solver.

### Confirmed Sonic 1 adaptations

- `Sonic1BreakableWallObjectInstance`
  - Uses `contact.touchSide()` instead of `contact.pushing()` because pushing can be lost before callback delivery.
- `Sonic1FalseFloorInstance`
  - Keeps its collision-center shift in `offsetX()` because moving X directly interacts badly with the shared solver.
- `Sonic1SpikeObjectInstance`
  - Disables the sticky contact buffer so hurt/contact timing stays closer to ROM behavior.
- `Sonic1PushBlockObjectInstance`
  - Opts into `preservesEdgeSubpixelMotion()` because shared edge-collapse behavior loses ROM push cadence.

### Confirmed Sonic 2 adaptations

- `SpringObjectInstance`
  - Broadens diagonal grounded acceptance because delayed split resolution can misclassify sloped standing as side contact.
- `SpringboardObjectInstance`
  - Drives launch from persistent state rather than repeated same-frame callback delivery.
- `CollapsingPlatformObjectInstance`
  - Uses both riding-state polling and callback-driven latches so the stood-on flag flips on the landing frame.
- `FlipperObjectInstance`
  - Manually releases locked players when no callback arrives because callback delivery only covers positive contact.
- `SeesawObjectInstance`
  - Re-validates standing players every frame because callback-only delivery cannot represent same-frame contact clear.
- `TornadoObjectInstance`
  - Calls `refreshRidingTrackingPosition(...)` because the object moves again after the engine's late solid timing.

### Confirmed Sonic 3K adaptations

- `AizLrzRockObjectInstance`
  - Reads pre-contact speed/rolling snapshots because callback delivery is already too late.
- `CorkFloorObjectInstance`
  - Same pattern for break-from-below and roll-break behavior.
- `BreakableWallObjectInstance`
  - Captures pre-contact X speed from `ObjectManager` because the ROM saves velocity before `SolidObjectFull`.
- `HCZHandLauncherObjectInstance`
  - Manually moves grabbed players because controlled players are outside normal solid tracking.
- `AizCollapsingLogBridgeObjectInstance`
  - Tracks standing/ejected players explicitly because the engine keeps re-evaluating contact every frame instead of matching the ROM's stop-calling-solid transition.

### Likely or partial adaptations

- `LauncherSpringObjectInstance`
- `Sonic1SeesawObjectInstance`
- `Sonic1PlatformObjectInstance`
- `Sonic1MovingBlockObjectInstance`
- `Sonic1LargeGrassyPlatformObjectInstance`
- `Sonic1GlassBlockObjectInstance`
- `Sonic1VanishingPlatformObjectInstance`

Why these are only likely:

- They show the same structural pattern as the confirmed cases, usually by polling `isPlayerRiding()` or preserving state outside the callback path, but the code comments do not state the compensation purpose as directly.

## Same-Frame-Dependent Object Families

These are not all "hacks." They are object families whose intended behavior depends on same-frame standing/contact state, so they are inherently sensitive to the architecture.

- `Sonic1ButtonObjectInstance`
- `Sonic3kButtonObjectInstance`
- `Sonic1PlatformObjectInstance`
- `ARZPlatformObjectInstance`
- `SpringboardObjectInstance`
- `FlipperObjectInstance`
- `SeesawObjectInstance`
- `CollapsingPlatformObjectInstance`

Common pattern:

- A solid interaction occurs.
- The object wants to branch on standing/contact state in the same routine or same frame.
- The current shared architecture forces latches, polling, or manual clear logic.

## ROM Call-Site Inventory And Sizing

The migration scope is broad enough that Phase 2 needs a definitive inventory, not just a hand-picked object list.

Raw label-reference counts from bundled disassembly grep:

```powershell
(rg -n 'SolidObject' docs/s1disasm | Measure-Object -Line).Lines
(rg -n 'SolidObject|SlopedSolid_SingleCharacter|PlatformObject' docs/s2disasm | Measure-Object -Line).Lines
(rg -n 'SolidObjectFull|SolidObjectTop|SolidObjectTopSloped2' docs/skdisasm | Measure-Object -Line).Lines
```

Current raw counts from those commands:

- S1 raw `SolidObject` references across `docs/s1disasm`: 41
- S2 raw `SolidObject` / `SlopedSolid_SingleCharacter` / `PlatformObject` references across `docs/s2disasm`: 268
- S3K raw `SolidObjectFull` / `SolidObjectTop` / `SolidObjectTopSloped2` references across `docs/skdisasm`: 422

Important caveats:

- These are raw grep-derived label-reference counts, not deduplicated object routine counts.
- They include definitions, comments, and repeated references across files in the disassembly directories.
- Some are multiple call sites in one routine.
- Some are helper trampolines or repeated states within the same object family.

What is proven from the counts:

- S1 is not a one-off special case.
- S2 is not "just CNZ plus a few platforms"; the migration surface is broad.
- S3K will require a staged migration and a real inventory.

Required Phase 0 follow-up:

- Generate a deduplicated inventory of object routines that call `SolidObject*` per game, ideally from `RomOffsetFinder` plus disassembly grep.
- Use that inventory as the migration source of truth for manual-checkpoint adoption.

## Recommended Design Direction

Target architecture: converge on one ExecuteObjects-style scheduler across S1, S2, and S3K, where solid resolution happens at explicit object-routine checkpoints.

Migration strategy: get there in two stages.

- Stage 1: add manual checkpoints inside the existing engine structure, with compatibility fallback for unchanged objects
- Stage 2: remove S1's pre-apply plus post-batch bridge and make checkpointed object execution the only authoritative gameplay path

This keeps the direction ROM-accurate while allowing incremental migration.

Why not stay with batched heuristics:

- Another round of sticky buffers, delayed side effects, and object-local exception logic would keep redistributing the same mismatch instead of removing it.
- Every new heuristic increases the list of objects that must opt in or opt out of shared behavior, which is the exact trap this design is trying to exit.

## Runtime Ownership

The new execution state should be runtime-owned, not `ThreadLocal`.

Recommendation:

- Add a `SolidExecutionRegistry` (or equivalently named runtime registry) to `GameRuntime`.
- Treat it like the other runtime-owned mutable registries: created with the runtime, cleared on runtime teardown, and cleared from `clearTransientFrameState()` for frame-transient data.

Why this is the right ownership model:

- `GameRuntime` already owns mutable gameplay state.
- Editor enter/exit, level rebuild, and hard reset semantics are already expressed through runtime lifecycle.
- `ThreadLocal` would hide ownership, complicate testability, and be harder to reason about under headless tools and future editor workflows.

What remains object-local:

- The object still decides when to call its checkpoint.
- The checkpoint result returned to the routine is routine-local data.

What remains runtime-owned:

- Current executing-object context
- Previous-frame per-object/per-player standing history
- Latest current-frame checkpoint snapshot per object, used by shared helpers
- Final current-frame checkpoint snapshot per object, promoted into previous-frame history at `finishFrame()`

## API Sketch

This is a design sketch, not a final Java API. The point is to resolve the open semantics.

```java
public interface SolidExecutionRegistry {
    void beginFrame(int frameCounter, List<PlayableEntity> players);
    void beginObject(ObjectInstance object);
    void endObject(ObjectInstance object);
    void finishFrame();

    ObjectSolidExecutionContext currentObject();
    ObjectStandingHistory history();
    void clearTransientState();
}

public interface ObjectSolidExecutionContext {
    ObjectInstance object();

    SolidCheckpointBatch resolveSolidNowAll();
    PlayerSolidContactResult resolveSolidNow(PlayableEntity player);

    SolidCheckpointBatch lastCheckpoint();
    PlayerStandingState previousFrameState(PlayableEntity player);
}

public record SolidCheckpointBatch(
        ObjectInstance object,
        Map<PlayableEntity, PlayerSolidContactResult> perPlayer) {
}

public record PlayerSolidContactResult(
        ContactKind kind,
        boolean standingNow,
        boolean standingLastFrame,
        boolean pushingNow,
        boolean pushingLastFrame,
        PreContactState preContact,
        PostContactState postContact) {

    public static PlayerSolidContactResult noContact(
            boolean standingLastFrame,
            boolean pushingLastFrame,
            PreContactState preContact,
            PostContactState postContact) { ... }
}

public enum ContactKind {
    NONE,
    TOP,
    SIDE,
    BOTTOM,
    CRUSH
}
```

Semantics closed by this sketch:

- Multi-player result shape: explicit per-player map, not one flattened result.
- Negative contact: `ContactKind.NONE` through `noContact(...)`, never `null`.
- Previous-frame state: stored in a runtime-owned history registry, not ad hoc object fields.
- Context delivery: through a runtime-owned current-object context surfaced via object services, not by changing `ObjectInstance.update(...)` signatures across the whole codebase.

Additional checkpoint semantics:

- `resolveSolidNowAll()` executes a fresh solid checkpoint every time it is called during an object's `update(...)`.
- The object routine is responsible for holding onto the returned result if it wants to reuse the same snapshot later in the same routine.
- `lastCheckpoint()` returns the most recently executed checkpoint for the current object.
- Shared helper queries consume the latest checkpoint seen for that object/player that frame.
- Previous-frame standing/pushing history is promoted from the final checkpoint executed for that object/player that frame.

## Context Delivery To Objects

Recommended delivery path:

- Keep `ObjectInstance.update(int frameCounter, PlayableEntity player)` unchanged for now.
- Expose the current execution context through `ObjectServices`, for example as `services().solidExecution()`.

Why:

- `ObjectInstance.update(...)` is pervasive and changing its signature would turn this design migration into a repo-wide object API migration.
- The engine already uses `ObjectServices` as the injected object-facing access point.
- The context is only valid while an object is executing, so it should be accessed through a scoped runtime-owned current-object context.

Required guardrails:

- `services().solidExecution()` must throw or return an inert object outside an object execution window so misuse is obvious.
- `ObjectManager` must route every actual `instance.update(...)` call through one shared execution helper that brackets `beginObject(...)` / `endObject(...)`.
- That helper must be used by the normal slot loop, counter-based S1 execution, slotless fallback loops, and `runNewlyLoadedObjects(...)`.

## Previous-Frame State Storage

Previous-frame standing/pushing history should live in the new runtime-owned registry, keyed by:

- live `ObjectInstance` identity
- `PlayableEntity` identity

It should not be added as mandatory fields on `AbstractObjectInstance`.

Why:

- Many objects do not need custom standing-history storage.
- The history is a shared execution concern, not per-object behavior logic.
- Runtime-owned keyed storage is easier to clear when objects unload, levels rebuild, or the runtime is torn down.

Lifecycle rules:

- History for destroyed or unloaded objects is discarded at unload/end-of-frame cleanup.
- History for players that no longer exist is discarded at frame start.
- Current-frame checkpoint results become previous-frame history at `finishFrame()`.

## Multi-Player Semantics

The checkpoint API must resolve all active playables, not just the main player.

Required semantics:

- Every checkpoint resolves against every active `PlayableEntity`.
- The returned batch preserves one explicit result per playable.
- The engine updates shared support/riding state for all playables from that same checkpoint.
- Legacy auto-checkpoint mode also resolves all active playables, not only the `player` argument passed to `update(...)`.

Why this is required:

- Main player, sidekick, and additional playable characters can all stand on or collide with solids.
- A one-player checkpoint API would recreate the same architecture bug for sidekicks.

What remains object-specific:

- An object can still choose to branch only on the main player result if that matches ROM behavior for that routine.
- That choice happens after the shared engine has produced correct per-player results.

## UNIFIED Versus DUAL_PATH Semantics

The checkpoint API should be shared, but the internal collision semantics must remain model-specific.

### UNIFIED (S1)

Required semantics:

- One collision index
- No plane-switcher-managed path changes
- Inline solid checkpoints still run in object-slot order, but path/layer fields are not part of the checkpoint state

Migration implication:

- S1 migration is larger than a reorder. It removes the current velocity pre-apply plus post-batch bridge and must preserve S1's existing UNIFIED terrain/solid behavior while moving solid timing into the object slot model.

### DUAL_PATH (S2/S3K)

Required semantics:

- The checkpoint uses each player's current top/left-right-bottom solid bits and whatever path/layer state the engine has already established at the moment the checkpoint runs.

Migration implication:

- This design revision does not reorder plane switchers. It only makes solid-check timing more ROM-accurate within the current path/layer model.

## Plane-Switcher Interaction

Plane-switcher scheduling is explicitly out of scope for this revision.

What this design does require:

- solid checkpoints must use the player's current path/layer state as already maintained by the existing engine
- helper and object logic must stop assuming a second broad solid pass will reconcile ordering later

What this design does not require:

- moving `applyPlaneSwitchers(...)` into object execution
- claiming new plane-switcher ROM parity
- adding plane-switcher-specific sentinel coverage to this plan

## Required Engine Changes

### 1. Make solid resolution an explicit object-routine operation

`SolidObjectProvider` should no longer imply "the engine will always do one automatic solid pass after `update()` and that is the authoritative gameplay model."

Target state:

- Legacy auto-after-update mode remains as a migration fallback.
- Manual checkpoint mode becomes the ROM-accurate path.
- Manual checkpoint mode supports more than one real checkpoint in a single object routine.

### 2. Make routine-visible checkpoint results the primary API

`SolidObjectListener` can remain temporarily as a compatibility adapter, but it should stop being the authoritative contract for same-frame object logic.

Target state:

- Objects read checkpoint results directly.
- Callback-only latches become compatibility shims and then disappear.

### 3. Put standing history and checkpoint snapshots on the runtime

Add runtime-owned storage for:

- previous-frame per-object/per-player standing history
- latest current-frame per-object checkpoint snapshots
- final current-frame per-object checkpoint snapshots used for history promotion
- currently executing object context

### 3a. Route every object-update path through one shared execution helper

The engine needs one shared helper in `ObjectManager` that owns:

- `beginObject(...)`
- object `update(...)`
- manual or compatibility checkpoint publication
- `endObject(...)`

That helper must be used by:

- the normal slot-ordered exec loop
- counter-based S1 exec
- slotless fallback loops
- `runNewlyLoadedObjects(...)`

### 4. Rewrite helper APIs around checkpoint snapshots

`CollisionSystem.resolveSolidContacts(...)`, `hasStandingContact(...)`, and `getHeadroomDistance(...)` must stop expressing "broad shared solid pass" as the main model.

Target state:

- movement code reads authoritative checkpoint snapshots and support state
- broad batched rescans are no longer required to answer common support/headroom questions

### 5. Remove S1's bridge once checkpoint coverage exists

After enough S1 objects are migrated and validated:

- remove velocity pre-application before object updates
- remove S1 post-movement batched `updateSolidContacts(...)`
- stop treating `deferSideToPostMovement` as a normal gameplay path

## Performance Expectations

The design should not assume "many more total solid visits per frame" as the default case.

What is already true today:

- The current batched/shared pass already visits solid providers and active players.
- The current partial-inline path already performs one post-update per-object visit.

Expected cost profile after migration:

- Legacy objects in auto mode still incur one checkpoint per object, roughly comparable to current partial-inline behavior.
- Manual-checkpoint objects may incur more than one checkpoint if the ROM routine really calls `SolidObject*` more than once.
- New overhead is mainly the per-object/per-player snapshot registry and any extra checkpoints in migrated objects that truly need them.

Why that is acceptable:

- The extra work is targeted at objects whose ROM logic already contains multiple solid helper calls or ordering-sensitive state reads.
- It replaces current heuristic workarounds rather than just stacking on top of them forever.

## Migration Strategy

### Phase 0: Inventory and baseline

- Generate a deduplicated per-game inventory of object routines that call `SolidObject*`.
- Freeze a sentinel list of migration targets and trace scenarios.
- Repair any stale baseline harnesses that later tasks rely on. On this checkout, `TestSolidObjectManager` needs runtime bootstrap repair before it can be used as a gate.
- Ensure baseline tests pass before any ordering change:
  - `TestCollisionLogic`
  - `TestCollisionModel`
  - `CollisionSystemTest`

### Phase 1: Introduce runtime-owned checkpoint infrastructure

- Add the runtime-owned execution registry.
- Add object-scoped context access through object services.
- Keep legacy auto-after-update behavior as default.
- Keep `SolidObjectListener` working as an adapter layer.
- Route every `instance.update(...)` path through the shared execution helper before any family migrations begin.

Acceptance bar:

- Existing generic collision/unit tests remain green.
- No gameplay behavior change intended yet.

### Phase 2: Migrate the highest-risk object families first

Starting sentinel set:

- `TornadoObjectInstance`
- `SpringObjectInstance`
- `SpringboardObjectInstance`
- `FlipperObjectInstance`
- `SeesawObjectInstance`
- `AizLrzRockObjectInstance`
- `CorkFloorObjectInstance`
- `BreakableWallObjectInstance`
- `HCZHandLauncherObjectInstance`

Required work:

- Convert those objects to manual checkpoints.
- Replace object-local latches/polling where the checkpoint result now makes them unnecessary.
- Allow migrated objects to issue more than one real checkpoint per routine when the ROM routine does so.

Acceptance bar:

- Sentinel headless tests pass.
- Checkpoint trace tests pass if the trace API was extended for checkpoint events.
- No new divergence in generic collision tests.

### Phase 3: Move S1 to the same scheduler contract

Required work:

- Remove the velocity pre-apply plus post-batch bridge.
- Keep UNIFIED collision semantics intact while moving solid timing into the shared slot-ordered checkpoint model.
- Migrate S1 solid-sensitive platform, button, breakable, spike, and push-block families.

Acceptance bar:

- S1 trace-replay scenarios stay green against the retail S1 Rev01 ROM.
- The S1-specific sentinels show no new frame-order divergence after bridge removal.

### Phase 4: Remove shared compensations

Do this only after the relevant migrated object families and traces are green.

Deletion candidates and gates:

- `refreshRidingTrackingPosition(...)`
  - Remove only after carrier objects such as `TornadoObjectInstance` and similar late-movers pass manual-checkpoint traces.
- generic sticky contact buffer behavior
  - shrink or remove only after platform, flipper, seesaw, monitor, and spike sentinels no longer rely on it.
- global `getPreContact*()` snapshot accessors
  - remove only after rock, cork floor, and breakable-wall families read structured checkpoint results instead.
- velocity-based side/top classification adjustments
  - remove only after diagonal spring, springboard, and sloped-solid sentinel traces match ROM behavior without them.
- S1 `deferSideToPostMovement` bridge usage
  - remove only after S1 UNIFIED sentinels pass without the post-batch path.

## Validation Strategy

The validation bar needs to be frame-accurate, not "feels better."

### Existing infrastructure to use

- `CollisionTrace`, `RecordingCollisionTrace`, and `NoOpCollisionTrace` in `src/main/java/com/openggf/physics`
- `CollisionSystemTest`
- `HeadlessTestRunner`
- `s1-retro-trace` skill
- `s1-trace-replay` skill

If checkpoint-order traces are part of the implementation plan, extend the existing `CollisionTrace` API with explicit checkpoint events rather than assuming those events already exist.

### ROM builds to use

Use the project's existing reference ROMs:

- `Sonic The Hedgehog (W) (REV01) [!].gen`
- `Sonic The Hedgehog 2 (W) (REV01) [!].gen`
- `Sonic and Knuckles & Sonic 3 (W) [!].gen`

### Required sentinel harness

For each sentinel object family, create or extend a headless test scenario that records per-frame:

- player position and velocity
- standing/riding state
- checkpoint result kind
- object state relevant to the interaction

Each migrated object family must have:

- a deterministic headless scenario in-engine
- a reference ROM trace or equivalent frame-by-frame expectation

### Minimum acceptance bar per migration

An object family is not considered migrated until:

1. The family-specific headless scenario passes.
2. The frame-by-frame trace matches the ROM/reference trace for the targeted interaction.
3. Generic collision tests stay green.
4. No shared compensation is removed until all families depending on that compensation are green.

### Suggested sentinel scenarios

- Adjacent solids with side-correction propagation
- Crush and sandwich timing
- Carrier objects that move again after their solid helper
- Same-frame standing-trigger objects such as buttons and platforms
- Objects that read pre-contact speed/rolling
- S1 UNIFIED scenarios where the current post-batch bridge previously masked timing differences

## Rollback Strategy

Migration will be staged. The design therefore needs an explicit rollback rule.

Recommended rollback unit:

- per object family
- per shared compensation removal

Bad signal that triggers rollback:

- any new frame-by-frame divergence in the sentinel trace for the migrated family
- any regression in `TestCollisionLogic`, `TestCollisionModel`, or `CollisionSystemTest`
- any new sidekick-only divergence in the same scenario

Rollback method:

- revert the migrated family to legacy auto-checkpoint mode
- restore the deleted shared compensation if its family gate was not actually satisfied
- keep the runtime-owned checkpoint infrastructure in place, because that is the enabling architecture, not the risky gameplay behavior change by itself

## Summary

Do not pursue another round of batched-pass heuristics.

The correct shared fix is:

- converge on one ExecuteObjects-style scheduler across S1, S2, and S3K
- make solid resolution happen at explicit object-routine checkpoints
- store checkpoint context and standing history on `GameRuntime`
- expose same-frame per-player contact results as routine-visible data
- treat callback latches, global pre-contact accessors, sticky buffers, and S1 bridge behavior as migration shims to be deleted behind trace-backed gates

That direction addresses the root shared-architecture mismatch. More heuristics do not.
