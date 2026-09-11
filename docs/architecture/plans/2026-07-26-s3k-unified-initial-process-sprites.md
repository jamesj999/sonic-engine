# S3K Unified Initial `Process_Sprites` Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use `superpowers:subagent-driven-development` to implement this plan task-by-task, with a fresh implementation worker and two-stage review for every task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the object-only S3K level-setup approximation with one production-owned, ROM-ordered initial `Process_Sprites` pass that executes playable, collision-reset, dynamic, and fixed SST responsibilities exactly once before the first counted `LevelLoop` frame.

**Architecture:** Keep the existing typed fresh-load lifecycle as the sole arming authority, but rename it for the behavior it now owns and move execution out of `ObjectManager`. A level-owned `InitialProcessSpritesCoordinator` will compose existing managers in native SST order under a dedicated setup phase. It will execute playable slots with neutral logical input, then the collision-reset/dynamic/fixed object envelope, without entering `LevelFrameStep` or advancing ordinary level, VBlank, oscillation, ring, camera, event, water, or tile-animation work. Live title-card release, no-title first-frame entry, and trace bootstrap may consume the same production token; restoration paths discard it and rewind preserves it.

**Tech Stack:** Java 21, Maven, JUnit Jupiter, OpenGGF level/playable/object/rewind lifecycles, ROM-backed trace replay, local `docs/skdisasm/sonic3k.asm`.

## Global Constraints

- The disassembly is the source of truth. Cite the setup sequence at `docs/skdisasm/sonic3k.asm:7848-7856`, `Process_Sprites` at `:35965-36008`, and the SST layout at `docs/skdisasm/sonic3k.constants.asm:303-323` in production comments that encode ordering.
- This plan supersedes only the object-only execution assumption in `2026-07-26-s3k-initial-object-setup-lifecycle.md`. Preserve that plan's production arming, atomic one-shot consumption, title/no-title seams, restoration discard, rewind capture, and no-selector requirements.
- Trace data remains comparison-only. No trace filename, profile, route, zone, act, frame, checkpoint, sidekick metadata, expected value, or current frontier may arm, suppress, or alter setup execution.
- The setup pass must model actual runtime state. It must not hydrate player, sidekick, object, RNG, counter, or lifecycle state from expected trace rows.
- No game-name or zone branch belongs in shared runtime code. `Sonic3kLevelInitProfile` may opt into the typed lifecycle; the coordinator consumes semantic lifecycle state without inspecting game or zone identity.
- Player and sidekick state **is allowed and expected to mutate**. The ROM dispatches `Player_1` and `Player_2` before every other setup SST. Tests must discover and pin native mutations rather than preserving the prior engine state.
- Setup input is neutral because the ROM has not entered `Demo_PlayRecord` and clears/locks control during level assembly. Do not sample live keyboard, controller, BK2, or trace input. Forced control already owned by runtime objects remains visible to the playable routine.
- Preserve native setup order: `Load_Sprites` placement/materialization first, then the ascending SST walk—main player slot 0, sidekick/player 2 slot 1, `Reserved_object_3` slot 2, explicit dynamic slot 3, managed dynamic slots 4-93, then post-dynamic fixed slots 94-109. Slot 93 aliases the first empty `Level_object_RAM` SST but remains inside the ROM's 90-probe managed window. Do not place `Load_Sprites` inside the SST walk.
- The pass must not execute an ordinary `LevelFrameStep` or `LevelLoop`. In particular it must not run palette-frame drain, runtime art queue, dirty-region processing, pre/post-physics events, camera follow, screen events, water-height handling, ring loading/collection, oscillation, ring animation, animated tiles, rendering, audio, lag accounting, or rewind keyframe capture.
- Do not increment `Level_frame_counter`, the production `LevelManager` frame counter, VBlank/VInt counters, lag counter, or global oscillation. A manager-private dispatch ordinal may advance only if it is the existing semantic input to routines dispatched by `Process_Sprites`; it must be tested independently from the gameplay counter.
- Playable animation, follower history, CPU state, collision, touch, and status timers are not globally prohibited. Include the portions reached from the native player object routines in their SST slots; exclude engine post-frame work that is not part of those routines. Tests must distinguish these two categories.
- `Load_Sprites` placement precedes the SST walk. `Load_Rings`, LRZ rock drawing, rendering, and `Animate_Tiles` remain owned by existing load systems and are not silently folded into this coordinator.
- Consume ownership before dispatch. If any slot throws, the token remains consumed and every begun registry/frame scope closes in `finally`.
- S1 and S2 retain `InitialProcessSpritesLifecycle.NONE` and their existing warm-up behavior. Special stages and bonus stages do not acquire the level lifecycle by accident.
- Update `CHANGELOG.md`, `docs/status/trace-frontier-log.md`, and, only if the approved behavior changes a documented discrepancy, the matching status discrepancy document during implementation. Do not encode measured frontier frames in production.
- Execute Tasks 1-8 strictly in order. Every task begins from the reviewed commit from the prior task; none is eligible for parallel implementation.
- No uncompressed trace payload may be added. Every commit uses repository trailers and hooks.

---

## Reconciliation and Evidence

### The prior object-only assumption is disproved

The earlier lifecycle plan said OpenGGF modeled playable creation/reset separately and therefore setup must not tick Sonic or Tails. That statement conflated object construction with the first native player-object dispatch. The ROM does both:

1. `SpawnLevelMainSprites` writes player object pointers and `Obj_ResetCollisionResponseList`.
2. `Load_Sprites`, `Load_Rings`, and the LRZ rock helper run.
3. `Process_Sprites` starts at `Object_RAM`, so player slots execute before dynamic level objects.
4. Only after that pass does setup render/animate tiles and later enter `LevelLoop`.

Candidate diagnostics make the missing playable work observable:

- Object-only setup plus an ordinary first frame double-dispatched AIZ's plane intro and regressed standalone AIZ to frame 719, `x` expected `0x0040`, actual `0x0050`.
- Deferring the token and executing only dynamic setup removed frame 719 but first failed at frame 1057 in `tails_cpu_target_x`, showing that player-1 history/player-2 CPU setup was missing.
- Adding a playable callback moved the first mismatch to frame 2164, but reused an ordinary update envelope and regressed scenario assertions. This proves both that playable setup mutation matters and that calling the normal sprite/frame update is too broad.

The implementation must therefore extract a setup-specific playable SST envelope rather than choosing between object-only dispatch and an ordinary gameplay frame.

### ROM slot order and behavior

`Process_Sprites` iterates `(Object_RAM_end-Object_RAM)/object_size = 110` records in ascending address order unless the teleport/death rendering branch applies. Fresh level setup uses the normal branch:

| SST range | Slots | Setup responsibility |
|---|---:|---|
| `Player_1` | 0 | Main playable routine, including native movement/control gates, follower-history write, animation, and eligible touch logic |
| `Player_2` | 1 | Sidekick/player-2 routine; CPU reads history already written by player 1 |
| `Reserved_object_3` | 2 | `Obj_ResetCollisionResponseList`, clearing only the current collision-list build cursor after player touch has read the prior list |
| `Dynamic_object_RAM` | 3-92 | 90 loadable object slots, including AIZ plane intro in dynamic index 2 / absolute SST slot 5 |
| `Level_object_RAM` | 93-109 | Slot 93 is the empty managed-window alias; named post-dynamic fixed slots begin at 94 |

The order has concrete consequences:

- `Player_1` records position history before the sidekick CPU reads its delayed target.
- The collision-response reset occurs after player touch for the current slot but before dynamic objects publish the next list, matching the ROM list lifecycle.
- AIZ plane-intro state changes after both playable slots and after the reset slot.
- Fixed tails/dust/shield/star routines run after dynamic objects; they are not generic “pre-dynamic fixed hooks.”

`Load_Sprites` is not an SST slot. At `sonic3k.asm:7848-7855`, it completes before `Load_Rings`, the LRZ helper, and the call to `Process_Sprites`; within the coordinator's owned subset the mandatory order is therefore `LOAD -> P1 -> P2 -> RESET -> DYNAMIC_SLOT_3 -> DYNAMIC_SLOTS_4_92 -> FIXED`. `Load_Rings` and the LRZ helper remain existing level-load responsibilities and must already have completed before the coordinator is consumed.

The engine's dynamic allocator does not own all 90 native slots. `ObjectSlotLayout.SONIC_3K` starts at absolute slot 4 and manages 89 slots through 92 because native `AllocateObject` pre-increments from `Dynamic_object_RAM`; `ObjectManager.runExecLoop` therefore cannot represent absolute slot 3. `InitialDynamicSstDispatcher` must expose three distinct operations:

```java
void loadSprites();
void processAbsoluteDynamicSlot3();
void processManagedDynamicSlots4Through93();
```

Fresh `SpawnLevelMainSprites` writes player slots, reset slot 2, fixed power-up slots, and zone intro objects such as AIZ in `Dynamic_object_RAM+(object_size*2)` (absolute slot 5), but does not write absolute slot 3 (`sonic3k.asm:8111-8350`). Task 1's oracle must record the slot-3 function pointer as zero immediately before and after initial `Process_Sprites`. The production S3K adapter implements `processAbsoluteDynamicSlot3()` as an explicit evidence-backed empty operation for fresh level setup and fails its invariant test if a registered owner appears; the generic coordinator still emits a `DYNAMIC_SLOT_3` stage before `DYNAMIC_SLOTS_4_92`. Do not relabel the existing 89-slot manager loop as 90 slots.

The rare `Process_Sprites` death branch draws a subset of dynamic objects rather than executing them. It is not active during a fresh setup pass and must not be generalized into this coordinator without separate ROM evidence and tests.

### Fixed SST inventory

The fixed range is exactly 17 slots (`sonic3k.constants.asm:309-323`). The S3K-specific implementation lives behind `InitialFixedSstDispatcher`, supplied by `Sonic3kLevelEventManager` through the game module/provider boundary. The generic coordinator receives that semantic dependency and never discovers the game or zone. “No engine owner” below is an explicit audit result to prove with an empty-slot test; it is not permission to skip the slot.

| SST | Native label | Spawn/init condition | Current engine owner | Initial-pass operation and expected mutation | ROM/test evidence |
|---:|---|---|---|---|---|
| 93 | unnamed fixed slot | never initialized by `SpawnLevelMainSprites` | none | `empty(93)`; no mutation | constants `:309-310`; assert no registered owner |
| 94 | `Breathing_bubbles` | zero after SST clear; activated later by drowning logic | `S3kFixedAirCountdownManager` via `Sonic3kLevelEventManager` | dispatch P1 fixed-air controller; fresh inactive controller must remain inert | constants `:311`; fixed-air focused snapshot test |
| 95 | `Breathing_bubbles_P2` | zero after clear; activated later for P2 | same | dispatch P2 fixed-air controller; fresh inactive controller remains inert | constants `:312`; P2 fixed-air test |
| 96 | `Tails_tails_2P` | competition-only tails visual, not main-level spawn | playable/tails renderer; no fixed SST controller exists | `empty(96)` in non-competition level lifecycle; provider rejects competition arming | constants `:314`; profile eligibility test |
| 97 | `Tails_tails` | installed/used by Tails player routine when applicable, not directly in `SpawnLevelMainSprites` | playable Tails animation/render state | semantic tails-fixed dispatch after dynamic; prove whether the initial player routine activates it, then apply one native tails mapping step or explicit empty | constants `:315`; ROM oracle plus tails animation test |
| 98 | `Dust` | `Obj_DashDust` for Sonic/Knuckles P1 (`sonic3k.asm:8359-8385`) | `SpriteManager.advanceFixedSkidDustAfterObjectExecution` / movement dust state | one post-dynamic fixed dust dispatch; may initialize/advance mapping | spawn and dust routine source; focused P1 dust test |
| 99 | `Dust_P2` | `Obj_DashDust` for Tails/P2 where spawned (`:8368-8375`, `:8388+`) | same | one post-dynamic P2 dust dispatch; inactive slots inert | spawn source; focused P2 dust test |
| 100 | `Shield` | `Obj_InstaShield` for Sonic/Knuckles, or saved elemental shield replaces it (`:8359-8385`, `:8280-8330`) | `AbstractPlayableSprite` power-up handles plus `ObjectManager` power-up instances | dispatch the registered P1 shield fixed object once after dynamic | spawn/power-up source; shield type matrix test |
| 101 | `Shield_P2` | normally zero in one-player level setup | no independent fixed P2 shield owner unless a live power-up instance is registered | explicit empty or registered P2 shield dispatch, determined by owner registry rather than character/zone | constants `:319`; empty/registered test |
| 102 | `Invincibility_stars[0]` | zero unless power-up restoration/player routine creates stars | `Sonic3kInvincibilityStarsObjectInstance` via `ObjectManager` | dispatch star 0 if registered | constants `:320`; slot-order star test |
| 103 | `Invincibility_stars[1]` | same | same | dispatch star 1 if registered | same |
| 104 | `Invincibility_stars[2]` | same | same | dispatch star 2 if registered | same |
| 105 | `Invincibility_stars[3]` | same | same | dispatch star 3 if registered | same |
| 106 | `Invincibility_stars_P2[0]` | zero unless P2 power-up creates stars | current power-up registry or no owner | dispatch registered P2 star 0, otherwise explicit empty | constants `:321`; P2 registry test |
| 107 | `Invincibility_stars_P2[1]` | same | same | dispatch registered P2 star 1, otherwise explicit empty | same |
| 108 | `Invincibility_stars_P2[2]` | same | same | dispatch registered P2 star 2, otherwise explicit empty | same |
| 109 | `Wave_Splash` | HCZ load installs wave splash state (`sonic3k.asm:7786-7787`) | `Sonic3kWaterSurfaceManager`/zone feature provider | dispatch one fixed wave-splash animation step only when its semantic owner is registered; AIZ/CNZ empty | constants `:322`; HCZ and non-HCZ owner tests |

Task 1's ROM oracle decides the two deliberately evidence-dependent entries (native Tails tails activation and fresh dust/shield mapping mutation) before implementation. Task 4 may update the expected-mutation column from that evidence, but it may not defer ownership, omit a slot, or add a zone branch to the coordinator.

### Setup counters and input

The setup call occurs before `LevelLoop` increments `Level_frame_counter` and before `Demo_PlayRecord`. Therefore:

- main-player logical input is zero;
- controller-2 held/pressed input is zero;
- no live input handler is consulted;
- player object routines may still honor control locks and forced input already set by production setup objects;
- `LevelManager.frameCounter`, VInt/VBlank, lag, oscillation, and ring-frame counters do not advance;
- playable animation and CPU routines receive the pre-loop dispatch epoch used by the ROM, not a fabricated first gameplay frame;
- `SpriteManager.frameCounter` cannot be incremented and restored around a broad update if its temporary value leaks into CPU or animation decisions. The extracted API must accept an explicit `ProcessSpritesEpoch` whose counter value is derived from production counters and whose mode prevents gameplay-counter publication.

The exact playable mutations are an oracle question, not a policy choice. Task 1 records them at the pre-call PC `$00647E` and return PC `$006484` of the `jsr (Process_Sprites).l` in `loc_6468` (verify the PCs against the assembled locked-on ROM before accepting output), plus the first ordinary `LevelLoop` player-slot boundary. It records at least centre/subpixel position, velocity, routine/status/control bits, animation id/frame/timer, follower-history cursor/entry, sidekick CPU state/targets/timers, water state, invulnerability/status timers, and collision/touch-visible fields.

### Collision-response list mapping

ROM `Obj_ResetCollisionResponseList` at `sonic3k.asm:8467-8469` clears only the current list count in slot 2. Player touch has already read the list built by the preceding native pass. OpenGGF must map that temporal split explicitly:

1. Before `LOAD`, freeze the existing `ObjectCollisionResponseList.previousObjects` as the setup player-touch read view and set `usePrevious=true`; take the ordinary frame-start touch snapshot without clearing overlap state.
2. `P1` and `P2` read that frozen previous view. Dynamic materialization from `LOAD` cannot enter this already-selected read view.
3. `RESET` clears only the current list-build cursor/publisher collection. It does **not** clear `previousObjects`, toggle `usePrevious`, refresh the player touch snapshot, or reset `ObjectTouchResponseController` enemy/special overlap latches.
4. `DYNAMIC` and `FIXED` publish the new current list in SST order.
5. After fixed slot 109, `captureForNextFrame(currentObjects)` replaces `previousObjects` with eligible publishers, then leaves `usePrevious=true` for the first ordinary player slots.

Add narrow `ObjectCollisionResponseList` operations named for these semantics (`freezePreviousReadView`, `resetCurrentBuild`, `captureCompletedBuild`) rather than a broad `clear`. Its rewind snapshot must include ordered `previousObjects` by rewind id, `usePrevious`, the in-progress current build/cursor, frame-start touch snapshot, and all enemy/special overlap-latch maps. Tests seed a previous SPECIAL and ENEMY object, prove P1/P2 see both, prove reset preserves the selected view and latches, prove an edge-triggered SPECIAL does not retrigger, prove overlapping ENEMY remains poll-every-frame, and prove a newly published dynamic object is visible only on the next ordinary player pass.

### Exact setup epoch and ordinals

The setup `ProcessSpritesEpoch` is native `Level_frame_counter == 0`. Trace bootstrap may initialize production phase counters first, but must pass the value those counters expose for the pre-loop setup—zero for a fresh level—and may not derive it from trace frame numbering. Both P1 and P2 receive that same immutable epoch. The first ordinary frame increments/publishes the normal gameplay epoch to 1 before its player/object cadence.

`SpriteManager.frameCounter`, `LevelManager.frameCounter`, VBlank/VInt, lag, oscillation, and ring-frame counters remain 0 throughout setup; no increment-and-restore is permitted. `ObjectManager.frameCounter` is a persistent object-dispatch ordinal rather than `Level_frame_counter`: the setup scope advances it once from 0 to 1 before dynamic/fixed dispatch and does not restore it; the first ordinary object pass advances it to 2. The coordinator exposes both `nativeLevelEpoch=0` and `objectDispatchOrdinal=1`, so playable code uses the former and object routines retain the proven latter.

Before extraction, audit every direct cadence read in `SpriteManager`, `PlayableSpriteRuntimeServices`, `SidekickCpuController`, animation managers, movement code, and fixed dispatchers. Code reachable during setup must use the explicit epoch or the documented persistent object ordinal; it must not fall back to `SpriteManager.getFrameCounter()`, `LevelManager.getFrameCounter() + 1`, or trace counters. Tests pin setup values `(level=0, sprite=0, object=1)`, first ordinary values `(level=1, sprite=1, object=2)`, and CPU/animation decisions at both boundaries.

### Current ownership gaps

| Concern | Current owner | Gap to close |
|---|---|---|
| Typed request | `InitialObjectSetupLifecycle.S3K_LOAD_THEN_EXECUTE_ONCE` | Name and contract promise object-only work |
| One-shot state | `InitialObjectSetupCoordinator` | Sound ownership; rename to process-sprites semantics |
| Execution | `LevelManager.consumePendingInitialObjectSetupPass()` delegates to `ObjectManager` | Cannot compose player and fixed-slot work in native order |
| First frame | `LevelFrameStep.execute()` consumes then continues | Performs setup and ordinary loop in one call |
| Playables | `SpriteManager.update*()` and warm-up helpers | Broad gameplay envelope or ad hoc partial preludes; neither models one setup SST pass |
| Dynamic objects | `ObjectManager.runInitialS3kLoadThenExecutePass()` | Correct placement seed, but skips players/fixed SST responsibilities and hard-codes S3K in shared manager API |
| Fixed objects | split between level event hooks, sprite helpers, and object manager | No explicit ROM slot-order contract |
| Trace bootstrap | `TraceReplaySessionBootstrap` consumes token before frame-zero RNG | Keep ordering, change semantic API only |
| Restoration | complete-run/bootstrap paths discard token | Preserve: represented state must not replay setup |
| Rewind | `LevelSnapshot` captures enum | Preserve pending/executed state through enum migration |

---

## Target Design

### Typed lifecycle

Rename the type and value to describe the actual native boundary:

```java
public enum InitialProcessSpritesLifecycle {
    NONE,
    LOAD_THEN_PROCESS_ONCE
}
```

`LevelInitProfile.initialProcessSpritesLifecycle()` defaults to `NONE`.
`Sonic3kLevelInitProfile` returns `LOAD_THEN_PROCESS_ONCE`. `LevelLoadContext` may request it only through the existing fresh full post-load assembly gate. The generic value does not name S3K; the game-specific profile is the opt-in owner.

Rename `InitialObjectSetupCoordinator` to `InitialProcessSpritesLifecycleCoordinator`. It remains a one-field, atomic consume-before-dispatch state machine and retains capture/restore/discard behavior.

### Production coordinator

Add a package-private level-owned coordinator:

```java
final class InitialProcessSpritesCoordinator {
    void execute(InitialProcessSpritesContext context);
}

record InitialProcessSpritesContext(
        InitialProcessSpritesStages stages,
        ProcessSpritesEpoch epoch) {
}

record InitialProcessSpritesStages(
        InitialDynamicSstDispatcher dynamic,
        PlayableSstDispatcher playables,
        CollisionListSstDispatcher collisionList,
        InitialFixedSstDispatcher fixed) {
}
```

`execute` owns one composition, in this order:

1. Open an explicit `INITIAL_PROCESS_SPRITES` playable/object dispatch scope.
2. Freeze the prior collision-response read view, then run `Load_Sprites` placement/materialization.
3. Execute the main playable slot with neutral logical input.
4. Execute active sidekick/player-2 slots in stable native slot order. For the normal team this means player 2 immediately after player 1; additional engine-only sidekicks require an explicit stable-slot policy and cannot interleave before player 2.
5. Execute slot 2, clearing only the current collision-list build state.
6. Visit explicit absolute dynamic slot 3, then execute the managed 90 slots 4-93 in ascending slot order, including child-spawn flushing at the audited native allocation seam.
7. Execute post-dynamic fixed slots 94-109 through `InitialFixedSstDispatcher`.
8. Capture the completed collision list for the following ordinary frame and close all scopes.

Because the engine's managers do not mirror one physical SST array, each stage must expose a narrow semantic primitive; the coordinator is the only place allowed to reconstruct the cross-manager order. It must not call `LevelFrameStep.execute`, `SpriteManager.update`, `SpriteManager.updateWithoutInput`, `LevelManager.updateObjectPositions*`, or any title-card prelude loop.

The four collaborator interfaces are package-private in `com.openggf.level.initial` and the coordinator accepts them through `InitialProcessSpritesStages`; production adapters wrap `SpriteManager`, `ObjectManager`/`ObjectCollisionResponseList`, and the game-provided fixed dispatcher. Tests construct lambda/fake collaborators that append `LOAD, P1, P2, RESET, DYNAMIC_SLOT_3, DYNAMIC_SLOTS_4_92, FIXED, CAPTURE, CLOSE`, inject failures after any label, and assert closure. No concrete manager subclassing or global singleton is required.

### Playable-slot API

Expose one narrow public semantic operation from `SpriteManager` through a public interface implemented by its adapter:

```java
public interface PlayableSstDispatcher {
    void processInitialPlayableSlots(
        ProcessSpritesEpoch epoch,
        InitialPlayableInput input);
}

public record InitialPlayableInput(
        int p1Held, int p1Pressed, int p2Held, int p2Pressed,
        boolean consumeQueuedObjectControlState) {
    public static InitialPlayableInput nativeNeutral() { ... }
}
```

`ProcessSpritesEpoch` is a public immutable value carrying `nativeLevelEpoch=0`, `objectDispatchOrdinal=1`, and `advanceGameplayCounter=false`. `InitialPlayableInput.nativeNeutral()` has no dependency on `InputHandler`: raw held and just-pressed `Ctrl_1`/`Ctrl_2` words are zero, matching the clears/locks before setup at `sonic3k.asm:7765-7774`, and P2 manual/virtual controller input is zero. Debug/test shortcuts and BK2/live input are not sampled. Existing object-owned forced-input masks and control locks remain visible. `applyQueuedControlStateForFrameStart` **does run**, because those queued mutations are runtime object-control state rather than user input; it must not consume an input-handler edge or playback cursor. The method reuses the canonical per-playable routine body also used by ordinary updates so setup and gameplay cannot drift in physics, history, animation, status, water, and eligible touch ordering.

`SpriteManager` already owns the active `LevelManager` through its injected
session wiring and ordinary playable dispatch resolves that field internally.
The public setup dispatcher therefore does not accept a redundant
`LevelManager` argument. This keeps `InitialProcessSpritesContext` limited to
the approved stages and epoch, lets `InitialProcessSpritesStages` hold the
public `PlayableSstDispatcher` directly, and avoids a second bound adapter or a
null manager seam.

Refactor `SpriteManager.update` into:

- input/debug collection owned only by ordinary frames;
- `processPlayableSlots(PlayableDispatchContext)` owning the shared main-then-sidekick SST routine;
- the setup API constructing a neutral, non-counted context;
- ordinary update constructing a counted, input-bearing context.

Do not implement setup by incrementing and restoring `SpriteManager.frameCounter`; a temporary counter can still mutate animation and CPU state. Do not call the existing S1 `warmUpFreshMainPlayableOnly` or title-card `warmUpCpuSidekicksOnly`; those represent different native preludes and retain their current callers.

### Object and fixed-slot APIs

Replace `ObjectManager.runInitialS3kLoadThenExecutePass` with semantic primitives called only by the coordinator:

```java
public InitialObjectDispatchScope beginInitialProcessSprites(...);
public void loadInitialDynamicSlots(InitialObjectDispatchScope scope);
public void processInitialDynamicSlots(InitialObjectDispatchScope scope);
public void finishInitialProcessSprites(InitialObjectDispatchScope scope);
```

The scope balances `SolidExecutionRegistry` in `close()`, owns the existing object dispatch ordinal, placement synchronization, destroyed-object cleanup, child-spawn flushing, and next-frame collision-response capture. Its name and logic do not inspect game identity.

Represent slot 2 and fixed slots with an explicit ordered contract rather than existing “before dynamic” gameplay hooks:

```java
interface InitialFixedSstDispatcher {
    void processPostDynamicFixedSlots(ProcessSpritesEpoch epoch);
}
```

Implement it at a factory boundary in `Sonic3kGameModule`, which constructs the fixed dispatcher from semantic owners supplied by `Sonic3kLevelEventManager`, `SpriteManager`/power-up registry, and `Sonic3kZoneFeatureProvider`. Collision reset is a separate generic `CollisionListSstDispatcher`, not a fixed-owner responsibility. Empty slots remain explicit with ROM citations and tests.

Slot 109 uses a narrow bridge because `Sonic3kWaterSurfaceManager` is private to `Sonic3kZoneFeatureProvider`:

```java
public interface InitialWaveSplashSstOwner {
    boolean isRegistered();
    void processInitialWaveSplash(ProcessSpritesEpoch epoch);
}

// Sonic3kZoneFeatureProvider
public InitialWaveSplashSstOwner initialWaveSplashSstOwner();
```

The zone-feature provider returns an owner adapter backed by its private `waterSurfaceManager`; `Sonic3kGameModule.createInitialFixedSstDispatcher(...)` injects that adapter into the fixed dispatcher. The event manager never looks up zone identity, accesses the private manager, or reaches through `GameServices`. HCZ setup registers the semantic owner through the existing zone-feature setup; a non-water zone returns an explicit unregistered/empty owner. `TestS3kInitialFixedSstDispatcher` uses a fake owner to assert slot-109 ordering and exactly one call, while `TestSonic3kZoneFeatureProvider` asserts HCZ registered and AIZ/CNZ unregistered.

### Lifecycle seams

- **Title shown:** `PostTitleCardDestination` consumes the token and runs the coordinator after the card releases. `GameLoop.updateTitleCardMode` returns `TITLE_RELEASE_SETUP_ONLY`; its caller exits the outer iteration instead of falling through to LEVEL. The next `GameLoop` iteration runs the first ordinary frame.
- **No title:** the five-argument `LevelFrameStep.execute(..., StepWrapper)` is the sole token consumer after pause has been evaluated. Both public `execute` overloads return `LevelFrameResult`; a pending token returns `SETUP_ONLY` before any ordinary work. `executeWithPause` returns `PAUSED` when pause owns the iteration, otherwise delegates and returns `SETUP_ONLY` or `GAMEPLAY_FRAME`.
- **Trace bootstrap:** after production counter phase initialization and before frame-zero RNG installation, consume the same token directly. Bootstrap does not call a normal frame and cannot arm a token.
- **Already consumed:** later consumers see `false` and proceed normally.
- **Exception:** ownership clears before dispatch; the exception propagates and no partial pass repeats.

Change every `LevelFrameStep` entry point to the mandatory typed result:

```java
enum LevelFrameResult {
    PAUSED,
    SETUP_ONLY,
    GAMEPLAY_FRAME
}
```

Call-site contract:

| Caller | Required handling |
|---|---|
| `GameLoop.updateTitleCardMode` and its mode router | release+setup exits the outer update; no LEVEL fall-through |
| `GameLoop` live LEVEL path, seamless transition path, and any direct `execute` path | `PAUSED` consumes the live input/VInt window per existing pause semantics; `SETUP_ONLY` consumes neither gameplay timer nor input edge; `GAMEPLAY_FRAME` performs current bookkeeping |
| `RecordingFrameDriver.stepFrame` | move external `frameCounter++`, logical override publication, `previousDriverSnapshot`, and BK2 index advance behind result-aware handling; retry the same input snapshot after `SETUP_ONLY`; preserve existing consumption on `PAUSED` |
| `HeadlessTestRunner` | propagate the driver's typed result; do not increment test frame/trace cursor for `SETUP_ONLY` |
| `TraceSessionLauncher` and `TraceCaptureTool` | do not advance comparison/capture row, audio/video frame, or external counter on `SETUP_ONLY` |
| `LiveRewindStepper` | do not capture a keyframe or advance the rewind timeline on `SETUP_ONLY`; `PAUSED` retains its existing paused-window behavior |
| direct headless/tests (`TestS3kCnzDirectedTraversalHeadless`, `TestZoneLayoutMutationPipeline`, `TestInstaShieldVisual`, lifecycle tests) | assert or explicitly discard only `GAMEPLAY_FRAME`; no void call remains |

`LevelFrameStep.updateTimers` must move behind `GAMEPLAY_FRAME` ownership for drivers that currently invoke it before `executeWithPause`; setup-only cannot age timers. Add call-site tests in `TestGameLoop`, `TestRecordingFrameDriverInputOnly`, `TestInGamePause`, lifecycle tests, `HeadlessTestRunner` integration, and trace capture/session launcher seams.

#### Two-phase live `GameLoop` admission

Returning `SETUP_ONLY` from the current late frame step is insufficient because `GameLoop.update` already ages timers and publishes controls. Refactor the live loop into two explicit phases:

1. **Admission phase (non-gameplay publication):** poll the already-current `InputHandler` snapshot without advancing it; update the title-card overlay/animation and its native VBlank-only clock; inspect (do not consume) the Start edge; apply the pause toggle first so `PAUSED` retains pending setup authority; then classify/consume pending setup. Return `FrameAdmission(PAUSED|SETUP_ONLY|GAMEPLAY_FRAME)`.
2. **Gameplay phase:** only for `GAMEPLAY_FRAME`, call `timerManager.update`, inspect/apply debug shortcuts, publish held/logical controls to level events, call `userRecordingControls.beforeLevelFrame`, run `LevelFrameStep` gameplay work, and perform ordinary recording/frame bookkeeping.

For title release, the admission phase may advance the title-card provider animation and the production VBlank work belonging to that release iteration, call `PostTitleCardDestination`, and exit as `SETUP_ONLY`. It must not run level timer/debug/control publication. For no-title setup, the same typed admission consumes setup before those mutations. For `PAUSED`, preserve ROM pause semantics: the pause VInt window and final input cursor advance still occur, but gameplay timers/debug/publication and setup do not. For `SETUP_ONLY`, even the final `inputHandler.update()` and playback/user-recording cursor remain unchanged so the same input snapshot is presented to the first `GAMEPLAY_FRAME`.

Move or gate these current operations explicitly:

- `timerManager.update()` (current `GameLoop.java:975-977`) -> gameplay phase;
- debug/input shortcut inspection beginning near `:979` -> gameplay phase;
- `spriteManager.publishHeldInputForLevelEvents(inputHandler)` near `:1471` -> gameplay phase;
- Start-edge lookup may remain a read-only admission input, but its edge is not consumed on `SETUP_ONLY`;
- `userRecordingControls.beforeLevelFrame(inputHandler)` near `:1485` -> gameplay phase;
- final `inputHandler.update()`, previous-input snapshot, recording cursor, and external frame counter -> advance on `PAUSED`/`GAMEPLAY_FRAME` according to existing pause rules, never on `SETUP_ONLY`.

`TestGameLoop` must cover both title-release and no-title setup and assert unchanged timer value, unchanged published held/logical state, preserved Start edge, zero `beforeLevelFrame` calls, and unchanged final input cursor after `SETUP_ONLY`; its next call must observe the same Start/input snapshot and return `GAMEPLAY_FRAME`. `TestInGamePause` separately proves `PAUSED` advances only the existing pause input/VInt window while leaving setup pending.

### Restoration and rewind

Rename snapshot fields mechanically to `pendingInitialProcessSpritesLifecycle`. Rewind taken before setup restores `LOAD_THEN_PROCESS_ONCE`; rewind taken after setup restores `NONE`. Complete-run and state-restoration bootstraps continue to discard pending authority before applying represented state. A restore must never infer pending state from frame zero.

---

## Task 1: Capture and Approve the ROM Playable Oracle

**Files:**

- Create: `tools/bizhawk/s3k_initial_process_sprites_probe.lua`
- Create: `docs/architecture/audits/2026-07-26-s3k-initial-process-sprites-oracle.md`
- Generate but do not stage: `target/initial-process-sprites-oracle/aiz1.jsonl`
- Generate but do not stage: `target/initial-process-sprites-oracle/aiz1-console.txt`

- [ ] Verify the locked-on ROM hash is `CFBF98C36C776677290A872547AC47C53D2761D6` and confirm the setup call/return PCs by disassembling around `$00647E`; reject the capture if the ROM revision or PCs differ.
- [ ] Implement a read-only BizHawk Lua probe that pauses/logs at pre-call PC `$00647E`, post-return PC `$006484`, and the first ordinary player-slot entry. It may read 68K RAM/registers only; it must not write RAM, alter input, or create a trace fixture.
- [ ] Record P1/P2 centre and subpixel position, x/y/ground velocity, routine/status/status-secondary/control, animation id/previous/frame/timer, follower-history index and touched entry, CPU routine/state/target/timers, controller held/logical words, level/VInt/oscillation counters, collision-list count, and all fixed-slot function pointers/routine bytes.
- [ ] Run the exact attended command (substitute only the discovered ROM path and local BizHawk executable):

```bash
mkdir -p target/initial-process-sprites-oracle
EmuHawk <discovered-s3k-rom.gen> \
  --lua=tools/bizhawk/s3k_initial_process_sprites_probe.lua \
  > target/initial-process-sprites-oracle/aiz1-console.txt
```

The probe writes `target/initial-process-sprites-oracle/aiz1.jsonl`. Capture three labeled records: `ADJACENT_MINUS_ONE_PRE_SETUP`, `POST_INITIAL_PROCESS_SPRITES`, and `FIRST_LEVEL_LOOP_PLAYER_ENTRY`. The first label distinguishes the prior lifecycle's adjacent frame -1 epoch from the setup call; it is not a trace frame selector.

- [ ] Translate stable before/after facts into the audit Markdown with ROM addresses, values, and source citations. Commit the small Lua probe and audit, never the generated JSONL/console output or an uncompressed trace.
- [ ] Acceptance: the audit answers which P1/P2 fields mutate, proves P1 history precedes P2 CPU, identifies dust/tails/shield fixed-slot activation, confirms zero controller words and unchanged level/VInt/oscillation counters, and confirms the first ordinary epoch transition. If any answer is missing, implementation is blocked.
- [ ] Request ROM-fidelity review of the raw local artifact and staged audit before proceeding.
- [ ] Commit:

```bash
git add tools/bizhawk/s3k_initial_process_sprites_probe.lua
git add docs/architecture/audits/2026-07-26-s3k-initial-process-sprites-oracle.md
git commit -m "docs(s3k): capture initial Process_Sprites oracle"
```

---

## Task 2: Pin the Failing Engine Contracts

**Files:**

- Create: `src/test/java/com/openggf/level/TestInitialProcessSpritesCoordinator.java`
- Create: `src/test/java/com/openggf/sprites/managers/TestInitialPlayableProcessSpritesPass.java`
- Modify: `src/test/java/com/openggf/tests/TestS3kInitialObjectSetupLifecycle.java`
- Modify: `src/test/java/com/openggf/level/objects/TestObjectManagerInitialS3kSetupPass.java`
- Modify: `src/test/java/com/openggf/level/objects/TestObjectCollisionResponseList.java`

- [ ] Add a coordinator contract test whose fake `InitialProcessSpritesStages` collaborators append labels and assert exact order `LOAD, P1, P2, RESET, DYNAMIC_SLOT_3, DYNAMIC_SLOTS_4_92, FIXED, CAPTURE, CLOSE`.
- [ ] Add a failure-first playable test asserting neutral raw/logical P1/P2 input,
  P1's temporary-offset `Reset_Player_Position_Array` initialization before the later
  P2 slot, unchanged `Pos_table_index`, zero/initialized Tails CPU
  routine/targets/timers with no delayed-follow read during setup, animation/status
  mutation allowed, and unchanged published gameplay/VBlank counters. Pin P1-before-P2
  SST dispatch order independently; do not require an ordinary history-index increment
  or delayed CPU selection that the Task 1 oracle disproves.
- [ ] Pin the approved Task 1 oracle fields as source-cited assertions rather than the weak expectation that mutation is merely “allowed.”
- [ ] Add failure-first lifecycle tests proving `PAUSED`, `SETUP_ONLY`, and `GAMEPLAY_FRAME`, and proving the next invocation after setup produces the first gameplay frame.
- [ ] Add exception tests proving consume-before-dispatch and balanced scopes.
- [ ] Add collision-list tests for previous read view, reset-only-current-build, next-frame capture, SPECIAL edge latch, and ENEMY persistent-overlap polling.
- [ ] Keep the SPECIAL/ENEMY overlap assertions at their actual
  `ObjectTouchResponseController` owner in `TestTouchResponseManager`; slot-2
  collision-list reset must preserve that separately owned state.
- [ ] Retain the useful placement/load assertions from `TestObjectManagerInitialS3kSetupPass`, but rewrite their expected owner as the coordinator.
- [ ] Run:

```bash
mvn -Dmse=off -Dtest=TestInitialProcessSpritesCoordinator,TestInitialPlayableProcessSpritesPass,TestS3kInitialObjectSetupLifecycle,TestObjectManagerInitialS3kSetupPass,TestObjectCollisionResponseList,TestTouchResponseManager test
```

Expected: new tests fail for missing APIs/wrong object-only order; existing unrelated assertions remain green.

- [ ] Commit tests:

```bash
git add src/test/java/com/openggf/level/TestInitialProcessSpritesCoordinator.java \
  src/test/java/com/openggf/sprites/managers/TestInitialPlayableProcessSpritesPass.java \
  src/test/java/com/openggf/tests/TestS3kInitialObjectSetupLifecycle.java \
  src/test/java/com/openggf/level/objects/TestObjectManagerInitialS3kSetupPass.java \
  src/test/java/com/openggf/level/objects/TestObjectCollisionResponseList.java \
  src/test/java/com/openggf/level/objects/TestTouchResponseManager.java
git commit -m "test(s3k): pin initial Process_Sprites slot order"
```

---

## Task 3: Extract the Neutral Playable SST Envelope

**Files:**

- Create: `src/main/java/com/openggf/sprites/managers/PlayableSstDispatcher.java`
- Create: `src/main/java/com/openggf/sprites/managers/PlayableDispatchContext.java`
- Create: `src/main/java/com/openggf/sprites/managers/ProcessSpritesEpoch.java`
- Create: `src/main/java/com/openggf/sprites/managers/InitialPlayableInput.java`
- Modify: `src/main/java/com/openggf/sprites/managers/SpriteManager.java`
- Modify: `src/test/java/com/openggf/sprites/managers/TestInitialPlayableProcessSpritesPass.java`
- Modify only the exact focused SpriteManager input/CPU/history test files identified by the Task 2 review; record their names in the task checklist before staging.

- [x] Pre-change focused regression inventory (recorded before production edits):
  `TestSpriteManagerUpdateOrder`, `TestSpriteManagerDebugMovementModifiers`,
  `TestSpriteManagerDebugEmeraldGrant`, `TestSpriteManagerCollisionOrder`,
  `TestSidekickCpuControllerLevelStart`, and `TestSidekickCpuFollowParity`.
  These are audit/run targets; Task 3 changes none of them unless a demonstrated
  ordinary-update regression requires a narrowly reviewed expectation update.
- [x] Refactor ordinary `SpriteManager.update` and `updateWithoutInput` to call one canonical `processPlayableSlots(PlayableDispatchContext)` routine.
- [x] Implement `processInitialPlayableSlots` with zero P1/P2 raw and logical controls, no debug shortcuts, and a non-counted epoch.
- [x] Preserve P1-before-P2 ordering, `recordFollowerHistoryForTick`, CPU target reads, animation/status/touch eligibility, deferred cross-playable mutation, water ordering, and temporary-sidekick sweep only where the ROM player routine owns them.
- [x] Prove the setup API does not publish a new `SpriteManager.frameCounter` value, sample `InputHandler`, or consume queued user input.
- [x] Prove queued object-control state applies, while raw/logical input, debug shortcuts, BK2 edges, and P2 manual input remain neutral; forced runtime controls and control locks remain semantic inputs rather than being cleared.
- [x] Audit and replace every reachable direct manager-counter read with explicit epoch/ordinal use; assert setup `(level=0, sprite=0, object=1)` and first ordinary `(1,1,2)`.
- [x] Run:

```bash
mvn -Dmse=off -Dtest=TestInitialPlayableProcessSpritesPass,TestSpriteManager*,TestSidekickCpu*,TestFollowerHistory* test
```

- [x] Request two-stage review: first against the ROM player-slot order and setup input epoch; second for shared-update regressions and absence of game/zone/trace selectors.
- [ ] Commit:

```bash
git add src/main/java/com/openggf/sprites/managers/PlayableDispatchContext.java \
  src/main/java/com/openggf/sprites/managers/ProcessSpritesEpoch.java \
  src/main/java/com/openggf/sprites/managers/PlayableSstDispatcher.java \
  src/main/java/com/openggf/sprites/managers/InitialPlayableInput.java \
  src/main/java/com/openggf/sprites/managers/SpriteManager.java \
  src/test/java/com/openggf/sprites/managers/TestInitialPlayableProcessSpritesPass.java
# Add only the individually reviewed existing test files recorded above.
git commit -m "refactor(sprites): expose initial playable SST dispatch"
```

---

## Task 4: Build the Unified Coordinator and Migrate Object Dispatch

**Files:**

- Create: `src/main/java/com/openggf/level/InitialProcessSpritesCoordinator.java`
- Create: `src/main/java/com/openggf/level/InitialProcessSpritesContext.java`
- Create: `src/main/java/com/openggf/level/InitialProcessSpritesStages.java`
- Create: `src/main/java/com/openggf/level/InitialDynamicSstDispatcher.java`
- Create: `src/main/java/com/openggf/level/CollisionListSstDispatcher.java`
- Create: `src/main/java/com/openggf/level/InitialFixedSstDispatcher.java`
- Create: `src/main/java/com/openggf/level/objects/InitialObjectDispatchScope.java`
- Modify: `src/main/java/com/openggf/level/objects/ObjectManager.java`
- Modify: `src/main/java/com/openggf/level/objects/ObjectCollisionResponseList.java`
- Modify: `src/main/java/com/openggf/level/LevelManager.java`
- Modify: `src/main/java/com/openggf/game/sonic3k/Sonic3kLevelEventManager.java`
- Modify: `src/main/java/com/openggf/game/sonic3k/S3kFixedAirCountdownManager.java`
- Modify: `src/main/java/com/openggf/sprites/managers/SpriteManager.java`
- Modify: `src/main/java/com/openggf/game/sonic3k/Sonic3kWaterSurfaceManager.java`
- Modify: `src/main/java/com/openggf/game/sonic3k/Sonic3kZoneFeatureProvider.java`
- Modify: `src/main/java/com/openggf/game/sonic3k/Sonic3kGameModule.java`
- Create: `src/main/java/com/openggf/game/sonic3k/InitialWaveSplashSstOwner.java`
- Modify: `src/test/java/com/openggf/level/TestInitialProcessSpritesCoordinator.java`
- Modify: `src/test/java/com/openggf/level/objects/TestObjectManagerInitialS3kSetupPass.java`
- Modify: `src/test/java/com/openggf/level/objects/TestObjectCollisionResponseList.java`
- Create: `src/test/java/com/openggf/game/sonic3k/TestS3kInitialFixedSstDispatcher.java`
- Modify: `src/test/java/com/openggf/game/sonic3k/TestSonic3kZoneFeatureProvider.java`

- [ ] Implement fakeable stage collaborators and explicit order `LOAD -> P1 -> P2 -> RESET -> DYNAMIC_SLOT_3 -> DYNAMIC_SLOTS_4_92 -> FIXED -> CAPTURE`, with scope close in `finally`.
- [ ] Prove fresh absolute dynamic slot 3 is empty from the Task 1 oracle and visit it explicitly before the managed 4-93 scan; assert the adapter fails if a slot-3 owner unexpectedly appears.
- [ ] Split the current `runInitialS3kLoadThenExecutePass` into semantic scoped primitives; remove the S3K-named public method after all callers migrate.
- [ ] Keep two-axis placement, active-spawn synchronization, destroyed-object cleanup, child allocation, solid registry, and next-frame collision-response capture.
- [ ] Implement the inventory's 17-slot S3K provider adapter. Every slot receives an explicit registered-owner dispatch or evidence-backed empty operation; preserve strict order.
- [ ] Construct the fixed adapter in `Sonic3kGameModule`; inject the `Sonic3kZoneFeatureProvider.initialWaveSplashSstOwner()` bridge and test registered HCZ versus explicit-empty AIZ/CNZ without event-manager zone discovery.
- [ ] Verify collision reset occurs before dynamic publication and fixed tails/dust/shields occur after dynamic execution.
- [ ] Implement the exact previous/current collision-list contract and preserve overlap latches across reset.
- [ ] Persist `ObjectManager.frameCounter` 0→1 for setup while all gameplay counters remain zero.
- [ ] Run:

```bash
mvn -Dmse=off -Dtest=TestInitialProcessSpritesCoordinator,TestObjectManagerInitialS3kSetupPass,TestObjectCollisionResponseList,TestS3kInitialFixedSstDispatcher,TestSonic3kZoneFeatureProvider,TestObjectManager*,TestSonic3kObjectSlotRecorder test
```

- [ ] Review for scope closure on every exception path, stable child allocation, and zero game/zone/trace branching.
- [ ] Commit:

```bash
git add src/main/java/com/openggf/level/InitialProcessSpritesCoordinator.java \
  src/main/java/com/openggf/level/InitialProcessSpritesContext.java \
  src/main/java/com/openggf/level/InitialProcessSpritesStages.java \
  src/main/java/com/openggf/level/InitialDynamicSstDispatcher.java \
  src/main/java/com/openggf/level/CollisionListSstDispatcher.java \
  src/main/java/com/openggf/level/InitialFixedSstDispatcher.java \
  src/main/java/com/openggf/level/objects/InitialObjectDispatchScope.java \
  src/main/java/com/openggf/level/objects/ObjectManager.java \
  src/main/java/com/openggf/level/objects/ObjectCollisionResponseList.java \
  src/main/java/com/openggf/level/LevelManager.java \
  src/main/java/com/openggf/game/sonic3k/Sonic3kLevelEventManager.java \
  src/main/java/com/openggf/game/sonic3k/S3kFixedAirCountdownManager.java \
  src/main/java/com/openggf/sprites/managers/SpriteManager.java \
  src/main/java/com/openggf/game/sonic3k/Sonic3kWaterSurfaceManager.java \
  src/main/java/com/openggf/game/sonic3k/Sonic3kZoneFeatureProvider.java \
  src/main/java/com/openggf/game/sonic3k/Sonic3kGameModule.java \
  src/main/java/com/openggf/game/sonic3k/InitialWaveSplashSstOwner.java \
  src/test/java/com/openggf/level/TestInitialProcessSpritesCoordinator.java \
  src/test/java/com/openggf/level/objects/TestObjectManagerInitialS3kSetupPass.java \
  src/test/java/com/openggf/level/objects/TestObjectCollisionResponseList.java \
  src/test/java/com/openggf/game/sonic3k/TestS3kInitialFixedSstDispatcher.java \
  src/test/java/com/openggf/game/sonic3k/TestSonic3kZoneFeatureProvider.java
git commit -m "feat(s3k): unify initial Process_Sprites dispatch"
```

---

## Task 5: Rename and Wire the Typed Lifecycle

**Files:**

- Delete: `src/main/java/com/openggf/game/InitialObjectSetupLifecycle.java`
- Create: `src/main/java/com/openggf/game/InitialProcessSpritesLifecycle.java`
- Delete: `src/main/java/com/openggf/level/InitialObjectSetupCoordinator.java`
- Create: `src/main/java/com/openggf/level/InitialProcessSpritesLifecycleCoordinator.java`
- Modify: `src/main/java/com/openggf/game/LevelInitProfile.java`
- Modify: `src/main/java/com/openggf/game/LevelLoadContext.java`
- Modify: `src/main/java/com/openggf/game/sonic3k/Sonic3kLevelInitProfile.java`
- Modify: `src/main/java/com/openggf/level/LevelManager.java`
- Modify: `src/main/java/com/openggf/PostTitleCardDestination.java`
- Create: `src/main/java/com/openggf/LevelFrameResult.java`
- Create: `src/main/java/com/openggf/FrameAdmission.java`
- Modify: `src/main/java/com/openggf/LevelFrameStep.java`
- Modify: `src/main/java/com/openggf/GameLoop.java`
- Modify: `src/main/java/com/openggf/tools/RecordingFrameDriver.java`
- Modify: `src/main/java/com/openggf/game/rewind/LiveRewindStepper.java`
- Modify: `src/main/java/com/openggf/TraceSessionLauncher.java`
- Modify: `src/main/java/com/openggf/tools/TraceCaptureTool.java`
- Modify: `src/test/java/com/openggf/tests/HeadlessTestRunner.java`
- Modify: `src/test/java/com/openggf/TestGameLoop.java`
- Modify: `src/test/java/com/openggf/tools/TestRecordingFrameDriverInputOnly.java`
- Modify: `src/test/java/com/openggf/game/TestInGamePause.java`
- Modify: `src/test/java/com/openggf/game/TestPostLoadAssemblyBehavior.java`
- Modify: `src/test/java/com/openggf/tests/TestTitleCardObjectExecution.java`
- Modify: `src/test/java/com/openggf/tests/TestS3kInitialObjectSetupLifecycle.java`
- Modify: the four direct test callers named in the lifecycle call-site table, if still direct after refactoring

- [ ] Rename the lifecycle API and value to `InitialProcessSpritesLifecycle.LOAD_THEN_PROCESS_ONCE`.
- [ ] Preserve the `FULL + post-load + FRESH_LEVEL_ASSEMBLY` arming gate and publish only after a successful load.
- [ ] Keep title-card release as an immediate consumer followed by a later ordinary iteration.
- [ ] Implement mandatory `PAUSED`, `SETUP_ONLY`, `GAMEPLAY_FRAME` results on both `execute` overloads and `executeWithPause`; only the deepest overload consumes the token.
- [ ] Implement two-phase `FrameAdmission` before `GameLoop` timer/debug/input publication. Move/gate timer update, debug inspection, held/logical publication, recording controls, and final input cursor exactly as specified; keep only title animation/native VBlank and read-only Start inspection in admission.
- [ ] Update every production/test caller enumerated above. Title release and `SETUP_ONLY` exit the outer iteration; recording/headless/trace/rewind callers retry the same input/row and do not advance gameplay timers, cursor, counter, capture, or keyframe.
- [ ] Prove pause retains pending authority and teardown/failed load clears it.
- [ ] Prove S1/S2 profiles remain `NONE` and preview/warm/shared loads cannot arm it.
- [ ] Run:

```bash
mvn -Dmse=off -Dtest=TestS3kInitialObjectSetupLifecycle,TestPostLoadAssemblyBehavior,TestTitleCardObjectExecution,TestLevelFrameStep*,TestGameLoop* test
mvn -Dmse=off -Dtest=TestRecordingFrameDriverInputOnly,TestInGamePause,TestTraceCaptureTool*,TestLiveRewindStepper* test
```

- [ ] Review all references with:

```bash
rg -n "InitialObjectSetup|runInitialS3kLoadThenExecutePass|consumePendingInitialObjectSetup" src/main src/test
```

Expected: no stale production API references.

- [ ] Commit:

```bash
git add src/main/java/com/openggf/game/InitialProcessSpritesLifecycle.java \
  src/main/java/com/openggf/game/InitialObjectSetupLifecycle.java \
  src/main/java/com/openggf/level/InitialProcessSpritesLifecycleCoordinator.java \
  src/main/java/com/openggf/level/InitialObjectSetupCoordinator.java \
  src/main/java/com/openggf/game/LevelInitProfile.java \
  src/main/java/com/openggf/game/LevelLoadContext.java \
  src/main/java/com/openggf/game/sonic3k/Sonic3kLevelInitProfile.java \
  src/main/java/com/openggf/level/LevelManager.java \
  src/main/java/com/openggf/PostTitleCardDestination.java \
  src/main/java/com/openggf/LevelFrameResult.java \
  src/main/java/com/openggf/FrameAdmission.java \
  src/main/java/com/openggf/LevelFrameStep.java \
  src/main/java/com/openggf/GameLoop.java \
  src/main/java/com/openggf/tools/RecordingFrameDriver.java \
  src/main/java/com/openggf/game/rewind/LiveRewindStepper.java \
  src/main/java/com/openggf/TraceSessionLauncher.java \
  src/main/java/com/openggf/tools/TraceCaptureTool.java \
  src/test/java/com/openggf/tests/HeadlessTestRunner.java \
  src/test/java/com/openggf/TestGameLoop.java \
  src/test/java/com/openggf/tools/TestRecordingFrameDriverInputOnly.java \
  src/test/java/com/openggf/game/TestInGamePause.java \
  src/test/java/com/openggf/game/TestPostLoadAssemblyBehavior.java \
  src/test/java/com/openggf/tests/TestTitleCardObjectExecution.java \
  src/test/java/com/openggf/tests/TestS3kInitialObjectSetupLifecycle.java
# Add only named direct-caller tests actually modified.
git commit -m "refactor(level): own initial Process_Sprites lifecycle"
```

---

## Task 6: Preserve Bootstrap, Restoration, and Rewind Semantics

**Files:**

- Modify: `src/main/java/com/openggf/trace/replay/TraceReplaySessionBootstrap.java`
- Modify: `src/main/java/com/openggf/game/rewind/snapshot/LevelSnapshot.java`
- Modify: `src/main/java/com/openggf/game/rewind/snapshot/SpriteManagerSnapshot.java`
- Modify: `src/main/java/com/openggf/game/rewind/snapshot/ObjectManagerSnapshot.java`
- Modify: `src/main/java/com/openggf/level/rewind/LevelRewindSnapshotAdapter.java`
- Modify: `src/main/java/com/openggf/level/objects/ObjectManager.java` capture/restore adapter
- Modify: `src/main/java/com/openggf/game/sonic3k/Sonic3kLevelEventManager.java` fixed-air rewind serialization
- Modify: `src/test/java/com/openggf/level/TestLevelManagerRewindSnapshot.java`
- Modify: `src/test/java/com/openggf/level/rewind/TestLevelRewindSnapshotAdapter.java`
- Modify: `src/test/java/com/openggf/game/rewind/TestRewindBenchmarkSizeEstimator.java`
- Create: `src/test/java/com/openggf/level/TestInitialProcessSpritesPartialRewind.java`
- Modify only exact trace bootstrap/restoration tests identified with `rg -l "discardPendingInitial|consumePendingInitial" src/test/java`; record names before staging

- [ ] Rename snapshot fields and adapter calls without changing pending-state meaning.
- [ ] Preserve bootstrap order: initialize production counter phases, consume unified setup, then install comparison frame-zero RNG.
- [ ] Preserve complete-run/state-restoration discard before applying represented state.
- [ ] Add tests for rewind before setup (`LOAD_THEN_PROCESS_ONCE` restored), rewind after setup (`NONE` restored), and restore-to-frame-zero (no inference/rearming).
- [ ] Inventory and snapshot every mutable setup field: explicit epoch if stored, persistent object dispatch ordinal, playable/CPU/history/animation state, ordered collision `previousObjects` ids, `usePrevious`, current build/cursor, frame-start touch view, ENEMY/SPECIAL overlap latches, fixed-air controller state, dust/tails/shield/star/wave owner state, and open-scope/stage marker if retained.
- [ ] Expand `ObjectManagerSnapshot` itself with ordered `previousCollisionObjectIds`, ordered `currentCollisionBuildObjectIds`, `usePreviousCollisionList`, current build cursor/stage, and existing `TouchResponseOverlapState`. Store ids through the established rewind-id mapping, never object references.
- [ ] Audit every canonical and convenience constructor/call site with `rg -n "new ObjectManagerSnapshot" src/main src/test`; update all arguments/defaults and add a compilation test for each convenience path.
- [ ] Update `TestRewindBenchmarkSizeEstimator` for the expanded object-manager schema and pin ordered previous/current id round trips, not only lifecycle enum size.
- [ ] Inject a throw after P1/P2-before-reset and after dynamic-before-fixed. Capture/restore each partial state with lifecycle authority consumed and scopes closed; restoration reproduces the partial state and never reruns setup.
- [ ] Cover snapshots immediately before setup, both partial failure seams, after successful setup, and after the first ordinary frame.
- [ ] Add a metadata-variance test proving trace profile/filename/zone fields cannot affect arming or execution.
- [ ] Run:

```bash
mvn -Dmse=off -Dtest=TestLevelManagerRewindSnapshot,TestLevelRewindSnapshotAdapter,TestInitialProcessSpritesPartialRewind,TestRewindBenchmarkSizeEstimator,TestTraceReplaySessionBootstrap*,TestS3kInitialObjectSetupLifecycle test
```

- [ ] Run architecture guards:

```bash
mvn -Dmse=off -Dtest=TestArchitecturalSourceGuard,TestRewindCoverageGuard,TestStaticStateRewindCoverageGuard test
```

- [ ] Commit:

```bash
git add src/main/java/com/openggf/trace/replay/TraceReplaySessionBootstrap.java \
  src/main/java/com/openggf/game/rewind/snapshot/LevelSnapshot.java \
  src/main/java/com/openggf/game/rewind/snapshot/SpriteManagerSnapshot.java \
  src/main/java/com/openggf/game/rewind/snapshot/ObjectManagerSnapshot.java \
  src/main/java/com/openggf/level/rewind/LevelRewindSnapshotAdapter.java \
  src/main/java/com/openggf/level/objects/ObjectManager.java \
  src/main/java/com/openggf/game/sonic3k/Sonic3kLevelEventManager.java \
  src/test/java/com/openggf/level/TestLevelManagerRewindSnapshot.java \
  src/test/java/com/openggf/level/rewind/TestLevelRewindSnapshotAdapter.java \
  src/test/java/com/openggf/level/TestInitialProcessSpritesPartialRewind.java \
  src/test/java/com/openggf/game/rewind/TestRewindBenchmarkSizeEstimator.java
# Add only the individually recorded trace bootstrap/restoration tests.
git commit -m "fix(rewind): preserve initial Process_Sprites authority"
```

---

## Task 7: Validate Measured Frontiers and Cross-Mode Canaries

**Files:**

- Modify: `docs/status/trace-frontier-log.md`
- Modify: `CHANGELOG.md`
- Modify only if evidence changes an approved discrepancy: `docs/status/known-discrepancies.md` or `docs/status/s3k-known-discrepancies.md`
- Modify tests only for a genuine ROM-backed invariant, never to bless a new error count

Baseline provenance is integration HEAD `5d037438c` plus the attended diagnostics recorded in the parent task: standalone AIZ 1330 errors first frame 719 (`x`, expected `0x0040`, actual `0x0050`), composed CNZ first frame 1808, and AIZ complete first frame 25039 (historically 28 errors). Preserve the raw Maven/Surefire reports for each focused run under `target/surefire-reports`; the frontier log cites the specific `TEST-*.xml`/`.txt` report, not only aggregate Maven exit status.

- [ ] Discover the actual locked-on S3K ROM in the worktree root and pass its path through the existing Maven property.
- [ ] Run standalone AIZ. Required result: the first mismatch moves later than frame 719. Record the actual error count/frame/field as measurement; do not encode a target frame in production or assertions.
- [ ] Run standalone CNZ. Canary: preserve or advance the composed frontier currently measured at frame 1808.
- [ ] Run AIZ complete. Canary: preserve or advance the current first mismatch at frame 25039 (historically 28 errors).
- [ ] Run the full standalone AIZ class, not only `replayMatchesTrace`, so all scenario assertions gate the change.
- [ ] Run all S3K complete-run replay tests and record first-error measurements.
- [ ] Run special/bonus stage suites to prove the level lifecycle did not leak into their loops.
- [ ] Run S1 and S2 trace-replay suites to prove the default `NONE` path is inert.
- [ ] Run:

```bash
mvn -Dmse=off -Dtest='*TraceReplay' test
mvn -Dmse=off -Dtest='*CompleteRun*TraceReplay' test
mvn -Dmse=off -Dtest='*SpecialStage*,*BonusStage*' test
mvn -Dmse=off test
```

- [ ] Expected-red interpretation: wildcard Maven failure is not itself a regression. Parse every `target/surefire-reports/TEST-*TraceReplay*.xml` and associated text report, compare per-class error count and first frame/field with the baseline log, and fail the task only for a new red class or an earlier/worse frontier not justified by ROM evidence. Missing/aborted classes are failures, never “expected red.”
- [ ] Include the concurrent user path contracts in the guard canary and preserve their staged changes: `TestRewindRoundTripProbe` must write `docs/status/rewind-round-trip-gaps.md`; `TestArchitecturalSourceGuard` must read `docs/status/known-discrepancies.md`. Do not restore old filenames or overwrite the other agent's staged edits.
- [ ] Classify every change from baseline as ROM-supported improvement, unrelated flake, or regression. AIZ frame 719 moving later is necessary but not sufficient.
- [ ] If any canary moves earlier, restore the last reviewed commit and narrow the failing stage with the focused coordinator/playable/object tests. Do not add a profile/zone exception.
- [ ] Update the frontier log with exact command, commit/worktree, pass/fail, error count, and first frame/field.
- [ ] Commit:

```bash
git add CHANGELOG.md docs/status/trace-frontier-log.md
# Add a discrepancy file only when this task actually changed that exact file.
git commit -m "docs(trace): record unified setup frontiers"
```

---

## Task 8: Final Review, Policy, and Rollback Readiness

**Files:**

- Review all files changed in Tasks 1-6
- Modify only documentation/tests needed to resolve review findings

- [ ] Run selector audit:

```bash
rg -n "trace_profile|traceProfile|traceName|filename|zoneName|frameIndex|known failing|AIZ|CNZ" \
  src/main/java/com/openggf/level \
  src/main/java/com/openggf/sprites/managers \
  src/main/java/com/openggf/trace/replay
```

Every match in the new path must be a citation, diagnostic label, or existing unrelated code—not an execution selector.

- [ ] Run counter audit: prove setup leaves level, VBlank/VInt, lag, oscillation, ring-frame, and input cursors unchanged while allowing native player/CPU/animation/history/object mutations.
- [ ] Run slot audit: prove `LOAD -> P1 -> P2 -> RESET -> explicit slot 3 -> managed slots 4-93 -> fixed 94-109 -> CAPTURE` and verify the AIZ intro occupies its native dynamic position without a named special case.
- [ ] Run lifecycle audit across title, no-title, paused, bootstrap, failed load, teardown, preview, warm reuse, complete restoration, rewind-before, and rewind-after cases.
- [ ] Request two independent reviews:
  1. ROM fidelity and slot/counter/input ownership.
  2. Architecture, restoration/rewind safety, selectors, and regression-gate completeness.
- [ ] Run final gates:

```bash
mvn -Dmse=off -Dtest=TestArchitecturalSourceGuard,TestTraceFixtureCompressionGuard,TestRewindCoverageGuard,TestStaticStateRewindCoverageGuard test
mvn -Dmse=off test
git diff --check
git status --short
```

- [ ] Confirm `AGENTS.md` and `CLAUDE.md` remain synchronized if either was touched; neither should need modification for this implementation.
- [ ] Confirm all architecture/status artifacts created during implementation are staged.

---

## Rollback Strategy

Rollback is by reviewed task boundary, not by runtime flag:

1. If playable extraction changes ordinary gameplay, revert Task 3 and keep the Task 1 oracle and Task 2 failing tests. Do not reintroduce the object-only assumption.
2. If unified object/fixed ordering is wrong, revert Task 4 and correct the explicit slot adapter from ROM evidence.
3. If title/no-title iteration ownership regresses, revert Task 5 while retaining the coordinator tests; do not make setup and gameplay share one outer iteration.
4. If restoration or rewind repeats setup, revert Task 6 and restore the prior lifecycle field until the renamed snapshot contract is corrected.
5. If AIZ, CNZ, or complete-run canaries move earlier, revert to the last reviewed task commit and isolate whether the cause is playable mutation, slot order, counter epoch, or lifecycle timing.

Do not roll back by adding a trace, zone, act, character, profile, frame, or error-count exception. Do not restore the old `ObjectManager`-only primitive as the final design: the AIZ and sidekick-history evidence demonstrates that it cannot represent the ROM setup pass.

## Completion Criteria

- One fresh S3K level assembly publishes one typed `LOAD_THEN_PROCESS_ONCE` authority.
- Exactly one consumer executes exactly one setup-only `Process_Sprites` pass.
- Every frame-step entry point returns exactly one of `PAUSED`, `SETUP_ONLY`, or `GAMEPLAY_FRAME`; title, live, recording, headless, trace/capture, and rewind callers honor its cursor/counter contract.
- The pass uses neutral controller input and native `LOAD -> P1 -> P2 -> RESET -> DYNAMIC_SLOT_3 -> DYNAMIC_SLOTS_4_92 -> FIXED -> CAPTURE` order.
- All 17 fixed slots have a tested game-provider owner or an explicit evidence-backed empty operation.
- Collision reset preserves the previous player-touch view and overlap latches, clears only current build state, and publishes the completed setup list for the first ordinary frame.
- Player/sidekick physics, history, CPU, animation, collision, touch, and status mutate only where their native player routines require.
- Setup observes `(LevelManager=0, SpriteManager=0, ObjectManager dispatch ordinal=1)` and first ordinary observes `(1,1,2)`; no increment/restore is used.
- Ordinary level/VBlank/oscillation/ring/camera/event/input/rewind-frame counters do not advance during setup.
- Title, no-title, pause, bootstrap, restoration, rewind, failure, preview, and warm-reuse ownership is test-covered.
- Rewind reproduces before, partial-after-playables, partial-after-dynamic, successful-setup, and first-ordinary state without replaying consumed work.
- Standalone AIZ moves later than frame 719; CNZ does not regress before frame 1808; AIZ complete does not regress before frame 25039.
- Full S3K standalone/complete, special/bonus, S1, S2, architecture, rewind, compression, and Maven suites pass or improve without a prohibited selector.
- Frontier and changelog documentation records actual measured outcomes.
